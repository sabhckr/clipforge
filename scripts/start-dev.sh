#!/usr/bin/env bash
# Development: API on 127.0.0.1:8710, Vite UI on http://127.0.0.1:5173 (proxies /api).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export CLIPFORGE_PORT="${CLIPFORGE_PORT:-8710}"
export CLIPFORGE_API_TARGET="${CLIPFORGE_API_TARGET:-http://127.0.0.1:$CLIPFORGE_PORT}"

(cd "$ROOT/backend" && exec .venv/bin/python -m clipforge) &
API_PID=$!
trap 'kill $API_PID 2>/dev/null || true' EXIT INT TERM
cd "$ROOT/frontend" && npm run dev
