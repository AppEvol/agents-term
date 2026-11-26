# Live Debugging AgentsTerm (Agent Guide)

AgentsTerm includes a live inspection and UI-control CLI (`agents_term_app_cli`, also available as `agentsterm-daemon app`) and debug dump uploader so coding agents running on the VM can inspect or drive the Android app while a human uses it on their phone.

```text
agent ─▶ agents_term_app_cli ─HTTP─▶ daemon (/api/app/*) ─WS─▶ Android app
```

The daemon passes each command to the app over the WebSocket the app already holds, so nothing listens on the phone. Run `agents_term_app_cli --help` (or `agents_term_app_cli help <command>`) anytime for full usage, options, and workflow guidance.

## Prerequisites

1. The Android phone is connected to the AgentsTerm VM daemon.
2. The user enabled **Settings > Debug sharing > Share debug logs to VM** and **Allow VM agents to inspect the app**.
   - Logcat and screenshots also respect the **Include Android logcat** and **Include screenshot** toggles.
   - UI control commands (`tap`, `long-press`, `swipe`, `scroll`, `type`, `key`) also require **Allow VM agents to control the app**. Without it they exit `3` with `control_disabled`.
3. Always run `agents_term_app_cli status` first. Exit code `3` means the app is disconnected or inspection/control is turned off — ask the user to connect/enable it rather than retrying in a loop.

## Live Inspection Commands

The static `agentsterm-daemon` binary provides the `app` subcommand and installs the `~/.local/bin/agents_term_app_cli` symlink (plus the legacy `app_state_cli` alias). Connection details are loaded automatically from `~/.config/herdr-mobile/config.json` (override with `--host/--port/--token/--config` or `AGENTS_TERM_HOST/PORT/TOKEN`).

```bash
agents_term_app_cli status                       # Check connection & opt-in (exit 3 if not ready)
agents_term_app_cli ping                         # Round-trip ping to the app
agents_term_app_cli state --pretty               # Current screen, connection, tabs, messages, settings
agents_term_app_cli logs --limit 50              # In-app debug events (http/ws/ssh/ui/lifecycle/error/app_state)
agents_term_app_cli logs --category error --category ws --since 1795536750000
agents_term_app_cli logs --grep timeout          # Substring match across event/detail/payload
agents_term_app_cli logs -f                      # Follow live events (one JSON object per line)
agents_term_app_cli logcat --lines 800 --raw     # Android logcat for the app process
agents_term_app_cli crash --raw                  # Last uncaught exception stack trace, if any
agents_term_app_cli screenshot -o /tmp/app.png   # Capture PNG of the current app window
agents_term_app_cli ui --brief                   # List visible Compose UI nodes and centers
agents_term_app_cli dump --note "repro context"  # Upload full bundle to ~/.config/herdr-mobile/debug-dumps/latest/
agents_term_app_cli raw ping --args '{}'         # Send any command by name with a JSON args object
```

*(You can also invoke `agentsterm-daemon app <subcommand>` directly.)*

### Exit Codes

Output is JSON on stdout (unless `--raw` or `--brief`). Failures print `{"ok":false,"code":"...","error":"..."}` on stderr:

| Exit | Meaning | `code` values |
| :--- | :--- | :--- |
| `0` | Success | |
| `1` | Local usage or config error | `bad_config`, `bad_args`, `io_error` |
| `2` | Daemon unreachable, bad token, or daemon too old | `daemon_unreachable`, `unauthorized`, `daemon_unsupported`, `daemon_error` |
| `3` | App not connected or remote inspection/control disabled | `no_app_connected`, `unsupported_app`, `inspection_disabled`, `control_disabled`, `app_disconnected` |
| `4` | App returned an error, timed out, or `wait` gave up | `app_error`, `timeout`, `wait_timeout` |

## Driving the App

Look, act, look again. All coordinates are **screenshot pixels**: the `screenshot` PNG, the bounds/centers that `ui` reports, and the coordinates passed to `tap`, `long-press`, and `swipe` share one space.

