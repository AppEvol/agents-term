"""End-to-end test of daemon/install.sh in a sandbox HOME (detached-process mode)."""

import json
import os
import re
import shutil
import signal
import subprocess
import tempfile
import unittest
import urllib.request
from pathlib import Path

from helpers import DAEMON_DIR, free_port

INSTALL = DAEMON_DIR / "install.sh"


class InstallScriptTest(unittest.TestCase):
    def setUp(self):
        self.home = Path(tempfile.mkdtemp(prefix="agentsterm-install-"))
        self.port = free_port()
        self.env = {
            **os.environ,
            "HOME": str(self.home),
            "AGENTSTERM_HOST": "127.0.0.1",
            "AGENTSTERM_INSTALL_DIR": str(DAEMON_DIR.parent),  # use this checkout, no clone
            "AGENTSTERM_KEEP_LEGACY": "1",   # never touch a real daemon on this machine
            "AGENTSTERM_NO_SYSTEMD": "1",
        }

    def tearDown(self):
        pid_file = self.home / ".config" / "herdr-mobile" / "daemon.pid"
        if pid_file.exists():
            try:
                os.kill(int(pid_file.read_text().strip()), signal.SIGTERM)
            except (ProcessLookupError, ValueError):
                pass
        shutil.rmtree(self.home, ignore_errors=True)

    def install(self, *args):
        return subprocess.run(["bash", str(INSTALL), "--no-update", "--port", str(self.port), *args],
                              env=self.env, capture_output=True, text=True, timeout=90)

    def pid(self):
        return int((self.home / ".config" / "herdr-mobile" / "daemon.pid").read_text().strip())

    def test_install_starts_healthy_daemon_prints_pairing_uri_and_is_idempotent(self):
        first = self.install()
        self.assertEqual(first.returncode, 0, first.stdout + first.stderr)
        uri = first.stdout.strip().splitlines()[-1]
        m = re.fullmatch(r"herdr://connect\?host=127\.0\.0\.1&port=(\d+)&token=([\w-]+)", uri)
        self.assertIsNotNone(m, first.stdout)
        self.assertEqual(int(m.group(1)), self.port)
        token = m.group(2)
        self.assertIn("healthy on 127.0.0.1", first.stdout)

        req = urllib.request.Request(f"http://127.0.0.1:{self.port}/api/info",
                                     headers={"Authorization": f"Bearer {token}"})
        with urllib.request.urlopen(req, timeout=5) as resp:
            self.assertEqual(json.load(resp)["version"], "0.2.0")
        self.assertEqual(oct((self.home / ".config" / "herdr-mobile").stat().st_mode & 0o777), "0o700")

        first_pid = self.pid()
        second = self.install()
        self.assertEqual(second.returncode, 0, second.stdout + second.stderr)
        # Re-running replaces the previous instance and keeps the pairing token.
        self.assertEqual(second.stdout.strip().splitlines()[-1], uri)
        self.assertNotEqual(self.pid(), first_pid)
        with self.assertRaises(ProcessLookupError):
            for _ in range(50):
                os.kill(first_pid, 0)
                import time
                time.sleep(0.1)

    def test_bad_arguments_and_missing_prerequisites_have_distinct_exit_codes(self):
        bad_port = subprocess.run(["bash", str(INSTALL), "--port", "99999"], env=self.env,
                                  capture_output=True, text=True, timeout=30)
        self.assertEqual(bad_port.returncode, 2)
        self.assertIn("invalid port", bad_port.stderr)
        unknown = subprocess.run(["bash", str(INSTALL), "--frobnicate"], env=self.env,
                                 capture_output=True, text=True, timeout=30)
        self.assertEqual(unknown.returncode, 2)

        # No Tailscale address and no AGENTSTERM_HOST -> prerequisite error, nothing started.
        stub = self.home / "stub-bin"
        stub.mkdir()
        (stub / "tailscale").write_text("#!/bin/sh\nexit 0\n")
        (stub / "tailscale").chmod(0o755)
        env = {k: v for k, v in self.env.items() if k != "AGENTSTERM_HOST"}
        env["PATH"] = f"{stub}:{env['PATH']}"
        no_ip = subprocess.run(["bash", str(INSTALL), "--no-update", "--port", str(self.port)], env=env,
                               capture_output=True, text=True, timeout=30)
        self.assertEqual(no_ip.returncode, 3, no_ip.stdout + no_ip.stderr)
        self.assertIn("Tailscale has no IPv4", no_ip.stderr)
        self.assertFalse((self.home / ".config" / "herdr-mobile" / "daemon.pid").exists())

        missing_daemon = dict(self.env, AGENTSTERM_INSTALL_DIR=str(self.home / "nowhere"))
        gone = subprocess.run(["bash", str(INSTALL), "--no-update", "--port", str(self.port)], env=missing_daemon,
                              capture_output=True, text=True, timeout=30)
        self.assertEqual(gone.returncode, 3)


if __name__ == "__main__":
    unittest.main()
