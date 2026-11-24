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
#   AGENTSTERM_NO_SYSTEMD   set to 1 to skip systemd --user and use the detached fallback (containers, tests)
#   AGENTSTERM_NO_CRON      set to 1 to skip modifying the user's crontab (containers, tests)
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
    -h|--help) sed -n '2,21p' "$0"; exit 0 ;;
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

# --- systemd --user & crontab @reboot helpers ---------------------------------------------------
USER_NAME="$(id -un)"
CRON_MARKER="# agentsterm-daemon"
START_SCRIPT="$CONFIG_DIR/start-daemon.sh"
SYSTEMD_READY=0
LINGER_ENABLED=0

ensure_linger() {
  local user="$1"
  command -v loginctl >/dev/null 2>&1 || return 1
  if loginctl show-user "$user" -p Linger 2>/dev/null | grep -qx 'Linger=yes'; then
    return 0
  fi
  if loginctl enable-linger "$user" >/dev/null 2>&1; then
    return 0
  fi
  return 1
}

probe_systemd_user() {
  [ "${AGENTSTERM_NO_SYSTEMD:-0}" != 1 ] || return 0
  command -v systemctl >/dev/null 2>&1 || return 0
  if [ -z "${XDG_RUNTIME_DIR:-}" ]; then
    XDG_RUNTIME_DIR="/run/user/$(id -u)"
    export XDG_RUNTIME_DIR
  fi
  if ensure_linger "$USER_NAME"; then
    LINGER_ENABLED=1
  fi
  local wait_attempts="${AGENTSTERM_SYSTEMD_WAIT_ATTEMPTS:-10}"
  local wait_delay="${AGENTSTERM_SYSTEMD_WAIT_DELAY:-0.5}"
  local attempt=0
  while :; do
    if [ -z "${DBUS_SESSION_BUS_ADDRESS:-}" ] && [ -S "$XDG_RUNTIME_DIR/bus" ]; then
      export DBUS_SESSION_BUS_ADDRESS="unix:path=$XDG_RUNTIME_DIR/bus"
    fi
    if systemctl --user show-environment >/dev/null 2>&1; then
      SYSTEMD_READY=1
      return 0
    fi
    [ "$LINGER_ENABLED" = 1 ] || return 0
    attempt=$((attempt + 1))
    [ "$attempt" -le "$wait_attempts" ] || return 0
    sleep "$wait_delay"
  done
}

read_crontab() {
  local out rc=0
  out="$(crontab -l 2>&1)" || rc=$?
  if [ "$rc" -eq 0 ]; then
    printf '%s\n' "$out"
    return 0
  fi
  case "$out" in
    *"no crontab for"*|*"No crontab for"*) return 0 ;;
    *) return "$rc" ;;
  esac
}

has_cron_reboot() {
  [ "${AGENTSTERM_NO_CRON:-0}" != 1 ] || return 1
  command -v crontab >/dev/null 2>&1 || return 1
  local existing
  existing="$(read_crontab)" || return 1
  printf '%s\n' "$existing" | grep -qF "$CRON_MARKER"
}

write_start_script() {
  local py_q daemon_q host_q port_q cfg_q
  py_q="$("$PYTHON" -c 'import shlex, sys; print(shlex.quote(sys.argv[1]))' "$PYTHON")"
  daemon_q="$("$PYTHON" -c 'import shlex, sys; print(shlex.quote(sys.argv[1]))' "$DAEMON")"
  host_q="$("$PYTHON" -c 'import shlex, sys; print(shlex.quote(sys.argv[1]))' "$TS_IP")"
  port_q="$("$PYTHON" -c 'import shlex, sys; print(shlex.quote(sys.argv[1]))' "$PORT")"
  cfg_q="$("$PYTHON" -c 'import shlex, sys; print(shlex.quote(sys.argv[1]))' "$CONFIG_DIR")"
  cat > "$START_SCRIPT" <<EOF
#!/usr/bin/env bash
# Auto-generated by AgentsTerm daemon/install.sh for @reboot startup.
set -euo pipefail
export PATH="\$HOME/.local/bin:\$HOME/.npm-global/bin:\$HOME/.cargo/bin:/usr/local/bin:\${PATH:-/usr/bin:/bin}"
export PYTHONUNBUFFERED=1
PYTHON=$py_q
DAEMON=$daemon_q
HOST=$host_q
PORT=$port_q
CONFIG_DIR=$cfg_q
mkdir -p "\$CONFIG_DIR"

# If a daemon is already accepting connections on HOST:PORT, do not start a duplicate.
if "\$PYTHON" - "\$HOST" "\$PORT" <<'PY' 2>/dev/null
import socket, sys
host, port = sys.argv[1], int(sys.argv[2])
try:
    with socket.create_connection((host, port), timeout=1):
        sys.exit(0)
except OSError:
    sys.exit(1)
PY
then
  exit 0
fi

# After a reboot, cron may start before Tailscale has assigned its IPv4 address.
# Wait up to 60s for HOST to be bindable on a local interface.
for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30; do
  if "\$PYTHON" - "\$HOST" <<'PY' 2>/dev/null
import socket, sys
host = sys.argv[1]
fam = socket.AF_INET6 if ":" in host else socket.AF_INET
s = socket.socket(fam, socket.SOCK_STREAM)
try:
    s.bind((host, 0))
finally:
    s.close()
PY
  then
    break
  fi
  sleep 2
done

if command -v setsid >/dev/null 2>&1; then DETACH=setsid; else DETACH=; fi
nohup \$DETACH "\$PYTHON" "\$DAEMON" serve --host "\$HOST" --port "\$PORT" \\
  >> "\$CONFIG_DIR/daemon.log" 2>&1 < /dev/null &
echo \$! > "\$CONFIG_DIR/daemon.pid"
EOF
  chmod 700 "$START_SCRIPT"
}

