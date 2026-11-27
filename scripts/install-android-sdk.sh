#!/usr/bin/env bash
# Accepts Android SDK licenses and installs the platform and build-tools
# required to build and test AgentsTerm.
set -euo pipefail

ANDROID_SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$ANDROID_SDK_DIR" ]; then
  echo "ERROR: ANDROID_HOME (or ANDROID_SDK_ROOT) must be set." >&2
  exit 1
fi

ANDROID_PLATFORM="${ANDROID_PLATFORM:-platforms;android-36.1}"
ANDROID_BUILD_TOOLS="${ANDROID_BUILD_TOOLS:-build-tools;36.0.0}"
ANDROID_NDK="${ANDROID_NDK:-ndk;29.0.14206865}"

export PATH="$ANDROID_SDK_DIR/cmdline-tools/latest/bin:$ANDROID_SDK_DIR/platform-tools:$PATH"
yes | sdkmanager --licenses > /dev/null 2>&1 || true
sdkmanager "$ANDROID_PLATFORM" "$ANDROID_BUILD_TOOLS" "$ANDROID_NDK"
