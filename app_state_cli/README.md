# app_state_cli

Inspect the **live AgentsTerm Android app** from the VM: debug event log, app state, logcat, last
crash, screenshots, and full debug dumps. Built for coding agents debugging the app while a
human uses it on the phone.

```
agent ─▶ app_state_cli ─HTTP─▶ daemon /api/app/* ─WS app_state_request─▶ app
                                                  ◀─WS app_state_response─
```

The daemon passes each command to the app over the WebSocket the app already holds, so nothing
listens on the phone. Python 3.8+, standard library only.

## Requirements

1. The daemon includes the app state broker (`/api/app/status`, `/api/app/command`).
   `status` reports `daemon_unsupported` if it does not.
2. The phone runs an AgentsTerm build with the reporter, is connected, and the user enabled
   **Settings > Debug sharing > Allow VM agents to inspect the app** (it requires
   "Enable debug sharing"). Logcat and screenshots also follow the existing "Include logcat" /
   "Include screenshot" toggles.
3. Screenshots need the app in the foreground (the user gets a toast for each one). Other commands
   work while the app is connected, but Android may freeze a backgrounded app, which shows up
   as a `timeout`.

## Usage

Connection details come from `~/.config/herdr-mobile/config.json` (written by the daemon).
Override them with `--host/--port/--token` or `AGENTS_TERM_HOST/PORT/TOKEN`.

```bash
CLI=app_state_cli/app_state_cli.py
$CLI status                       # is an app connected & ready? (exit 3 if not)
$CLI state --pretty               # screen, connection, tabs, recent messages, settings
$CLI logs --limit 50              # in-app debug events (http/ws/ssh/ui/lifecycle/error/app_state)
$CLI logs --category error --category ws --since 1795536750000
$CLI logs --grep timeout          # substring match on event/detail/payload
$CLI logs -f                      # follow: one JSON event per line
$CLI logcat --lines 800 --raw     # Android logcat for the app process
$CLI crash --raw                  # last uncaught exception stack trace, if any
$CLI screenshot -o /tmp/app.png   # PNG of the app window
$CLI dump --note "repro of #42"   # full bundle -> ~/.config/herdr-mobile/debug-dumps/latest/
$CLI raw ping --args '{}'         # any command by name (for new commands)
```

Output is JSON on stdout. Failures print `{"ok": false, "code": ..., "error": ...}` on stderr:

| Exit | Meaning | `code` values |
| :--- | :--- | :--- |
| 0 | success | |
| 1 | usage / local error | `bad_config`, `bad_args` |
| 2 | daemon unreachable, bad token, or daemon too old | `daemon_unreachable`, `unauthorized`, `daemon_unsupported` |
| 3 | app not ready | `no_app_connected`, `unsupported_app`, `inspection_disabled`, `app_disconnected` |
| 4 | app failed or timed out | `app_error`, `timeout` |

## Protocol

| Direction | Frame |
| :--- | :--- |
| app → daemon (on connect, on toggle) | `{"type":"app_hello","reporter":{"enabled":true,"appVersion":"…","device":"…","commands":[…]}}` |
| daemon → app | `{"type":"app_state_request","requestId":"…","command":"logs","args":{…}}` |
| app → daemon | `{"type":"app_state_response","requestId":"…","ok":true,"result":{…}}` or `"ok":false,"error":"…"` |

REST: `GET /api/app/status`, and `POST /api/app/command` with body
`{"command":"logs","args":{…},"timeoutMs":15000}` (bearer auth, like every daemon endpoint).
Requests go to the most recently connected app that has inspection enabled.

To add a command, handle it in `AppStateReporter.dispatch` (Android:
`app/src/main/java/com/example/data/AppStateReporter.kt`), add it to `COMMANDS`, and optionally
add a subcommand here. `raw` works without any CLI change.

## Tests

```bash
python3 -m unittest discover -s app_state_cli
```
