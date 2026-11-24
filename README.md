# AgentsTerm

Native Android client (Kotlin + Jetpack Compose + Material 3) for controlling persistent coding agents (**Antigravity (`agy`)**, **Claude Code (`claude`)**, **Codex (`codex`)**, and **Shell**) running on a Tailscale-connected Linux VM.

AgentsTerm supports both:
- **Zero-touch VM bootstrapping over SSH**: Connects over Tailscale SSH to run `daemon/install.sh` automatically (falling back to `agy --dangerously-skip-permissions` to repair the VM if prerequisites are missing) and outputs the pairing URI.
- **Direct / Pairing URI connection**: Connects directly using an `agentsterm://connect?...` or `herdr://connect?...` URI (or manual Tailscale IP, port, and bearer token).

---

## 1. Agent Guide: Building the Android APK

Follow these steps to compile, test, and package the Android APK from this repository.

### Prerequisites
- **JDK 17 or 21** (Gradle 9.3.1 requires JDK 17+ to run; Java compilation target is configured in `app/build.gradle.kts`).
- **Android SDK** with `ANDROID_SDK_ROOT` (or `ANDROID_HOME`) exported:
  - `platforms;android-36` (SDK 36, minor API level 1 / Android 16)
  - `build-tools;36.0.0` (or latest 36.x)
  - `platform-tools` (for `adb`)
- **Gradle 9.3.1+** on `PATH` (this repository uses `gradle/wrapper/gradle-wrapper.properties` pinned to `9.3.1` without committing `gradlew` / `gradle-wrapper.jar`).

> **Important:** Do **not** commit a `local.properties` file or modify `signingConfigs` in `app/build.gradle.kts`. Set `ANDROID_SDK_ROOT` via the environment instead.

### Step 1: Ensure `debug.keystore` Exists
`app/build.gradle.kts` signs debug builds using `${rootDir}/debug.keystore`. On a fresh clone, decode it from the committed base64 archive before running Gradle:

```bash
if [ ! -f debug.keystore ] && [ -f debug.keystore.base64 ]; then
  base64 -d debug.keystore.base64 > debug.keystore
fi
```

### Step 2: Run Unit Tests
Verify the protocol models, pairing URI parser, WebSocket state reducer, and SSH bootstrap command builder:

```bash
gradle :app:testDebugUnitTest --tests "com.example.ModelsAndStateTest"
```

### Step 3: Build the Debug APK
```bash
gradle :app:assembleDebug
```

The compiled debug APK is written to:
```text
app/build/outputs/apk/debug/app-debug.apk
```

### Step 4 (Optional): Build a Signed Release APK / AAB
Release builds read signing credentials from environment variables (`app/build.gradle.kts`):

```bash
export KEYSTORE_PATH="/path/to/my-upload-key.jks"
export STORE_PASSWORD="<store-password>"
export KEY_PASSWORD="<key-password>"

gradle :app:assembleRelease   # -> app/build/outputs/apk/release/app-release.apk
gradle :app:bundleRelease     # -> app/build/outputs/bundle/release/app-release.aab
```

### Step 5: Install onto an Android Device
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Automatic Verified Releases (GitHub Actions)
Every push to `main` that passes both `Build APK & Unit Tests` and `End-to-End Android Instrumented Tests` automatically publishes a new versioned GitHub Release (`v0.3.<run_number>-<short_sha>`) with auto-generated release notes, incrementing Android `versionCode` / `versionName`, and both `AgentsTerm-debug.apk` and `AgentsTerm-v0.3.<run_number>-<short_sha>-debug.apk` attached.

- **Always-latest verified APK:** `https://github.com/nedn/agents-term/releases/latest/download/AgentsTerm-debug.apk`
- **Legacy rolling tag link (also kept in sync):** `https://github.com/nedn/agents-term/releases/download/latest/AgentsTerm-debug.apk`

---

## 2. Agent Guide: Setting Up the VM Space for AgentsTerm

The VM daemon is the Python 3 program in [`daemon/`](daemon/) (standard library only, no build step).

**Every SSH bootstrap from the app leaves the latest daemon running.** The bootstrap command:

1. Clones the repo if needed and runs `daemon/install.sh`. The script updates the checkout to the latest `main`, restarts the daemon unless it is already running that exact commit, and verifies the commit the running daemon reports.
2. Only if that fails (python3 or git missing, Tailscale down, ...) invokes `agy --dangerously-skip-permissions` with a prompt telling Antigravity to repair the VM.
3. Runs `install.sh` again as the authoritative check. A non-zero exit fails the bootstrap in the app, even if a pairing URI was printed along the way.

So to update a VM, run the SSH bootstrap again from the app (or run the commands below by hand).

### Prerequisites on the VM
1. **Tailscale active** on the same tailnet as the phone: `tailscale ip -4` must print a `100.x.y.z` address.
2. **SSH access** for the zero-touch bootstrap: Tailscale SSH (`sudo tailscale set --ssh`, recommended) or OpenSSH with a key or password.
3. **Tools:** `python3` (>= 3.9), `git`, and the agent CLIs you want to control (`agy`, `claude`, `codex`) on `PATH` and authenticated. Non-interactive SSH shells don't load `.bashrc`, so put them in `~/.local/bin` or `/usr/local/bin`, or export them in `~/.profile`.

### Install or update (idempotent)
```bash
DIR="$HOME/.local/share/agentsterm/agents-term"
if [ -d "$DIR/.git" ]; then bash "$DIR/daemon/install.sh" --port 7501
else git clone --depth 1 https://github.com/nedn/agents-term.git "$DIR" && bash "$DIR/daemon/install.sh" --port 7501; fi
```

