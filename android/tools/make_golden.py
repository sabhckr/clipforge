"""Generate golden files that pin the Android port to the desktop engine.

Run from the repository root with the backend's Python environment:

    backend/.venv/bin/python android/tools/make_golden.py

Outputs (committed) go to android/app/src/test/resources/golden/:
  segmentation.json        random + edge cases planned by backend/clipforge/segmentation.py
  floats.json              doubles serialized by Python's json (repr) for the Kotlin writer
  canonical_input.json     a nested document (pretty-printed)
  canonical_expected.txt   Python's canonical bytes for it
  python_signed.json       a manifest signed by backend/clipforge/provenance.py

The signing key is derived from a FIXED TEST-ONLY seed (bytes 0..31). It protects
nothing and must never be used for real manifests.
"""
from __future__ import annotations

import json
import math
import random
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "backend"))

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey  # noqa: E402

from clipforge import provenance  # noqa: E402
from clipforge.segmentation import SegmentParams, plan_segments  # noqa: E402

OUT = ROOT / "android" / "app" / "src" / "test" / "resources" / "golden"
TEST_SEED = bytes(range(32))


def seg_cases() -> list[dict]:
    rnd = random.Random(20261119)
    cases = []
    fixed = [
        (90, [], {}, "fixed"),
        (90, [27.5, 33.9, 62.0, 70.0], {}, "hybrid"),
        (70, [20.0, 40.0], {}, "hybrid"),
        (80, [28.0, 32.0], {}, "hybrid"),
        (100, [5.0, 12.0, 16.0], dict(target=30, window=20, min_duration=15, max_duration=50), "hybrid"),
        (68, [], {}, "fixed"),
        (66, [], dict(target=30, window=0, min_duration=30, max_duration=30, remainder_min=10), "fixed"),
        (66, [], dict(target=30, window=0, min_duration=30, max_duration=30, remainder_min=10,
                      short_remainder="merge"), "fixed"),
        (95, [31.0, 58.0], dict(overlap=2.0), "hybrid"),
        (9.5, [], {}, "fixed"),
        (35, [], {}, "hybrid"),
        (35.0005, [], {}, "fixed"),
        (26, [8.0, 17.0], dict(target=10, window=3, min_duration=5, max_duration=13, remainder_min=3), "hybrid"),
        (3600, [i * 7.3 for i in range(1, 400)], {}, "hybrid"),
        (61.2345, [0.0, 61.2345, 30.0004, 30.0001, 29.9996], {}, "hybrid"),
    ]
    for duration, scenes, params, method in fixed:
        cases.append((duration, scenes, params, method))
    for _ in range(400):
        target = rnd.choice([5, 10, 15, 20, 30, 45, 60, 90])
        window = rnd.choice([0, 1, 2.5, 5, 10])
        min_d = round(rnd.uniform(1, target), 3)
        max_d = target + window if rnd.random() < 0.7 else round(target + rnd.uniform(0, 30), 3)
        params = dict(target=float(target), window=float(window), min_duration=min_d, max_duration=float(max_d),
                      remainder_min=round(rnd.uniform(0.5, min(20, target)), 3),
                      short_remainder=rnd.choice(["drop", "merge"]),
                      overlap=rnd.choice([0.0, 0.0, 0.5, 1.0, round(rnd.uniform(0, min_d * 0.9), 3)]))
        duration = round(rnd.uniform(0.5, 900), 3)
        scenes = sorted(round(rnd.uniform(0, duration), rnd.choice([1, 2, 3, 6]))
                        for _ in range(rnd.randint(0, 60)))
        cases.append((duration, scenes, params, rnd.choice(["hybrid", "hybrid", "fixed"])))

    out = []
    for duration, scenes, params, method in cases:
        p = SegmentParams(**params)
        case = {"duration": duration, "scenes": scenes, "method": method,
                "params": {"target": p.target, "window": p.window, "min_duration": p.min_duration,
                           "max_duration": p.max_duration, "remainder_min": p.remainder_min,
                           "short_remainder": p.short_remainder, "overlap": p.overlap}}
        try:
            plan = plan_segments(duration, scenes, p, method=method)
            case["expected"] = {"segments": [s.to_dict() for s in plan.segments], "notes": plan.notes,
                                "scene_boundaries_used": plan.scene_boundaries_used,
                                "dropped_tail": plan.dropped_tail}
        except ValueError as exc:
            case["error"] = str(exc)
        out.append(case)
    return out


