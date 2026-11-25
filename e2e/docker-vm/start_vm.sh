#!/usr/bin/env bash
# Build and start a fresh Docker E2E VM container serving the compiled static Rust daemon binary.
#
# Usage:
#   e2e/docker-vm/start_vm.sh [--name agentsterm-e2e-vm] [--ssh-port 2222] [--daemon-port 7591]
#       [--pubkey /path/to/id.pub] [--target vm-minimal]
#       [--binary /path/to/agentsterm-daemon]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

NAME="agentsterm-e2e-vm"
SSH_PORT="2222"
DAEMON_PORT="7591"
PUBKEY=""
TARGET="vm-minimal"
BINARY_PATH=""

while [ $# -gt 0 ]; do
  case "$1" in
    --name) NAME="${2:?}"; shift 2 ;;
    --ssh-port) SSH_PORT="${2:?}"; shift 2 ;;
    --daemon-port) DAEMON_PORT="${2:?}"; shift 2 ;;
    --pubkey) PUBKEY="${2:?}"; shift 2 ;;
    --target) TARGET="${2:?}"; shift 2 ;;
    --binary) BINARY_PATH="${2:?}"; shift 2 ;;
    *) echo "Unknown arg: $1" >&2; exit 2 ;;
  esac
done

IMAGE_TAG="agentsterm-e2e-${TARGET}:latest"
RAW_ARCH="$(uname -m)"
case "$RAW_ARCH" in
  x86_64|amd64) ARCH="x86_64"; RUST_TARGET="x86_64-unknown-linux-musl" ;;
  aarch64|arm64) ARCH="aarch64"; RUST_TARGET="aarch64-unknown-linux-musl" ;;
  *) echo "Unsupported host arch: $RAW_ARCH" >&2; exit 2 ;;
esac

if [ -z "$BINARY_PATH" ]; then
  CANDIDATE="$REPO_ROOT/daemon-rs/target/${RUST_TARGET}/release/agentsterm-daemon"
  if [ ! -x "$CANDIDATE" ]; then
    echo ">>> Building static Rust daemon (${RUST_TARGET})..." >&2
    (cd "$REPO_ROOT/daemon-rs" && cargo build --release --target "$RUST_TARGET" >&2)
  fi
  BINARY_PATH="$CANDIDATE"
fi
[ -x "$BINARY_PATH" ] || { echo "ERROR: daemon binary not found at $BINARY_PATH" >&2; exit 1; }

SNAPSHOT_DIR="$(mktemp -d "${TMPDIR:-/tmp}/agentsterm-vm-release.XXXXXX")"
mkdir -p "$SNAPSHOT_DIR/release"

ASSET="agentsterm-daemon-linux-${ARCH}"
cp "$BINARY_PATH" "$SNAPSHOT_DIR/release/$ASSET"
chmod 755 "$SNAPSHOT_DIR/release/$ASSET"
(cd "$SNAPSHOT_DIR/release" && sha256sum "$ASSET" > "${ASSET}.sha256")
BINARY_HASH="$("$BINARY_PATH" version --hash-only)"
BINARY_SHA256="$(awk '{print $1}' "$SNAPSHOT_DIR/release/${ASSET}.sha256")"

DOCKER_ARGS=(
  -d
  --name "$NAME"
  --hostname docker-e2e-vm
  -e "DAEMON_PORT=$DAEMON_PORT"
  -e "BRIDGE_PORT=17591"
  -p "127.0.0.1:${SSH_PORT}:22"
  -p "127.0.0.1:${DAEMON_PORT}:17591"
  -v "${SNAPSHOT_DIR}/release:/mnt/release:ro"
)

if [ -n "$PUBKEY" ]; then
  DOCKER_ARGS+=(-v "${PUBKEY}:/mnt/ssh-pubkey:ro")
fi

docker build -q --target "$TARGET" -t "$IMAGE_TAG" "$SCRIPT_DIR" >/dev/null
docker rm -f "$NAME" >/dev/null 2>&1 || true
docker run "${DOCKER_ARGS[@]}" "$IMAGE_TAG" >/dev/null

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
    printf 'CONTAINER_NAME=%s\nSSH_PORT=%s\nDAEMON_PORT=%s\nSNAPSHOT_DIR=%s\nBINARY_HASH=%s\nBINARY_SHA256=%s\n' \
      "$NAME" "$SSH_PORT" "$DAEMON_PORT" "$SNAPSHOT_DIR" "$BINARY_HASH" "$BINARY_SHA256"
    exit 0
  fi
  sleep 0.25
done

echo "ERROR: sshd did not become ready on 127.0.0.1:$SSH_PORT" >&2
docker logs "$NAME" >&2 || true
exit 1
