#!/usr/bin/env bash
# Installs (or updates) the AgentsTerm VM daemon and starts it persistently on the Tailscale IPv4.
#
# Every run leaves the latest daemon code running: it updates the checkout, restarts the daemon
# unless it is already running that exact commit, and verifies the commit the daemon reports.
# Idempotent: safe to re-run. Replaces the legacy Node daemon (~/agent-terminal) if it is running.
# Run it from an SSH session (the app's bootstrap does this), not from an AgentsTerm agent tab:
# restarting the daemon ends every agent session it is running, including the one running this.
#
#   bash daemon/install.sh [--port 7501] [--ref main] [--no-update] [--force-restart]
#
# Environment overrides:
#   AGENTSTERM_REPO_URL     git URL (default https://github.com/nedn/agents-term.git)
#   AGENTSTERM_REF          branch/tag to deploy (default main)
#   AGENTSTERM_INSTALL_DIR  checkout used by the service (default ~/.local/share/agentsterm/agents-term)
#   AGENTSTERM_HOST         bind address (default: this VM's Tailscale IPv4)
#   AGENTSTERM_KEEP_LEGACY  set to 1 to leave other daemon processes alone (tests/sandboxes)
#   AGENTSTERM_NO_SYSTEMD   set to 1 to always start a detached process (containers, tests)
#
# Exit codes: 0 ok, 2 bad usage, 3 missing prerequisite (python3/git/tailscale), 4 daemon unhealthy.
set -euo pipefail

# Everything lives in main(): bash parses the whole function before running it, so updating this
# checkout (git checkout below) cannot corrupt the script mid-execution.
main() {

PORT="${AGENT_TERMINAL_PORT:-7501}"
REF="${AGENTSTERM_REF:-main}"
REPO_URL="${AGENTSTERM_REPO_URL:-https://github.com/nedn/agents-term.git}"
INSTALL_DIR="${AGENTSTERM_INSTALL_DIR:-$HOME/.local/share/agentsterm/agents-term}"
UPDATE=1
FORCE_RESTART=0
CONFIG_DIR="$HOME/.config/herdr-mobile"
UNIT_NAME="herdr-mobile.service"   # same unit name as the legacy Node daemon, so it is replaced in place

log() { printf '>>> %s\n' "$*"; }
die() { local code="$1"; shift; printf 'ERROR: %s\n' "$*" >&2; exit "$code"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --port) PORT="${2:?--port needs a value}"; shift 2 ;;
    --port=*) PORT="${1#--port=}"; shift ;;
    --ref) REF="${2:?--ref needs a value}"; shift 2 ;;
    --ref=*) REF="${1#--ref=}"; shift ;;
    --no-update) UPDATE=0; shift ;;
    --force-restart) FORCE_RESTART=1; shift ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) die 2 "unknown argument: $1" ;;
  esac
done
case "$PORT" in ''|*[!0-9]*) die 2 "invalid port: $PORT" ;; esac
[ "$PORT" -ge 1 ] && [ "$PORT" -le 65535 ] || die 2 "invalid port: $PORT"

export PATH="$PATH:$HOME/.local/bin:/usr/local/bin:/usr/bin:/bin"  # fallbacks; keep the caller's PATH priority

# --- prerequisites ------------------------------------------------------------------------------
PYTHON="$(command -v python3 || true)"
[ -n "$PYTHON" ] || die 3 "python3 is not installed (install it with the system package manager, e.g. 'sudo apt-get install -y python3')."
"$PYTHON" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 9) else 1)' \
  || die 3 "python3 >= 3.9 is required (found $("$PYTHON" -V 2>&1))."

TS_IP="${AGENTSTERM_HOST:-}"
if [ -z "$TS_IP" ]; then
  command -v tailscale >/dev/null 2>&1 || die 3 "tailscale CLI not found; install Tailscale and run 'sudo tailscale up'."
  TS_IP="$(tailscale ip -4 2>/dev/null | head -n 1 || true)"
  [ -n "$TS_IP" ] || die 3 "Tailscale has no IPv4 address; run 'sudo tailscale up' and retry."
fi

# --- checkout -----------------------------------------------------------------------------------
if [ "$UPDATE" = 1 ]; then
  command -v git >/dev/null 2>&1 || die 3 "git is not installed (e.g. 'sudo apt-get install -y git')."
  if [ -d "$INSTALL_DIR/.git" ]; then
    log "Updating $INSTALL_DIR to $REF"
    git -C "$INSTALL_DIR" fetch --depth 1 origin "$REF"
    git -C "$INSTALL_DIR" checkout -q -B agentsterm-deploy FETCH_HEAD
  else
    log "Cloning $REPO_URL ($REF) into $INSTALL_DIR"
    mkdir -p "$(dirname "$INSTALL_DIR")"
    git clone -q --depth 1 --branch "$REF" "$REPO_URL" "$INSTALL_DIR"
  fi
fi
DAEMON="$INSTALL_DIR/daemon/agentsterm-daemon"
[ -f "$DAEMON" ] || die 3 "$DAEMON not found (is $REF a branch that contains the Python daemon?)."

mkdir -p "$CONFIG_DIR" && chmod 700 "$CONFIG_DIR"

