"""Cargo test runner and `--show-output` log parser emitting Allure 3 JSON."""

from __future__ import annotations

import re
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Tuple

from test_utils_py.process import stream_command
from test_utils_py.reporters.allure_model import build_result, now_ms, write_result, write_text_attachment

CARGO_RUNNING_RE = re.compile(r"^\s*Running\s+(.+?)\s+\(([^)]+)\)\s*$")
CARGO_DOCTEST_RE = re.compile(r"^\s*Doc-tests\s+(.+?)\s*$")
CARGO_TEST_LINE_RE = re.compile(r"^test\s+(.+?)\s+\.\.\.\s+(ok|FAILED|ignored)(?:,\s*(.*))?$")
CARGO_SECTION_HEADER_RE = re.compile(r"^----\s+(.+?)\s+(stdout|stderr)\s+----$")
CARGO_SUMMARY_RE = re.compile(r"^test result:\s+(ok|FAILED)\..*?finished in\s+([0-9]+(?:\.[0-9]+)?)s\s*$")


def _extract_panic_message(output_text: str) -> str:
    lines = [line.strip() for line in output_text.splitlines() if line.strip()]
    for idx, line in enumerate(lines):
        if "panicked at" in line:
            if idx + 1 < len(lines) and not lines[idx + 1].startswith("note:"):
                return f"{line}: {lines[idx + 1]}"
            return line
    return lines[0] if lines else "Rust test failed"


def normalize_cargo_target(raw_target: str) -> str:
    target = raw_target.strip()
    if target.startswith("unittests "):
        return target[len("unittests ") :].strip()
    return target


