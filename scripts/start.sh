#!/usr/bin/env bash
# Single-port mode: build the UI once, then FastAPI serves UI + API on http://127.0.0.1:8710
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
(cd "$ROOT/frontend" && npm run build)
cd "$ROOT/backend" && exec .venv/bin/python -m clipforge
