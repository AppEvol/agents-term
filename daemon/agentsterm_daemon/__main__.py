"""Entry point: python3 -m agentsterm_daemon [serve|pairing|rotate-token] [--host H] [--port P] [--no-auth]"""

from __future__ import annotations

import asyncio
import os
import sys
from typing import List, Optional
from urllib.parse import urlencode

from .config import detect_tailscale_ip, load_or_create_config, rotate_token, save_config


def parse_args(argv: List[str]) -> dict:
    command = "serve"
    host: Optional[str] = None
    try:
        port = int(os.environ.get("AGENT_TERMINAL_PORT") or os.environ.get("HERDR_PORT") or 7501)
    except ValueError:
        port = 7501
    no_auth = False
    i = 0
    if argv and not argv[0].startswith("-"):
        command = argv[0]
        i = 1
    while i < len(argv):
        arg = argv[i]
        if arg == "--port" and i + 1 < len(argv):
            i += 1
            port = _int_or(argv[i], -1)
        elif arg.startswith("--port="):
            port = _int_or(arg[len("--port="):], -1)
        elif arg == "--host" and i + 1 < len(argv):
            i += 1
            host = argv[i]
        elif arg.startswith("--host="):
            host = arg[len("--host="):]
        elif arg == "--no-auth":
            no_auth = True
        elif arg in ("-h", "--help"):
            command = "help"
        i += 1
    if not 1 <= port <= 65535:
        port = 7501
    return {"command": command, "host": host, "port": port, "no_auth": no_auth}


def _int_or(raw: str, default: int) -> int:
    try:
        return int(raw)
    except ValueError:
        return default


def pairing_uri(host: str, port: int, token: str) -> str:
    params = {"host": host, "port": str(port)}
    if token:
        params["token"] = token
    return f"herdr://connect?{urlencode(params)}"


USAGE = """usage: agentsterm-daemon [serve|pairing|rotate-token] [--host HOST] [--port PORT] [--no-auth]

  serve         run the daemon (default); binds the Tailscale IPv4 unless --host is given
  pairing       print the herdr://connect pairing URI for the Android app
  rotate-token  generate a new bearer token (restart the daemon to apply)
"""


def main(argv: Optional[List[str]] = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    command = args["command"]

    if command == "help":
        print(USAGE, end="")
        return 0

    if command in ("pairing", "rotate-token"):
        cfg = rotate_token() if command == "rotate-token" else load_or_create_config()
        host = args["host"] or cfg.get("host") or detect_tailscale_ip() or "127.0.0.1"
        port = args["port"] or cfg.get("port") or 7501
        uri = pairing_uri(host, port, cfg["token"])
        if command == "pairing":
            print(f"Host:  {host}")
            print(f"Port:  {port}")
            print(f"Token: {cfg['token']}")
            print("")
        else:
            print("Rotated bearer token. Restart the daemon to apply.")
        print(uri)
        return 0

    if command != "serve":
        print(f"Unknown command: {command}\n\n{USAGE}", file=sys.stderr, end="")
        return 2

    from .server import run_daemon

    cfg = load_or_create_config()
    bind_host = args["host"] or os.environ.get("AGENT_TERMINAL_HOST") or detect_tailscale_ip() or "0.0.0.0"
    if cfg.get("host") != bind_host or cfg.get("port") != args["port"]:
        save_config({**cfg, "host": bind_host, "port": args["port"]})
    try:
        asyncio.run(run_daemon(bind_host, args["port"], cfg["token"], args["no_auth"]))
    except OSError as err:
        print(f"Failed to start daemon on {bind_host}:{args['port']}: {err}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
