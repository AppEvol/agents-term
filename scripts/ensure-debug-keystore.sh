#!/usr/bin/env bash
# Ensures an Android debug.keystore exists in the repository root (or at a
# custom path passed via KEYSTORE_PATH / $1).
#
# Precedence when the keystore does not already exist:
#   1. DEBUG_KEYSTORE_BASE64 env var (CI secret)
#   2. debug.keystore.base64 file (or KEYSTORE_BASE64_FILE)
#   3. Generate a standard Android debug keystore with keytool
set -euo pipefail

KEYSTORE_PATH="${1:-${KEYSTORE_PATH:-debug.keystore}}"
KEYSTORE_BASE64_FILE="${KEYSTORE_BASE64_FILE:-${KEYSTORE_PATH}.base64}"

if [ -f "$KEYSTORE_PATH" ]; then
  exit 0
fi

mkdir -p "$(dirname "$KEYSTORE_PATH")"

if [ -n "${DEBUG_KEYSTORE_BASE64:-}" ]; then
  printf '%s' "$DEBUG_KEYSTORE_BASE64" | base64 -d > "$KEYSTORE_PATH"
elif [ -f "$KEYSTORE_BASE64_FILE" ]; then
  base64 -d "$KEYSTORE_BASE64_FILE" > "$KEYSTORE_PATH"
else
  keytool -genkeypair -v \
    -keystore "$KEYSTORE_PATH" \
    -storepass android \
    -alias androiddebugkey \
    -keypass android \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US"
fi
