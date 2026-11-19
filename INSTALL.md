# Installing CLIPFORGE

Requirements: **FFmpeg + FFprobe** (any recent build with libx264), **Python 3.10+** and
**Node.js 20+**. Docker is not required.

## macOS

```bash
brew install ffmpeg python@3.13 node
cd clipforge
scripts/setup.sh
scripts/start-dev.sh
```

Open http://127.0.0.1:5173. Homebrew's FFmpeg includes `h264_videotoolbox`, so the
**Hardware** encoder option works on Apple silicon and Intel Macs.

## Linux (Debian/Ubuntu)

```bash
sudo apt install ffmpeg python3 python3-venv nodejs npm
cd clipforge
scripts/setup.sh
scripts/start-dev.sh
```

If your distribution's Node.js is older than 20, install a current release from nodejs.org or
use nvm. The hardware option uses NVENC or Quick Sync when your FFmpeg build includes them and
they pass a test encode. Otherwise it falls back to libx264.

## Windows

1. Install FFmpeg, for example with `winget install Gyan.FFmpeg`. Make sure `ffmpeg` and
   `ffprobe` run in a new terminal. If they don't, add FFmpeg's `bin` folder to `PATH`.
2. Install Python 3.12+ from python.org (tick **Add to PATH**) and Node.js 20+ LTS.
3. In PowerShell:

```powershell
cd clipforge\backend
py -3 -m venv .venv
.venv\Scripts\pip install -r requirements-dev.txt
.venv\Scripts\python -m clipforge
```

In a second terminal:

```powershell
cd clipforge\frontend
npm install
npm run dev
```

Open http://127.0.0.1:5173.

## Manual start (any OS)

```bash
# API: http://127.0.0.1:8710
cd backend && .venv/bin/python -m clipforge
# UI: http://127.0.0.1:5173 (proxies /api to the API)
cd frontend && npm run dev
```

**Single-port mode.** Run `npm run build` in `frontend/`. When `frontend/dist` exists, the API
also serves the UI, so http://127.0.0.1:8710 gives you everything.

## Configuration

Backend settings are environment variables. All are optional.

| Variable | Default | Meaning |
|---|---|---|
| `CLIPFORGE_HOST` | `127.0.0.1` | Listen address. A non-loopback address requires `CLIPFORGE_API_TOKEN` |
| `CLIPFORGE_PORT` | `8710` | API port |
| `CLIPFORGE_DATA_DIR` | `clipforge/data` | Uploads, clips, exports, SQLite DB, logs |
| `CLIPFORGE_KEY_DIR` | `<data>/keys` | Ed25519 key pair (private key file is created with mode 0600) |
| `CLIPFORGE_MAX_UPLOAD_MB` | `8192` | Largest accepted upload |
| `CLIPFORGE_MAX_CONCURRENT_JOBS` | `1` | Worker threads (each runs one FFmpeg process at a time) |
| `CLIPFORGE_MAX_QUEUED_JOBS` | `20` | Queued + running cap. Further uploads get HTTP 429 |
| `CLIPFORGE_MIN_FREE_DISK_MB` | `2048` | Refuse uploads and stop jobs below this much free space |
| `CLIPFORGE_EXPORT_RETENTION` | `20` | ZIP files kept on disk. Older ones are deleted, but their history rows stay |
| `CLIPFORGE_FFMPEG` / `CLIPFORGE_FFPROBE` | `ffmpeg` / `ffprobe` | Executable paths |
| `CLIPFORGE_CORS_ORIGINS` | `http://localhost:5173,http://127.0.0.1:5173` | Allowed browser origins (comma-separated) |
| `CLIPFORGE_API_TOKEN` | unset | Requires a token on every API call except `/api/health` |
| `CLIPFORGE_FRONTEND_DIST` | `frontend/dist` | Built UI to serve in single-port mode |

Frontend settings (`frontend/.env.local`, see `.env.example`):

| Variable | Default | Meaning |
|---|---|---|
| `CLIPFORGE_API_TARGET` | `http://127.0.0.1:8710` | Where the Vite dev server proxies `/api` |
| `VITE_API_BASE` | empty (relative `/api`) | API origin baked into a production build when the API is hosted elsewhere |

Processing defaults (clip length, scene threshold, aspect ratio, quality and so on) are set on
the **Settings** page and stored in SQLite. You can override them per project on **New project**.

## Troubleshooting

| Symptom | Fix |
|---|---|
| Dashboard says FFmpeg/FFprobe not found | Install FFmpeg and make sure both binaries are on the `PATH` of the shell that starts the API, or set `CLIPFORGE_FFMPEG` / `CLIPFORGE_FFPROBE` |
| "Port already in use" | Something else is on 8710 or 5173. Set `CLIPFORGE_PORT=…` and `CLIPFORGE_API_TARGET=http://127.0.0.1:…`, or `CLIPFORGE_UI_PORT=…` |
| Upload rejected: "Invalid or unsupported video" | FFprobe could not read the file, or the container isn't MP4/MOV, MKV/WebM or AVI. Try `ffprobe yourfile` to see why |
| HTTP 413 | File is above `CLIPFORGE_MAX_UPLOAD_MB` |
| HTTP 429 | Queue is full. Wait for jobs to finish or raise `CLIPFORGE_MAX_QUEUED_JOBS` |
| HTTP 507 / "Not enough free disk space" | Free disk space or lower `CLIPFORGE_MIN_FREE_DISK_MB` |
| Job failed | Open the job in the Queue. The FFmpeg error output is under **FFmpeg error output**. Fix the cause and press **Retry** |
| Every clip ends at a fixed interval | The source has no hard cuts above the threshold. Lower **Scene detection threshold** (for example 0.2), or accept fixed intervals |
| Hardware encoder shows "none detected" | Your FFmpeg build or GPU has no working H.264 hardware encoder. Software encoding is used |
| Server stopped mid-job | On restart, interrupted jobs are requeued automatically (up to 2 times) |
| Logs | JSON lines on stdout and in `data/logs/clipforge.log` (rotated at 10 MB) |
