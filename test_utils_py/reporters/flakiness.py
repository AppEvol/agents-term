"""Render the static HTML CI flakiness report page."""

from __future__ import annotations

import html
from pathlib import Path
from typing import Any, Dict, List


def write_flakiness_page(output: Path, rows: List[Dict[str, Any]]) -> None:
    """Render a standalone HTML table summarizing per-test first-run failures and recoveries."""
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
