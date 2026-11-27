#!/usr/bin/env python3
"""CI entry point for Python unittest and Cargo suites with per-case isolated retries."""

from __future__ import annotations

import json
import re
import subprocess
import sys
import tempfile
from collections import Counter, defaultdict
from pathlib import Path
from typing import Callable, Dict, List, Optional, Sequence

import allure_results
from test_retries import record_attempt, retry_case


def run_python(cmd: List[str], folder: Path) -> int:
    complete = folder / "unittest-complete"
    code = subprocess.run([*cmd, "--completion-file", str(complete)], check=False).returncode
    if not complete.is_file():
        return 2
    complete.unlink()
    return code


CARGO_COUNTS = re.compile(r"(\d+) passed; (\d+) failed; (\d+) ignored; (\d+) measured; (\d+) filtered out;")
CARGO_SELECTOR = re.compile(r"error: (?:doc)?test failed, to rerun pass `(--(?:bin|test) [\w-]+|--lib|--doc)`")


def cargo_failed_targets(log: str, records: List[dict]) -> Optional[Dict[str, List[str]]]:
    """Accept only completed libtest targets and ordinary Cargo test-failure diagnostics."""
    counts = defaultdict(Counter)
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
        running = allure_results.CARGO_RUNNING_RE.fullmatch(line)
        docs = allure_results.CARGO_DOCTEST_RE.fullmatch(line)
        if running or docs:
            if target or failed:
                return None
            target = allure_results._normalize_cargo_target(running[1]) if running else f"Doc-tests ({docs[1].strip()})"
        elif summary := allure_results.CARGO_SUMMARY_RE.fullmatch(line):
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
        elif line.startswith("error:"):
            selector = CARGO_SELECTOR.fullmatch(line)
            if selector and failed:
                selectors[failed] = selector[1].split()
                failed = None
            elif line != f"error: {len(selectors)} target{'s' if len(selectors) != 1 else ''} failed:":
                return None
    return None if target or failed or counts else selectors


def read_results(folder: Path) -> List[dict]:
    return [json.loads(path.read_text(encoding="utf-8")) for path in sorted(folder.glob("*-result.json"))]


def retry_results(
    failures: List[dict],
    output: Path,
    execute: Callable[[dict, Path], Optional[int]],
    failure_code: int,
) -> int:
    def retry(result: dict, attempt: int) -> Optional[str]:
        with tempfile.TemporaryDirectory(dir=output) as scratch:
            folder = Path(scratch)
            code = execute(result, folder)
            records = read_results(folder)
            if len(records) == 1 and records[0]["historyId"] == result["historyId"]:
                case = records[0]
                status = case["status"]
                expected_code = failure_code if status in ("failed", "broken") else 0
                if code == expected_code and status in ("passed", "failed", "broken"):
                    record_attempt(case, attempt)
                    allure_results._write_result(output, case)
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

    recovered = True
    for result in failures:
        if not retry_case(result["fullName"], lambda attempt, case=result: retry(case, attempt)):
            recovered = False
    return 0 if recovered and failures else 1


def main(argv: Sequence[str]) -> int:
    args = allure_results.build_parser().parse_args(argv)
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    if list(output.glob("*-result.json")):
        sys.exit("CI result directory must be empty before the suite starts")
    if args.subcommand == "unittest":
        warn_flags = [f"-W{option}" for option in sys.warnoptions]
        cmd = [sys.executable, *warn_flags, allure_results.__file__, *argv]
        code = run_python(cmd, output)
        if code != 1:
            return code
        prefix = f"[{args.variant}] " if args.variant else ""

        def isolated_python(case: dict, folder: Path) -> int:
            test_id = case["fullName"][len(prefix) :]
            return run_python([*cmd, "--test-id", test_id, "--output", str(folder)], folder)

        failures = [case for case in read_results(output) if case["status"] in ("failed", "broken")]
        return retry_results(failures, output, isolated_python, failure_code=1)
    if args.subcommand != "cargo":
        sys.exit("Expected unittest or cargo suite")
    cmd = args.command[1:]
    if cmd != ["cargo", "test", "--no-fail-fast", "--", "--show-output"]:
        sys.exit("Expected cargo test --no-fail-fast -- --show-output")
    code, log = allure_results.run_cargo_tests(args, cmd, output)
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

    def run_rust(case: dict, folder: Path) -> Optional[int]:
        target, name = case["fullName"].split("::", 1)
        isolated = ["cargo", "test", *selectors[target], "--", "--exact", name, "--show-output"]
        code, log = allure_results.run_cargo_tests(args, isolated, folder)
        failed = cargo_failed_targets(log, read_results(folder))
        return code if failed is not None and (code == 0 or target in failed) else None

    return retry_results(failures, output, run_rust, failure_code=101)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
