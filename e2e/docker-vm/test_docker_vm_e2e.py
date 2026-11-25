#!/usr/bin/env python3
"""Cross-boundary Docker VM end-to-end test suite.

Starts a real Linux Docker container running OpenSSH, cron, a fake Tailscale shim,
deterministic fake agent CLIs (agy/claude/codex), and a local git mirror of the
current worktree, then exercises the full SSH bootstrap -> HTTP -> WebSocket ->
agent runner -> live app debugging -> restart recovery contract.

Standard library only; requires Docker and an `ssh` / `ssh-keygen` client on PATH.
"""

from __future__ import annotations

import base64
import hashlib
import json
import os
import re
import shutil
import socket
import struct
import subprocess
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

SCRIPT_DIR = Path(__file__).resolve().parent
START_VM_SCRIPT = SCRIPT_DIR / "start_vm.sh"
PAIRING_URI_RE = re.compile(r"(?:agentsterm|herdr)://connect\?[^\s\"'`<>)\]]+", re.IGNORECASE)


def free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class MinimalWsClient:
    """Minimal RFC 6455 text WebSocket client using only Python stdlib."""

    def __init__(self, host: str, port: int, path: str, token: str) -> None:
        self.sock = socket.create_connection((host, port), timeout=15)
        self.sock.settimeout(15)
        key = base64.b64encode(os.urandom(16)).decode("ascii")
        req = (
            f"GET {path} HTTP/1.1\r\n"
            f"Host: {host}:{port}\r\n"
            f"Upgrade: websocket\r\n"
            f"Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            f"Sec-WebSocket-Version: 13\r\n"
            f"Authorization: Bearer {token}\r\n\r\n"
        )
        self.sock.sendall(req.encode("ascii"))
        buf = b""
        while b"\r\n\r\n" not in buf:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise ConnectionError("WebSocket handshake closed early")
            buf += chunk
        header_raw, self._buf = buf.split(b"\r\n\r\n", 1)
        status_line = header_raw.split(b"\r\n", 1)[0].decode("latin1")
        if "101" not in status_line:
            raise ConnectionError(f"WebSocket handshake failed: {status_line}")
        expected_accept = base64.b64encode(
            hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode("ascii")).digest()
        ).decode("ascii")
        if expected_accept.encode("ascii") not in header_raw:
            raise ConnectionError("Invalid Sec-WebSocket-Accept")

    def _read_exact(self, n: int) -> bytes:
        while len(self._buf) < n:
            chunk = self.sock.recv(max(4096, n - len(self._buf)))
            if not chunk:
                raise ConnectionError("WebSocket closed")
            self._buf += chunk
        out = self._buf[:n]
        self._buf = self._buf[n:]
        return out

    def send_text(self, text: str) -> None:
        payload = text.encode("utf-8")
        mask = os.urandom(4)
        header = bytearray([0x81])
        length = len(payload)
        if length < 126:
            header.append(0x80 | length)
        elif length < 65536:
            header.append(0x80 | 126)
            header.extend(struct.pack("!H", length))
        else:
            header.append(0x80 | 127)
            header.extend(struct.pack("!Q", length))
        header.extend(mask)
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        self.sock.sendall(bytes(header) + masked)

    def send_json(self, obj: Dict[str, Any]) -> None:
        self.send_text(json.dumps(obj))

    def recv_json(self, timeout: float = 15.0) -> Dict[str, Any]:
        self.sock.settimeout(timeout)
        while True:
            b0, b1 = self._read_exact(2)
            opcode = b0 & 0x0F
            masked = bool(b1 & 0x80)
            length = b1 & 0x7F
            if length == 126:
                length = struct.unpack("!H", self._read_exact(2))[0]
            elif length == 127:
                length = struct.unpack("!Q", self._read_exact(8))[0]
            mask = self._read_exact(4) if masked else None
            payload = self._read_exact(length)
            if mask:
                payload = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
            if opcode == 0x9:  # ping -> pong
                self._send_pong(payload)
                continue
            if opcode == 0xA:  # pong
                continue
            if opcode == 0x8:  # close
                raise ConnectionError("WebSocket closed by server")
            if opcode == 0x1:  # text
                return json.loads(payload.decode("utf-8"))

    def _send_pong(self, payload: bytes) -> None:
        mask = os.urandom(4)
        header = bytearray([0x8A, 0x80 | len(payload)]) + bytearray(mask)
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        self.sock.sendall(bytes(header) + masked)

    def close(self) -> None:
        try:
            self.sock.close()
        except OSError:
            pass


