"""Unit tests for scripts/ci/ci_gate.sh."""

from __future__ import annotations

import os
import subprocess
import unittest
from pathlib import Path
from typing import Dict

REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "scripts" / "ci" / "ci_gate.sh"

ALL_GREEN_ENV: Dict[str, str] = {
    "STATIC_CHECKS": "success",
    "SCRIPT_TESTS": "success",
    "DAEMON_CHANGE_CHECK": "success",
    "RUST_DAEMON": "success",
    "BUILD_AND_UNIT_TEST": "success",
    "INSTALLER_TESTS": "success",
    "DOCKER_VM_E2E": "success",
    "E2E_TESTS": "success",
    "ALLURE_REPORT": "success",
    "RUN_ANDROID_BUILD": "true",
}


class CiGateTest(unittest.TestCase):
    def _run_gate(self, **overrides: str) -> subprocess.CompletedProcess:
        env = {**os.environ, **ALL_GREEN_ENV, **overrides}
        return subprocess.run([str(SCRIPT)], env=env, capture_output=True, text=True)

    def test_all_success_passes(self) -> None:
        proc = self._run_gate()
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)

    def test_optional_daemon_jobs_skipped_passes(self) -> None:
        proc = self._run_gate(
            RUST_DAEMON="skipped",
            INSTALLER_TESTS="skipped",
            DOCKER_VM_E2E="skipped",
        )
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)

    def test_docs_only_allows_skipped_android_jobs(self) -> None:
        proc = self._run_gate(
            RUN_ANDROID_BUILD="false",
            BUILD_AND_UNIT_TEST="skipped",
            E2E_TESTS="skipped",
            RUST_DAEMON="skipped",
            INSTALLER_TESTS="skipped",
            DOCKER_VM_E2E="skipped",
        )
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)

    def test_non_docs_rejects_skipped_android_jobs(self) -> None:
        for var, job in (
            ("BUILD_AND_UNIT_TEST", "build-and-unit-test"),
            ("E2E_TESTS", "e2e-tests"),
        ):
            with self.subTest(job=job):
                proc = self._run_gate(RUN_ANDROID_BUILD="true", **{var: "skipped"})
                self.assertNotEqual(proc.returncode, 0)
                self.assertIn(f"FAIL: {job} = skipped", proc.stderr)

    def test_required_jobs_must_succeed(self) -> None:
        for var, job in (
            ("STATIC_CHECKS", "static-checks"),
            ("SCRIPT_TESTS", "script-tests"),
            ("DAEMON_CHANGE_CHECK", "daemon-change-check"),
            ("ALLURE_REPORT", "allure-report"),
        ):
            for bad_state in ("failure", "skipped", "cancelled", ""):
                with self.subTest(job=job, bad_state=bad_state):
                    proc = self._run_gate(**{var: bad_state})
                    self.assertNotEqual(proc.returncode, 0)
                    self.assertIn(f"FAIL: {job} = {bad_state}", proc.stderr)

    def test_failed_optional_jobs_block_gate(self) -> None:
        for var, job in (
            ("RUST_DAEMON", "rust-daemon-build-and-test"),
            ("INSTALLER_TESTS", "installer-tests"),
            ("DOCKER_VM_E2E", "docker-vm-e2e"),
        ):
            for bad_state in ("failure", "cancelled"):
                with self.subTest(job=job, bad_state=bad_state):
                    proc = self._run_gate(**{var: bad_state})
                    self.assertNotEqual(proc.returncode, 0)
                    self.assertIn(f"FAIL: {job} = {bad_state}", proc.stderr)


if __name__ == "__main__":
    unittest.main()
