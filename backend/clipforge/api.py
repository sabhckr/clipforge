"""FastAPI application: HTTP API, upload streaming, downloads and verification."""
from __future__ import annotations

import hashlib
import hmac
import json
import shutil
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any, Optional

from fastapi import Body, FastAPI, File, Form, HTTPException, Query, Request, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse, Response
from fastapi.staticfiles import StaticFiles
from pydantic import ValidationError
from starlette.concurrency import run_in_threadpool
from starlette.requests import ClientDisconnect

from . import APP_NAME, APP_VERSION
from .config import Config, load_config
from .db import Database
from .exporter import build_export, verify_job_on_disk
from .ffmpeg_tools import (ALLOWED_EXTENSIONS, FFmpegError, detect_hw_encoder,
                           ffmpeg_version, probe)
from .jobs import JobManager
from .logging_setup import get_logger, setup_logging
from .models import AppSettings, ProcessingSettings, SessionRequest
from .provenance import (KeyStore, build_report, iso, load_public_pem, match_files,
                         sha256_stream, verify_signed_manifest)
from .storage import (UnsafePath, disk_free, display_filename, is_uuid, new_id,
                      project_name_from, remove_tree, safe_join)

log = get_logger("api")

CONTAINER_EXTS = {"mp4": {".mp4", ".m4v", ".mov"}, "mkv": {".mkv", ".webm"}, "avi": {".avi"}}
MAX_VERIFY_FILES = 50
MAX_MANIFEST_BYTES = 10 * 1024 * 1024
SESSION_COOKIE = "clipforge_session"


def _err(status: int, message: str, **extra: Any) -> HTTPException:
    return HTTPException(status_code=status, detail={"message": message, **extra})


def _require_uuid(value: str, kind: str) -> str:
    if not is_uuid(value):
        raise _err(400, f"Invalid {kind} ID")
    return value


