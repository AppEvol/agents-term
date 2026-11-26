#!/usr/bin/env python3
"""Comprehensive multi-distro Linux VM end-to-end test suite for the static Rust daemon.

Runs completely headless (no Android app/emulator required) against real Linux Docker
containers representing popular VM environments:
  - vm-ubuntu-2404     Ubuntu 24.04 LTS (dash, curl, cron, zero git/python3)
  - vm-ubuntu-2204     Ubuntu 22.04 LTS (dash, curl, cron, zero git/python3)
  - vm-debian-12       Debian 12 Bookworm (dash, wget-only/no curl, cron, zero git/python3)
  - vm-almalinux-9     AlmaLinux 9 / RHEL 9 compatible (bash, curl, cronie, zero git)
  - vm-fedora-41       Fedora 41 (bash, curl, no cron -> detached-only fallback, zero git)
  - vm-amazonlinux-2023 Amazon Linux 2023 / AWS EC2 (bash, curl, no cron, zero git)
  - vm-alpine-321      Alpine Linux 3.21 (musl libc, BusyBox ash, wget-only/no curl, zero git/python3)

Select a specific environment with `E2E_VM_TARGET=<target>` (used by CI matrix).
When unset, runs `vm-ubuntu-2404`.
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
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

SCRIPT_DIR = Path(__file__).resolve().parent
START_VM_SCRIPT = SCRIPT_DIR / "start_vm.sh"
PAIRING_URI_RE = re.compile(r"(?:agentsterm|herdr)://connect\?[^\s\"'`<>)\]]+", re.IGNORECASE)
ISO_TS_RE = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z \[(?:INFO|WARN|ERROR)\]")

# Minimal valid 1x1 transparent PNG for screenshot / debug-dump tests.
TINY_PNG_B64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="


@dataclass(frozen=True)
class DistroProfile:
    target: str
    expect_no_git: bool
    expect_no_python: bool
    expect_no_curl: bool
    expect_cron: Optional[bool]  # True=must install @reboot, False=must fall back cleanly, None=either ok
    expect_musl: bool


DISTRO_PROFILES: Dict[str, DistroProfile] = {
    "vm-minimal": DistroProfile("vm-minimal", True, True, False, True, False),
    "vm-ubuntu-2404": DistroProfile("vm-ubuntu-2404", True, True, False, True, False),
    "vm-ubuntu-2204": DistroProfile("vm-ubuntu-2204", True, True, False, True, False),
    "vm-debian-12": DistroProfile("vm-debian-12", True, True, True, True, False),
    "vm-almalinux-9": DistroProfile("vm-almalinux-9", True, False, False, True, False),
    "vm-fedora-41": DistroProfile("vm-fedora-41", True, True, False, False, False),
    "vm-amazonlinux-2023": DistroProfile("vm-amazonlinux-2023", True, False, False, False, False),
    "vm-alpine-321": DistroProfile("vm-alpine-321", True, True, True, None, True),
}

SELECTED_TARGET = os.environ.get("E2E_VM_TARGET", "").strip()


def free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class MinimalWsClient:
    """Minimal RFC 6455 text WebSocket client using only Python stdlib."""

    def __init__(self, host: str, port: int, path: str, token: Optional[str] = None) -> None:
        self.sock = socket.create_connection((host, port), timeout=15)
        self.sock.settimeout(15)
        key = base64.b64encode(os.urandom(16)).decode("ascii")
        headers = [
            f"GET {path} HTTP/1.1",
            f"Host: {host}:{port}",
            "Upgrade: websocket",
            "Connection: Upgrade",
            f"Sec-WebSocket-Key: {key}",
            "Sec-WebSocket-Version: 13",
        ]
        if token is not None:
            headers.append(f"Authorization: Bearer {token}")
        req = "\r\n".join(headers) + "\r\n\r\n"
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
            self.sock.close()
            raise ConnectionError(f"WebSocket handshake failed: {status_line}")
        expected_accept = base64.b64encode(
            hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode("ascii")).digest()
        ).decode("ascii")
        if expected_accept.encode("ascii") not in header_raw:
            self.sock.close()
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

    def recv_until(self, predicate: Any, timeout: float = 10.0) -> Dict[str, Any]:
        deadline = time.time() + timeout
        while time.time() < deadline:
            remaining = max(0.2, deadline - time.time())
            ev = self.recv_json(timeout=remaining)
            if predicate(ev):
                return ev
        raise AssertionError("Timed out waiting for matching WebSocket event")

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
    env_override: Optional[Dict[str, str]] = None,
) -> str:
    """Matches the production POSIX bootstrap command from SshBootstrapModels.kt."""
    env_exports = ""
    if env_override:
        for k, v in env_override.items():
            env_exports += f'export {k}="{v}"\n'
    script = f"""[ -f "$HOME/.profile" ] && command . "$HOME/.profile" >/dev/null 2>&1 || true
