"""CI Allure 3 report pipeline: merge shards, summarize, restore history, generate HTML & Pages bundle."""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Dict, List, Mapping, Optional, Tuple

from test_utils_py import REPO_ROOT
from test_utils_py.analytics.badges import generate_badges
from test_utils_py.analytics.history import cap_history_file, filter_valid_jsonl_lines
from test_utils_py.reporters.allure_cli import generate_report
from test_utils_py.reporters.markdown import build_markdown_summary, write_summary_outputs
from test_utils_py.reporters.merger import merge_results


def _write_github_output(github_output: Optional[Path], key: str, value: str) -> None:
    if github_output is None:
        return
    github_output.parent.mkdir(parents=True, exist_ok=True)
    with github_output.open("a", encoding="utf-8") as fh:
        fh.write(f"{key}={value}\n")


def _restore_remote_history(dashboard_url: str, history_file: Path, max_entries: int) -> bool:
    history_url = f"{dashboard_url}history.jsonl"
    try:
        with urllib.request.urlopen(history_url, timeout=15) as resp:
            raw_text = resp.read().decode("utf-8", errors="replace")
    except (urllib.error.URLError, OSError, ValueError):
        print(f"No existing Allure 3 history at {history_url} (starting fresh).")
        return False

    valid_lines = filter_valid_jsonl_lines(raw_text, max_entries=max_entries)
    if valid_lines:
        history_file.parent.mkdir(parents=True, exist_ok=True)
        history_file.write_text("\n".join(valid_lines) + "\n", encoding="utf-8")
    print(f"Restored Allure 3 history from {history_url}")
    return True


def _resolve_path(raw: str, repo_root: Path) -> Path:
    path = Path(raw)
    return path if path.is_absolute() else repo_root / path


