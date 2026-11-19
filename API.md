# CLIPFORGE HTTP API

Base URL: `http://127.0.0.1:8710` by default. FastAPI also serves interactive docs at `/docs`.

**Errors** use the shape `{"detail": {"message": "…", …}}`. Request-validation errors from
FastAPI's query or body parsing use its standard `{"detail": [ … ]}` list.

**IDs** are UUID v4. A malformed ID returns **400**. A well-formed ID that doesn't exist
returns **404**.

**Auth.** When `CLIPFORGE_API_TOKEN` is set, every `/api/*` route except `GET /api/health` and
`/api/session` requires either `Authorization: Bearer <token>` or the session cookie set by
`POST /api/session`.

## System

| Method | Path | Description |
|---|---|---|
| GET | `/api/health` | `{status, app, version, ai_used:false, ffmpeg, ffprobe, ffmpeg_version}` |
| GET | `/api/stats` | Job counts by status, videos processed, clips generated, seconds, storage bytes, free disk, limits |
| GET | `/api/encoders` | `{software:"libx264", hardware:"h264_videotoolbox"|null}` (hardware is checked with a real test encode and cached) |
| GET | `/api/session` | `{auth_required, authenticated}` |
| POST | `/api/session` | Body `{token}`. Sets an HttpOnly, SameSite=Strict session cookie |
| GET / PUT | `/api/settings` | `{defaults: ProcessingSettings, owner_name, owner_contact, include_owner_in_manifest}` |

## Jobs

### `POST /api/jobs`: upload and queue

The video is sent as the **raw request body**. It is streamed to disk and never buffered whole.

Query parameters:

- `filename` (required): original name. Its extension must be one of
  `.mp4 .m4v .mov .mkv .webm .avi`. The name is sanitised and used only for display.
- `settings` (optional): a JSON object of ProcessingSettings fields that override the saved
  defaults.
- `name` (optional): project name.

```bash
curl -X POST "http://127.0.0.1:8710/api/jobs?filename=talk.mp4" \
  -H "Content-Type: application/octet-stream" --data-binary @talk.mp4
```

| Status | Meaning |
|---|---|
| 201 | Job object (status `queued`), including the server-probed `source.media` |
| 400 | Empty upload or interrupted upload |
| 413 | Larger than `CLIPFORGE_MAX_UPLOAD_MB` |
| 415 | Disallowed extension, or the content is not a supported video (checked with FFprobe) |
| 422 | Invalid settings |
| 429 | Queue full |
| 507 | Not enough free disk space |

**ProcessingSettings** (all fields optional; unknown fields are rejected):

| Field | Default | Range |
|---|---|---|
| `segmentation_method` | `hybrid` | `hybrid`, `fixed` |
| `target_duration` | 30 | 3–600 s |
| `scene_threshold` | 0.30 | 0.05–0.95 |
| `scene_window` | 5 | 0–120 s (±) |
| `min_duration` | 15 | 1–600 s, ≤ target |
| `max_duration` | null (= target + window) | ≥ target |
| `remainder_min` | 10 | 0.5–600 s |
| `short_remainder` | `drop` | `drop`, `merge` |
| `overlap` | 0 | 0–30 s, < min_duration |
| `aspect_ratio` | `9:16` | `9:16`, `1:1`, `16:9` |
| `resolution` | `1080p` | `1080p`, `720p` (short side) |
| `fit_mode` | `crop` | `crop`, `pad` |
| `crop_position` | `center` | `center`, `left`, `right`, `custom` |
| `crop_offset` | 0.5 | 0–1 (used when `custom`) |
| `crf` | 20 | 0–51 |
| `preset` | `medium` | x264 presets |
| `encoder` | `software` | `software`, `hardware` |
| `export_format` | `mp4` | `mp4`, `mov` |
| `audio_bitrate_kbps` | 192 | 64–512 |
| `audio_normalize` | false | EBU R128 loudnorm |
| `thumbnails` | true | |

### Job object

```json
{
  "id": "…", "name": "talk", "status": "running", "stage": "rendering", "progress": 41.2,
  "error": null, "error_detail": null, "settings": { … },
  "source": {"filename": "talk.mp4", "size_bytes": 123, "sha256": "…", "asset_id": "…",
             "media": {"duration": 95.0, "width": 1280, "height": 720, "rotation": 0, "fps": 30.0,
                       "video_codec": "h264", "has_audio": true, "audio_codec": "aac", …}},
  "plan": {"method_used": "hybrid", "scene_boundaries_detected": [27.0, 60.0, 78.0],
           "scene_boundaries_used": 2, "dropped_tail_seconds": 0, "notes": [], "segments": [ … ]},
  "encoder_used": "libx264", "clip_count": 3, "attempts": 1,
  "created_at": "…", "updated_at": "…", "started_at": "…", "finished_at": null, "has_manifest": false
}
```