def floats() -> list[float]:
    rnd = random.Random(7)
    vals = [0.1, 0.2, 0.3, 1 / 3, 2 / 3, 1e16, 1e15, 9999999999999998.0, 1e-4, 1e-5, 0.0001234,
            123456789.123, 5e-324, 1.7976931348623157e308, 2.0 ** 52, 2.0 ** 53 + 2, 1e22, 1e23,
            -0.0, 0.0, -1.5, 100.0, 30.0, 0.30000000000000004, 1234.5678, 59.999, 29.97, 23.976023976023978,
            4.35, 2.675, 1.005, 0.125, 1e-7, 3.0e-310]
    for _ in range(3000):
        bits = rnd.getrandbits(64)
        v = struct.unpack("<d", struct.pack("<Q", bits))[0]
        if math.isfinite(v):
            vals.append(v)
    for _ in range(2000):
        vals.append(round(rnd.uniform(-1000, 100000), rnd.randint(0, 6)))
    return vals


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)

    (OUT / "segmentation.json").write_text(json.dumps(seg_cases()), encoding="utf-8")
    (OUT / "floats.json").write_text(json.dumps(floats()), encoding="utf-8")

    doc = {
        "zeta": [1, 2.5, -0.0, 1e-7, 1e16, 12345678901234567890, True, False, None],
        "alpha": {"nested": {"b": "Café — clip ✓ 🎬", "a": "quote \" backslash \\ tab \t nl \n ctrl \u0001 \u001f"}},
        "Upper": "keys sort by code point", "émoji": "😀", "num": 0.30000000000000004,
        "empty_obj": {}, "empty_arr": [], "unicode_esc": "  é",
    }
    (OUT / "canonical_input.json").write_text(json.dumps(doc, indent=2, ensure_ascii=False), encoding="utf-8")
    (OUT / "canonical_expected.txt").write_bytes(provenance.canonical_bytes(doc))

    key = Ed25519PrivateKey.from_private_bytes(TEST_SEED)

    class FixedKeys:
        def public_key(self):
            return key.public_key()

        def sign(self, data: bytes) -> bytes:
            return key.sign(data)

    manifest = {
        "manifest_version": "1.0",
        "generator": {"name": "CLIPFORGE", "version": "1.0.0", "ffmpeg": "7.1", "ai_used": False},
        "project_id": "0b6f3f0e-2d0c-4c3e-9b8a-2f8f9f0d1a11",
        "project_name": "Golden test — Café ✓",
        "created_at": "2026-11-19T10:00:00Z",
        "source": {"asset_id": "src-1", "sha256": "ab" * 32, "size_bytes": 123456789,
                   "original_filename": "VID_20261119_🎬.mp4", "ingested_at": "2026-11-19T09:59:00Z",
                   "media": {"container": "mp4", "duration": 95.123, "width": 1080, "height": 1920,
                             "rotation": 90, "fps": 29.97, "video_codec": "hevc", "has_audio": True,
                             "audio_codec": "aac"}},
        "processing": {"settings": {"target_duration": 30.0, "scene_threshold": 0.3, "overlap": 0.0,
                                    "max_duration": None},
                       "segmentation": {"segments": [{"start": 0.0, "end": 27.5}, {"start": 27.5, "end": 60.25}]},
                       "encoder_used": "libx264"},
        "outputs": [{"asset_id": "clip-1", "index": 1, "path": "clips/clip_0001.mp4", "sha256": "cd" * 32,
                     "size_bytes": 1000, "source_start": 0.0, "source_end": 27.5, "duration": 27.5,
                     "width": 1080, "height": 1920, "has_audio": True,
                     "boundary": {"start": "start", "end": "scene"}}],
    }
    signed = provenance.sign_manifest(manifest, FixedKeys())
    (OUT / "python_signed.json").write_text(json.dumps(signed, indent=2, ensure_ascii=False), encoding="utf-8")
    print("golden files written to", OUT)


if __name__ == "__main__":
    main()