def run_ci_report(env: Optional[Mapping[str, str]] = None, *, repo_root: Path = REPO_ROOT) -> int:
    """Merge downloaded CI result shards, write summary, build Allure 3 report, and emit Pages assets."""
    cfg = dict(os.environ if env is None else env)

    raw_dir = _resolve_path(cfg.get("ALLURE_RAW_DIR") or "allure-results-raw", repo_root)
    merged_dir = _resolve_path(cfg.get("ALLURE_MERGED_DIR") or "allure-results", repo_root)
    report_dir = _resolve_path(cfg.get("ALLURE_REPORT_DIR") or "allure-report", repo_root)
    config_file = _resolve_path(cfg.get("ALLURE_CONFIG_FILE") or "allurerc.json", repo_root)
    artifact_name = cfg.get("ALLURE_ARTIFACT_NAME") or "allure-report"
    history_file = _resolve_path(cfg.get("ALLURE_HISTORY_FILE") or ".allure-history/history.jsonl", repo_root)
    history_max_entries = int(cfg.get("ALLURE_HISTORY_MAX_ENTRIES") or "30")
    pages_url = cfg.get("ALLURE_PAGES_URL") or ""
    report_script = cfg.get("ALLURE_REPORT_SCRIPT") or ""

    gh_server = cfg.get("GITHUB_SERVER_URL") or ""
    gh_repo = cfg.get("GITHUB_REPOSITORY") or ""
    gh_run_id = cfg.get("GITHUB_RUN_ID") or ""
    gh_ref = cfg.get("GITHUB_REF") or ""
    gh_ref_name = cfg.get("GITHUB_REF_NAME") or ""
    gh_sha = cfg.get("GITHUB_SHA") or ""
    gh_event = cfg.get("GITHUB_EVENT_NAME") or ""
    gh_workflow = cfg.get("GITHUB_WORKFLOW") or "Android CI"
    gh_run_number = cfg.get("GITHUB_RUN_NUMBER") or "0"
    step_summary = Path(cfg["GITHUB_STEP_SUMMARY"]) if cfg.get("GITHUB_STEP_SUMMARY") else None
    github_output = Path(cfg["GITHUB_OUTPUT"]) if cfg.get("GITHUB_OUTPUT") else None

    run_url = f"{gh_server}/{gh_repo}/actions/runs/{gh_run_id}" if (gh_server and gh_repo and gh_run_id) else ""
    dashboard_url = f"{pages_url.rstrip('/')}/" if pages_url else ""

    env_pairs: List[Tuple[str, str]] = []
    if gh_repo:
        env_pairs.append(("Repository", gh_repo))
    if gh_ref_name:
        env_pairs.append(("Ref", gh_ref_name))
    if gh_sha:
        env_pairs.append(("Commit", gh_sha))
    if gh_event:
        env_pairs.append(("Event", gh_event))
    if run_url:
        env_pairs.append(("WorkflowRun", run_url))

    executor: Optional[Dict[str, Any]] = None
    if run_url:
        executor = {
            "name": "GitHub Actions",
            "type": "github",
            "url": f"{gh_server}/{gh_repo}/actions",
            "buildName": f"{gh_workflow} #{gh_run_number}",
            "buildUrl": run_url,
            "buildOrder": gh_run_number,
        }

    if raw_dir.is_dir():
        count = merge_results(
            input_dirs=[raw_dir],
            output_dir=merged_dir,
            env_pairs=env_pairs,
            executor=executor,
        )
        print(f"Merged {count} Allure test result(s) into {merged_dir}")
    else:
        if merged_dir.exists():
            shutil.rmtree(merged_dir)
        merged_dir.mkdir(parents=True, exist_ok=True)

    result_count = sum(1 for _ in merged_dir.glob("*-result.json"))

    markdown, summary_count = build_markdown_summary(
        merged_dir,
        artifact_name=artifact_name,
        report_url=run_url,
        dashboard_url=dashboard_url,
    )
    sys.stdout.write(markdown)
    write_summary_outputs(
        markdown,
        summary_count,
        step_summary=step_summary,
        github_output=github_output,
    )

    if result_count == 0:
        print("No Allure test results found; skipping Allure 3 HTML generation.")
        _write_github_output(github_output, "has_report", "false")
        return 0

    should_fetch_history = cfg.get("ALLURE_FETCH_HISTORY") or ""
    if not should_fetch_history:
        should_fetch_history = (
            "true" if (gh_ref == "refs/heads/main" and gh_event in ("push", "workflow_dispatch")) else "false"
        )

    history_file.parent.mkdir(parents=True, exist_ok=True)
    if should_fetch_history == "true" and dashboard_url:
        _restore_remote_history(dashboard_url, history_file, history_max_entries)

    if report_script:
        subprocess.run(
            [
                report_script,
                "--results-dir",
                str(merged_dir),
                "--output",
                str(report_dir),
                "--config",
                str(config_file),
            ],
            cwd=str(repo_root),
            check=True,
        )
    else:
        generate_report(
            results_dirs=[merged_dir],
            output_dir=report_dir,
            config_file=config_file,
            repo_root=repo_root,
        )

    index_html = report_dir / "index.html"
    if not index_html.is_file():
        print(f"ERROR: expected {index_html} to be generated by Allure 3", file=sys.stderr)
        return 1

    # Bundle static site assets for GitHub Pages (history.jsonl, .nojekyll, SVG badges, metrics.json).
    (report_dir / ".nojekyll").write_text("", encoding="utf-8")

    if history_file.is_file():
        cap_history_file(history_file, report_dir / "history.jsonl", max_entries=history_max_entries)

    metrics = generate_badges(
        merged_dir,
        report_dir,
        commit_sha=gh_sha,
        run_url=run_url,
        dashboard_url=dashboard_url,
    )
    print(f"Generated SVG badges and metrics.json ({metrics['passed']}/{metrics['total']} passed) in {report_dir}")

    _write_github_output(github_output, "has_report", "true")
    return 0


def main() -> int:
    try:
        return run_ci_report()
    except RuntimeError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    except subprocess.CalledProcessError as exc:
        return exc.returncode or 1
