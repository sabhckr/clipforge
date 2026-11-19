"""Scene-change detection with FFmpeg's `scene` score (select filter + showinfo).

The scene score is a deterministic frame-difference metric computed by FFmpeg;
no learned model is involved. Frames are downscaled first for speed.
"""
from __future__ import annotations

import re
import threading
from pathlib import Path
from typing import Callable, Optional

from .ffmpeg_tools import run_ffmpeg

_PTS_RE = re.compile(r"\bpts_time:\s*([0-9]+(?:\.[0-9]+)?)")
ANALYSIS_WIDTH = 320


def parse_showinfo(stderr: str) -> list[float]:
    times: list[float] = []
    for line in stderr.splitlines():
        if "Parsed_showinfo" not in line:
            continue
        m = _PTS_RE.search(line)
        if m:
            times.append(float(m.group(1)))
    return sorted(set(times))


def detect_scenes(path: Path, threshold: float, *, ffmpeg_bin: str = "ffmpeg",
                  cancel_event: Optional[threading.Event] = None,
                  on_progress: Optional[Callable[[float], None]] = None,
                  on_process=None) -> list[float]:
    """Return scene-change timestamps (seconds) where score > threshold."""
    if not 0 < threshold < 1:
        raise ValueError("threshold must be in (0, 1)")
    vf = f"scale={ANALYSIS_WIDTH}:-2,select='gt(scene\\,{threshold:.4f})',showinfo"
    stderr = run_ffmpeg(
        ["-i", str(path), "-map", "0:v:0", "-an", "-sn", "-dn", "-vf", vf, "-f", "null", "-"],
        ffmpeg_bin=ffmpeg_bin, cancel_event=cancel_event, on_progress=on_progress,
        on_process=on_process, keep_stderr=True)
    return parse_showinfo(stderr)