```bash
agents_term_app_cli ui --brief                          # one line per element; [x,y] is its center
# 1080x2400 screenshot pixels; windows 0:activity; [x,y] is each element's center
[1010,150] Button description="More options" tag=overflow_menu_button clickable
[440,2200] "Message" tag=composer_input editable
[980,2200] Button description="Send" tag=send_button clickable disabled

agents_term_app_cli tap --tag overflow_menu_button      # by test tag (most stable)
agents_term_app_cli tap --text "Settings"               # by visible text, value, or description (substring)
agents_term_app_cli tap 980 2200                        # by screenshot-pixel position (or --xy 980 2200)
agents_term_app_cli long-press --text "Summarize" --duration-ms 1000
agents_term_app_cli type "Summarize the repo" --tag composer_input --replace --submit
agents_term_app_cli type "22" --field "Port"            # pick text field by label/content
agents_term_app_cli scroll down                         # reveal content further down (no fling)
agents_term_app_cli scroll up --amount 0.3 --tag messages
agents_term_app_cli swipe 100 1200 900 1200 --hold-ms 150
agents_term_app_cli key back                            # closes keyboard/menu/dialog, else goes back
agents_term_app_cli key enter                           # focused field's keyboard action (Send, Done...)
agents_term_app_cli wait --text "Repository is clean"   # poll until on screen (exit 4 after --wait-timeout)
agents_term_app_cli wait --tag menu_app_settings --gone
```

- Every control action accepts `-s /tmp/after.png` (`--screenshot`) and `--settle SEC` (default `0.6`) to capture the resulting screen in the same call.
- `ui` lists only what is currently on screen. To reach items further down a list: `scroll`, then `ui` again.
- When several elements match, the first one in the front-most window is used; pick another with `--index N`.
- Dialogs, bottom sheets, and popup menus are separate windows (`window` >= 1 in `ui`).
- `ui` never returns password field contents, and `type` text is never written to the debug log.

### QA Testing Workflow

1. Run `agents_term_app_cli status` (expect `controlReady: true`), then `ui --brief` and `screenshot`.
2. Walk the flow with `tap` / `type` / `scroll` / `key back`, using `wait` instead of sleeping and `-s step-N.png` for evidence.
3. Verify with `ui`, `state`, `logs --category error`, and `crash --raw`.
4. On a bug, capture it while visible: `screenshot`, then `dump --note "<what happened>"`.

## Uploaded Debug Dumps

When the user taps **Upload Debug Dump to VM** (or the top-bar bug icon, or `agents_term_app_cli dump`), the daemon writes a sanitized bundle to `~/.config/herdr-mobile/debug-dumps/` and updates the symlink:

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

## Protocol

| Direction | Frame |
| :--- | :--- |
| app → daemon (on connect, on toggle) | `{"type":"app_hello","reporter":{"enabled":true,"appVersion":"…","device":"…","commands":[…],"controlEnabled":false,"controlCommands":["tap",…]}}` |
| daemon → app | `{"type":"app_state_request","requestId":"…","command":"logs","args":{…}}` |
| app → daemon | `{"type":"app_state_response","requestId":"…","ok":true,"result":{…}}` or `"ok":false,"error":"…"` |

REST: `GET /api/app/status` and `POST /api/app/command` with body `{"command":"logs","args":{…},"timeoutMs":15000}` (bearer auth).

## Important Rules

- **Screenshots**, `ui`, and control commands require the app to be in the foreground and show a brief toast on the phone. Other commands may time out if Android freezes a backgrounded app.
- Only drive the app when the task calls for it: the user is holding that phone.
- **Bearer tokens** are redacted before leaving the phone.
- **Never restart the running daemon from an AgentsTerm tab:** Agent sessions are child processes of the VM daemon (`herdr-mobile.service`). Running `agentsterm-daemon install` or restarting `herdr-mobile.service` from inside an agent tab kills your own session. Test daemon changes on a scratch port with a temporary `HOME` instead (`HOME=/tmp/test-home agentsterm-daemon serve --host 127.0.0.1 --port 7599`).
