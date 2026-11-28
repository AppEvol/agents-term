#!/usr/bin/env bash
# Detects whether the Rust VM daemon binaries, daemon tests, or Android sources
# changed so CI can reuse verified prebuilt daemon binaries and skip Android
# builds on documentation-only pull requests.
set -euo pipefail

: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
: "${GITHUB_OUTPUT:?GITHUB_OUTPUT is required}"

EVENT_NAME="${EVENT_NAME:-}"
PR_BASE_SHA="${PR_BASE_SHA:-}"
MERGE_GROUP_BASE_SHA="${MERGE_GROUP_BASE_SHA:-}"
PUSH_BEFORE_SHA="${PUSH_BEFORE_SHA:-}"
FORCE_DAEMON_TESTS="${FORCE_DAEMON_TESTS:-false}"
DAEMON_BUILD_HASH_SCRIPT="${DAEMON_BUILD_HASH_SCRIPT:-scripts/daemon-build-hash.py}"

EXPECTED_HASH="$(python3 "${DAEMON_BUILD_HASH_SCRIPT}")"
echo "Expected daemon build hash: ${EXPECTED_HASH}"

BINARY_CHANGED="true"
PUBLISHED_HASH="none"
mkdir -p dist-daemon
if gh release download latest \
  --repo "${GITHUB_REPOSITORY}" \
  --dir dist-daemon \
  --pattern "agentsterm-daemon-linux-*" >/dev/null 2>&1; then
  if [ -f dist-daemon/agentsterm-daemon-linux-x86_64 ] \
    && [ -f dist-daemon/agentsterm-daemon-linux-x86_64.sha256 ] \
    && [ -f dist-daemon/agentsterm-daemon-linux-aarch64 ] \
    && [ -f dist-daemon/agentsterm-daemon-linux-aarch64.sha256 ]; then
    if (cd dist-daemon \
      && sha256sum -c agentsterm-daemon-linux-x86_64.sha256 >/dev/null \
      && sha256sum -c agentsterm-daemon-linux-aarch64.sha256 >/dev/null); then
      chmod 755 dist-daemon/agentsterm-daemon-linux-x86_64 dist-daemon/agentsterm-daemon-linux-aarch64
      PUBLISHED_HASH="$(./dist-daemon/agentsterm-daemon-linux-x86_64 version --hash-only || true)"
      echo "Published latest release build hash: ${PUBLISHED_HASH}"
      if [ "${PUBLISHED_HASH}" = "${EXPECTED_HASH}" ]; then
        BINARY_CHANGED="false"
      fi
    fi
  fi
fi

if [ "${BINARY_CHANGED}" = "true" ]; then
  rm -rf dist-daemon
fi

DAEMON_RS_TESTS_CHANGED="false"
DAEMON_E2E_HARNESS_CHANGED="false"
INSTALLER_TESTS_CHANGED="false"
DOCS_ONLY="false"

BASE_SHA=""
if [ "${EVENT_NAME}" = "pull_request" ] && [ -n "${PR_BASE_SHA}" ]; then
  BASE_SHA="${PR_BASE_SHA}"
elif [ "${EVENT_NAME}" = "merge_group" ] && [ -n "${MERGE_GROUP_BASE_SHA}" ]; then
  BASE_SHA="${MERGE_GROUP_BASE_SHA}"
elif [ "${EVENT_NAME}" = "push" ] && [ -n "${PUSH_BEFORE_SHA}" ] \
  && [ "${PUSH_BEFORE_SHA}" != "0000000000000000000000000000000000000000" ]; then
  BASE_SHA="${PUSH_BEFORE_SHA}"
fi

