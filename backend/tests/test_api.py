"""End-to-end API tests: upload -> background processing -> clips -> manifest -> export."""
import io
import json
import sys
import time
import zipfile

import pytest
from fastapi.testclient import TestClient

import clipforge.jobs as jobs_mod
from clipforge.api import create_app
from clipforge.ffmpeg_tools import FFmpegError

from .conftest import ffprobe_streams, upload, wait_for

ZERO_UUID = "00000000-0000-4000-8000-000000000000"


# ------------------------------------------------------------------ basics
def test_health(client):
    r = client.get("/api/health")
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "ok" and body["ai_used"] is False
    assert body["ffmpeg"] and body["ffprobe"]


def test_invalid_upload_garbage_bytes(client, tmp_path):
    bad = tmp_path / "notavideo.mp4"
    bad.write_bytes(b"\x00\x01garbage" * 1000)
    r = upload(client, bad)
    assert r.status_code == 415
    assert "Invalid or unsupported video" in r.json()["detail"]["message"]
    assert client.get("/api/jobs").json() == []
    assert list((client.app.state.cfg.uploads_dir).iterdir()) == []  # cleaned up


def test_unsupported_extension(client, short_video, tmp_path):
    r = upload(client, short_video, filename="clip.exe")
    assert r.status_code == 415


def test_extension_is_not_trusted(client, tmp_path):
    fake = tmp_path / "image.mov"
    fake.write_text("<html>not a video</html>")
    assert upload(client, fake).status_code == 415


def test_empty_upload(client, tmp_path):
    empty = tmp_path / "empty.mp4"
    empty.write_bytes(b"")
    r = upload(client, empty)
    assert r.status_code == 400
    assert r.json()["detail"]["message"] == "Empty upload"


def test_invalid_settings_rejected(client, short_video):
    r = upload(client, short_video, settings={"aspect_ratio": "4:3"})
    assert r.status_code == 422
    r = upload(client, short_video, settings={"unknown_field": 1})
    assert r.status_code == 422


def test_upload_size_limit(cfg, short_video):
    cfg.max_upload_bytes = 1024
    with TestClient(create_app(cfg)) as c:
        assert upload(c, short_video).status_code == 413


def test_invalid_and_unknown_ids(client):
    assert client.get("/api/jobs/not-a-uuid").status_code == 400
    assert client.get(f"/api/jobs/{ZERO_UUID}").status_code == 404
    assert client.get(f"/api/clips/{ZERO_UUID}").status_code == 404
    assert client.get("/api/clips/xyz/download").status_code == 400
    assert client.post(f"/api/jobs/{ZERO_UUID}/cancel").status_code == 404
    assert client.get(f"/api/exports/{ZERO_UUID}/download").status_code == 404


@pytest.mark.parametrize("path", [
    "/api/clips/..%2F..%2F..%2Fetc%2Fpasswd/download",
    "/api/jobs/..%2F..%2Fclipforge.db/manifest",
    "/api/clips/%2e%2e/thumbnail",
    "/api/exports/..%2Fkeys%2Fclipforge_ed25519_private.pem/download",
])
def test_path_traversal_rejected(client, path):
    r = client.get(path)
    assert r.status_code in (400, 404)
    assert b"PRIVATE KEY" not in r.content and b"SQLite" not in r.content


def test_traversal_filename_is_sanitised(client, short_video):
    r = upload(client, short_video, filename="../../../../tmp/evil.mp4")
    assert r.status_code == 201
    job = r.json()
    assert job["source"]["filename"] == "evil.mp4"
    cfg = client.app.state.cfg
    assert (cfg.uploads_dir / job["id"] / "source.mp4").exists()
    client.post(f"/api/jobs/{job['id']}/cancel")
    wait_for(client, job["id"])


# ------------------------------------------------------------ full pipeline
@pytest.fixture()
def completed_job(client, scene_video):
    r = upload(client, scene_video)
    assert r.status_code == 201, r.text
    job = wait_for(client, r.json()["id"])
    assert job["status"] == "completed", job
    return job


