#!/usr/bin/env bash
# Publishes a versioned release on green main (APK + static Rust daemon
# binaries) and updates the rolling `latest` tag and release alias when this
# run has the highest run number.
set -euo pipefail

: "${APP_MAJOR_MINOR:?APP_MAJOR_MINOR is required}"
: "${GITHUB_SHA:?GITHUB_SHA is required}"
: "${GITHUB_RUN_ID:?GITHUB_RUN_ID is required}"
: "${GITHUB_RUN_NUMBER:?GITHUB_RUN_NUMBER is required}"
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"

SHORT_SHA="${GITHUB_SHA::7}"
VERSION="${APP_MAJOR_MINOR}.${GITHUB_RUN_NUMBER}-${SHORT_SHA}"
TAG="v${VERSION}"
STABLE_APK="dist/AgentsTerm-debug.apk"
VERSIONED_APK="dist/AgentsTerm-${TAG}-debug.apk"
cp dist/app-debug.apk "$STABLE_APK"
cp dist/app-debug.apk "$VERSIONED_APK"

ASSETS=(
  "$STABLE_APK"
  "$VERSIONED_APK"
  dist/agentsterm-daemon-linux-x86_64
  dist/agentsterm-daemon-linux-x86_64.sha256
  dist/agentsterm-daemon-linux-aarch64
  dist/agentsterm-daemon-linux-aarch64.sha256
)

EXISTING_TAGS=$(gh api "repos/${GITHUB_REPOSITORY}/releases?per_page=30" --jq '.[].tag_name' \
  | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+-[0-9a-f]+$' || true)

IS_NEWEST="true"
EXISTING_MAX=$(printf '%s\n' "$EXISTING_TAGS" \
  | sed -n 's/^v[0-9][0-9]*\.[0-9][0-9]*\.\([0-9][0-9]*\)-.*$/\1/p' \
  | sort -nr | head -n 1 || true)
if [ -n "${EXISTING_MAX:-}" ] && [ "$EXISTING_MAX" -gt "$GITHUB_RUN_NUMBER" ]; then
  IS_NEWEST="false"
fi

LATEST_FLAG="--latest"
if [ "$IS_NEWEST" != "true" ]; then
  LATEST_FLAG="--latest=false"
fi

PREV_TAG=$(printf '%s\n' "$EXISTING_TAGS" \
  | awk -F'[.-]' -v cur="$GITHUB_RUN_NUMBER" 'NF >= 3 && $3 < cur { print $3, $0 }' \
  | sort -nr | head -n 1 | awk '{print $2}' || true)

# shellcheck disable=SC2016  # backticks are literal Markdown
NOTES=$(printf 'Verified Android debug APK and static Linux Rust VM daemon binaries automatically built and tested on green `main` commit `%s`.\n\n- **Version:** `%s` (`versionCode=%s`)\n- **Static Rust VM daemon:** `agentsterm-daemon-linux-x86_64` & `agentsterm-daemon-linux-aarch64` (+ `.sha256`)\n- **Build & unit tests:** passed\n- **Android Emulator + Docker VM E2E tests:** passed\n- **CI run:** https://github.com/%s/actions/runs/%s\n\n### Install on Android\n1. Download `AgentsTerm-debug.apk` directly on your Android phone (always-latest link: https://github.com/%s/releases/latest/download/AgentsTerm-debug.apk).\n2. Open the downloaded APK and allow installing from your browser/files app if prompted.\n' \
  "$SHORT_SHA" "$VERSION" "$GITHUB_RUN_NUMBER" "$GITHUB_REPOSITORY" "$GITHUB_RUN_ID" "$GITHUB_REPOSITORY")

if gh release view "$TAG" --repo "${GITHUB_REPOSITORY}" >/dev/null 2>&1; then
  gh release upload "$TAG" "${ASSETS[@]}" --clobber --repo "${GITHUB_REPOSITORY}"
  gh release edit "$TAG" \
    --title "AgentsTerm ${TAG}" \
    --notes "$NOTES" \
    "$LATEST_FLAG" \
    --repo "${GITHUB_REPOSITORY}"
else
  NOTES_ARGS=(--generate-notes --notes "$NOTES")
  if [ -n "${PREV_TAG:-}" ]; then
    NOTES_ARGS+=(--notes-start-tag "$PREV_TAG")
  fi
  gh release create "$TAG" "${ASSETS[@]}" \
    --target "$GITHUB_SHA" \
    --title "AgentsTerm ${TAG}" \
    "${NOTES_ARGS[@]}" \
    "$LATEST_FLAG" \
    --repo "${GITHUB_REPOSITORY}"
fi

if [ "$IS_NEWEST" = "true" ]; then
  if gh api "repos/${GITHUB_REPOSITORY}/git/ref/tags/latest" >/dev/null 2>&1; then
    gh api "repos/${GITHUB_REPOSITORY}/git/refs/tags/latest" -X PATCH \
      -f sha="${GITHUB_SHA}" -F force=true >/dev/null
  fi
  if gh release view latest --repo "${GITHUB_REPOSITORY}" >/dev/null 2>&1; then
    # shellcheck disable=SC2016  # backticks are literal Markdown
    ROLLING_NOTES=$(printf 'Rolling alias pointing to verified release [`%s`](https://github.com/%s/releases/tag/%s).\n\n%s\n' \
      "$TAG" "$GITHUB_REPOSITORY" "$TAG" "$NOTES")
    gh release upload latest \
      "$STABLE_APK" \
      dist/agentsterm-daemon-linux-x86_64 \
      dist/agentsterm-daemon-linux-x86_64.sha256 \
      dist/agentsterm-daemon-linux-aarch64 \
      dist/agentsterm-daemon-linux-aarch64.sha256 \
      --clobber --repo "${GITHUB_REPOSITORY}"
    gh release edit latest \
      --title "AgentsTerm Rolling Latest (${TAG})" \
      --notes "$ROLLING_NOTES" \
      --latest=false \
      --repo "${GITHUB_REPOSITORY}"
  fi
fi
