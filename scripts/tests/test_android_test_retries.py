"""Android release gates must reject partial suites and ambiguous isolated executions."""

from __future__ import annotations

import fcntl
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from typing import Dict, Optional

SCRIPTS_DIR = Path(__file__).resolve().parents[1]
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import android_test_retries  # noqa: E402 - scripts/ is added to sys.path above

RUNNER = SCRIPTS_DIR / "android_test_retries.py"


def _wait_for_path(path: Path, timeout: float = 10.0) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if path.exists():
            return
        time.sleep(0.02)
    raise AssertionError(f"Timed out waiting for {path}")


def _write_sync_gradle(root: Path, suite: str) -> None:
    results_dir = (
        "app/build/test-results/testDebugUnitTest"
        if suite == "android-unit"
        else "app/build/outputs/androidTest-results/connected"
    )
    extra_output = (
        'print("Starting 1 tests on emulator")\nprint("Finished 1 tests on emulator")\n'
        if suite == "android-e2e"
        else ""
    )
    fake_gradle = root / "gradle"
    fake_gradle.write_text(
        f"""#!/usr/bin/env python3
import os, sys, time
from pathlib import Path

run_id = os.environ["RUN_ID"]
sync_dir = Path(os.environ["SYNC_DIR"])
events = sync_dir / "events.log"
with events.open("a", encoding="utf-8") as log:
    log.write(f"enter:{{run_id}}\\n")
(sync_dir / f"{{run_id}}-entered").write_text("ok", encoding="utf-8")

wait_for = os.environ.get("WAIT_FOR_FILE")
if wait_for:
    target = Path(wait_for)
    deadline = time.monotonic() + 10.0
    while not target.exists():
        if time.monotonic() >= deadline:
            print(f"Timed out waiting for {{target}}", file=sys.stderr)
            sys.exit(1)
        time.sleep(0.02)

path = Path({results_dir!r})
path.mkdir(parents=True, exist_ok=True)
(path / "TEST-Example.xml").write_text(
    '<testsuite tests="1"><testcase classname="Example" name="ok"/></testsuite>',
    encoding="utf-8",
)
{extra_output}
with events.open("a", encoding="utf-8") as log:
    log.write(f"exit:{{run_id}}\\n")
""",
        encoding="utf-8",
    )
    fake_gradle.chmod(0o755)


