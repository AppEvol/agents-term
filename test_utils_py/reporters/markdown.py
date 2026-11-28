"""Render GitHub-Flavored Markdown test summaries from merged Allure results."""

from __future__ import annotations

from pathlib import Path
from typing import Dict, List, Optional, Tuple

from test_utils_py.analytics.metrics import collect_metrics, format_duration_ms


def _escape_md_cell(value: str) -> str:
    return value.replace("|", "\\|").replace("\r\n", " ").replace("\n", " ").strip()


def build_markdown_summary(
    results_dir: Path,
    *,
    artifact_name: str = "allure-report",
    report_url: str = "",
    dashboard_url: str = "",
) -> Tuple[str, int]:
    """Build a GitHub-Flavored Markdown summary table from merged Allure results."""
    metrics = collect_metrics(results_dir)
    total_count = metrics["total"]
    if total_count == 0:
        lines = [
            "## Allure 3 Test Report",
            "",
            (
                "No test results were collected in this run "
                "(for example, a documentation-only change where test jobs were skipped)."
            ),
            "",
        ]
        return "\n".join(lines), 0

    suites: Dict[str, Dict[str, int]] = metrics["suites"]
    failures: List[Tuple[str, str, str, str]] = metrics["failures"]
    lines = [
        "## Allure 3 Test Report",
        "",
        "| Test Suite | Passed | Failed | Broken | Skipped | Total | Duration |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for suite_name in sorted(suites):
        s = suites[suite_name]
        suite_total = s["passed"] + s["failed"] + s["broken"] + s["skipped"]
        lines.append(
            f"| {_escape_md_cell(suite_name)} | {s['passed']} | {s['failed']} | {s['broken']} | "
            f"{s['skipped']} | {suite_total} | {format_duration_ms(s['duration_ms'])} |"
        )
    lines.append(
        f"| **Total** | **{metrics['passed']}** | **{metrics['failed']}** | **{metrics['broken']}** | "
        f"**{metrics['skipped']}** | **{total_count}** | **{metrics['duration_human']}** |"
    )
    lines.append("")

    lines.append(
        f"{metrics['first_passed']} passed first try · {metrics['recovered']} recovered on retry · "
        f"{metrics['unresolved']} unresolved · {metrics['attempts']} executed attempts."
    )
    if metrics["recovered_tests"]:
        lines.extend(["", "### Recovered on Retry", ""])
        lines.extend(f"- `{_escape_md_cell(name)}`" for name in metrics["recovered_tests"])
    lines.append("")

    if report_url:
        lines.append(
            f"- Full interactive Allure 3 Awesome report artifact: **`{artifact_name}`** ([workflow run]({report_url}))"
        )
    else:
        lines.append(f"- Full interactive Allure 3 Awesome report artifact: **`{artifact_name}`**")

    if dashboard_url:
        lines.append(f"- Hosted `main` Allure 3 Dashboard (GitHub Pages): {dashboard_url}")
        lines.append(f"- [Per-test flakiness history on main]({dashboard_url.rstrip('/')}/flakiness.html)")

    if failures:
        lines.extend(["", "### Failures & Broken Tests", ""])
        for status, suite_label, name, msg in failures[:30]:
            suffix = f" — `{_escape_md_cell(msg)}`" if msg else ""
            lines.append(
                f"- **[{status.upper()}]** `{_escape_md_cell(suite_label)}` :: `{_escape_md_cell(name)}`{suffix}"
            )
        if len(failures) > 30:
            lines.append(f"- ... and {len(failures) - 30} more (see the `{artifact_name}` HTML report)")

    lines.append("")
    return "\n".join(lines), total_count


def write_summary_outputs(
    markdown: str,
    count: int,
    *,
    step_summary: Optional[Path] = None,
    github_output: Optional[Path] = None,
) -> None:
    """Append rendered summary markdown and result count to GitHub Actions files if configured."""
    if step_summary is not None:
        step_summary.parent.mkdir(parents=True, exist_ok=True)
        with step_summary.open("a", encoding="utf-8") as fh:
            fh.write(markdown)
    if github_output is not None:
        github_output.parent.mkdir(parents=True, exist_ok=True)
        with github_output.open("a", encoding="utf-8") as fh:
            fh.write(f"result_count={count}\n")
