import asyncio
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from typing import Any, Dict, List, Optional

DAEMON_DIR = Path(__file__).resolve().parent.parent
FIXTURES_DIR = Path(__file__).resolve().parent / "fixtures"
sys.path.insert(0, str(DAEMON_DIR))


class TempHomeTestCase(unittest.TestCase):
    """Points HOME at a temp dir and prepends a fake bin dir to PATH."""

    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="agentsterm-test-"))
        self.bin_dir = self.tmp / "bin"
        self.bin_dir.mkdir()
        self._env = {k: os.environ.get(k) for k in ("HOME", "PATH")}
        os.environ["HOME"] = str(self.tmp)
        os.environ["PATH"] = f"{self.bin_dir}:{self._env['PATH'] or ''}"

    def tearDown(self) -> None:
        for k, v in self._env.items():
            if v is None:
                os.environ.pop(k, None)
            else:
                os.environ[k] = v
        shutil.rmtree(self.tmp, ignore_errors=True)

    def fake_binary(self, name: str, body: str) -> Path:
        """Writes an executable Python script named `name` into the fake bin dir."""
        path = self.bin_dir / name
        path.write_text("#!/usr/bin/env python3\nimport json, sys, os, time\n" + body)
        path.chmod(0o755)
        return path

    def emit_lines_script(self, lines: List[Dict[str, Any]], args_log: Optional[Path] = None) -> str:
        log = f"open({str(args_log)!r}, 'a').write(json.dumps(sys.argv[1:]) + '\\n')\n" if args_log else ""
        return log + f"for line in {json.dumps(lines)!s}:\n    sys.stdout.write(json.dumps(line) + '\\n')\n"


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class DaemonProcess:
    """Runs the daemon as a subprocess with an isolated HOME."""

    def __init__(self, home: Path, extra_path: Optional[Path] = None) -> None:
        self.home = home
        self.port = free_port()
        env = {**os.environ, "HOME": str(home)}
        if extra_path:
            env["PATH"] = f"{extra_path}:{env.get('PATH', '')}"
        self.proc = subprocess.Popen(
            [sys.executable, str(DAEMON_DIR / "agentsterm-daemon"), "--host", "127.0.0.1", "--port", str(self.port)],
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
        )
        line = self.proc.stdout.readline()
        if "listening" not in line:
            self.stop()
            raise RuntimeError(f"daemon failed to start: {line}")
        cfg = json.loads((home / ".config" / "herdr-mobile" / "config.json").read_text())
        self.token = cfg["token"]
        self.base = f"http://127.0.0.1:{self.port}"

    def api(self, method: str, path: str, body: Any = None, token: Optional[str] = "default"):
        import urllib.error
        import urllib.request

        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(self.base + path, data=data, method=method)
        if token == "default":
            token = self.token
        if token:
            req.add_header("Authorization", f"Bearer {token}")
        if data is not None:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                return resp.status, json.loads(resp.read() or b"null")
        except urllib.error.HTTPError as err:
            with err:
                return err.code, json.loads(err.read() or b"null")

    def stop(self) -> None:
        if self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.proc.kill()
        if self.proc.stdout:
            self.proc.stdout.close()


def run_async(coro, timeout: float = 30.0):
    return asyncio.run(asyncio.wait_for(coro, timeout))
