# VM Daemon Setup & Operations

The AgentsTerm VM daemon (`agentsterm-daemon`) is a single static Rust binary built from [`daemon-rs/`](../daemon-rs/). All binaries, configuration, persistent tab histories, logs, and debug dumps live under `$HOME/.agents-term/` (see [Architecture](architecture.md)).

## VM Prerequisites

1. **Tailscale active** on the same tailnet as the phone: `tailscale ip -4` must print a `100.x.y.z` address.
2. **SSH access** for zero-touch setup from the app: Tailscale SSH (`sudo tailscale set --ssh`, recommended) or OpenSSH with a key or password.
3. **Linux `x86_64` or `aarch64`** with a POSIX shell, `curl` or `wget`, and `sha256sum` or `shasum` (zero runtime dependencies such as `python3` or `git`).
4. **Agent CLIs** you want to use (`agy`, `claude`, `codex`) installed on `PATH` and authenticated.

## Installing & Updating

### From the Android App (Recommended)

Run **SSH & Bootstrap VM** from the app's **SSH Auto-Setup** tab. An official APK downloads the daemon binary from its matching GitHub Release, verifies its SHA-256 checksum, installs it into `~/.agents-term/bin/agentsterm-daemon`, and runs `agentsterm-daemon install`.

### Manual Install or Update

From a checkout on the VM, run the installer over SSH.

> **Warning:** Restarting the daemon terminates active agent turns, including an AgentsTerm tab running the installer. Run manual updates from a regular SSH session, not from inside an agent tab.

```sh
sh daemon-rs/install.sh --port 7501
```

Useful environment overrides:

- `AGENTSTERM_RELEASE_BASE_URL` — base URL for a specific release tag (default: `https://github.com/AppEvol/agents-term/releases/latest/download`; e.g., `https://github.com/AppEvol/agents-term/releases/download/<tag>`).
- `AGENTSTERM_HOME` — state and install root directory (default: `$HOME/.agents-term`).
- `AGENTSTERM_BIN_DIR` — binary install directory (default: `$AGENTSTERM_HOME/bin`).
- `AGENTSTERM_HOST` — bind address override (default: detected Tailscale IPv4).

### Service Lifecycle & Persistence

The installer:

- Reuses the existing bearer token and tab history in `~/.agents-term/` and preserves active turns when the requested build hash is already running (pass `--force-restart` to restart anyway).
- Configures the `agentsterm-daemon.service` `systemd --user` service when available; otherwise starts a detached daemon and installs an `@reboot` crontab entry (`agentsterm-daemon ensure-running`) when possible.
- Reports whether the daemon will survive a VM reboot, verifies `/api/info`, and prints the pairing URI for **Direct / URI** setup in the app.

## Day-to-Day CLI

```sh
~/.agents-term/bin/agentsterm-daemon pairing       # print host, port, token, and pairing URI
~/.agents-term/bin/agentsterm-daemon version       # print version and commit (--hash-only for build hash)
~/.agents-term/bin/agentsterm-daemon rotate-token  # generate a new bearer token (restart daemon to apply)
```

Other subcommands:

- `serve [--host HOST] [--port PORT] [--no-auth]` — run the daemon in the foreground (default subcommand; binds Tailscale IPv4 unless `--host` is given; port defaults to `$AGENT_TERMINAL_PORT` or `7501`).
- `install [--host HOST] [--port PORT] [--force-restart]` — configure/restart the user service or detached daemon and verify health.
- `ensure-running [--host HOST] [--port PORT]` — start the daemon if not already running (used by `@reboot` crontab).
- `update [<version>] [--expected-hash HASH] [--stage-only] [--force] [--host HOST] [--port PORT]` — download a validated release tag (or `latest`), verify its `.sha256` checksum and `version --hash-only`, back up the current healthy binary to `~/.agents-term/bin/agentsterm-daemon.prev`, atomically install, and run `install` with automatic rollback on failure. Set `AGENTSTERM_DISABLE_SELF_UPDATE=1` to disable self-update.
- `rollback [--host HOST] [--port PORT]` — restore `~/.agents-term/bin/agentsterm-daemon.prev` to `~/.agents-term/bin/agentsterm-daemon` and restart the service. Works both as `agentsterm-daemon rollback` and directly via `~/.agents-term/bin/agentsterm-daemon.prev rollback` (breakglass recovery if the newly installed binary cannot start).
- `app ...` — inspect or drive the connected Android app (also installed as the `~/.agents-term/bin/agents_term_app_cli` symlink; see [Live Debugging](live-debugging.md)).

To run tests for the daemon and shell installer from source, see [Development, Testing & Releases](development.md).

## Troubleshooting

- **Missing agent CLI after setup:** Install the CLI in `~/.local/bin` or `/usr/local/bin` so the daemon finds it on `PATH`, and verify it is authenticated under the same Linux user running the daemon.
- **Phone cannot reach `http://<tailscale-ip>:7501`:** Confirm Tailscale is connected on both phone and VM, the daemon is bound to the Tailscale IPv4 (not `127.0.0.1`), and any host firewall (`ufw`/`iptables`) allows TCP `7501` on the `tailscale0` interface.
- **Host key mismatch warning in AgentsTerm:** If the VM was rebuilt and generated a new SSH host key, tap **Trust New Host Key & Retry** in the bootstrap console to update the pinned SHA-256 fingerprint.
- **Rotating the bearer token:** Run `~/.agents-term/bin/agentsterm-daemon rotate-token` over SSH, restart the daemon (`systemctl --user restart agentsterm-daemon.service` or `agentsterm-daemon install --force-restart`), and paste the new pairing URI into **VM Connection Settings** in the app.

For the HTTP and WebSocket protocol served by the daemon, see [Daemon API Contract](daemon-api.md).
