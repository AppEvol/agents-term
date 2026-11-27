#!/usr/bin/env bash
# Enforces the required CI job outcomes for the ci-gate ("All Required CI
# Checks") branch-protection check.
set -euo pipefail

fail=0

require_success() {
  if [ "$2" != "success" ]; then
    echo "FAIL: $1 = $2 (expected success)" >&2
    fail=1
  fi
}

require_success_or_skipped() {
  if [ "$2" != "success" ] && [ "$2" != "skipped" ]; then
    echo "FAIL: $1 = $2 (expected success or skipped)" >&2
    fail=1
  fi
}

require_success "static-checks" "${STATIC_CHECKS:-}"
require_success "script-tests" "${SCRIPT_TESTS:-}"
require_success "daemon-change-check" "${DAEMON_CHANGE_CHECK:-}"
# The Android jobs may only be skipped when the change check decided
# the pull request is documentation-only.
if [ "${RUN_ANDROID_BUILD:-}" = "false" ]; then
  require_success_or_skipped "build-and-unit-test" "${BUILD_AND_UNIT_TEST:-}"
  require_success_or_skipped "e2e-tests" "${E2E_TESTS:-}"
else
  require_success "build-and-unit-test" "${BUILD_AND_UNIT_TEST:-}"
  require_success "e2e-tests" "${E2E_TESTS:-}"
fi
require_success_or_skipped "rust-daemon-build-and-test" "${RUST_DAEMON:-}"
require_success_or_skipped "installer-tests" "${INSTALLER_TESTS:-}"
require_success_or_skipped "docker-vm-e2e" "${DOCKER_VM_E2E:-}"

exit "$fail"
