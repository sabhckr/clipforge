"""Launcher with a safety check: refuses to listen on a non-loopback address
unless an API token is configured.

    python -m clipforge                      # 127.0.0.1:8710
    CLIPFORGE_HOST=0.0.0.0 CLIPFORGE_API_TOKEN=... python -m clipforge
"""
from __future__ import annotations

import ipaddress
import os
import sys

import uvicorn


def _is_loopback(host: str) -> bool:
    if host in ("localhost",):
        return True
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


def main() -> None:
    host = os.environ.get("CLIPFORGE_HOST", "127.0.0.1")
    port = int(os.environ.get("CLIPFORGE_PORT", "8710"))
    if not _is_loopback(host) and not os.environ.get("CLIPFORGE_API_TOKEN"):
        sys.exit("Refusing to listen on a non-loopback address without CLIPFORGE_API_TOKEN. "
                 "See SECURITY.md before exposing CLIPFORGE on a network.")
    uvicorn.run("clipforge.main:app", host=host, port=port, log_level="warning",
                proxy_headers=False)


if __name__ == "__main__":
    main()