def create_app(cfg: Optional[Config] = None) -> FastAPI:
    cfg = cfg or load_config()
    cfg.ensure_dirs()
    setup_logging(cfg.logs_dir)
    db = Database(cfg.db_path)
    keys = KeyStore(cfg.key_dir)
    manager = JobManager(cfg, db, keys)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        keys.info()  # create the key pair on first start
        manager.start()
        log.info("CLIPFORGE started", extra={"version": APP_VERSION,
                                             "data_dir": str(cfg.data_dir),
                                             "auth": bool(cfg.api_token)})
        yield
        manager.stop()

    app = FastAPI(title=APP_NAME, version=APP_VERSION, lifespan=lifespan)
    app.state.cfg, app.state.db, app.state.keys, app.state.manager = cfg, db, keys, manager

    app.add_middleware(CORSMiddleware, allow_origins=cfg.cors_origins, allow_credentials=True,
                       allow_methods=["GET", "POST", "PUT", "DELETE"],
                       allow_headers=["Authorization", "Content-Type"])

    # ------------------------------------------------------------- auth
    def _session_value(token: str) -> str:
        return hmac.new(token.encode(), b"clipforge-session-v1", hashlib.sha256).hexdigest()

    @app.middleware("http")
    async def auth_and_headers(request: Request, call_next):
        path = request.url.path
        if (cfg.api_token and path.startswith("/api/") and request.method != "OPTIONS"
                and path not in ("/api/health", "/api/session")):
            ok = False
            auth = request.headers.get("authorization", "")
            if auth.lower().startswith("bearer "):
                ok = hmac.compare_digest(auth[7:].strip(), cfg.api_token)
            cookie = request.cookies.get(SESSION_COOKIE)
            if not ok and cookie:
                ok = hmac.compare_digest(cookie, _session_value(cfg.api_token))
            if not ok:
                return JSONResponse({"detail": {"message": "Authentication required"}},
                                    status_code=401)
        response = await call_next(request)
        response.headers.setdefault("X-Content-Type-Options", "nosniff")
        response.headers.setdefault("Referrer-Policy", "no-referrer")
        return response

    @app.exception_handler(UnsafePath)
    async def _unsafe(_: Request, __: UnsafePath):
        return JSONResponse({"detail": {"message": "Invalid path"}}, status_code=400)

    @app.get("/api/session")
    def session_status(request: Request):
        if not cfg.api_token:
            return {"auth_required": False, "authenticated": True}
        cookie = request.cookies.get(SESSION_COOKIE, "")
        return {"auth_required": True,
                "authenticated": bool(cookie) and hmac.compare_digest(
                    cookie, _session_value(cfg.api_token))}

    @app.post("/api/session")
    def create_session(body: SessionRequest, request: Request):
        if not cfg.api_token:
            return {"auth_required": False, "authenticated": True}
        if not hmac.compare_digest(body.token, cfg.api_token):
            raise _err(401, "Invalid access token")
        resp = JSONResponse({"auth_required": True, "authenticated": True})
        resp.set_cookie(SESSION_COOKIE, _session_value(cfg.api_token), httponly=True,
                        samesite="strict", secure=request.url.scheme == "https",
                        max_age=7 * 24 * 3600)
        return resp

    # ----------------------------------------------------------- helpers
    def get_app_settings() -> AppSettings:
        raw = db.get_setting("app")
        try:
            return AppSettings.model_validate(raw or {})
        except ValidationError:
            return AppSettings()

    def job_or_404(job_id: str) -> dict:
        _require_uuid(job_id, "job")
        job = db.get_job(job_id)
        if job is None:
            raise _err(404, "Job not found")
        return job

    def clip_or_404(clip_id: str) -> dict:
        _require_uuid(clip_id, "clip")
        clip = db.get_clip(clip_id)
        if clip is None:
            raise _err(404, "Clip not found")
        return clip

    def ser_job(job: dict) -> dict:
        return {
            "id": job["id"], "name": job["name"], "status": job["status"],
            "stage": job["stage"], "progress": job["progress"], "error": job["error"],
            "error_detail": job["error_detail"],
            "settings": json.loads(job["settings_json"]),
            "source": {"filename": job["source_filename"], "size_bytes": job["source_size"],
                       "sha256": job["source_sha256"], "asset_id": job["source_asset_id"],
                       "media": json.loads(job["media_json"])},
            "plan": json.loads(job["plan_json"]) if job["plan_json"] else None,
            "encoder_used": job["encoder_used"], "clip_count": job["clip_count"],
            "attempts": job["attempts"],
            "created_at": iso(job["created_at"]), "updated_at": iso(job["updated_at"]),
            "started_at": iso(job["started_at"]) if job["started_at"] else None,
            "finished_at": iso(job["finished_at"]) if job["finished_at"] else None,
            "has_manifest": job["status"] == "completed",
        }

    def ser_clip(c: dict) -> dict:
        base = f"/api/clips/{c['id']}"
        return {
            "id": c["id"], "job_id": c["job_id"], "index": c["idx"], "filename": c["filename"],
            "start": c["start"], "end": c["end"], "duration": c["duration"],
            "width": c["width"], "height": c["height"], "size_bytes": c["size_bytes"],
            "sha256": c["sha256"], "has_audio": bool(c["has_audio"]),
            "boundary": {"start": c["start_kind"], "end": c["end_kind"]},
            "created_at": iso(c["created_at"]),
            "urls": {"download": f"{base}/download", "stream": f"{base}/stream",
                     "thumbnail": f"{base}/thumbnail" if c["thumb_relpath"] else None},
        }

    def ser_export(e: dict) -> dict:
        return {"id": e["id"], "job_id": e["job_id"], "job_name": e["job_name"],
                "clip_count": len(json.loads(e["clip_ids_json"])), "filename": e["filename"],
                "size_bytes": e["size_bytes"], "created_at": iso(e["created_at"]),
                "available": not e["file_deleted"],
                "download_url": f"/api/exports/{e['id']}/download"}

    # ------------------------------------------------------------ system
    @app.get("/api/health")
    def health():
        return {"status": "ok", "app": APP_NAME, "version": APP_VERSION, "ai_used": False,
                "ffmpeg": shutil.which(cfg.ffmpeg_bin) is not None,
                "ffprobe": shutil.which(cfg.ffprobe_bin) is not None,
                "ffmpeg_version": ffmpeg_version(cfg.ffmpeg_bin)}

    @app.get("/api/stats")
    def stats():
        s = db.stats()
        jobs = db.list_jobs(limit=10_000)
        src_bytes = sum(j["source_size"] for j in jobs)
        exp_bytes = sum(e["size_bytes"] for e in db.list_exports(limit=10_000)
                        if not e["file_deleted"])
        return {**s, "storage": {"sources_bytes": src_bytes, "clips_bytes": s["clip_bytes"],
                                 "exports_bytes": exp_bytes,
                                 "total_bytes": src_bytes + s["clip_bytes"] + exp_bytes,
                                 "disk_free_bytes": disk_free(cfg.data_dir)},
                "limits": {"max_upload_bytes": cfg.max_upload_bytes,
                           "max_concurrent_jobs": cfg.max_concurrent_jobs,
                           "max_queued_jobs": cfg.max_queued_jobs}}

    @app.get("/api/encoders")
    async def encoders():
        hw = await run_in_threadpool(detect_hw_encoder, cfg.ffmpeg_bin)
        return {"software": "libx264", "hardware": hw}

    # ---------------------------------------------------------- settings
    @app.get("/api/settings")
    def read_settings():
        return get_app_settings().model_dump()

    @app.put("/api/settings")
    def write_settings(body: AppSettings):
        db.set_setting("app", body.model_dump())
        return body.model_dump()

    # -------------------------------------------------------------- jobs
    @app.post("/api/jobs", status_code=201)
    async def create_job(request: Request,
                         filename: str = Query(..., min_length=1, max_length=255),
                         settings: Optional[str] = Query(None, max_length=10_000),
                         name: Optional[str] = Query(None, max_length=120)):
        """Upload a video as the raw request body (streamed to disk) and queue a job."""
        ext = Path(display_filename(filename)).suffix.lower()
        if ext not in ALLOWED_EXTENSIONS:
            raise _err(415, f"Unsupported file type '{ext or 'none'}'. "
                            f"Allowed: {', '.join(sorted(ALLOWED_EXTENSIONS))}")
        try:
            base = get_app_settings().defaults.model_dump()
            proc_settings = ProcessingSettings.model_validate(
                {**base, **json.loads(settings)} if settings else base)
        except (json.JSONDecodeError, ValidationError, TypeError) as exc:
            msg = exc.errors() if isinstance(exc, ValidationError) else str(exc)
            raise _err(422, "Invalid processing settings", errors=json.loads(
                json.dumps(msg, default=str)))
        if manager.active_count() >= cfg.max_queued_jobs:
            raise _err(429, "The processing queue is full. Try again when jobs finish.")
        declared = request.headers.get("content-length")
        if declared and declared.isdigit():
            if int(declared) > cfg.max_upload_bytes:
                raise _err(413, "File exceeds the upload size limit")
            if int(declared) == 0:
                raise _err(400, "Empty upload")
            if disk_free(cfg.data_dir) - int(declared) < cfg.min_free_disk_bytes:
                raise _err(507, "Not enough free disk space for this upload")

        job_id = new_id()
        up_dir = safe_join(cfg.uploads_dir, job_id)
        up_dir.mkdir(parents=True)
        tmp = up_dir / "source.upload"
        h = hashlib.sha256()
        total = 0
        ok = False
        try:
            with open(tmp, "wb") as fh:
                async for chunk in request.stream():
                    if not chunk:
                        continue
                    total += len(chunk)
                    if total > cfg.max_upload_bytes:
                        raise _err(413, "File exceeds the upload size limit")
                    h.update(chunk)
                    fh.write(chunk)
            if total == 0:
                raise _err(400, "Empty upload")
            try:
                media = await run_in_threadpool(probe, tmp, cfg.ffprobe_bin, cfg.ffprobe_timeout)
            except FFmpegError as exc:
                raise _err(415, f"Invalid or unsupported video: {exc}")
            store_ext = ext if ext in CONTAINER_EXTS[media.container] else f".{media.container}"
            src = up_dir / f"source{store_ext}"
            tmp.replace(src)
            clean_name = display_filename(filename)
            db.insert_job({
                "id": job_id, "name": (name or "").strip()[:120] or project_name_from(clean_name),
                "status": "queued", "stage": "queued", "progress": 0,
                "settings_json": proc_settings.model_dump_json(),
                "source_filename": clean_name,
                "source_relpath": str(src.relative_to(cfg.data_dir)),
                "source_size": total, "source_sha256": h.hexdigest(),
                "source_asset_id": new_id(), "media_json": json.dumps(media.to_dict()),
            })
            ok = True
        except ClientDisconnect:
            raise _err(400, "Upload interrupted")
        finally:
            if not ok:
                remove_tree(up_dir)
        manager.enqueue(job_id)
        log.info("job created", extra={"job_id": job_id, "bytes": total})
        return ser_job(db.get_job(job_id))

    @app.get("/api/jobs")
    def list_jobs(status: Optional[str] = Query(None, pattern="^(queued|running|completed|failed|cancelled)$"),
                  limit: int = Query(100, ge=1, le=1000)):
        return [ser_job(j) for j in db.list_jobs(limit=limit, status=status)]

    @app.get("/api/jobs/{job_id}")
    def get_job(job_id: str):
        return ser_job(job_or_404(job_id))

    @app.delete("/api/jobs/{job_id}")
    def delete_job(job_id: str):
        job_or_404(job_id)
        try:
            manager.delete(job_id)
        except RuntimeError as exc:
            raise _err(409, str(exc))
        return {"deleted": True}

    @app.post("/api/jobs/{job_id}/cancel")
    def cancel_job(job_id: str):
        job = job_or_404(job_id)
        if job["status"] not in ("queued", "running"):
            raise _err(409, f"Job is {job['status']} and cannot be cancelled")
        if not manager.cancel(job_id):
            raise _err(409, "Job could not be cancelled")
        return ser_job(db.get_job(job_id))

    @app.post("/api/jobs/{job_id}/retry")
    def retry_job(job_id: str):
        job = job_or_404(job_id)
        if job["status"] not in ("failed", "cancelled"):
            raise _err(409, f"Only failed or cancelled jobs can be retried (job is {job['status']})")
        if manager.active_count() >= cfg.max_queued_jobs:
            raise _err(429, "The processing queue is full")
        try:
            if not manager.retry(job_id):
                raise _err(409, "Job could not be retried")
        except FileNotFoundError as exc:
            raise _err(410, str(exc))
        return ser_job(db.get_job(job_id))

    @app.get("/api/jobs/{job_id}/clips")
    def job_clips(job_id: str):
        job_or_404(job_id)
        return [ser_clip(c) for c in db.list_clips(job_id)]

    @app.get("/api/jobs/{job_id}/manifest")
    def job_manifest(job_id: str):
        job_or_404(job_id)
        path = manager.manifest_path(job_id)
        if not path.exists():
            raise _err(404, "Manifest not available (job not completed)")
        return FileResponse(path, media_type="application/json",
                            filename=f"manifest_{job_id[:8]}.json")

    @app.post("/api/jobs/{job_id}/verify")
    async def verify_job(job_id: str):
        job = job_or_404(job_id)
        try:
            return await run_in_threadpool(verify_job_on_disk, cfg, db, keys, job, None)
        except FileNotFoundError as exc:
            raise _err(404, str(exc))

    def _parse_clip_ids(raw: Optional[str] | list[str]) -> Optional[list[str]]:
        if not raw:
            return None
        ids = raw if isinstance(raw, list) else [p for p in raw.split(",") if p]
        if len(ids) > 10_000 or any(not is_uuid(i) for i in ids):
            raise _err(400, "Invalid clip ID in selection")
        return ids

    async def _export(job: dict, ids: Optional[list[str]]) -> dict:
        if job["status"] != "completed":
            raise _err(409, "Only completed jobs can be exported")
        try:
            return await run_in_threadpool(build_export, cfg, db, keys, job, ids)
        except ValueError as exc:
            raise _err(400, str(exc))
        except FileNotFoundError as exc:
            raise _err(404, str(exc))

    @app.get("/api/jobs/{job_id}/download")
    async def download_job(job_id: str, clips: Optional[str] = Query(None, max_length=400_000)):
        job = job_or_404(job_id)
        row = await _export(job, _parse_clip_ids(clips))
        return FileResponse(safe_join(cfg.data_dir, row["relpath"]),
                            media_type="application/zip", filename=row["filename"])

    @app.post("/api/jobs/{job_id}/exports", status_code=201)
    async def create_export(job_id: str, body: dict = Body(default={})):
        job = job_or_404(job_id)
        ids = body.get("clip_ids") if isinstance(body, dict) else None
        if ids is not None and not isinstance(ids, list):
            raise _err(400, "clip_ids must be a list")
        row = await _export(job, _parse_clip_ids(ids))
        return {**ser_export(db.get_export(row["id"])), "verification_ok": row["verification_ok"]}

    # ------------------------------------------------------------- clips
    @app.get("/api/clips")
    def list_all_clips(limit: int = Query(500, ge=1, le=5000)):
        return [ser_clip(c) for c in db.list_clips(limit=limit)]

    @app.get("/api/clips/{clip_id}")
    def get_clip(clip_id: str):
        return ser_clip(clip_or_404(clip_id))

    def _clip_file(clip: dict) -> Path:
        path = safe_join(cfg.data_dir, clip["relpath"])
        if not path.exists():
            raise _err(410, "Clip file is missing from storage")
        return path

    def _media_type(clip: dict) -> str:
        return "video/quicktime" if clip["filename"].endswith(".mov") else "video/mp4"

    @app.get("/api/clips/{clip_id}/download")
    def download_clip(clip_id: str):
        clip = clip_or_404(clip_id)
        return FileResponse(_clip_file(clip), media_type=_media_type(clip),
                            filename=clip["filename"])

    @app.get("/api/clips/{clip_id}/stream")
    def stream_clip(clip_id: str):
        clip = clip_or_404(clip_id)
        return FileResponse(_clip_file(clip), media_type=_media_type(clip),
                            content_disposition_type="inline", filename=clip["filename"])

    @app.get("/api/clips/{clip_id}/thumbnail")
    def clip_thumbnail(clip_id: str):
        clip = clip_or_404(clip_id)
        if not clip["thumb_relpath"]:
            raise _err(404, "No thumbnail for this clip")
        path = safe_join(cfg.data_dir, clip["thumb_relpath"])
        if not path.exists():
            raise _err(410, "Thumbnail file is missing")
        return FileResponse(path, media_type="image/jpeg")

    # ----------------------------------------------------------- exports
    @app.get("/api/exports")
    def list_exports(limit: int = Query(100, ge=1, le=1000)):
        return [ser_export(e) for e in db.list_exports(limit=limit)]

    @app.get("/api/exports/{export_id}/download")
    def download_export(export_id: str):
        _require_uuid(export_id, "export")
        e = db.get_export(export_id)
        if e is None:
            raise _err(404, "Export not found")
        path = safe_join(cfg.data_dir, e["relpath"])
        if e["file_deleted"] or not path.exists():
            raise _err(410, "This export file was removed by the retention policy; create a new export")
        return FileResponse(path, media_type="application/zip", filename=e["filename"])

    # -------------------------------------------------------- provenance
    @app.get("/api/provenance/key")
    def key_info():
        info = keys.info()
        return {"algorithm": "Ed25519", "key_id": info.key_id, "fingerprint": info.fingerprint,
                "public_key_pem": info.public_key_pem, "created_at": info.created_at,
                "storage": "Private key is stored on this machine only and is never served."}

    @app.get("/api/provenance/public_key.pem")
    def public_key_pem():
        return Response(keys.info().public_key_pem, media_type="application/x-pem-file",
                        headers={"Content-Disposition": 'attachment; filename="clipforge_public_key.pem"'})

    @app.post("/api/verify")
    async def verify(manifest: UploadFile = File(...),
                     files: list[UploadFile] = File(default=[]),
                     public_key: Optional[UploadFile] = File(default=None),
                     trust: str = Form("local")):
        """Verify a signed manifest and (optionally) files against it.

        trust='local' checks the signer is this installation's key; 'embedded'
        only checks integrity using the key embedded in the manifest; a supplied
        public_key PEM file overrides both.
        """
        if len(files) > MAX_VERIFY_FILES:
            raise _err(400, f"At most {MAX_VERIFY_FILES} files can be verified at once")
        raw = await manifest.read(MAX_MANIFEST_BYTES + 1)
        if len(raw) > MAX_MANIFEST_BYTES:
            raise _err(413, "Manifest file is too large")
        try:
            doc = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            raise _err(400, "Manifest is not valid JSON")
        trusted = None
        if public_key is not None and public_key.filename:
            try:
                trusted = load_public_pem(await public_key.read(64 * 1024))
            except ValueError as exc:
                raise _err(400, f"Invalid public key: {exc}")
        elif trust == "local":
            trusted = keys.public_key()
        sig = verify_signed_manifest(doc, trusted=trusted)
        hashed: list[tuple[str, str]] = []
        for f in files:
            digest, _ = await run_in_threadpool(sha256_stream, f.file, cfg.max_upload_bytes)
            hashed.append((display_filename(f.filename or "file"), digest))
        inner = doc.get("manifest", {}) if isinstance(doc, dict) else {}
        return build_report(doc, sig, match_files(inner if isinstance(inner, dict) else {},
                                                  hashed))

    # ---------------------------------------------------- static frontend
    if cfg.frontend_dist.is_dir() and (cfg.frontend_dist / "index.html").exists():
        app.mount("/", StaticFiles(directory=cfg.frontend_dist, html=True), name="frontend")

    return app
