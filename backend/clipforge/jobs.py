"""Background job queue and the processing pipeline.

Jobs are persisted in SQLite; the in-memory queue only holds job IDs. Worker
threads run ffmpeg subprocesses, so the HTTP server is never blocked.
"""
from __future__ import annotations

import json
import queue
import subprocess
import threading
import time
from pathlib import Path
from typing import Callable, Optional

from .config import Config
from .db import Database
from .ffmpeg_tools import (Cancelled, FFmpegError, MediaInfo, detect_hw_encoder,
                           ffmpeg_version, probe)
from .logging_setup import get_logger
from .models import AppSettings, ProcessingSettings
from .provenance import KeyStore, build_manifest, sha256_file, sign_manifest
from .render import render_clip, render_thumbnail
from .scenes import detect_scenes
from .segmentation import SegmentParams, plan_segments
from .storage import disk_free, new_id, remove_tree, safe_join

log = get_logger("jobs")

MAX_AUTO_RESUMES = 2
STAGE_WEIGHTS = {"scenes": (2.0, 20.0), "render": (20.0, 95.0)}


class JobManager:
    def __init__(self, cfg: Config, db: Database, keys: KeyStore):
        self.cfg = cfg
        self.db = db
        self.keys = keys
        self._q: "queue.Queue[Optional[str]]" = queue.Queue()
        self._cancel: dict[str, threading.Event] = {}
        self._procs: dict[str, subprocess.Popen] = {}
        self._lock = threading.Lock()
        self._workers: list[threading.Thread] = []
        self._shutting_down = threading.Event()

    # ------------------------------------------------------------ lifecycle
    def start(self) -> None:
        self._recover()
        for i in range(self.cfg.max_concurrent_jobs):
            t = threading.Thread(target=self._worker, name=f"clipforge-worker-{i}", daemon=True)
            t.start()
            self._workers.append(t)
        log.info("job workers started", extra={"workers": len(self._workers)})

    def stop(self, timeout: float = 10.0) -> None:
        self._shutting_down.set()
        with self._lock:
            for ev in self._cancel.values():
                ev.set()
        for _ in self._workers:
            self._q.put(None)
        for t in self._workers:
            t.join(timeout=timeout)
        self._workers.clear()

    def _recover(self) -> None:
        """Requeue jobs that were queued or interrupted by a previous shutdown/crash."""
        for job in reversed(self.db.list_jobs(limit=10_000)):
            if job["status"] == "running":
                if job["attempts"] > MAX_AUTO_RESUMES:
                    self.db.update_job(job["id"], status="failed", stage="failed",
                                       error="Job was interrupted repeatedly; retry manually.",
                                       finished_at=time.time())
                    continue
                self._cleanup_outputs(job["id"])
                self.db.update_job(job["id"], status="queued", stage="queued", progress=0,
                                   error=None, error_detail=None)
                log.info("requeued interrupted job", extra={"job_id": job["id"]})
                self._q.put(job["id"])
            elif job["status"] == "queued":
                self._q.put(job["id"])

    # ------------------------------------------------------------- commands
    def enqueue(self, job_id: str) -> None:
        self._q.put(job_id)

    def active_count(self) -> int:
        return self.db.count_jobs(("queued", "running"))

    def cancel(self, job_id: str) -> bool:
        if self.db.transition_job(job_id, ("queued",), status="cancelled", stage="cancelled",
                                  finished_at=time.time()):
            return True
        with self._lock:
            ev = self._cancel.get(job_id)
        if ev is not None:
            ev.set()
            return True
        return False

    def retry(self, job_id: str) -> bool:
        job = self.db.get_job(job_id)
        if job is None or job["status"] not in ("failed", "cancelled"):
            return False
        src = safe_join(self.cfg.data_dir, job["source_relpath"])
        if not src.exists():
            raise FileNotFoundError("The source video for this job no longer exists")
        self._cleanup_outputs(job_id)
        if not self.db.transition_job(job_id, ("failed", "cancelled"), status="queued",
                                      stage="queued", progress=0, error=None,
                                      error_detail=None, finished_at=None, attempts=0):
            return False
        self.enqueue(job_id)
        return True

    def delete(self, job_id: str) -> bool:
        job = self.db.get_job(job_id)
        if job is None:
            return False
        if job["status"] in ("queued", "running"):
            raise RuntimeError("Cancel the job before deleting it")
        self.db.delete_job(job_id)
        remove_tree(self.job_dir(job_id))
        remove_tree(safe_join(self.cfg.uploads_dir, job_id))
        return True

    # ---------------------------------------------------------------- paths
    def job_dir(self, job_id: str) -> Path:
        return safe_join(self.cfg.jobs_dir, job_id)

    def manifest_path(self, job_id: str) -> Path:
        return self.job_dir(job_id) / "manifest.json"

    def _cleanup_outputs(self, job_id: str) -> None:
        self.db.delete_clips_for_job(job_id)
        remove_tree(self.job_dir(job_id))

    # --------------------------------------------------------------- worker
    def _worker(self) -> None:
        while True:
            job_id = self._q.get()
            if job_id is None or self._shutting_down.is_set():
                return
            ev = threading.Event()
            with self._lock:
                self._cancel[job_id] = ev  # registered first so cancel() never misses it
            if not self.db.transition_job(job_id, ("queued",), status="running",
                                          stage="starting", progress=0,
                                          started_at=time.time()):
                with self._lock:
                    self._cancel.pop(job_id, None)
                continue  # cancelled or deleted while queued
            job = self.db.get_job(job_id)
            if job is None:
                with self._lock:
                    self._cancel.pop(job_id, None)
                continue
            self.db.update_job(job_id, attempts=job["attempts"] + 1)
            try:
                self._run(job, ev)
            except Cancelled:
                self._cleanup_outputs(job_id)
                if self._shutting_down.is_set():
                    # Resume automatically on next start.
                    self.db.update_job(job_id, status="queued", stage="queued", progress=0)
                else:
                    self.db.update_job(job_id, status="cancelled", stage="cancelled",
                                       finished_at=time.time())
                log.info("job cancelled", extra={"job_id": job_id})
            except Exception as exc:  # noqa: BLE001 - every failure must be recorded
                detail = getattr(exc, "stderr_tail", "") or ""
                self._cleanup_outputs(job_id)
                self.db.update_job(job_id, status="failed", stage="failed",
                                   error=str(exc) or exc.__class__.__name__,
                                   error_detail=detail[-4000:] or None,
                                   finished_at=time.time())
                log.exception("job failed", extra={"job_id": job_id})
            finally:
                with self._lock:
                    self._cancel.pop(job_id, None)
                    self._procs.pop(job_id, None)

    # ------------------------------------------------------------- pipeline
    def _run(self, job: dict, cancel: threading.Event) -> None:
        cfg = self.cfg
        job_id = job["id"]
        settings = ProcessingSettings.model_validate(json.loads(job["settings_json"]))
        src = safe_join(cfg.data_dir, job["source_relpath"])
        out_dir = self.job_dir(job_id)
        clips_dir = out_dir / "clips"
        thumbs_dir = out_dir / "thumbnails"
        remove_tree(out_dir)
        clips_dir.mkdir(parents=True)
        thumbs_dir.mkdir()

        last_write = [0.0]

        def progress(stage: str, pct: float, force: bool = False) -> None:
            now = time.monotonic()
            if force or now - last_write[0] > 0.5:
                last_write[0] = now
                self.db.update_job(job_id, stage=stage, progress=round(min(max(pct, 0), 100), 1))

        def track(proc: Optional[subprocess.Popen]) -> None:
            with self._lock:
                if proc is None:
                    self._procs.pop(job_id, None)
                else:
                    self._procs[job_id] = proc

        def check_cancel() -> None:
            if cancel.is_set():
                raise Cancelled()

        # A. validate / B. inspect
        progress("inspecting", 1, force=True)
        if not src.exists():
            raise FileNotFoundError("Source video is missing from storage")
        media: MediaInfo = probe(src, cfg.ffprobe_bin, cfg.ffprobe_timeout)
        self._check_disk(media)
        check_cancel()

        # C. scene detection (deterministic; falls back to fixed intervals)
        notes: list[str] = []
        scene_times: list[float] = []
        method_used = settings.segmentation_method
        if settings.segmentation_method == "hybrid":
            progress("detecting_scenes", STAGE_WEIGHTS["scenes"][0], force=True)
            lo, hi = STAGE_WEIGHTS["scenes"]
            try:
                scene_times = detect_scenes(
                    src, settings.scene_threshold, ffmpeg_bin=cfg.ffmpeg_bin,
                    cancel_event=cancel, on_process=track,
                    on_progress=lambda t: progress("detecting_scenes",
                                                   lo + (hi - lo) * min(t / media.duration, 1)))
            except Cancelled:
                raise
            except Exception as exc:  # noqa: BLE001 - fallback is a requirement
                log.warning("scene detection failed; using fixed intervals",
                            extra={"job_id": job_id, "error": str(exc)})
                notes.append(f"Scene detection failed ({exc}); fell back to fixed intervals.")
                method_used = "fixed (scene detection failed)"
        check_cancel()

        # D-G. plan
        progress("planning", STAGE_WEIGHTS["render"][0], force=True)
        params = SegmentParams(
            target=settings.target_duration, window=settings.scene_window,
            min_duration=settings.min_duration, max_duration=settings.effective_max_duration,
            remainder_min=settings.remainder_min, short_remainder=settings.short_remainder,
            overlap=settings.overlap)
        plan = plan_segments(media.duration, scene_times, params,
                             method="hybrid" if method_used == "hybrid" else "fixed")
        notes.extend(plan.notes)
        if not plan.segments:
            raise ValueError("Segmentation produced no clips")

        # Encoder selection
        hw = None
        if settings.encoder == "hardware":
            hw = detect_hw_encoder(cfg.ffmpeg_bin)
            if hw is None:
                notes.append("No working hardware H.264 encoder found; used libx264.")

        plan_doc = {
            "method_requested": settings.segmentation_method,
            "method_used": method_used,
            "scene_threshold": settings.scene_threshold,
            "scene_boundaries_detected": [round(t, 3) for t in scene_times],
            "scene_boundaries_used": plan.scene_boundaries_used,
            "dropped_tail_seconds": plan.dropped_tail,
            "notes": notes,
            "segments": [s.to_dict() for s in plan.segments],
        }
        self.db.update_job(job_id, plan_json=json.dumps(plan_doc),
                           media_json=json.dumps(media.to_dict()))

        # H. render each segment independently
        r_lo, r_hi = STAGE_WEIGHTS["render"]
        total = sum(s.duration for s in plan.segments) or 1.0
        done = 0.0
        encoders_used: set[str] = set()
        ext = settings.export_format
        out_w, out_h = settings.output_size
        for seg in plan.segments:
            check_cancel()
            self._check_disk(None)
            asset_id = new_id()
            filename = f"clip_{seg.index:04d}.{ext}"
            dst = clips_dir / filename
            base = done

            def on_prog(t: float, base: float = base, seg_dur: float = seg.duration) -> None:
                frac = (base + min(t, seg_dur)) / total
                progress("rendering", r_lo + (r_hi - r_lo) * frac)

            enc = render_clip(src, dst, seg.start, seg.duration, settings, media, asset_id,
                              ffmpeg_bin=cfg.ffmpeg_bin, hw_encoder=hw, cancel_event=cancel,
                              on_progress=on_prog, on_process=track)
            encoders_used.add(enc)
            if enc != hw and hw is not None:
                hw = None  # hardware failed once; stay on software for the rest

            out_info = probe(dst, cfg.ffprobe_bin, cfg.ffprobe_timeout)
            if (out_info.width, out_info.height) != (out_w, out_h):
                raise FFmpegError(f"Rendered clip has unexpected size "
                                  f"{out_info.width}x{out_info.height}, expected {out_w}x{out_h}")
            thumb_rel = None
            if settings.thumbnails:
                thumb = thumbs_dir / f"clip_{seg.index:04d}.jpg"
                try:
                    render_thumbnail(dst, thumb, min(1.0, out_info.duration / 4),
                                     ffmpeg_bin=cfg.ffmpeg_bin, cancel_event=cancel)
                    thumb_rel = str(thumb.relative_to(cfg.data_dir))
                except Cancelled:
                    raise
                except FFmpegError as exc:
                    log.warning("thumbnail failed", extra={"job_id": job_id, "error": str(exc)})

            self.db.insert_clip({
                "id": asset_id, "job_id": job_id, "idx": seg.index, "filename": filename,
                "relpath": str(dst.relative_to(cfg.data_dir)), "thumb_relpath": thumb_rel,
                "start": seg.start, "end": seg.end, "duration": round(out_info.duration, 3),
                "width": out_info.width, "height": out_info.height,
                "size_bytes": dst.stat().st_size, "sha256": sha256_file(dst),
                "has_audio": int(out_info.has_audio), "start_kind": seg.start_kind,
                "end_kind": seg.end_kind,
            })
            done += seg.duration
            progress("rendering", r_lo + (r_hi - r_lo) * done / total, force=True)

        # I-J. hashes + signed provenance manifest
        check_cancel()
        progress("signing", 97, force=True)
        encoder_used = ", ".join(sorted(encoders_used))
        self.db.update_job(job_id, encoder_used=encoder_used)
        fresh = self.db.get_job(job_id)
        assert fresh is not None
        self.write_manifest(fresh)

        self.db.update_job(job_id, status="completed", stage="completed", progress=100,
                           clip_count=len(plan.segments), finished_at=time.time(),
                           error=None, error_detail=None)
        log.info("job completed", extra={"job_id": job_id, "clips": len(plan.segments)})

    def write_manifest(self, job: dict) -> dict:
        app = AppSettings.model_validate(self.db.get_setting("app") or {})
        owner = None
        if app.include_owner_in_manifest and (app.owner_name or app.owner_contact):
            owner = {"name": app.owner_name, "contact": app.owner_contact,
                     "statement": "Owner-declared information; not independently verified."}
        manifest = build_manifest(
            job=job, settings=json.loads(job["settings_json"]),
            media=json.loads(job["media_json"]), plan=json.loads(job["plan_json"] or "{}"),
            clips=self.db.list_clips(job["id"]), ffmpeg_version=ffmpeg_version(self.cfg.ffmpeg_bin),
            owner=owner)
        signed = sign_manifest(manifest, self.keys)
        path = self.manifest_path(job["id"])
        tmp = path.with_suffix(".json.tmp")
        tmp.write_text(json.dumps(signed, indent=2, ensure_ascii=False), encoding="utf-8")
        tmp.replace(path)
        return signed

    def _check_disk(self, media: Optional[MediaInfo]) -> None:
        free = disk_free(self.cfg.data_dir)
        if free < self.cfg.min_free_disk_bytes:
            raise OSError(f"Not enough free disk space ({free // (1024 * 1024)} MB free, "
                          f"{self.cfg.min_free_disk_bytes // (1024 * 1024)} MB required)")
