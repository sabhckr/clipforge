"""SQLite persistence for jobs, clips, settings and export history.

A short-lived connection is opened per operation (WAL mode) so worker threads
and request handlers never share a connection object.
"""
from __future__ import annotations

import json
import sqlite3
import threading
import time
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Iterator, Optional

SCHEMA = """
CREATE TABLE IF NOT EXISTS jobs (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    status TEXT NOT NULL,
    stage TEXT NOT NULL DEFAULT '',
    progress REAL NOT NULL DEFAULT 0,
    error TEXT,
    error_detail TEXT,
    settings_json TEXT NOT NULL,
    source_filename TEXT NOT NULL,
    source_relpath TEXT NOT NULL,
    source_size INTEGER NOT NULL,
    source_sha256 TEXT,
    source_asset_id TEXT NOT NULL,
    media_json TEXT NOT NULL,
    plan_json TEXT,
    encoder_used TEXT,
    clip_count INTEGER NOT NULL DEFAULT 0,
    attempts INTEGER NOT NULL DEFAULT 0,
    created_at REAL NOT NULL,
    updated_at REAL NOT NULL,
    started_at REAL,
    finished_at REAL
);
CREATE INDEX IF NOT EXISTS idx_jobs_status ON jobs(status);
CREATE INDEX IF NOT EXISTS idx_jobs_created ON jobs(created_at);

CREATE TABLE IF NOT EXISTS clips (
    id TEXT PRIMARY KEY,
    job_id TEXT NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    idx INTEGER NOT NULL,
    filename TEXT NOT NULL,
    relpath TEXT NOT NULL,
    thumb_relpath TEXT,
    start REAL NOT NULL,
    end REAL NOT NULL,
    duration REAL NOT NULL,
    width INTEGER NOT NULL,
    height INTEGER NOT NULL,
    size_bytes INTEGER NOT NULL,
    sha256 TEXT NOT NULL,
    has_audio INTEGER NOT NULL,
    start_kind TEXT NOT NULL,
    end_kind TEXT NOT NULL,
    created_at REAL NOT NULL,
    UNIQUE(job_id, idx)
);
CREATE INDEX IF NOT EXISTS idx_clips_job ON clips(job_id);

CREATE TABLE IF NOT EXISTS settings (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS exports (
    id TEXT PRIMARY KEY,
    job_id TEXT NOT NULL,
    job_name TEXT NOT NULL,
    clip_ids_json TEXT NOT NULL,
    filename TEXT NOT NULL,
    relpath TEXT NOT NULL,
    size_bytes INTEGER NOT NULL,
    created_at REAL NOT NULL,
    file_deleted INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_exports_created ON exports(created_at);
"""

JOB_STATUSES = ("queued", "running", "completed", "failed", "cancelled")


