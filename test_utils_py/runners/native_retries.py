"""CI runner for Python unittest and Cargo suites with per-case isolated retries."""

from __future__ import annotations

import re
import subprocess
import sys
import tempfile
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Sequence

from test_utils_py import REPO_ROOT
from test_utils_py.reporters.allure_model import read_results, write_result
from test_utils_py.retry import record_attempt, retry_case
from test_utils_py.runners.cargo_runner import (
    CARGO_DOCTEST_RE,
    CARGO_RUNNING_RE,
    CARGO_SUMMARY_RE,
    normalize_cargo_target,
    run_cargo_tests,
)

CARGO_COUNTS = re.compile(r"(\d+) passed; (\d+) failed; (\d+) ignored; (\d+) measured; (\d+) filtered out;")
CARGO_SELECTOR = re.compile(r"error: (?:doc)?test failed, to rerun pass `(--(?:bin|test) [\w-]+|--lib|--doc)`")
ALLURE_RESULTS_ENTRY = REPO_ROOT / "scripts" / "allure_results.py"


def run_python(cmd: List[str], folder: Path) -> int:
    complete = folder / "unittest-complete"
    complete.unlink(missing_ok=True)
    code = subprocess.run([*cmd, "--completion-file", str(complete)], check=False).returncode
    if not complete.is_file():
        return 2
    complete.unlink()
    return code


def cargo_failed_targets(log: str, records: List[Dict[str, Any]]) -> Optional[Dict[str, List[str]]]:
    """Accept only completed libtest targets and ordinary Cargo test-failure diagnostics."""
    counts: Dict[str, Counter] = defaultdict(Counter)
    seen = set()
    for case in records:
        if case["historyId"] in seen:
            return None
        seen.add(case["historyId"])
        suite = next(label["value"] for label in case["labels"] if label["name"] == "suite")
        counts[suite][case["status"]] += 1
    selectors: Dict[str, List[str]] = {}
    target = None
    failed = None
    for line in log.splitlines():
        running = CARGO_RUNNING_RE.fullmatch(line)
        docs = CARGO_DOCTEST_RE.fullmatch(line)
        if running or docs:
            if target or failed:
                return None
            target = normalize_cargo_target(running[1]) if running else f"Doc-tests ({docs[1].strip()})"
        elif summary := CARGO_SUMMARY_RE.fullmatch(line):
            summary_counts = CARGO_COUNTS.search(line)
            if target is None or summary_counts is None:
                return None
            passed, failures, ignored, measured, _filtered = map(int, summary_counts.groups())
            if measured or (summary[1] == "FAILED") != bool(failures):
                return None
            observed = counts.pop(target, Counter())
            if (observed["passed"], observed["failed"], observed["skipped"]) != (passed, failures, ignored):
                return None
            failed = target if summary[1] == "FAILED" else None
            target = None
        elif target is None and line.startswith("error:"):
            selector = CARGO_SELECTOR.fullmatch(line)
            if selector and failed:
                selectors[failed] = selector[1].split()
                failed = None
            elif line != f"error: {len(selectors)} target{'s' if len(selectors) != 1 else ''} failed:":
                return None
    return None if target or failed or counts else selectors


def retry_results(
    failures: List[Dict[str, Any]],
    output: Path,
    execute: Callable[[Dict[str, Any], Path], Optional[int]],
    failure_code: int,
) -> int:
    def retry(result: Dict[str, Any], attempt: int) -> Optional[str]:
        with tempfile.TemporaryDirectory(dir=output.parent) as scratch:
            folder = Path(scratch)
            code = execute(result, folder)
            records = read_results(folder)
            if len(records) == 1 and records[0]["historyId"] == result["historyId"]:
                case = records[0]
                status = case["status"]
                expected_code = failure_code if status in ("failed", "broken") else 0
                if code == expected_code and status in ("passed", "failed", "broken"):
                    record_attempt(case, attempt)
                    write_result(output, case)
                    for attachment in folder.glob("*-attachment*"):
                        attachment.rename(output / attachment.name)
                    return status
            # Keep partial reports/fixture diagnostics with the raw shard artifact.
            # The Allure merger scans recursively, so use a separate file suffix.
            evidence = output / "invalid-attempts" / result["uuid"] / str(attempt)
            evidence.mkdir(parents=True)
            for path in folder.iterdir():
                path.rename(evidence / (path.name + ".raw"))
            print(f"Invalid isolated execution for {result['fullName']} (exit {code})", file=sys.stderr)
            return None

    recovered_all = True
    for result in failures:
        if not retry_case(result["fullName"], lambda attempt, case=result: retry(case, attempt)):
            recovered_all = False
    return 0 if recovered_all and failures else 1


def main(argv: Optional[Sequence[str]] = None) -> int:
    from test_utils_py.cli import build_parser

    raw_argv = list(sys.argv[1:] if argv is None else argv)
    args = build_parser().parse_args(raw_argv)
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    if list(output.glob("*-result.json")):
        sys.exit("CI result directory must be empty before the suite starts")
    if args.subcommand == "unittest":
        warn_flags = [f"-W{option}" for option in sys.warnoptions]
        cmd = [sys.executable, *warn_flags, str(ALLURE_RESULTS_ENTRY), *raw_argv]
        code = run_python(cmd, output)
        if code != 1:
            return code
        prefix = f"[{args.variant}] " if args.variant else ""

        def isolated_python(case: Dict[str, Any], folder: Path) -> int:
            test_id = case["fullName"][len(prefix) :]
            return run_python([*cmd, "--test-id", test_id, "--output", str(folder)], folder)

        records = read_results(output)
        if len({case["historyId"] for case in records}) != len(records):
            print("Duplicate unittest case IDs prevent isolated recovery", file=sys.stderr)
            return 1
        failures = [case for case in records if case["status"] in ("failed", "broken")]
        return retry_results(failures, output, isolated_python, failure_code=1)
    if args.subcommand != "cargo":
        sys.exit("Expected unittest or cargo suite")
    cmd = args.command[1:]
    if cmd != ["cargo", "test", "--no-fail-fast", "--", "--show-output"]:
        sys.exit("Expected cargo test --no-fail-fast -- --show-output")
    code, log = run_cargo_tests(
        cmd,
        output,
        cwd=args.cwd,
        parent_suite=args.parent_suite,
        tags=args.tag,
    )
    records = read_results(output)
    selectors = cargo_failed_targets(log, records)
    if code == 0:
        return 0 if records and selectors == {} else 1
    if code != 101:
        return code
    failures = [case for case in records if case["status"] in ("failed", "broken")]
    by_target = {case["fullName"].split("::", 1)[0] for case in failures}
    if not selectors or by_target != selectors.keys():
        return code

    def run_rust(case: Dict[str, Any], folder: Path) -> Optional[int]:
        target, name = case["fullName"].split("::", 1)
        isolated = ["cargo", "test", *selectors[target], "--", "--exact", name, "--show-output"]
        attempt_code, attempt_log = run_cargo_tests(
            isolated,
            folder,
            cwd=args.cwd,
            parent_suite=args.parent_suite,
            tags=args.tag,
        )
        failed = cargo_failed_targets(attempt_log, read_results(folder))
        return attempt_code if failed is not None and (attempt_code == 0 or target in failed) else None

    return retry_results(failures, output, run_rust, failure_code=101)
