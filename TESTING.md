# Testing

```bash
scripts/test.sh                          # everything below
cd backend && .venv/bin/python -m pytest -q          # backend only
cd backend && .venv/bin/python -m pytest -q -k cancel   # a subset
cd frontend && npm test && npm run build              # frontend only
```

The backend tests need `ffmpeg` and `ffprobe` on `PATH`. They run real FFmpeg; nothing is
mocked except where a test deliberately injects a failure.

## Fixtures: no third-party footage

`tests/conftest.py` builds every test video from FFmpeg's built-in `lavfi` generators
(`testsrc2`, `smptebars`, `rgbtestsrc` and others, plus a `sine` tone). Concatenating visually
different generators gives **hard cuts at known timestamps**:

| Fixture | Content |
|---|---|
| `scene_video` | 70 s, 640×360, AAC audio, cuts at 12 / 29 / 41 / 58 s |
| `short_video` | 22 s single scene with audio |
| `silent_video` | 24 s MKV, no audio stream |
| `rotated_video` | 1280×720 coded, 90° display-matrix rotation |

Every API test gets a fresh temporary data directory, database and key pair.

## Coverage

| Requirement | Tests |
|---|---|
| Health endpoint | `test_health` |
| Invalid upload / unsupported format / empty upload | `test_invalid_upload_garbage_bytes`, `test_unsupported_extension`, `test_extension_is_not_trusted`, `test_empty_upload`, `test_upload_size_limit`, `test_probe_rejects_non_media`, `test_probe_rejects_audio_only` |
| Duration inspection | `test_probe_reports_duration_resolution_audio` |
| Rotation handling | `test_probe_handles_rotation`, `test_rotated_source_renders_upright_without_stretch` |
| Scene detection (finds known cuts, deterministic) | `test_scene_detection_finds_known_cuts`, `test_parse_showinfo_ignores_other_lines` |
| Fixed segmentation and fallback | `test_fixed_interval_exact_multiple`, `test_fixed_segmentation_method`, `test_scene_detection_failure_falls_back` |
| Hybrid segmentation | `test_hybrid_snaps_to_nearest_scene_in_window`, `test_hybrid_ignores_scenes_outside_window`, `test_tie_breaks_to_earlier_boundary`, `test_min_duration_respected_by_window`, `test_hybrid_pipeline_end_to_end` (cuts at 29 and 58 s used) |
| Short / long final remainder | `test_short_remainder_avoided_when_possible`, `test_short_remainder_dropped_with_note_when_unavoidable`, `test_short_remainder_merge_policy`, `test_long_final_remainder_kept` |
| Rule invariants (no zero-length, no overlap, no duplicates, within source, nothing silently dropped) | `test_invariants_hold_across_durations` (20 combinations), `test_overlap_only_when_configured`, `test_duplicate_and_unsorted_scenes_…` |
| Output resolution and aspect ratio (FFprobe on real outputs) | `test_render_output_size_and_streams[9:16, 1:1, 16:9]`, `test_default_settings_produce_1080x1920`, `test_pad_mode_output_size`, `test_hybrid_pipeline_end_to_end` |
| H.264 / yuv420p / AAC / fast-start | `test_render_output_size_and_streams`, `test_render_faststart` |
| Audio preservation | `test_render_output_size_and_streams`, `test_hybrid_pipeline_end_to_end`, `test_render_silent_source_has_no_audio`, `test_silent_mkv_source` |
| Crop positions / no stretching | `test_crop_positions_produce_different_filters` |
| Manifest generation | `test_manifest_generation_and_signature` |
| Signature verification | `test_verify_endpoint_valid_and_modified_manifest`, `test_verify_rejects_foreign_key`, `test_verify_rejects_garbage_manifest` |
| Modified-file detection (uploaded and on disk) | `test_verify_detects_modified_video` |
| Private key never exposed, file mode 0600 | `test_private_key_never_served` |
| ZIP integrity and structure, selected export, retention | `test_bulk_zip_export_structure_and_integrity` (`testzip()`, layout, re-verifies exported files using the exported key), `test_selected_clip_export`, `test_export_retention_prunes_old_zips` |
| Invalid job IDs | `test_invalid_and_unknown_ids` |
| Path traversal | `test_path_traversal_rejected` (4 encoded variants), `test_traversal_filename_is_sanitised` |
| Job cancellation | `test_job_cancellation` (running: FFmpeg killed, outputs removed), `test_cancel_queued_job` |
| Failed-job recovery | `test_failed_job_reports_error_and_retry_recovers` (error and stderr surfaced, retry succeeds), `test_jobs_persist_and_interrupted_jobs_resume` (restart persistence, crashed job requeued and completed) |
| Queue limit, delete, stats, settings, token auth | `test_queue_limit`, `test_delete_job_removes_files`, `test_stats_reflect_real_data`, `test_settings_roundtrip_and_defaults_apply`, `test_token_auth_when_configured` |

Frontend (`src/__tests__/frontend.test.jsx`, Vitest + Testing Library + jsdom): formatting
helpers, upload pre-validation, API error parsing, hash routing, settings validation that
mirrors the server, a SettingsForm render and change, conditional disabling, status badge and
progress clamp, and verification-report rendering with a modified file. `npm run build` checks
the production bundle compiles.

## Last recorded run

Run on macOS (Darwin 27, Apple silicon), FFmpeg 9.0.2, Python 3.13.16, Node 26.9.0, on
2026-11-19:

```
backend:  85 passed, 1 warning in 184.66s
frontend: Test Files 1 passed (1), Tests 9 passed (9)
build:    vite build ✓ (index JS 285.9 kB / 87.8 kB gzip)
```

The one warning is a Starlette deprecation notice about its test client's `httpx` backend. It
does not affect behaviour.

## Continuous integration

`.github/workflows/ci.yml` runs the backend suite on **Ubuntu, macOS and Windows**, each with
that platform's FFmpeg package. It also runs the frontend tests and production build on Node
22. CI runs on every push and pull request.

## Phone-style input and mobile layout (manual)

- A 40 s HEVC (H.265) clip, coded 1920×1080 with a 90° display rotation and stereo AAC (how
  Android phones typically record portrait video), was uploaded to a running server. It was
  inspected as 1080×1920 and produced two upright 1080×1920 H.264/AAC clips (30 s and 10 s).
- Every page was loaded at 375×812 with an Android Chrome user agent and checked for
  horizontal overflow. Two pages (Export center, Ownership) overflowed and were fixed. All
  pages now fit at 375 px.

## Manual end-to-end check (also performed)

With the servers from `scripts/start-dev.sh` running, a 95 s 1280×720 synthetic video with
cuts at 27 / 60 / 78 s was dropped on the dashboard dropzone, uploaded and processed with
default settings. Result: 3 clips (0–27, 27–60, 60–95). The first two cuts snapped to scene
changes. The final 35 s segment fits within the 35 s maximum, so no extra cut was needed. All
clips were 1080×1920 with AAC audio. A selected-clip ZIP from the gallery contained the
documented layout and a passing verification report. The Ownership page's on-disk
verification reported a valid signature and 3/3 files matched. The clip preview played the
1080×1920 file.