class Database:
    def __init__(self, path: Path):
        self.path = path
        self._write_lock = threading.Lock()
        with self._conn() as conn:
            conn.execute("PRAGMA journal_mode=WAL")
            conn.executescript(SCHEMA)

    @contextmanager
    def _conn(self) -> Iterator[sqlite3.Connection]:
        conn = sqlite3.connect(self.path, timeout=30)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA foreign_keys=ON")
        try:
            yield conn
            conn.commit()
        finally:
            conn.close()

    def _write(self, sql: str, params: tuple | dict = ()) -> int:
        with self._write_lock, self._conn() as conn:
            return conn.execute(sql, params).rowcount

    # ---------------------------------------------------------------- jobs
    def insert_job(self, row: dict[str, Any]) -> None:
        now = time.time()
        row = {"created_at": now, "updated_at": now, **row}
        cols = ", ".join(row.keys())
        marks = ", ".join(f":{k}" for k in row)
        self._write(f"INSERT INTO jobs ({cols}) VALUES ({marks})", row)

    def update_job(self, job_id: str, **fields: Any) -> None:
        if not fields:
            return
        fields["updated_at"] = time.time()
        sets = ", ".join(f"{k} = :{k}" for k in fields)
        self._write(f"UPDATE jobs SET {sets} WHERE id = :_id", {**fields, "_id": job_id})

    def transition_job(self, job_id: str, from_statuses: tuple[str, ...], **fields: Any) -> bool:
        """Atomically update a job only if it is currently in one of from_statuses."""
        fields["updated_at"] = time.time()
        sets = ", ".join(f"{k} = :{k}" for k in fields)
        marks = ", ".join(f":_s{i}" for i in range(len(from_statuses)))
        params = {**fields, "_id": job_id, **{f"_s{i}": s for i, s in enumerate(from_statuses)}}
        return self._write(f"UPDATE jobs SET {sets} WHERE id = :_id AND status IN ({marks})",
                           params) == 1

    def get_job(self, job_id: str) -> Optional[dict]:
        with self._conn() as conn:
            row = conn.execute("SELECT * FROM jobs WHERE id = ?", (job_id,)).fetchone()
        return dict(row) if row else None

    def list_jobs(self, limit: int = 200, status: Optional[str] = None) -> list[dict]:
        with self._conn() as conn:
            if status:
                rows = conn.execute("SELECT * FROM jobs WHERE status = ? ORDER BY created_at DESC LIMIT ?",
                                    (status, limit)).fetchall()
            else:
                rows = conn.execute("SELECT * FROM jobs ORDER BY created_at DESC LIMIT ?",
                                    (limit,)).fetchall()
        return [dict(r) for r in rows]

    def count_jobs(self, statuses: tuple[str, ...]) -> int:
        marks = ", ".join("?" for _ in statuses)
        with self._conn() as conn:
            return conn.execute(f"SELECT COUNT(*) FROM jobs WHERE status IN ({marks})",
                                statuses).fetchone()[0]

    def delete_job(self, job_id: str) -> None:
        self._write("DELETE FROM jobs WHERE id = ?", (job_id,))

    def stats(self) -> dict:
        with self._conn() as conn:
            by_status = {r["status"]: r["n"] for r in conn.execute(
                "SELECT status, COUNT(*) AS n FROM jobs GROUP BY status")}
            clips = conn.execute("SELECT COUNT(*), COALESCE(SUM(duration),0), "
                                 "COALESCE(SUM(size_bytes),0) FROM clips").fetchone()
            src = conn.execute("SELECT COALESCE(SUM(json_extract(media_json,'$.duration')),0) "
                               "FROM jobs WHERE status='completed'").fetchone()[0]
        return {
            "jobs_by_status": {s: by_status.get(s, 0) for s in JOB_STATUSES},
            "videos_processed": by_status.get("completed", 0),
            "clips_generated": clips[0],
            "clip_seconds": round(clips[1], 3),
            "clip_bytes": clips[2],
            "source_seconds_processed": round(src or 0, 3),
        }

    # ---------------------------------------------------------------- clips
    def insert_clip(self, row: dict[str, Any]) -> None:
        row = {"created_at": time.time(), **row}
        cols = ", ".join(row.keys())
        marks = ", ".join(f":{k}" for k in row)
        self._write(f"INSERT INTO clips ({cols}) VALUES ({marks})", row)

    def delete_clips_for_job(self, job_id: str) -> None:
        self._write("DELETE FROM clips WHERE job_id = ?", (job_id,))

    def get_clip(self, clip_id: str) -> Optional[dict]:
        with self._conn() as conn:
            row = conn.execute("SELECT * FROM clips WHERE id = ?", (clip_id,)).fetchone()
        return dict(row) if row else None

    def list_clips(self, job_id: Optional[str] = None, limit: int = 1000) -> list[dict]:
        with self._conn() as conn:
            if job_id:
                rows = conn.execute("SELECT * FROM clips WHERE job_id = ? ORDER BY idx",
                                    (job_id,)).fetchall()
            else:
                rows = conn.execute("SELECT * FROM clips ORDER BY created_at DESC, idx LIMIT ?",
                                    (limit,)).fetchall()
        return [dict(r) for r in rows]

    # ------------------------------------------------------------- settings
    def get_setting(self, key: str) -> Optional[Any]:
        with self._conn() as conn:
            row = conn.execute("SELECT value FROM settings WHERE key = ?", (key,)).fetchone()
        return json.loads(row["value"]) if row else None

    def set_setting(self, key: str, value: Any) -> None:
        self._write("INSERT INTO settings (key, value) VALUES (?, ?) "
                    "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                    (key, json.dumps(value)))

    # -------------------------------------------------------------- exports
    def insert_export(self, row: dict[str, Any]) -> None:
        row = {"created_at": time.time(), **row}
        cols = ", ".join(row.keys())
        marks = ", ".join(f":{k}" for k in row)
        self._write(f"INSERT INTO exports ({cols}) VALUES ({marks})", row)

    def list_exports(self, limit: int = 100) -> list[dict]:
        with self._conn() as conn:
            rows = conn.execute("SELECT * FROM exports ORDER BY created_at DESC LIMIT ?",
                                (limit,)).fetchall()
        return [dict(r) for r in rows]

    def get_export(self, export_id: str) -> Optional[dict]:
        with self._conn() as conn:
            row = conn.execute("SELECT * FROM exports WHERE id = ?", (export_id,)).fetchone()
        return dict(row) if row else None

    def mark_export_deleted(self, export_id: str) -> None:
        self._write("UPDATE exports SET file_deleted = 1 WHERE id = ?", (export_id,))
