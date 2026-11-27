#!/usr/bin/env bash
# Publishes a non-main branch preview pre-release (APK + static Rust daemon
# binaries) without modifying the rolling latest release.
set -euo pipefail

: "${APP_MAJOR_MINOR:?APP_MAJOR_MINOR is required}"
: "${GITHUB_SHA:?GITHUB_SHA is required}"
: "${GITHUB_RUN_ID:?GITHUB_RUN_ID is required}"
: "${GITHUB_RUN_NUMBER:?GITHUB_RUN_NUMBER is required}"
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
: "${GITHUB_REF_NAME:?GITHUB_REF_NAME is required}"

SHORT_SHA="${GITHUB_SHA::7}"
VERSION="${APP_MAJOR_MINOR}.${GITHUB_RUN_NUMBER}-${SHORT_SHA}"
TAG="v${VERSION}-preview"
STABLE_APK="dist/AgentsTerm-debug.apk"
VERSIONED_APK="dist/AgentsTerm-${TAG}-debug.apk"
cp dist/app-debug.apk "$STABLE_APK"
cp dist/app-debug.apk "$VERSIONED_APK"

# shellcheck disable=SC2016  # backticks are literal Markdown
NOTES=$(printf 'Preview pre-release built and verified on branch `%s` (`%s`) for live testing against the static Rust VM daemon before merging to `main`.\n\n- **Version:** `%s` (`versionCode=%s`)\n- **Daemon download base URL baked into preview APK:** `https://github.com/%s/releases/download/%s`\n- **CI run:** https://github.com/%s/actions/runs/%s\n\n### Live Testing Steps\n1. Install `AgentsTerm-debug.apk` from this pre-release on your phone.\n2. Open **Settings > Edit VM Connection > SSH Auto-Setup** and run **SSH & Bootstrap VM** against your VM (it will download and verify the static Rust daemon from this pre-release and migrate in place, preserving your token and tabs).\n3. Or manually install on your VM:\n   ```sh\n   AGENTSTERM_RELEASE_BASE_URL=https://github.com/%s/releases/download/%s sh daemon-rs/install.sh\n   ```\n' \
  "$GITHUB_REF_NAME" "$SHORT_SHA" "$VERSION" "$GITHUB_RUN_NUMBER" "$GITHUB_REPOSITORY" "$TAG" "$GITHUB_REPOSITORY" "$GITHUB_RUN_ID" "$GITHUB_REPOSITORY" "$TAG")

gh release create "$TAG" \
  "$STABLE_APK" \
  "$VERSIONED_APK" \
  dist/agentsterm-daemon-linux-x86_64 \
  dist/agentsterm-daemon-linux-x86_64.sha256 \
  dist/agentsterm-daemon-linux-aarch64 \
  dist/agentsterm-daemon-linux-aarch64.sha256 \
  --target "$GITHUB_SHA" \
  --title "AgentsTerm Preview ${TAG}" \
  --notes "$NOTES" \
  --prerelease \
  --latest=false \
  --repo "${GITHUB_REPOSITORY}"
