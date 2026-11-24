#!/usr/bin/env python3
"""Inspect and drive the live AgentsTerm Android app from the VM.

The AgentsTerm daemon relays each command to the app over the WebSocket the app keeps open,
so this works whenever the phone is connected to the daemon and the user has enabled
"Allow VM agents to inspect the app" (Settings > Debug sharing). The commands that act on the app
(tap, long-press, swipe, scroll, type, key) also need "Allow VM agents to control the app".

Positions are screenshot pixels: read them off a `screenshot`, or take a node's bounds from `ui`.

Connection details are read from the daemon config (~/.config/herdr-mobile/config.json) and can
be overridden with --host/--port/--token or AGENTS_TERM_HOST/AGENTS_TERM_PORT/AGENTS_TERM_TOKEN.

Output is JSON on stdout (use --pretty for indented output). Errors are JSON on stderr with a
non-zero exit code:
    1  usage / local error          3  app not ready (not connected, too old, inspection/control off)
    2  daemon unreachable / auth    4  app failed or timed out answering the command, or `wait` gave up

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

NOT_READY_CODES = {"no_app_connected", "unsupported_app", "inspection_disabled", "control_disabled",
                   "app_disconnected"}

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


def save_screenshot(client: DaemonClient, args: argparse.Namespace, output: Optional[str]) -> Dict[str, Any]:
    """Captures the app to a PNG file; returns the response with the image data replaced by its path."""
    resp = run_app_command(client, args, "screenshot")
    result = resp.get("result") or {}
    b64 = result.pop("pngBase64", None)
    if not b64:
        raise CliError("App returned no screenshot data", EXIT_APP, "app_error")
    out = Path(output).expanduser() if output else Path(
        f"/tmp/agentsterm-screenshot-{time.strftime('%Y%m%d-%H%M%S')}.png"
    )
    out.parent.mkdir(parents=True, exist_ok=True)
    data = base64.b64decode(b64)
    out.write_bytes(data)
    result["path"] = str(out.resolve())
    result["bytes"] = len(data)
    resp["result"] = result
    return resp


def cmd_screenshot(client: DaemonClient, args: argparse.Namespace) -> int:
    emit(save_screenshot(client, args, args.output), args.pretty)
    return 0


# --- UI tree and control -----------------------------------------------------------------------

def selector_args(args: argparse.Namespace) -> Dict[str, Any]:
    out: Dict[str, Any] = {}
    if getattr(args, "tag", None):
        out["tag"] = args.tag
    if getattr(args, "text", None):
        out["text"] = args.text
    if getattr(args, "exact", False):
        out["exact"] = True
    if getattr(args, "index", None) is not None:
        out["index"] = args.index
    return out


def brief_node(node: Dict[str, Any]) -> str:
    """One line per element, e.g. `[980,2200] Button "Send" tag=send_button clickable`."""
    center = node.get("center") or [0, 0]
    parts = [f"[{center[0]},{center[1]}]"]
    if node.get("role"):
        parts.append(str(node["role"]))
    for key in ("text", "value", "description"):
        if node.get(key) is not None:
            label = json.dumps(node[key], ensure_ascii=False)
            parts.append(label if key == "text" else f"{key}={label}")
    if node.get("tag"):
        parts.append(f"tag={node['tag']}")
    if node.get("toggle"):
        parts.append(f"toggle={node['toggle']}")
    if node.get("selected") is not None:
        parts.append(f"selected={str(node['selected']).lower()}")
    parts.extend(flag for flag in ("clickable", "longClickable", "scrollable", "editable", "focused", "password")
                 if node.get(flag))
    if node.get("enabled") is False:
        parts.append("disabled")
    if node.get("window"):
        parts.append(f"window={node['window']}")
    return " ".join(parts)


def cmd_ui(client: DaemonClient, args: argparse.Namespace) -> int:
    cmd_args = selector_args(args)
    if args.interactive:
        cmd_args["interactiveOnly"] = True
    resp = run_app_command(client, args, "ui", cmd_args)
    if not args.brief:
        emit(resp, args.pretty)
        return 0
    result = resp.get("result") or {}
    windows = ", ".join(f"{w.get('index')}:{w.get('type')}" for w in result.get("windows") or [])
    lines = [f"# {result.get('width')}x{result.get('height')} screenshot pixels; windows {windows}; "
             f"[x,y] is each element's center"]
    lines.extend(brief_node(n) for n in result.get("nodes") or [])
    sys.stdout.write("\n".join(lines) + "\n")
    return 0


def act(client: DaemonClient, args: argparse.Namespace, command: str, cmd_args: Dict[str, Any],
        timeout_s: Optional[float] = None) -> int:
    """Runs a control command, then optionally captures what the app looks like afterwards."""
    resp = client.command(command, cmd_args, timeout_s=timeout_s or args.timeout)
    if args.screenshot:
        time.sleep(max(args.settle, 0.0))
        resp["screenshot"] = (save_screenshot(client, args, args.screenshot).get("result") or {}).get("path")
    emit(resp, args.pretty)
    return 0


def press_args(args: argparse.Namespace, name: str) -> Dict[str, Any]:
    cmd_args = selector_args(args)
    if (args.x is None) != (args.y is None):
        raise CliError(f"{name} needs both X and Y", EXIT_USAGE, "bad_args")
    if args.x is not None:
        if cmd_args:
            raise CliError(f"{name} takes either X Y or --tag/--text, not both", EXIT_USAGE, "bad_args")
        cmd_args.update({"x": args.x, "y": args.y})
    if not cmd_args:
        raise CliError(f"{name} needs a target: X Y (screenshot pixels), --tag or --text", EXIT_USAGE, "bad_args")
    return cmd_args


def cmd_tap(client: DaemonClient, args: argparse.Namespace) -> int:
    return act(client, args, "tap", press_args(args, "tap"))


def cmd_long_press(client: DaemonClient, args: argparse.Namespace) -> int:
    cmd_args = press_args(args, "long-press")
    if args.duration_ms is not None:
        cmd_args["durationMs"] = args.duration_ms
    return act(client, args, "long_press", cmd_args)


def cmd_swipe(client: DaemonClient, args: argparse.Namespace) -> int:
    cmd_args = {"x1": args.x1, "y1": args.y1, "x2": args.x2, "y2": args.y2,
                "durationMs": args.duration_ms, "holdMs": args.hold_ms}
    # The app answers after the stroke, so wait at least that long.
    stroke_s = (args.duration_ms + args.hold_ms) / 1000.0
    return act(client, args, "swipe", cmd_args, timeout_s=max(args.timeout, stroke_s + 10))


def cmd_scroll(client: DaemonClient, args: argparse.Namespace) -> int:
    cmd_args = selector_args(args)
    cmd_args["direction"] = args.direction
    if args.amount is not None:
        cmd_args["amount"] = args.amount
    return act(client, args, "scroll", cmd_args)


def cmd_type(client: DaemonClient, args: argparse.Namespace) -> int:
    cmd_args: Dict[str, Any] = {"text": args.value}
    if args.tag:
        cmd_args["tag"] = args.tag
    if args.field:
        cmd_args["field"] = args.field
    if args.exact:
        cmd_args["exact"] = True
    if args.index is not None:
        cmd_args["index"] = args.index
    if args.replace:
        cmd_args["replace"] = True
    if args.submit:
        cmd_args["submit"] = True
    return act(client, args, "type", cmd_args)


def cmd_key(client: DaemonClient, args: argparse.Namespace) -> int:
    return act(client, args, "key", {"key": args.key})


def cmd_wait(client: DaemonClient, args: argparse.Namespace) -> int:
    """Polls the UI tree until an element shows up (or, with --gone, goes away)."""
    cmd_args = selector_args(args)
    cmd_args.pop("index", None)
    if not cmd_args:
        raise CliError("wait needs --tag or --text", EXIT_USAGE, "bad_args")
    started = time.monotonic()
    while True:
        resp = run_app_command(client, args, "ui", cmd_args)
        nodes = (resp.get("result") or {}).get("nodes") or []
        waited_ms = int((time.monotonic() - started) * 1000)
        if bool(nodes) != args.gone:
            emit({"ok": True, "waitedMs": waited_ms, "gone": args.gone, "nodes": nodes}, args.pretty)
            return 0
        if time.monotonic() - started >= args.wait_timeout:
            what = "still on screen" if args.gone else "not on screen"
            raise CliError(f"Element is {what} after {args.wait_timeout:g}s", EXIT_APP, "wait_timeout")
        time.sleep(max(args.interval, 0.05))


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
        description="Inspect and drive the live AgentsTerm Android app over the daemon's WebSocket relay.",
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

    p = add("screenshot", cmd_screenshot, "Capture the app, including open dialogs and menus, to a PNG (app must be in the foreground).")
    p.add_argument("-o", "--output", help="PNG path (default: /tmp/agentsterm-screenshot-<time>.png)")

    def add_selector(p: argparse.ArgumentParser, index: bool = True) -> None:
        p.add_argument("--tag", help="element with this exact test tag")
        p.add_argument("--text", help="element whose text, content or description contains this (case-insensitive)")
        p.add_argument("--exact", action="store_true", help="--text must match exactly")
        if index:
            p.add_argument("--index", type=int, help="which match to use when several match (default: 0, the first)")

    def add_action(name: str, handler, help_text: str) -> argparse.ArgumentParser:
        p = add(name, handler, help_text + " Needs 'Allow VM agents to control the app'.")
        p.add_argument("--screenshot", "-s", metavar="PNG", help="also capture the app to this PNG after the action")
        p.add_argument("--settle", type=float, default=0.6,
                       help="seconds to let the UI settle before --screenshot (default: 0.6)")
        return p

    p = add("ui", cmd_ui, "List what is on screen: text, test tags, bounds and what can be tapped, scrolled or typed in.")
    add_selector(p, index=False)
    p.add_argument("--interactive", action="store_true", help="only elements that can be tapped, scrolled or typed in")
    p.add_argument("--brief", action="store_true", help="one line per element instead of JSON")

    p = add("wait", cmd_wait, "Wait until an element is on screen (exit 4 if it never shows up).")
    add_selector(p, index=False)
    p.add_argument("--gone", action="store_true", help="wait until no element matches instead")
    p.add_argument("--wait-timeout", type=float, default=10.0, help="seconds to keep trying (default: 10)")
    p.add_argument("--interval", type=float, default=0.4, help="seconds between checks (default: 0.4)")

    for name, handler, help_text in (
        ("tap", cmd_tap, "Tap a position or an element."),
        ("long-press", cmd_long_press, "Press and hold a position or an element."),
    ):
        p = add_action(name, handler, help_text)
        p.add_argument("x", type=int, nargs="?", help="X in screenshot pixels")
        p.add_argument("y", type=int, nargs="?", help="Y in screenshot pixels")
        add_selector(p)
        if name == "long-press":
            p.add_argument("--duration-ms", type=int, help="how long to hold (default: 800)")

    p = add_action("swipe", cmd_swipe, "Drag a finger from X1 Y1 to X2 Y2 (screenshot pixels).")
    for coord in ("x1", "y1", "x2", "y2"):
        p.add_argument(coord, type=int)
    p.add_argument("--duration-ms", type=int, default=300, help="travel time; shorter flings further (default: 300)")
    p.add_argument("--hold-ms", type=int, default=0, help="rest at the end before lifting, which prevents a fling")

    p = add_action("scroll", cmd_scroll, "Scroll a list by dragging it, without a fling.")
    p.add_argument("direction", nargs="?", default="down", choices=["up", "down", "left", "right"],
                   help="which content to reveal (default: down)")
    p.add_argument("--amount", type=float, help="fraction of the list's size to scroll (default: 0.6)")
    add_selector(p)

    p = add_action("type", cmd_type, "Enter text in the focused text field, or in the one named by --tag/--field.")
    p.add_argument("value", metavar="TEXT", help="text to enter")
    p.add_argument("--tag", help="text field with this exact test tag")
    p.add_argument("--field", help="text field whose label or content contains this")
    p.add_argument("--exact", action="store_true", help="--field must match exactly")
    p.add_argument("--index", type=int, help="which match to use when several match")
    p.add_argument("--replace", action="store_true", help="replace the field's content instead of inserting at the cursor")
    p.add_argument("--submit", action="store_true", help="then run the field's keyboard action (Send, Done, Search...)")

    p = add_action("key", cmd_key, "Press Back, or Enter (the focused text field's keyboard action).")
    p.add_argument("key", choices=["back", "enter"])

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
