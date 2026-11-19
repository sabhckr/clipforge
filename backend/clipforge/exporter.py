"""Export packaging (CLIPFORGE_EXPORT/ ZIP) and on-disk job verification."""
from __future__ import annotations

import json
import time
import zipfile
from pathlib import Path
from typing import Optional

from . import APP_NAME, APP_VERSION
from .config import Config
from .db import Database
from .provenance import (KeyStore, build_report, iso, match_files, sha256_file,
                         verify_signed_manifest)
from .storage import new_id, safe_join

ROOT = "CLIPFORGE_EXPORT"


def verify_job_on_disk(cfg: Config, db: Database, keys: KeyStore, job: dict,
                       clip_ids: Optional[list[str]] = None) -> dict:
    """Re-hash the job's files on disk and check them against its signed manifest."""
    manifest_path = safe_join(cfg.jobs_dir, job["id"], "manifest.json")
    if not manifest_path.exists():
        raise FileNotFoundError("This job has no manifest yet")
    doc = json.loads(manifest_path.read_text(encoding="utf-8"))
    sig = verify_signed_manifest(doc, trusted=keys.public_key())
    files: list[tuple[str, str]] = []
    missing: list[dict] = []
    clips = db.list_clips(job["id"])
    wanted = set(clip_ids) if clip_ids else None
    for c in clips:
        if wanted is not None and c["id"] not in wanted:
            continue
        path = safe_join(cfg.data_dir, c["relpath"])
        if path.exists():
            files.append((f"clips/{c['filename']}", sha256_file(path)))
        else:
            missing.append({"name": f"clips/{c['filename']}", "asset_id": c["id"]})
    manifest = doc.get("manifest", {}) if isinstance(doc, dict) else {}
    return build_report(doc, sig, match_files(manifest, files), missing)


def build_export(cfg: Config, db: Database, keys: KeyStore, job: dict,
                 clip_ids: Optional[list[str]] = None) -> dict:
    """Create a ZIP with the documented export structure; record it in history."""
    all_clips = db.list_clips(job["id"])
    if clip_ids:
        wanted = set(clip_ids)
        clips = [c for c in all_clips if c["id"] in wanted]
        unknown = wanted - {c["id"] for c in clips}
        if unknown:
            raise ValueError(f"{len(unknown)} selected clip(s) do not belong to this job")
    else:
        clips = all_clips
    if not clips:
        raise ValueError("No clips to export")

    manifest_path = safe_join(cfg.jobs_dir, job["id"], "manifest.json")
    if not manifest_path.exists():
        raise FileNotFoundError("This job has no manifest yet")

    report = verify_job_on_disk(cfg, db, keys, job, [c["id"] for c in clips])
    media = json.loads(job["media_json"])
    project = {
        "generator": {"name": APP_NAME, "version": APP_VERSION},
        "project_id": job["id"],
        "project_name": job["name"],
        "exported_at": iso(time.time()),
        "source": {"filename": job["source_filename"], "asset_id": job["source_asset_id"],
                   "sha256": job["source_sha256"], "duration": media.get("duration"),
                   "width": media.get("width"), "height": media.get("height")},
        "settings": json.loads(job["settings_json"]),
        "selection": "selected" if clip_ids else "all",
        "clips": [{"asset_id": c["id"], "file": f"clips/{c['filename']}",
                   "thumbnail": f"thumbnails/{Path(c['thumb_relpath']).name}"
                   if c["thumb_relpath"] else None,
                   "source_start": c["start"], "source_end": c["end"],
                   "duration": c["duration"], "width": c["width"], "height": c["height"],
                   "sha256": c["sha256"]} for c in clips],
        "notes": ["manifest.json covers every clip produced by this project; "
                  "a selected export contains a subset of them."] if clip_ids else [],
    }

    export_id = new_id()
    safe_name = "".join(ch if ch.isalnum() or ch in "-_" else "_" for ch in job["name"])[:60]
    filename = f"clipforge_{safe_name or 'export'}_{export_id[:8]}.zip"
    dst = safe_join(cfg.exports_dir, f"{export_id}.zip")
    tmp = dst.with_suffix(".zip.partial")
    try:
        with zipfile.ZipFile(tmp, "w", allowZip64=True) as zf:
            def add_json(name: str, obj: dict) -> None:
                zf.writestr(f"{ROOT}/{name}", json.dumps(obj, indent=2, ensure_ascii=False),
                            compress_type=zipfile.ZIP_DEFLATED)
            add_json("project.json", project)
            zf.write(manifest_path, f"{ROOT}/manifest.json", compress_type=zipfile.ZIP_DEFLATED)
            for c in clips:
                zf.write(safe_join(cfg.data_dir, c["relpath"]), f"{ROOT}/clips/{c['filename']}",
                         compress_type=zipfile.ZIP_STORED)  # already compressed video
                if c["thumb_relpath"]:
                    thumb = safe_join(cfg.data_dir, c["thumb_relpath"])
                    if thumb.exists():
                        zf.write(thumb, f"{ROOT}/thumbnails/{thumb.name}",
                                 compress_type=zipfile.ZIP_STORED)
            zf.writestr(f"{ROOT}/verification/public_key.pem", keys.info().public_key_pem)
            add_json("verification/verification_report.json", report)
        tmp.replace(dst)
    finally:
        tmp.unlink(missing_ok=True)

    row = {"id": export_id, "job_id": job["id"], "job_name": job["name"],
           "clip_ids_json": json.dumps([c["id"] for c in clips]), "filename": filename,
           "relpath": str(dst.relative_to(cfg.data_dir)), "size_bytes": dst.stat().st_size}
    db.insert_export(row)
    prune_exports(cfg, db)
    return {**row, "verification_ok": report["summary"]["ok"]}


def prune_exports(cfg: Config, db: Database) -> None:
    """Keep only the newest N export ZIPs on disk; history rows remain."""
    live = [e for e in db.list_exports(limit=10_000) if not e["file_deleted"]]
    for e in live[cfg.export_retention:]:
        try:
            safe_join(cfg.data_dir, e["relpath"]).unlink(missing_ok=True)
        finally:
            db.mark_export_deleted(e["id"])
