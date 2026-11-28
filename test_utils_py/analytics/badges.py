"""Render self-hosted shields-style SVG metric badges, `metrics.json`, and `flakiness.html`."""

from __future__ import annotations

import html
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict

from test_utils_py.analytics.history import history_metrics
from test_utils_py.analytics.metrics import collect_metrics
from test_utils_py.reporters.flakiness import write_flakiness_page


def _estimate_svg_text_width(text: str) -> int:
    width = 0.0
    for ch in text:
        if ch in "ilI1.,|:;'! ":
            width += 4.2
        elif ch in "mwMW@":
            width += 8.6
        elif ch.isupper():
            width += 7.1
        else:
            width += 6.2
    return max(24, round(width) + 12)


def render_svg_badge(label: str, value: str, color: str) -> str:
    """Render a self-contained flat SVG badge (shields.io style, 20px tall)."""
    label_w = _estimate_svg_text_width(label)
    value_w = _estimate_svg_text_width(value)
    total_w = label_w + value_w
    label_x = label_w * 5
    value_x = round((label_w + value_w / 2.0) * 10)
    label_len = (label_w - 10) * 10
    value_len = (value_w - 10) * 10

    esc_label = html.escape(label)
    esc_value = html.escape(value)
    esc_color = html.escape(color)
    esc_title = html.escape(f"{label}: {value}")

    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{total_w}" height="20" role="img" aria-label="{esc_title}">'
        f"<title>{esc_title}</title>"
        '<linearGradient id="s" x2="0" y2="100%">'
        '<stop offset="0" stop-color="#bbb" stop-opacity=".1"/>'
        '<stop offset="1" stop-opacity=".1"/>'
        "</linearGradient>"
        '<clipPath id="r">'
        f'<rect width="{total_w}" height="20" rx="3" fill="#fff"/>'
        "</clipPath>"
        '<g clip-path="url(#r)">'
        f'<rect width="{label_w}" height="20" fill="#555"/>'
        f'<rect x="{label_w}" width="{value_w}" height="20" fill="{esc_color}"/>'
        f'<rect width="{total_w}" height="20" fill="url(#s)"/>'
        "</g>"
        '<g fill="#fff" text-anchor="middle" font-family="Verdana,Geneva,DejaVu Sans,sans-serif" '
        'text-rendering="geometricPrecision" font-size="110">'
        f'<text aria-hidden="true" x="{label_x}" y="150" fill="#010101" fill-opacity=".3" '
        f'transform="scale(.1)" textLength="{label_len}">{esc_label}</text>'
        f'<text x="{label_x}" y="140" transform="scale(.1)" fill="#fff" textLength="{label_len}">{esc_label}</text>'
        f'<text aria-hidden="true" x="{value_x}" y="150" fill="#010101" fill-opacity=".3" '
        f'transform="scale(.1)" textLength="{value_len}">{esc_value}</text>'
        f'<text x="{value_x}" y="140" transform="scale(.1)" fill="#fff" textLength="{value_len}">'
        f"{esc_value}</text>"
        "</g>"
        "</svg>\n"
    )


def generate_badges(
    results_dir: Path,
    output_dir: Path,
    *,
    commit_sha: str = "",
    run_url: str = "",
    dashboard_url: str = "",
) -> Dict[str, Any]:
    """Generate self-hosted SVG metric badges, `flakiness.html`, and `metrics.json`."""
    metrics = collect_metrics(results_dir)
    badges_dir = output_dir / "badges"
    badges_dir.mkdir(parents=True, exist_ok=True)

    total = metrics["total"]
    passed = metrics["passed"]
    failing = metrics["failed"] + metrics["broken"]
    pass_rate = metrics["pass_rate_pct"]
    suites_total = metrics["suites_total"]
    suites_passing = metrics["suites_passing"]

    if total == 0:
        tests_val, tests_color = "no results", "#6e7781"
    elif failing == 0:
        tests_val, tests_color = f"{passed} passed", "#2ea44f"
    else:
        tests_val, tests_color = f"{passed}/{total} passed", "#cb2431"

    if pass_rate is None:
        rate_val, rate_color = "n/a", "#6e7781"
    else:
        rate_str = f"{int(pass_rate)}%" if pass_rate == int(pass_rate) else f"{pass_rate:.1f}%"
        if failing == 0:
            rate_color = "#2ea44f"
        elif pass_rate >= 90.0:
            rate_color = "#dbab09"
        else:
            rate_color = "#cb2431"
        rate_val = rate_str

    if suites_total == 0:
        suites_val, suites_color = "0 suites", "#6e7781"
    elif suites_passing == suites_total:
        suites_val, suites_color = f"{suites_passing}/{suites_total} green", "#2ea44f"
    else:
        suites_val, suites_color = f"{suites_passing}/{suites_total} green", "#cb2431"

    duration_val = metrics["duration_human"] if total > 0 else "n/a"
    duration_color = "#0969da" if total > 0 else "#6e7781"

    (badges_dir / "tests.svg").write_text(render_svg_badge("tests", tests_val, tests_color), encoding="utf-8")
    (badges_dir / "pass-rate.svg").write_text(render_svg_badge("pass rate", rate_val, rate_color), encoding="utf-8")
    (badges_dir / "suites.svg").write_text(render_svg_badge("suites", suites_val, suites_color), encoding="utf-8")
    (badges_dir / "duration.svg").write_text(
        render_svg_badge("test time", duration_val, duration_color),
        encoding="utf-8",
    )

    recovered_count = metrics["recovered"]
    (badges_dir / "recovered.svg").write_text(
        render_svg_badge("recovered on retry", str(recovered_count), "#dbab09" if recovered_count else "#2ea44f"),
        encoding="utf-8",
    )
    trends = history_metrics(output_dir / "history.jsonl")
    write_flakiness_page(output_dir / "flakiness.html", trends)
    public_metrics: Dict[str, Any] = {
        "first_passed": metrics["first_passed"],
        "recovered": recovered_count,
        "unresolved": metrics["unresolved"],
        "retry_count": metrics["retry_count"],
        "attempts": metrics["attempts"],
        "test_history": trends,
        "total": total,
        "executed": metrics["executed"],
        "passed": passed,
        "failed": metrics["failed"],
        "broken": metrics["broken"],
        "skipped": metrics["skipped"],
        "pass_rate_pct": pass_rate,
        "duration_ms": metrics["duration_ms"],
        "duration_human": duration_val,
        "suites_total": suites_total,
        "suites_passing": suites_passing,
        "suites": {name: dict(counts) for name, counts in sorted(metrics["suites"].items())},
        "commit": commit_sha,
        "run_url": run_url,
        "dashboard_url": dashboard_url,
        "updated_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }
    (output_dir / "metrics.json").write_text(
        json.dumps(public_metrics, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return public_metrics
