"""Thin, safe wrappers around the ffmpeg / ffprobe executables.

All commands are passed as argument lists (never through a shell). Long-running
ffmpeg processes report progress via `-progress pipe:1` and can be cancelled.
"""
from __future__ import annotations

import json
import os
import subprocess
import tempfile
import threading
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Callable, Optional

from .logging_setup import get_logger

log = get_logger("ffmpeg")

# ffprobe `format_name` values we accept (container is detected from content,
# not from the filename).
ALLOWED_FORMATS = {
    "mov,mp4,m4a,3gp,3g2,mj2": "mp4",   # MP4 and MOV share a demuxer
    "matroska,webm": "mkv",
    "avi": "avi",
}
ALLOWED_EXTENSIONS = {".mp4", ".m4v", ".mov", ".mkv", ".webm", ".avi"}


class FFmpegError(RuntimeError):
    def __init__(self, message: str, stderr_tail: str = ""):
        super().__init__(message)
        self.stderr_tail = stderr_tail


class Cancelled(Exception):
    """Raised when a job's cancel event fires during processing."""


@dataclass
class MediaInfo:
    format_name: str
    container: str
    duration: float
    size_bytes: int
    width: int              # display width (after rotation / SAR)
    height: int             # display height
    coded_width: int
    coded_height: int
    rotation: int
    fps: float
    video_codec: str
    has_audio: bool
    audio_codec: Optional[str]
    audio_channels: Optional[int]
    audio_sample_rate: Optional[int]

    def to_dict(self) -> dict:
        return asdict(self)


def _parse_rate(rate: str | None) -> float:
    if not rate or rate in ("0/0", "N/A"):
        return 0.0
    if "/" in rate:
        num, den = rate.split("/", 1)
        try:
            return float(num) / float(den) if float(den) else 0.0
        except ValueError:
            return 0.0
    try:
        return float(rate)
    except ValueError:
        return 0.0


def _rotation_of(stream: dict) -> int:
    rot = 0
    tags = stream.get("tags") or {}
    if "rotate" in tags:
        try:
            rot = int(float(tags["rotate"]))
        except ValueError:
            rot = 0
    for sd in stream.get("side_data_list") or []:
        if "rotation" in sd:
            try:
                rot = int(float(sd["rotation"]))
            except (TypeError, ValueError):
                pass
    return rot % 360


def probe(path: Path, ffprobe_bin: str = "ffprobe", timeout: int = 60) -> MediaInfo:
    """Inspect a media file with ffprobe. Raises FFmpegError for anything unusable."""
    cmd = [ffprobe_bin, "-v", "error", "-print_format", "json",
           "-show_format", "-show_streams", "--", str(path)]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired as exc:
        raise FFmpegError("ffprobe timed out while inspecting the file") from exc
    except FileNotFoundError as exc:
        raise FFmpegError(f"ffprobe executable not found: {ffprobe_bin}") from exc
    if proc.returncode != 0:
        raise FFmpegError("File is not a readable media file", proc.stderr[-2000:])
    try:
        data = json.loads(proc.stdout or "{}")
    except json.JSONDecodeError as exc:
        raise FFmpegError("ffprobe returned invalid output") from exc

    fmt = data.get("format") or {}
    format_name = fmt.get("format_name", "")
    if format_name not in ALLOWED_FORMATS:
        raise FFmpegError(f"Unsupported container format: {format_name or 'unknown'}")

    streams = data.get("streams") or []
    video = next((s for s in streams if s.get("codec_type") == "video"
                  and not (s.get("disposition") or {}).get("attached_pic")), None)
    if video is None:
        raise FFmpegError("No video stream found")
    audio = next((s for s in streams if s.get("codec_type") == "audio"), None)

    duration = 0.0
    for candidate in (fmt.get("duration"), video.get("duration")):
        try:
            if candidate is not None and float(candidate) > 0:
                duration = float(candidate)
                break
        except ValueError:
            continue
    if duration <= 0:
        raise FFmpegError("Could not determine a positive duration")

    coded_w = int(video.get("width") or 0)
    coded_h = int(video.get("height") or 0)
    if coded_w <= 0 or coded_h <= 0:
        raise FFmpegError("Video stream has no valid dimensions")

    # Display size: apply sample aspect ratio, then rotation.
    sar = _parse_rate((video.get("sample_aspect_ratio") or "1:1").replace(":", "/")) or 1.0
    disp_w = int(round(coded_w * sar))
    disp_h = coded_h
    rotation = _rotation_of(video)
    if rotation in (90, 270):
        disp_w, disp_h = disp_h, disp_w

    fps = _parse_rate(video.get("avg_frame_rate")) or _parse_rate(video.get("r_frame_rate"))

    return MediaInfo(
        format_name=format_name,
        container=ALLOWED_FORMATS[format_name],
        duration=round(duration, 3),
        size_bytes=int(fmt.get("size") or path.stat().st_size),
        width=disp_w,
        height=disp_h,
        coded_width=coded_w,
        coded_height=coded_h,
        rotation=rotation,
        fps=round(fps, 3),
        video_codec=video.get("codec_name", "unknown"),
        has_audio=audio is not None,
        audio_codec=audio.get("codec_name") if audio else None,
        audio_channels=int(audio["channels"]) if audio and audio.get("channels") else None,
        audio_sample_rate=int(audio["sample_rate"]) if audio and audio.get("sample_rate") else None,
    )