def parse_cargo_test_output(
    *,
    output_text: str,
    output_dir: Path,
    parent_suite: str = "Rust VM Daemon",
    exit_code: int = 0,
    tags: Optional[List[str]] = None,
) -> int:
    """Parse `cargo test -- --show-output` output and emit Allure JSON results."""
    output_dir.mkdir(parents=True, exist_ok=True)
    tag_list = list(tags or [])
    base_ms = now_ms()

    targets: List[Dict[str, Any]] = []
    current_target: Optional[Dict[str, Any]] = None
    section_mode: Optional[str] = None
    active_output_test: Optional[str] = None
    active_output_lines: List[str] = []

    def flush_output_block() -> None:
        nonlocal active_output_test, active_output_lines
        if current_target is not None and active_output_test is not None:
            text = "\n".join(active_output_lines).strip()
            if text:
                bucket = current_target["failure_outputs"] if section_mode == "failures" else current_target["outputs"]
                if active_output_test in bucket:
                    bucket[active_output_test] = f"{bucket[active_output_test]}\n\n{text}"
                else:
                    bucket[active_output_test] = text
        active_output_test = None
        active_output_lines = []

    def ensure_target(name: str) -> Dict[str, Any]:
        nonlocal current_target, section_mode
        flush_output_block()
        section_mode = None
        target: Dict[str, Any] = {
            "name": name,
            "tests": [],
            "outputs": {},
            "failure_outputs": {},
            "duration_ms": 0,
        }
        current_target = target
        targets.append(target)
        return target

    for raw_line in output_text.splitlines():
        line = raw_line.rstrip("\r")

        running_match = CARGO_RUNNING_RE.match(line)
        if running_match:
            ensure_target(normalize_cargo_target(running_match.group(1)))
            continue

        doctest_match = CARGO_DOCTEST_RE.match(line)
        if doctest_match:
            ensure_target(f"Doc-tests ({doctest_match.group(1).strip()})")
            continue

        stripped = line.strip()
        if current_target is not None:
            if stripped == "successes:":
                flush_output_block()
                section_mode = "successes"
                continue
            if stripped == "failures:":
                flush_output_block()
                section_mode = "failures"
                continue

            header_match = CARGO_SECTION_HEADER_RE.match(line)
            if header_match and section_mode in ("successes", "failures"):
                flush_output_block()
                active_output_test = header_match.group(1).strip()
                active_output_lines = []
                continue

            summary_match = CARGO_SUMMARY_RE.match(line)
            if summary_match:
                flush_output_block()
                section_mode = None
                current_target["duration_ms"] = max(0, round(float(summary_match.group(2)) * 1000))
                continue

            if active_output_test is not None:
                active_output_lines.append(line)
                continue

        test_match = CARGO_TEST_LINE_RE.match(line)
        if test_match:
            test_target = current_target if current_target is not None else ensure_target("cargo test")
            raw_test_name = test_match.group(1).strip()
            raw_status = test_match.group(2)
            extra = (test_match.group(3) or "").strip()
            clean_name = raw_test_name.replace(" - should panic", "").strip()
            test_target["tests"].append((clean_name, raw_status, extra))

    flush_output_block()

    emitted = 0
    has_failed = False
    cursor_ms = base_ms
    for target in targets:
        target_name = target["name"]
        tests: List[Tuple[str, str, str]] = target["tests"]
        if not tests:
            continue
        per_test_ms = target["duration_ms"] // len(tests) if target["duration_ms"] else 0

        for test_name, raw_status, extra in tests:
            start_ms = cursor_ms
            stop_ms = start_ms + per_test_ms
            cursor_ms = stop_ms

            if raw_status == "ok":
                status = "passed"
            elif raw_status == "FAILED":
                status = "failed"
                has_failed = True
            else:
                status = "skipped"

            fail_out = target["failure_outputs"].get(test_name, "")
            pass_out = target["outputs"].get(test_name, "")
            message = ""
            trace = ""
            attachments: List[Dict[str, str]] = []

            if status == "failed":
                message = _extract_panic_message(fail_out)
                trace = fail_out
                if fail_out:
                    attachments.append(write_text_attachment(output_dir, "Test failure output", fail_out))
            elif status == "skipped" and extra:
                message = extra
            elif pass_out:
                attachments.append(write_text_attachment(output_dir, "Test stdout", pass_out))

            if "::" in test_name:
                module_part, short_name = test_name.rsplit("::", 1)
            else:
                module_part, short_name = "", test_name

            full_name = f"{target_name}::{test_name}"
            labels: List[Dict[str, str]] = [
                {"name": "parentSuite", "value": parent_suite},
                {"name": "suite", "value": target_name},
            ]
            if module_part:
                labels.append({"name": "subSuite", "value": module_part})
                labels.append({"name": "package", "value": module_part})
            labels.append({"name": "testClass", "value": target_name})
            labels.append({"name": "framework", "value": "cargo-test"})
            labels.append({"name": "language", "value": "rust"})
            for tag in tag_list:
                labels.append({"name": "tag", "value": tag})

            payload = build_result(
                name=short_name,
                full_name=full_name,
                status=status,
                start_ms=start_ms,
                stop_ms=stop_ms,
                labels=labels,
                status_details={"message": message, "trace": trace},
                attachments=attachments,
            )
            write_result(output_dir, payload)
            emitted += 1

    if exit_code != 0 and not has_failed:
        tail = "\n".join(output_text.splitlines()[-120:])
        attachments = [write_text_attachment(output_dir, "Cargo output", output_text)] if output_text.strip() else []
        labels = [
            {"name": "parentSuite", "value": parent_suite},
            {"name": "suite", "value": "cargo build / test"},
            {"name": "framework", "value": "cargo-test"},
            {"name": "language", "value": "rust"},
        ]
        for tag in tag_list:
            labels.append({"name": "tag", "value": tag})
        payload = build_result(
            name="Cargo build / test execution",
            full_name=f"{parent_suite}::cargo_execution",
            status="broken",
            start_ms=base_ms,
            stop_ms=now_ms(),
            labels=labels,
            status_details={"message": f"cargo test exited with code {exit_code}", "trace": tail},
            attachments=attachments,
        )
        write_result(output_dir, payload)
        emitted += 1

    return emitted


def run_cargo_tests(
    cmd: Sequence[str],
    output_dir: Path,
    *,
    cwd: Optional[str] = None,
    parent_suite: str = "Rust VM Daemon",
    tags: Optional[List[str]] = None,
) -> Tuple[int, str]:
    """Execute a Cargo test command, stream its output live, and emit Allure JSON results."""
    exit_code, log = stream_command(cmd, cwd=Path(cwd) if cwd else None)
    count = parse_cargo_test_output(
        output_text=log,
        output_dir=output_dir,
        parent_suite=parent_suite,
        exit_code=exit_code,
        tags=tags,
    )
    print(f"Recorded {count} Rust test result(s) into {output_dir}")
    return exit_code, log
