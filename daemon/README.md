# AgentsTerm VM daemon (Python)

The VM side of AgentsTerm. It serves the REST + WebSocket API the Android app talks to, runs agent
turns (`agy`, `claude`, `codex`, persistent `bash`) and streams their output, and relays
`app_state_cli` inspection commands to the app. Python 3.9+ (CI tests 3.11 and 3.13), standard library only.

It replaces the earlier Node/TypeScript daemon (`~/agent-terminal`) and is a drop-in replacement:
same API contract, same `~/.config/herdr-mobile/` config, token and `state.json` format, and the same
`herdr-mobile.service` unit name. When first ported, a side-by-side run against the TS daemon (same
fake agents and requests) produced identical REST responses, WebSocket events and persisted state.
One deliberate change since: text blocks around tool calls are now separated by a paragraph break
(the TS daemon glued them together).

| File | Role |
| :--- | :--- |
| `install.sh` | Idempotent installer run by every SSH bootstrap: updates the checkout, restarts the daemon unless it already runs that commit, verifies the running commit (see the top-level README) |
| `agentsterm-daemon` | Launcher: `serve` (default), `pairing`, `rotate-token`, `version`; `--host`, `--port`, `--no-auth` |
| `agentsterm_daemon/server.py` | Routes, WebSocket sessions, debug dumps, directory listing |
| `agentsterm_daemon/runner.py` | Agent turns and stream-json parsing |
| `agentsterm_daemon/app_state.py` | App state broker (`/api/app/status`, `/api/app/command`) |
| `agentsterm_daemon/httpserver.py` | Minimal asyncio HTTP/1.1 + RFC 6455 WebSocket server |
| `agentsterm_daemon/store.py`, `models.py`, `config.py` | Persistence, model catalogs, config/binary discovery |

Run from a checkout: `python3 daemon/agentsterm-daemon --host 127.0.0.1 --port 7599`.
Tests: `python3 -m unittest discover -s daemon/tests`. The tests use temp `HOME`s and their own
ports, so they never touch a running daemon.
