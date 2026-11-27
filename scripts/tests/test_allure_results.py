"""Unit tests for the Allure 3 result emitters, converters, merger, and summary scripts."""

from __future__ import annotations

import importlib.util
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any, Dict, List

REPO_ROOT = Path(__file__).resolve().parents[2]
ALLURE_RESULTS_SCRIPT = REPO_ROOT / "scripts" / "allure_results.py"
ALLURE_REPORT_SH = REPO_ROOT / "scripts" / "allure-report.sh"
GENERATE_CI_SH = REPO_ROOT / "scripts" / "ci" / "generate_allure_report.sh"
ALLURERC_JSON = REPO_ROOT / "allurerc.json"


def _load_allure_results_module() -> Any:
    spec = importlib.util.spec_from_file_location("allure_results", ALLURE_RESULTS_SCRIPT)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.path.insert(0, str(ALLURE_RESULTS_SCRIPT.parent))
    try:
        spec.loader.exec_module(module)
    finally:
        sys.path.pop(0)
    return module


allure_results = _load_allure_results_module()


def _read_results(results_dir: Path) -> List[Dict[str, Any]]:
    return [json.loads(p.read_text(encoding="utf-8")) for p in sorted(results_dir.glob("*-result.json"))]


def _label_map(result: Dict[str, Any]) -> Dict[str, List[str]]:
    mapping: Dict[str, List[str]] = {}
    for label in result.get("labels", []):
        mapping.setdefault(label["name"], []).append(label["value"])
    return mapping


class AllureConfigAndVersionTest(unittest.TestCase):
    def test_allurerc_json_matches_allure3_schema(self) -> None:
        cfg = json.loads(ALLURERC_JSON.read_text(encoding="utf-8"))
        self.assertEqual(cfg["name"], "AgentsTerm Test Report")
        self.assertEqual(cfg["output"], "./allure-report")
        self.assertEqual(cfg["historyPath"], "./.allure-history/history.jsonl")
        self.assertTrue(cfg["appendHistory"])
        awesome_opts = cfg["plugins"]["awesome"]["options"]
        self.assertTrue(awesome_opts["singleFile"])
        self.assertEqual(awesome_opts["groupBy"], ["parentSuite", "suite", "subSuite"])
        self.assertFalse(cfg["plugins"]["agent"]["enabled"])

    def test_allure_report_sh_pins_allure3_and_node22(self) -> None:
        text = ALLURE_REPORT_SH.read_text(encoding="utf-8")
        self.assertRegex(text, r'ALLURE_VERSION="3\.\d+\.\d+"')
        self.assertIn("MIN_NODE_MAJOR=22", text)
        self.assertIn("MIN_NODE_MINOR=18", text)


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


class JunitConverterTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="agentsterm-allure-junit-"))

    def tearDown(self) -> None:
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_converts_junit_xml_and_attaches_roborazzi_diffs(self) -> None:
        xml_dir = self.tmp / "xml"
        xml_dir.mkdir()
        (xml_dir / "TEST-MarkdownGoldenTest.xml").write_text(
            """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="com.appevol.agentsterm.ui.markdown.MarkdownGoldenTest" tests="3" failures="1" errors="1" skipped="1" timestamp="2026-11-26T18:00:00Z">
  <testcase name="matchesGolden[inline_styles on COMPACT_320]" classname="com.appevol.agentsterm.ui.markdown.MarkdownGoldenTest" time="0.42">
    <failure message="Golden mismatch">AssertionError: images differ</failure>
    <system-out>Rendered markdown sample</system-out>
  </testcase>
  <testcase name="matchesGolden[tables on PIXEL_412]" classname="com.appevol.agentsterm.ui.markdown.MarkdownGoldenTest" time="0.15">
    <error message="IllegalStateException">boom trace</error>
  </testcase>
  <testcase name="matchesGolden[lists on LARGE_448]" classname="com.appevol.agentsterm.ui.markdown.MarkdownGoldenTest" time="0.00">
    <skipped message="skipped on host" />
  </testcase>
</testsuite>
""",
            encoding="utf-8",
        )
        roborazzi_dir = self.tmp / "roborazzi" / "nested"
        roborazzi_dir.mkdir(parents=True)
        (roborazzi_dir / "inline_styles__compact_320_compare.png").write_bytes(b"\x89PNG\r\n\x1a\nfake")
        (roborazzi_dir / "inline_styles__compact_320_actual.png").write_bytes(b"\x89PNG\r\n\x1a\nactual")

        out_dir = self.tmp / "allure-out"
        count = allure_results.convert_junit_xml(
            input_path=xml_dir,
            output_dir=out_dir,
            parent_suite="Android Unit Tests",
            roborazzi_dir=self.tmp / "roborazzi",
            tags=["android-unit"],
        )
        self.assertEqual(count, 3)

        results = {r["name"]: r for r in _read_results(out_dir)}
        failed = results["matchesGolden[inline_styles on COMPACT_320]"]
        self.assertEqual(failed["status"], "failed")
        self.assertEqual(failed["stop"] - failed["start"], 420)
        attachment_names = [a["name"] for a in failed["attachments"]]
        self.assertIn("System output", attachment_names)
        self.assertIn("Golden diff (inline_styles__compact_320)", attachment_names)
        self.assertIn("Actual rendering (inline_styles__compact_320)", attachment_names)
        for att in failed["attachments"]:
            self.assertTrue((out_dir / att["source"]).is_file())

        self.assertEqual(results["matchesGolden[tables on PIXEL_412]"]["status"], "broken")
        self.assertEqual(results["matchesGolden[lists on LARGE_448]"]["status"], "skipped")
        labels = _label_map(failed)
        self.assertEqual(labels["parentSuite"], ["Android Unit Tests"])
        self.assertEqual(labels["suite"], ["MarkdownGoldenTest"])
        self.assertEqual(labels["package"], ["com.appevol.agentsterm.ui.markdown"])
        self.assertEqual(labels["tag"], ["android-unit"])


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
        count = allure_results.parse_cargo_test_output(
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
        count = allure_results.parse_cargo_test_output(
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
        count = allure_results.parse_cargo_test_output(
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


class MergeSummaryAndCiScriptTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="agentsterm-allure-merge-"))

    def tearDown(self) -> None:
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_merge_and_summary_render_markdown_table_and_metadata(self) -> None:
        shard1 = self.tmp / "raw" / "shard1"
        shard2 = self.tmp / "raw" / "shard2"
        allure_results.parse_cargo_test_output(
            output_text=(
                "     Running unittests src/main.rs (bin)\n"
                "running 1 test\n"
                "test ok_one ... ok\n"
                "test result: ok. 1 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.50s\n"
            ),
            output_dir=shard1,
            parent_suite="Rust VM Daemon",
        )
        allure_results.parse_cargo_test_output(
            output_text=(
                "     Running tests/app_cli.rs (bin)\n"
                "running 1 test\n"
                "test bad_one ... FAILED\n"
                "failures:\n"
                "---- bad_one stdout ----\n"
                "thread 'bad_one' panicked at tests/app_cli.rs:10:5:\n"
                "boom\n"
                "test result: FAILED. 0 passed; 1 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.25s\n"
            ),
            output_dir=shard2,
            parent_suite="Android Unit Tests",
            exit_code=101,
        )

        merged = self.tmp / "merged"
        count = allure_results.merge_results(
            input_dirs=[self.tmp / "raw"],
            output_dir=merged,
            env_pairs=[("Repository", "AppEvol/agents-term"), ("Ref", "main")],
            executor={"name": "GitHub Actions", "type": "github", "buildOrder": "42"},
        )
        self.assertEqual(count, 2)
        self.assertIn("Repository=AppEvol/agents-term", (merged / "environment.properties").read_text(encoding="utf-8"))
        executor = json.loads((merged / "executor.json").read_text(encoding="utf-8"))
        self.assertEqual(executor["buildOrder"], "42")

        md, total = allure_results.build_markdown_summary(
            merged,
            artifact_name="allure-report",
            report_url="https://github.com/AppEvol/agents-term/actions/runs/123",
            dashboard_url="https://appevol.github.io/agents-term/",
        )
        self.assertEqual(total, 2)
        self.assertIn("| Android Unit Tests | 0 | 1 | 0 | 0 | 1 |", md)
        self.assertIn("| Rust VM Daemon | 1 | 0 | 0 | 0 | 1 |", md)
        self.assertIn("| **Total** | **1** | **1** | **0** | **0** | **2** |", md)
        self.assertIn("https://appevol.github.io/agents-term/", md)
        self.assertIn("### Failures & Broken Tests", md)
        self.assertIn("bad_one", md)

    def test_merge_keeps_attempt_evidence_and_counts_logical_verdicts(self) -> None:
        raw = self.tmp / "runs"
        raw.mkdir()
        results = [
            ("linux", ["failed", "broken", "passed", "passed"]),
            ("mac", ["failed", "failed", "passed"]),
            ("ok", ["passed"]),
        ]
        for variant, statuses in results:
            for index, status in enumerate(statuses):
                labels = [{"name": "parentSuite", "value": "Installer"}]
                if index:
                    labels.append({"name": "retryAttempt", "value": str(index)})
                payload = {
                    "historyId": variant,
                    "name": "test_install",
                    "fullName": f"[{variant}] test_install",
                    "status": status,
                    "start": 10 * index,
                    "stop": 10 * index + 2,
                    "labels": labels,
                    "attachments": [{"source": "some-attachment.txt", "name": "logs", "type": "text/plain"}],
                    "statusDetails": {"message": status},
                    "steps": [{"name": "fixture", "status": status}],
                }
                (raw / f"{variant}-{index}-result.json").write_text(json.dumps(payload), encoding="utf-8")
        (raw / "some-attachment.txt").write_text("log", encoding="utf-8")
        merged = self.tmp / "merged"
        self.assertEqual(allure_results.merge_results(input_dirs=[raw], output_dir=merged), 3)
        cases = {r["historyId"]: r for r in _read_results(merged)}
        self.assertTrue(cases["linux"]["statusDetails"]["flaky"])
        self.assertEqual(cases["mac"]["status"], "failed")
        self.assertEqual(cases["mac"]["statusDetails"]["message"], "failed")
        self.assertEqual([step["status"] for step in cases["linux"]["steps"]], results[0][1])
        self.assertEqual(cases["linux"]["steps"][0]["steps"][0]["name"], "fixture")
        self.assertTrue((merged / cases["linux"]["steps"][0]["attachments"][0]["source"]).is_file())
        self.assertEqual(len(_read_results(raw)), 8)

        out_dir = self.tmp / "report"
        out_dir.mkdir()
        # Allure 3 history stores the final verdict, per-test labels, and matrix-specific history IDs.
        (out_dir / "history.jsonl").write_text(
            "\n".join(
                json.dumps({"testResults": entries})
                for entries in [
                    {"linux": {"status": "skipped"}},
                    {"linux": {**cases["linux"], "labels": [], "status": "failed"}},
                    {"linux": cases["linux"]},
                    cases,
                ]
            )
            + "\n",
            encoding="utf-8",
        )
        metrics = allure_results.generate_badges(merged, out_dir)
        for key, expected in {
            "total": 3,
            "first_passed": 1,
            "recovered": 1,
            "unresolved": 1,
            "attempts": 8,
            "passed": 2,
            "duration_ms": 16,
        }.items():
            self.assertEqual(metrics[key], expected, key)
        linux = next(row for row in metrics["test_history"] if row["name"] == "[linux] test_install")
        self.assertEqual(
            (linux["executed"], linux["first_failures"], linux["recovered"], linux["unresolved"]), (3, 3, 2, 1)
        )
        self.assertIn("repeated recovery", (out_dir / "flakiness.html").read_text(encoding="utf-8"))
        self.assertIn(
            "1 passed first try · 1 recovered on retry · 1 unresolved · 8 executed attempts",
            allure_results.build_markdown_summary(merged)[0],
        )

    def test_generate_badges_emits_valid_svgs_and_metrics_json(self) -> None:
        results_dir = self.tmp / "badge-results"
        allure_results.parse_cargo_test_output(
            output_text=(
                "     Running unittests src/main.rs (bin)\n"
                "running 2 tests\n"
                "test ok_one ... ok\n"
                "test ok_two ... ok\n"
                "test result: ok. 2 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 1.50s\n"
            ),
            output_dir=results_dir,
            parent_suite="Rust VM Daemon",
        )
        out_dir = self.tmp / "pages-out"
        metrics = allure_results.generate_badges(
            results_dir,
            out_dir,
            commit_sha="abc1234",
            run_url="https://github.com/AppEvol/agents-term/actions/runs/99",
            dashboard_url="https://appevol.github.io/agents-term/",
        )
        self.assertEqual(metrics["total"], 2)
        self.assertEqual(metrics["passed"], 2)
        self.assertEqual(metrics["pass_rate_pct"], 100.0)
        self.assertEqual(metrics["suites_passing"], 1)
        self.assertEqual(metrics["suites_total"], 1)

        loaded_metrics = json.loads((out_dir / "metrics.json").read_text(encoding="utf-8"))
        self.assertEqual(loaded_metrics["commit"], "abc1234")
        self.assertEqual(loaded_metrics["dashboard_url"], "https://appevol.github.io/agents-term/")

        for name, expected_text in (
            ("tests.svg", "2 passed"),
            ("pass-rate.svg", "100%"),
            ("suites.svg", "1/1 green"),
            ("duration.svg", "1.5s"),
        ):
            svg_text = (out_dir / "badges" / name).read_text(encoding="utf-8")
            root = ET.fromstring(svg_text)
            self.assertTrue(root.tag.endswith("svg"))
            self.assertIn(expected_text, svg_text)
            self.assertIn("#2ea44f" if name != "duration.svg" else "#0969da", svg_text)

    def test_generate_badges_marks_failures_red_and_escapes_xml(self) -> None:
        results_dir = self.tmp / "badge-fail"
        allure_results.parse_cargo_test_output(
            output_text=(
                "     Running tests/app_cli.rs (bin)\n"
                "running 2 tests\n"
                "test good_case ... ok\n"
                "test bad_case ... FAILED\n"
                "test result: FAILED. 1 passed; 1 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.40s\n"
            ),
            output_dir=results_dir,
            parent_suite="Rust VM Daemon",
            exit_code=101,
        )
        out_dir = self.tmp / "pages-fail"
        allure_results.generate_badges(results_dir, out_dir)
        tests_svg = (out_dir / "badges" / "tests.svg").read_text(encoding="utf-8")
        rate_svg = (out_dir / "badges" / "pass-rate.svg").read_text(encoding="utf-8")
        suites_svg = (out_dir / "badges" / "suites.svg").read_text(encoding="utf-8")
        self.assertIn("1/2 passed", tests_svg)
        self.assertIn("#cb2431", tests_svg)
        self.assertIn("50%", rate_svg)
        self.assertIn("#cb2431", rate_svg)
        self.assertIn("0/1 green", suites_svg)
        self.assertIn("#cb2431", suites_svg)

        escaped = allure_results.render_svg_badge("a&b", "<100%>", "#2ea44f")
        ET.fromstring(escaped)
        self.assertIn("a&amp;b", escaped)
        self.assertIn("&lt;100%&gt;", escaped)

    def test_generate_allure_report_sh_bundles_pages_assets_and_caps_history(self) -> None:
        raw_dir = self.tmp / "raw" / "shard"
        allure_results.parse_cargo_test_output(
            output_text=(
                "     Running unittests src/main.rs (bin)\n"
                "running 1 test\n"
                "test ok_one ... ok\n"
                "test result: ok. 1 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.20s\n"
            ),
            output_dir=raw_dir,
            parent_suite="Rust VM Daemon",
        )
        history_file = self.tmp / "history.jsonl"
        history_file.write_text(
            "\n".join(json.dumps({"uuid": f"run-{i}"}) for i in range(5)) + "\n",
            encoding="utf-8",
        )
        stub_report_sh = self.tmp / "fake-allure-report.sh"
        stub_report_sh.write_text(
            "#!/usr/bin/env bash\n"
            "set -euo pipefail\n"
            'out=""\n'
            "while [ $# -gt 0 ]; do\n"
            '  if [ "$1" = "--output" ]; then out="$2"; shift 2; else shift; fi\n'
            "done\n"
            'mkdir -p "$out"\n'
            'printf "<html><body>ok</body></html>\\n" > "$out/index.html"\n',
            encoding="utf-8",
        )
        stub_report_sh.chmod(0o755)

        merged_dir = self.tmp / "merged"
        report_dir = self.tmp / "report"
        step_summary = self.tmp / "summary.md"
        gh_output = self.tmp / "output.txt"
        env = {
            **os.environ,
            "ALLURE_RAW_DIR": str(self.tmp / "raw"),
            "ALLURE_MERGED_DIR": str(merged_dir),
            "ALLURE_REPORT_DIR": str(report_dir),
            "ALLURE_HISTORY_FILE": str(history_file),
            "ALLURE_HISTORY_MAX_ENTRIES": "3",
            "ALLURE_PAGES_URL": "https://appevol.github.io/agents-term",
            "ALLURE_FETCH_HISTORY": "false",
            "ALLURE_REPORT_SCRIPT": str(stub_report_sh),
            "GITHUB_STEP_SUMMARY": str(step_summary),
            "GITHUB_OUTPUT": str(gh_output),
        }
        proc = subprocess.run(
            ["bash", str(GENERATE_CI_SH)],
            env=env,
            capture_output=True,
            text=True,
            timeout=30,
        )
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        self.assertTrue((report_dir / "index.html").is_file())
        self.assertTrue((report_dir / ".nojekyll").is_file())
        self.assertTrue((report_dir / "metrics.json").is_file())
        for badge in ("tests.svg", "pass-rate.svg", "suites.svg", "duration.svg"):
            self.assertTrue((report_dir / "badges" / badge).is_file())
        bundled_history = (report_dir / "history.jsonl").read_text(encoding="utf-8").splitlines()
        self.assertEqual(len(bundled_history), 3)
        self.assertIn("run-4", bundled_history[-1])
        self.assertIn("has_report=true", gh_output.read_text(encoding="utf-8"))
        self.assertIn("https://appevol.github.io/agents-term/", step_summary.read_text(encoding="utf-8"))

    def test_generate_allure_report_sh_handles_zero_results_without_node(self) -> None:
        raw_dir = self.tmp / "empty-raw"
        raw_dir.mkdir()
        merged_dir = self.tmp / "empty-merged"
        report_dir = self.tmp / "empty-report"
        step_summary = self.tmp / "step_summary.md"
        gh_output = self.tmp / "gh_output.txt"

        env = {
            **os.environ,
            "ALLURE_RAW_DIR": str(raw_dir),
            "ALLURE_MERGED_DIR": str(merged_dir),
            "ALLURE_REPORT_DIR": str(report_dir),
            "GITHUB_STEP_SUMMARY": str(step_summary),
            "GITHUB_OUTPUT": str(gh_output),
        }
        proc = subprocess.run(
            ["bash", str(GENERATE_CI_SH)],
            env=env,
            capture_output=True,
            text=True,
            timeout=30,
        )
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        self.assertIn("No test results were collected", step_summary.read_text(encoding="utf-8"))
        out_text = gh_output.read_text(encoding="utf-8")
        self.assertIn("result_count=0", out_text)
        self.assertIn("has_report=false", out_text)
        self.assertFalse(report_dir.exists())


if __name__ == "__main__":
    unittest.main()
