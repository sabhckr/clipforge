"""Check that the desktop verifier accepts a manifest signed by the Android code.

The Kotlin unit test ProvenanceTest.writeCrossCheckManifest signs a manifest with the
fixed TEST-ONLY seed (bytes 0..31) and writes it to app/build/cross-check/. This script
verifies it with backend/clipforge/provenance.py, the same code the desktop app uses.

    python android/tools/verify_cross_check.py android/app/build/cross-check/kotlin_signed.json
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "backend"))

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey  # noqa: E402

from clipforge import provenance  # noqa: E402


def main(path: str) -> int:
    doc = json.loads(Path(path).read_text(encoding="utf-8"))
    expected_key = Ed25519PrivateKey.from_private_bytes(bytes(range(32))).public_key()
    result = provenance.verify_signed_manifest(doc, trusted=expected_key)
    print(json.dumps(result, indent=2))
    if not (result["valid"] and result["trusted_key"]):
        print("FAIL: the desktop verifier rejected the Android-signed manifest", file=sys.stderr)
        return 1
    # Tampering must be detected too.
    doc["manifest"]["project_name"] += " (edited)"
    if provenance.verify_signed_manifest(doc)["valid"]:
        print("FAIL: tampered manifest still verified", file=sys.stderr)
        return 1
    print("OK: desktop verifier accepts the Android signature and rejects a tampered copy")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
