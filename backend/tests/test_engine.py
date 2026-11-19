"""Engine tests against real FFmpeg using synthetic fixtures; outputs checked with ffprobe."""
import pytest

from clipforge.ffmpeg_tools import FFmpegError, probe
from clipforge.models import ProcessingSettings
from clipforge.render import build_video_filter, render_clip
from clipforge.scenes import detect_scenes, parse_showinfo

from .conftest import ffprobe_streams

KNOWN_CUTS = [12, 29, 41, 58]


def test_probe_reports_duration_resolution_audio(scene_video):
    m = probe(scene_video)
    assert m.duration == pytest.approx(70, abs=0.1)
    assert (m.width, m.height) == (640, 360)
    assert m.has_audio and m.audio_codec == "aac"
    assert m.container == "mp4"


def test_probe_rejects_non_media(tmp_path):
    bad = tmp_path / "fake.mp4"
    bad.write_bytes(b"this is not a video" * 100)
    with pytest.raises(FFmpegError):
        probe(bad)


def test_probe_rejects_audio_only(tmp_path):
    from .conftest import ffmpeg
    a = tmp_path / "tone.mp4"
    ffmpeg("-f", "lavfi", "-i", "sine=duration=2", "-c:a", "aac", str(a))
    with pytest.raises(FFmpegError, match="No video stream"):
        probe(a)


def test_probe_handles_rotation(rotated_video):
    m = probe(rotated_video)
    assert m.rotation in (90, 270)
    assert (m.width, m.height) == (720, 1280)  # display orientation


def test_scene_detection_finds_known_cuts(scene_video):
    times = detect_scenes(scene_video, 0.3)
    for cut in KNOWN_CUTS:
        assert any(abs(t - cut) < 0.1 for t in times), f"missed cut at {cut}: {times}"
    # Deterministic: identical result on a second run.
    assert detect_scenes(scene_video, 0.3) == times


def test_parse_showinfo_ignores_other_lines():
    text = ("[Parsed_showinfo_2 @ 0x1] n:   0 pts:  12288 pts_time:12      duration:512\n"
            "[other] pts_time:99\n"
            "[Parsed_showinfo_2 @ 0x1] n:   1 pts:  29696 pts_time:29.0417 duration:512\n")
    assert parse_showinfo(text) == [12.0, 29.0417]


@pytest.mark.parametrize("aspect,expected", [("9:16", (1080, 1920)), ("1:1", (1080, 1080)),
                                             ("16:9", (1920, 1080))])
def test_render_output_size_and_streams(scene_video, tmp_path, aspect, expected):
    s = ProcessingSettings(aspect_ratio=aspect, preset="ultrafast", crf=30)
    out = tmp_path / f"clip_{aspect.replace(':', 'x')}.mp4"
    render_clip(scene_video, out, 5.0, 4.0, s, probe(scene_video), "test-asset")
    info = ffprobe_streams(out)
    v = next(st for st in info["streams"] if st["codec_type"] == "video")
    a = [st for st in info["streams"] if st["codec_type"] == "audio"]
    assert (v["width"], v["height"]) == expected
    assert v["codec_name"] == "h264" and v["pix_fmt"] == "yuv420p"
    assert v.get("sample_aspect_ratio", "1:1") in ("1:1", "0:1")
    assert len(a) == 1 and a[0]["codec_name"] == "aac"  # audio preserved
    assert float(info["format"]["duration"]) == pytest.approx(4.0, abs=0.15)
    assert "mp4" in info["format"]["format_name"]


def test_render_faststart(scene_video, tmp_path):
    out = tmp_path / "fs.mp4"
    render_clip(scene_video, out, 0, 3, ProcessingSettings(preset="ultrafast"),
                probe(scene_video), "a")
    head = out.read_bytes()[:4096]
    assert head.find(b"moov") != -1 and head.find(b"moov") < max(head.find(b"mdat"), 4096)


def test_render_silent_source_has_no_audio(silent_video, tmp_path):
    out = tmp_path / "silent.mp4"
    m = probe(silent_video)
    assert not m.has_audio
    render_clip(silent_video, out, 0, 3, ProcessingSettings(preset="ultrafast"), m, "a")
    assert all(st["codec_type"] != "audio" for st in ffprobe_streams(out)["streams"])


def test_rotated_source_renders_upright_without_stretch(rotated_video, tmp_path):
    out = tmp_path / "rot.mp4"
    render_clip(rotated_video, out, 0, 2, ProcessingSettings(preset="ultrafast"),
                probe(rotated_video), "a")
    v = next(st for st in ffprobe_streams(out)["streams"] if st["codec_type"] == "video")
    assert (v["width"], v["height"]) == (1080, 1920)


def test_crop_positions_produce_different_filters():
    filters = {pos: build_video_filter(ProcessingSettings(crop_position=pos))
               for pos in ("left", "center", "right")}
    assert "*0.0000" in filters["left"] and "*0.5000" in filters["center"]
    assert "*1.0000" in filters["right"]
    custom = build_video_filter(ProcessingSettings(crop_position="custom", crop_offset=0.25))
    assert "*0.2500" in custom
    # Never stretch: crop mode scales with 'increase', pad mode with 'decrease'.
    assert "force_original_aspect_ratio=increase" in filters["center"]
    assert "force_original_aspect_ratio=decrease" in build_video_filter(
        ProcessingSettings(fit_mode="pad"))


def test_pad_mode_output_size(scene_video, tmp_path):
    out = tmp_path / "pad.mp4"
    render_clip(scene_video, out, 0, 2, ProcessingSettings(fit_mode="pad", preset="ultrafast"),
                probe(scene_video), "a")
    v = next(st for st in ffprobe_streams(out)["streams"] if st["codec_type"] == "video")
    assert (v["width"], v["height"]) == (1080, 1920)


def test_settings_validation():
    with pytest.raises(ValueError):
        ProcessingSettings(min_duration=40, target_duration=30)
    with pytest.raises(ValueError):
        ProcessingSettings(max_duration=20)
    with pytest.raises(ValueError):
        ProcessingSettings(aspect_ratio="4:3")
