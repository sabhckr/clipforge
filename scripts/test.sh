#!/usr/bin/env bash
# Runs every automated check: backend pytest suite, frontend unit tests, production build.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
(cd "$ROOT/backend" && .venv/bin/python -m pytest -q)
(cd "$ROOT/frontend" && npm test && npm run build)
