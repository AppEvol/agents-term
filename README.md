# AgentsTerm

Native Android client (Kotlin + Jetpack Compose + Material 3) for controlling persistent coding agents (**Antigravity (`agy`)**, **Claude Code (`claude`)**, **Codex (`codex`)**, and **Shell**) running on a Tailscale-connected Linux VM.

AgentsTerm supports both:
- **SSH setup** downloads and verifies the Rust daemon on the VM, starts it, and uses the pairing URI it prints to connect.
- **Direct / Pairing URI connection**: Connects directly using an `agentsterm://connect?...` or `herdr://connect?...` URI (or manual Tailscale IP, port, and bearer token).

---

## 1. Agent Guide: Building the Android APK

Follow these steps to compile, test, and package the Android APK from this repository.

### Prerequisites
On a Debian/Ubuntu VM, `sudo ./install_deps.sh` installs everything below plus the Rust toolchain for the daemon, and `./install_deps.sh --check` reports what is missing without changing anything.

- **JDK 17 or 21** (Gradle 9.3.1 requires JDK 17+ to run; Java compilation target is configured in `app/build.gradle.kts`).
- **Android SDK** with `ANDROID_SDK_ROOT` (or `ANDROID_HOME`) exported:
  - `platforms;android-36` (SDK 36, minor API level 1 / Android 16)
  - `build-tools;36.0.0` (or latest 36.x)
  - `platform-tools` (for `adb`)
- **Gradle 9.3.1+** on `PATH` (this repository uses `gradle/wrapper/gradle-wrapper.properties` pinned to `9.3.1` without committing `gradlew` / `gradle-wrapper.jar`).

> **Important:** Do **not** commit `local.properties` or any `*.keystore` files. Set `ANDROID_SDK_ROOT` via the environment instead.

### Debug Signing Keystore
`app/build.gradle.kts` signs debug builds using the first available keystore:
1. `${rootDir}/debug.keystore` (used by CI when decoding `DEBUG_KEYSTORE_BASE64`, or if you place a keystore in your checkout).
2. The main checkout's `debug.keystore` when building from a linked `git worktree`.
3. AGP's default debug signing config (`~/.android/debug.keystore`, generated automatically if missing).

Fresh clones and git worktrees therefore build out of the box with no manual keystore setup.

### Step 1: Run Unit Tests
Verify the protocol models, pairing URI parser, WebSocket state reducer, and SSH bootstrap command builder:

```bash
gradle :app:testDebugUnitTest --tests "com.example.ModelsAndStateTest"
```

### Static Checks
Linting, formatting, import order, and dead-code detection for the Kotlin, Python, shell, and workflow code run from one script (CI runs the same one). It needs a JDK on `PATH` and downloads its pinned tools on first use:

```bash
scripts/static-checks.sh          # check
scripts/static-checks.sh --fix    # apply automatic fixes, then check
gradle :app:lintDebug             # Android Lint (needs the Android SDK)
```

### Step 2: Build the Debug APK
```bash
gradle :app:assembleDebug
```

The compiled debug APK is written to:
```text
app/build/outputs/apk/debug/app-debug.apk
```

### Step 3 (Optional): Build a Signed Release APK / AAB
Release builds read signing credentials from environment variables (`app/build.gradle.kts`):

```bash
export KEYSTORE_PATH="/path/to/my-upload-key.jks"
export STORE_PASSWORD="<store-password>"
export KEY_PASSWORD="<key-password>"

gradle :app:assembleRelease   # -> app/build/outputs/apk/release/app-release.apk
gradle :app:bundleRelease     # -> app/build/outputs/bundle/release/app-release.aab
```

### Step 4: Install onto an Android Device
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Automatic Verified Releases (GitHub Actions)
Every push to `main` that passes `Static Checks`, `Build APK & Unit Tests`, the VM daemon tests, and `End-to-End Android Instrumented Tests` automatically publishes a new versioned GitHub Release (`v0.3.<run_number>-<short_sha>`) with auto-generated release notes, incrementing Android `versionCode` / `versionName`, and both `AgentsTerm-debug.apk` and `AgentsTerm-v0.3.<run_number>-<short_sha>-debug.apk` attached.

- **Always-latest verified APK:** `https://github.com/nedn/agents-term/releases/latest/download/AgentsTerm-debug.apk`
- **Legacy rolling tag link (also kept in sync):** `https://github.com/nedn/agents-term/releases/download/latest/AgentsTerm-debug.apk`

---

## 2. Agent Guide: Setting Up the VM Space for AgentsTerm

The VM daemon is a static Rust binary built from [`daemon-rs/`](daemon-rs/). To install or update it, run **SSH & Bootstrap VM** in the app. An official APK downloads the daemon from its matching GitHub Release, verifies its SHA-256 checksum, and runs `agentsterm-daemon install`.

### Prerequisites on the VM
1. **Tailscale active** on the same tailnet as the phone: `tailscale ip -4` must print a `100.x.y.z` address.
2. **SSH access** for the zero-touch bootstrap: Tailscale SSH (`sudo tailscale set --ssh`, recommended) or OpenSSH with a key or password.
3. **Linux x86_64 or aarch64** with a POSIX shell, `curl` or `wget`, and `sha256sum` or `shasum`. Put the agent CLIs you want to use (`agy`, `claude`, `codex`) on `PATH` and authenticate them.

### Manual install or update

From a checkout on the VM, run the installer over SSH. Restarting the daemon ends active agent turns, including a tab running the installer.