install_cron_reboot() {
  [ "${AGENTSTERM_NO_CRON:-0}" != 1 ] || return 1
  command -v crontab >/dev/null 2>&1 || return 1
  write_start_script
  local existing filtered entry script_q
  existing="$(read_crontab)" || return 1
  filtered="$(printf '%s\n' "$existing" | grep -vF "$CRON_MARKER" || true)"
  script_q="$("$PYTHON" -c 'import shlex, sys; print(shlex.quote(sys.argv[1]).replace("%", "\\%"))' "$START_SCRIPT")"
  entry="@reboot $script_q $CRON_MARKER"
  if [ -n "$filtered" ]; then
    printf '%s\n%s\n' "$filtered" "$entry" | crontab - >/dev/null 2>&1
  else
    printf '%s\n' "$entry" | crontab - >/dev/null 2>&1
  fi
}

remove_cron_reboot() {
  [ "${AGENTSTERM_NO_CRON:-0}" != 1 ] || return 0
  command -v crontab >/dev/null 2>&1 || return 0
  local existing filtered
  existing="$(read_crontab)" || return 0
  if ! printf '%s\n' "$existing" | grep -qF "$CRON_MARKER"; then
    return 0
  fi
  filtered="$(printf '%s\n' "$existing" | grep -vF "$CRON_MARKER" || true)"
  if [ -n "$filtered" ]; then
    printf '%s\n' "$filtered" | crontab - >/dev/null 2>&1 || true
  else
    crontab -r >/dev/null 2>&1 || printf '' | crontab - >/dev/null 2>&1 || true
  fi
  rm -f "$START_SCRIPT"
}

# --- start --------------------------------------------------------------------------------------
probe_systemd_user
REBOOT_STATUS=""
if [ "$NEED_RESTART" != 1 ]; then
  if [ "$SYSTEMD_READY" = 1 ] && systemctl --user is-active -q "$UNIT_NAME" 2>/dev/null; then
    if [ "$LINGER_ENABLED" = 1 ]; then
      REBOOT_STATUS="will survive reboots (systemd --user with linger enabled)"
    else
      REBOOT_STATUS="will NOT survive a reboot or logout (could not enable linger; run 'sudo loginctl enable-linger $USER_NAME')"
    fi
  elif [ -x "$START_SCRIPT" ] && has_cron_reboot; then
    REBOOT_STATUS="will survive reboots (via @reboot crontab entry -> $START_SCRIPT)"
  elif install_cron_reboot; then
    log "Installed @reboot crontab entry ($START_SCRIPT) for existing detached daemon"
    REBOOT_STATUS="will survive reboots (via @reboot crontab entry -> $START_SCRIPT)"
  else
    REBOOT_STATUS="will NOT survive a reboot (systemd --user and crontab are unavailable; run 'sudo loginctl enable-linger $USER_NAME' and re-run install.sh --force-restart)"
  fi
elif [ "$SYSTEMD_READY" = 1 ]; then
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
  remove_cron_reboot
  stop_detached   # a nohup-started daemon would hold the port
  systemctl --user restart "$UNIT_NAME"
  MODE="systemd --user ($UNIT_NAME)"
  if [ "$LINGER_ENABLED" = 1 ]; then
    REBOOT_STATUS="will survive reboots (systemd --user with linger enabled)"
  else
    log "note: could not enable linger; run 'sudo loginctl enable-linger $USER_NAME' so systemd --user starts at boot"
    REBOOT_STATUS="will NOT survive a reboot or logout (could not enable linger; run 'sudo loginctl enable-linger $USER_NAME')"
  fi
else
  log "systemd --user is unavailable; starting a detached process"
  stop_detached
  if command -v setsid >/dev/null 2>&1; then DETACH=setsid; else DETACH=; fi
  PYTHONUNBUFFERED=1 nohup $DETACH "$PYTHON" "$DAEMON" serve --host "$TS_IP" --port "$PORT" \
    >> "$CONFIG_DIR/daemon.log" 2>&1 < /dev/null &
  echo $! > "$CONFIG_DIR/daemon.pid"
  if install_cron_reboot; then
    log "Installed @reboot crontab fallback ($START_SCRIPT)"
    if [ "${AGENTSTERM_NO_SYSTEMD:-0}" != 1 ] && command -v systemctl >/dev/null 2>&1; then
      log "note: for systemd --user supervision instead of cron, run 'sudo loginctl enable-linger $USER_NAME' and re-run install.sh --force-restart"
    fi
    MODE="detached process + @reboot crontab (pid $(cat "$CONFIG_DIR/daemon.pid"), log $CONFIG_DIR/daemon.log)"
    REBOOT_STATUS="will survive reboots (via @reboot crontab entry -> $START_SCRIPT)"
  else
    MODE="detached process (pid $(cat "$CONFIG_DIR/daemon.pid"), log $CONFIG_DIR/daemon.log)"
    REBOOT_STATUS="will NOT survive a reboot (systemd --user and crontab are unavailable; run 'sudo loginctl enable-linger $USER_NAME' and re-run install.sh --force-restart)"
  fi
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
log "Reboot persistence: $REBOOT_STATUS"
"$PYTHON" "$DAEMON" pairing --host "$TS_IP" --port "$PORT"
}

main "$@"