`install.sh` does the following:
- Updates the checkout (`--ref <branch>`, default `main`; `--no-update` skips this step).
- Restarts the daemon unless it is already running the checkout's exact commit (`--force-restart` always restarts). A restart ends agent turns that are in progress, so an up-to-date daemon is left alone.
- Installs `~/.config/systemd/user/herdr-mobile.service` and enables linger. Without `systemd --user` it starts a detached process logging to `~/.config/herdr-mobile/daemon.log`.
- Replaces the legacy Node daemon (`~/agent-terminal`) if one is running. Config, token, and tab history in `~/.config/herdr-mobile/` are reused as-is.
- Health-checks `http://<tailscale-ip>:<port>/api/info`, requires it to report the commit that was just installed, and prints the `herdr://connect?...` pairing URI.

Exit codes: `3` means a prerequisite is missing (python3, git, or Tailscale), and `4` means the daemon is not healthy on the expected commit (see `systemctl --user status herdr-mobile.service` or the log).

> **Don't run `install.sh` from an AgentsTerm agent tab.** Agent sessions are child processes of the daemon, so restarting the daemon ends them, including the session running the script. Run it over SSH instead.

### Day-to-day
```bash
D="$HOME/.local/share/agentsterm/agents-term/daemon/agentsterm-daemon"
python3 "$D" pairing --port 7501      # print host, port, token and the pairing URI
python3 "$D" version                  # daemon version and the git commit of the checkout
python3 "$D" rotate-token             # new bearer token (restart the daemon to apply)
systemctl --user restart herdr-mobile.service
python3 -m unittest discover -s daemon/tests   # daemon tests (from a repo checkout)
```

*(AgentsTerm accepts both `agentsterm://connect?...` and `herdr://connect?...`. The SSH bootstrapper scans the bootstrap output for this URI, tests `/api/info`, opens the WebSocket stream, and launches the workspace.)*

---

## Daemon API Contract Expected by AgentsTerm

AgentsTerm communicates with the VM daemon over HTTP and WebSocket inside the Tailscale WireGuard tunnel (sending `Authorization: Bearer <token>` when a token is configured):

| Endpoint | Purpose |
| :--- | :--- |
| `GET /api/info` | Returns server version, the git `commit` the daemon is running, hostname, Tailscale IP, `defaultCwd`, `defaultSystemPrompt`, `defaultSystemPromptFullAccess`, and detected `agents[]` (including discovered `models[]` and `defaultModel`). |
| `GET /api/agents/:agent/models` | Returns (and optionally refreshes via `?refresh=1`) available models for an agent CLI (`agy`, `claude`, `codex`). |
| `GET /api/tabs` | Lists all persistent agent tabs and `activeTabId`. |
| `POST /api/tabs` | Creates a new persistent tab (`agent`, `cwd`, `permissionMode`, optional `title`, optional `model`, optional `systemPrompt`). |
| `PATCH /api/tabs/:id` | Renames a tab, updates its tool `permissionMode`, changes its `model`, or updates its `systemPrompt` (pass `""` to clear). |
| `DELETE /api/tabs/:id` | Closes a persistent tab. |
| `GET /api/tabs/:id/messages` | Fetches full message/tool/thinking transcript for a tab. |
| `POST /api/tabs/:id/messages` | Sends a prompt to the tab (with optional `overridePermissionMode`). |
| `POST /api/tabs/:id/interrupt` | Sends interrupt (`SIGINT`/`SIGTERM`) to a running agent turn. |
| `POST /api/tabs/:id/read` | Marks a tab as read. |
| `GET /api/fs?path=...` | Lists directories on the VM for the New Tab directory browser. |
| `POST /api/debug/dumps` | Uploads an Android debug dump (`events.ndjson`, `state.json`, `logcat.txt`, `crash.txt`, optional `screenshot.png`) into `~/.config/herdr-mobile/debug-dumps/` and updates the `latest` symlink. |
| `GET /api/debug/dumps` | Lists recent Android debug dump folders stored on the VM. |
| `GET /api/app/status` | App state reporter: whether a connected app has remote inspection (`ready`) and remote control (`controlReady`) enabled. |
| `POST /api/app/command` | App state reporter: relays `{command, args, timeoutMs}` to the app over the WebSocket and returns its result (used by `app_state_cli/`). |
| `WS /ws?token=...` | Real-time stream of `snapshot`, `tab_*`, `message_created`, `message_delta`, `tool_update`, and `message_updated` events. The app sends `app_hello` and `app_state_response` frames, and the daemon sends `app_state_request` frames (see `app_state_cli/README.md`). |

---

## Troubleshooting

- **`'agy' was not found in PATH` during SSH bootstrap**: Symlink `agy` into `~/.local/bin/agy` or `/usr/local/bin/agy`, or export its directory in `~/.profile`.
- **Phone cannot reach `http://<tailscale-ip>:7501`**: Confirm Tailscale is connected on both phone and VM, the daemon is bound to the Tailscale IPv4 (not `127.0.0.1`), and any host firewall (`ufw`/`iptables`) allows TCP `7501` on the `tailscale0` interface.
- **Host key mismatch warning in AgentsTerm**: If the VM was rebuilt and generated a new SSH host key, tap **Trust New Host Key & Retry** in the bootstrap console to update the pinned SHA-256 fingerprint.
- **Rotate bearer token**: Run `python3 ~/.local/share/agentsterm/agents-term/daemon/agentsterm-daemon rotate-token` on the VM, restart the daemon, and paste the new pairing URI into **VM Connection Settings**.
