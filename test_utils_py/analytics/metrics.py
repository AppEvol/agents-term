"""Aggregate pass/fail/broken/skipped counts, retry recoveries, and durations from Allure results."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from test_utils_py.reporters.allure_model import label_value


def format_duration_ms(duration_ms: int) -> str:
    if duration_ms < 1000:
        return f"{duration_ms}ms"
    seconds = duration_ms / 1000.0
    if seconds < 60:
        return f"{seconds:.1f}s"
    minutes = int(seconds // 60)
    rem_seconds = seconds - minutes * 60
    return f"{minutes}m {rem_seconds:.1f}s"


def collect_metrics(results_dir: Path) -> Dict[str, Any]:
    """Aggregate pass/fail/broken/skipped counts and durations from merged Allure results."""
    result_files = sorted(results_dir.glob("*-result.json")) if results_dir.is_dir() else []
    suites: Dict[str, Dict[str, int]] = {}
    totals = {"passed": 0, "failed": 0, "broken": 0, "skipped": 0, "duration_ms": 0}
    recovered_tests: List[str] = []
    first_passed = 0
    retry_count = 0
    failures: List[Tuple[str, str, str, str]] = []

    for path in result_files:
        data = json.loads(path.read_text(encoding="utf-8"))
        parent = label_value(data, "parentSuite", "Other")
        suite = label_value(data, "suite", "")
        status = str(data.get("status", "broken"))
        if status not in ("passed", "failed", "broken", "skipped"):
            status = "broken"
        start = int(data.get("start") or 0)
        stop = int(data.get("stop") or start)
        duration_ms = max(0, stop - start)
        initial = label_value(data, "initialStatus", status)
        retries = int(label_value(data, "retryCount", "0"))
        if retries:
            # Exclude time spent waiting for other cases between isolated executions.
            duration_ms = sum(max(0, step["stop"] - step["start"]) for step in data["steps"])
        retry_count += retries
        first_passed += int(initial == "passed")
        if status == "passed" and initial in ("failed", "broken"):
            recovered_tests.append(data.get("fullName") or data["name"])

        bucket = suites.setdefault(
            parent,
            {"passed": 0, "failed": 0, "broken": 0, "skipped": 0, "duration_ms": 0},
        )
        bucket[status] += 1
        bucket["duration_ms"] += duration_ms
        totals[status] += 1
        totals["duration_ms"] += duration_ms

        if status in ("failed", "broken"):
            details = data.get("statusDetails") or {}
            msg = str(details.get("message") or details.get("trace") or "").splitlines()
            first_line = msg[0] if msg else ""
            suite_label = f"{parent} / {suite}" if suite else parent
            failures.append((status, suite_label, str(data.get("name", "unnamed")), first_line[:160]))

    total_count = totals["passed"] + totals["failed"] + totals["broken"] + totals["skipped"]
    executed_count = totals["passed"] + totals["failed"] + totals["broken"]
    pass_rate_pct: Optional[float] = (
        round((totals["passed"] / executed_count) * 100.0, 1) if executed_count > 0 else None
    )
    suites_total = len(suites)
    suites_passing = sum(1 for s in suites.values() if s["failed"] == 0 and s["broken"] == 0)

    return {
        "total": total_count,
        "executed": executed_count,
        "passed": totals["passed"],
        "failed": totals["failed"],
        "broken": totals["broken"],
        "skipped": totals["skipped"],
        "pass_rate_pct": pass_rate_pct,
        "duration_ms": totals["duration_ms"],
        "duration_human": format_duration_ms(totals["duration_ms"]),
        "first_passed": first_passed,
        "recovered": len(recovered_tests),
        "recovered_tests": sorted(recovered_tests),
        "unresolved": totals["failed"] + totals["broken"],
        "retry_count": retry_count,
        "attempts": executed_count + retry_count,
        "suites_total": suites_total,
        "suites_passing": suites_passing,
        "suites": suites,
        "failures": failures,
    }