def test_hybrid_pipeline_end_to_end(client, completed_job):
    job = completed_job
    plan = job["plan"]
    assert plan["method_used"] == "hybrid"
    # Cuts at 29 and 58 are within ±5 s of 30 / 60 and must be used.
    clips = client.get(f"/api/jobs/{job['id']}/clips").json()
    assert [(c["start"], c["end"]) for c in clips] == [(0, 29.0), (29.0, 58.0), (58.0, 70.0)]
    assert [c["boundary"]["end"] for c in clips] == ["scene", "scene", "end"]
    assert job["clip_count"] == 3 and job["progress"] == 100
    cfg = client.app.state.cfg
    for c in clips:
        assert (c["width"], c["height"]) == (720, 1280)  # 720p vertical (fast test settings)
        assert c["has_audio"]
        path = cfg.data_dir / "jobs" / job["id"] / "clips" / c["filename"]
        info = ffprobe_streams(path)
        v = next(s for s in info["streams"] if s["codec_type"] == "video")
        assert (v["width"], v["height"]) == (720, 1280)
        assert abs(v["width"] / v["height"] - 9 / 16) < 1e-6
        assert any(s["codec_type"] == "audio" and s["codec_name"] == "aac"
                   for s in info["streams"])
        assert float(info["format"]["duration"]) == pytest.approx(c["end"] - c["start"], abs=0.15)
    # Downloads, stream, thumbnail
    r = client.get(clips[0]["urls"]["download"])
    assert r.status_code == 200 and r.headers["content-type"] == "video/mp4"
    assert "attachment" in r.headers["content-disposition"]
    r = client.get(clips[0]["urls"]["stream"], headers={"Range": "bytes=0-99"})
    assert r.status_code == 206 and len(r.content) == 100
    r = client.get(clips[0]["urls"]["thumbnail"])
    assert r.status_code == 200 and r.content[:2] == b"\xff\xd8"


def test_default_settings_produce_1080x1920(client, short_video):
    r = client.post("/api/jobs", params={"filename": "short.mp4",
                                         "settings": json.dumps({"preset": "ultrafast"})},
                    content=short_video.read_bytes())
    job = wait_for(client, r.json()["id"])
    assert job["status"] == "completed", job
    assert job["settings"]["aspect_ratio"] == "9:16" and job["settings"]["resolution"] == "1080p"
    clip = client.get(f"/api/jobs/{job['id']}/clips").json()[0]
    path = client.app.state.cfg.data_dir / "jobs" / job["id"] / "clips" / clip["filename"]
    v = next(s for s in ffprobe_streams(path)["streams"] if s["codec_type"] == "video")
    assert (v["width"], v["height"]) == (1080, 1920)
    assert v["codec_name"] == "h264" and v["pix_fmt"] == "yuv420p"


def test_fixed_segmentation_method(client, scene_video):
    r = upload(client, scene_video, settings={"segmentation_method": "fixed"})
    job = wait_for(client, r.json()["id"])
    clips = client.get(f"/api/jobs/{job['id']}/clips").json()
    assert [(c["start"], c["end"]) for c in clips] == [(0, 30), (30, 60), (60, 70)]


def test_scene_detection_failure_falls_back(client, scene_video, monkeypatch):
    def boom(*a, **k):
        raise FFmpegError("simulated detector crash")
    monkeypatch.setattr(jobs_mod, "detect_scenes", boom)
    job = wait_for(client, upload(client, scene_video).json()["id"])
    assert job["status"] == "completed"
    assert job["plan"]["method_used"].startswith("fixed")
    assert any("fell back" in n for n in job["plan"]["notes"])
    clips = client.get(f"/api/jobs/{job['id']}/clips").json()
    assert [c["end"] for c in clips] == [30, 60, 70]


def test_silent_mkv_source(client, silent_video):
    job = wait_for(client, upload(client, silent_video).json()["id"])
    assert job["status"] == "completed", job
    clips = client.get(f"/api/jobs/{job['id']}/clips").json()
    assert all(not c["has_audio"] for c in clips)


# ------------------------------------------------------------- provenance
def test_manifest_generation_and_signature(client, completed_job):
    job = completed_job
    doc = client.get(f"/api/jobs/{job['id']}/manifest").json()
    m = doc["manifest"]
    assert m["manifest_version"] == "1.0" and m["generator"]["ai_used"] is False
    assert m["source"]["sha256"] == job["source"]["sha256"]
    assert len(m["source"]["sha256"]) == 64
    assert len(m["outputs"]) == 3
    for out in m["outputs"]:
        assert out["parent_asset_id"] == m["source"]["asset_id"]
        assert len(out["sha256"]) == 64
    assert doc["signature"]["algorithm"] == "Ed25519"
    assert "PRIVATE" not in json.dumps(doc)
    report = client.post(f"/api/jobs/{job['id']}/verify").json()
    assert report["summary"]["ok"] is True
    assert report["summary"]["files_matched"] == 3


