"""Clip rendering: filter graph construction and ffmpeg invocation."""
from __future__ import annotations

import threading
from pathlib import Path
from typing import Callable, Optional

from .ffmpeg_tools import Cancelled, FFmpegError, MediaInfo, run_ffmpeg
from .logging_setup import get_logger
from .models import ProcessingSettings

log = get_logger("render")


def build_video_filter(settings: ProcessingSettings) -> str:
    """Scale/crop (or pad) to the output size without ever stretching.

    FFmpeg auto-rotates decoded frames using the stream's display matrix, so the
    graph operates on the upright picture. Non-square pixels are normalised first.
    """
    w, h = settings.output_size
    f = settings.crop_fraction
    norm = "scale=trunc(iw*sar/2)*2:ih,setsar=1"
    if settings.fit_mode == "pad":
        geom = (f"scale={w}:{h}:force_original_aspect_ratio=decrease:flags=lanczos,"
                f"pad={w}:{h}:(ow-iw)/2:(oh-ih)/2:color=black")
    else:
        geom = (f"scale={w}:{h}:force_original_aspect_ratio=increase:flags=lanczos,"
                f"crop={w}:{h}:(iw-{w})*{f:.4f}:(ih-{h})*{f:.4f}")
    return f"{norm},{geom},setsar=1,format=yuv420p"


def _video_codec_args(settings: ProcessingSettings, hw_encoder: Optional[str]) -> list[str]:
    if hw_encoder:
        args = ["-c:v", hw_encoder]
        if hw_encoder == "h264_videotoolbox":
            # Map CRF (lower = better) onto VideoToolbox's 1-100 quality scale.
            q = max(1, min(100, round(100 - settings.crf * 1.6)))
            args += ["-q:v", str(q), "-allow_sw", "1"]
        elif hw_encoder == "h264_nvenc":
            args += ["-rc", "vbr", "-cq", str(settings.crf), "-b:v", "0"]
        elif hw_encoder == "h264_qsv":
            args += ["-global_quality", str(settings.crf)]
        return args + ["-profile:v", "high"]
    return ["-c:v", "libx264", "-preset", settings.preset, "-crf", str(settings.crf),
            "-profile:v", "high"]


def build_render_args(src: Path, dst: Path, start: float, duration: float,
                      settings: ProcessingSettings, media: MediaInfo,
                      hw_encoder: Optional[str], asset_id: str) -> list[str]:
    args = ["-ss", f"{start:.3f}", "-i", str(src), "-t", f"{duration:.3f}",
            "-map", "0:v:0"]
    if media.has_audio:
        args += ["-map", "0:a:0"]
    args += ["-vf", build_video_filter(settings)]
    args += _video_codec_args(settings, hw_encoder)
    if media.has_audio:
        args += ["-c:a", "aac", "-b:a", f"{settings.audio_bitrate_kbps}k"]
        if settings.audio_normalize:
            args += ["-af", "loudnorm=I=-16:TP=-1.5:LRA=11,aresample=48000"]
    else:
        args += ["-an"]
    args += ["-sn", "-dn", "-map_metadata", "-1", "-map_chapters", "-1",
             "-metadata", f"comment=CLIPFORGE asset_id={asset_id}",
             "-movflags", "+faststart",
             "-f", "mov" if settings.export_format == "mov" else "mp4",
             str(dst)]
    return args


def render_clip(src: Path, dst: Path, start: float, duration: float,
                settings: ProcessingSettings, media: MediaInfo, asset_id: str, *,
                ffmpeg_bin: str = "ffmpeg", hw_encoder: Optional[str] = None,
                cancel_event: Optional[threading.Event] = None,
                on_progress: Optional[Callable[[float], None]] = None,
                on_process=None) -> str:
    """Render one segment. Returns the encoder actually used.

    If a hardware encoder fails, the clip is re-rendered with libx264.
    """
    tmp = dst.with_name(dst.stem + ".partial" + dst.suffix)
    try:
        if hw_encoder:
            try:
                run_ffmpeg(build_render_args(src, tmp, start, duration, settings, media,
                                             hw_encoder, asset_id),
                           ffmpeg_bin=ffmpeg_bin, cancel_event=cancel_event,
                           on_progress=on_progress, on_process=on_process)
                tmp.replace(dst)
                return hw_encoder
            except Cancelled:
                raise
            except FFmpegError as exc:
                log.warning("hardware encode failed, falling back to libx264",
                            extra={"encoder": hw_encoder, "stderr": exc.stderr_tail[-500:]})
        run_ffmpeg(build_render_args(src, tmp, start, duration, settings, media, None, asset_id),
                   ffmpeg_bin=ffmpeg_bin, cancel_event=cancel_event,
                   on_progress=on_progress, on_process=on_process)
        tmp.replace(dst)
        return "libx264"
    finally:
        if tmp.exists():
            tmp.unlink(missing_ok=True)


def render_thumbnail(clip: Path, dst: Path, at: float, *, ffmpeg_bin: str = "ffmpeg",
                     cancel_event: Optional[threading.Event] = None) -> None:
    run_ffmpeg(["-ss", f"{max(0.0, at):.3f}", "-i", str(clip), "-frames:v", "1",
                "-vf", "scale=360:-2", "-q:v", "4", str(dst)],
               ffmpeg_bin=ffmpeg_bin, cancel_event=cancel_event)
