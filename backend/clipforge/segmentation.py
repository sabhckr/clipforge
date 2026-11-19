"""Deterministic hybrid segmentation.

Pure functions only — no I/O — so the rules are easy to test exhaustively.

Algorithm (hybrid):
  pos = 0
  while the rest of the video does not fit into one clip (> max_duration):
      ideal = pos + target
      window = [max(pos + min, ideal - search), min(pos + max, ideal + search)]
      if possible, shrink the window's upper edge so the cut leaves at least
      `remainder_min` seconds after it (avoids an unusable tail)
      cut = scene boundary in window nearest to ideal (ties -> earlier)
            else ideal clamped into the window   (fixed fallback)
  the rest becomes the final clip if >= remainder_min, otherwise it is merged
  into the previous clip or dropped (per `short_remainder`) and the decision is
  recorded in `notes` — never silently.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass, field
from typing import Iterable, Literal

EPS = 1e-6
BoundaryKind = Literal["start", "scene", "fixed", "end"]


@dataclass
class Segment:
    index: int
    start: float
    end: float
    start_kind: BoundaryKind
    end_kind: BoundaryKind

    @property
    def duration(self) -> float:
        return round(self.end - self.start, 3)

    def to_dict(self) -> dict:
        d = asdict(self)
        d["duration"] = self.duration
        return d


@dataclass
class SegmentPlan:
    segments: list[Segment]
    method: str
    notes: list[str] = field(default_factory=list)
    scene_boundaries_used: int = 0
    dropped_tail: float = 0.0


@dataclass(frozen=True)
class SegmentParams:
    target: float = 30.0
    window: float = 5.0
    min_duration: float = 15.0
    max_duration: float = 35.0
    remainder_min: float = 10.0
    short_remainder: str = "drop"   # "drop" | "merge"
    overlap: float = 0.0


def _r(x: float) -> float:
    return round(x, 3)


def clean_scene_times(times: Iterable[float], duration: float) -> list[float]:
    """Sort, de-duplicate (1 ms), and keep strictly interior timestamps."""
    out: list[float] = []
    for t in sorted(float(t) for t in times):
        if t <= EPS or t >= duration - EPS:
            continue
        t = _r(t)
        if out and abs(out[-1] - t) < 0.001:
            continue
        out.append(t)
    return out


def plan_segments(duration: float, scene_times: Iterable[float] | None,
                  params: SegmentParams, method: str = "hybrid") -> SegmentPlan:
    if duration <= 0:
        raise ValueError("duration must be positive")
    p = params
    if p.min_duration > p.target or p.max_duration < p.target:
        raise ValueError("require min_duration <= target <= max_duration")

    scenes = clean_scene_times(scene_times or [], duration) if method == "hybrid" else []
    notes: list[str] = []

    if duration + EPS < p.remainder_min:
        raise ValueError(
            f"Video is {duration:.2f}s long, shorter than the {p.remainder_min:g}s minimum clip length")

    cuts: list[tuple[float, BoundaryKind]] = []
    pos = 0.0
    used_scenes = 0
    guard = 0
    while duration - pos > p.max_duration + EPS:
        guard += 1
        if guard > 100_000:  # defensive; cannot happen with validated params
            raise RuntimeError("segmentation did not converge")
        ideal = pos + p.target
        lo = max(pos + p.min_duration, ideal - p.window)
        hi = min(pos + p.max_duration, ideal + p.window)
        # Prefer cuts that leave a usable tail when that is achievable.
        tail_hi = duration - p.remainder_min
        if tail_hi >= lo - EPS:
            hi = min(hi, tail_hi)
        hi = max(hi, lo)

        candidates = [s for s in scenes if lo - EPS <= s <= hi + EPS and s > pos + EPS]
        if candidates:
            cut = min(candidates, key=lambda s: (abs(s - ideal), s))
            kind: BoundaryKind = "scene"
            used_scenes += 1
        else:
            cut = min(max(ideal, lo), hi)
            kind = "fixed"
        cut = _r(cut)
        if cut <= pos + EPS or cut >= duration - EPS:
            break
        cuts.append((cut, kind))
        pos = cut

    bounds: list[tuple[float, BoundaryKind]] = [(0.0, "start"), *cuts, (_r(duration), "end")]

    dropped = 0.0
    if len(bounds) > 2:
        tail = bounds[-1][0] - bounds[-2][0]
        if tail + EPS < p.remainder_min:
            if p.short_remainder == "merge":
                notes.append(f"Final remainder of {tail:.2f}s was below the "
                             f"{p.remainder_min:g}s minimum and was merged into the previous clip.")
                bounds.pop(-2)
            else:
                notes.append(f"Final remainder of {tail:.2f}s was below the "
                             f"{p.remainder_min:g}s minimum and was not rendered.")
                dropped = _r(tail)
                last_cut = bounds[-2][0]
                bounds = [*bounds[:-2], (last_cut, "end")]

    segments: list[Segment] = []
    for i in range(len(bounds) - 1):
        start, start_kind = bounds[i]
        end, end_kind = bounds[i + 1]
        if i > 0 and p.overlap > 0:
            start = max(0.0, start - p.overlap)
        start, end = _r(start), _r(min(end, duration))
        if end - start <= EPS:
            continue  # never emit zero-length clips
        segments.append(Segment(len(segments) + 1, start, end, start_kind, end_kind))

    if p.overlap > 0:
        notes.append(f"Clips after the first start {p.overlap:g}s early (overlap enabled).")
    if method == "hybrid" and not scenes:
        notes.append("No scene boundaries were available; fixed-interval cuts were used.")

    return SegmentPlan(segments=segments, method=method, notes=notes,
                       scene_boundaries_used=used_scenes, dropped_tail=dropped)
