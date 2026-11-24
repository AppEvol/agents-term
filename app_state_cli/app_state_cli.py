#!/usr/bin/env python3
"""Inspect the live AgentsTerm Android app from the VM.

The AgentsTerm daemon relays each command to the app over the WebSocket the app keeps open,
so this works whenever the phone is connected to the daemon and the user has enabled
"Allow VM agents to inspect the app" (Settings > Debug sharing).

Connection details are read from the daemon config (~/.config/herdr-mobile/config.json) and can
be overridden with --host/--port/--token or AGENTS_TERM_HOST/AGENTS_TERM_PORT/AGENTS_TERM_TOKEN.

Output is JSON on stdout (use --pretty for indented output). Errors are JSON on stderr with a
non-zero exit code:
    1  usage / local error          3  app not ready (not connected, too old, inspection off)
    2  daemon unreachable / auth    4  app failed or timed out answering the command

Standard library only; requires Python 3.8+.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Dict, Optional

EXIT_USAGE = 1
EXIT_DAEMON = 2
EXIT_NOT_READY = 3
EXIT_APP = 4

NOT_READY_CODES = {"no_app_connected", "unsupported_app", "inspection_disabled", "app_disconnected"}

DEFAULT_CONFIG_PATH = Path.home() / ".config" / "herdr-mobile" / "config.json"


class CliError(Exception):
    def __init__(self, message: str, exit_code: int, code: Optional[str] = None):
        super().__init__(message)
        self.exit_code = exit_code
        self.code = code


class DaemonClient:
    def __init__(self, base_url: str, token: str, http_timeout: float):
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.http_timeout = http_timeout

    def _request(self, method: str, path: str, body: Optional[Dict[str, Any]] = None,
                 timeout: Optional[float] = None) -> Dict[str, Any]:
        data = json.dumps(body).encode("utf-8") if body is not None else None
        req = urllib.request.Request(self.base_url + path, data=data, method=method)
        req.add_header("Accept", "application/json")
        if data is not None:
            req.add_header("Content-Type", "application/json")
        if self.token:
            req.add_header("Authorization", "Bearer " + self.token)
        try:
            with urllib.request.urlopen(req, timeout=timeout or self.http_timeout) as resp:
                return _decode_json(resp.read())
        except urllib.error.HTTPError as err:
            with err:  # close the response; an unclosed HTTPError emits a ResourceWarning on stderr
                payload = _decode_json(err.read(), default={})
            message = payload.get("error") or f"HTTP {err.code} from daemon"
            code = payload.get("code")
            if err.code == 401:
                raise CliError(f"{message} (check the token in {DEFAULT_CONFIG_PATH})", EXIT_DAEMON, "unauthorized")
            if code in NOT_READY_CODES:
                raise CliError(message, EXIT_NOT_READY, code)
            if code:
                raise CliError(message, EXIT_APP, code)
            if err.code == 404:
                raise CliError(
                    f"{message}. The daemon is too old for app state reporting; update and restart it.",
                    EXIT_DAEMON, "daemon_unsupported",
                )
            raise CliError(message, EXIT_DAEMON, "daemon_error")
        except (urllib.error.URLError, OSError) as err:
            reason = getattr(err, "reason", err)
            raise CliError(f"Cannot reach daemon at {self.base_url}: {reason}", EXIT_DAEMON, "daemon_unreachable")

    def status(self) -> Dict[str, Any]:
        return self._request("GET", "/api/app/status")

    def command(self, command: str, args: Optional[Dict[str, Any]] = None, timeout_s: float = 15.0) -> Dict[str, Any]:
        body = {"command": command, "args": args or {}, "timeoutMs": int(timeout_s * 1000)}
        # Give HTTP a little longer than the daemon-side timeout so the daemon's error wins.
        return self._request("POST", "/api/app/command", body, timeout=timeout_s + 10)


def _decode_json(raw: bytes, default: Any = None) -> Any:
    try:
        return json.loads(raw.decode("utf-8")) if raw else (default if default is not None else {})
    except ValueError:
        if default is not None:
            return default
        raise CliError("Daemon returned non-JSON response", EXIT_DAEMON, "daemon_error")


def load_connection(args: argparse.Namespace) -> DaemonClient:
    cfg: Dict[str, Any] = {}
    config_path = Path(args.config).expanduser() if args.config else DEFAULT_CONFIG_PATH
    if config_path.exists():
        try:
            cfg = json.loads(config_path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as err:
            raise CliError(f"Cannot read daemon config {config_path}: {err}", EXIT_USAGE, "bad_config")
    host = args.host or os.environ.get("AGENTS_TERM_HOST") or cfg.get("host") or "127.0.0.1"
    if host in ("0.0.0.0", "::"):
        host = "127.0.0.1"
    port = args.port or os.environ.get("AGENTS_TERM_PORT") or cfg.get("port") or 7501
    token = args.token if args.token is not None else os.environ.get("AGENTS_TERM_TOKEN", cfg.get("token", ""))
    if ":" in str(host) and not str(host).startswith("["):
        host = f"[{host}]"
    return DaemonClient(f"http://{host}:{port}", token or "", http_timeout=10.0)


def emit(payload: Any, pretty: bool) -> None:
    sys.stdout.write(json.dumps(payload, indent=2 if pretty else None, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def run_app_command(client: DaemonClient, args: argparse.Namespace, command: str,
                    cmd_args: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    return client.command(command, cmd_args, timeout_s=args.timeout)


# --- subcommands -------------------------------------------------------------------------------

def cmd_status(client: DaemonClient, args: argparse.Namespace) -> int:
    status = client.status()
    emit(status, args.pretty)
    return 0 if status.get("ready") else EXIT_NOT_READY


def cmd_simple(name: str):
    def handler(client: DaemonClient, args: argparse.Namespace) -> int:
        emit(run_app_command(client, args, name), args.pretty)
        return 0
    return handler


def logs_args(args: argparse.Namespace, since: Optional[int]) -> Dict[str, Any]:
    out: Dict[str, Any] = {"limit": args.limit}
    if args.category:
        out["categories"] = args.category
    if since is not None:
        out["sinceTs"] = since
    if args.grep:
        out["contains"] = args.grep
    return out


def cmd_logs(client: DaemonClient, args: argparse.Namespace) -> int:
    since = args.since
    if not args.follow:
        emit(run_app_command(client, args, "logs", logs_args(args, since)), args.pretty)
        return 0
    # Follow mode: stream one JSON event per line, polling for events newer than the last seen.
    seen_ts = since
    seen_at_ts: set = set()  # identities of printed events whose ts == seen_ts (sinceTs is inclusive)
    first = True
    while True:
        try:
            resp = run_app_command(client, args, "logs", logs_args(args, seen_ts))
        except CliError as err:
            if err.exit_code in (EXIT_NOT_READY, EXIT_DAEMON, EXIT_APP) and not first:
                sys.stderr.write(json.dumps({"warning": str(err), "code": err.code}) + "\n")
                time.sleep(args.interval)
                continue
            raise
        first = False
        events = (resp.get("result") or {}).get("events") or []
        for ev in events:
            ts = ev.get("ts")
            if not isinstance(ts, (int, float)):
                emit(ev, False)
                continue
            ts = int(ts)
            identity = json.dumps(ev, sort_keys=True)
            if seen_ts is not None and (ts < seen_ts or (ts == seen_ts and identity in seen_at_ts)):
                continue
            if seen_ts is None or ts > seen_ts:
                seen_ts, seen_at_ts = ts, set()
            seen_at_ts.add(identity)
            emit(ev, False)
        time.sleep(args.interval)


def cmd_logcat(client: DaemonClient, args: argparse.Namespace) -> int:
    resp = run_app_command(client, args, "logcat", {"lines": args.lines})
    if args.raw:
        sys.stdout.write(((resp.get("result") or {}).get("text") or "") + "\n")
        return 0
    emit(resp, args.pretty)
    return 0


def cmd_crash(client: DaemonClient, args: argparse.Namespace) -> int:
    resp = run_app_command(client, args, "crash")
    if args.raw:
        sys.stdout.write(((resp.get("result") or {}).get("text") or "") + "\n")
        return 0
    emit(resp, args.pretty)
    return 0


def cmd_screenshot(client: DaemonClient, args: argparse.Namespace) -> int:
    resp = run_app_command(client, args, "screenshot")
    result = resp.get("result") or {}
    b64 = result.pop("pngBase64", None)
    if not b64:
        raise CliError("App returned no screenshot data", EXIT_APP, "app_error")
    out = Path(args.output).expanduser() if args.output else Path(
        f"/tmp/agentsterm-screenshot-{time.strftime('%Y%m%d-%H%M%S')}.png"
    )
    out.parent.mkdir(parents=True, exist_ok=True)
    data = base64.b64decode(b64)
    out.write_bytes(data)
    result["path"] = str(out.resolve())
    result["bytes"] = len(data)
    resp["result"] = result
    emit(resp, args.pretty)
    return 0


def cmd_dump(client: DaemonClient, args: argparse.Namespace) -> int:
    cmd_args: Dict[str, Any] = {"includeScreenshot": not args.no_screenshot}
    if args.note:
        cmd_args["note"] = args.note
    emit(client.command("dump", cmd_args, timeout_s=max(args.timeout, 60.0)), args.pretty)
    return 0


def cmd_raw(client: DaemonClient, args: argparse.Namespace) -> int:
    try:
        cmd_args = json.loads(args.args_json) if args.args_json else {}
    except ValueError as err:
        raise CliError(f"--args is not valid JSON: {err}", EXIT_USAGE, "bad_args")
    if not isinstance(cmd_args, dict):
        raise CliError("--args must be a JSON object", EXIT_USAGE, "bad_args")
    emit(run_app_command(client, args, args.name, cmd_args), args.pretty)
    return 0


def build_parser() -> argparse.ArgumentParser:
    def make_common(suppress: bool) -> argparse.ArgumentParser:
        # Global flags are accepted before or after the subcommand. Subparsers must not supply
        # defaults, or they would overwrite values given before the subcommand.
        def d(value):
            return argparse.SUPPRESS if suppress else value
        common = argparse.ArgumentParser(add_help=False)
        common.add_argument("--host", default=d(None), help="daemon host (default: from daemon config)")
        common.add_argument("--port", type=int, default=d(None), help="daemon port (default: from daemon config)")
        common.add_argument("--token", default=d(None), help="daemon bearer token (default: from daemon config)")
        common.add_argument("--config", default=d(None), help=f"daemon config path (default: {DEFAULT_CONFIG_PATH})")
        common.add_argument("--timeout", type=float, default=d(15.0), help="seconds to wait for the app (default: 15)")
        common.add_argument("--pretty", action="store_true", default=d(False), help="indent JSON output")
        return common

    sub_common = make_common(suppress=True)

    parser = argparse.ArgumentParser(
        prog="app_state_cli",
        description="Inspect the live AgentsTerm Android app over the daemon's WebSocket relay.",
        parents=[make_common(suppress=False)],
    )
    sub = parser.add_subparsers(dest="subcommand", metavar="COMMAND")
    sub.required = True

    def add(name: str, handler, help_text: str) -> argparse.ArgumentParser:
        p = sub.add_parser(name, help=help_text, description=help_text, parents=[sub_common])
        p.set_defaults(handler=handler)
        return p

    add("status", cmd_status, "Show whether an app is connected and ready (exit 3 if not ready).")
    add("ping", cmd_simple("ping"), "Round-trip a ping to the app.")
    add("state", cmd_simple("state"), "Dump app state: connection, screen, tabs, recent messages, settings.")

    p = add("logs", cmd_logs, "Show the app's in-memory debug event log (HTTP/WS/SSH/UI/errors).")
    p.add_argument("--limit", type=int, default=100, help="max events (default: 100, max 600)")
    p.add_argument("--category", action="append",
                   help="only these categories (repeatable): http, ws, ssh, ui, lifecycle, error, app_state")
    p.add_argument("--since", type=int, help="only events with ts >= this epoch-ms timestamp")
    p.add_argument("--grep", help="only events whose event/detail/payload contains this text (case-insensitive)")
    p.add_argument("--follow", "-f", action="store_true", help="keep polling; print one JSON event per line")
    p.add_argument("--interval", type=float, default=2.0, help="poll interval for --follow (default: 2s)")

    p = add("logcat", cmd_logcat, "Show the app process's Android logcat.")
    p.add_argument("--lines", type=int, default=400, help="lines to fetch (default: 400, max 2000)")
    p.add_argument("--raw", action="store_true", help="print plain text instead of JSON")

    p = add("crash", cmd_crash, "Show the last uncaught-exception report saved by the app (if any).")
    p.add_argument("--raw", action="store_true", help="print plain text instead of JSON")

    p = add("screenshot", cmd_screenshot, "Capture the app window to a PNG (app must be in the foreground).")
    p.add_argument("-o", "--output", help="PNG path (default: /tmp/agentsterm-screenshot-<time>.png)")

    p = add("dump", cmd_dump, "Have the app upload a full debug bundle to ~/.config/herdr-mobile/debug-dumps/.")
    p.add_argument("--note", help="note to include in the dump")
    p.add_argument("--no-screenshot", action="store_true", help="skip the screenshot")

    p = add("raw", cmd_raw, "Send an arbitrary command name with JSON args (for new/experimental commands).")
    p.add_argument("name", help="command name")
    p.add_argument("--args", dest="args_json", help="JSON object of command args")

    return parser


def main(argv: Optional[list] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        client = load_connection(args)
        return args.handler(client, args)
    except CliError as err:
        sys.stderr.write(json.dumps({"ok": False, "code": err.code, "error": str(err)}) + "\n")
        return err.exit_code
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main())
