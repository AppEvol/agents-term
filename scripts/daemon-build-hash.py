#!/usr/bin/env python3
"""Compute the exact 40-char AGENTSTERM_BUILD_HASH produced by daemon-rs/build.rs.

Used by CI to verify whether the prebuilt Rust daemon binaries in the latest
GitHub Release already match the current source tree without compiling Rust.
"""

from __future__ import annotations

import hashlib
import os
import sys
from pathlib import Path
from typing import List, Tuple

STATIC_FILES = (
    "Cargo.toml",
    "Cargo.lock",
    "build.rs",
    "../docs/live-debugging.md",
    "../docs/agents_debugging_tips.md",
)


def _rust_component_sort_key(rel_parts: Tuple[str, ...]) -> Tuple[Tuple[int, bytes], ...]:
    """Match Rust's std::path::Component Ord: ParentDir (..) < Normal(name)."""
    key: List[Tuple[int, bytes]] = []
    for part in rel_parts:
        if part == ".":
            key.append((0, b""))
        elif part == "..":
            key.append((1, b""))
        else:
            key.append((2, os.fsencode(part)))
    return tuple(key)


def compute_build_hash(manifest_dir: Path) -> str:
    """Mirror daemon-rs/build.rs file collection, sorting, and SHA-256 framing."""
    manifest_dir = manifest_dir.resolve()
    entries: List[Tuple[Tuple[str, ...], Path]] = []

    for rel in STATIC_FILES:
        p = manifest_dir / rel
        if p.is_file():
            entries.append((tuple(Path(rel).parts), p))

    src_dir = manifest_dir / "src"
    if src_dir.is_dir():
        for root, dirs, files in os.walk(src_dir):
            dirs.sort()
            files.sort()
            for name in files:
                p = Path(root) / name
                if p.is_file():
                    rel_parts = p.relative_to(manifest_dir).parts
                    entries.append((rel_parts, p))

    entries.sort(key=lambda item: _rust_component_sort_key(item[0]))

    hasher = hashlib.sha256()
    for rel_parts, path in entries:
        rel_str = "/".join(rel_parts)
        hasher.update(rel_str.encode("utf-8"))
        hasher.update(b"\0")
        hasher.update(path.read_bytes())
        hasher.update(b"\0")

    return hasher.hexdigest()[:40]


def main(argv: List[str]) -> int:
    repo_root = Path(__file__).resolve().parent.parent
    manifest_dir = Path(argv[1]) if len(argv) > 1 else repo_root / "daemon-rs"
    print(compute_build_hash(manifest_dir))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
