"""Allure 3 history JSONL validation, capping, and per-test flakiness analytics."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Dict, Iterator, List, Tuple

from test_utils_py.reporters.allure_model import label_value


def filter_valid_jsonl_lines(raw_text: str, *, max_entries: int) -> List[str]:
    """Keep only valid JSON lines from raw JSONL text, capped to the most recent entries."""
    valid_lines: List[str] = []
    for raw_line in raw_text.splitlines():
        line = raw_line.strip()
        if not line:
            continue
        try:
            json.loads(line)
        except ValueError:
            continue
        valid_lines.append(line)
    if max_entries <= 0:
        return []
    return valid_lines[-max_entries:]


def cap_history_file(src: Path, dst: Path, *, max_entries: int) -> int:
    """Copy the last `max_entries` lines from a history JSONL file into `dst`."""
    if not src.is_file() or max_entries <= 0:
        return 0
    lines = src.read_text(encoding="utf-8", errors="replace").splitlines()
    kept = lines[-max_entries:]
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_text("\n".join(kept) + ("\n" if kept else ""), encoding="utf-8")
    return len(kept)


def _historical_results(history: Path) -> Iterator[Tuple[str, Dict[str, Any]]]:
    if history.is_file():
        with history.open(encoding="utf-8") as stream:
            for line in stream:
                if line.strip():
                    yield from json.loads(line).get("testResults", {}).items()


def history_metrics(history: Path) -> List[Dict[str, Any]]:
    """Count executed runs per history identity over the dashboard's retained window."""
    rows: Dict[str, Dict[str, Any]] = {}
    for key, test in _historical_results(history):
        final = test.get("status")
        if final not in ("passed", "broken", "failed"):
            continue
        row = rows.setdefault(
            key,
            {
                "name": test.get("fullName") or test.get("name") or key,
                "suite": label_value(test, "parentSuite"),
                "executed": 0,
                "first_failures": 0,
                "recovered": 0,
                "unresolved": 0,
            },
        )
        row["executed"] += 1
        if label_value(test, "initialStatus", final) in ("failed", "broken"):
            row["first_failures"] += 1
            row["recovered" if final == "passed" else "unresolved"] += 1
    return sorted(rows.values(), key=lambda row: (-row["recovered"], -row["first_failures"], row["name"]))
