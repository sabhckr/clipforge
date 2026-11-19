# Architecture

```
clipforge/
  backend/                 Python 3.10+, FastAPI
    clipforge/
      api.py               HTTP routes, upload streaming, auth middleware, static UI
      jobs.py              JobManager: bounded queue, worker threads, pipeline, cancel/retry/recovery
      ffmpeg_tools.py      ffprobe inspection, ffmpeg runner (progress + cancel), HW encoder probe
      scenes.py            scene-change detection (select=gt(scene,T) + showinfo)
      segmentation.py      pure hybrid/fixed segmentation rules
      render.py            filter graph (scale/crop/pad), encoder args, clip + thumbnail render
      provenance.py        SHA-256, Ed25519 key store, manifest build/sign/verify, reports
      exporter.py          CLIPFORGE_EXPORT ZIP packaging, on-disk verification, retention
      db.py                SQLite (WAL): jobs, clips, settings, exports
      storage.py           UUIDs, safe_join, filename sanitising, disk helpers
      models.py            Pydantic ProcessingSettings / AppSettings
      config.py            environment configuration
      logging_setup.py     JSON-lines logging
      __main__.py          launcher with non-loopback safety check
    tests/                 pytest suites (synthetic FFmpeg fixtures)
  frontend/                React 19 + Vite, plain CSS, lucide-react
    src/lib/api.js         API client (configurable base URL), XHR streaming upload
    src/pages/             Dashboard, NewProject, Queue, Gallery, ClipPreview, ExportCenter, Settings, Verify
    src/components/        SettingsForm, Dropzone, AuthGate, ui primitives
  scripts/                 setup.sh, start-dev.sh, start.sh, test.sh
  data/                    (runtime, git-ignored) see "Storage layout"
```

The frontend and backend are separate. The UI only talks to `/api/*` through `api.js`. By
default it uses relative URLs, so the same build works behind the Vite proxy, the
single-port FastAPI static mount, or a reverse proxy. Set `VITE_API_BASE` to point it at an
API on another origin.

## Request → job → clips

```
browser ──POST /api/jobs (raw body stream)──▶ api.create_job
           ├─ extension allow-list (first gate only)
           ├─ settings validated (Pydantic, extra=forbid)
           ├─ queue cap (429), Content-Length vs limit (413) and free disk (507)
           ├─ stream to data/uploads/<job>/source.upload, SHA-256 computed while streaming
           ├─ ffprobe: container must be MP4/MOV, Matroska/WebM or AVI, with a video stream
           │  and positive duration; anything else is deleted and gets 415
           └─ INSERT job (queued) → JobManager.enqueue(job_id) → 201
                                     │
worker thread ◀──────────────────────┘
  queued→running (atomic UPDATE … WHERE status='queued')
  A/B  probe again, disk check
  C    detect_scenes (ffmpeg, cancellable, progress 2→20%)  ── on error: fixed fallback + note
  D-G  plan_segments(duration, scenes, params)               ── pure function
  H    for each segment: render_clip → .partial file → rename
       ffprobe the output (must equal the target W×H) → SHA-256 → thumbnail → INSERT clip
       (progress 20→95%, weighted by clip duration)
  I-J  build manifest → Ed25519 sign → write manifest.json atomically
       status=completed
  on Cancelled: delete outputs, status=cancelled
  on any exception: delete outputs, status=failed, error + FFmpeg stderr tail stored
```

Jobs never run in the request handler. Each worker runs one FFmpeg subprocess at a time, and
`CLIPFORGE_MAX_CONCURRENT_JOBS` sets the number of workers. Progress comes from FFmpeg's
`-progress pipe:1` `out_time_us` and is written to SQLite at most every 0.5 s. The UI polls.

### Cancellation

`POST /cancel` on a queued job flips its status atomically. On a running job it sets a
`threading.Event`. A watcher thread in `run_ffmpeg` terminates the FFmpeg process (SIGTERM,
then SIGKILL after 5 s), the pipeline raises `Cancelled`, and partial outputs are removed.

### Recovery

On startup, jobs left `running` (crash or kill) are cleaned and requeued, at most 2 times.
After that they are marked failed. Jobs left `queued` are requeued. On a clean shutdown,
running jobs are put back in the queue. Failed or cancelled jobs can be retried by hand from a
clean slate.