set -eu
{env_exports}export PATH="$HOME/.local/bin:$HOME/.npm-global/bin:$HOME/.cargo/bin:/usr/local/bin:$PATH"
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
    docker_target: str = "vm-ubuntu-2404"
    container_name: str = ""
    ssh_port: int = 0
    daemon_port: int = 0
    snapshot_dir: Optional[Path] = None
    binary_hash: str = ""
    binary_sha256: str = ""
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
        )
        if proc.returncode != 0:
            raise RuntimeError(
                f"start_vm.sh failed for target={cls.docker_target} (rc={proc.returncode}):\n"
                f"STDOUT:\n{proc.stdout}\nSTDERR:\n{proc.stderr}"
            )
        meta: Dict[str, str] = {}
        for line in proc.stdout.splitlines():
            if "=" in line:
                k, v = line.split("=", 1)
                meta[k.strip()] = v.strip()
        cls.snapshot_dir = Path(meta["SNAPSHOT_DIR"])
        cls.binary_hash = meta["BINARY_HASH"]
        cls.binary_sha256 = meta["BINARY_SHA256"]
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

    def docker_exec(self, command: str, user: str = "vmuser", timeout: int = 30) -> subprocess.CompletedProcess:
        return subprocess.run(
            ["docker", "exec", "-u", user, self.container_name, "sh", "-lc", command],
            capture_output=True,
            text=True,
            timeout=timeout,
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

    def wait_for_daemon_healthy(self, token: Optional[str] = "default", timeout: float = 15.0) -> Dict[str, Any]:
        deadline = time.time() + timeout
        last_err = ""
        while time.time() < deadline:
            try:
                st, info = self.api("GET", "/api/info", token=token)
                if st == 200 and isinstance(info, dict):
                    return info
                last_err = f"status={st} body={info}"
            except OSError as exc:
                last_err = str(exc)
            time.sleep(0.2)
        raise AssertionError(f"Daemon did not become healthy within {timeout}s: {last_err}")

    def wait_for_tab_idle(
        self,
        tab_id: str,
        timeout: float = 20.0,
        allowed_statuses: Tuple[str, ...] = ("idle", "done"),
        min_messages: int = 1,
    ) -> Tuple[Dict[str, Any], List[Dict[str, Any]]]:
        deadline = time.time() + timeout
        last_state = None
        while time.time() < deadline:
            status, tab_data = self.api("GET", "/api/tabs")
            self.assertEqual(status, 200)
            tabs = tab_data["tabs"] if isinstance(tab_data, dict) else tab_data
            tab = next((t for t in tabs if t["id"] == tab_id), None)
            self.assertIsNotNone(tab)
            last_state = tab["status"]
            if tab["status"] in allowed_statuses and not tab.get("queuedMessages"):
                m_status, msg_data = self.api("GET", f"/api/tabs/{tab_id}/messages")
                self.assertEqual(m_status, 200)
                messages = msg_data["messages"] if isinstance(msg_data, dict) else msg_data
                if len(messages) >= min_messages and messages[-1]["status"] != "streaming":
                    return tab, messages
            time.sleep(0.15)
        raise AssertionError(
            f"Tab {tab_id} did not reach {allowed_statuses} (last={last_state}, min_messages={min_messages}) "
            f"within {timeout}s"
        )


def _selected_linux_target() -> str:
    if SELECTED_TARGET and SELECTED_TARGET in DISTRO_PROFILES:
        return SELECTED_TARGET
    return "vm-ubuntu-2404"


class RustDaemonLinuxVmE2ETest(BaseDockerVmTest):
    """Comprehensive E2E test suite for the static Rust daemon across popular Linux VM distros."""

    docker_target = _selected_linux_target()

    def profile(self) -> DistroProfile:
        return DISTRO_PROFILES[self.docker_target]

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

    def test_01_ssh_bootstrap_distro_environment_and_idempotency(self) -> None:
        prof = self.profile()
        if prof.expect_no_git:
            self.assertNotEqual(self.docker_exec("command -v git").returncode, 0)
        if prof.expect_no_python:
            self.assertNotEqual(self.docker_exec("command -v python3").returncode, 0)
        if prof.expect_no_curl:
            self.assertNotEqual(self.docker_exec("command -v curl").returncode, 0)
            self.assertEqual(self.docker_exec("command -v wget").returncode, 0)
        if prof.expect_musl:
            os_rel = self.docker_exec("cat /etc/os-release").stdout.lower()
            self.assertIn("alpine", os_rel)

        # First bootstrap over real SSH.
        res1 = self.ssh_exec(build_rust_bootstrap_command(self.daemon_port))
        self.assertEqual(res1.returncode, 0, f"SSH bootstrap failed:\nSTDOUT:\n{res1.stdout}\nSTDERR:\n{res1.stderr}")
        match = PAIRING_URI_RE.search(res1.stdout + "\n" + res1.stderr)
        self.assertIsNotNone(match, f"No pairing URI in bootstrap output:\n{res1.stdout}")
        params = urllib.parse.parse_qs(urllib.parse.urlparse(match.group(0)).query)
        type(self).token = params["token"][0]

        st, info = self.api("GET", "/api/info")
        self.assertEqual(st, 200)
        self.assertEqual(info["hostname"], "docker-e2e-vm")
        self.assertEqual(info["runtime"], "rust")
        self.assertEqual(info["buildHash"], self.binary_hash)
        self.assertEqual(info["commit"], self.binary_hash)
        self.assertEqual(info["defaultCwd"], "/home/vmuser")
        self.assertIn("live-debugging.md", info["defaultSystemPrompt"])
        self.assertIn("agents_term_app_cli", info["defaultSystemPrompt"])
        self.assertIn("close this tab", info["defaultSystemPromptFullAccess"])
        agent_types = {a["type"]: a for a in info["agents"]}
        for kind in ("agy", "claude", "codex", "shell"):
            self.assertIn(kind, agent_types)
            self.assertTrue(agent_types[kind]["available"], f"Agent {kind} should be available")

        # Verify embedded doc, symlinks, and strict config permissions (0700 dir / 0600 config.json).
        self.assertEqual(self.docker_exec("test -f ~/.config/herdr-mobile/live-debugging.md").returncode, 0)
        self.assertEqual(self.docker_exec("test -L ~/.local/bin/agents_term_app_cli").returncode, 0)
        self.assertEqual(self.docker_exec("test -L ~/.local/bin/app_state_cli").returncode, 0)
        perms = self.docker_exec(
            "stat -c '%a' ~/.config/herdr-mobile ~/.config/herdr-mobile/config.json"
        ).stdout.split()
        self.assertEqual(perms, ["700", "600"])

        pid_before = self.docker_exec("cat ~/.config/herdr-mobile/daemon.pid").stdout.strip()
        self.assertTrue(pid_before.isdigit())

        # Second SSH bootstrap skips download and preserves live daemon PID.
        res2 = self.ssh_exec(build_rust_bootstrap_command(self.daemon_port))
        self.assertEqual(res2.returncode, 0, res2.stdout + res2.stderr)
        self.assertIn("already matches release SHA-256", res2.stdout)
        self.assertIn("skipping restart to preserve live turns", res2.stdout)
        pid_after = self.docker_exec("cat ~/.config/herdr-mobile/daemon.pid").stdout.strip()
        self.assertEqual(pid_before, pid_after)

        # Verify reboot persistence handling matches distro capabilities.
        if prof.expect_cron is True:
            cron_out = self.docker_exec("crontab -l").stdout
            self.assertIn("@reboot", cron_out)
            self.assertIn("ensure-running", cron_out)
            self.assertIn("will survive reboots", res1.stdout)
        elif prof.expect_cron is False:
            self.assertIn("will NOT survive a reboot", res1.stdout)

    def test_02_bootstrap_sha256_mismatch_and_tailscale_failure_guards(self) -> None:
        self.ensure_bootstrapped()

        # 1. Tampered SHA-256 mirror must fail with exit code 3 and leave installed binary untouched.
        sha_before = self.docker_exec("sha256sum ~/.local/bin/agentsterm-daemon | awk '{print $1}'").stdout.strip()
        bad_port = 18089
        self.docker_exec(
            "rm -rf /tmp/bad-release && mkdir -p /tmp/bad-release && "
            "cp /opt/agentsterm-release/agentsterm-daemon-linux-* /tmp/bad-release/ && "
            "for f in /tmp/bad-release/*.sha256; do "
            "  echo '0000000000000000000000000000000000000000000000000000000000000000  bad' > \"$f\"; "
            "done",
            user="root",
        )
        # Spin up temporary HTTP server for tampered artifacts inside the container.
        self.docker_exec(
            f"if command -v httpd >/dev/null 2>&1 && httpd --help 2>&1 | grep -qi busybox; then "
            f"  httpd -p 127.0.0.1:{bad_port} -h /tmp/bad-release; "
            f"elif command -v busybox >/dev/null 2>&1 && busybox httpd --help >/dev/null 2>&1; then "
            f"  busybox httpd -p 127.0.0.1:{bad_port} -h /tmp/bad-release; "
            f"else "
            f"  nohup python3 -m http.server {bad_port} --bind 127.0.0.1 --directory /tmp/bad-release >/dev/null 2>&1 & "
            f"fi",
            user="root",
        )
        time.sleep(0.3)
        bad_cmd = build_rust_bootstrap_command(
            self.daemon_port,
            env_override={"AGENTSTERM_RELEASE_BASE_URL": f"http://127.0.0.1:{bad_port}"},
        )
        bad_res = self.ssh_exec(bad_cmd)
        self.assertEqual(bad_res.returncode, 3, f"Expected exit 3 on SHA mismatch:\n{bad_res.stdout}\n{bad_res.stderr}")
        self.assertIn("SHA-256 mismatch", bad_res.stderr)
        sha_after = self.docker_exec("sha256sum ~/.local/bin/agentsterm-daemon | awk '{print $1}'").stdout.strip()
        self.assertEqual(sha_before, sha_after)

        # 2. Tailscale down without --host must exit 3; explicit --host must still succeed.
        try:
            self.docker_exec("touch /tmp/tailscale-down", user="root")
            no_host_res = self.docker_exec(f"$HOME/.local/bin/agentsterm-daemon install --port {self.daemon_port}")
            self.assertEqual(no_host_res.returncode, 3, no_host_res.stdout + no_host_res.stderr)
            self.assertIn("Tailscale", no_host_res.stderr)

            with_host_res = self.docker_exec(
                f"$HOME/.local/bin/agentsterm-daemon install --host 127.0.0.1 --port {self.daemon_port}"
            )
            self.assertEqual(with_host_res.returncode, 0, with_host_res.stdout + with_host_res.stderr)
        finally:
            self.docker_exec("rm -f /tmp/tailscale-down", user="root")

    def test_03_auth_security_and_http_error_contracts(self) -> None:
        self.ensure_bootstrapped()

        # Missing & wrong bearer tokens rejected across REST endpoints.
        for path in ("/api/info", "/api/tabs", "/api/fs", "/api/debug/dumps", "/api/app/status"):
            self.assertEqual(self.api("GET", path, token=None)[0], 401, f"Missing token should 401 on {path}")
            self.assertEqual(self.api("GET", path, token="wrong-token")[0], 401, f"Wrong token should 401 on {path}")

        # WebSocket rejects missing/invalid token and accepts query-param token.
        with self.assertRaises(ConnectionError):
            MinimalWsClient("127.0.0.1", self.daemon_port, "/ws", token=None)
        with self.assertRaises(ConnectionError):
            MinimalWsClient("127.0.0.1", self.daemon_port, "/ws?token=wrong-token", token=None)
        ws_query = MinimalWsClient("127.0.0.1", self.daemon_port, f"/ws?token={self.token}", token=None)
        try:
            snap = ws_query.recv_json()
            self.assertEqual(snap["type"], "snapshot")
            self.assertEqual(snap["server"]["runtime"], "rust")
        finally:
            ws_query.close()

        # Unknown endpoint -> 404.
        st, body = self.api("GET", "/api/does-not-exist")
        self.assertEqual(st, 404)
        self.assertIn("error", body)

        # Missing required agent -> 400.
        st, _ = self.api("POST", "/api/tabs", {"title": "No Agent"})
        self.assertEqual(st, 400)

        # Non-existent tab -> 404 across all tab endpoints.
        fake_id = "tab_doesnotexist"
        self.assertEqual(self.api("PATCH", f"/api/tabs/{fake_id}", {"title": "X"})[0], 404)
        self.assertEqual(self.api("GET", f"/api/tabs/{fake_id}/messages")[0], 404)
        self.assertEqual(self.api("POST", f"/api/tabs/{fake_id}/messages", {"text": "hi"})[0], 404)
        self.assertEqual(self.api("DELETE", f"/api/tabs/{fake_id}/queue/q_none")[0], 404)
        self.assertEqual(self.api("POST", f"/api/tabs/{fake_id}/interrupt", {})[0], 404)
        self.assertEqual(self.api("POST", f"/api/tabs/{fake_id}/read")[0], 404)

        # Empty message text -> 400, and unsupported agent type -> error message.
        st, created = self.api("POST", "/api/tabs", {"agent": "unsupported-bot", "cwd": "/home/vmuser"})
        self.assertEqual(st, 200)
        bad_tab = created["tab"]["id"]
        try:
            self.assertEqual(self.api("POST", f"/api/tabs/{bad_tab}/messages", {"text": "   "})[0], 400)
            self.assertEqual(self.api("POST", f"/api/tabs/{bad_tab}/messages", {"text": "hello"})[0], 200)
            tab_err, msgs_err = self.wait_for_tab_idle(bad_tab, allowed_statuses=("error", "idle"))
            self.assertEqual(msgs_err[-1]["status"], "error")
            self.assertIn("Unsupported agent type", msgs_err[-1].get("error") or "")
            self.assertIn(tab_err["status"], ("error", "idle"))
        finally:
            self.api("DELETE", f"/api/tabs/{bad_tab}")

    def test_04_cli_subcommands_ensure_running_pairing_and_token_rotation(self) -> None:
        self.ensure_bootstrapped()

        # version & version --hash-only
        ver = self.docker_exec("$HOME/.local/bin/agentsterm-daemon version")
        self.assertEqual(ver.returncode, 0)
        self.assertIn(self.binary_hash, ver.stdout)
        hash_only = self.docker_exec("$HOME/.local/bin/agentsterm-daemon version --hash-only")
        self.assertEqual(hash_only.returncode, 0)
        self.assertEqual(hash_only.stdout.strip(), self.binary_hash)

        # pairing prints active host/port/token URI
        pair = self.docker_exec("$HOME/.local/bin/agentsterm-daemon pairing")
        self.assertEqual(pair.returncode, 0)
        self.assertIn(f"token={self.token}", pair.stdout)

        # Create a persistence marker tab before testing stop/ensure-running/token rotation.
        st, created = self.api("POST", "/api/tabs", {"agent": "shell", "title": "Persist Across Lifecycle"})
        self.assertEqual(st, 200)
        marker_tab_id = created["tab"]["id"]
        self.assertEqual(
            self.api("POST", f"/api/tabs/{marker_tab_id}/messages", {"text": "echo SURVIVED_LIFECYCLE"})[0], 200
        )
        self.wait_for_tab_idle(marker_tab_id)

        # ensure-running while already running is a no-op (PID unchanged).
        pid1 = self.docker_exec("cat ~/.config/herdr-mobile/daemon.pid").stdout.strip()
        er1 = self.docker_exec(
            f"$HOME/.local/bin/agentsterm-daemon ensure-running --host 127.0.0.1 --port {self.daemon_port}"
        )
        self.assertEqual(er1.returncode, 0, er1.stdout + er1.stderr)
        pid2 = self.docker_exec("cat ~/.config/herdr-mobile/daemon.pid").stdout.strip()
        self.assertEqual(pid1, pid2)

        # Stop daemon via SIGTERM, then use ensure-running to revive it cleanly.
        self.docker_exec("kill -TERM $(cat ~/.config/herdr-mobile/daemon.pid)")
        deadline = time.time() + 6
        while time.time() < deadline:
            if self.docker_exec(f"kill -0 {pid1} 2>/dev/null").returncode != 0:
                break
            time.sleep(0.1)
        self.assertNotEqual(self.docker_exec(f"kill -0 {pid1} 2>/dev/null").returncode, 0)

        er2 = self.docker_exec(
            f"$HOME/.local/bin/agentsterm-daemon ensure-running --host 127.0.0.1 --port {self.daemon_port}"
        )
        self.assertEqual(er2.returncode, 0, er2.stdout + er2.stderr)
        self.wait_for_daemon_healthy()
        pid3 = self.docker_exec("cat ~/.config/herdr-mobile/daemon.pid").stdout.strip()
        self.assertNotEqual(pid1, pid3)

        # Verify marker tab survived stop + ensure-running.
        st, msgs_resp = self.api("GET", f"/api/tabs/{marker_tab_id}/messages")
        self.assertEqual(st, 200)
        self.assertTrue(any("SURVIVED_LIFECYCLE" in m.get("text", "") for m in msgs_resp["messages"]))

        # Rotate token + restart daemon: old token must fail with 401, new token succeeds.
        old_token = self.token
        rot = self.docker_exec(
            f"$HOME/.local/bin/agentsterm-daemon rotate-token --host 127.0.0.1 --port {self.daemon_port}"
        )
        self.assertEqual(rot.returncode, 0, rot.stdout + rot.stderr)
        rot_match = PAIRING_URI_RE.search(rot.stdout)
        self.assertIsNotNone(rot_match)
        new_token = urllib.parse.parse_qs(urllib.parse.urlparse(rot_match.group(0)).query)["token"][0]
        self.assertNotEqual(old_token, new_token)

        restart = self.docker_exec(
            f"$HOME/.local/bin/agentsterm-daemon install --host 127.0.0.1 --port {self.daemon_port} --force-restart"
        )
        self.assertEqual(restart.returncode, 0, restart.stdout + restart.stderr)
        type(self).token = new_token

        self.assertEqual(self.api("GET", "/api/info", token=old_token)[0], 401)
        self.assertEqual(self.api("GET", "/api/info", token=new_token)[0], 200)
        st, msgs_after_rot = self.api("GET", f"/api/tabs/{marker_tab_id}/messages")
        self.assertEqual(st, 200)
        self.assertTrue(any("SURVIVED_LIFECYCLE" in m.get("text", "") for m in msgs_after_rot["messages"]))
        self.assertEqual(self.api("DELETE", f"/api/tabs/{marker_tab_id}")[0], 200)

    def test_05_shell_runner_multi_ws_broadcast_and_issue_52_cleanup(self) -> None:
        self.ensure_bootstrapped()
        ws1 = MinimalWsClient("127.0.0.1", self.daemon_port, "/ws", self.token)
        ws2 = MinimalWsClient("127.0.0.1", self.daemon_port, f"/ws?token={self.token}", None)
        try:
            self.assertEqual(ws1.recv_json()["type"], "snapshot")
            self.assertEqual(ws2.recv_json()["type"], "snapshot")

            st, created = self.api(
                "POST",
                "/api/tabs",
                {"agent": "shell", "title": "Docker Shell", "cwd": "/home/vmuser"},
            )
            self.assertEqual(st, 200)
            tab_id = created["tab"]["id"]

            # Both WS clients must receive tab_created.
            ws1.recv_until(lambda e: e.get("type") == "tab_created" and e["tab"]["id"] == tab_id)
            ws2.recv_until(lambda e: e.get("type") == "tab_created" and e["tab"]["id"] == tab_id)

            # Command stdout + stderr + cwd persistence + enriched PATH (~/.local/bin).
            st, _ = self.api(
                "POST",
                f"/api/tabs/{tab_id}/messages",
                {"text": "cd /tmp && echo RUST_DOCKER_OK && echo STDERR_LINE >&2 && command -v agentsterm-daemon"},
            )
            self.assertEqual(st, 200)
            tab, msgs = self.wait_for_tab_idle(tab_id)
            self.assertEqual(tab["cwd"], "/tmp")
            self.assertIn("RUST_DOCKER_OK", msgs[-1]["text"])
            self.assertIn("STDERR_LINE", msgs[-1]["text"])
            self.assertIn(".local/bin/agentsterm-daemon", msgs[-1]["text"])
            self.assertTrue(tab.get("unread"))

            # Mark read clears unread flag.
            self.assertEqual(self.api("POST", f"/api/tabs/{tab_id}/read")[0], 200)
            _, tabs_after_read = self.api("GET", "/api/tabs")
            read_tab = next(t for t in tabs_after_read["tabs"] if t["id"] == tab_id)
            self.assertFalse(read_tab.get("unread"))

            # Non-zero exit code with stderr and partial stdout preserves text and attaches error banner.
            self.assertEqual(
                self.api("POST", f"/api/tabs/{tab_id}/messages", {"text": "echo FAIL_OUT; echo FAIL_ERR >&2; exit 7"})[
                    0
                ],
                200,
            )
            _, fail_msgs = self.wait_for_tab_idle(tab_id, allowed_statuses=("idle", "error"), min_messages=4)
            self.assertIn("FAIL_OUT", fail_msgs[-1]["text"])
            self.assertIn("Exited with code 7", fail_msgs[-1].get("error") or "")

            # Issue #52: interrupting must kill child process group so `finished` never appears.
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

            self.assertEqual(self.api("POST", f"/api/tabs/{tab_id}/interrupt", {})[0], 200)
            _, msgs_after = self.wait_for_tab_idle(tab_id, min_messages=6)
            self.assertEqual(msgs_after[-1]["status"], "interrupted")

            time.sleep(3.2)
            self.assertNotEqual(
                self.docker_exec("test -f /tmp/issue52/finished").returncode,
                0,
                "Issue #52 regression: orphaned child process created /tmp/issue52/finished after interrupt",
            )

            # DELETE while a command is actively running must kill its child process group & broadcast tab_deleted.
            self.docker_exec("rm -rf /tmp/delete_running && mkdir -p /tmp/delete_running")
            self.assertEqual(
                self.api(
                    "POST",
                    f"/api/tabs/{tab_id}/messages",
                    {
                        "text": "sh -c 'touch /tmp/delete_running/started && sleep 3 && touch /tmp/delete_running/finished'"
                    },
                )[0],
                200,
            )
            deadline = time.time() + 5
            while time.time() < deadline:
                if self.docker_exec("test -f /tmp/delete_running/started").returncode == 0:
                    break
                time.sleep(0.1)
            self.assertEqual(self.docker_exec("test -f /tmp/delete_running/started").returncode, 0)

            self.assertEqual(self.api("DELETE", f"/api/tabs/{tab_id}")[0], 200)
            ws1.recv_until(lambda e: e.get("type") == "tab_deleted" and e.get("tabId") == tab_id)
            ws2.recv_until(lambda e: e.get("type") == "tab_deleted" and e.get("tabId") == tab_id)

            time.sleep(3.2)
            self.assertNotEqual(
                self.docker_exec("test -f /tmp/delete_running/finished").returncode,
                0,
                "Deleting an active tab left an orphaned child process running",
            )
        finally:
            ws1.close()
            ws2.close()

    def test_06_message_queue_auto_drain_and_interrupt_with_queue(self) -> None:
        self.ensure_bootstrapped()
        st, created = self.api("POST", "/api/tabs", {"agent": "shell", "title": "Queue Test", "cwd": "/home/vmuser"})
        self.assertEqual(st, 200)
        tab_id = created["tab"]["id"]
        try:
            # Start a short busy turn and enqueue 3 follow-ups; delete the middle one.
            self.assertEqual(
                self.api("POST", f"/api/tabs/{tab_id}/messages", {"text": "sleep 1.2 && echo TURN_ONE"})[0], 200
            )
            q1 = self.api("POST", f"/api/tabs/{tab_id}/messages", {"text": "echo QUEUED_PART_A"})[1]
            q2 = self.api("POST", f"/api/tabs/{tab_id}/messages", {"text": "echo QUEUED_REMOVED"})[1]
            q3 = self.api("POST", f"/api/tabs/{tab_id}/messages", {"text": "echo QUEUED_PART_B"})[1]
            self.assertTrue(q1.get("queued"))
            self.assertTrue(q2.get("queued"))
            self.assertTrue(q3.get("queued"))
            self.assertEqual(len(q3["tab"]["queuedMessages"]), 3)

            del_st, del_resp = self.api("DELETE", f"/api/tabs/{tab_id}/queue/{q2['queuedMessage']['id']}")
            self.assertEqual(del_st, 200)
            self.assertEqual(len(del_resp["tab"]["queuedMessages"]), 2)

            # Wait for turn 1 AND the auto-drained combined turn 2 (4 messages total: U1, A1, U2, A2).
            tab, msgs = self.wait_for_tab_idle(tab_id, timeout=15.0, min_messages=4)
            self.assertEqual(tab["queuedMessages"], [])
            self.assertIn("TURN_ONE", msgs[1]["text"])
            self.assertIn("QUEUED_PART_A", msgs[3]["text"])
            self.assertIn("QUEUED_PART_B", msgs[3]["text"])
            self.assertNotIn("QUEUED_REMOVED", msgs[3]["text"])

            # Interrupt active turn with processQueue=True + inline replacement text.
            self.docker_exec("rm -f /tmp/q_int_started")
            self.assertEqual(
                self.api("POST", f"/api/tabs/{tab_id}/messages", {"text": "touch /tmp/q_int_started && sleep 8"})[0],
                200,
            )
            deadline = time.time() + 5
            while time.time() < deadline:
                if self.docker_exec("test -f /tmp/q_int_started").returncode == 0:
                    break
                time.sleep(0.1)
            self.assertEqual(
                self.api(
                    "POST",
                    f"/api/tabs/{tab_id}/interrupt",
                    {"processQueue": True, "text": "echo INTERRUPT_HANDOFF_OK"},
                )[0],
                200,
            )
            _, msgs_handoff = self.wait_for_tab_idle(tab_id, timeout=15.0, min_messages=8)
            self.assertEqual(msgs_handoff[5]["status"], "interrupted")
            self.assertIn("INTERRUPT_HANDOFF_OK", msgs_handoff[-1]["text"])

            # Interrupting an idle tab is a safe 200 no-op.
            self.assertEqual(self.api("POST", f"/api/tabs/{tab_id}/interrupt", {})[0], 200)
        finally:
            self.api("DELETE", f"/api/tabs/{tab_id}")

    def test_07_coding_agents_models_streaming_resume_system_prompt_and_permissions(self) -> None:
        self.ensure_bootstrapped()

        # Model discovery: ANSI + tab/space + dedup (agy), defaults (claude), priority + hidden filter (codex).
        st, agy_m = self.api("GET", "/api/agents/agy/models?refresh=1")
        self.assertEqual(st, 200)
        self.assertEqual([m["id"] for m in agy_m["models"]], ["gemini-4-pro", "gemini-4-flash"])
        self.assertEqual(agy_m["defaultModel"], "gemini-4-pro")

        st, claude_m = self.api("GET", "/api/agents/claude/models?refresh=1")
        self.assertEqual(st, 200)
        self.assertIn("sonnet", [m["id"] for m in claude_m["models"]])
        self.assertEqual(claude_m["defaultModel"], "sonnet")

        st, codex_m = self.api("GET", "/api/agents/codex/models?refresh=1")
        self.assertEqual(st, 200)
        codex_ids = [m["id"] for m in codex_m["models"]]
        self.assertEqual(codex_ids, ["gpt-5.3-codex", "o4-mini"], f"Priority sort / hide filter failed: {codex_m}")
        self.assertNotIn("hidden-internal-model", codex_ids)

        # Full multi-turn lifecycle for all 3 coding agents.
        for agent, expected_summary, target_model in [
            ("agy", "Docker E2E Agy Task", "gemini-4-flash"),
            ("claude", "Docker E2E Claude Task", "opus"),
            ("codex", "Docker E2E Codex Task", "o4-mini"),
        ]:
            st, created = self.api(
                "POST",
                "/api/tabs",
                {
                    "agent": agent,
                    "title": f"E2E {agent}",
                    "cwd": "/home/vmuser",
                    "permissionMode": "full-access",
                    "systemPrompt": "CUSTOM_SP_V1",
                },
            )
            self.assertEqual(st, 200)
            tab_id = created["tab"]["id"]
            try:
                # Turn 1: task summary extraction, tool calls, usage, sessionRef, systemPrompt v1.
                self.assertEqual(self.api("POST", f"/api/tabs/{tab_id}/messages", {"text": "Check status"})[0], 200)
                tab1, msgs1 = self.wait_for_tab_idle(tab_id, min_messages=2)
                self.assertEqual(tab1.get("taskSummary"), expected_summary)
                self.assertNotIn("<tab_task_summary>", msgs1[-1]["text"])
                self.assertTrue(msgs1[-1].get("toolCalls"))
                self.assertIsNotNone(msgs1[-1].get("usage"))
                self.assertIsNotNone(tab1.get("sessionRef"))
                self.assertIn("resumed=no", msgs1[-1]["text"])
                self.assertIn("sp=v1", msgs1[-1]["text"])
                if agent in ("claude", "codex"):
                    self.assertTrue(msgs1[-1].get("thinking"), f"{agent} should capture thinking/reasoning")

                # /model query, set, reset, and set again.
                self.assertEqual(self.api("POST", f"/api/tabs/{tab_id}/messages", {"text": "/model"})[0], 200)
                _, msgs_q = self.wait_for_tab_idle(tab_id, min_messages=4)
                self.assertIn("Current model", msgs_q[-1]["text"])

                self.assertEqual(
                    self.api("POST", f"/api/tabs/{tab_id}/messages", {"text": f"/model {target_model}"})[0], 200
                )
                tab_m, _ = self.wait_for_tab_idle(tab_id, min_messages=6)
                self.assertEqual(tab_m.get("model"), target_model)

                self.assertEqual(self.api("POST", f"/api/tabs/{tab_id}/messages", {"text": "/model default"})[0], 200)
                tab_rst, _ = self.wait_for_tab_idle(tab_id, min_messages=8)
                self.assertIsNone(tab_rst.get("model"))

                self.assertEqual(
                    self.api("POST", f"/api/tabs/{tab_id}/messages", {"text": f"/model {target_model}"})[0], 200
                )
                self.wait_for_tab_idle(tab_id, min_messages=10)

                # Update systemPrompt to v2 and run Turn 2 with TASK_DONE -> verifies resume + model + sp=v2 + done status!
                p_st, patched = self.api("PATCH", f"/api/tabs/{tab_id}", {"systemPrompt": "CUSTOM_SP_V2"})
                self.assertEqual(p_st, 200)
                self.assertEqual(patched["tab"]["systemPrompt"], "CUSTOM_SP_V2")

                self.assertEqual(
                    self.api("POST", f"/api/tabs/{tab_id}/messages", {"text": "Follow up TASK_DONE"})[0], 200
                )
                tab2, msgs2 = self.wait_for_tab_idle(tab_id, allowed_statuses=("done",), min_messages=12)
                self.assertEqual(tab2["status"], "done")
                self.assertNotIn("<this-work-is-done-172728>", msgs2[-1]["text"])
                self.assertIn("resumed=yes", msgs2[-1]["text"])
                self.assertIn(f"model={target_model}", msgs2[-1]["text"])
                self.assertIn("sp=v2", msgs2[-1]["text"])

                # Manual status toggle done -> idle via PATCH.
                st_p, p_idle = self.api("PATCH", f"/api/tabs/{tab_id}", {"status": "idle"})
                self.assertEqual(st_p, 200)
                self.assertEqual(p_idle["tab"]["status"], "idle")

                # CLI failure turn -> surfaces error cleanly (partial output preserves text + error banner;
                # empty output sets status="error").
                self.assertEqual(self.api("POST", f"/api/tabs/{tab_id}/messages", {"text": "FAIL_TURN now"})[0], 200)
                _, msgs_fail = self.wait_for_tab_idle(tab_id, allowed_statuses=("error", "idle"), min_messages=14)
                if agent == "agy":
                    self.assertIn("Agy failing", msgs_fail[-1]["text"])
                    self.assertIn("Exited with code 1", msgs_fail[-1].get("error") or "")
                else:
                    self.assertEqual(msgs_fail[-1]["status"], "error")
                    self.assertIn("simulated", msgs_fail[-1].get("error") or "")
            finally:
                self.api("DELETE", f"/api/tabs/{tab_id}")

        # Permission denials, single-turn overridePermissionMode, and PATCH full-access for claude & agy.
        for agent in ("claude", "agy"):
            st, created = self.api(
                "POST",
                "/api/tabs",
                {"agent": agent, "title": f"Perm {agent}", "cwd": "/home/vmuser", "permissionMode": "default"},
            )
            self.assertEqual(st, 200)
            perm_tab = created["tab"]["id"]
            try:
                self.api("POST", f"/api/tabs/{perm_tab}/messages", {"text": "DENY_TOOL please"})
                perm_state, denied_msgs = self.wait_for_tab_idle(
                    perm_tab, allowed_statuses=("blocked",), min_messages=2
                )
                self.assertEqual(perm_state["status"], "blocked")
                self.assertTrue(denied_msgs[-1].get("deniedActions"))

                # Single-message overridePermissionMode succeeds without mutating tab's permissionMode.
                self.api(
                    "POST",
                    f"/api/tabs/{perm_tab}/messages",
                    {"text": "DENY_TOOL once", "overridePermissionMode": "full-access"},
                )
                after_override, override_msgs = self.wait_for_tab_idle(perm_tab, min_messages=4)
                self.assertEqual(after_override["permissionMode"], "default")
                self.assertFalse(override_msgs[-1].get("deniedActions"))
                self.assertIn("perms=1", override_msgs[-1]["text"])

                # Upgrading tab to full-access applies permanently.
                self.api("PATCH", f"/api/tabs/{perm_tab}", {"permissionMode": "full-access"})
                self.api("POST", f"/api/tabs/{perm_tab}/messages", {"text": "DENY_TOOL retry"})
                _, retry_msgs = self.wait_for_tab_idle(perm_tab, min_messages=6)
                self.assertFalse(retry_msgs[-1].get("deniedActions"))
                self.assertIn("perms=1", retry_msgs[-1]["text"])
            finally:
                self.api("DELETE", f"/api/tabs/{perm_tab}")

    def test_08_tab_crud_defaults_and_cwd_normalization(self) -> None:
        self.ensure_bootstrapped()
        st, created = self.api(
            "POST",
            "/api/tabs",
            {"agent": "shell", "cwd": "/tmp/../home/vmuser/projects", "systemPrompt": "ignored-for-shell"},
        )
        self.assertEqual(st, 200)
        tab = created["tab"]
        tab_id = tab["id"]
        try:
            self.assertEqual(tab["cwd"], "/home/vmuser/projects")
            self.assertEqual(tab["title"], "Shell · projects")
            self.assertIsNone(tab["systemPrompt"])

            # PATCH title, cwd normalization, and verify shell keeps systemPrompt=null and ignores status=done.
            p_st, patched = self.api(
                "PATCH",
                f"/api/tabs/{tab_id}",
                {
                    "title": "Normalized Shell",
                    "cwd": "/home/vmuser/projects/..",
                    "systemPrompt": "still-ignored",
                    "status": "done",
                },
            )
            self.assertEqual(p_st, 200)
            self.assertEqual(patched["tab"]["title"], "Normalized Shell")
            self.assertEqual(patched["tab"]["cwd"], "/home/vmuser")
            self.assertIsNone(patched["tab"]["systemPrompt"])
            self.assertEqual(patched["tab"]["status"], "idle")

            st_list, listed = self.api("GET", "/api/tabs")
            self.assertEqual(st_list, 200)
            self.assertEqual(listed["activeTabId"], tab_id)
        finally:
            self.api("DELETE", f"/api/tabs/{tab_id}")

    def test_09_filesystem_and_debug_dumps_endpoints(self) -> None:
        self.ensure_bootstrapped()

        # /api/fs: default HOME, dotdot normalization, hidden dirs excluded, root parent=null, nonexistent.
        self.docker_exec("mkdir -p ~/projects/zeta ~/projects/Alpha ~/projects/.hidden-dir")
        st, fs_default = self.api("GET", "/api/fs")
        self.assertEqual(st, 200)
        self.assertEqual(fs_default["path"], "/home/vmuser")
        self.assertTrue(fs_default["exists"])
        self.assertIn("projects", [e["name"] for e in fs_default["directories"]])

        st, fs_proj = self.api("GET", "/api/fs?path=/home/vmuser/projects/../projects")
        self.assertEqual(st, 200)
        self.assertEqual(fs_proj["path"], "/home/vmuser/projects")
        names = [e["name"] for e in fs_proj["directories"]]
        self.assertEqual(names, ["Alpha", "zeta"])
        self.assertNotIn(".hidden-dir", names)

        st, fs_root = self.api("GET", "/api/fs?path=/")
        self.assertEqual(st, 200)
        self.assertIsNone(fs_root["parent"])

        st, fs_missing = self.api("GET", "/api/fs?path=/nonexistent-agentsterm-dir")
        self.assertEqual(st, 200)
        self.assertFalse(fs_missing["exists"])

        # /api/debug/dumps: full payload + traversal sanitization + permissions + latest symlink.
        st, uploaded = self.api(
            "POST",
            "/api/debug/dumps",
            {
                "dumpId": "../../evil/rust-docker-dump",
                "note": "Zero-dep Rust E2E dump",
                "appVersion": "0.3.e2e",
                "deviceInfo": "DockerE2EPhone",
                "connectionSummary": "127.0.0.1:7591",
                "workspaceStateJson": json.dumps({"screen": "chat", "tabCount": 2}),
                "eventsNdjson": '{"event":"test1"}\n{"event":"test2"}\n',
                "logcat": "I/AgentsTerm: sample logcat line\n",
                "lastCrash": "java.lang.RuntimeException: sample crash\n",
                "screenshotPngBase64": TINY_PNG_B64,
            },
        )
        self.assertEqual(st, 200)
        self.assertTrue(uploaded.get("ok"))
        self.assertNotIn("/", uploaded["dumpId"])
        self.assertTrue(uploaded["remoteDir"].startswith("/home/vmuser/.config/herdr-mobile/debug-dumps/"))
        expected_files = {
            "meta.json",
            "state.json",
            "events.ndjson",
            "logcat.txt",
            "crash.txt",
            "screenshot.png",
            "summary.md",
        }
        self.assertTrue(expected_files.issubset(set(uploaded["files"])))

        # Verify files & symlink exist on disk with 0600 permissions.
        self.assertEqual(self.docker_exec("test -L ~/.config/herdr-mobile/debug-dumps/latest").returncode, 0)
        self.assertEqual(
            self.docker_exec("test -s ~/.config/herdr-mobile/debug-dumps/latest/screenshot.png").returncode, 0
        )
        meta_perm = self.docker_exec("stat -c '%a' ~/.config/herdr-mobile/debug-dumps/latest/meta.json").stdout.strip()
        self.assertEqual(meta_perm, "600")

        st, listed = self.api("GET", "/api/debug/dumps")
        self.assertEqual(st, 200)
        self.assertTrue(any("rust-docker-dump" in d["dirName"] for d in listed["dumps"]))

    def test_10_app_state_broker_and_native_app_cli_full_matrix(self) -> None:
        self.ensure_bootstrapped()

        # 1. No app connected -> ready=False, exit code 3, HTTP 503.
        st, status_empty = self.api("GET", "/api/app/status")
        self.assertEqual(st, 200)
        self.assertFalse(status_empty["ready"])
        self.assertEqual(status_empty["reason"], "no_app_connected")

        cli_help = self.docker_exec("$HOME/.local/bin/agents_term_app_cli --help")
        self.assertEqual(cli_help.returncode, 0, cli_help.stdout + cli_help.stderr)
        self.assertIn("agents_term_app_cli", cli_help.stdout)
        self.assertIn("RECOMMENDED AGENT WORKFLOW", cli_help.stdout)

        cli_no_app = self.docker_exec(f"$HOME/.local/bin/agents_term_app_cli --port {self.daemon_port} status")
        self.assertEqual(cli_no_app.returncode, 3)

        st_cmd, err_no_app = self.api("POST", "/api/app/command", {"command": "state", "args": {}})
        self.assertEqual(st_cmd, 503)
        self.assertEqual(err_no_app["code"], "no_app_connected")

        # Invalid command name -> 400 bad_request.
        st_bad, err_bad = self.api("POST", "/api/app/command", {"command": "BAD!CMD", "args": {}})
        self.assertEqual(st_bad, 400)
        self.assertEqual(err_bad["code"], "bad_request")

        # 2. Connected WS client WITHOUT app_hello -> unsupported_app.
        ws_plain = MinimalWsClient("127.0.0.1", self.daemon_port, "/ws", self.token)
        try:
            ws_plain.recv_json()
            time.sleep(0.1)
            _, st_unsup = self.api("GET", "/api/app/status")
            self.assertEqual(st_unsup["reason"], "unsupported_app")
        finally:
            ws_plain.close()
        time.sleep(0.1)

        # 3. Connected WS client with app_hello toggling inspection/control + full command suite.
        ws = MinimalWsClient("127.0.0.1", self.daemon_port, "/ws", self.token)
        stop_flag = threading.Event()
        received_commands: List[Dict[str, Any]] = []
        mode_state = {"behavior": "ok"}

        def responder() -> None:
            while not stop_flag.is_set():
                try:
                    frame = ws.recv_json(timeout=0.3)
                except socket.timeout:
                    continue
                except OSError:
                    break
                if frame.get("type") != "app_state_request":
                    continue
                received_commands.append(frame)
                cmd = frame["command"]
                rid = frame["requestId"]
                behavior = mode_state["behavior"]
                if behavior == "error":
                    ws.send_json(
                        {"type": "app_state_response", "requestId": rid, "ok": False, "error": "Simulated app failure"}
                    )
                elif behavior == "timeout":
                    continue
                elif behavior == "disconnect":
                    ws.close()
                    break
                elif cmd == "screenshot":
                    ws.send_json(
                        {
                            "type": "app_state_response",
                            "requestId": rid,
                            "ok": True,
                            "result": {"pngBase64": TINY_PNG_B64, "width": 1, "height": 1},
                        }
                    )
                elif cmd == "ui":
                    ws.send_json(
                        {
                            "type": "app_state_response",
                            "requestId": rid,
                            "ok": True,
                            "result": {
                                "width": 1080,
                                "height": 2400,
                                "windows": [{"index": 0, "type": "activity"}],
                                "nodeCount": 1,
                                "nodes": [
                                    {
                                        "id": 1,
                                        "window": 0,
                                        "tag": "send_btn",
                                        "text": "Send",
                                        "role": "Button",
                                        "clickable": True,
                                        "bounds": [10, 20, 110, 70],
                                        "center": [60, 45],
                                    }
                                ],
                            },
                        }
                    )
                elif cmd in ("logcat", "crash"):
                    ws.send_json(
                        {
                            "type": "app_state_response",
                            "requestId": rid,
                            "ok": True,
                            "result": {"text": f"RAW_{cmd.upper()}_LINE"},
                        }
                    )
                else:
                    ws.send_json(
                        {
                            "type": "app_state_response",
                            "requestId": rid,
                            "ok": True,
                            "result": {"screen": "chat", "echoCommand": cmd, "echoArgs": frame.get("args", {})},
                        }
                    )

        try:
            ws.recv_json()
            # Inspection disabled -> exit 3.
            ws.send_json(
                {
                    "type": "app_hello",
                    "reporter": {
                        "enabled": False,
                        "appVersion": "0.3.e2e",
                        "device": "SimulatedPhone",
                        "commands": ["state", "logs", "logcat", "crash", "ui", "screenshot", "dump"],
                        "controlEnabled": False,
                        "controlCommands": ["tap", "long_press", "swipe", "scroll", "type", "key"],
                    },
                }
            )
            time.sleep(0.15)
            dis_res = self.docker_exec(f"$HOME/.local/bin/agents_term_app_cli --port {self.daemon_port} status")
            self.assertEqual(dis_res.returncode, 3)
            self.assertEqual(json.loads(dis_res.stdout)["reason"], "inspection_disabled")

            # Inspection enabled, control disabled.
            ws.send_json(
                {
                    "type": "app_hello",
                    "reporter": {
                        "enabled": True,
                        "appVersion": "0.3.e2e",
                        "device": "SimulatedPhone",
                        "commands": ["state", "logs", "logcat", "crash", "ui", "screenshot", "dump", "ping"],
                        "controlEnabled": False,
                        "controlCommands": ["tap", "long_press", "swipe", "scroll", "type", "key"],
                    },
                }
            )
            threading.Thread(target=responder, daemon=True).start()
            time.sleep(0.15)

            st_res = self.docker_exec(f"$HOME/.local/bin/agents_term_app_cli status --port {self.daemon_port}")
            self.assertEqual(st_res.returncode, 0, st_res.stdout + st_res.stderr)
            st_json = json.loads(st_res.stdout)
            self.assertTrue(st_json["ready"])
            self.assertFalse(st_json["controlReady"])
            self.assertEqual(st_json["controlReason"], "control_disabled")

            # Read-only commands via `agents_term_app_cli`, legacy `app_state_cli` alias, and `agentsterm-daemon app`.
            state_res = self.docker_exec(f"$HOME/.local/bin/agents_term_app_cli --port {self.daemon_port} state")
            self.assertEqual(state_res.returncode, 0)
            self.assertEqual(json.loads(state_res.stdout)["result"]["screen"], "chat")

            ping_res = self.docker_exec(f"$HOME/.local/bin/app_state_cli --port {self.daemon_port} ping")
            self.assertEqual(ping_res.returncode, 0)
            self.assertEqual(json.loads(ping_res.stdout)["result"]["echoCommand"], "ping")

            logs_res = self.docker_exec(
                f"$HOME/.local/bin/agentsterm-daemon app logs --limit 5 --category net --grep hi --port {self.daemon_port}"
            )
            self.assertEqual(logs_res.returncode, 0)
            self.assertEqual(json.loads(logs_res.stdout)["result"]["echoArgs"]["contains"], "hi")

            logcat_res = self.docker_exec(
                f"$HOME/.local/bin/agents_term_app_cli --port {self.daemon_port} logcat --lines 20 --raw"
            )
            self.assertEqual(logcat_res.returncode, 0)
            self.assertIn("RAW_LOGCAT_LINE", logcat_res.stdout)

            crash_res = self.docker_exec(f"$HOME/.local/bin/agents_term_app_cli --port {self.daemon_port} crash --raw")
            self.assertEqual(crash_res.returncode, 0)
            self.assertIn("RAW_CRASH_LINE", crash_res.stdout)

            ui_res = self.docker_exec(f"$HOME/.local/bin/agents_term_app_cli --port {self.daemon_port} ui --brief")
            self.assertEqual(ui_res.returncode, 0)
            self.assertIn("1080x2400 screenshot pixels", ui_res.stdout)
            self.assertIn("tag=send_btn", ui_res.stdout)

            shot_res = self.docker_exec(
                f"$HOME/.local/bin/agents_term_app_cli --port {self.daemon_port} screenshot -o /tmp/e2e-shot.png"
            )
            self.assertEqual(shot_res.returncode, 0, shot_res.stdout + shot_res.stderr)
            self.assertEqual(self.docker_exec("test -s /tmp/e2e-shot.png").returncode, 0)

            dump_res = self.docker_exec(
                f"$HOME/.local/bin/agents_term_app_cli --port {self.daemon_port} dump --note e2e --no-screenshot"
            )
            self.assertEqual(dump_res.returncode, 0)
            self.assertFalse(json.loads(dump_res.stdout)["result"]["echoArgs"]["includeScreenshot"])

            raw_res = self.docker_exec(
                f"$HOME/.local/bin/agents_term_app_cli --port {self.daemon_port} raw ping --args '{{\"x\":1}}'"
            )
            self.assertEqual(raw_res.returncode, 0)
            self.assertEqual(json.loads(raw_res.stdout)["result"]["echoArgs"]["x"], 1)

            # Control command rejected with exit 3 when controlEnabled=False.
            tap_denied = self.docker_exec(
                f"$HOME/.local/bin/agents_term_app_cli --port {self.daemon_port} tap --tag send_btn"
            )
            self.assertEqual(tap_denied.returncode, 3)
            self.assertIn("control_disabled", tap_denied.stderr)

            # Enable control -> tap, long-press, swipe, scroll, type, key, wait all succeed!
            ws.send_json(
                {
                    "type": "app_hello",
                    "reporter": {
                        "enabled": True,
                        "appVersion": "0.3.e2e",
                        "device": "SimulatedPhone",
                        "commands": ["state", "logs", "logcat", "crash", "ui", "screenshot", "dump", "ping"],
                        "controlEnabled": True,
                        "controlCommands": ["tap", "long_press", "swipe", "scroll", "type", "key"],
                    },
                }
            )
            time.sleep(0.15)
            for subcmd in [
                "tap --tag send_btn",
                "tap 60 45",
                "long-press 10 20 --duration-ms 600",
                "swipe 10 20 30 40 --duration-ms 200 --hold-ms 50",
                "scroll down --amount 0.3",
                "type 'hello world' --tag composer --replace --submit",
                "key back",
                "wait --tag send_btn --wait-timeout 2",
            ]:
                c_res = self.docker_exec(f"$HOME/.local/bin/agents_term_app_cli --port {self.daemon_port} {subcmd}")
                self.assertEqual(c_res.returncode, 0, f"Command `{subcmd}` failed:\n{c_res.stdout}\n{c_res.stderr}")

            # App error response -> exit 4.
            mode_state["behavior"] = "error"
            err_res = self.docker_exec(f"$HOME/.local/bin/agents_term_app_cli --port {self.daemon_port} state")
            self.assertEqual(err_res.returncode, 4)
            self.assertIn("Simulated app failure", err_res.stderr)

            # Timeout -> exit 4 (HTTP 504).
            mode_state["behavior"] = "timeout"
            to_res = self.docker_exec(
                f"$HOME/.local/bin/agents_term_app_cli --port {self.daemon_port} --timeout 1 state"
            )
            self.assertEqual(to_res.returncode, 4)
            self.assertIn("timeout", to_res.stderr)

            # App disconnect mid-flight -> exit 3 (HTTP 503).
            mode_state["behavior"] = "disconnect"
            dc_res = self.docker_exec(f"$HOME/.local/bin/agents_term_app_cli --port {self.daemon_port} state")
            self.assertEqual(dc_res.returncode, 3)
            self.assertIn("app_disconnected", dc_res.stderr)
        finally:
            stop_flag.set()
            ws.close()

    def test_11_issues_61_62_graceful_restart_and_sigkill_crash_recovery(self) -> None:
        self.ensure_bootstrapped()

        st, created = self.api(
            "POST",
            "/api/tabs",
            {"agent": "claude", "title": "MidTurn Restart", "cwd": "/home/vmuser"},
        )
        self.assertEqual(st, 200)
        tab_id = created["tab"]["id"]
        try:
            # Part A: Graceful restart (--force-restart / SIGTERM) mid-turn.
            self.assertEqual(self.api("POST", f"/api/tabs/{tab_id}/messages", {"text": "SLOW_TURN check #62"})[0], 200)

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

            restart_res = self.docker_exec(
                f"$HOME/.local/bin/agentsterm-daemon install --host 127.0.0.1 --port {self.daemon_port} --force-restart"
            )
            self.assertEqual(restart_res.returncode, 0, restart_res.stdout + restart_res.stderr)

            st, resp = self.api("GET", f"/api/tabs/{tab_id}/messages")
            self.assertEqual(st, 200)
            self.assertEqual(resp["tab"]["sessionRef"]["value"], "claude-sess-e2e-1")
            last_msg = resp["messages"][-1]
            self.assertEqual(last_msg["status"], "interrupted")
            self.assertIn("daemon restarted", last_msg.get("error") or "")

            # Part B: Hard SIGKILL crash mid-turn + ensure-running recovery + resumed follow-up!
            self.assertEqual(self.api("POST", f"/api/tabs/{tab_id}/messages", {"text": "SLOW_TURN hard crash"})[0], 200)
            time.sleep(0.4)
            pid_now = self.docker_exec("cat ~/.config/herdr-mobile/daemon.pid").stdout.strip()
            self.docker_exec(f"kill -KILL {pid_now}")
            time.sleep(0.3)

            revive = self.docker_exec(
                f"$HOME/.local/bin/agentsterm-daemon ensure-running --host 127.0.0.1 --port {self.daemon_port}"
            )
            self.assertEqual(revive.returncode, 0, revive.stdout + revive.stderr)
            self.wait_for_daemon_healthy()

            st, after_crash = self.api("GET", f"/api/tabs/{tab_id}/messages")
            self.assertEqual(st, 200)
            self.assertEqual(after_crash["tab"]["status"], "idle")
            self.assertEqual(after_crash["tab"]["sessionRef"]["value"], "claude-sess-e2e-1")
            self.assertEqual(after_crash["messages"][-1]["status"], "interrupted")
            self.assertIn("daemon restarted", after_crash["messages"][-1].get("error") or "")

            # Follow-up message resumes the preserved sessionRef!
            self.assertEqual(self.api("POST", f"/api/tabs/{tab_id}/messages", {"text": "Continue after crash"})[0], 200)
            _, resumed_msgs = self.wait_for_tab_idle(tab_id, min_messages=6)
            self.assertIn("resumed=yes", resumed_msgs[-1]["text"])
            self.assertIn("sess=claude-sess-e2e-1", resumed_msgs[-1]["text"])

            # Verify daemon.log has ISO-8601 UTC timestamps (#61).
            daemon_log = self.docker_exec("cat ~/.config/herdr-mobile/daemon.log").stdout
            self.assertRegex(daemon_log, ISO_TS_RE, f"Expected ISO-8601 timestamps in daemon.log:\n{daemon_log}")
        finally:
            self.api("DELETE", f"/api/tabs/{tab_id}")

    def test_12_systemd_user_unit_install_path(self) -> None:
        self.ensure_bootstrapped()

        # Exercise `install`'s systemd --user branch in an isolated scratch HOME + port
        # using tiny fake `systemctl` + `loginctl` shims so we don't disturb the main daemon.
        scratch_port = 17655
        script = f"""
set -eu
SCRATCH=$(mktemp -d /tmp/agentsterm-systemd-e2e.XXXXXX)
trap 'if [ -f "$SCRATCH/svc.pid" ]; then kill "$(cat "$SCRATCH/svc.pid")" 2>/dev/null || true; fi; rm -rf "$SCRATCH"' EXIT
mkdir -p "$SCRATCH/bin" "$SCRATCH/home/.local/bin"
cp "$HOME/.local/bin/agentsterm-daemon" "$SCRATCH/home/.local/bin/agentsterm-daemon"
chmod 755 "$SCRATCH/home/.local/bin/agentsterm-daemon"

cat > "$SCRATCH/bin/loginctl" <<'SH'
#!/usr/bin/env sh
if [ "${{1:-}}" = "show-user" ]; then
  echo "yes"
  exit 0
fi
exit 0
SH
chmod 755 "$SCRATCH/bin/loginctl"

cat > "$SCRATCH/bin/systemctl" <<SH
#!/usr/bin/env sh
echo "\\$*" >> "$SCRATCH/systemctl.log"
case "\\$*" in
  *"show-environment"*) exit 0 ;;
  *"daemon-reload"*|*"enable"*) exit 0 ;;
  *"restart"*)
    if [ -f "$SCRATCH/svc.pid" ]; then kill "\\$(cat "$SCRATCH/svc.pid")" 2>/dev/null || true; fi
    HOME="$SCRATCH/home" "$SCRATCH/home/.local/bin/agentsterm-daemon" serve --host 127.0.0.1 --port {scratch_port} >> "$SCRATCH/svc.log" 2>&1 < /dev/null &
    echo \\$! > "$SCRATCH/svc.pid"
    exit 0
    ;;
esac
exit 0
SH
chmod 755 "$SCRATCH/bin/systemctl"

OUT=$(HOME="$SCRATCH/home" PATH="$SCRATCH/bin:$PATH" AGENTSTERM_NO_CRON=1 \
  "$SCRATCH/home/.local/bin/agentsterm-daemon" install --host 127.0.0.1 --port {scratch_port})
printf '%s\\n' "$OUT"
grep -q "systemd --user (herdr-mobile.service)" <<EOF
$OUT
EOF
grep -q "will survive reboots (systemd --user with linger enabled)" <<EOF
$OUT
EOF
UNIT="$SCRATCH/home/.config/systemd/user/herdr-mobile.service"
test -f "$UNIT"
grep -q "ExecStart=$SCRATCH/home/.local/bin/agentsterm-daemon serve --host 127.0.0.1 --port {scratch_port}" "$UNIT"
grep -q "Restart=always" "$UNIT"
grep -q "daemon-reload" "$SCRATCH/systemctl.log"
grep -q "enable -q herdr-mobile.service" "$SCRATCH/systemctl.log"
grep -q "restart herdr-mobile.service" "$SCRATCH/systemctl.log"
"""
        res = self.docker_exec(script, timeout=45)
        self.assertEqual(
            res.returncode, 0, f"systemd --user E2E check failed:\nSTDOUT:\n{res.stdout}\nSTDERR:\n{res.stderr}"
        )


if __name__ == "__main__":
    unittest.main()
