#!/usr/bin/env bash
# Build and start a fresh Docker E2E VM container serving the current repository checkout.
#
# Usage:
#   e2e/docker-vm/start_vm.sh [--name agentsterm-e2e-vm] [--ssh-port 2222] [--daemon-port 7591] [--pubkey /path/to/id.pub]
#
# Prints CONTAINER_NAME, SSH_PORT, DAEMON_PORT, SNAPSHOT_DIR on stdout when ready.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

NAME="agentsterm-e2e-vm"
SSH_PORT="2222"
DAEMON_PORT="7591"
PUBKEY=""
IMAGE_TAG="agentsterm-e2e-vm:latest"

while [ $# -gt 0 ]; do
  case "$1" in
    --name) NAME="${2:?}"; shift 2 ;;
    --ssh-port) SSH_PORT="${2:?}"; shift 2 ;;
    --daemon-port) DAEMON_PORT="${2:?}"; shift 2 ;;
    --pubkey) PUBKEY="${2:?}"; shift 2 ;;
    --image) IMAGE_TAG="${2:?}"; shift 2 ;;
    *) echo "Unknown arg: $1" >&2; exit 2 ;;
  esac
done

# Build a clean git snapshot of the working tree on branch `main` so the VM's
# `git clone --branch main` deploys the exact code currently in this worktree.
SNAPSHOT_DIR="$(mktemp -d "${TMPDIR:-/tmp}/agentsterm-vm-repo.XXXXXX")"
mkdir -p "$SNAPSHOT_DIR/repo"
(
  cd "$REPO_ROOT"
  # Copy tracked + untracked-non-ignored daemon/app_state_cli/docs files.
  git archive HEAD | tar -x -C "$SNAPSHOT_DIR/repo"
  # Overlay any modified tracked files (daemon/, app_state_cli/, docs/) so local edits are tested.
  for dir in daemon app_state_cli docs; do
    if [ -d "$REPO_ROOT/$dir" ]; then
      rm -rf "$SNAPSHOT_DIR/repo/$dir"
      cp -a "$REPO_ROOT/$dir" "$SNAPSHOT_DIR/repo/$dir"
    fi
  done
  rm -rf "$SNAPSHOT_DIR/repo/daemon/tests"
  find "$SNAPSHOT_DIR/repo/daemon" -name "__pycache__" -type d -exec rm -rf {} + 2>/dev/null || true
)

git -C "$SNAPSHOT_DIR/repo" init -q -b main
git -C "$SNAPSHOT_DIR/repo" -c user.name="E2E Test" -c user.email="e2e@example.invalid" add -A
git -C "$SNAPSHOT_DIR/repo" -c user.name="E2E Test" -c user.email="e2e@example.invalid" \
  -c commit.gpgsign=false commit -q -m "E2E snapshot"
SNAPSHOT_COMMIT="$(git -C "$SNAPSHOT_DIR/repo" rev-parse HEAD)"

docker build -q -t "$IMAGE_TAG" "$SCRIPT_DIR" >/dev/null
docker rm -f "$NAME" >/dev/null 2>&1 || true

DOCKER_ARGS=(
  -d
  --name "$NAME"
  --hostname docker-e2e-vm
  -e "DAEMON_PORT=$DAEMON_PORT"
  -e "BRIDGE_PORT=17591"
  -p "127.0.0.1:${SSH_PORT}:22"
  -p "127.0.0.1:${DAEMON_PORT}:17591"
  -v "${SNAPSHOT_DIR}/repo:/mnt/repo:ro"
)
if [ -n "$PUBKEY" ]; then
  DOCKER_ARGS+=(-v "${PUBKEY}:/mnt/ssh-pubkey:ro")
fi

docker run "${DOCKER_ARGS[@]}" "$IMAGE_TAG" >/dev/null

# Wait until OpenSSH responds with its banner on the forwarded port.
for _ in $(seq 1 40); do
  if python3 - "$SSH_PORT" <<'PY'
import socket, sys
port = int(sys.argv[1])
try:
    with socket.create_connection(("127.0.0.1", port), timeout=1) as s:
        banner = s.recv(64)
        sys.exit(0 if banner.startswith(b"SSH-") else 1)
except OSError:
    sys.exit(1)
PY
  then
    printf 'CONTAINER_NAME=%s\nSSH_PORT=%s\nDAEMON_PORT=%s\nSNAPSHOT_DIR=%s\nSNAPSHOT_COMMIT=%s\n' \
      "$NAME" "$SSH_PORT" "$DAEMON_PORT" "$SNAPSHOT_DIR" "$SNAPSHOT_COMMIT"
    exit 0
  fi
  sleep 0.25
done

echo "ERROR: sshd did not become ready on 127.0.0.1:$SSH_PORT" >&2
docker logs "$NAME" >&2 || true
exit 1
