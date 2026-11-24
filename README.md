# AgentsTerm

Native Android client (Kotlin + Jetpack Compose + Material 3) for controlling persistent coding agents (**Antigravity (`agy`)**, **Claude Code (`claude`)**, **Codex (`codex`)**, and **Shell**) running on a Tailscale-connected Linux VM.

AgentsTerm supports both:
- **Zero-touch VM bootstrapping via SSH + Antigravity**: Connects over Tailscale SSH and runs `agy --dangerously-skip-permissions` on the VM so Antigravity sets up the daemon environment and outputs the pairing URI automatically.
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

---

## 2. Agent Guide: Setting Up the VM Space for AgentsTerm

Follow these steps when executing directly on the target Linux VM (for example, when invoked via `agy --dangerously-skip-permissions` during AgentsTerm's first-run SSH bootstrap) so the VM is ready for the mobile app to connect.

### Prerequisites on the VM
1. **Tailscale active**: The VM must be connected to the same tailnet as the Android device.
   ```bash
   tailscale ip -4
   ```
   Confirm this returns a `100.x.y.z` Tailscale IPv4 address.
2. **SSH access (for zero-touch mobile bootstrap)**:
   - Recommended: enable Tailscale SSH (`sudo tailscale set --ssh`) so AgentsTerm can connect passwordlessly over WireGuard.
   - Alternative: standard OpenSSH (`sshd` on port `22`) with key or password authentication.
3. **Runtime & CLI tools**:
   - `git`
   - `node` (>= 20) and `npm`
   - Coding agent CLIs you intend to control installed on `PATH` and authenticated (e.g., `agy`, `claude`, `codex`).
   - Note: Non-interactive SSH shells do not load `.bashrc`. Ensure `agy` and `node` live in a standard location (`~/.local/bin`, `~/.npm-global/bin`, `/usr/local/bin`, or `~/.nvm/versions/node/*/bin`) or are exported in `~/.profile`.

### Step 1: Check Whether the Daemon Is Already Running
Before reinstalling, check if the AgentsTerm / `agent-terminal` daemon is already listening on port `7501` (or your configured port):

```bash
TS_IP=$(tailscale ip -4 | head -n 1)
ss -tlnp 2>/dev/null | grep ":7501" || true
```

If it is already running and healthy, jump straight to **Step 5** to verify `/api/info` and print the pairing URI.

### Step 2: Clone or Update the Daemon Repository
Install the server from [`nedn/agent-terminal`](https://github.com/nedn/agent-terminal) into `~/agent-terminal`:

```bash
if [ -d "$HOME/agent-terminal/.git" ]; then
  git -C "$HOME/agent-terminal" pull --ff-only
else
  git clone https://github.com/nedn/agent-terminal.git "$HOME/agent-terminal"
fi
```

### Step 3: Install Dependencies and Build the Server
```bash
cd "$HOME/agent-terminal/server"
npm install
npm run build
```

Confirm `~/agent-terminal/server/dist/index.js` was generated.

### Step 4: Start the Daemon Persistently on the Tailscale Interface
Bind the daemon to the VM's Tailscale IPv4 address on port `7501` so agent sessions survive phone disconnects, app restarts, and SSH exit.

#### Option A: `systemd --user` (Preferred on Linux VMs)
```bash
mkdir -p "$HOME/.config/systemd/user"
cp "$HOME/agent-terminal/server/systemd/herdr-mobile.service" "$HOME/.config/systemd/user/"
systemctl --user daemon-reload
systemctl --user enable --now herdr-mobile.service
loginctl enable-linger "$USER" || true
systemctl --user status herdr-mobile.service --no-pager
```

> If `herdr-mobile.service` needs a custom Node path, port, or working directory, edit `~/.config/systemd/user/herdr-mobile.service` (e.g., `ExecStart=/path/to/node /home/<user>/agent-terminal/server/dist/index.js --port 7501`) and run `systemctl --user daemon-reload && systemctl --user restart herdr-mobile.service`.

#### Option B: Detached Background Process (Fallback if `systemd --user` is unavailable)
```bash
mkdir -p "$HOME/.config/herdr-mobile"
nohup node "$HOME/agent-terminal/server/dist/index.js" --port 7501 \
  > "$HOME/.config/herdr-mobile/daemon.log" 2>&1 < /dev/null &
sleep 2
```

### Step 5: Verify Health and Emit the Pairing URI
1. Retrieve the pairing details (this prints the Tailscale host, port, bearer token, and pairing URI):
   ```bash
   node "$HOME/agent-terminal/server/dist/index.js" pairing --port 7501
   ```
2. Verify the HTTP API responds cleanly on the Tailscale IP:
   ```bash
   TS_IP=$(tailscale ip -4 | head -n 1)
   TOKEN=$(node -e "const fs=require('fs'),p=require('os').homedir()+'/.config/herdr-mobile/config.json'; if(fs.existsSync(p)) console.log(JSON.parse(fs.readFileSync(p,'utf8')).token||'');")
   if [ -n "$TOKEN" ]; then
     curl -fsS -H "Authorization: Bearer $TOKEN" "http://${TS_IP}:7501/api/info"
   else
     curl -fsS "http://${TS_IP}:7501/api/info"
   fi
   ```
3. **Print the exact pairing URI on its own line** in the final output:
   ```text
   herdr://connect?host=<TAILSCALE_IP>&port=7501&token=<TOKEN>
   ```
   *(AgentsTerm accepts both `agentsterm://connect?...` and `herdr://connect?...` schemes. When invoked via the app's SSH + `agy` bootstrapper, AgentsTerm automatically scans the output for this URI, tests `/api/info`, opens the WebSocket stream, and launches the workspace.)*

---

## Daemon API Contract Expected by AgentsTerm

AgentsTerm communicates with the VM daemon over HTTP and WebSocket inside the Tailscale WireGuard tunnel (sending `Authorization: Bearer <token>` when a token is configured):

| Endpoint | Purpose |
| :--- | :--- |
| `GET /api/info` | Returns server version, hostname, Tailscale IP, `defaultCwd`, and detected `agents[]`. |
| `GET /api/tabs` | Lists all persistent agent tabs and `activeTabId`. |
| `POST /api/tabs` | Creates a new persistent tab (`agent`, `cwd`, `permissionMode`, optional `title`). |
| `PATCH /api/tabs/:id` | Renames a tab or updates its tool `permissionMode`. |
| `DELETE /api/tabs/:id` | Closes a persistent tab. |
| `GET /api/tabs/:id/messages` | Fetches full message/tool/thinking transcript for a tab. |
| `POST /api/tabs/:id/messages` | Sends a prompt to the tab (with optional `overridePermissionMode`). |
| `POST /api/tabs/:id/interrupt` | Sends interrupt (`SIGINT`/`SIGTERM`) to a running agent turn. |
| `POST /api/tabs/:id/read` | Marks a tab as read. |
| `GET /api/fs?path=...` | Lists directories on the VM for the New Tab directory browser. |
| `POST /api/debug/dumps` | Uploads an Android debug dump (`events.ndjson`, `state.json`, `logcat.txt`, `crash.txt`, optional `screenshot.png`) into `~/.config/herdr-mobile/debug-dumps/` and updates the `latest` symlink. |
| `GET /api/debug/dumps` | Lists recent Android debug dump folders stored on the VM. |
| `WS /ws?token=...` | Real-time stream of `snapshot`, `tab_*`, `message_created`, `message_delta`, `tool_update`, and `message_updated` events. |

---

## Troubleshooting

- **`'agy' was not found in PATH` during SSH bootstrap**: Symlink `agy` into `~/.local/bin/agy` or `/usr/local/bin/agy`, or export its directory in `~/.profile`.
- **Phone cannot reach `http://<tailscale-ip>:7501`**: Confirm Tailscale is connected on both phone and VM, the daemon is bound to the Tailscale IPv4 (not `127.0.0.1`), and any host firewall (`ufw`/`iptables`) allows TCP `7501` on the `tailscale0` interface.
- **Host key mismatch warning in AgentsTerm**: If the VM was rebuilt and generated a new SSH host key, tap **Trust New Host Key & Retry** in the bootstrap console to update the pinned SHA-256 fingerprint.
- **Rotate bearer token**: Run `node ~/agent-terminal/server/dist/index.js rotate-token` on the VM and paste the new pairing URI into **VM Connection Settings**.
