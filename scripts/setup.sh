#!/usr/bin/env bash
# One-time setup: Python venv + backend deps, frontend deps. macOS / Linux.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

command -v ffmpeg >/dev/null || { echo "ffmpeg not found. See INSTALL.md (macOS: brew install ffmpeg)." >&2; exit 1; }
command -v ffprobe >/dev/null || { echo "ffprobe not found. It ships with ffmpeg; see INSTALL.md." >&2; exit 1; }
command -v node >/dev/null || { echo "Node.js 20+ not found. See INSTALL.md." >&2; exit 1; }

PY="${PYTHON:-}"
if [ -z "$PY" ]; then
  for c in python3.13 python3.12 python3.11 python3.10 python3; do
    if command -v "$c" >/dev/null && "$c" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)'; then PY="$c"; break; fi
  done
fi
[ -n "$PY" ] || { echo "Python 3.10+ not found. See INSTALL.md." >&2; exit 1; }

echo "Using $($PY --version)"
cd "$ROOT/backend"
[ -d .venv ] || "$PY" -m venv .venv
.venv/bin/pip install --quiet --upgrade pip
.venv/bin/pip install --quiet -r requirements-dev.txt

cd "$ROOT/frontend"
npm install --no-audit --no-fund
echo "Setup complete. Start with: scripts/start-dev.sh  (or scripts/start.sh for a single-port build)"
