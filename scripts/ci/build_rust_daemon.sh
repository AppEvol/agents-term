#!/usr/bin/env bash
# Builds static Linux musl release binaries (x86_64 and aarch64) for the Rust
# VM daemon, writes SHA-256 checksums into dist-daemon/, and verifies that the
# embedded build hash matches scripts/daemon-build-hash.py.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DAEMON_DIR="${REPO_ROOT}/daemon-rs"
DIST_DIR="${REPO_ROOT}/dist-daemon"

(
  cd "${DAEMON_DIR}"
  cargo build --release --target x86_64-unknown-linux-musl
  CARGO_TARGET_AARCH64_UNKNOWN_LINUX_MUSL_LINKER=rust-lld \
    cargo build --release --target aarch64-unknown-linux-musl
)

mkdir -p "${DIST_DIR}"
cp "${DAEMON_DIR}/target/x86_64-unknown-linux-musl/release/agentsterm-daemon" \
  "${DIST_DIR}/agentsterm-daemon-linux-x86_64"
cp "${DAEMON_DIR}/target/aarch64-unknown-linux-musl/release/agentsterm-daemon" \
  "${DIST_DIR}/agentsterm-daemon-linux-aarch64"
chmod 755 \
  "${DIST_DIR}/agentsterm-daemon-linux-x86_64" \
  "${DIST_DIR}/agentsterm-daemon-linux-aarch64"

(
  cd "${DIST_DIR}"
  sha256sum agentsterm-daemon-linux-x86_64 > agentsterm-daemon-linux-x86_64.sha256
  sha256sum agentsterm-daemon-linux-aarch64 > agentsterm-daemon-linux-aarch64.sha256
)

EXPECTED_HASH="$(python3 "${REPO_ROOT}/scripts/daemon-build-hash.py" "${DAEMON_DIR}")"
BUILT_HASH="$("${DIST_DIR}/agentsterm-daemon-linux-x86_64" version --hash-only)"
if [ "${EXPECTED_HASH}" != "${BUILT_HASH}" ]; then
  echo "ERROR: scripts/daemon-build-hash.py (${EXPECTED_HASH}) != binary hash (${BUILT_HASH})" >&2
  exit 1
fi

file "${DIST_DIR}/agentsterm-daemon-linux-x86_64" "${DIST_DIR}/agentsterm-daemon-linux-aarch64"
ls -lh "${DIST_DIR}"