if [ -n "${BASE_SHA}" ] && git cat-file -e "${BASE_SHA}^{commit}" 2>/dev/null; then
  CHANGED_FILES="$(git diff --name-only "${BASE_SHA}" HEAD || true)"
  if printf '%s\n' "${CHANGED_FILES}" | grep -qE '^(daemon-rs/tests/.*\.rs$|rust-toolchain\.toml$|scripts/ci/(build_rust_daemon|setup_rust_toolchain)\.sh$)'; then
    DAEMON_RS_TESTS_CHANGED="true"
  fi
  if printf '%s\n' "${CHANGED_FILES}" | grep -qE '^(e2e/docker-vm/|scripts/daemon-build-hash\.py$)'; then
    DAEMON_E2E_HARNESS_CHANGED="true"
  fi
  if printf '%s\n' "${CHANGED_FILES}" | grep -qE '^(daemon-rs/install\.sh$|daemon-rs/tests/.*\.py$)'; then
    INSTALLER_TESTS_CHANGED="true"
  fi
  # Exercise all native suites when their common CI runner changes.
  if grep -qE '^(test_utils_py/|scripts/(allure_results|native_test_retries)\.py$)' <<< "${CHANGED_FILES}"; then
    DAEMON_RS_TESTS_CHANGED="true"
    DAEMON_E2E_HARNESS_CHANGED="true"
    INSTALLER_TESTS_CHANGED="true"
  fi
  # A pull request that only touches documentation cannot change the APK,
  # so it skips the Android build and emulator jobs, on its own run and
  # on its merge queue run. Documentation means docs/ and top-level Markdown
  # files, except the two docs that daemon-rs/build.rs embeds in the daemon
  # binary the emulator tests run against. Pushes to main and manual runs
  # always build: they publish. Anything that is not plainly documentation
  # leaves NOT_DOCS non-empty, so a surprise here runs the full suite rather
  # than skipping it.
  NOT_DOCS="$(grep -vE '^(docs/|[^/]+\.md$)' <<< "${CHANGED_FILES}" || true)"
  EMBEDDED_DOCS="$(grep -E '^docs/(live-debugging|agents_debugging_tips)\.md$' <<< "${CHANGED_FILES}" || true)"
  if { [ "${EVENT_NAME}" = "pull_request" ] || [ "${EVENT_NAME}" = "merge_group" ]; } \
    && [ -n "${CHANGED_FILES}" ] \
    && [ -z "${NOT_DOCS}" ] && [ -z "${EMBEDDED_DOCS}" ]; then
    DOCS_ONLY="true"
  fi
fi

RUN_RUST_DAEMON_BUILD="false"
RUN_DAEMON_VM_E2E="false"
RUN_INSTALLER_TESTS="false"
RUN_ANDROID_BUILD="true"

if [ "${DOCS_ONLY}" = "true" ]; then
  RUN_ANDROID_BUILD="false"
fi
if [ "${BINARY_CHANGED}" = "true" ] || [ "${DAEMON_RS_TESTS_CHANGED}" = "true" ] || [ "${FORCE_DAEMON_TESTS}" = "true" ]; then
  RUN_RUST_DAEMON_BUILD="true"
fi
if [ "${BINARY_CHANGED}" = "true" ] || [ "${DAEMON_E2E_HARNESS_CHANGED}" = "true" ] || [ "${FORCE_DAEMON_TESTS}" = "true" ]; then
  RUN_DAEMON_VM_E2E="true"
fi
if [ "${BINARY_CHANGED}" = "true" ] || [ "${INSTALLER_TESTS_CHANGED}" = "true" ] || [ "${FORCE_DAEMON_TESTS}" = "true" ]; then
  RUN_INSTALLER_TESTS="true"
fi

{
  echo "binary_changed=${BINARY_CHANGED}"
  echo "run_rust_daemon_build=${RUN_RUST_DAEMON_BUILD}"
  echo "run_daemon_vm_e2e=${RUN_DAEMON_VM_E2E}"
  echo "run_installer_tests=${RUN_INSTALLER_TESTS}"
  echo "run_android_build=${RUN_ANDROID_BUILD}"
} >> "${GITHUB_OUTPUT}"

if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  {
    echo "### Daemon Change Detection"
    echo "- Expected build hash: \`${EXPECTED_HASH}\`"
    echo "- Latest release hash: \`${PUBLISHED_HASH}\`"
    echo "- Binary changed: \`${BINARY_CHANGED}\`"
    echo "- Run Rust daemon build & tests: \`${RUN_RUST_DAEMON_BUILD}\`"
    echo "- Run multi-distro Linux VM E2E matrix: \`${RUN_DAEMON_VM_E2E}\`"
    echo "- Run installer tests: \`${RUN_INSTALLER_TESTS}\`"
    echo "- Documentation-only pull request: \`${DOCS_ONLY}\`"
    echo "- Run Android build, unit tests & emulator E2E: \`${RUN_ANDROID_BUILD}\`"
  } >> "${GITHUB_STEP_SUMMARY}"
fi