`status` is one of `queued`, `running`, `completed`, `failed` or `cancelled`. `stage` is one of
`queued`, `starting`, `inspecting`, `detecting_scenes`, `planning`, `rendering`, `signing`,
`completed`, `failed` or `cancelled`.

| Method | Path | Description |
|---|---|---|
| GET | `/api/jobs?status=&limit=` | List jobs, newest first |
| GET | `/api/jobs/{job_id}` | One job |
| DELETE | `/api/jobs/{job_id}` | Delete the job and its upload, clips and manifest (409 while queued or running) |
| POST | `/api/jobs/{job_id}/cancel` | Cancel a queued or running job (409 otherwise) |
| POST | `/api/jobs/{job_id}/retry` | Re-run a failed or cancelled job from scratch (409 otherwise, 410 if the source is gone) |
| GET | `/api/jobs/{job_id}/clips` | Clips in index order |
| GET | `/api/jobs/{job_id}/manifest` | Signed manifest JSON (404 until the job completes) |
| POST | `/api/jobs/{job_id}/verify` | Re-hash the stored clips and check the manifest signature, then return a verification report |
| GET | `/api/jobs/{job_id}/download?clips=id,id` | Build and stream a `CLIPFORGE_EXPORT` ZIP (all clips, or the listed ones) |
| POST | `/api/jobs/{job_id}/exports` | Body `{clip_ids?: [...]}`. Builds a ZIP and returns the export record plus `verification_ok` |

## Clips

| Method | Path | Description |
|---|---|---|
| GET | `/api/clips?limit=` | Recent clips across all jobs |
| GET | `/api/clips/{clip_id}` | Clip metadata: index, start, end, duration, size, SHA-256, `has_audio`, boundary kinds, `urls` |
| GET | `/api/clips/{clip_id}/download` | File as an attachment |
| GET | `/api/clips/{clip_id}/stream` | Inline, with HTTP Range support (for `<video>`) |
| GET | `/api/clips/{clip_id}/thumbnail` | JPEG |

## Exports

| Method | Path | Description |
|---|---|---|
| GET | `/api/exports` | Export history: `available:false` once retention has removed the ZIP |
| GET | `/api/exports/{export_id}/download` | ZIP (410 if removed by retention) |

## Provenance

| Method | Path | Description |
|---|---|---|
| GET | `/api/provenance/key` | `{algorithm, key_id, fingerprint, public_key_pem, created_at}`. The private key is never returned |
| GET | `/api/provenance/public_key.pem` | Public key file |
| POST | `/api/verify` | Multipart form. See below |

`POST /api/verify` form fields:

- `manifest` (file, required): a signed `manifest.json`.
- `files` (files, 0–50): videos to check. Each is hashed as a stream and never stored.
- `public_key` (file, optional): a PEM to trust. It overrides `trust`.
- `trust`: `local` (default) requires this installation's key. `embedded` checks integrity only,
  using the key embedded in the manifest.

```bash
curl -X POST http://127.0.0.1:8710/api/verify \
  -F manifest=@CLIPFORGE_EXPORT/manifest.json \
  -F files=@CLIPFORGE_EXPORT/clips/clip_0001.mp4 \
  -F public_key=@CLIPFORGE_EXPORT/verification/public_key.pem
```

The response is a verification report:

```json
{
  "report_version": "1.0", "verified_at": "…", "project_id": "…",
  "signature": {"valid": true, "algorithm": "Ed25519", "key_id": "…", "trusted_key": true, "error": null},
  "files": [{"name": "clip_0001.mp4", "sha256": "…", "status": "match|modified|unknown",
             "role": "output|source|null", "asset_id": "…", "expected_sha256": "…"}],
  "missing": [],
  "summary": {"ok": true, "signature_valid": true, "trusted_key": true, "files_checked": 1,
              "files_matched": 1, "files_modified": 0, "files_unknown": 0, "files_missing": 0},
  "disclaimer": "…does not prevent copying and does not by itself establish copyright ownership."
}
```
