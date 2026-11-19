"""Filesystem layout and path-safety helpers.

No path is ever built from client input: directories are keyed by server-
generated UUIDs, and every resolved path is checked to stay inside data_dir.
"""
from __future__ import annotations

import re
import shutil
import unicodedata
import uuid
from pathlib import Path

_UUID_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")


class UnsafePath(ValueError):
    pass


def new_id() -> str:
    return str(uuid.uuid4())


def is_uuid(value: str) -> bool:
    return isinstance(value, str) and bool(_UUID_RE.match(value))


def safe_join(base: Path, *parts: str) -> Path:
    base = base.resolve()
    target = base.joinpath(*parts).resolve()
    if target != base and base not in target.parents:
        raise UnsafePath(f"path escapes {base}")
    return target


def display_filename(name: str, max_len: int = 120) -> str:
    """Sanitise a client-supplied filename for display/metadata only."""
    name = unicodedata.normalize("NFKC", name or "")
    name = name.replace("\\", "/").split("/")[-1]
    name = "".join(ch for ch in name if ch.isprintable() and ch not in '<>:"|?*\x00')
    name = name.strip().lstrip(".") or "video"
    if len(name) > max_len:
        stem, dot, ext = name.rpartition(".")
        name = (stem[: max_len - len(ext) - 1] + "." + ext) if dot and len(ext) <= 8 else name[:max_len]
    return name


def project_name_from(filename: str) -> str:
    stem = display_filename(filename).rsplit(".", 1)[0]
    return stem[:80] or "Untitled project"


def dir_size(path: Path) -> int:
    total = 0
    if not path.exists():
        return 0
    for p in path.rglob("*"):
        try:
            if p.is_file() and not p.is_symlink():
                total += p.stat().st_size
        except OSError:
            continue
    return total


def disk_free(path: Path) -> int:
    return shutil.disk_usage(path).free


def remove_tree(path: Path) -> None:
    if path.exists():
        shutil.rmtree(path, ignore_errors=True)
