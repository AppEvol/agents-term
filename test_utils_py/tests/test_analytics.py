"""Unit tests for test_utils_py.analytics (metrics, history filtering/capping, SVG badges)."""

from __future__ import annotations

import json
import shutil
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

from test_utils_py.analytics.badges import generate_badges, render_svg_badge
from test_utils_py.analytics.history import cap_history_file, filter_valid_jsonl_lines
from test_utils_py.analytics.metrics import format_duration_ms
from test_utils_py.runners.cargo_runner import parse_cargo_test_output


class AnalyticsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="agentsterm-analytics-"))

    def tearDown(self) -> None:
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_format_duration_ms(self) -> None:
        self.assertEqual(format_duration_ms(250), "250ms")
        self.assertEqual(format_duration_ms(1500), "1.5s")
        self.assertEqual(format_duration_ms(125000), "2m 5.0s")

    def test_filter_and_cap_jsonl_history(self) -> None:
        raw = "\n".join(
            [
                "",
                "not-json",
                json.dumps({"uuid": "1"}),
                json.dumps({"uuid": "2"}),
                "{broken",
                json.dumps({"uuid": "3"}),
            ]
        )
        self.assertEqual(filter_valid_jsonl_lines(raw, max_entries=2), ['{"uuid": "2"}', '{"uuid": "3"}'])

        src = self.tmp / "src.jsonl"
        dst = self.tmp / "dst.jsonl"
        src.write_text("\n".join(json.dumps({"i": i}) for i in range(6)) + "\n", encoding="utf-8")
        kept = cap_history_file(src, dst, max_entries=3)
        self.assertEqual(kept, 3)
        self.assertEqual(len(dst.read_text(encoding="utf-8").splitlines()), 3)

    def test_generate_badges_emits_valid_svgs_and_metrics_json(self) -> None:
        results_dir = self.tmp / "badge-results"
        parse_cargo_test_output(
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
        metrics = generate_badges(
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
        parse_cargo_test_output(
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
        generate_badges(results_dir, out_dir)
        tests_svg = (out_dir / "badges" / "tests.svg").read_text(encoding="utf-8")
        rate_svg = (out_dir / "badges" / "pass-rate.svg").read_text(encoding="utf-8")
        suites_svg = (out_dir / "badges" / "suites.svg").read_text(encoding="utf-8")
        self.assertIn("1/2 passed", tests_svg)
        self.assertIn("#cb2431", tests_svg)
        self.assertIn("50%", rate_svg)
        self.assertIn("#cb2431", rate_svg)
        self.assertIn("0/1 green", suites_svg)
        self.assertIn("#cb2431", suites_svg)

        escaped = render_svg_badge("a&b", "<100%>", "#2ea44f")
        ET.fromstring(escaped)
        self.assertIn("a&amp;b", escaped)
        self.assertIn("&lt;100%&gt;", escaped)


if __name__ == "__main__":
    unittest.main()
