#!/usr/bin/env bash
# Computes the APK version name and optional pinned daemon release URL, then
# runs Android Lint, JVM unit tests, and debug + androidTest APK builds.
set -euo pipefail

: "${APP_MAJOR_MINOR:?APP_MAJOR_MINOR is required}"
: "${APP_VERSION_CODE:?APP_VERSION_CODE is required}"
: "${GITHUB_SHA_FULL:?GITHUB_SHA_FULL is required}"

PUBLISH_RELEASE="${PUBLISH_RELEASE:-false}"
PUBLISH_PRERELEASE="${PUBLISH_PRERELEASE:-false}"

SHORT_SHA="${GITHUB_SHA_FULL::7}"
export APP_VERSION_NAME="${APP_MAJOR_MINOR}.${APP_VERSION_CODE}-${SHORT_SHA}"

if [ "$PUBLISH_RELEASE" = "true" ]; then
  : "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required when PUBLISH_RELEASE=true}"
  RELEASE_TAG="v${APP_VERSION_NAME}"
  export AGENTSTERM_DAEMON_RELEASE_BASE_URL="https://github.com/${GITHUB_REPOSITORY}/releases/download/${RELEASE_TAG}"
  export AGENTSTERM_EXPECTED_DAEMON_RELEASE_TAG="${RELEASE_TAG}"
  echo "Building release APK pinned to daemon release ${AGENTSTERM_DAEMON_RELEASE_BASE_URL}"
elif [ "$PUBLISH_PRERELEASE" = "true" ]; then
  : "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required when PUBLISH_PRERELEASE=true}"
  PRERELEASE_TAG="v${APP_VERSION_NAME}-preview"
  export AGENTSTERM_DAEMON_RELEASE_BASE_URL="https://github.com/${GITHUB_REPOSITORY}/releases/download/${PRERELEASE_TAG}"
  export AGENTSTERM_EXPECTED_DAEMON_RELEASE_TAG="${PRERELEASE_TAG}"
  echo "Building preview APK pointing daemon bootstrap at ${AGENTSTERM_DAEMON_RELEASE_BASE_URL}"
fi

echo "Building AgentsTerm ${APP_VERSION_NAME} (versionCode=${APP_VERSION_CODE})"
gradle :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace
python3 "$(dirname "$0")/../android_test_retries.py" android-unit
