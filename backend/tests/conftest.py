"""Shared fixtures. All test media is generated with FFmpeg's lavfi sources —
no third-party or copyrighted footage is used."""
from __future__ import annotations

import json
import subprocess
import time
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from clipforge.api import create_app
from clipforge.config import Config


def ffmpeg(*args: str) -> None:
    subprocess.run(["ffmpeg", "-hide_banner", "-v", "error", "-y", *args], check=True)


def make_scene_video(path: Path, scene_lengths: list[float], *, size: str = "640x360",
                     audio: bool = True, rate: int = 24) -> Path:
    """Concatenate visually distinct sources -> hard cuts at known timestamps."""
    sources = ["testsrc2", "smptebars", "rgbtestsrc", "testsrc", "yuvtestsrc",
               "pal75bars", "smptehdbars"]
    inputs: list[str] = []
    for i, length in enumerate(scene_lengths):
        src = sources[i % len(sources)]
        inputs += ["-f", "lavfi", "-i", f"{src}=size={size}:rate={rate}:duration={length}"]
    n = len(scene_lengths)
    total = sum(scene_lengths)
    chains = "".join(f"[{i}:v]scale={size.replace('x', ':')},setsar=1,format=yuv420p[v{i}];"
                     for i in range(n))
    concat = "".join(f"[v{i}]" for i in range(n)) + f"concat=n={n}:v=1:a=0[v]"
    args = [*inputs]
    if audio:
        args += ["-f", "lavfi", "-i", f"sine=frequency=440:sample_rate=48000:duration={total}"]
    args += ["-filter_complex", chains + concat, "-map", "[v]"]
    if audio:
        args += ["-map", f"{n}:a", "-c:a", "aac", "-b:a", "96k"]
    args += ["-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-t", str(total),
             str(path)]
    ffmpeg(*args)
    return path


def ffprobe_streams(path: Path) -> dict:
    out = subprocess.run(["ffprobe", "-v", "error", "-print_format", "json", "-show_streams",
                          "-show_format", str(path)], capture_output=True, text=True, check=True)
    return json.loads(out.stdout)


@pytest.fixture(scope="session")
def media_dir(tmp_path_factory) -> Path:
    return tmp_path_factory.mktemp("media")


@pytest.fixture(scope="session")
def scene_video(media_dir) -> Path:
    """70 s, cuts at 12, 29, 41, 58 — audio present."""
    return make_scene_video(media_dir / "scenes.mp4", [12, 17, 12, 17, 12])


@pytest.fixture(scope="session")
def short_video(media_dir) -> Path:
    """22 s single scene with audio — yields one clip with default settings."""
    return make_scene_video(media_dir / "short.mp4", [22])


@pytest.fixture(scope="session")
def silent_video(media_dir) -> Path:
    return make_scene_video(media_dir / "silent.mkv", [12, 12], audio=False)


@pytest.fixture(scope="session")
def rotated_video(media_dir) -> Path:
    """1280x720 coded, tagged with a 90° display rotation -> displays as portrait."""
    base = make_scene_video(media_dir / "rot_base.mp4", [12], size="1280x720")
    out = media_dir / "rotated.mp4"
    ffmpeg("-display_rotation", "90", "-i", str(base), "-c", "copy", str(out))
    return out


@pytest.fixture()
def cfg(tmp_path) -> Config:
    return Config(
        data_dir=tmp_path / "data", key_dir=tmp_path / "data" / "keys",
        max_upload_bytes=200 * 1024 * 1024, max_concurrent_jobs=1, max_queued_jobs=5,
        min_free_disk_bytes=0, ffmpeg_bin="ffmpeg", ffprobe_bin="ffprobe",
        cors_origins=["http://localhost:5173"], api_token=None, export_retention=3,
        frontend_dist=tmp_path / "no_frontend")


@pytest.fixture()
def client(cfg):
    app = create_app(cfg)
    with TestClient(app) as c:
        yield c


FAST = {"preset": "ultrafast", "resolution": "720p", "crf": 30}


def upload(client: TestClient, path: Path, settings: dict | None = None,
           filename: str | None = None):
    return client.post("/api/jobs",
                       params={"filename": filename or path.name,
                               "settings": json.dumps({**FAST, **(settings or {})})},
                       content=path.read_bytes(),
                       headers={"Content-Type": "application/octet-stream"})


def wait_for(client: TestClient, job_id: str, statuses=("completed", "failed", "cancelled"),
             timeout: float = 180) -> dict:
    deadline = time.time() + timeout
    while time.time() < deadline:
        job = client.get(f"/api/jobs/{job_id}").json()
        if job["status"] in statuses:
            return job
        time.sleep(0.2)
    raise AssertionError(f"job {job_id} did not reach {statuses}: {job}")
