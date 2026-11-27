#!/usr/bin/env bash
# Starts the zero-dependency Docker E2E VM container using the static Rust
# daemon binary and writes its metadata (including SNAPSHOT_DIR) to
# GITHUB_OUTPUT.
set -euo pipefail

: "${GITHUB_OUTPUT:?GITHUB_OUTPUT is required}"

BINARY_PATH="${1:-dist-daemon/agentsterm-daemon-linux-x86_64}"
chmod 755 "$BINARY_PATH"
META="$(bash e2e/docker-vm/start_vm.sh \
  --name agentsterm-ci-vm \
  --ssh-port 2222 \
  --daemon-port 7591 \
  --target vm-minimal \
  --binary "$BINARY_PATH")"
printf '%s\n' "$META"
printf '%s\n' "$META" >> "$GITHUB_OUTPUT"