ProgressCb = Callable[[float], None]      # seconds of output processed


def run_ffmpeg(args: list[str], *, ffmpeg_bin: str = "ffmpeg",
               cancel_event: Optional[threading.Event] = None,
               on_progress: Optional[ProgressCb] = None,
               on_process: Optional[Callable[[Optional[subprocess.Popen]], None]] = None,
               keep_stderr: bool = False,
               timeout: Optional[float] = None) -> str:
    """Run ffmpeg with progress reporting and cooperative cancellation.

    stderr is written to a temp file (not a pipe) so large showinfo output
    cannot deadlock. Returns the stderr text when keep_stderr=True, else the tail.
    """
    cmd = [ffmpeg_bin, "-hide_banner", "-nostdin", "-y", "-progress", "pipe:1",
           "-nostats", *args]
    with tempfile.TemporaryFile(mode="w+b") as errf:
        try:
            proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=errf,
                                    stdin=subprocess.DEVNULL)
        except FileNotFoundError as exc:
            raise FFmpegError(f"ffmpeg executable not found: {ffmpeg_bin}") from exc
        if on_process:
            on_process(proc)
        killer: Optional[threading.Thread] = None
        stop_watch = threading.Event()

        def _watch_cancel() -> None:
            while not stop_watch.wait(0.2):
                if cancel_event is not None and cancel_event.is_set():
                    _terminate(proc)
                    return

        if cancel_event is not None:
            killer = threading.Thread(target=_watch_cancel, daemon=True)
            killer.start()
        try:
            assert proc.stdout is not None
            for raw in proc.stdout:
                line = raw.decode("utf-8", "replace").strip()
                if on_progress and line.startswith("out_time_us="):
                    val = line.split("=", 1)[1]
                    if val.isdigit():
                        on_progress(int(val) / 1_000_000)
            proc.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            _terminate(proc)
            raise FFmpegError("ffmpeg timed out")
        finally:
            stop_watch.set()
            if killer:
                killer.join(timeout=1)
            if on_process:
                on_process(None)
        errf.seek(0)
        stderr = errf.read().decode("utf-8", "replace")

    if cancel_event is not None and cancel_event.is_set():
        raise Cancelled()
    if proc.returncode != 0:
        tail = stderr[-3000:]
        raise FFmpegError(f"ffmpeg exited with code {proc.returncode}", tail)
    return stderr if keep_stderr else stderr[-3000:]


def _terminate(proc: subprocess.Popen) -> None:
    if proc.poll() is not None:
        return
    try:
        proc.terminate()
        proc.wait(timeout=5)
    except subprocess.TimeoutExpired:
        proc.kill()
    except OSError:
        pass


_version_cache: dict[str, str] = {}
_hw_cache: dict[str, Optional[str]] = {}
_hw_lock = threading.Lock()


def ffmpeg_version(ffmpeg_bin: str = "ffmpeg") -> str:
    if ffmpeg_bin not in _version_cache:
        try:
            out = subprocess.run([ffmpeg_bin, "-version"], capture_output=True, text=True,
                                 timeout=15).stdout
            _version_cache[ffmpeg_bin] = out.splitlines()[0] if out else "unknown"
        except (OSError, subprocess.TimeoutExpired):
            _version_cache[ffmpeg_bin] = "unavailable"
    return _version_cache[ffmpeg_bin]


HW_ENCODERS = ["h264_videotoolbox", "h264_nvenc", "h264_qsv", "h264_amf"]


def detect_hw_encoder(ffmpeg_bin: str = "ffmpeg") -> Optional[str]:
    """Return a working hardware H.264 encoder, verified by a tiny test encode."""
    with _hw_lock:
        if ffmpeg_bin in _hw_cache:
            return _hw_cache[ffmpeg_bin]
        found: Optional[str] = None
        try:
            listing = subprocess.run([ffmpeg_bin, "-hide_banner", "-encoders"],
                                     capture_output=True, text=True, timeout=15).stdout
        except (OSError, subprocess.TimeoutExpired):
            listing = ""
        for enc in HW_ENCODERS:
            if f" {enc} " not in listing:
                continue
            test = [ffmpeg_bin, "-hide_banner", "-nostdin", "-v", "error",
                    "-f", "lavfi", "-i", "testsrc2=size=256x256:rate=30:duration=0.5",
                    "-c:v", enc, "-pix_fmt", "yuv420p", "-f", "null", os.devnull]
            try:
                ok = subprocess.run(test, capture_output=True, timeout=30).returncode == 0
            except (OSError, subprocess.TimeoutExpired):
                ok = False
            if ok:
                found = enc
                break
        _hw_cache[ffmpeg_bin] = found
        log.info("hardware encoder detection", extra={"encoder": found})
        return found
