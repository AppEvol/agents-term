"""Unit tests for reporters (JUnit converter, merger/retry folding, Markdown, Allure CLI, CI pipeline)."""

from __future__ import annotations

import http.server
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import unittest
from pathlib import Path
from typing import Any, Dict, List

from test_utils_py import REPO_ROOT
from test_utils_py.analytics.badges import generate_badges
from test_utils_py.reporters.allure_cli import (
    ALLURE_VERSION,
    MIN_NODE_MAJOR,
    MIN_NODE_MINOR,
    generate_report,
    parse_node_version,
)
from test_utils_py.reporters.junit_converter import convert_junit_xml
from test_utils_py.reporters.markdown import build_markdown_summary
from test_utils_py.reporters.merger import merge_results
from test_utils_py.runners.cargo_runner import parse_cargo_test_output

ALLURERC_JSON = REPO_ROOT / "allurerc.json"
GENERATE_CI_PY = REPO_ROOT / "scripts" / "ci" / "generate_allure_report.py"
ALLURE_REPORT_PY = REPO_ROOT / "scripts" / "allure-report.py"


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

    def test_allure_cli_pins_allure3_and_node22(self) -> None:
        self.assertRegex(ALLURE_VERSION, r"^3\.\d+\.\d+$")
        self.assertEqual((MIN_NODE_MAJOR, MIN_NODE_MINOR), (22, 18))
        self.assertEqual(parse_node_version("v22.18.0"), (22, 18))
        self.assertEqual(parse_node_version("24.1.2\n"), (24, 1))

    def test_allure_report_py_entrypoint_supports_help(self) -> None:
        proc = subprocess.run(
            [sys.executable, str(ALLURE_REPORT_PY), "--help"],
            capture_output=True,
            text=True,
            timeout=15,
        )
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn("--results-dir", proc.stdout)
        self.assertIn("--open", proc.stdout)

    def test_allure_cli_auto_collects_local_junit_and_invokes_cached_allure_bin(self) -> None:
        with tempfile.TemporaryDirectory(prefix="agentsterm-allure-cli-") as tmp:
            root = Path(tmp)
            fake_repo = root / "repo"
            unit_xml = fake_repo / "app/build/test-results/testDebugUnitTest"
            unit_xml.mkdir(parents=True)
            (unit_xml / "TEST-Sample.xml").write_text(
                '<testsuite name="Sample" tests="1"><testcase classname="Sample" name="ok" time="0.1"/></testsuite>',
                encoding="utf-8",
            )
            fake_bin = root / "bin"
            fake_bin.mkdir()
            (fake_bin / "node").write_text("#!/usr/bin/env bash\necho v22.18.0\n", encoding="utf-8")
            (fake_bin / "node").chmod(0o755)
            (fake_bin / "npm").write_text("#!/usr/bin/env bash\nexit 0\n", encoding="utf-8")
            (fake_bin / "npm").chmod(0o755)

            cache_dir = root / "cache"
            allure_bin = cache_dir / "node_modules/.bin/allure"
            allure_bin.parent.mkdir(parents=True)
            recorded = root / "allure-args.json"
            allure_bin.write_text(
                f"""#!/usr/bin/env python3
import json, sys
from pathlib import Path
with open({str(recorded)!r}, "w", encoding="utf-8") as fh:
    json.dump(sys.argv[1:], fh)
out = Path(sys.argv[sys.argv.index("--output") + 1])
out.mkdir(parents=True, exist_ok=True)
(out / "index.html").write_text("<html>ok</html>", encoding="utf-8")
""",
                encoding="utf-8",
            )
            allure_bin.chmod(0o755)
            (cache_dir / ".allure-version").write_text(f"{ALLURE_VERSION}\n", encoding="utf-8")

            prev_path = os.environ.get("PATH", "")
            prev_cache = os.environ.get("ALLURE_CACHE_DIR")
            os.environ["PATH"] = f"{fake_bin}:{prev_path}"
            os.environ["ALLURE_CACHE_DIR"] = str(cache_dir)
            try:
                out_dir = root / "out-report"
                index_html = generate_report(
                    output_dir=out_dir,
                    config_file=ALLURERC_JSON,
                    repo_root=fake_repo,
                )
            finally:
                os.environ["PATH"] = prev_path
                if prev_cache is None:
                    os.environ.pop("ALLURE_CACHE_DIR", None)
                else:
                    os.environ["ALLURE_CACHE_DIR"] = prev_cache

            self.assertTrue(index_html.is_file())
            cli_args = json.loads(recorded.read_text(encoding="utf-8"))
            self.assertEqual(cli_args[0], "generate")
            self.assertIn("--clean", cli_args)


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
        count = convert_junit_xml(
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


class MergeSummaryAndCiReportTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="agentsterm-allure-merge-"))

    def tearDown(self) -> None:
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_merge_and_summary_render_markdown_table_and_metadata(self) -> None:
        shard1 = self.tmp / "raw" / "shard1"
        shard2 = self.tmp / "raw" / "shard2"
        parse_cargo_test_output(
            output_text=(
                "     Running unittests src/main.rs (bin)\n"
                "running 1 test\n"
                "test ok_one ... ok\n"
                "test result: ok. 1 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.50s\n"
            ),
            output_dir=shard1,
            parent_suite="Rust VM Daemon",
        )
        parse_cargo_test_output(
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
        count = merge_results(
            input_dirs=[self.tmp / "raw"],
            output_dir=merged,
            env_pairs=[("Repository", "AppEvol/agents-term"), ("Ref", "main")],
            executor={"name": "GitHub Actions", "type": "github", "buildOrder": "42"},
        )
        self.assertEqual(count, 2)
        self.assertIn("Repository=AppEvol/agents-term", (merged / "environment.properties").read_text(encoding="utf-8"))
        executor = json.loads((merged / "executor.json").read_text(encoding="utf-8"))
        self.assertEqual(executor["buildOrder"], "42")

        md, total = build_markdown_summary(
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
        self.assertEqual(merge_results(input_dirs=[raw], output_dir=merged), 3)
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
        metrics = generate_badges(merged, out_dir)
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
            build_markdown_summary(merged)[0],
        )

    def test_generate_allure_report_py_bundles_pages_assets_and_caps_history(self) -> None:
        raw_dir = self.tmp / "raw" / "shard"
        parse_cargo_test_output(
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
            [sys.executable, "-W", "error::ResourceWarning", str(GENERATE_CI_PY)],
            env=env,
            capture_output=True,
            text=True,
            timeout=30,
        )
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        self.assertTrue((report_dir / "index.html").is_file())
        self.assertTrue((report_dir / ".nojekyll").is_file())
        self.assertTrue((report_dir / "metrics.json").is_file())
        self.assertTrue((report_dir / "flakiness.html").is_file())
        for badge in ("tests.svg", "pass-rate.svg", "suites.svg", "duration.svg", "recovered.svg"):
            self.assertTrue((report_dir / "badges" / badge).is_file())
        bundled_history = (report_dir / "history.jsonl").read_text(encoding="utf-8").splitlines()
        self.assertEqual(len(bundled_history), 3)
        self.assertIn("run-4", bundled_history[-1])
        self.assertIn("has_report=true", gh_output.read_text(encoding="utf-8"))
        self.assertIn("https://appevol.github.io/agents-term/", step_summary.read_text(encoding="utf-8"))

    def test_generate_allure_report_py_handles_zero_results_without_node(self) -> None:
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
            [sys.executable, "-W", "error::ResourceWarning", str(GENERATE_CI_PY)],
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

    def test_generate_allure_report_py_fetches_and_sanitizes_remote_history(self) -> None:
        raw_dir = self.tmp / "remote-raw" / "shard"
        parse_cargo_test_output(
            output_text=(
                "     Running unittests src/main.rs (bin)\n"
                "running 1 test\n"
                "test ok_one ... ok\n"
                "test result: ok. 1 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.10s\n"
            ),
            output_dir=raw_dir,
            parent_suite="Rust VM Daemon",
        )
        payload = "\n".join(
            [
                "corrupted-line",
                json.dumps({"uuid": "h-1"}),
                json.dumps({"uuid": "h-2"}),
                json.dumps({"uuid": "h-3"}),
            ]
        ).encode("utf-8")

        class _HistoryHandler(http.server.BaseHTTPRequestHandler):
            def do_GET(self) -> None:
                if self.path == "/history.jsonl":
                    self.send_response(200)
                    self.end_headers()
                    self.wfile.write(payload)
                else:
                    self.send_response(404)
                    self.end_headers()

            def log_message(self, format: str, *args: Any) -> None:
                del format, args

        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), _HistoryHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            stub_report = self.tmp / "fake-report-remote.sh"
            stub_report.write_text(
                "#!/usr/bin/env bash\n"
                "set -euo pipefail\n"
                'out=""\n'
                "while [ $# -gt 0 ]; do\n"
                '  if [ "$1" = "--output" ]; then out="$2"; shift 2; else shift; fi\n'
                "done\n"
                'mkdir -p "$out"\n'
                'printf "<html>ok</html>\\n" > "$out/index.html"\n',
                encoding="utf-8",
            )
            stub_report.chmod(0o755)

            history_file = self.tmp / "restored-history.jsonl"
            report_dir = self.tmp / "remote-report"
            env = {
                **os.environ,
                "ALLURE_RAW_DIR": str(self.tmp / "remote-raw"),
                "ALLURE_MERGED_DIR": str(self.tmp / "remote-merged"),
                "ALLURE_REPORT_DIR": str(report_dir),
                "ALLURE_HISTORY_FILE": str(history_file),
                "ALLURE_HISTORY_MAX_ENTRIES": "2",
                "ALLURE_PAGES_URL": f"http://127.0.0.1:{server.server_port}",
                "ALLURE_FETCH_HISTORY": "true",
                "ALLURE_REPORT_SCRIPT": str(stub_report),
            }
            proc = subprocess.run(
                [sys.executable, "-W", "error::ResourceWarning", str(GENERATE_CI_PY)],
                env=env,
                capture_output=True,
                text=True,
                timeout=30,
            )
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)

        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        restored = history_file.read_text(encoding="utf-8").splitlines()
        self.assertEqual(len(restored), 2)
        self.assertEqual(json.loads(restored[0])["uuid"], "h-2")
        self.assertEqual(json.loads(restored[1])["uuid"], "h-3")


if __name__ == "__main__":
    unittest.main()
