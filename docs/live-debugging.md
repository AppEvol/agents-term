# Live Debugging AgentsTerm (Agent Guide)

AgentsTerm includes a read-only live inspection bridge (`app_state_cli`) and debug dump uploader so coding agents running on the VM can inspect the Android app while a human uses it on their phone.

```text
agent ─▶ app_state_cli ─HTTP─▶ daemon (/api/app/*) ─WS─▶ Android app
```

## Prerequisites

1. The Android phone is connected to the AgentsTerm VM daemon.
2. The user enabled **Settings > Debug sharing > Share debug logs to VM** and **Allow VM agents to inspect the app**.
   - Logcat and screenshots also respect the **Include Android logcat** and **Include screenshot** toggles.
3. Always run `status` first. Exit code `3` means the app is disconnected or inspection is turned off — ask the user to connect/enable it rather than retrying in a loop.

## Live Inspection Commands (`app_state_cli`)

Connection details are loaded automatically from `~/.config/herdr-mobile/config.json` (override with `--host/--port/--token` or `AGENTS_TERM_HOST/PORT/TOKEN`).

```bash
# From an AgentsTerm repo checkout:
CLI=app_state_cli/app_state_cli.py
# Or from any working directory on a bootstrapped VM:
CLI="$HOME/.local/share/agentsterm/agents-term/app_state_cli/app_state_cli.py"

python3 "$CLI" status                       # Check connection & opt-in (exit 3 if not ready)
python3 "$CLI" state --pretty               # Current screen, connection, tabs, messages, settings
python3 "$CLI" logs --limit 50              # In-app debug events (http/ws/ssh/ui/lifecycle/error)
python3 "$CLI" logs --category error --category ws
python3 "$CLI" logs --grep timeout          # Substring match across event/detail/payload
python3 "$CLI" logs -f                      # Follow live events (one JSON object per line)
python3 "$CLI" logcat --lines 800 --raw     # Android logcat for the app process
python3 "$CLI" crash --raw                  # Last uncaught exception stack trace, if any
python3 "$CLI" screenshot -o /tmp/app.png   # Capture PNG of the current app window
python3 "$CLI" dump --note "repro context"  # Upload full bundle to ~/.config/herdr-mobile/debug-dumps/latest/
```

### Exit Codes

| Exit | Meaning |
| :--- | :--- |
| `0` | Success (JSON on stdout, unless `--raw`) |
| `1` | Local usage or config error |
| `2` | Daemon unreachable, unauthorized, or too old |
| `3` | App not connected or remote inspection disabled |
| `4` | App returned an error or timed out (e.g. backgrounded) |

## Uploaded Debug Dumps

When the user taps **Upload Debug Dump to VM** (or the top-bar bug icon, or `app_state_cli dump`), the daemon writes a sanitized bundle to `~/.config/herdr-mobile/debug-dumps/` and updates the symlink:

```text
~/.config/herdr-mobile/debug-dumps/latest/
├── summary.md      # Overview + user note
├── meta.json       # App version, device, timestamps
├── state.json      # Sanitized workspace/tab snapshot
├── events.ndjson   # In-app event buffer
├── logcat.txt      # Optional Android logcat
├── crash.txt       # Optional last uncaught crash trace
└── screenshot.png  # Optional window screenshot
```

## Important Rules

- **Screenshots** require the app to be in the foreground and show a brief toast on the phone (`VM agent captured a screenshot`). Other commands may time out if Android freezes the backgrounded app.
- **Read-only:** `app_state_cli` never mutates app state; bearer tokens are redacted before leaving the phone.
- **Never restart the running daemon from an AgentsTerm tab:** Agent sessions are child processes of the VM daemon (`herdr-mobile.service`). Running `daemon/install.sh` or restarting `herdr-mobile.service` from inside an agent tab kills your own session. Test daemon changes on a scratch port with a temporary `HOME` instead (`python3 daemon/agentsterm-daemon --host 127.0.0.1 --port 7599`).

For full WebSocket/REST protocol details and how to add new inspection commands, see [`app_state_cli/README.md`](../app_state_cli/README.md).
