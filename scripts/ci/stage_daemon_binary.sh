#!/usr/bin/env bash
# Places the downloaded static x86_64 Rust daemon binary where
# e2e/docker-vm/start_vm.sh expects it.
set -euo pipefail

SRC="${1:-dist-daemon/agentsterm-daemon-linux-x86_64}"
DEST="${2:-daemon-rs/target/x86_64-unknown-linux-musl/release/agentsterm-daemon}"

mkdir -p "$(dirname "$DEST")"
cp "$SRC" "$DEST"
chmod 755 "$DEST"
