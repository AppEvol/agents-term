"""Daemon config (~/.config/herdr-mobile/config.json), binary discovery and server info.

Paths are computed from $HOME at call time so tests can point HOME at a temp dir.
"""

from __future__ import annotations

import json
import os
import re
import secrets
import socket
import subprocess
import time
from pathlib import Path
from typing import Any, Dict, List, Optional

SERVER_VERSION = "0.2.0"

_TAILSCALE_CACHE_TTL_S = 30.0
_tailscale_cache: Dict[str, Any] = {"at": 0.0, "ip": None}


_commit_cache: Dict[str, Optional[str]] = {}


def repo_root() -> Path:
    return Path(__file__).resolve().parent.parent.parent


def live_debugging_doc_path() -> Path:
    return repo_root() / "docs" / "live-debugging.md"


FULL_ACCESS_COMPLETION_INSTRUCTION = (
    "When the user asks you to accomplish a task in this tab, once the task is complete "
    "clearly let them know it is finished and that they can close this tab."
)


def default_system_prompt(permission_mode: Optional[str] = None) -> str:
    """Default system prompt offered for new coding-agent tabs."""
    doc_path = live_debugging_doc_path()
    base = (
        "You are responding inside AgentsTerm, a mobile app terminal/chat view on a phone or tablet. "
        "Keep formatting mobile-friendly (concise paragraphs, short lists, avoid overly wide tables or ASCII art).\n\n"
        "Live app debugging: when troubleshooting AgentsTerm itself, you can inspect the live phone app "
        "(state, logs, logcat, crash traces, screenshots) via `app_state_cli` or check uploaded dumps in "
        f"`~/.config/herdr-mobile/debug-dumps/latest/`. For commands, prerequisites, and rules, read `{doc_path}`."
    )
    if permission_mode == "full-access":
        return f"{base}\n\n{FULL_ACCESS_COMPLETION_INSTRUCTION}"
    return base


def daemon_commit() -> Optional[str]:
    """Git commit of the checkout this daemon process was started from (resolved once).

    The process keeps running the code it loaded at startup, so this identifies the running daemon
    even after the checkout is updated underneath it. install.sh compares it with the checkout's
    HEAD to decide whether a restart is needed, and to verify the restart took effect.
    """
    if "value" not in _commit_cache:
        _commit_cache["value"] = _read_git_commit(repo_root())
    return _commit_cache["value"]


def _read_git_commit(repo: Path) -> Optional[str]:
    override = os.environ.get("AGENTSTERM_DAEMON_COMMIT")
    if override:
        return override.strip() or None
    try:
        out = subprocess.run(
            ["git", "-C", str(repo), "rev-parse", "HEAD"], capture_output=True, text=True, timeout=5, check=False
        )
        commit = out.stdout.strip()
        return commit if out.returncode == 0 and re.fullmatch(r"[0-9a-f]{40,64}", commit) else None
    except (OSError, subprocess.SubprocessError):
        return None


def home_dir() -> Path:
    return Path(os.environ.get("HOME") or str(Path.home()))


def config_dir() -> Path:
    return home_dir() / ".config" / "herdr-mobile"


def config_path() -> Path:
    return config_dir() / "config.json"


def state_path() -> Path:
    return config_dir() / "state.json"


def ensure_config_dir() -> None:
    config_dir().mkdir(parents=True, exist_ok=True, mode=0o700)


def generate_token() -> str:
    # Same shape as crypto.randomBytes(24).toString("base64url"): 32 URL-safe chars.
    return secrets.token_urlsafe(24)


def load_or_create_config() -> Dict[str, Any]:
    ensure_config_dir()
    path = config_path()
    if path.exists():
        try:
            parsed = json.loads(path.read_text(encoding="utf-8"))
            token = parsed.get("token") if isinstance(parsed, dict) else None
            if isinstance(token, str) and token.strip():
                cfg: Dict[str, Any] = {"token": token.strip()}
                if parsed.get("host") is not None:
                    cfg["host"] = parsed.get("host")
                if parsed.get("port") is not None:
                    cfg["port"] = parsed.get("port")
                return cfg
        except (OSError, ValueError):
            pass  # Fall through and recreate
    cfg = {"token": generate_token()}
    save_config(cfg)
    return cfg


def save_config(cfg: Dict[str, Any]) -> None:
    ensure_config_dir()
    path = config_path()
    tmp = path.with_name(path.name + ".tmp")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        f.write(json.dumps(cfg, indent=2) + "\n")
    os.replace(tmp, path)


