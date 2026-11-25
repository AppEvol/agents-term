#!/usr/bin/env sh
# Minimal POSIX bootstrapper for the static Rust AgentsTerm VM daemon.
# Downloads the prebuilt static musl binary from GitHub Releases, verifies its SHA-256,
# atomically installs it into ~/.local/bin/agentsterm-daemon, and delegates lifecycle
# management (systemd --user / detached+@reboot / health verification / pairing URI)
# to `agentsterm-daemon install`.
#
# Zero runtime dependencies: no python3, no git, no bash required.
#
# Usage:
#   sh daemon/install.sh [--port 7501] [--host IP] [--force-restart]
#
# Environment overrides:
#   AGENTSTERM_RELEASE_BASE_URL  base URL hosting agentsterm-daemon-linux-<arch>{,.sha256}
#                                (default: https://github.com/nedn/agents-term/releases/latest/download)
#   AGENTSTERM_BIN_DIR           install directory (default: $HOME/.local/bin)
#   AGENTSTERM_HOST              bind address override (default: Tailscale IPv4)
set -eu

BASE_URL="${AGENTSTERM_RELEASE_BASE_URL:-https://github.com/nedn/agents-term/releases/latest/download}"
BIN_DIR="${AGENTSTERM_BIN_DIR:-$HOME/.local/bin}"
BIN_PATH="$BIN_DIR/agentsterm-daemon"

die() { code="$1"; shift; printf 'ERROR: %s\n' "$*" >&2; exit "$code"; }
log() { printf '>>> %s\n' "$*"; }

OS="$(uname -s)"
[ "$OS" = "Linux" ] || die 3 "Unsupported OS: $OS (AgentsTerm VM daemon currently supports Linux)."

RAW_ARCH="$(uname -m)"
case "$RAW_ARCH" in
  x86_64|amd64) ARCH="x86_64" ;;
  aarch64|arm64) ARCH="aarch64" ;;
  *) die 3 "Unsupported CPU architecture: $RAW_ARCH (supported: x86_64, aarch64)." ;;
esac

ASSET="agentsterm-daemon-linux-${ARCH}"
BIN_URL="${BASE_URL%/}/${ASSET}"
SHA_URL="${BIN_URL}.sha256"

fetch() {
  url="$1"
  dest="$2"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL --retry 3 -o "$dest" "$url"
  elif command -v wget >/dev/null 2>&1; then
    wget -qO "$dest" "$url"
  else
    die 3 "Neither 'curl' nor 'wget' is installed; install one of them to download the daemon binary."
  fi
}

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    die 3 "Neither 'sha256sum' nor 'shasum' is installed; cannot verify daemon binary checksum."
  fi
}

mkdir -p "$BIN_DIR"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/agentsterm-dl.XXXXXX")"
trap 'rm -rf "$TMP_DIR"' EXIT INT TERM

log "Checking latest AgentsTerm daemon (${ASSET})..."
fetch "$SHA_URL" "$TMP_DIR/checksum.sha256" || die 3 "Failed to download checksum from $SHA_URL"
EXPECTED_SHA="$(awk 'NR==1 {print $1}' "$TMP_DIR/checksum.sha256")"
[ -n "$EXPECTED_SHA" ] || die 3 "Checksum file from $SHA_URL was empty."

NEED_DOWNLOAD=1
if [ -x "$BIN_PATH" ]; then
  CURRENT_SHA="$(sha256_of "$BIN_PATH" || true)"
  if [ "$CURRENT_SHA" = "$EXPECTED_SHA" ]; then
    log "Installed binary at $BIN_PATH already matches release SHA-256 (${EXPECTED_SHA%"${EXPECTED_SHA#????????????}"})."
    NEED_DOWNLOAD=0
  fi
fi

if [ "$NEED_DOWNLOAD" = 1 ]; then
  log "Downloading $BIN_URL ..."
  fetch "$BIN_URL" "$TMP_DIR/$ASSET" || die 3 "Failed to download daemon binary from $BIN_URL"
  ACTUAL_SHA="$(sha256_of "$TMP_DIR/$ASSET")"
  if [ "$ACTUAL_SHA" != "$EXPECTED_SHA" ]; then
    die 3 "SHA-256 mismatch for $ASSET (expected $EXPECTED_SHA, got $ACTUAL_SHA)."
  fi
  chmod 755 "$TMP_DIR/$ASSET"
  # Move into the same directory first then rename atomically to avoid ETXTBSY on a running binary.
  mv "$TMP_DIR/$ASSET" "$BIN_DIR/.agentsterm-daemon.tmp.$$"
  mv -f "$BIN_DIR/.agentsterm-daemon.tmp.$$" "$BIN_PATH"
  log "Installed verified binary at $BIN_PATH"
fi

rm -rf "$TMP_DIR"
trap - EXIT INT TERM

exec "$BIN_PATH" install "$@"