class AndroidTestRetryTest(unittest.TestCase):
    def _runner_env(self, root: Path, lock_dir: Path, extra: Optional[Dict[str, str]] = None) -> Dict[str, str]:
        env = {
            **os.environ,
            "PATH": f"{root}:{os.environ['PATH']}",
            "AGENTSTERM_LOCK_DIR": str(lock_dir),
        }
        if extra:
            env.update(extra)
        return env

    def run_fixture(self, root: Path, lock_dir: Path, scenario: str) -> subprocess.CompletedProcess:
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
                str(RUNNER),
                "android-e2e",
                "-Pandroid.testInstrumentationRunnerArguments.realVmDaemonPort=7591",
            ],
            cwd=root,
            env=self._runner_env(root, lock_dir, {"SCENARIO": scenario}),
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
                base = Path(tmp)
                root = base / "checkout"
                root.mkdir()
                lock_dir = base / "locks"
                proc = self.run_fixture(root, lock_dir, scenario)
                self.assertEqual(proc.returncode, exit_code, proc.stdout + proc.stderr)
                raw = root / "app/build/ci-test-attempts/android-e2e"
                self.assertEqual(len(list(raw.glob("failure-*/attempt-*/results/TEST-device.xml"))), attempts)
                reports = [json.loads(p.read_text()) for p in root.glob("allure-results/android-e2e/*-result.json")]
                self.assertEqual(len(reports), count)
                self.assertEqual({r["fullName"] for r in reports}, {"Example.flaky"})
                self.assertEqual(sum(r["status"] == "failed" for r in reports), 1)

    def test_unit_runs_serialize_within_same_checkout(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            root = base / "checkout"
            sync_dir = base / "sync"
            lock_dir = base / "locks"
            root.mkdir()
            sync_dir.mkdir()
            _write_sync_gradle(root, "android-unit")
            release = sync_dir / "release-first"

            env_first = self._runner_env(
                root,
                lock_dir,
                {"RUN_ID": "first", "SYNC_DIR": str(sync_dir), "WAIT_FOR_FILE": str(release)},
            )
            env_second = self._runner_env(
                root,
                lock_dir,
                {"RUN_ID": "second", "SYNC_DIR": str(sync_dir)},
            )

            with subprocess.Popen(
                [sys.executable, str(RUNNER), "android-unit"],
                cwd=root,
                env=env_first,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
            ) as first:
                _wait_for_path(sync_dir / "first-entered")
                with subprocess.Popen(
                    [sys.executable, str(RUNNER), "android-unit"],
                    cwd=root,
                    env=env_second,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    text=True,
                ) as second:
                    assert second.stderr is not None
                    notice = second.stderr.readline()
                    self.assertIn("waiting for lock", notice)
                    self.assertIn("in this checkout", notice)
                    self.assertFalse((sync_dir / "second-entered").exists())
                    release.write_text("go", encoding="utf-8")
                    out2, err2 = second.communicate(timeout=15)
                out1, err1 = first.communicate(timeout=15)

            self.assertEqual(first.returncode, 0, out1 + err1)
            self.assertEqual(second.returncode, 0, out2 + notice + err2)
            events = (sync_dir / "events.log").read_text(encoding="utf-8").splitlines()
            self.assertEqual(events, ["enter:first", "exit:first", "enter:second", "exit:second"])

    def test_unit_runs_in_different_checkouts_run_in_parallel(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            root_a = base / "worktree-a"
            root_b = base / "worktree-b"
            sync_dir = base / "sync"
            lock_dir = base / "locks"
            for directory in (root_a, root_b, sync_dir):
                directory.mkdir()
            _write_sync_gradle(root_a, "android-unit")
            _write_sync_gradle(root_b, "android-unit")

            env_a = self._runner_env(
                root_a,
                lock_dir,
                {
                    "RUN_ID": "a",
                    "SYNC_DIR": str(sync_dir),
                    "WAIT_FOR_FILE": str(sync_dir / "b-entered"),
                },
            )
            env_b = self._runner_env(
                root_b,
                lock_dir,
                {
                    "RUN_ID": "b",
                    "SYNC_DIR": str(sync_dir),
                    "WAIT_FOR_FILE": str(sync_dir / "a-entered"),
                },
            )

            with subprocess.Popen(
                [sys.executable, str(RUNNER), "android-unit"],
                cwd=root_a,
                env=env_a,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
            ) as proc_a:
                with subprocess.Popen(
                    [sys.executable, str(RUNNER), "android-unit"],
                    cwd=root_b,
                    env=env_b,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    text=True,
                ) as proc_b:
                    out_b, err_b = proc_b.communicate(timeout=15)
                out_a, err_a = proc_a.communicate(timeout=15)

            self.assertEqual(proc_a.returncode, 0, out_a + err_a)
            self.assertEqual(proc_b.returncode, 0, out_b + err_b)
            self.assertNotIn("waiting for lock", err_a)
            self.assertNotIn("waiting for lock", err_b)

    def test_e2e_runs_serialize_across_different_checkouts_on_same_vm(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            root_a = base / "worktree-a"
            root_b = base / "worktree-b"
            sync_dir = base / "sync"
            lock_dir = base / "locks"
            for directory in (root_a, root_b, sync_dir):
                directory.mkdir()
            _write_sync_gradle(root_a, "android-e2e")
            _write_sync_gradle(root_b, "android-e2e")
            release = sync_dir / "release-first"

            env_a = self._runner_env(
                root_a,
                lock_dir,
                {"RUN_ID": "first", "SYNC_DIR": str(sync_dir), "WAIT_FOR_FILE": str(release)},
            )
            env_b = self._runner_env(
                root_b,
                lock_dir,
                {"RUN_ID": "second", "SYNC_DIR": str(sync_dir)},
            )

            with subprocess.Popen(
                [sys.executable, str(RUNNER), "android-e2e"],
                cwd=root_a,
                env=env_a,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
            ) as first:
                _wait_for_path(sync_dir / "first-entered")
                with subprocess.Popen(
                    [sys.executable, str(RUNNER), "android-e2e"],
                    cwd=root_b,
                    env=env_b,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    text=True,
                ) as second:
                    assert second.stderr is not None
                    notice = second.stderr.readline()
                    self.assertIn("waiting for lock", notice)
                    self.assertIn("on this machine", notice)
                    self.assertFalse((sync_dir / "second-entered").exists())
                    release.write_text("go", encoding="utf-8")
                    out2, err2 = second.communicate(timeout=15)
                out1, err1 = first.communicate(timeout=15)

            self.assertEqual(first.returncode, 0, out1 + err1)
            self.assertEqual(second.returncode, 0, out2 + notice + err2)
            events = (sync_dir / "events.log").read_text(encoding="utf-8").splitlines()
            self.assertEqual(events, ["enter:first", "exit:first", "enter:second", "exit:second"])

    def test_lock_outside_build_survives_build_directory_deletion(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            root = base / "checkout"
            sync_dir = base / "sync"
            lock_dir = base / "locks"
            build_dir = root / "app/build/ci-test-attempts"
            for directory in (root, sync_dir, build_dir):
                directory.mkdir(parents=True)
            _write_sync_gradle(root, "android-unit")

            prev_lock_dir = os.environ.get("AGENTSTERM_LOCK_DIR")
            os.environ["AGENTSTERM_LOCK_DIR"] = str(lock_dir)
            try:
                lock_path = android_test_retries.lock_path_for("android-unit", cwd=root)
            finally:
                if prev_lock_dir is None:
                    os.environ.pop("AGENTSTERM_LOCK_DIR", None)
                else:
                    os.environ["AGENTSTERM_LOCK_DIR"] = prev_lock_dir

            self.assertNotIn((root / "app/build").resolve(), lock_path.resolve().parents)

            with lock_path.open("a+", encoding="utf-8") as held:
                fcntl.flock(held, fcntl.LOCK_EX)
                # Simulating `gradle clean` while a lock is held must not break serialization.
                shutil.rmtree(root / "app/build")
                with subprocess.Popen(
                    [sys.executable, str(RUNNER), "android-unit"],
                    cwd=root,
                    env=self._runner_env(root, lock_dir, {"RUN_ID": "after-clean", "SYNC_DIR": str(sync_dir)}),
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    text=True,
                ) as proc:
                    assert proc.stderr is not None
                    notice = proc.stderr.readline()
                    self.assertIn("waiting for lock", notice)
                    self.assertFalse((sync_dir / "after-clean-entered").exists())
                    fcntl.flock(held, fcntl.LOCK_UN)
                    out, err = proc.communicate(timeout=15)

            self.assertEqual(proc.returncode, 0, out + notice + err)
            events = (sync_dir / "events.log").read_text(encoding="utf-8").splitlines()
            self.assertEqual(events, ["enter:after-clean", "exit:after-clean"])
