"""End-to-end tests of the minimal POSIX `daemon/install.sh` static binary bootstrapper."""

from __future__ import annotations

import hashlib
import http.server
import os
import platform
import shutil
import socketserver
import subprocess
import tempfile
import threading
import unittest
from pathlib import Path
from typing import Optional

from helpers import DAEMON_DIR, free_port

INSTALL_SCRIPT = DAEMON_DIR / "install.sh"


def _host_arch() -> str:
    machine = platform.machine().lower()
    if machine in ("x86_64", "amd64"):
        return "x86_64"
    if machine in ("aarch64", "arm64"):
        return "aarch64"
    return machine


class _QuietHandler(http.server.SimpleHTTPRequestHandler):
    downloads = 0
    asset_name = ""

    def do_GET(self) -> None:
        if self.path.lstrip("/") == self.asset_name:
            type(self).downloads += 1
        super().do_GET()

    def log_message(self, *_args: object) -> None:
        pass


class InstallScriptTest(unittest.TestCase):
    def setUp(self) -> None:
        if platform.system() != "Linux":
            self.skipTest("daemon/install.sh targets Linux")
        self.tmp = Path(tempfile.mkdtemp(prefix="agentsterm-install-"))
        self.home = self.tmp / "home"
        self.home.mkdir()
        self.release_dir = self.tmp / "release"
        self.release_dir.mkdir()
        self.asset_name = f"agentsterm-daemon-linux-{_host_arch()}"
        self.port = free_port()

        handler_cls = type(
            "ReleaseHandler",
            (_QuietHandler,),
            {"downloads": 0, "asset_name": self.asset_name},
        )
        self.handler_cls = handler_cls

        def make_handler(*args, **kwargs):
            return handler_cls(*args, directory=str(self.release_dir), **kwargs)

        self.httpd = socketserver.TCPServer(("127.0.0.1", 0), make_handler)
        self.http_port = self.httpd.server_address[1]
        self.http_thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.http_thread.start()

        self.env = {
            **os.environ,
            "HOME": str(self.home),
            "AGENTSTERM_RELEASE_BASE_URL": f"http://127.0.0.1:{self.http_port}",
            "AGENTSTERM_HOST": "127.0.0.1",
            "AGENTSTERM_KEEP_LEGACY": "1",
            "AGENTSTERM_NO_SYSTEMD": "1",
            "AGENTSTERM_NO_CRON": "1",
        }

    def tearDown(self) -> None:
        self.httpd.shutdown()
        self.httpd.server_close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def publish_fake_binary(self, label: str, exit_code: int = 0, bad_sha: bool = False) -> None:
        body = (
            "#!/usr/bin/env sh\n"
            f'echo ">>> fake-daemon {label} args: $*"\n'
            f'if [ "{exit_code}" -ne 0 ]; then exit {exit_code}; fi\n'
            f'echo "herdr://connect?host=127.0.0.1&port={self.port}&token=tok_{label}"\n'
        ).encode()
        (self.release_dir / self.asset_name).write_bytes(body)
        sha = "0" * 64 if bad_sha else hashlib.sha256(body).hexdigest()
        (self.release_dir / f"{self.asset_name}.sha256").write_text(f"{sha}  {self.asset_name}\n")

    def run_install(self, *args: str, env: Optional[dict] = None) -> subprocess.CompletedProcess:
        return subprocess.run(
            ["sh", str(INSTALL_SCRIPT), "--port", str(self.port), *args],
            env=env if env is not None else self.env,
            capture_output=True,
            text=True,
            timeout=30,
        )

    def test_downloads_verifies_skips_when_current_and_upgrades(self) -> None:
        self.publish_fake_binary("v1")
        r1 = self.run_install()
        self.assertEqual(r1.returncode, 0, r1.stdout + r1.stderr)
        self.assertEqual(self.handler_cls.downloads, 1)
        self.assertIn("fake-daemon v1 args: install --port", r1.stdout)
        self.assertIn("token=tok_v1", r1.stdout)

        # Second run with same checksum skips downloading the binary.
        r2 = self.run_install()
        self.assertEqual(r2.returncode, 0, r2.stdout + r2.stderr)
        self.assertEqual(self.handler_cls.downloads, 1)
        self.assertIn("already matches release SHA-256", r2.stdout)

        # Publishing v2 triggers download + atomic replacement.
        self.publish_fake_binary("v2")
        r3 = self.run_install("--force-restart")
        self.assertEqual(r3.returncode, 0, r3.stdout + r3.stderr)
        self.assertEqual(self.handler_cls.downloads, 2)
        self.assertIn("fake-daemon v2 args: install --port", r3.stdout)
        self.assertIn("--force-restart", r3.stdout)

    def test_checksum_mismatch_rejected_without_overwriting_existing_binary(self) -> None:
        self.publish_fake_binary("good")
        self.assertEqual(self.run_install().returncode, 0)
        installed = self.home / ".local" / "bin" / "agentsterm-daemon"
        before = installed.read_bytes()

        self.publish_fake_binary("corrupt", bad_sha=True)
        bad = self.run_install()
        self.assertEqual(bad.returncode, 3)
        self.assertIn("SHA-256 mismatch", bad.stderr)
        self.assertEqual(installed.read_bytes(), before)

    def test_propagates_daemon_install_exit_code(self) -> None:
        self.publish_fake_binary("unhealthy", exit_code=4)
        res = self.run_install()
        self.assertEqual(res.returncode, 4)


if __name__ == "__main__":
    unittest.main()
