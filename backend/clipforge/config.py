"""Runtime configuration, read once from environment variables.

Every value has a safe local default so `uvicorn clipforge.main:app` works with
no configuration on macOS.
"""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parent.parent
PROJECT_DIR = BACKEND_DIR.parent


def _env_int(name: str, default: int) -> int:
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return default
    try:
        return int(raw)
    except ValueError as exc:  # fail loudly on bad config
        raise RuntimeError(f"{name} must be an integer, got {raw!r}") from exc


def _env_list(name: str, default: list[str]) -> list[str]:
    raw = os.environ.get(name)
    if not raw:
        return default
    return [item.strip() for item in raw.split(",") if item.strip()]


@dataclass
class Config:
    data_dir: Path
    key_dir: Path
    max_upload_bytes: int
    max_concurrent_jobs: int
    max_queued_jobs: int
    min_free_disk_bytes: int
    ffmpeg_bin: str
    ffprobe_bin: str
    cors_origins: list[str]
    api_token: str | None
    export_retention: int
    frontend_dist: Path
    ffprobe_timeout: int = 60
    extra: dict = field(default_factory=dict)

    @property
    def db_path(self) -> Path:
        return self.data_dir / "clipforge.db"

    @property
    def uploads_dir(self) -> Path:
        return self.data_dir / "uploads"

    @property
    def jobs_dir(self) -> Path:
        return self.data_dir / "jobs"

    @property
    def exports_dir(self) -> Path:
        return self.data_dir / "exports"

    @property
    def logs_dir(self) -> Path:
        return self.data_dir / "logs"

    @property
    def tmp_dir(self) -> Path:
        return self.data_dir / "tmp"

    def ensure_dirs(self) -> None:
        for d in (self.data_dir, self.uploads_dir, self.jobs_dir, self.exports_dir,
                  self.logs_dir, self.tmp_dir):
            d.mkdir(parents=True, exist_ok=True)
        self.key_dir.mkdir(parents=True, exist_ok=True)
        try:
            os.chmod(self.key_dir, 0o700)
        except OSError:
            pass


def load_config() -> Config:
    data_dir = Path(os.environ.get("CLIPFORGE_DATA_DIR", PROJECT_DIR / "data")).expanduser().resolve()
    key_dir = Path(os.environ.get("CLIPFORGE_KEY_DIR", data_dir / "keys")).expanduser().resolve()
    token = os.environ.get("CLIPFORGE_API_TOKEN") or None
    return Config(
        data_dir=data_dir,
        key_dir=key_dir,
        max_upload_bytes=_env_int("CLIPFORGE_MAX_UPLOAD_MB", 8192) * 1024 * 1024,
        max_concurrent_jobs=max(1, _env_int("CLIPFORGE_MAX_CONCURRENT_JOBS", 1)),
        max_queued_jobs=max(1, _env_int("CLIPFORGE_MAX_QUEUED_JOBS", 20)),
        min_free_disk_bytes=_env_int("CLIPFORGE_MIN_FREE_DISK_MB", 2048) * 1024 * 1024,
        ffmpeg_bin=os.environ.get("CLIPFORGE_FFMPEG", "ffmpeg"),
        ffprobe_bin=os.environ.get("CLIPFORGE_FFPROBE", "ffprobe"),
        cors_origins=_env_list("CLIPFORGE_CORS_ORIGINS",
                               ["http://localhost:5173", "http://127.0.0.1:5173"]),
        api_token=token,
        export_retention=max(1, _env_int("CLIPFORGE_EXPORT_RETENTION", 20)),
        frontend_dist=Path(os.environ.get("CLIPFORGE_FRONTEND_DIST",
                                          PROJECT_DIR / "frontend" / "dist")).resolve(),
    )
