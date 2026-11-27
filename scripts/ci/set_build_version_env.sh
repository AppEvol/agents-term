#!/usr/bin/env bash
# Exports APP_VERSION_CODE and APP_VERSION_NAME into GITHUB_ENV for downstream
# steps in the Android E2E job.
set -euo pipefail

: "${APP_MAJOR_MINOR:?APP_MAJOR_MINOR is required}"
: "${GITHUB_SHA:?GITHUB_SHA is required}"
: "${GITHUB_RUN_NUMBER:?GITHUB_RUN_NUMBER is required}"
: "${GITHUB_ENV:?GITHUB_ENV is required}"

SHORT_SHA="${GITHUB_SHA::7}"
{
  echo "APP_VERSION_CODE=${GITHUB_RUN_NUMBER}"
  echo "APP_VERSION_NAME=${APP_MAJOR_MINOR}.${GITHUB_RUN_NUMBER}-${SHORT_SHA}"
} >> "$GITHUB_ENV"