def rotate_token() -> Dict[str, Any]:
    cfg = dict(load_or_create_config())
    cfg["token"] = generate_token()
    save_config(cfg)
    return cfg


_IPV4_RE = re.compile(r"^\d+\.\d+\.\d+\.\d+$")


def _is_cgnat(ip: str) -> bool:
    parts = [int(p) for p in ip.split(".")]
    return parts[0] == 100 and 64 <= parts[1] <= 127


def detect_tailscale_ip(use_cache: bool = True) -> Optional[str]:
    now = time.monotonic()
    if use_cache and now - _tailscale_cache["at"] < _TAILSCALE_CACHE_TTL_S:
        return _tailscale_cache["ip"]
    ip = _detect_tailscale_ip_uncached()
    _tailscale_cache.update(at=now, ip=ip)
    return ip


def _detect_tailscale_ip_uncached() -> Optional[str]:
    try:
        out = subprocess.run(
            ["tailscale", "ip", "-4"], capture_output=True, text=True, timeout=3, check=False
        ).stdout
        for line in out.splitlines():
            line = line.strip()
            if _IPV4_RE.match(line):
                return line
    except (OSError, subprocess.SubprocessError):
        pass
    # Fallback: scan interfaces for a tailscale* name or the CGNAT 100.64.0.0/10 range.
    try:
        out = subprocess.run(
            ["ip", "-4", "-o", "addr", "show"], capture_output=True, text=True, timeout=3, check=False
        ).stdout
        for line in out.splitlines():
            m = re.match(r"^\d+:\s+(\S+)\s+inet\s+(\d+\.\d+\.\d+\.\d+)/", line)
            if not m:
                continue
            name, addr = m.group(1), m.group(2)
            if addr.startswith("127."):
                continue
            if "tailscale" in name or _is_cgnat(addr):
                return addr
    except (OSError, subprocess.SubprocessError, ValueError):
        pass
    return None


def enriched_path() -> str:
    home = home_dir()
    extra = [
        str(home / ".local" / "bin"),
        str(home / ".npm-global" / "bin"),
        str(home / ".cargo" / "bin"),
        "/usr/local/bin",
        "/usr/bin",
        "/bin",
    ]
    existing = [p for p in (os.environ.get("PATH") or "").split(os.pathsep) if p]
    seen = set()
    combined: List[str] = []
    for d in extra + existing:
        if d not in seen:
            seen.add(d)
            combined.append(d)
    return os.pathsep.join(combined)


def resolve_binary(binary: str) -> Optional[str]:
    for d in enriched_path().split(os.pathsep):
        candidate = os.path.join(d, binary)
        if os.path.isfile(candidate) and os.access(candidate, os.X_OK):
            return candidate
    return None


AGENT_SPECS = [
    {"type": "agy", "name": "Antigravity", "description": "Antigravity CLI", "binary": "agy"},
    {"type": "claude", "name": "Claude Code", "description": "Claude CLI", "binary": "claude"},
    {"type": "codex", "name": "Codex", "description": "Codex CLI", "binary": "codex"},
    {"type": "shell", "name": "Shell", "description": "Persistent bash", "binary": "bash"},
]


def detect_available_agents() -> List[Dict[str, Any]]:
    from .models import get_cached_agent_models

    agents = []
    for spec in AGENT_SPECS:
        binary_path = resolve_binary(spec["binary"])
        available = binary_path is not None
        discovered = (
            get_cached_agent_models(spec["type"]) if available else {"defaultModel": None, "models": []}
        )
        agents.append(
            {
                **spec,
                "available": available,
                "binaryPath": binary_path,
                "defaultModel": discovered["defaultModel"],
                "models": discovered["models"],
            }
        )
    return agents


def build_server_info(bind_host: Optional[str] = None) -> Dict[str, Any]:
    tailscale_ip = detect_tailscale_ip() or (bind_host if bind_host and bind_host != "0.0.0.0" else None)
    return {
        "version": SERVER_VERSION,
        "commit": daemon_commit(),
        "hostname": socket.gethostname(),
        "tailscaleIp": tailscale_ip,
        "defaultCwd": str(home_dir()),
        "defaultSystemPrompt": default_system_prompt("default"),
        "defaultSystemPromptFullAccess": default_system_prompt("full-access"),
        "agents": detect_available_agents(),
    }