def _verify(client, manifest_bytes, files=(), trust="local"):
    multipart = [("manifest", ("manifest.json", manifest_bytes, "application/json"))]
    multipart += [("files", (name, data, "video/mp4")) for name, data in files]
    return client.post("/api/verify", files=multipart, data={"trust": trust}).json()


def test_verify_endpoint_valid_and_modified_manifest(client, completed_job):
    raw = client.get(f"/api/jobs/{completed_job['id']}/manifest").content
    assert _verify(client, raw)["signature"]["valid"] is True
    doc = json.loads(raw)
    doc["manifest"]["outputs"][0]["source_end"] = 999
    report = _verify(client, json.dumps(doc).encode())
    assert report["signature"]["valid"] is False
    assert report["summary"]["ok"] is False


def test_verify_detects_modified_video(client, completed_job):
    job_id = completed_job["id"]
    raw = client.get(f"/api/jobs/{job_id}/manifest").content
    clips = client.get(f"/api/jobs/{job_id}/clips").json()
    original = client.get(clips[0]["urls"]["download"]).content
    tampered = bytearray(original)
    tampered[len(tampered) // 2] ^= 0xFF
    report = _verify(client, raw, [(clips[0]["filename"], original),
                                   (clips[1]["filename"], bytes(tampered)),
                                   ("random.bin", b"unrelated")])
    statuses = [f["status"] for f in report["files"]]
    assert statuses == ["match", "modified", "unknown"]
    assert report["summary"]["ok"] is False

    # On-disk tamper detection
    path = client.app.state.cfg.data_dir / "jobs" / job_id / "clips" / clips[2]["filename"]
    with open(path, "r+b") as fh:
        fh.seek(100)
        fh.write(b"tamper")
    disk = client.post(f"/api/jobs/{job_id}/verify").json()
    assert disk["summary"]["files_modified"] == 1 and disk["summary"]["ok"] is False


def test_verify_rejects_foreign_key(client, completed_job, tmp_path):
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    raw = client.get(f"/api/jobs/{completed_job['id']}/manifest").content
    other = Ed25519PrivateKey.generate().public_key().public_bytes(
        serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo)
    r = client.post("/api/verify", files=[
        ("manifest", ("m.json", raw, "application/json")),
        ("public_key", ("other.pem", other, "application/x-pem-file"))]).json()
    assert r["signature"]["valid"] is False and r["signature"]["trusted_key"] is False


def test_verify_rejects_garbage_manifest(client):
    r = client.post("/api/verify", files=[("manifest", ("m.json", b"{not json", "text/plain"))])
    assert r.status_code == 400
    report = _verify(client, b'{"hello": 1}')
    assert report["signature"]["valid"] is False


def test_private_key_never_served(client):
    info = client.get("/api/provenance/key").json()
    assert "PUBLIC KEY" in info["public_key_pem"]
    assert "PRIVATE" not in json.dumps(info)
    key_file = client.app.state.cfg.key_dir / "clipforge_ed25519_private.pem"
    assert key_file.exists()
    if sys.platform != "win32":  # POSIX permission bits do not exist on Windows
        assert (key_file.stat().st_mode & 0o077) == 0


# ------------------------------------------------------------------ export
def test_bulk_zip_export_structure_and_integrity(client, completed_job):
    job_id = completed_job["id"]
    r = client.get(f"/api/jobs/{job_id}/download")
    assert r.status_code == 200 and r.headers["content-type"] == "application/zip"
    zf = zipfile.ZipFile(io.BytesIO(r.content))
    assert zf.testzip() is None
    names = set(zf.namelist())
    for expected in ["CLIPFORGE_EXPORT/project.json", "CLIPFORGE_EXPORT/manifest.json",
                     "CLIPFORGE_EXPORT/clips/clip_0001.mp4", "CLIPFORGE_EXPORT/clips/clip_0003.mp4",
                     "CLIPFORGE_EXPORT/thumbnails/clip_0001.jpg",
                     "CLIPFORGE_EXPORT/verification/public_key.pem",
                     "CLIPFORGE_EXPORT/verification/verification_report.json"]:
        assert expected in names, expected
    report = json.loads(zf.read("CLIPFORGE_EXPORT/verification/verification_report.json"))
    assert report["summary"]["ok"] is True and report["summary"]["files_matched"] == 3
    # Exported files re-verify against the exported manifest with the exported public key.
    pem = zf.read("CLIPFORGE_EXPORT/verification/public_key.pem")
    manifest = zf.read("CLIPFORGE_EXPORT/manifest.json")
    clip1 = zf.read("CLIPFORGE_EXPORT/clips/clip_0001.mp4")
    rep = client.post("/api/verify", files=[
        ("manifest", ("manifest.json", manifest, "application/json")),
        ("files", ("clip_0001.mp4", clip1, "video/mp4")),
        ("public_key", ("public_key.pem", pem, "application/x-pem-file"))]).json()
    assert rep["summary"]["ok"] is True

    history = client.get("/api/exports").json()
    assert len(history) == 1 and history[0]["clip_count"] == 3 and history[0]["available"]


def test_selected_clip_export(client, completed_job):
    job_id = completed_job["id"]
    clips = client.get(f"/api/jobs/{job_id}/clips").json()
    r = client.post(f"/api/jobs/{job_id}/exports", json={"clip_ids": [clips[1]["id"]]})
    assert r.status_code == 201, r.text
    exp = r.json()
    assert exp["clip_count"] == 1 and exp["verification_ok"] is True
    z = zipfile.ZipFile(io.BytesIO(client.get(exp["download_url"]).content))
    clip_files = [n for n in z.namelist() if n.startswith("CLIPFORGE_EXPORT/clips/")]
    assert clip_files == ["CLIPFORGE_EXPORT/clips/clip_0002.mp4"]
    assert json.loads(z.read("CLIPFORGE_EXPORT/project.json"))["selection"] == "selected"
    # A clip from another job / garbage IDs are rejected
    assert client.post(f"/api/jobs/{job_id}/exports",
                       json={"clip_ids": [ZERO_UUID]}).status_code == 400
    assert client.get(f"/api/jobs/{job_id}/download", params={"clips": "../x"}).status_code == 400


def test_export_retention_prunes_old_zips(client, completed_job):
    job_id = completed_job["id"]
    for _ in range(4):  # retention is 3 in the test config
        assert client.post(f"/api/jobs/{job_id}/exports", json={}).status_code == 201
    history = client.get("/api/exports").json()
    assert len(history) == 4
    assert [e["available"] for e in history] == [True, True, True, False]
    assert client.get(history[-1]["download_url"]).status_code == 410


# ------------------------------------------------- cancellation & recovery
def test_job_cancellation(client, scene_video):
    r = upload(client, scene_video, settings={"preset": "veryslow", "resolution": "1080p",
                                              "crf": 18})
    job_id = r.json()["id"]
    wait_for(client, job_id, statuses=("running",), timeout=30)
    time.sleep(0.5)
    r = client.post(f"/api/jobs/{job_id}/cancel")
    assert r.status_code == 200
    job = wait_for(client, job_id)
    assert job["status"] == "cancelled"
    assert client.get(f"/api/jobs/{job_id}/clips").json() == []
    assert not (client.app.state.cfg.jobs_dir / job_id).exists()
    assert client.post(f"/api/jobs/{job_id}/cancel").status_code == 409


def test_cancel_queued_job(client, scene_video, short_video):
    first = upload(client, scene_video, settings={"preset": "veryslow"}).json()["id"]
    second = upload(client, short_video).json()["id"]
    assert client.post(f"/api/jobs/{second}/cancel").status_code == 200
    assert client.get(f"/api/jobs/{second}").json()["status"] == "cancelled"
    client.post(f"/api/jobs/{first}/cancel")
    wait_for(client, first)


def test_failed_job_reports_error_and_retry_recovers(client, short_video, monkeypatch):
    real = jobs_mod.render_clip
    calls = {"n": 0}

    def flaky(*a, **k):
        calls["n"] += 1
        if calls["n"] == 1:
            raise FFmpegError("simulated encoder crash", "stderr tail: boom")
        return real(*a, **k)

    monkeypatch.setattr(jobs_mod, "render_clip", flaky)
    job_id = upload(client, short_video).json()["id"]
    job = wait_for(client, job_id)
    assert job["status"] == "failed"
    assert "simulated encoder crash" in job["error"] and "boom" in job["error_detail"]
    assert client.get(f"/api/jobs/{job_id}/clips").json() == []
    assert client.get(f"/api/jobs/{job_id}/manifest").status_code == 404

    r = client.post(f"/api/jobs/{job_id}/retry")
    assert r.status_code == 200
    job = wait_for(client, job_id)
    assert job["status"] == "completed" and job["clip_count"] == 1
    assert client.post(f"/api/jobs/{job_id}/retry").status_code == 409


def test_jobs_persist_and_interrupted_jobs_resume(cfg, short_video):
    with TestClient(create_app(cfg)) as c:
        done = wait_for(c, upload(c, short_video).json()["id"])
        assert done["status"] == "completed"
    # Simulate a crash mid-job: mark a fresh job as running in the database.
    from clipforge.db import Database
    with TestClient(create_app(cfg)) as c:
        jobs = c.get("/api/jobs").json()
        assert [j["id"] for j in jobs] == [done["id"]]  # persisted across restart
        assert len(c.get(f"/api/jobs/{done['id']}/clips").json()) == 1
        new_id = upload(c, short_video, settings={"preset": "veryslow"}).json()["id"]
        c.post(f"/api/jobs/{new_id}/cancel")
        wait_for(c, new_id)
    Database(cfg.db_path).update_job(new_id, status="running", stage="rendering", progress=40)
    with TestClient(create_app(cfg)) as c:
        job = wait_for(c, new_id)
        assert job["status"] == "completed"


def test_queue_limit(cfg, short_video):
    cfg.max_queued_jobs = 1
    with TestClient(create_app(cfg)) as c:
        first = upload(c, short_video, settings={"preset": "veryslow"}).json()["id"]
        r = upload(c, short_video)
        assert r.status_code == 429
        c.post(f"/api/jobs/{first}/cancel")
        wait_for(c, first)


def test_delete_job_removes_files(client, completed_job):
    job_id = completed_job["id"]
    cfg = client.app.state.cfg
    assert client.delete(f"/api/jobs/{job_id}").status_code == 200
    assert client.get(f"/api/jobs/{job_id}").status_code == 404
    assert not (cfg.jobs_dir / job_id).exists() and not (cfg.uploads_dir / job_id).exists()


def test_stats_reflect_real_data(client, completed_job):
    s = client.get("/api/stats").json()
    assert s["videos_processed"] == 1 and s["clips_generated"] == 3
    assert s["storage"]["clips_bytes"] > 0 and s["storage"]["disk_free_bytes"] > 0


def test_settings_roundtrip_and_defaults_apply(client, short_video):
    s = client.get("/api/settings").json()
    s["defaults"]["aspect_ratio"] = "1:1"
    s["defaults"]["resolution"] = "720p"
    s["defaults"]["preset"] = "ultrafast"
    assert client.put("/api/settings", json=s).status_code == 200
    r = client.post("/api/jobs", params={"filename": "a.mp4"}, content=short_video.read_bytes())
    job = wait_for(client, r.json()["id"])
    clip = client.get(f"/api/jobs/{job['id']}/clips").json()[0]
    assert (clip["width"], clip["height"]) == (720, 720)
    bad = {**s, "defaults": {**s["defaults"], "crf": 99}}
    assert client.put("/api/settings", json=bad).status_code == 422


def test_token_auth_when_configured(cfg):
    cfg.api_token = "s3cret-token"
    with TestClient(create_app(cfg)) as c:
        assert c.get("/api/health").status_code == 200
        assert c.get("/api/jobs").status_code == 401
        assert c.get("/api/jobs", headers={"Authorization": "Bearer wrong"}).status_code == 401
        assert c.get("/api/jobs", headers={"Authorization": "Bearer s3cret-token"}).status_code == 200
        assert c.post("/api/session", json={"token": "nope"}).status_code == 401
        assert c.post("/api/session", json={"token": "s3cret-token"}).status_code == 200
        assert c.get("/api/jobs").status_code == 200  # session cookie
