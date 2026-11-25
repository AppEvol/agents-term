#!/usr/bin/env python3
"""Exercise the static Rust daemon over SSH, HTTP and WebSocket in a minimal Linux VM.

Covers bootstrap, reboot setup, agent turns, interruption and restart, the filesystem
and debug endpoints, and the native app_state_cli.
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
ISO_TS_RE = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z \[(?:INFO|WARN|ERROR)\]")


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
            if opcode == 0x9:
                self._send_pong(payload)
                continue
            if opcode == 0xA:
                continue
            if opcode == 0x8:
                raise ConnectionError("WebSocket closed by server")
            if opcode == 0x1:
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


def build_rust_bootstrap_command(
    daemon_port: int,
    release_base_url: str = "https://github.com/nedn/agents-term/releases/latest/download",
) -> str:
    """Matches the production POSIX bootstrap command from SshBootstrapModels.kt."""
    script = f"""[ -f "$HOME/.profile" ] && command . "$HOME/.profile" >/dev/null 2>&1 || true
set -eu
export PATH="$HOME/.local/bin:$HOME/.npm-global/bin:$HOME/.cargo/bin:/usr/local/bin:$PATH"
export FORCE_COLOR=0
BASE_URL="${{AGENTSTERM_RELEASE_BASE_URL:-{release_base_url}}}"
BIN_DIR="${{AGENTSTERM_BIN_DIR:-$HOME/.local/bin}}"
BIN_PATH="$BIN_DIR/agentsterm-daemon"
die() {{ code="$1"; shift; printf 'ERROR: %s\\n' "$*" >&2; exit "$code"; }}
OS=$(uname -s)
[ "$OS" = "Linux" ] || die 3 "Unsupported OS: $OS"
RAW_ARCH=$(uname -m)
case "$RAW_ARCH" in
  x86_64|amd64) ARCH="x86_64" ;;
  aarch64|arm64) ARCH="aarch64" ;;
  *) die 3 "Unsupported arch: $RAW_ARCH" ;;
esac
ASSET="agentsterm-daemon-linux-$ARCH"
BIN_URL="${{BASE_URL%/}}/$ASSET"
SHA_URL="$BIN_URL.sha256"
fetch() {{
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL --retry 3 -o "$2" "$1"
  elif command -v wget >/dev/null 2>&1; then
    wget -qO "$2" "$1"
  else
    die 3 "Neither curl nor wget found."
  fi
}}
sha256_of() {{
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{{print $1}}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{{print $1}}'
  else
    die 3 "Neither sha256sum nor shasum found."
  fi
}}
mkdir -p "$BIN_DIR"
TMP_DIR=$(mktemp -d "${{TMPDIR:-/tmp}}/agentsterm-dl.XXXXXX")
trap 'rm -rf "$TMP_DIR"' EXIT INT TERM
fetch "$SHA_URL" "$TMP_DIR/checksum.sha256" || die 3 "Failed to download $SHA_URL"
EXPECTED_SHA=$(awk 'NR==1 {{print $1}}' "$TMP_DIR/checksum.sha256")
[ -n "$EXPECTED_SHA" ] || die 3 "Empty checksum"
NEED_DL=1
if [ -x "$BIN_PATH" ]; then
  CURRENT_SHA=$(sha256_of "$BIN_PATH" || true)
  if [ "$CURRENT_SHA" = "$EXPECTED_SHA" ]; then
    printf '>>> Installed binary already matches release SHA-256.\\n'
    NEED_DL=0
  fi
fi
if [ "$NEED_DL" = 1 ]; then
  fetch "$BIN_URL" "$TMP_DIR/$ASSET" || die 3 "Failed to download $BIN_URL"
  ACTUAL_SHA=$(sha256_of "$TMP_DIR/$ASSET")
  [ "$ACTUAL_SHA" = "$EXPECTED_SHA" ] || die 3 "SHA-256 mismatch"
  chmod 755 "$TMP_DIR/$ASSET"
  mv "$TMP_DIR/$ASSET" "$BIN_DIR/.agentsterm-daemon.tmp.$$"
  mv -f "$BIN_DIR/.agentsterm-daemon.tmp.$$" "$BIN_PATH"
