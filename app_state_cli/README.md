# app_state_cli

Inspect and drive the **live AgentsTerm Android app** from the VM: debug event log, app state,
logcat, last crash, screenshots, full debug dumps, the on-screen UI tree, and taps, swipes, text
entry and Back. Built for coding agents debugging the app while a human uses it on the phone, and
for agents QA testing the app themselves.

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
3. The commands that act on the app (`tap`, `long-press`, `swipe`, `scroll`, `type`, `key`) need a
   second switch, off by default: **Allow VM agents to control the app**. Without it they exit 3
   with `control_disabled`. `status` reports `controlReady`.
4. Screenshots, `ui` and the control commands need the app in the foreground (the user gets a toast for each one). Other commands
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
$CLI screenshot -o /tmp/app.png   # PNG of the app, including open dialogs and menus
$CLI ui --brief                   # what is on screen, one element per line
$CLI dump --note "repro of #42"   # full bundle -> ~/.config/herdr-mobile/debug-dumps/latest/
$CLI raw ping --args '{}'         # any command by name (for new commands)
```

Output is JSON on stdout. Failures print `{"ok": false, "code": ..., "error": ...}` on stderr:

| Exit | Meaning | `code` values |
| :--- | :--- | :--- |
| 0 | success | |
| 1 | usage / local error | `bad_config`, `bad_args` |
| 2 | daemon unreachable, bad token, or daemon too old | `daemon_unreachable`, `unauthorized`, `daemon_unsupported` |
| 3 | app not ready | `no_app_connected`, `unsupported_app`, `inspection_disabled`, `control_disabled`, `app_disconnected` |
| 4 | app failed or timed out, or `wait` gave up | `app_error`, `timeout`, `wait_timeout` |

## Driving the app

Look, act, look again. Positions are **screenshot pixels**: the `screenshot` PNG, the bounds that
`ui` reports and the coordinates that `tap` and `swipe` take all share one space, so a position
read off a screenshot can be tapped as is.

```bash
$CLI ui --brief                          # one line per element; [x,y] is its center
# 1080x2400 screenshot pixels; windows 0:activity; [x,y] is each element's center
[1010,150] Button description="More options" tag=overflow_menu_button clickable
[440,2200] "Message" tag=composer_input editable
[980,2200] Button description="Send" tag=send_button clickable disabled

$CLI tap --tag overflow_menu_button      # by test tag (most stable)
$CLI tap --text "Settings"               # by visible text or content description (substring)
$CLI tap 980 2200                        # by position
$CLI long-press --text "Summarize the repo"
$CLI type "Summarize the repo" --tag composer_input   # --replace to overwrite, --submit to send
$CLI scroll down                         # reveal content further down (drags the biggest list)
$CLI scroll up --amount 0.3 --tag <tag of a list>
$CLI swipe 100 1200 900 1200             # free-form drag; --hold-ms 150 to stop without a fling
$CLI key back                            # closes the keyboard, menu or dialog on top, else goes back
$CLI key enter                           # the focused field's keyboard action (Send, Done...)
$CLI wait --text "Repository is clean"   # poll until it is on screen (exit 4 after --wait-timeout)
$CLI wait --tag menu_app_settings --gone
```

- Every action accepts `--screenshot after.png` (`-s`) to capture the result in the same call.
- `ui` lists only what is on screen. To reach something further down a list: `scroll`, then `ui` again.
- When several elements match, the first one in the front-most window is used; pick another with
  `--index N`. `ui --text …` shows all matches.
- Dialogs, bottom sheets and popup menus are separate windows (`window` ≥ 1 in `ui`). A tap outside
  of one dismisses it, as on the phone.
- `ui` never returns the content of password fields, and `type` text is not written to the debug log.

Limits: only the app's own windows can be seen and driven. The system keyboard, permission
prompts, the share sheet and other apps are out of reach, as is anything while the app is in the
background. Multi-touch (pinch) is not supported. The user sees a "VM agent is controlling the
app" notice, and each action is in the debug log (`logs --category app_state`).

### QA testing and filing bugs

1. `status` (expect `controlReady: true`), then `ui --brief` and a `screenshot` to get your bearings.
2. Walk the flow under test with `tap` / `type` / `scroll` / `key back`, using `wait` instead of
   sleeping, and `-s step-N.png` to keep evidence of each step.
3. Check the outcome: `ui` for what is shown, `state` for what the app believes,
   `logs --category error` and `crash --raw` for what went wrong.
4. On a bug, capture it while it is on screen: `screenshot`, then `dump --note "<what happened>"`
   (event log, logcat, state and screenshot land in `~/.config/herdr-mobile/debug-dumps/latest/`).
5. File it with the steps you ran, expected vs. actual, the app version from `status`, and the
   evidence. Read the dump before attaching anything, since it describes the user's workspace:

   ```bash
   gh issue create --title "Send button stays disabled after pasting a prompt" --body-file report.md
   ```

## Protocol

| Direction | Frame |
| :--- | :--- |
| app → daemon (on connect, on toggle) | `{"type":"app_hello","reporter":{"enabled":true,"appVersion":"…","device":"…","commands":[…],"controlEnabled":false,"controlCommands":["tap",…]}}` |
| daemon → app | `{"type":"app_state_request","requestId":"…","command":"logs","args":{…}}` |
| app → daemon | `{"type":"app_state_response","requestId":"…","ok":true,"result":{…}}` or `"ok":false,"error":"…"` |

REST: `GET /api/app/status`, and `POST /api/app/command` with body
`{"command":"logs","args":{…},"timeoutMs":15000}` (bearer auth, like every daemon endpoint).
Requests go to the most recently connected app that has inspection enabled.

UI commands and their args (positions in screenshot pixels; `tag`/`text`/`exact`/`index` select an
element instead of a position):

| Command | Args |
| :--- | :--- |
| `ui` | optional `tag`, `text`, `exact`, `interactiveOnly` filters |
| `tap` | `x`,`y` or a selector |
| `long_press` | `x`,`y` or a selector; `durationMs` (default 800) |
| `swipe` | `x1`,`y1`,`x2`,`y2`; `durationMs` (default 300); `holdMs` |
| `scroll` | `direction` (`up`/`down`/`left`/`right`); `amount` (0.05–0.9); optional selector of the list |
| `type` | `text`; field by `tag` or `field` (else the focused field); `replace`; `submit` |
| `key` | `key`: `back` or `enter` |

To add an inspection command, handle it in `AppStateReporter.dispatch` (Android:
`app/src/main/java/com/example/data/AppStateReporter.kt`), add it to `COMMANDS`, and optionally
add a subcommand here. `raw` works without any CLI change. UI commands live in
`AppUiControl.kt` (command logic, plain Kotlin) and `AndroidUiDriver.kt` (semantics tree, touch
and key events); a command listed in `AppUiController.CONTROL_COMMANDS` is refused unless the user
allowed control.

## Tests

```bash
python3 -m unittest discover -s app_state_cli
```