## Segmentation algorithm (`segmentation.py`)

The parameters are target `T` (30), window `W` (±5), min `m` (15), max `M` (default `T+W` =
35) and remainder minimum `r` (10).

```
pos = 0
while duration - pos > M:
    ideal = pos + T
    lo = max(pos + m, ideal - W)
    hi = min(pos + M, ideal + W)
    if duration - r >= lo: hi = min(hi, duration - r)     # avoid an unusable tail if we can
    cut = scene in [lo, hi] closest to ideal (ties → earlier), else clamp(ideal, lo, hi)
    pos = cut
final clip = [pos, duration]
if a final clip after a cut is shorter than r:
    short_remainder = "drop"  → not rendered; recorded in plan.notes and dropped_tail_seconds
    short_remainder = "merge" → merged into the previous clip; recorded in plan.notes
```

What this guarantees (and the tests check):

- Clips are never zero-length and never run past the source.
- Boundaries are strictly increasing with no duplicates.
- Clips only overlap when `overlap > 0`.
- Every clip except the last is between `m` and `M` seconds.
- `covered + dropped == duration`, so nothing disappears without being reported.
- The same inputs always produce the same plan.

Scene timestamps are cleaned before use: sorted, de-duplicated to 1 ms, and the first and last
frame are excluded.

## Formatting (`render.py`)

```
scale=trunc(iw*sar/2)*2:ih,setsar=1                       # square pixels
crop:  scale=W:H:force_original_aspect_ratio=increase, crop=W:H:(iw-W)*f:(ih-H)*f
pad:   scale=W:H:force_original_aspect_ratio=decrease, pad=W:H:(ow-iw)/2:(oh-ih)/2
setsar=1,format=yuv420p
```

`f` is 0 (left/top), 0.5 (center), 1 (right/bottom), or a custom value. FFmpeg rotates frames
upright using the display matrix, and FFprobe swaps the reported width and height for 90° and
270° sources. Seeking uses input-side `-ss` with re-encoding, which is frame-accurate. The
output gets `-movflags +faststart`. Source metadata and chapters are stripped, and a disclosed
`comment=CLIPFORGE asset_id=<uuid>` tag is added.

**Encoders.** `software` uses libx264 with CRF and the chosen preset. `hardware` probes
`h264_videotoolbox`, `h264_nvenc`, `h264_qsv` and `h264_amf` with a real test encode and uses
the first that works. If a hardware encode fails, that clip is re-rendered with libx264, and
the rest of the job stays on software.

## Provenance (`provenance.py`)

- On first start, a key pair is created in `CLIPFORGE_KEY_DIR`. The private key is PKCS#8 PEM
  with mode 0600, in a directory with mode 0700. No route returns it.
- The manifest records: generator name and version, the FFmpeg version, `ai_used: false`,
  project ID, the source asset (UUID, SHA-256, size, sanitised original filename, media info),
  the processing settings, the segmentation plan (detected and used cuts, notes), the encoder
  used, and the outputs. Each output has its asset ID, `parent_asset_id`, path, SHA-256, size,
  source start and end, dimensions and boundary kinds.
- Canonical form for signing: `json.dumps(manifest, sort_keys=True, separators=(",", ":"),
  ensure_ascii=False)` encoded as UTF-8. The signature block stores the algorithm, the
  canonicalization rule, `key_id` (first 16 hex characters of SHA-256 of the raw public key),
  the public key PEM, and a base64 signature.
- Verification checks the signature against a trusted key (the local key by default, or a
  supplied PEM). It then matches files by SHA-256 against the outputs and the source. A file
  whose name matches an output but whose hash does not is reported as **modified**.

## Storage layout

```
data/
  clipforge.db                 SQLite (jobs, clips, settings, exports)
  uploads/<job_id>/source.<ext>
  jobs/<job_id>/clips/clip_0001.mp4
  jobs/<job_id>/thumbnails/clip_0001.jpg
  jobs/<job_id>/manifest.json  signed
  exports/<export_id>.zip      newest N kept
  keys/clipforge_ed25519_private.pem   (0600)
  keys/clipforge_ed25519_public.pem
  logs/clipforge.log
```

Every path comes from server-generated UUIDs or fixed names. Database rows store paths relative
to `data/`. Each path is resolved with `safe_join`, which rejects anything that escapes the base
directory.
