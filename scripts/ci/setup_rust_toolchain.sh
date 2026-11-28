#!/usr/bin/env bash
# Installs the pinned Rust toolchain, lint components, and Linux musl targets
# declared in rust-toolchain.toml for testing and cross-compiling the VM daemon.
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
toolchain_file="${repo_root}/rust-toolchain.toml"
rust_channel="$(sed -n 's/^channel = "\(.*\)"$/\1/p' "${toolchain_file}")"
if [ -z "${rust_channel}" ]; then
  echo "Failed to read Rust channel from ${toolchain_file}" >&2
  exit 1
fi

rustup toolchain install "${rust_channel}" --profile minimal --no-self-update
rustup component add --toolchain "${rust_channel}" rustfmt clippy
rustup target add --toolchain "${rust_channel}" x86_64-unknown-linux-musl aarch64-unknown-linux-musl
