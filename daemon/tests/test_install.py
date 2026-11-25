"""End-to-end tests of daemon/install.sh in a sandbox HOME.

The daemon is installed from a throwaway git repository holding a copy of this checkout's daemon/,
so the tests can publish new commits and check that every run ends with the latest code running,
and stubbed loginctl/systemctl/crontab binaries exercise the systemd --user, linger, and @reboot
crontab fallback paths without touching the host machine.
"""

import json
import os
import shlex
import shutil
import signal
import subprocess
import tempfile
import textwrap
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
            "AGENTSTERM_NO_CRON": "1",  # never touch the real crontab on this machine
        }
        self.env.pop("AGENTSTERM_INSTALL_DIR", None)
        self.env.pop("AGENTSTERM_DAEMON_COMMIT", None)

    def tearDown(self):
        pids = []
        for pid_file in (
            self.home / ".config" / "herdr-mobile" / "daemon.pid",
            self.tmp / "systemd-daemon.pid",
        ):
            if pid_file.exists():
                try:
                    pids.append(int(pid_file.read_text().strip()))
                except ValueError:
                    pass
        for pid in set(pids):
            try:
                os.kill(pid, signal.SIGTERM)
            except ProcessLookupError:
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

    def install(self, *args, script=None, env=None):
        # First run: the script comes from origin (as the bootstrap does after cloning).
        script = script or (
            self.install_dir / "daemon" / "install.sh"
            if self.install_dir.exists()
            else self.origin / "daemon" / "install.sh"
        )
        return subprocess.run(
            ["bash", str(script), "--port", str(self.port), *args],
            env=env if env is not None else self.env,
            capture_output=True,
            text=True,
            timeout=90,
        )

    def write_stub(self, stub_dir: Path, name: str, body: str) -> Path:
        path = stub_dir / name
        path.write_text("#!/usr/bin/env bash\nset -euo pipefail\n" + textwrap.dedent(body))
        path.chmod(0o755)
        return path

    def make_fake_crontab(self, stub_dir: Path, cron_file: Path, *, allow: bool = True) -> Path:
        if not allow:
            return self.write_stub(stub_dir, "crontab", 'echo "crontab: permission denied" >&2; exit 1\n')
        q_file = shlex.quote(str(cron_file))
        return self.write_stub(
            stub_dir,
            "crontab",
            f"""\
            CRON_FILE={q_file}
            case "${{1:-}}" in
              -l)
                if [ -f "$CRON_FILE" ]; then
                  cat "$CRON_FILE"
                else
                  echo "no crontab for $(id -un)" >&2
                  exit 1
                fi
                ;;
              -r)
                rm -f "$CRON_FILE"
                ;;
              -)
                cat > "$CRON_FILE"
                ;;
              *)
                echo "unsupported crontab args: $*" >&2
                exit 2
                ;;
            esac
            """,
        )

    def sandbox_env_with_stubs(self, stub_dir: Path, *, no_systemd: bool = False, no_cron: bool = False):
        env = dict(self.env)
        env["PATH"] = f"{stub_dir}:{env['PATH']}"
        env["AGENTSTERM_SYSTEMD_WAIT_DELAY"] = "0.05"
        env["AGENTSTERM_SYSTEMD_WAIT_ATTEMPTS"] = "10"
        env.pop("XDG_RUNTIME_DIR", None)
        env.pop("DBUS_SESSION_BUS_ADDRESS", None)
        if not no_systemd:
            env.pop("AGENTSTERM_NO_SYSTEMD", None)
        if not no_cron:
            env.pop("AGENTSTERM_NO_CRON", None)
        return env

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

    def test_systemd_enables_linger_before_probing_and_waits_for_user_manager(self):
        """Fresh VM over SSH: enable-linger brings up user@<uid>.service, systemd path is taken, stale cron removed."""
        stub_dir = self.tmp / "stubs-systemd"
        stub_dir.mkdir()
        linger_flag = self.tmp / "linger-enabled"
        probe_count = self.tmp / "probe-count"
        call_log = self.tmp / "calls.log"
        systemd_pid = self.tmp / "systemd-daemon.pid"
        cron_file = self.tmp / "crontab.txt"
        cron_file.write_text("0 3 * * * /bin/true\n@reboot /old/start-daemon.sh # agentsterm-daemon\n")
        self.make_fake_crontab(stub_dir, cron_file)

        self.write_stub(
            stub_dir,
            "loginctl",
            f"""\
            LOG={shlex.quote(str(call_log))}
            FLAG={shlex.quote(str(linger_flag))}
            echo "loginctl $*" >> "$LOG"
            case "${{1:-}}" in
              show-user)
                if [ -f "$FLAG" ]; then
                  echo "Linger=yes"
                  exit 0
                fi
                echo "Failed to get user: not logged in or lingering" >&2
                exit 1
                ;;
              enable-linger)
                touch "$FLAG"
                exit 0
                ;;
            esac
            exit 1
            """,
        )

        self.write_stub(
            stub_dir,
            "systemctl",
            f"""\
            LOG={shlex.quote(str(call_log))}
            FLAG={shlex.quote(str(linger_flag))}
            COUNT_FILE={shlex.quote(str(probe_count))}
            PID_FILE={shlex.quote(str(systemd_pid))}
            echo "systemctl XDG=${{XDG_RUNTIME_DIR:-}} $*" >> "$LOG"
            [ "${{1:-}}" = "--user" ] || exit 1
            shift
            case "${{1:-}}" in
              show-environment)
                # Require XDG_RUNTIME_DIR=/run/user/<uid> and linger enabled, and fail the first
                # probe to verify install.sh waits for user@<uid>.service to come up.
                [ "${{XDG_RUNTIME_DIR:-}}" = "/run/user/$(id -u)" ] || exit 1
                [ -f "$FLAG" ] || exit 1
                n=0
                [ -f "$COUNT_FILE" ] && n=$(cat "$COUNT_FILE")
                n=$((n + 1))
                echo "$n" > "$COUNT_FILE"
                [ "$n" -ge 2 ] || exit 1
                exit 0
                ;;
              daemon-reload|enable)
                exit 0
                ;;
              restart)
                if [ -f "$PID_FILE" ]; then
                  kill "$(cat "$PID_FILE")" 2>/dev/null || true
                  rm -f "$PID_FILE"
                fi
                UNIT="$HOME/.config/systemd/user/${{2}}"
                EXEC_LINE="$(sed -n 's/^ExecStart=//p' "$UNIT")"
                # shellcheck disable=SC2086
                nohup $EXEC_LINE >> "$HOME/.config/herdr-mobile/daemon.log" 2>&1 < /dev/null &
                echo $! > "$PID_FILE"
                exit 0
                ;;
              is-active)
                [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null
                exit $?
                ;;
              status)
                exit 0
                ;;
            esac
            exit 1
            """,
        )

        env = self.sandbox_env_with_stubs(stub_dir)
        commit_a = self.git("rev-parse", "HEAD")
        res = self.install(env=env)
        self.assertEqual(res.returncode, 0, res.stdout + res.stderr)
        self.assertIn("Running as systemd --user (herdr-mobile.service)", res.stdout)
        self.assertIn("will survive reboots", res.stdout)

        uri = res.stdout.strip().splitlines()[-1]
        token = uri.rsplit("token=", 1)[1]
        self.assertEqual(self.info(token)["commit"], commit_a)
        self.assertTrue((self.home / ".config" / "systemd" / "user" / "herdr-mobile.service").is_file())
        self.assertGreaterEqual(int(probe_count.read_text().strip()), 2)

        # enable-linger must be called before the successful show-environment probe.
        calls = call_log.read_text().splitlines()
        linger_idx = next(i for i, line in enumerate(calls) if line.startswith("loginctl enable-linger"))
        show_env_indices = [i for i, line in enumerate(calls) if "show-environment" in line]
        self.assertTrue(show_env_indices)
        self.assertLess(linger_idx, show_env_indices[0])

        # Stale @reboot entry removed; unrelated user cron entry preserved.
        remaining_cron = cron_file.read_text()
        self.assertIn("0 3 * * * /bin/true", remaining_cron)
        self.assertNotIn("# agentsterm-daemon", remaining_cron)

        # Re-running with no upstream change keeps the service running and still reports reboot survival.
        pid_before = int(systemd_pid.read_text().strip())
        again = self.install(env=env)
        self.assertEqual(again.returncode, 0, again.stdout + again.stderr)
        self.assertIn("already running the latest commit", again.stdout)
        self.assertIn("will survive reboots", again.stdout)
        self.assertEqual(int(systemd_pid.read_text().strip()), pid_before)

    def test_systemd_unavailable_falls_back_to_detached_with_reboot_crontab(self):
        """When systemd --user and enable-linger fail, install.sh installs an @reboot crontab that revives the daemon."""
        stub_dir = self.tmp / "stubs-cron-fallback"
        stub_dir.mkdir()
        cron_file = self.tmp / "crontab.txt"
        cron_file.write_text("0 4 * * * /bin/echo keep-me\n")
        self.make_fake_crontab(stub_dir, cron_file)
        self.write_stub(stub_dir, "loginctl", 'echo "Interactive authentication required." >&2; exit 1\n')
        self.write_stub(stub_dir, "systemctl", 'echo "Failed to connect to bus" >&2; exit 1\n')

        env = self.sandbox_env_with_stubs(stub_dir)
        commit_a = self.git("rev-parse", "HEAD")

        first = self.install(env=env)
        self.assertEqual(first.returncode, 0, first.stdout + first.stderr)
        self.assertIn("detached process + @reboot crontab", first.stdout)
        self.assertIn("will survive reboots", first.stdout)
        uri = first.stdout.strip().splitlines()[-1]
        token = uri.rsplit("token=", 1)[1]
        self.assertEqual(self.info(token)["commit"], commit_a)

        cron_lines = cron_file.read_text().splitlines()
        self.assertIn("0 4 * * * /bin/echo keep-me", cron_lines)
        reboot_lines = [line for line in cron_lines if "# agentsterm-daemon" in line]
        self.assertEqual(len(reboot_lines), 1, cron_lines)
        self.assertTrue(reboot_lines[0].startswith("@reboot "))

        # Re-running with --force-restart must not duplicate the @reboot entry.
        forced = self.install("--force-restart", env=env)
        self.assertEqual(forced.returncode, 0, forced.stdout + forced.stderr)
        cron_lines_after = cron_file.read_text().splitlines()
        self.assertIn("0 4 * * * /bin/echo keep-me", cron_lines_after)
        self.assertEqual(len([line for line in cron_lines_after if "# agentsterm-daemon" in line]), 1)

        # Simulate a VM reboot: kill the running daemon, then execute the @reboot crontab command.
        old_pid = self.pid()
        os.kill(old_pid, signal.SIGTERM)
        self.assert_gone(old_pid)

        reboot_cmd = reboot_lines[0][len("@reboot ") :]
        reboot_run = subprocess.run(["/bin/sh", "-c", reboot_cmd], env=env, capture_output=True, text=True, timeout=30)
        self.assertEqual(reboot_run.returncode, 0, reboot_run.stdout + reboot_run.stderr)

        # Wait for the reboot-started daemon to become healthy on the same port and token.
        deadline = time.time() + 15
        info = None
        while time.time() < deadline:
            try:
                info = self.info(token)
                break
            except OSError:
                time.sleep(0.2)
        self.assertIsNotNone(info, "daemon did not come back after running @reboot crontab entry")
        self.assertEqual(info["commit"], commit_a)
        revived_pid = self.pid()
        self.assertNotEqual(revived_pid, old_pid)

        # Running the @reboot script while the daemon is already up must be a no-op and keep daemon.pid.
        dup_run = subprocess.run(["/bin/sh", "-c", reboot_cmd], env=env, capture_output=True, text=True, timeout=30)
        self.assertEqual(dup_run.returncode, 0, dup_run.stdout + dup_run.stderr)
        self.assertEqual(self.pid(), revived_pid)

    def test_reports_will_not_survive_reboot_when_both_systemd_and_crontab_unavailable(self):
        stub_dir = self.tmp / "stubs-no-persist"
        stub_dir.mkdir()
        self.make_fake_crontab(stub_dir, self.tmp / "crontab.txt", allow=False)
        self.write_stub(stub_dir, "loginctl", "exit 1\n")
        self.write_stub(stub_dir, "systemctl", "exit 1\n")

        env = self.sandbox_env_with_stubs(stub_dir)
        res = self.install(env=env)
        self.assertEqual(res.returncode, 0, res.stdout + res.stderr)
        self.assertIn("will NOT survive a reboot", res.stdout)
        self.assertIn("sudo loginctl enable-linger", res.stdout)

    def test_systemd_without_linger_reports_will_not_survive_reboot(self):
        stub_dir = self.tmp / "stubs-no-linger"
        stub_dir.mkdir()
        systemd_pid = self.tmp / "systemd-daemon.pid"
        self.make_fake_crontab(stub_dir, self.tmp / "crontab.txt")
        self.write_stub(stub_dir, "loginctl", 'echo "Linger=no"; exit 1\n')
        self.write_stub(
            stub_dir,
            "systemctl",
            f"""\
            PID_FILE={shlex.quote(str(systemd_pid))}
            [ "${{1:-}}" = "--user" ] || exit 1
            shift
            case "${{1:-}}" in
              show-environment|daemon-reload|enable|status)
                exit 0
                ;;
              restart)
                UNIT="$HOME/.config/systemd/user/${{2}}"
                EXEC_LINE="$(sed -n 's/^ExecStart=//p' "$UNIT")"
                # shellcheck disable=SC2086
                nohup $EXEC_LINE >> "$HOME/.config/herdr-mobile/daemon.log" 2>&1 < /dev/null &
                echo $! > "$PID_FILE"
                exit 0
                ;;
            esac
            exit 1
            """,
        )

        env = self.sandbox_env_with_stubs(stub_dir)
        res = self.install(env=env)
        self.assertEqual(res.returncode, 0, res.stdout + res.stderr)
        self.assertIn("Running as systemd --user (herdr-mobile.service)", res.stdout)
        self.assertIn("will NOT survive a reboot", res.stdout)
        self.assertIn("sudo loginctl enable-linger", res.stdout)


if __name__ == "__main__":
    unittest.main()
