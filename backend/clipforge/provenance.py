"""Ownership & provenance: hashing, manifests, Ed25519 signing and verification.

What this does:  proves that a manifest was signed by the holder of a specific
private key and that files match the hashes recorded in it.
What it does NOT do: prevent copying, or establish copyright ownership.
"""
from __future__ import annotations

import base64
import hashlib
import json
import os
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, BinaryIO, Iterable, Optional

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import (Ed25519PrivateKey,
                                                               Ed25519PublicKey)

from . import APP_NAME, APP_VERSION, MANIFEST_VERSION

PRIVATE_KEY_FILE = "clipforge_ed25519_private.pem"
PUBLIC_KEY_FILE = "clipforge_ed25519_public.pem"
CANONICALIZATION = "json:sort_keys,separators=(',',':'),utf-8"
SIGNATURE_ALGORITHM = "Ed25519"
CHUNK = 1024 * 1024

DISCLAIMER = ("This report verifies the integrity of files against a signed CLIPFORGE "
              "manifest and identifies which key signed it. It does not prevent copying "
              "and does not by itself establish copyright ownership.")


# ----------------------------------------------------------------- hashing
def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(CHUNK), b""):
            h.update(chunk)
    return h.hexdigest()


def sha256_stream(stream: BinaryIO, limit: Optional[int] = None) -> tuple[str, int]:
    h = hashlib.sha256()
    total = 0
    for chunk in iter(lambda: stream.read(CHUNK), b""):
        total += len(chunk)
        if limit is not None and total > limit:
            raise ValueError("stream exceeds size limit")
        h.update(chunk)
    return h.hexdigest(), total


def canonical_bytes(obj: Any) -> bytes:
    return json.dumps(obj, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False, allow_nan=False).encode("utf-8")


def iso(ts: float) -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(ts))


# -------------------------------------------------------------------- keys
@dataclass
class KeyInfo:
    key_id: str
    public_key_pem: str
    fingerprint: str
    created_at: str
    private_key_path: str


class KeyStore:
    """Loads or creates the installation's Ed25519 key pair on local disk.

    The private key file is written with mode 0600 inside a 0700 directory and is
    never returned by any API.
    """

    def __init__(self, key_dir: Path):
        self.key_dir = key_dir
        self._lock = threading.Lock()
        self._private: Optional[Ed25519PrivateKey] = None

    @property
    def private_path(self) -> Path:
        return self.key_dir / PRIVATE_KEY_FILE

    @property
    def public_path(self) -> Path:
        return self.key_dir / PUBLIC_KEY_FILE

    def _load_or_create(self) -> Ed25519PrivateKey:
        with self._lock:
            if self._private is not None:
                return self._private
            self.key_dir.mkdir(parents=True, exist_ok=True)
            os.chmod(self.key_dir, 0o700)
            if self.private_path.exists():
                key = serialization.load_pem_private_key(self.private_path.read_bytes(),
                                                         password=None)
                if not isinstance(key, Ed25519PrivateKey):
                    raise RuntimeError("Signing key file is not an Ed25519 private key")
            else:
                key = Ed25519PrivateKey.generate()
                pem = key.private_bytes(serialization.Encoding.PEM,
                                        serialization.PrivateFormat.PKCS8,
                                        serialization.NoEncryption())
                fd = os.open(self.private_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                with os.fdopen(fd, "wb") as fh:
                    fh.write(pem)
                self.public_path.write_bytes(public_pem(key.public_key()).encode())
            self._private = key
            return key

    def public_key(self) -> Ed25519PublicKey:
        return self._load_or_create().public_key()

    def info(self) -> KeyInfo:
        pub = self.public_key()
        return KeyInfo(
            key_id=key_id(pub),
            public_key_pem=public_pem(pub),
            fingerprint=fingerprint(pub),
            created_at=iso(self.private_path.stat().st_mtime),
            private_key_path=str(self.private_path),
        )

    def sign(self, data: bytes) -> bytes:
        return self._load_or_create().sign(data)


def public_pem(pub: Ed25519PublicKey) -> str:
    return pub.public_bytes(serialization.Encoding.PEM,
                            serialization.PublicFormat.SubjectPublicKeyInfo).decode()


def _raw(pub: Ed25519PublicKey) -> bytes:
    return pub.public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)


def key_id(pub: Ed25519PublicKey) -> str:
    return hashlib.sha256(_raw(pub)).hexdigest()[:16]


def fingerprint(pub: Ed25519PublicKey) -> str:
    digest = hashlib.sha256(_raw(pub)).hexdigest()
    return ":".join(digest[i:i + 4] for i in range(0, 32, 4))


def load_public_pem(pem: str | bytes) -> Ed25519PublicKey:
    data = pem.encode() if isinstance(pem, str) else pem
    key = serialization.load_pem_public_key(data)
    if not isinstance(key, Ed25519PublicKey):
        raise ValueError("Public key is not Ed25519")
    return key


