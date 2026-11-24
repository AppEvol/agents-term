"""End-to-end tests of daemon/install.sh in a sandbox HOME (detached-process mode).

The daemon is installed from a throwaway git repository holding a copy of this checkout's daemon/,
so the tests can publish new commits and check that every run ends with the latest code running.
"""

import json
import os
import shutil
import signal
import subprocess
import tempfile
import time
import unittest
import urllib.request
from pathlib import Path

from helpers import DAEMON_DIR, free_port

GIT = ["git", "-c", "user.name=test", "-c", "user.email=test@example.invalid", "-c", "commit.gpgsign=false"]


class InstallScriptTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="agentsterm-install-"))
        self.home = self.tmp / "home"
        self.home.mkdir()
        self.port = free_port()
        # "origin": a repo whose main branch contains daemon/, like the real agents-term repo.
        self.origin = self.tmp / "origin"
        shutil.copytree(DAEMON_DIR, self.origin / "daemon", ignore=shutil.ignore_patterns("__pycache__", "tests"))
        self.git("init", "-q", "-b", "main")
        self.commit("initial daemon")
        self.install_dir = self.home / ".local" / "share" / "agentsterm" / "agents-term"
        self.env = {
            **os.environ,
            "HOME": str(self.home),
            "AGENTSTERM_HOST": "127.0.0.1",
            "AGENTSTERM_REPO_URL": f"file://{self.origin}",
            "AGENTSTERM_KEEP_LEGACY": "1",  # never touch a real daemon on this machine
            "AGENTSTERM_NO_SYSTEMD": "1",
        }
        self.env.pop("AGENTSTERM_INSTALL_DIR", None)
        self.env.pop("AGENTSTERM_DAEMON_COMMIT", None)

    def tearDown(self):
        pid_file = self.home / ".config" / "herdr-mobile" / "daemon.pid"
        if pid_file.exists():
            try:
                os.kill(int(pid_file.read_text().strip()), signal.SIGTERM)
            except (ProcessLookupError, ValueError):
                pass
        shutil.rmtree(self.tmp, ignore_errors=True)

    def git(self, *args):
        return subprocess.run(
            [*GIT, "-C", str(self.origin), *args], check=True, capture_output=True, text=True
        ).stdout.strip()

    def commit(self, message):
        (self.origin / "CHANGELOG").write_text(message + "\n")
        self.git("add", "-A")
        self.git("commit", "-q", "-m", message)
        return self.git("rev-parse", "HEAD")

    def install(self, *args, script=None):
        # First run: the script comes from origin (as the bootstrap does after cloning).
        script = script or (
            self.install_dir / "daemon" / "install.sh"
            if self.install_dir.exists()
            else self.origin / "daemon" / "install.sh"
        )
        return subprocess.run(
            ["bash", str(script), "--port", str(self.port), *args],
            env=self.env,
            capture_output=True,
            text=True,
            timeout=90,
        )

    def pid(self):
        return int((self.home / ".config" / "herdr-mobile" / "daemon.pid").read_text().strip())

    def info(self, token):
        req = urllib.request.Request(
            f"http://127.0.0.1:{self.port}/api/info", headers={"Authorization": f"Bearer {token}"}
        )
        with urllib.request.urlopen(req, timeout=5) as resp:
            return json.load(resp)

    def assert_gone(self, pid):
        for _ in range(50):
            try:
                os.kill(pid, 0)
            except ProcessLookupError:
                return
            time.sleep(0.1)
        self.fail(f"old daemon pid {pid} is still running")

    def test_every_run_ends_with_the_latest_commit_running(self):
        commit_a = self.git("rev-parse", "HEAD")

        # Fresh VM: clones, starts, health-checks, prints the pairing URI.
        first = self.install()
        self.assertEqual(first.returncode, 0, first.stdout + first.stderr)
        uri = first.stdout.strip().splitlines()[-1]
        self.assertRegex(uri, rf"^herdr://connect\?host=127\.0\.0\.1&port={self.port}&token=[\w-]+$")
        token = uri.rsplit("token=", 1)[1]
        self.assertEqual(self.info(token)["commit"], commit_a)
        self.assertEqual(oct((self.home / ".config" / "herdr-mobile").stat().st_mode & 0o777), "0o700")
        pid_a = self.pid()

        # Nothing new upstream: the daemon already runs the latest commit, so it is not restarted.
        again = self.install()
        self.assertEqual(again.returncode, 0, again.stdout + again.stderr)
        self.assertIn("already running the latest commit", again.stdout)
        self.assertEqual(again.stdout.strip().splitlines()[-1], uri)
        self.assertEqual(self.pid(), pid_a)
        os.kill(pid_a, 0)  # still alive

        # A new commit lands upstream: the next run must restart onto exactly that commit.
        commit_b = self.commit("daemon change B")
        updated = self.install()
        self.assertEqual(updated.returncode, 0, updated.stdout + updated.stderr)
        self.assertIn(f"commit {commit_b[:12]}", updated.stdout)
        self.assertEqual(self.info(token)["commit"], commit_b)
        self.assertEqual(updated.stdout.strip().splitlines()[-1], uri)  # token survives updates
        pid_b = self.pid()
        self.assertNotEqual(pid_b, pid_a)
        self.assert_gone(pid_a)

        # --force-restart restarts even when the commit is unchanged.
        forced = self.install("--force-restart")
        self.assertEqual(forced.returncode, 0, forced.stdout + forced.stderr)
        self.assertNotEqual(self.pid(), pid_b)
        self.assertEqual(self.info(token)["commit"], commit_b)

        # A daemon that died is started again even though the checkout did not change.
        os.kill(self.pid(), signal.SIGTERM)
        self.assert_gone(self.pid())
        revived = self.install()
        self.assertEqual(revived.returncode, 0, revived.stdout + revived.stderr)
        self.assertEqual(self.info(token)["commit"], commit_b)

    def test_stale_daemon_that_survives_the_restart_fails_the_health_check(self):
        """If the port is still served by old code after the restart, install.sh must not report success."""
        first = self.install()
        self.assertEqual(first.returncode, 0, first.stdout + first.stderr)
        # Simulate "the old daemon kept the port": forget its pid so install.sh cannot stop it.
        stale_pid = self.pid()
        (self.home / ".config" / "herdr-mobile" / "daemon.pid").unlink()
        try:
            self.commit("daemon change that never gets to run")
            result = self.install()
            self.assertEqual(result.returncode, 4, result.stdout + result.stderr)
            self.assertIn("expected", result.stderr)
        finally:
            os.kill(stale_pid, signal.SIGTERM)

    def test_bad_arguments_and_missing_prerequisites_have_distinct_exit_codes(self):
        script = self.origin / "daemon" / "install.sh"
        bad_port = subprocess.run(
            ["bash", str(script), "--port", "99999"], env=self.env, capture_output=True, text=True, timeout=30
        )
        self.assertEqual(bad_port.returncode, 2)
        self.assertIn("invalid port", bad_port.stderr)
        unknown = subprocess.run(
            ["bash", str(script), "--frobnicate"], env=self.env, capture_output=True, text=True, timeout=30
        )
        self.assertEqual(unknown.returncode, 2)

        # No Tailscale address and no AGENTSTERM_HOST -> prerequisite error, nothing started.
        stub = self.tmp / "stub-bin"
        stub.mkdir()
        (stub / "tailscale").write_text("#!/bin/sh\nexit 0\n")
        (stub / "tailscale").chmod(0o755)
        env = {k: v for k, v in self.env.items() if k != "AGENTSTERM_HOST"}
        env["PATH"] = f"{stub}:{env['PATH']}"
        no_ip = subprocess.run(
            ["bash", str(script), "--port", str(self.port)], env=env, capture_output=True, text=True, timeout=30
        )
        self.assertEqual(no_ip.returncode, 3, no_ip.stdout + no_ip.stderr)
        self.assertIn("Tailscale has no IPv4", no_ip.stderr)
        self.assertFalse((self.home / ".config" / "herdr-mobile" / "daemon.pid").exists())

        # The requested ref has no daemon in it.
        self.git("checkout", "-q", "--orphan", "empty")
        self.git("rm", "-rq", "--cached", ".")
        shutil.rmtree(self.origin / "daemon")
        self.commit("branch without a daemon")
        no_daemon = subprocess.run(
            ["bash", str(DAEMON_DIR / "install.sh"), "--port", str(self.port), "--ref", "empty"],
            env=self.env,
            capture_output=True,
            text=True,
            timeout=60,
        )
        self.assertEqual(no_daemon.returncode, 3, no_daemon.stdout + no_daemon.stderr)
        self.assertIn("not found", no_daemon.stderr)


if __name__ == "__main__":
    unittest.main()
