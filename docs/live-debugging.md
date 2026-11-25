# Live Debugging AgentsTerm (Agent Guide)

AgentsTerm includes a live inspection and UI-control bridge (`app_state_cli` / `agentsterm-daemon app`) and debug dump uploader so coding agents running on the VM can inspect or drive the Android app while a human uses it on their phone.

```text
agent ─▶ app_state_cli ─HTTP─▶ daemon (/api/app/*) ─WS─▶ Android app
```

## Prerequisites

1. The Android phone is connected to the AgentsTerm VM daemon.
2. The user enabled **Settings > Debug sharing > Share debug logs to VM** and **Allow VM agents to inspect the app**.
   - Logcat and screenshots also respect the **Include Android logcat** and **Include screenshot** toggles.
   - UI control commands (`tap`, `long-press`, `swipe`, `scroll`, `type`, `key`) also require **Allow VM agents to control the app**.
3. Always run `status` first. Exit code `3` means the app is disconnected or inspection/control is turned off — ask the user to connect/enable it rather than retrying in a loop.

## Live Inspection & Control Commands

The static `agentsterm-daemon` binary provides the `app` subcommand and is symlinked as `~/.local/bin/app_state_cli`. Connection details are loaded automatically from `~/.config/herdr-mobile/config.json` (override with `--host/--port/--token` or `AGENTS_TERM_HOST/PORT/TOKEN`).

```bash
app_state_cli status                       # Check connection & opt-in (exit 3 if not ready)
app_state_cli state --pretty               # Current screen, connection, tabs, messages, settings
app_state_cli logs --limit 50              # In-app debug events (http/ws/ssh/ui/lifecycle/error)
app_state_cli logs --category error --category ws
app_state_cli logs --grep timeout          # Substring match across event/detail/payload
app_state_cli logs -f                      # Follow live events (one JSON object per line)
app_state_cli logcat --lines 800 --raw     # Android logcat for the app process
app_state_cli crash --raw                  # Last uncaught exception stack trace, if any
app_state_cli screenshot -o /tmp/app.png   # Capture PNG of the current app window
app_state_cli dump --note "repro context"  # Upload full bundle to ~/.config/herdr-mobile/debug-dumps/latest/
app_state_cli ui --brief                   # List visible Compose UI nodes and bounds
app_state_cli tap --tag send_button        # Tap a UI element (requires control opt-in)
```

*(You can also invoke `agentsterm-daemon app <subcommand>` directly.)*

### Exit Codes

| Exit | Meaning |
| :--- | :--- |
| `0` | Success (JSON on stdout, unless `--raw` / `--brief`) |
| `1` | Local usage or config error |
| `2` | Daemon unreachable, unauthorized, or too old |
| `3` | App not connected or remote inspection/control disabled |
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
- **Bearer tokens** are redacted before leaving the phone.
- **Never restart the running daemon from an AgentsTerm tab:** Agent sessions are child processes of the VM daemon (`herdr-mobile.service`). Running `agentsterm-daemon install` or restarting `herdr-mobile.service` from inside an agent tab kills your own session. Test daemon changes on a scratch port with a temporary `HOME` instead (`HOME=/tmp/test-home agentsterm-daemon serve --host 127.0.0.1 --port 7599`).