# --------------------------------------------------------------- manifests
def build_manifest(*, job: dict, settings: dict, media: dict, plan: dict, clips: list[dict],
                   ffmpeg_version: str, owner: Optional[dict] = None) -> dict:
    manifest: dict[str, Any] = {
        "manifest_version": MANIFEST_VERSION,
        "generator": {"name": APP_NAME, "version": APP_VERSION, "ffmpeg": ffmpeg_version,
                      "ai_used": False},
        "project_id": job["id"],
        "project_name": job["name"],
        "created_at": iso(time.time()),
        "source": {
            "asset_id": job["source_asset_id"],
            "sha256": job["source_sha256"],
            "size_bytes": job["source_size"],
            "original_filename": job["source_filename"],
            "ingested_at": iso(job["created_at"]),
            "media": {k: media.get(k) for k in ("container", "duration", "width", "height",
                                                "rotation", "fps", "video_codec",
                                                "has_audio", "audio_codec")},
        },
        "processing": {
            "settings": settings,
            "segmentation": plan,
            "encoder_used": job.get("encoder_used"),
        },
        "outputs": [
            {
                "asset_id": c["id"],
                "parent_asset_id": job["source_asset_id"],
                "relationship": "derived_segment",
                "index": c["idx"],
                "path": f"clips/{c['filename']}",
                "sha256": c["sha256"],
                "size_bytes": c["size_bytes"],
                "created_at": iso(c["created_at"]),
                "source_start": c["start"],
                "source_end": c["end"],
                "duration": c["duration"],
                "width": c["width"],
                "height": c["height"],
                "has_audio": bool(c["has_audio"]),
                "boundary": {"start": c["start_kind"], "end": c["end_kind"]},
            }
            for c in clips
        ],
    }
    if owner:
        manifest["owner"] = owner
    return manifest


def sign_manifest(manifest: dict, keys: KeyStore) -> dict:
    pub = keys.public_key()
    sig = keys.sign(canonical_bytes(manifest))
    return {
        "manifest": manifest,
        "signature": {
            "algorithm": SIGNATURE_ALGORITHM,
            "canonicalization": CANONICALIZATION,
            "key_id": key_id(pub),
            "public_key_pem": public_pem(pub),
            "value": base64.b64encode(sig).decode(),
        },
    }


def verify_signed_manifest(doc: Any, trusted: Optional[Ed25519PublicKey] = None) -> dict:
    """Check a signed manifest document. Never raises for bad input."""
    result: dict[str, Any] = {"valid": False, "algorithm": None, "key_id": None,
                              "trusted_key": False, "error": None}
    try:
        if not isinstance(doc, dict) or "manifest" not in doc or "signature" not in doc:
            raise ValueError("Not a signed CLIPFORGE manifest (missing 'manifest' or 'signature')")
        sig = doc["signature"]
        if not isinstance(sig, dict):
            raise ValueError("Malformed signature block")
        result["algorithm"] = sig.get("algorithm")
        if sig.get("algorithm") != SIGNATURE_ALGORITHM:
            raise ValueError(f"Unsupported signature algorithm: {sig.get('algorithm')}")
        embedded = load_public_pem(sig.get("public_key_pem", ""))
        result["key_id"] = key_id(embedded)
        if sig.get("key_id") and sig["key_id"] != result["key_id"]:
            raise ValueError("key_id does not match the embedded public key")
        verifier = trusted or embedded
        if trusted is not None:
            result["trusted_key"] = _raw(trusted) == _raw(embedded)
            if not result["trusted_key"]:
                result["error"] = "Manifest was signed by a different key than the trusted key"
        signature = base64.b64decode(sig.get("value", ""), validate=True)
        verifier.verify(signature, canonical_bytes(doc["manifest"]))
        result["valid"] = True
    except InvalidSignature:
        result["valid"] = False
        result["error"] = result["error"] or (
            "Signature does not match manifest contents (manifest modified or wrong key)")
    except Exception as exc:  # noqa: BLE001 - report every failure to the caller
        result["valid"] = False
        result["error"] = result["error"] or str(exc)
    return result


def match_files(manifest: dict, files: Iterable[tuple[str, str]]) -> list[dict]:
    """files: (name, sha256). Matches each to a manifest output or the source."""
    by_hash: dict[str, dict] = {}
    by_path: dict[str, dict] = {}
    for out in manifest.get("outputs", []) or []:
        by_hash[out.get("sha256")] = out
        by_path[out.get("path", "").split("/")[-1]] = out
    src = manifest.get("source") or {}
    results = []
    for name, digest in files:
        base = name.replace("\\", "/").split("/")[-1]
        if digest in by_hash:
            out = by_hash[digest]
            results.append({"name": name, "sha256": digest, "status": "match", "role": "output",
                            "asset_id": out.get("asset_id"), "expected_sha256": out.get("sha256")})
        elif src.get("sha256") == digest:
            results.append({"name": name, "sha256": digest, "status": "match", "role": "source",
                            "asset_id": src.get("asset_id"), "expected_sha256": digest})
        elif base in by_path:
            out = by_path[base]
            results.append({"name": name, "sha256": digest, "status": "modified", "role": "output",
                            "asset_id": out.get("asset_id"), "expected_sha256": out.get("sha256")})
        else:
            results.append({"name": name, "sha256": digest, "status": "unknown", "role": None,
                            "asset_id": None, "expected_sha256": None})
    return results


def build_report(signed_doc: Any, signature_result: dict, file_results: list[dict],
                 missing: Optional[list[dict]] = None) -> dict:
    manifest = signed_doc.get("manifest", {}) if isinstance(signed_doc, dict) else {}
    missing = missing or []
    ok = (signature_result.get("valid") is True
          and all(f["status"] == "match" for f in file_results)
          and not missing)
    return {
        "report_version": "1.0",
        "generator": {"name": APP_NAME, "version": APP_VERSION},
        "verified_at": iso(time.time()),
        "project_id": manifest.get("project_id") if isinstance(manifest, dict) else None,
        "signature": signature_result,
        "files": file_results,
        "missing": missing,
        "summary": {
            "ok": ok,
            "signature_valid": signature_result.get("valid") is True,
            "trusted_key": signature_result.get("trusted_key") is True,
            "files_checked": len(file_results),
            "files_matched": sum(1 for f in file_results if f["status"] == "match"),
            "files_modified": sum(1 for f in file_results if f["status"] == "modified"),
            "files_unknown": sum(1 for f in file_results if f["status"] == "unknown"),
            "files_missing": len(missing),
        },
        "disclaimer": DISCLAIMER,
    }
