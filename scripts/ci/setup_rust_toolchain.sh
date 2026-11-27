#!/usr/bin/env bash
# Installs the stable Rust toolchain, lint components, and Linux musl targets
# needed to test and cross-compile the static VM daemon in CI.
set -euo pipefail

rustup update stable --no-self-update
rustup default stable
rustup component add rustfmt clippy
rustup target add x86_64-unknown-linux-musl aarch64-unknown-linux-musl
