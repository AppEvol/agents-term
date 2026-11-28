"""Unit tests for test_utils_py runners (unittest, cargo, process streaming)."""

from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any, Dict, List

from test_utils_py import REPO_ROOT
from test_utils_py.process import stream_command
from test_utils_py.runners.cargo_runner import parse_cargo_test_output

ALLURE_RESULTS_SCRIPT = REPO_ROOT / "scripts" / "allure_results.py"


def _read_results(results_dir: Path) -> List[Dict[str, Any]]:
    return [json.loads(p.read_text(encoding="utf-8")) for p in sorted(results_dir.glob("*-result.json"))]


def _label_map(result: Dict[str, Any]) -> Dict[str, List[str]]:
    mapping: Dict[str, List[str]] = {}
    for label in result.get("labels", []):
        mapping.setdefault(label["name"], []).append(label["value"])
    return mapping


class ProcessHelperTest(unittest.TestCase):
    def test_stream_command_captures_output_and_writes_log(self) -> None:
        with tempfile.TemporaryDirectory(prefix="agentsterm-proc-") as tmp:
            log_path = Path(tmp) / "run.log"
            code, output = stream_command(
                [sys.executable, "-c", 'import sys; print("out-line"); print("err-line", file=sys.stderr)'],
                log_path=log_path,
            )
            self.assertEqual(code, 0)
            self.assertIn("out-line", output)
            self.assertIn("err-line", output)
            self.assertEqual(log_path.read_text(encoding="utf-8"), output)


class UnittestRunnerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="agentsterm-allure-ut-"))
        self.tests_dir = self.tmp / "suite"
        self.tests_dir.mkdir()

    def tearDown(self) -> None:
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _write_sample_suite(self, *, include_failure: bool) -> None:
        fail_body = "self.assertEqual(1, 2)" if include_failure else "self.assertEqual(2, 2)"
        err_body = 'raise RuntimeError("boom")' if include_failure else "self.assertTrue(True)"
        sub_fail = "self.assertEqual(i, 0)" if include_failure else "self.assertGreaterEqual(i, 0)"
        (self.tests_dir / "test_sample.py").write_text(
            "\n".join(
                [
                    "import unittest",
                    "",
                    "class SampleCase(unittest.TestCase):",
                    "    def test_pass(self):",
                    "        self.assertTrue(True)",
                    "",
                    "    def test_fail(self):",
                    f"        {fail_body}",
                    "",
                    "    def test_error(self):",
                    f"        {err_body}",
                    "",
                    "    @unittest.skip('not applicable here')",
                    "    def test_skip(self):",
                    "        pass",
                    "",
                    "    def test_subtests(self):",
                    "        for i in (0, 1):",
                    "            with self.subTest(i=i):",
                    f"                {sub_fail}",
                    "",
                ]
            ),
            encoding="utf-8",
        )

    def test_emits_all_statuses_and_propagates_failure_exit_code(self) -> None:
        self._write_sample_suite(include_failure=True)
        out_dir = self.tmp / "out-fail"
        proc = subprocess.run(
            [
                sys.executable,
                "-W",
                "error::ResourceWarning",
                str(ALLURE_RESULTS_SCRIPT),
                "unittest",
                "--start-dir",
                str(self.tests_dir),
                "--output",
                str(out_dir),
                "--parent-suite",
                "Sample Suite",
                "--suite",
                "Python 3.13",
                "--variant",
                "py3.13",
                "--param",
                "python=3.13",
                "--tag",
                "installer",
            ],
            capture_output=True,
            text=True,
            timeout=30,
        )
        self.assertEqual(proc.returncode, 1, proc.stdout + proc.stderr)
        results = {r["name"]: r for r in _read_results(out_dir)}
        self.assertEqual(
            {name: r["status"] for name, r in results.items()},
            {
                "test_pass": "passed",
                "test_fail": "failed",
                "test_error": "broken",
                "test_skip": "skipped",
                "test_subtests": "failed",
            },
        )
        self.assertIn("AssertionError", results["test_fail"]["statusDetails"]["message"])
        self.assertIn("RuntimeError: boom", results["test_error"]["statusDetails"]["message"])
        self.assertEqual(results["test_skip"]["statusDetails"]["message"], "not applicable here")
        self.assertEqual(len(results["test_subtests"]["steps"]), 2)
        labels = _label_map(results["test_pass"])
        self.assertEqual(labels["parentSuite"], ["Sample Suite"])
        self.assertEqual(labels["suite"], ["Python 3.13"])
        self.assertEqual(labels["subSuite"], ["SampleCase"])
        self.assertEqual(labels["tag"], ["installer"])
        self.assertEqual(results["test_pass"]["parameters"], [{"name": "python", "value": "3.13"}])
        self.assertGreaterEqual(results["test_pass"]["stop"], results["test_pass"]["start"])

    def test_matrix_shards_have_distinct_history_and_testcase_ids(self) -> None:
        self._write_sample_suite(include_failure=False)
        out_a = self.tmp / "shard-a"
        out_b = self.tmp / "shard-b"

        for out_dir, variant, distro in (
            (out_a, "vm-ubuntu-2404", "vm-ubuntu-2404"),
            (out_b, "vm-alpine-321", "vm-alpine-321"),
        ):
            proc = subprocess.run(
                [
                    sys.executable,
                    "-W",
                    "error::ResourceWarning",
                    str(ALLURE_RESULTS_SCRIPT),
                    "unittest",
                    "--start-dir",
                    str(self.tests_dir),
                    "--output",
                    str(out_dir),
                    "--parent-suite",
                    "Linux VM E2E",
                    "--suite",
                    distro,
                    "--variant",
                    variant,
                    "--param",
                    f"vm_target={distro}",
                ],
                capture_output=True,
                text=True,
                timeout=30,
            )
            self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)

        res_a = {r["name"]: r for r in _read_results(out_a)}
        res_b = {r["name"]: r for r in _read_results(out_b)}
        self.assertNotEqual(res_a["test_pass"]["historyId"], res_b["test_pass"]["historyId"])
        self.assertNotEqual(res_a["test_pass"]["testCaseId"], res_b["test_pass"]["testCaseId"])
        self.assertNotEqual(res_a["test_pass"]["fullName"], res_b["test_pass"]["fullName"])

    def test_emits_setup_class_error_without_start_test(self) -> None:
        (self.tests_dir / "test_fixture_err.py").write_text(
            "\n".join(
                [
                    "import unittest",
                    "",
                    "class BrokenFixtureCase(unittest.TestCase):",
                    "    @classmethod",
                    "    def setUpClass(cls):",
                    '        raise RuntimeError("fixture setup exploded")',
                    "",
                    "    def test_never_runs(self):",
                    "        self.assertTrue(True)",
                    "",
                ]
            ),
            encoding="utf-8",
        )
        out_dir = self.tmp / "out-fixture-err"
        proc = subprocess.run(
            [
                sys.executable,
                "-W",
                "error::ResourceWarning",
                str(ALLURE_RESULTS_SCRIPT),
                "unittest",
                "--start-dir",
                str(self.tests_dir),
                "--output",
                str(out_dir),
                "--parent-suite",
                "Fixture Suite",
            ],
            capture_output=True,
            text=True,
            timeout=30,
        )
        self.assertEqual(proc.returncode, 1, proc.stdout + proc.stderr)
        results = _read_results(out_dir)
        self.assertEqual(len(results), 1)
        self.assertEqual(results[0]["status"], "broken")
        self.assertIn("fixture setup exploded", results[0]["statusDetails"]["message"])


class CargoParserTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="agentsterm-allure-cargo-"))

    def tearDown(self) -> None:
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_parses_multi_target_show_output_including_failures_and_doctests(self) -> None:
        sample_log = """\
   Compiling agentsterm-daemon v0.3.0
    Finished `test` profile [unoptimized + debuginfo] target(s) in 1.20s
     Running unittests src/main.rs (target/debug/deps/agentsterm_daemon-abc)

running 3 tests
test util::tests::works_ok ... ok
test util::tests::panics_as_expected - should panic ... ok
test util::tests::skipped_case ... ignored, requires live device

successes:

---- util::tests::works_ok stdout ----
debug line from passing test


successes:
    util::tests::panics_as_expected
    util::tests::works_ok

test result: ok. 2 passed; 0 failed; 1 ignored; 0 measured; 0 filtered out; finished in 0.30s

     Running tests/daemon_integration.rs (target/debug/deps/daemon_integration-def)

running 2 tests
test healthy_case ... ok
test broken_case ... FAILED

failures:

---- broken_case stdout ----

thread 'broken_case' panicked at tests/daemon_integration.rs:42:9:
assertion `left == right` failed
  left: 1
 right: 2
note: run with `RUST_BACKTRACE=1` environment variable to display a backtrace


failures:
    broken_case

test result: FAILED. 1 passed; 1 failed; 0 ignored; 0 measured; 0 filtered out; finished in 1.00s

   Doc-tests agentsterm_daemon

running 0 tests

test result: ok. 0 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.00s
"""
        out_dir = self.tmp / "rust-allure"
        count = parse_cargo_test_output(
            output_text=sample_log,
            output_dir=out_dir,
            parent_suite="Rust VM Daemon",
            exit_code=101,
            tags=["rust-daemon"],
        )
        self.assertEqual(count, 5)
        results = {r["fullName"]: r for r in _read_results(out_dir)}

        ok_res = results["src/main.rs::util::tests::works_ok"]
        self.assertEqual(ok_res["status"], "passed")
        self.assertEqual(ok_res["name"], "works_ok")
        self.assertEqual(len(ok_res["attachments"]), 1)
        self.assertIn("debug line from passing test", (out_dir / ok_res["attachments"][0]["source"]).read_text())

        panic_ok = results["src/main.rs::util::tests::panics_as_expected"]
        self.assertEqual(panic_ok["status"], "passed")

        ignored = results["src/main.rs::util::tests::skipped_case"]
        self.assertEqual(ignored["status"], "skipped")
        self.assertEqual(ignored["statusDetails"]["message"], "requires live device")

        failed = results["tests/daemon_integration.rs::broken_case"]
        self.assertEqual(failed["status"], "failed")
        self.assertIn("panicked at tests/daemon_integration.rs:42:9", failed["statusDetails"]["message"])
        self.assertIn("assertion `left == right` failed", failed["statusDetails"]["trace"])
        self.assertEqual(_label_map(failed)["suite"], ["tests/daemon_integration.rs"])

    def test_compile_failure_emits_synthetic_broken_result(self) -> None:
        out_dir = self.tmp / "rust-compile-fail"
        count = parse_cargo_test_output(
            output_text="error[E0425]: cannot find value `foo` in this scope\n",
            output_dir=out_dir,
            parent_suite="Rust VM Daemon",
            exit_code=101,
        )
        self.assertEqual(count, 1)
        results = _read_results(out_dir)
        self.assertEqual(results[0]["status"], "broken")
        self.assertIn("E0425", results[0]["statusDetails"]["trace"])

    def test_partial_run_failure_after_passing_binary_emits_broken_result(self) -> None:
        sample_log = """\
     Running unittests src/main.rs (target/debug/deps/agentsterm_daemon-abc)

running 1 test
test util::tests::works_ok ... ok

test result: ok. 1 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.10s

error: test failed, to rerun pass `--test daemon_integration`
Caused by:
  process didn't exit successfully (signal: 11, SIGSEGV)
"""
        out_dir = self.tmp / "rust-partial-fail"
        count = parse_cargo_test_output(
            output_text=sample_log,
            output_dir=out_dir,
            parent_suite="Rust VM Daemon",
            exit_code=101,
        )
        self.assertEqual(count, 2)
        statuses = {r["name"]: r["status"] for r in _read_results(out_dir)}
        self.assertEqual(
            statuses,
            {
                "works_ok": "passed",
                "Cargo build / test execution": "broken",
            },
        )


if __name__ == "__main__":
    unittest.main()
