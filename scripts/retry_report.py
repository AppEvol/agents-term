"""Represent isolated attempts in a logical Allure result and summarize its history."""

import html
import json
from pathlib import Path
from typing import Any, Dict, Iterator, List, Tuple

from test_retries import MAX_RETRIES, recovered


def label_value(result: Dict[str, Any], name: str, default: str = "") -> str:
    for label in result.get("labels", []):
        if label.get("name") == name and label.get("value"):
            return str(label["value"])
    return default


def fold_retries(directory: Path) -> int:
    """Fold only explicitly numbered attempts, retaining their evidence as Allure steps."""
    originals: Dict[Tuple[str, str], List[Path]] = {}
    retries: Dict[Tuple[str, str], Dict[int, Path]] = {}
    count = 0
    for path in directory.glob("*-result.json"):
        data = json.loads(path.read_text(encoding="utf-8"))
        key = (label_value(data, "parentSuite"), data.get("historyId", ""))
        number = label_value(data, "retryAttempt")
        if not number:
            originals.setdefault(key, []).append(path)
            count += 1
            continue
        index = int(number)
        attempts = retries.setdefault(key, {})
        if not key[1] or index in attempts or not 1 <= index <= MAX_RETRIES:
            raise ValueError(f"Invalid or duplicate retry attempt {number} for {key}")
        attempts[index] = path

    for key, attempts in retries.items():
        if len(originals.get(key, [])) != 1 or sorted(attempts) != list(range(1, len(attempts) + 1)):
            raise ValueError(f"Missing or ambiguous suite result or retry sequence for {key}")
        path = originals[key][0]
        original = json.loads(path.read_text(encoding="utf-8"))
        if original["status"] not in ("failed", "broken"):
            raise ValueError(f"Unexpected isolated retry for {original['status']} test {key}")
        ordered = [original] + [json.loads(attempts[i].read_text(encoding="utf-8")) for i in sorted(attempts)]
        steps = []
        for index, attempt in enumerate(ordered):
            step = {
                k: attempt[k]
                for k in ("status", "statusDetails", "start", "stop", "steps", "attachments")
                if k in attempt
            }
            step["name"] = f"Isolated retry {index}" if index else "Initial suite run"
            steps.append(step)
        original["steps"] = steps
        original.pop("attachments", None)
        original["labels"].extend(
            [
                {"name": "initialStatus", "value": original["status"]},
                {"name": "retryCount", "value": str(len(attempts))},
            ]
        )
        if recovered([step["status"] for step in steps[1:]]):
            original["status"] = "passed"
            original["statusDetails"] = {"flaky": True}
            original["labels"].append({"name": "tag", "value": "recovered on retry"})
        original["stop"] = max(step.get("stop", 0) for step in steps)
        path.write_text(json.dumps(original, indent=2, sort_keys=True), encoding="utf-8")
        for attempt_path in attempts.values():
            attempt_path.unlink()
    return count


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


def write_flakiness_page(output: Path, rows: List[Dict[str, Any]]) -> None:
    table_rows = []
    for row in rows:
        if row["first_failures"]:
            flag = " · repeated recovery" if row["recovered"] >= 2 else ""
            table_rows.append(
                f"<tr><td>{html.escape(row['suite'])}<br><code>{html.escape(row['name'])}</code>{flag}</td>"
                f"<td>{row['first_failures']} / {row['executed']}</td><td>{row['recovered']}</td>"
                f"<td>{row['unresolved']}</td></tr>"
            )
    body = (
        "<table><thead><tr><th>Test and variant</th><th>First failures / executions</th>"
        "<th>Recovered</th><th>Unresolved</th></tr></thead><tbody>" + "".join(table_rows) + "</tbody></table>"
        if table_rows
        else "<p>No first-run failures recorded in the retained history.</p>"
    )
    output.write_text(
        '<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" '
        'content="width=device-width, initial-scale=1"><title>CI test flakiness</title>'
        "<style>body{font:1rem system-ui;max-width:70rem;margin:auto;padding:1rem;color:#25292e}"
        "table{width:100%;border-collapse:collapse}td,th{text-align:left;padding:.5em;border-bottom:1px solid #ccc}"
        "code{overflow-wrap:anywhere}</style><main><a href='./'>Allure report</a><h1>CI test flakiness</h1>"
        "<p>Counts cover the retained report history. Each test and variant counts once per run in which it executed. "
        "Repeated recovery means at least two runs failed in the suite and recovered in isolation.</p>"
        + body
        + "</main></html>\n",
        encoding="utf-8",
    )
