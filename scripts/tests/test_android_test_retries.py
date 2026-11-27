"""Android release gates must reject partial suites and ambiguous isolated executions."""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

RUNNER = Path(__file__).resolve().parents[1] / "android_test_retries.py"


class AndroidTestRetryTest(unittest.TestCase):
    def run_fixture(self, root: Path, scenario: str) -> subprocess.CompletedProcess:
        fake_gradle = root / "gradle"
        fake_gradle.write_text(
            """#!/usr/bin/env python3
import os, sys
from pathlib import Path
scenario = os.environ["SCENARIO"]
retry = any("RunnerArguments.class=" in arg for arg in sys.argv)
name = "other" if retry and scenario == "wrong-case" else "flaky"
path = Path("app/build/outputs/androidTest-results/connected")
path.mkdir(parents=True)
failure = "" if retry else "<failure>original</failure>"
(path / "TEST-device.xml").write_text(
    '<testsuites tests="1">'
    f'<testsuite tests="1"><testcase classname="Example" name="{name}">{failure}</testcase></testsuite>'
    '</testsuites>'
)
print("Starting 1 tests on emulator")
if scenario != "crashed":
    print("Finished 1 tests on emulator")
if not retry:
    print("Execution failed for task ':app:connectedDebugAndroidTest'.")
    print("> There were failing tests. See the report at: file:///test-report")
    sys.exit(1)
assert "-Pandroid.testInstrumentationRunnerArguments.class=Example#flaky" in sys.argv
assert "-Pandroid.testInstrumentationRunnerArguments.realVmDaemonPort=7591" in sys.argv
"""
        )
        fake_gradle.chmod(0o755)
        return subprocess.run(
            [
                sys.executable,
                RUNNER,
                "android-e2e",
                "-Pandroid.testInstrumentationRunnerArguments.realVmDaemonPort=7591",
            ],
            cwd=root,
            env={**os.environ, "PATH": f"{root}:{os.environ['PATH']}", "SCENARIO": scenario},
            capture_output=True,
            text=True,
        )

    def test_qualified_emulator_retries_and_incomplete_runs(self) -> None:
        for scenario, exit_code, count, attempts in (
            ("recovers", 0, 3, 2),
            ("wrong-case", 1, 1, 1),
            ("crashed", 1, 1, 0),
        ):
            with self.subTest(scenario=scenario), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                proc = self.run_fixture(root, scenario)
                self.assertEqual(proc.returncode, exit_code, proc.stdout + proc.stderr)
                raw = root / "app/build/ci-test-attempts/android-e2e"
                self.assertEqual(len(list(raw.glob("failure-*/attempt-*/results/TEST-device.xml"))), attempts)
                reports = [json.loads(p.read_text()) for p in root.glob("allure-results/android-e2e/*-result.json")]
                self.assertEqual(len(reports), count)
                self.assertEqual({r["fullName"] for r in reports}, {"Example.flaky"})
                self.assertEqual(sum(r["status"] == "failed" for r in reports), 1)