```sh
sh daemon-rs/install.sh --port 7501
```

The script installs the latest release in `~/.agents-term/bin/agentsterm-daemon`. To install a specific release, set `AGENTSTERM_RELEASE_BASE_URL` to its `https://github.com/nedn/agents-term/releases/download/<tag>` URL.

The installer reuses the token and tab history in `~/.agents-term/` (overridable via `AGENTSTERM_HOME`) and preserves active turns when the requested build is already running. It configures the `agentsterm-daemon.service` systemd user service when available, otherwise it starts a detached daemon and sets up an `@reboot` crontab entry when possible. It reports whether the daemon will survive a reboot and prints the pairing URI after checking `/api/info`.

### Day-to-day

```sh
~/.agents-term/bin/agentsterm-daemon pairing   # print the connection details and pairing URI
~/.agents-term/bin/agentsterm-daemon version   # print the version and build hash
```

From a source checkout, run `cargo test --manifest-path daemon-rs/Cargo.toml` for the daemon and `python3 -m unittest discover -s daemon-rs/tests` for the shell installer.

---

## Daemon API Contract Expected by AgentsTerm

AgentsTerm communicates with the VM daemon over HTTP and WebSocket inside the Tailscale WireGuard tunnel (sending `Authorization: Bearer <token>` when a token is configured):

| Endpoint | Purpose |
| :--- | :--- |
| `GET /api/info` | Returns server version, the git `commit` the daemon is running, hostname, Tailscale IP, `defaultCwd`, `defaultSystemPrompt`, `defaultSystemPromptFullAccess`, `attachments` (image upload limits; absent on daemons without attachment support), and detected `agents[]` (including discovered `models[]` and `defaultModel`). |
| `GET /api/agents/:agent/models` | Returns (and optionally refreshes via `?refresh=1`) available models for an agent CLI (`agy`, `claude`, `codex`). |
| `GET /api/tabs` | Lists all persistent agent tabs and `activeTabId`. |
| `POST /api/tabs` | Creates a new persistent tab (`agent`, `cwd`, `permissionMode`, optional `title`, optional `model`, optional `systemPrompt`). Returns HTTP 409 when the VM already has 10 or more open tabs. |
| `PATCH /api/tabs/:id` | Renames a tab, updates its tool `permissionMode`, changes its `model`, updates its `systemPrompt` (pass `""` to clear), or sets its task `status` (`"done"` / `"idle"`). |
| `DELETE /api/tabs/:id` | Closes a persistent tab. |
| `GET /api/tabs/:id/messages` | Fetches full message/tool/thinking transcript for a tab. |
| `POST /api/tabs/:id/messages` | Sends a prompt to the tab (with optional `overridePermissionMode` and optional `attachments`, a list of uploaded image ids). `text` may be empty when there are attachments. |
| `POST /api/tabs/:id/attachments` | Uploads one image (raw request body; PNG, JPEG, GIF, WebP or BMP, at most 8 MB) into `~/.agents-term/agents_tabs/<tabId>/attachments/` and returns `{attachment: {id, name, mimeType, bytes, path}}`. The agent is handed the file paths in its prompt. Not available on shell tabs. |
| `GET /api/tabs/:id/attachments/:attachmentId` | Downloads an uploaded image, for the chat transcript. |
| `POST /api/tabs/:id/interrupt` | Sends interrupt (`SIGINT`/`SIGTERM`) to a running agent turn. |
| `POST /api/tabs/:id/read` | Marks a tab as read. |
| `GET /api/fs?path=...` | Lists directories on the VM for the New Tab directory browser. |
| `POST /api/debug/dumps` | Uploads an Android debug dump (`events.ndjson`, `state.json`, `logcat.txt`, `crash.txt`, optional `screenshot.png`) into `~/.agents-term/debug-dumps/` and updates the `latest` symlink. |
| `GET /api/debug/dumps` | Lists recent Android debug dump folders stored on the VM. |
| `GET /api/app/status` | App state reporter: whether a connected app has remote inspection (`ready`) and remote control (`controlReady`) enabled. |
| `POST /api/app/command` | App state reporter: relays `{command, args, timeoutMs}` to the app over the WebSocket and returns its result (used by `agents_term_app_cli` / `agentsterm-daemon app`). |
| `WS /ws?token=...` | Real-time stream of `snapshot`, `tab_*`, `message_created`, `message_delta`, `tool_update`, and `message_updated` events. The app sends `app_hello` and `app_state_response` frames, and the daemon sends `app_state_request` frames (see `docs/live-debugging.md`). |

---

## Troubleshooting

- If an agent CLI is missing after setup, install it in `~/.local/bin` or `/usr/local/bin` so the daemon can find it.
- **Phone cannot reach `http://<tailscale-ip>:7501`**: Confirm Tailscale is connected on both phone and VM, the daemon is bound to the Tailscale IPv4 (not `127.0.0.1`), and any host firewall (`ufw`/`iptables`) allows TCP `7501` on the `tailscale0` interface.
- **Host key mismatch warning in AgentsTerm**: If the VM was rebuilt and generated a new SSH host key, tap **Trust New Host Key & Retry** in the bootstrap console to update the pinned SHA-256 fingerprint.
- To rotate the bearer token, run `~/.agents-term/bin/agentsterm-daemon rotate-token` over SSH, restart the daemon, and paste the new pairing URI into **VM Connection Settings**.