fi
rm -rf "$TMP_DIR"
trap - EXIT INT TERM
exec "$BIN_PATH" install --port {daemon_port}
"""
    b64 = base64.b64encode(script.encode("utf-8")).decode("ascii")
    return f"printf '%s' '{b64}' | base64 -d | sh -s"


class BaseDockerVmTest(unittest.TestCase):
    docker_target: str = "vm-minimal"
    container_name: str = ""
    ssh_port: int = 0
    daemon_port: int = 0
    snapshot_dir: Optional[Path] = None
    binary_hash: str = ""
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
        cls.container_name = f"agentsterm-{cls.docker_target}-{os.getpid()}"

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
                "--target",
                cls.docker_target,
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
        cls.binary_hash = meta["BINARY_HASH"]
        cls.base_url = f"http://127.0.0.1:{cls.daemon_port}"
        cls.token = ""

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
            ["docker", "exec", "-u", user, self.container_name, "sh", "-lc", command],
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


class MinimalContainerRustDaemonE2ETest(BaseDockerVmTest):
    """Full cross-boundary E2E against a zero-dependency container (NO git, NO python3)."""

    docker_target = "vm-minimal"

    def ensure_bootstrapped(self) -> None:
        if self.token:
            return
        res = self.ssh_exec(build_rust_bootstrap_command(self.daemon_port))
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

    def test_01_zero_dependency_ssh_bootstrap_and_idempotency(self) -> None:
        # Confirm neither git nor python3 is installed inside this container!
        self.assertNotEqual(self.docker_exec("command -v git").returncode, 0)
        self.assertNotEqual(self.docker_exec("command -v python3").returncode, 0)

        self.ensure_bootstrapped()

        unauth_status, _ = self.api("GET", "/api/info", token=None)
        self.assertEqual(unauth_status, 401)

        st, info = self.api("GET", "/api/info")
        self.assertEqual(st, 200)
        self.assertEqual(info["hostname"], "docker-e2e-vm")
        self.assertEqual(info["runtime"], "rust")
        self.assertEqual(info["buildHash"], self.binary_hash)
        self.assertEqual(info["commit"], self.binary_hash)
        self.assertIn("live-debugging.md", info["defaultSystemPrompt"])

        # Embedded live-debugging.md and app_state_cli symlink must be installed.
        self.assertEqual(self.docker_exec("test -f ~/.config/herdr-mobile/live-debugging.md").returncode, 0)
        self.assertEqual(self.docker_exec("test -L ~/.local/bin/app_state_cli").returncode, 0)

        pid_before = self.docker_exec("cat ~/.config/herdr-mobile/daemon.pid").stdout.strip()
        self.assertTrue(pid_before.isdigit())

        # Second SSH bootstrap skips download and preserves live daemon PID.
        res2 = self.ssh_exec(build_rust_bootstrap_command(self.daemon_port))
        self.assertEqual(res2.returncode, 0, res2.stdout + res2.stderr)
        self.assertIn("already matches release SHA-256", res2.stdout)
        pid_after = self.docker_exec("cat ~/.config/herdr-mobile/daemon.pid").stdout.strip()
        self.assertEqual(pid_before, pid_after)

        # @reboot crontab invokes native `ensure-running` subcommand (no Python start-daemon.sh).
        cron_out = self.docker_exec("crontab -l").stdout
        self.assertIn("@reboot", cron_out)
        self.assertIn("ensure-running", cron_out)

    def test_02_shell_ws_queue_and_issue_52_child_process_group_cleanup(self) -> None:
        self.ensure_bootstrapped()
        ws = MinimalWsClient("127.0.0.1", self.daemon_port, "/ws", self.token)
        try:
            snap = ws.recv_json()
            self.assertEqual(snap["type"], "snapshot")

            st, created = self.api(
                "POST",
                "/api/tabs",
                {"agent": "shell", "title": "Docker Shell", "cwd": "/home/vmuser"},
            )
            self.assertEqual(st, 200)
            tab_id = created["tab"]["id"]

            seen_types = []
            for _ in range(10):
                ev = ws.recv_json(timeout=5)
                seen_types.append(ev.get("type"))
                if ev.get("type") == "tab_created" and ev["tab"]["id"] == tab_id:
                    break
            self.assertIn("tab_created", seen_types)

            # Real command + cwd persistence.
            st, _ = self.api("POST", f"/api/tabs/{tab_id}/messages", {"text": "cd /tmp && echo RUST_DOCKER_OK && pwd"})
            self.assertEqual(st, 200)
            tab, msgs = self.wait_for_tab_idle(tab_id)
            self.assertEqual(tab["cwd"], "/tmp")
            self.assertIn("RUST_DOCKER_OK", msgs[-1]["text"])

            # Issue #52 exact reproduction: interrupting must kill child process group so `finished` never appears.
            self.docker_exec("rm -rf /tmp/issue52 && mkdir -p /tmp/issue52")
            st, _ = self.api(
                "POST",
                f"/api/tabs/{tab_id}/messages",
                {"text": "sh -c 'touch /tmp/issue52/started && sleep 3 && touch /tmp/issue52/finished'"},
            )
            self.assertEqual(st, 200)
            deadline = time.time() + 5
            while time.time() < deadline:
                if self.docker_exec("test -f /tmp/issue52/started").returncode == 0:
                    break
                time.sleep(0.1)
            self.assertEqual(self.docker_exec("test -f /tmp/issue52/started").returncode, 0)

            # Queue a follow-up, delete it from queue, then interrupt.
            st, queued = self.api("POST", f"/api/tabs/{tab_id}/messages", {"text": "echo SHOULD_NOT_RUN"})
            self.assertEqual(st, 200)
            self.assertTrue(queued.get("queued"))
            q_id = queued["queuedMessage"]["id"]
            self.assertEqual(self.api("DELETE", f"/api/tabs/{tab_id}/queue/{q_id}")[0], 200)

            self.assertEqual(self.api("POST", f"/api/tabs/{tab_id}/interrupt", {})[0], 200)
            _, msgs_after = self.wait_for_tab_idle(tab_id)
            self.assertEqual(msgs_after[-1]["status"], "interrupted")

            time.sleep(3.2)
            self.assertNotEqual(
                self.docker_exec("test -f /tmp/issue52/finished").returncode,
                0,
                "Issue #52 regression: orphaned child process created /tmp/issue52/finished after interrupt",
            )

            # PATCH / read / DELETE.
            p_st, patched = self.api("PATCH", f"/api/tabs/{tab_id}", {"title": "Renamed Rust Shell"})
            self.assertEqual(p_st, 200)
            self.assertEqual(patched["tab"]["title"], "Renamed Rust Shell")
            self.assertEqual(self.api("POST", f"/api/tabs/{tab_id}/read")[0], 200)
            self.assertEqual(self.api("DELETE", f"/api/tabs/{tab_id}")[0], 200)
        finally:
            ws.close()

    def test_03_coding_agents_streaming_resume_models_and_permissions(self) -> None:
        self.ensure_bootstrapped()

        for agent, expected_id in [("agy", "gemini-4-pro"), ("claude", "sonnet"), ("codex", "gpt-5.3-codex")]:
            st, data = self.api("GET", f"/api/agents/{agent}/models?refresh=1")
            self.assertEqual(st, 200)
            ids = [m["id"] for m in data["models"]]
            self.assertIn(expected_id, ids, f"{agent} models missing {expected_id}: {data}")

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

            self.assertEqual(self.api("POST", f"/api/tabs/{tab_id}/messages", {"text": "Check status"})[0], 200)
            tab1, msgs1 = self.wait_for_tab_idle(tab_id)
            self.assertEqual(tab1.get("taskSummary"), expected_summary)
            self.assertNotIn("<tab_task_summary>", msgs1[-1]["text"])
            self.assertTrue(msgs1[-1].get("toolCalls"))
            self.assertIsNotNone(tab1.get("sessionRef"))
            self.assertIn("resumed=no", msgs1[-1]["text"])

            target_model = {"agy": "gemini-4-flash", "claude": "opus", "codex": "o4-mini"}[agent]
            self.assertEqual(
                self.api("POST", f"/api/tabs/{tab_id}/messages", {"text": f"/model {target_model}"})[0], 200
            )
            tab_m, _ = self.wait_for_tab_idle(tab_id)
            self.assertEqual(tab_m.get("model"), target_model)

            self.assertEqual(self.api("POST", f"/api/tabs/{tab_id}/messages", {"text": "Follow up"})[0], 200)
            _, msgs2 = self.wait_for_tab_idle(tab_id)
            self.assertIn("resumed=yes", msgs2[-1]["text"])
            self.assertIn(f"model={target_model}", msgs2[-1]["text"])

        st, created = self.api(
            "POST",
            "/api/tabs",
            {"agent": "claude", "title": "Perm Test", "cwd": "/home/vmuser", "permissionMode": "default"},
        )
        self.assertEqual(st, 200)
        perm_tab = created["tab"]["id"]
        self.api("POST", f"/api/tabs/{perm_tab}/messages", {"text": "DENY_TOOL please"})
        perm_state, denied_msgs = self.wait_for_tab_idle(perm_tab, allowed_statuses=("blocked",))
        self.assertEqual(perm_state["status"], "blocked")
        self.assertTrue(denied_msgs[-1].get("deniedActions"))

        self.api("PATCH", f"/api/tabs/{perm_tab}", {"permissionMode": "full-access"})
        self.api("POST", f"/api/tabs/{perm_tab}/messages", {"text": "DENY_TOOL retry"})
        _, retry_msgs = self.wait_for_tab_idle(perm_tab)
        self.assertFalse(retry_msgs[-1].get("deniedActions"))
        self.assertIn("perms=1", retry_msgs[-1]["text"])

    def test_04_fs_debug_dumps_and_native_app_state_cli_without_python(self) -> None:
        self.ensure_bootstrapped()

        st, fs_data = self.api("GET", "/api/fs?path=/home/vmuser")
        self.assertEqual(st, 200)
        self.assertTrue(fs_data["exists"])
        self.assertIn("projects", [e["name"] for e in fs_data["directories"]])

        st, uploaded = self.api(
            "POST",
            "/api/debug/dumps",
            {
                "dumpId": "rust-docker-dump",
                "note": "Zero-dep Rust E2E dump",
                "appVersion": "0.3.e2e",
                "deviceInfo": "DockerE2EPhone",
                "eventsNdjson": '{"event":"test"}\n',
            },
        )
        self.assertEqual(st, 200)
        self.assertTrue(uploaded.get("ok"))
        st, listed = self.api("GET", "/api/debug/dumps")
        self.assertEqual(st, 200)
        self.assertTrue(any("rust-docker-dump" in d["dirName"] for d in listed["dumps"]))

        # Invoke native `app_state_cli` symlink (Rust binary, ZERO Python) inside the container.
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
            ws.recv_json()
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
            threading.Thread(target=responder, daemon=True).start()
            time.sleep(0.2)

            status_res = self.docker_exec(f"$HOME/.local/bin/app_state_cli --port {self.daemon_port} status")
            self.assertEqual(status_res.returncode, 0, status_res.stdout + status_res.stderr)
            self.assertTrue(json.loads(status_res.stdout)["ready"])

            state_res = self.docker_exec(f"$HOME/.local/bin/app_state_cli --port {self.daemon_port} state")
            self.assertEqual(state_res.returncode, 0, state_res.stdout + state_res.stderr)
            state_json = json.loads(state_res.stdout)
            self.assertTrue(state_json["ok"])
            self.assertEqual(state_json["result"]["ui"]["screen"], "chat")
        finally:
            stop_flag.set()
            ws.close()

    def test_05_issues_61_and_62_mid_turn_restart_and_timestamped_logs(self) -> None:
        self.ensure_bootstrapped()

        st, created = self.api(
            "POST",
            "/api/tabs",
            {"agent": "claude", "title": "MidTurn Restart", "cwd": "/home/vmuser"},
        )
        self.assertEqual(st, 200)
        tab_id = created["tab"]["id"]

        self.assertEqual(self.api("POST", f"/api/tabs/{tab_id}/messages", {"text": "SLOW_TURN check #62"})[0], 200)

        # Verify sessionRef is written to state.json WHILE turn 1 is still streaming (#62).
        deadline = time.time() + 5
        saved_ref = None
        while time.time() < deadline:
            raw = self.docker_exec("cat ~/.config/herdr-mobile/state.json").stdout
            if raw.strip():
                state = json.loads(raw)
                tab = next((t for t in state.get("tabs", []) if t["id"] == tab_id), None)
                if tab and isinstance(tab.get("sessionRef"), dict):
                    saved_ref = tab["sessionRef"].get("value")
                    if saved_ref:
                        break
            time.sleep(0.1)
        self.assertEqual(saved_ref, "claude-sess-e2e-1", "Issue #62: sessionRef not persisted mid-turn")

        # Restart daemon mid-turn via --force-restart.
        restart_res = self.docker_exec(
            f"$HOME/.local/bin/agentsterm-daemon install --port {self.daemon_port} --force-restart"
        )
        self.assertEqual(restart_res.returncode, 0, restart_res.stdout + restart_res.stderr)

        st, resp = self.api("GET", f"/api/tabs/{tab_id}/messages")
        self.assertEqual(st, 200)
        self.assertEqual(resp["tab"]["sessionRef"]["value"], "claude-sess-e2e-1")
        last_msg = resp["messages"][-1]
        self.assertEqual(last_msg["status"], "interrupted")
        self.assertIn("daemon restarted", last_msg.get("error") or "")

        # Verify daemon.log has ISO-8601 UTC timestamps (#61).
        daemon_log = self.docker_exec("cat ~/.config/herdr-mobile/daemon.log").stdout
        self.assertRegex(daemon_log, ISO_TS_RE, f"Expected ISO-8601 timestamps in daemon.log:\n{daemon_log}")


if __name__ == "__main__":
    unittest.main()
