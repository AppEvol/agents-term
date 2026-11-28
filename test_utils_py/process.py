"""Subprocess execution, output streaming, and cross-process file locking helpers."""

from __future__ import annotations

import fcntl
import hashlib
import os
import subprocess
import sys
import tempfile
from contextlib import contextmanager
from pathlib import Path
from typing import Dict, Generator, List, Optional, Sequence, Tuple


def stream_command(
    cmd: Sequence[str],
    *,
    cwd: Optional[Path] = None,
    env: Optional[Dict[str, str]] = None,
    log_path: Optional[Path] = None,
) -> Tuple[int, str]:
    """Run a command, mirroring merged stdout/stderr live while capturing full output."""
    captured_lines: List[str] = []
    log_file = log_path.open("w", encoding="utf-8") if log_path is not None else None
    try:
        with subprocess.Popen(
            list(cmd),
            cwd=str(cwd) if cwd is not None else None,
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            bufsize=1,
        ) as proc:
            assert proc.stdout is not None
            with proc.stdout:
                for line in proc.stdout:
                    sys.stdout.write(line)
                    sys.stdout.flush()
                    if log_file is not None:
                        log_file.write(line)
                    captured_lines.append(line)
            exit_code = proc.wait()
    finally:
        if log_file is not None:
            log_file.close()
    return exit_code, "".join(captured_lines)


def lock_path_for(name: str, *, vm_wide: bool = False, cwd: Optional[Path] = None) -> Path:
    """Resolve a lock file outside build directories so clean builds cannot unlink active locks."""
    base_env = os.environ.get("AGENTSTERM_LOCK_DIR")
    if base_env:
        lock_dir = Path(base_env)
        lock_dir.mkdir(parents=True, exist_ok=True)
    else:
        lock_dir = Path(tempfile.gettempdir()) / f"agentsterm-locks-{os.getuid()}"
        lock_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
    if vm_wide:
        return lock_dir / f"{name}.lock"
    checkout = str((cwd or Path.cwd()).resolve())
    digest = hashlib.sha256(checkout.encode("utf-8")).hexdigest()[:16]
    return lock_dir / f"{name}-{digest}.lock"


@contextmanager
def acquire_file_lock(name: str, *, vm_wide: bool = False, cwd: Optional[Path] = None) -> Generator[Path, None, None]:
    """Acquire an exclusive flock, printing a status notice when waiting on another process."""
    path = lock_path_for(name, vm_wide=vm_wide, cwd=cwd)
    scope = "on this machine" if vm_wide else "in this checkout"
    with path.open("a+", encoding="utf-8") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            print(
                f"Another {name} run is active {scope}; waiting for lock ({path})...",
                file=sys.stderr,
                flush=True,
            )
            fcntl.flock(lock, fcntl.LOCK_EX)
        try:
            lock.seek(0)
            lock.truncate()
            lock.write(f"pid={os.getpid()} cwd={Path.cwd().resolve()}\n")
            lock.flush()
            yield path
        finally:
            fcntl.flock(lock, fcntl.LOCK_UN)