def build_bootstrap_command(daemon_port: int) -> str:
    """Matches the production base64-encoded SSH bootstrap command from SshBootstrapModels.kt."""
    script = f"""set -e
[ -f "$HOME/.profile" ] && . "$HOME/.profile" >/dev/null 2>&1 || true
export PATH="$HOME/.local/bin:$HOME/.npm-global/bin:$HOME/.cargo/bin:/usr/local/bin:$PATH"
export FORCE_COLOR=0
AGENTSTERM_DIR="$HOME/.local/share/agentsterm/agents-term"
AGENTSTERM_INSTALL="$AGENTSTERM_DIR/daemon/install.sh"
if command -v git >/dev/null 2>&1; then
  if [ ! -d "$AGENTSTERM_DIR/.git" ]; then
    git clone -q --depth 1 --branch main https://github.com/nedn/agents-term.git "$AGENTSTERM_DIR"
  fi
fi
bash "$AGENTSTERM_INSTALL" --port {daemon_port} --ref main
"""
    b64 = base64.b64encode(script.encode("utf-8")).decode("ascii")
    return f"printf '%s' '{b64}' | base64 -d | bash -s"


class DockerVmE2ETest(unittest.TestCase):
    container_name: str = ""
    ssh_port: int = 0
    daemon_port: int = 0
    snapshot_dir: Optional[Path] = None
    snapshot_commit: str = ""
    key_dir: Optional[Path] = None
    key_path: Optional[Path] = None
    token: str = ""
    base_url: str = ""

    @classmethod
    def setUpClass(cls) -> None:
        cls.key_dir = Path(tempfile.mkdtemp(prefix="agentsterm-e2e-keys-"))
        cls.key_path = cls.key_dir / "id_ed25519"
        subprocess.run(
            ["ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", str(cls.key_path)],
            check=True,
        )
        cls.ssh_port = free_port()
        cls.daemon_port = free_port()
        cls.container_name = f"agentsterm-headless-e2e-{os.getpid()}"

        proc = subprocess.run(
            [
                "bash",
                str(START_VM_SCRIPT),
                "--name",
                cls.container_name,
                "--ssh-port",
                str(cls.ssh_port),
                "--daemon-port",
                str(cls.daemon_port),
                "--pubkey",
                str(cls.key_path) + ".pub",
            ],
            capture_output=True,
            text=True,
            check=True,
        )
        meta: Dict[str, str] = {}
        for line in proc.stdout.splitlines():
            if "=" in line:
                k, v = line.split("=", 1)
                meta[k.strip()] = v.strip()
        cls.snapshot_dir = Path(meta["SNAPSHOT_DIR"])
        cls.snapshot_commit = meta["SNAPSHOT_COMMIT"]
        cls.base_url = f"http://127.0.0.1:{cls.daemon_port}"

    @classmethod
    def tearDownClass(cls) -> None:
        if cls.container_name:
            subprocess.run(["docker", "rm", "-f", cls.container_name], capture_output=True)
        if cls.snapshot_dir and cls.snapshot_dir.exists():
            shutil.rmtree(cls.snapshot_dir, ignore_errors=True)
        if cls.key_dir and cls.key_dir.exists():
            shutil.rmtree(cls.key_dir, ignore_errors=True)

    def ssh_exec(self, command: str, timeout: int = 90) -> subprocess.CompletedProcess:
        return subprocess.run(
            [
                "ssh",
                "-i",
                str(self.key_path),
                "-o",
                "StrictHostKeyChecking=no",
                "-o",
                "UserKnownHostsFile=/dev/null",
                "-o",
                "BatchMode=yes",
                "-o",
                "ConnectTimeout=10",
                "-p",
                str(self.ssh_port),
                "vmuser@127.0.0.1",
                command,
            ],
            capture_output=True,
            text=True,
            timeout=timeout,
        )

    def docker_exec(self, command: str, user: str = "vmuser") -> subprocess.CompletedProcess:
        return subprocess.run(
            ["docker", "exec", "-u", user, self.container_name, "bash", "-lc", command],
            capture_output=True,
            text=True,
            timeout=30,
        )

    def api(self, method: str, path: str, body: Any = None, token: Optional[str] = "default") -> Tuple[int, Any]:
        data = json.dumps(body).encode("utf-8") if body is not None else None
        req = urllib.request.Request(self.base_url + path, data=data, method=method)
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
                raw = err.read()
                try:
                    payload = json.loads(raw or b"null")
                except ValueError:
                    payload = raw.decode("utf-8", "replace")
                return err.code, payload

    def wait_for_tab_idle(
        self,
        tab_id: str,
        timeout: float = 20.0,
        allowed_statuses: Tuple[str, ...] = ("idle", "done"),
    ) -> Tuple[Dict[str, Any], List[Dict[str, Any]]]:
        deadline = time.time() + timeout
        while time.time() < deadline:
            status, tab_data = self.api("GET", "/api/tabs")
            self.assertEqual(status, 200)
            tabs = tab_data["tabs"] if isinstance(tab_data, dict) else tab_data
            tab = next((t for t in tabs if t["id"] == tab_id), None)
            self.assertIsNotNone(tab)
            if tab["status"] in allowed_statuses and not tab.get("queuedMessages"):
                m_status, msg_data = self.api("GET", f"/api/tabs/{tab_id}/messages")
                self.assertEqual(m_status, 200)
                messages = msg_data["messages"] if isinstance(msg_data, dict) else msg_data
                if messages and messages[-1]["status"] != "streaming":
                    return tab, messages
            time.sleep(0.15)
        raise AssertionError(f"Tab {tab_id} did not reach {allowed_statuses} within {timeout}s")

    def ensure_bootstrapped(self) -> None:
        if self.token:
            return
        res = self.ssh_exec(build_bootstrap_command(self.daemon_port))
        self.assertEqual(res.returncode, 0, f"SSH bootstrap failed:\nSTDOUT:\n{res.stdout}\nSTDERR:\n{res.stderr}")
        match = PAIRING_URI_RE.search(res.stdout + "\n" + res.stderr)
        self.assertIsNotNone(match, f"No pairing URI in bootstrap output:\n{res.stdout}")
        parsed = urllib.parse.urlparse(match.group(0))
        params = urllib.parse.parse_qs(parsed.query)
        self.assertEqual(params.get("host", [""])[0], "127.0.0.1")
        self.assertEqual(params.get("port", [""])[0], str(self.daemon_port))
        token = params.get("token", [""])[0]
        self.assertTrue(len(token) >= 16)
        type(self).token = token

    def test_01_fresh_ssh_bootstrap_and_idempotency(self) -> None:
        self.ensure_bootstrapped()

        # Unauthenticated requests must be rejected.
        unauth_status, _ = self.api("GET", "/api/info", token=None)
        self.assertEqual(unauth_status, 401)

        # Authenticated /api/info reports container hostname and expected snapshot commit.
        status, info = self.api("GET", "/api/info")
        self.assertEqual(status, 200)
        self.assertEqual(info["hostname"], "docker-e2e-vm")
        self.assertEqual(info["commit"], self.snapshot_commit)

        pid_before = self.docker_exec("cat ~/.config/herdr-mobile/daemon.pid").stdout.strip()
        self.assertTrue(pid_before.isdigit(), f"Unexpected daemon.pid: {pid_before!r}")

        # Re-running SSH bootstrap with no new commits must NOT restart the daemon.
        res2 = self.ssh_exec(build_bootstrap_command(self.daemon_port))
        self.assertEqual(res2.returncode, 0, res2.stdout + res2.stderr)
        pid_after = self.docker_exec("cat ~/.config/herdr-mobile/daemon.pid").stdout.strip()
        self.assertEqual(pid_before, pid_after, "Idempotent bootstrap should not restart daemon")

        # Verify @reboot crontab entry was installed.
        cron_out = self.docker_exec("crontab -l").stdout
        self.assertIn("@reboot", cron_out)
        self.assertIn("start-daemon.sh", cron_out)

    def test_02_shell_tab_ws_streaming_cwd_queue_and_interrupt(self) -> None:
        self.ensure_bootstrapped()
        ws = MinimalWsClient("127.0.0.1", self.daemon_port, "/ws", self.token)
        try:
            snap = ws.recv_json()
            self.assertEqual(snap["type"], "snapshot")

            status, created = self.api(
                "POST",
                "/api/tabs",
                {"agent": "shell", "title": "Docker Shell", "cwd": "/home/vmuser"},
            )
            self.assertEqual(status, 200)
            tab_id = created["tab"]["id"]

            # Drain WS events until we see tab_created.
            seen_types = []
            for _ in range(10):
                ev = ws.recv_json(timeout=5)
                seen_types.append(ev.get("type"))
                if ev.get("type") == "tab_created" and ev["tab"]["id"] == tab_id:
                    break
            self.assertIn("tab_created", seen_types)

            # Run a real shell command that changes cwd and prints output.
            st, _ = self.api("POST", f"/api/tabs/{tab_id}/messages", {"text": "cd /tmp && echo DOCKER_SHELL_OK && pwd"})
            self.assertEqual(st, 200)
            tab, msgs = self.wait_for_tab_idle(tab_id)
            self.assertEqual(tab["cwd"], "/tmp")
            self.assertIn("DOCKER_SHELL_OK", msgs[-1]["text"])

            # Start a slow shell command, queue a follow-up, then remove the queued item and interrupt.
            st, _ = self.api("POST", f"/api/tabs/{tab_id}/messages", {"text": "echo START_WAIT && sleep 10"})
            self.assertEqual(st, 200)
            time.sleep(0.4)
            st, queued = self.api("POST", f"/api/tabs/{tab_id}/messages", {"text": "echo SHOULD_NOT_RUN"})
            self.assertEqual(st, 200)
            self.assertTrue(queued.get("queued"), f"Expected queued response: {queued}")
            q_id = queued["queuedMessage"]["id"]
            d_st, _ = self.api("DELETE", f"/api/tabs/{tab_id}/queue/{q_id}")
            self.assertEqual(d_st, 200)

            i_st, _ = self.api("POST", f"/api/tabs/{tab_id}/interrupt")
            self.assertEqual(i_st, 200)
            _, msgs_after = self.wait_for_tab_idle(tab_id)
            self.assertEqual(msgs_after[-1]["status"], "interrupted")

            # Clean up tab via PATCH & DELETE.
            p_st, patched = self.api("PATCH", f"/api/tabs/{tab_id}", {"title": "Renamed Shell"})
            self.assertEqual(p_st, 200)
            self.assertEqual(patched["tab"]["title"], "Renamed Shell")
            r_st, _ = self.api("POST", f"/api/tabs/{tab_id}/read")
            self.assertEqual(r_st, 200)
            del_st, _ = self.api("DELETE", f"/api/tabs/{tab_id}")
            self.assertEqual(del_st, 200)
        finally:
            ws.close()

    def test_03_coding_agents_streaming_resume_models_and_permissions(self) -> None:
        self.ensure_bootstrapped()

        # Verify model discovery endpoints for all three agents.
        for agent, expected_id in [("agy", "gemini-4-pro"), ("claude", "sonnet"), ("codex", "gpt-5.3-codex")]:
            st, data = self.api("GET", f"/api/agents/{agent}/models?refresh=1")
            self.assertEqual(st, 200)
            ids = [m["id"] for m in data["models"]]
            self.assertIn(expected_id, ids, f"{agent} models missing {expected_id}: {data}")

        # Exercise agy, claude, and codex end-to-end (turn 1 summary + tool calls + turn 2 resume + /model).
        for agent, expected_summary in [
            ("agy", "Docker E2E Agy Task"),
            ("claude", "Docker E2E Claude Task"),
            ("codex", "Docker E2E Codex Task"),
        ]:
            st, created = self.api(
                "POST",
                "/api/tabs",
                {"agent": agent, "title": f"E2E {agent}", "cwd": "/home/vmuser", "permissionMode": "default"},
            )
            self.assertEqual(st, 200)
            tab_id = created["tab"]["id"]

            # Turn 1: trigger <tab_task_summary> extraction and tool call tracking.
            st, _ = self.api("POST", f"/api/tabs/{tab_id}/messages", {"text": "Check container status"})
            self.assertEqual(st, 200)
            tab1, msgs1 = self.wait_for_tab_idle(tab_id)
            self.assertEqual(tab1.get("taskSummary"), expected_summary)
            self.assertNotIn("<tab_task_summary>", msgs1[-1]["text"])
            self.assertTrue(msgs1[-1].get("toolCalls"), f"{agent} should record toolCalls")
            self.assertIsNotNone(tab1.get("sessionRef"), f"{agent} should persist sessionRef")
            self.assertIn("resumed=no", msgs1[-1]["text"])

            # Switch model with /model command.
            target_model = {"agy": "gemini-4-flash", "claude": "opus", "codex": "o4-mini"}[agent]
            st, _ = self.api("POST", f"/api/tabs/{tab_id}/messages", {"text": f"/model {target_model}"})
            self.assertEqual(st, 200)
            tab_m, _ = self.wait_for_tab_idle(tab_id)
            self.assertEqual(tab_m.get("model"), target_model)

            # Turn 2: verify sessionRef resume and switched model.
            st, _ = self.api("POST", f"/api/tabs/{tab_id}/messages", {"text": "Follow up turn"})
            self.assertEqual(st, 200)
            _, msgs2 = self.wait_for_tab_idle(tab_id)
            self.assertIn("resumed=yes", msgs2[-1]["text"])
            self.assertIn(f"model={target_model}", msgs2[-1]["text"])

        # Verify permission denial + full-access retry on claude.
        st, created = self.api(
            "POST",
            "/api/tabs",
            {"agent": "claude", "title": "Perm Test", "cwd": "/home/vmuser", "permissionMode": "default"},
        )
        self.assertEqual(st, 200)
        perm_tab = created["tab"]["id"]
        self.api("POST", f"/api/tabs/{perm_tab}/messages", {"text": "DENY_TOOL please"})
        perm_tab_state, denied_msgs = self.wait_for_tab_idle(perm_tab, allowed_statuses=("blocked",))
        self.assertEqual(perm_tab_state["status"], "blocked")
        self.assertTrue(denied_msgs[-1].get("deniedActions"), "Expected deniedActions on default permissionMode")

        self.api("PATCH", f"/api/tabs/{perm_tab}", {"permissionMode": "full-access"})
        self.api("POST", f"/api/tabs/{perm_tab}/messages", {"text": "DENY_TOOL retry"})
        _, retry_msgs = self.wait_for_tab_idle(perm_tab)
        self.assertFalse(retry_msgs[-1].get("deniedActions"))
        self.assertIn("perms=1", retry_msgs[-1]["text"])

    def test_04_fs_debug_dumps_and_app_state_cli_roundtrip(self) -> None:
        self.ensure_bootstrapped()

        # /api/fs lists home directory.
        st, fs_data = self.api("GET", "/api/fs?path=/home/vmuser")
        self.assertEqual(st, 200)
        self.assertEqual(fs_data["path"], "/home/vmuser")
        self.assertTrue(fs_data["exists"])
        dir_names = [e["name"] for e in fs_data["directories"]]
        self.assertIn("projects", dir_names)

        # /api/debug/dumps upload & listing.
        st, uploaded = self.api(
            "POST",
            "/api/debug/dumps",
            {
                "dumpId": "docker-e2e-dump",
                "note": "Docker E2E dump",
                "appVersion": "0.3.e2e",
                "deviceInfo": "DockerE2EPhone",
                "eventsNdjson": '{"event":"test"}\n',
            },
        )
        self.assertEqual(st, 200)
        self.assertTrue(uploaded.get("ok"))
        self.assertEqual(uploaded["dumpId"], "docker-e2e-dump")
        st, listed = self.api("GET", "/api/debug/dumps")
        self.assertEqual(st, 200)
        self.assertTrue(any("docker-e2e-dump" in d["dirName"] for d in listed["dumps"]))

        # Simulate connected Android phone over WebSocket answering AppStateBroker requests,
        # then invoke app_state_cli.py from INSIDE the Docker container.
        ws = MinimalWsClient("127.0.0.1", self.daemon_port, "/ws", self.token)
        stop_flag = threading.Event()

        def responder() -> None:
            while not stop_flag.is_set():
                try:
                    frame = ws.recv_json(timeout=0.5)
                except socket.timeout:
                    continue
                except OSError:
                    break
                if frame.get("type") == "app_state_request":
                    ws.send_json(
                        {
                            "type": "app_state_response",
                            "requestId": frame["requestId"],
                            "ok": True,
                            "result": {"ui": {"screen": "chat"}, "echoCommand": frame["command"]},
                        }
                    )

        try:
            ws.recv_json()  # snapshot
            ws.send_json(
                {
                    "type": "app_hello",
                    "reporter": {
                        "enabled": True,
                        "appVersion": "0.3.e2e",
                        "device": "SimulatedPhone",
                        "commands": ["ping", "state"],
                        "controlEnabled": False,
                        "controlCommands": [],
                    },
                }
            )
            t = threading.Thread(target=responder, daemon=True)
            t.start()
            time.sleep(0.2)

            cli_cmd = (
                f"python3 ~/.local/share/agentsterm/agents-term/app_state_cli/app_state_cli.py "
                f"--port {self.daemon_port} status"
            )
            status_res = self.docker_exec(cli_cmd)
            self.assertEqual(status_res.returncode, 0, status_res.stdout + status_res.stderr)
            status_json = json.loads(status_res.stdout)
            self.assertTrue(status_json["ready"])

            state_cmd = (
                f"python3 ~/.local/share/agentsterm/agents-term/app_state_cli/app_state_cli.py "
                f"--port {self.daemon_port} state"
            )
            state_res = self.docker_exec(state_cmd)
            self.assertEqual(state_res.returncode, 0, state_res.stdout + state_res.stderr)
            state_json = json.loads(state_res.stdout)
            self.assertTrue(state_json["ok"])
            self.assertEqual(state_json["result"]["ui"]["screen"], "chat")
        finally:
            stop_flag.set()
            ws.close()

    def test_05_live_upgrade_preserves_token_and_tabs_and_recovers_inflight_turn(self) -> None:
        self.ensure_bootstrapped()

        # Create a tab and start a SLOW_TURN so a streaming message is in flight during upgrade/restart.
        st, created = self.api(
            "POST",
            "/api/tabs",
            {"agent": "agy", "title": "Persist Across Upgrade", "cwd": "/home/vmuser"},
        )
        self.assertEqual(st, 200)
        tab_id = created["tab"]["id"]
        self.api("POST", f"/api/tabs/{tab_id}/messages", {"text": "SLOW_TURN test"})
        time.sleep(0.5)

        pid_before = self.docker_exec("cat ~/.config/herdr-mobile/daemon.pid").stdout.strip()

        # Publish a new commit into /opt/agentsterm-repo inside the container.
        new_commit = self.docker_exec(
            'tmp=$(mktemp -d) && git clone -q /opt/agentsterm-repo "$tmp" && '
            'cd "$tmp" && echo upgrade-marker >> daemon/README.md && '
            "git -c user.name=E2E -c user.email=e2e@example.invalid -c commit.gpgsign=false "
            "commit -q -am 'e2e upgrade commit' && git push -q origin main && "
            'git rev-parse HEAD && rm -rf "$tmp"'
        ).stdout.strip()
        self.assertNotEqual(new_commit, self.snapshot_commit)

        # Re-run SSH bootstrap: daemon must upgrade to new_commit, preserve token & tabs,
        # and mark the interrupted in-flight message as interrupted.
        res = self.ssh_exec(build_bootstrap_command(self.daemon_port))
        self.assertEqual(res.returncode, 0, res.stdout + res.stderr)

        pid_after = self.docker_exec("cat ~/.config/herdr-mobile/daemon.pid").stdout.strip()
        self.assertNotEqual(pid_before, pid_after, "Daemon should restart on new commit")

        # Existing token must still authenticate and report the upgraded commit!
        st, info = self.api("GET", "/api/info")
        self.assertEqual(st, 200)
        self.assertEqual(info["commit"], new_commit)

        # Tab and messages survived, with the in-flight streaming turn recovered as interrupted.
        st, tabs_resp = self.api("GET", "/api/tabs")
        self.assertEqual(st, 200)
        tabs = tabs_resp["tabs"] if isinstance(tabs_resp, dict) else tabs_resp
        self.assertTrue(any(t["id"] == tab_id for t in tabs))

        st, msgs_resp = self.api("GET", f"/api/tabs/{tab_id}/messages")
        self.assertEqual(st, 200)
        msgs = msgs_resp["messages"] if isinstance(msgs_resp, dict) else msgs_resp
        self.assertEqual(msgs[-1]["status"], "interrupted")


if __name__ == "__main__":
    unittest.main()