# --- is the running daemon already this exact code? ---------------------------------------------
# The daemon reports the commit it was started from. A healthy daemon that still runs older code
# must be restarted; one that already runs TARGET_COMMIT is left alone (a restart ends agent turns).
TARGET_COMMIT="$(git -C "$INSTALL_DIR" rev-parse HEAD 2>/dev/null || true)"
running_commit() {
  "$PYTHON" - "$TS_IP" "$PORT" "$CONFIG_DIR/config.json" <<'PY' 2>/dev/null || true
import json, sys, urllib.request
host, port, cfg_path = sys.argv[1:4]
token = json.load(open(cfg_path)).get("token", "")
req = urllib.request.Request(f"http://{host}:{port}/api/info", headers={"Authorization": f"Bearer {token}"})
with urllib.request.urlopen(req, timeout=3) as resp:
    print(json.load(resp).get("commit") or "")
PY
}
NEED_RESTART=1
if [ "$FORCE_RESTART" != 1 ] && [ -n "$TARGET_COMMIT" ] && [ "$(running_commit)" = "$TARGET_COMMIT" ]; then
  NEED_RESTART=0
  MODE="unchanged (already running the latest commit ${TARGET_COMMIT:0:12}; not restarted)"
  log "Daemon is already running the latest commit ${TARGET_COMMIT:0:12}"
fi

# --- stop the legacy Node daemon / previous detached instance -----------------------------------
stop_detached() {
  if [ "${AGENTSTERM_KEEP_LEGACY:-0}" = 1 ]; then
    [ -f "$CONFIG_DIR/daemon.pid" ] && kill "$(cat "$CONFIG_DIR/daemon.pid")" 2>/dev/null || true
    return 0
  fi
  if [ -f "$CONFIG_DIR/daemon.pid" ]; then
    kill "$(cat "$CONFIG_DIR/daemon.pid")" 2>/dev/null || true
    rm -f "$CONFIG_DIR/daemon.pid"
  fi
  pkill -f "agent-terminal/server/dist/index.js" 2>/dev/null || true
  pkill -f "daemon/agentsterm-daemon( |$)" 2>/dev/null || true
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    pgrep -f "agent-terminal/server/dist/index.js|daemon/agentsterm-daemon( |$)" >/dev/null 2>&1 || return 0
    sleep 0.3
  done
}

# --- start --------------------------------------------------------------------------------------
if [ "$NEED_RESTART" != 1 ]; then
  :
elif [ "${AGENTSTERM_NO_SYSTEMD:-0}" != 1 ] && systemctl --user show-environment >/dev/null 2>&1; then
  UNIT_DIR="$HOME/.config/systemd/user"
  mkdir -p "$UNIT_DIR"
  cat > "$UNIT_DIR/$UNIT_NAME" <<EOF
[Unit]
Description=AgentsTerm VM daemon (Python)
After=network-online.target tailscaled.service
Wants=network-online.target

[Service]
Type=simple
ExecStart=$PYTHON $DAEMON serve --host $TS_IP --port $PORT
Restart=always
RestartSec=3
Environment=PYTHONUNBUFFERED=1
Environment=PATH=%h/.local/bin:%h/.npm-global/bin:%h/.cargo/bin:/usr/local/bin:/usr/bin:/bin

[Install]
WantedBy=default.target
EOF
  log "Installed $UNIT_DIR/$UNIT_NAME"
  systemctl --user daemon-reload
  systemctl --user enable -q "$UNIT_NAME"
  stop_detached   # a nohup-started daemon would hold the port
  systemctl --user restart "$UNIT_NAME"
  loginctl enable-linger "$(id -un)" >/dev/null 2>&1 || log "note: could not enable linger; the daemon may stop when you log out"
  MODE="systemd --user ($UNIT_NAME)"
else
  log "systemd --user is unavailable; starting a detached process"
  stop_detached
  if command -v setsid >/dev/null 2>&1; then DETACH=setsid; else DETACH=; fi
  PYTHONUNBUFFERED=1 nohup $DETACH "$PYTHON" "$DAEMON" serve --host "$TS_IP" --port "$PORT" \
    >> "$CONFIG_DIR/daemon.log" 2>&1 < /dev/null &
  echo $! > "$CONFIG_DIR/daemon.pid"
  MODE="detached process (pid $(cat "$CONFIG_DIR/daemon.pid"), log $CONFIG_DIR/daemon.log)"
fi

# --- health check -------------------------------------------------------------------------------
log "Waiting for the daemon on http://$TS_IP:$PORT ..."
if ! "$PYTHON" - "$TS_IP" "$PORT" "$CONFIG_DIR/config.json" "$TARGET_COMMIT" <<'PY'
import json, sys, time, urllib.request
host, port, cfg_path, target = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
deadline = time.time() + 20
last = "no response"
while time.time() < deadline:
    try:
        token = json.load(open(cfg_path)).get("token", "")
        req = urllib.request.Request(f"http://{host}:{port}/api/info", headers={"Authorization": f"Bearer {token}"})
        with urllib.request.urlopen(req, timeout=3) as resp:
            info = json.load(resp)
            commit = info.get("commit") or ""
            # A previous instance may still be answering while it shuts down: keep waiting until
            # the daemon on this port reports the commit that was just installed.
            if target and commit != target:
                last = f"daemon reports commit {commit[:12] or 'unknown'}, expected {target[:12]}"
            else:
                print(f">>> Daemon v{info.get('version')} (commit {commit[:12] or 'unknown'}) healthy on "
                      f"{host}:{port} (hostname {info.get('hostname')})")
                sys.exit(0)
    except Exception as err:  # noqa: BLE001
        last = str(err)
    time.sleep(0.5)
print(f"ERROR: daemon is not healthy on the expected code: {last}", file=sys.stderr)
sys.exit(1)
PY
then
  if [ -f "$CONFIG_DIR/daemon.log" ]; then tail -n 20 "$CONFIG_DIR/daemon.log" >&2 || true; fi
  systemctl --user status "$UNIT_NAME" --no-pager -n 20 >&2 2>/dev/null || true
  die 4 "the daemon failed its health check (see output above)."
fi

log "Running as $MODE"
"$PYTHON" "$DAEMON" pairing --host "$TS_IP" --port "$PORT"
}

main "$@"
