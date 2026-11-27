#!/usr/bin/env python3
"""Stdlib-only Allure 3 test-result emitter, converter, merger, and summary tool.

Emits Allure JSON result files (`<uuid>-result.json`) and attachments readable by
Allure 3 (`allure@3.33.0`) across all AgentsTerm test suites without adding extra
Python, Rust, or Gradle dependencies:

  - `unittest`: runs Python `unittest` discovery and emits per-test Allure JSON.
  - `junit`: converts Gradle JUnit XML (Android unit & instrumented tests) and
    attaches Roborazzi `*_compare.png` diffs on golden-test failures.
  - `cargo`: wraps `cargo test -- --show-output` (or parses a saved log) and
    emits per-test Allure JSON with panic traces and stdout attachments.
  - `merge`: merges shard directories and writes `environment.properties` /
    `executor.json`.
  - `summary`: renders a Markdown summary table (and optional `$GITHUB_STEP_SUMMARY`).
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
import traceback
import unittest
import uuid
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence, Set, Tuple

ROBORAZZI_GOLDEN_RE = re.compile(r"\[([A-Za-z0-9_]+)\s+on\s+([A-Za-z0-9_]+)\]")
CARGO_RUNNING_RE = re.compile(r"^\s*Running\s+(.+?)\s+\(([^)]+)\)\s*$")
CARGO_DOCTEST_RE = re.compile(r"^\s*Doc-tests\s+(.+?)\s*$")
CARGO_TEST_LINE_RE = re.compile(r"^test\s+(.+?)\s+\.\.\.\s+(ok|FAILED|ignored)(?:,\s*(.*))?$")
CARGO_SECTION_HEADER_RE = re.compile(r"^----\s+(.+?)\s+(stdout|stderr)\s+----$")
CARGO_SUMMARY_RE = re.compile(r"^test result:\s+(ok|FAILED)\..*?finished in\s+([0-9]+(?:\.[0-9]+)?)s\s*$")


def _now_ms() -> int:
    return int(time.time() * 1000)


def _md5_hex(value: str) -> str:
    return hashlib.md5(value.encode("utf-8")).hexdigest()


def _parse_key_values(items: Sequence[str], flag_name: str) -> List[Tuple[str, str]]:
    parsed: List[Tuple[str, str]] = []
    for item in items:
        if "=" not in item:
            raise ValueError(f"{flag_name} expects KEY=VALUE, got: {item!r}")
        key, value = item.split("=", 1)
        parsed.append((key, value))
    return parsed


def _compute_identity(full_name: str, parameters: Sequence[Dict[str, str]]) -> Tuple[str, str]:
    param_part = "|".join(f"{p['name']}={p['value']}" for p in sorted(parameters, key=lambda item: item["name"]))
    digest = _md5_hex(f"{full_name}||{param_part}")
    return digest, digest


def _write_text_attachment(output_dir: Path, name: str, content: str, mime_type: str = "text/plain") -> Dict[str, str]:
    source = f"{uuid.uuid4()}-attachment.txt"
    (output_dir / source).write_text(content, encoding="utf-8")
    return {"name": name, "source": source, "type": mime_type}


def _copy_file_attachment(output_dir: Path, src_path: Path, name: str, mime_type: str) -> Dict[str, str]:
    suffix = src_path.suffix or ".bin"
    source = f"{uuid.uuid4()}-attachment{suffix}"
    shutil.copy2(src_path, output_dir / source)
    return {"name": name, "source": source, "type": mime_type}


def _write_result(output_dir: Path, payload: Dict[str, Any]) -> Path:
    output_dir.mkdir(parents=True, exist_ok=True)
    result_uuid = payload.setdefault("uuid", str(uuid.uuid4()))
    out_path = output_dir / f"{result_uuid}-result.json"
    out_path.write_text(json.dumps(payload, indent=2, sort_keys=True), encoding="utf-8")
    return out_path


def _build_result(
    *,
    name: str,
    full_name: str,
    status: str,
    start_ms: int,
    stop_ms: int,
    labels: List[Dict[str, str]],
    parameters: Optional[List[Dict[str, str]]] = None,
    status_details: Optional[Dict[str, str]] = None,
    attachments: Optional[List[Dict[str, str]]] = None,
    steps: Optional[List[Dict[str, Any]]] = None,
) -> Dict[str, Any]:
    params = list(parameters or [])
    history_id, test_case_id = _compute_identity(full_name, params)
    payload: Dict[str, Any] = {
        "uuid": str(uuid.uuid4()),
        "name": name,
        "fullName": full_name,
        "historyId": history_id,
        "testCaseId": test_case_id,
        "status": status,
        "stage": "finished",
        "start": start_ms,
        "stop": max(start_ms, stop_ms),
        "labels": labels,
    }
    if params:
        payload["parameters"] = params
    if status_details:
        cleaned = {k: v for k, v in status_details.items() if v}
        if cleaned:
            payload["statusDetails"] = cleaned
    if attachments:
        payload["attachments"] = attachments
    if steps:
        payload["steps"] = steps
    return payload


def _parse_iso_timestamp_ms(raw: Optional[str]) -> Optional[int]:
    if not raw:
        return None
    text = raw.strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    try:
        dt = datetime.fromisoformat(text)
    except ValueError:
        return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return int(dt.timestamp() * 1000)


def _format_exc_info(err: Tuple[Any, Any, Any]) -> Tuple[str, str]:
    exc_type, exc_value, _tb = err
    message = f"{exc_type.__name__}: {exc_value}" if exc_value else exc_type.__name__
    trace = "".join(traceback.format_exception(*err))
    return message, trace


class AllureTextTestResult(unittest.TextTestResult):
    """`unittest.TextTestResult` subclass that writes Allure JSON per test case."""

    def __init__(
        self,
        stream: Any,
        descriptions: bool,
        verbosity: int,
        *,
        output_dir: Path,
        parent_suite: str,
        suite_override: Optional[str] = None,
        variant: Optional[str] = None,
        parameters: Optional[List[Dict[str, str]]] = None,
        tags: Optional[List[str]] = None,
    ) -> None:
        super().__init__(stream, descriptions, verbosity)
        self._output_dir = output_dir
        self._parent_suite = parent_suite
        self._suite_override = suite_override
        self._variant = variant
        self._parameters = list(parameters or [])
        self._tags = list(tags or [])
        self._test_starts: Dict[str, int] = {}
        self._test_records: Dict[str, Dict[str, Any]] = {}
        self._subtest_steps: Dict[str, List[Dict[str, Any]]] = {}
        self._subtest_failure: Dict[str, Tuple[str, str, str]] = {}

    def _test_key(self, test: unittest.TestCase) -> str:
        return test.id()

    def startTest(self, test: unittest.TestCase) -> None:
        super().startTest(test)
        key = self._test_key(test)
        self._test_starts[key] = _now_ms()
        self._subtest_steps[key] = []

    def addSuccess(self, test: unittest.TestCase) -> None:
        super().addSuccess(test)
        self._record(test, "passed")

    def addFailure(self, test: unittest.TestCase, err: Any) -> None:
        super().addFailure(test, err)
        message, trace = _format_exc_info(err)
        self._record(test, "failed", message=message, trace=trace)

    def addError(self, test: unittest.TestCase, err: Any) -> None:
        super().addError(test, err)
        message, trace = _format_exc_info(err)
        key = self._test_key(test)
        if key not in self._test_starts:
            # Class/module fixture errors (setUpClass, tearDownClass, setUpModule,
            # or import errors) invoke addError(_ErrorHolder, err) without
            # startTest/stopTest, so emit them immediately.
            now = _now_ms()
            self._emit_test(test, "broken", now, now, message, trace, [])
            return
        self._record(test, "broken", message=message, trace=trace)

    def addSkip(self, test: unittest.TestCase, reason: str) -> None:
        super().addSkip(test, reason)
        key = self._test_key(test)
        if key not in self._test_starts:
            now = _now_ms()
            self._emit_test(test, "skipped", now, now, reason, "", [])
            return
        self._record(test, "skipped", message=reason)

    def addExpectedFailure(self, test: unittest.TestCase, err: Any) -> None:
        super().addExpectedFailure(test, err)
        message, trace = _format_exc_info(err)
        self._record(test, "skipped", message=f"Expected failure: {message}", trace=trace)

    def addUnexpectedSuccess(self, test: unittest.TestCase) -> None:
        super().addUnexpectedSuccess(test)
        self._record(test, "failed", message="Unexpected success")

    def addSubTest(self, test: unittest.TestCase, subtest: unittest.TestCase, err: Any) -> None:
        super().addSubTest(test, subtest, err)
        key = self._test_key(test)
        now = _now_ms()
        sub_name = subtest._subDescription() if hasattr(subtest, "_subDescription") else str(subtest)
        step: Dict[str, Any] = {
            "name": f"subTest {sub_name}",
            "stage": "finished",
            "start": now,
            "stop": now,
        }
        if err is None:
            step["status"] = "passed"
        else:
            is_failure = issubclass(err[0], test.failureException)
            step_status = "failed" if is_failure else "broken"
            message, trace = _format_exc_info(err)
            step["status"] = step_status
            step["statusDetails"] = {"message": message, "trace": trace}
            existing = self._subtest_failure.get(key)
            if existing is None or (existing[0] == "failed" and step_status == "broken"):
                self._subtest_failure[key] = (step_status, message, trace)
        self._subtest_steps.setdefault(key, []).append(step)

    def _record(
        self,
        test: unittest.TestCase,
        status: str,
        *,
        message: str = "",
        trace: str = "",
    ) -> None:
        key = self._test_key(test)
        self._test_records[key] = {
            "status": status,
            "message": message,
            "trace": trace,
        }

    def stopTest(self, test: unittest.TestCase) -> None:
        super().stopTest(test)
        stop_ms = _now_ms()
        key = self._test_key(test)
        start_ms = self._test_starts.pop(key, stop_ms)
        record = self._test_records.pop(key, None)
        steps = self._subtest_steps.pop(key, [])
        sub_failure = self._subtest_failure.pop(key, None)

        if record is None:
            if sub_failure is not None:
                status, message, trace = sub_failure
            else:
                status, message, trace = "passed", "", ""
        else:
            status = record["status"]
            message = record["message"]
            trace = record["trace"]
            if status == "passed" and sub_failure is not None:
                status, message, trace = sub_failure

        self._emit_test(test, status, start_ms, stop_ms, message, trace, steps)

    def _emit_test(
        self,
        test: unittest.TestCase,
        status: str,
        start_ms: int,
        stop_ms: int,
        message: str,
        trace: str,
        steps: List[Dict[str, Any]],
    ) -> None:
        test_id = test.id()
        parts = test_id.split(".")
        method_name = getattr(test, "_testMethodName", parts[-1])
        class_name = test.__class__.__name__
        module_name = test.__class__.__module__
        full_name = f"[{self._variant}] {test_id}" if self._variant else test_id

        labels: List[Dict[str, str]] = [
            {"name": "parentSuite", "value": self._parent_suite},
            {"name": "suite", "value": self._suite_override or module_name},
            {"name": "subSuite", "value": class_name},
            {"name": "package", "value": module_name},
            {"name": "testClass", "value": f"{module_name}.{class_name}"},
            {"name": "framework", "value": "unittest"},
            {"name": "language", "value": "python"},
        ]
        for tag in self._tags:
            labels.append({"name": "tag", "value": tag})

        payload = _build_result(
            name=method_name,
            full_name=full_name,
            status=status,
            start_ms=start_ms,
            stop_ms=stop_ms,
            labels=labels,
            parameters=self._parameters,
            status_details={"message": message, "trace": trace},
            steps=steps,
        )
        _write_result(self._output_dir, payload)


def _find_roborazzi_attachments(
    roborazzi_dir: Optional[Path],
    output_dir: Path,
    testcase_name: str,
) -> List[Dict[str, str]]:
    if roborazzi_dir is None or not roborazzi_dir.is_dir():
        return []
    attachments: List[Dict[str, str]] = []
    seen: Set[Path] = set()

    match = ROBORAZZI_GOLDEN_RE.search(testcase_name)
    if match:
        stem = f"{match.group(1)}__{match.group(2).lower()}"
        for suffix, label in (
            ("_compare.png", f"Golden diff ({stem})"),
            ("_actual.png", f"Actual rendering ({stem})"),
        ):
            for candidate in sorted(roborazzi_dir.rglob(f"{stem}{suffix}")):
                if candidate.is_file() and candidate not in seen:
                    seen.add(candidate)
                    attachments.append(_copy_file_attachment(output_dir, candidate, label, "image/png"))
    return attachments


def convert_junit_xml(
    *,
    input_path: Path,
    output_dir: Path,
    parent_suite: str,
    framework: str = "junit4",
    language: str = "kotlin",
    roborazzi_dir: Optional[Path] = None,
    suite_override: Optional[str] = None,
    variant: Optional[str] = None,
    parameters: Optional[List[Dict[str, str]]] = None,
    tags: Optional[List[str]] = None,
) -> int:
    """Convert one JUnit XML file or directory tree of `*.xml` into Allure JSON."""
    if input_path.is_file():
        xml_files = [input_path]
    elif input_path.is_dir():
        xml_files = sorted(input_path.rglob("*.xml"))
    else:
        return 0

    output_dir.mkdir(parents=True, exist_ok=True)
    count = 0
    params = list(parameters or [])
    tag_list = list(tags or [])

    def walk_suite(suite_elem: ET.Element, inherited_name: str = "") -> None:
        nonlocal count
        suite_name = suite_elem.attrib.get("name") or inherited_name or "default"
        cursor_ms = _parse_iso_timestamp_ms(suite_elem.attrib.get("timestamp")) or _now_ms()

        for child in suite_elem:
            if child.tag == "testsuite":
                walk_suite(child, suite_name)
                continue
            if child.tag != "testcase":
                continue

            tc_name = child.attrib.get("name") or "unnamed"
            classname = child.attrib.get("classname") or suite_name
            try:
                duration_ms = max(0, round(float(child.attrib.get("time", "0")) * 1000))
            except ValueError:
                duration_ms = 0

            start_ms = cursor_ms
            stop_ms = start_ms + duration_ms
            cursor_ms = stop_ms

            status = "passed"
            message = ""
            trace = ""
            failure = child.find("failure")
            error = child.find("error")
            skipped = child.find("skipped")
            if failure is not None:
                status = "failed"
                message = failure.attrib.get("message", "")
                trace = (failure.text or "").strip()
            elif error is not None:
                status = "broken"
                message = error.attrib.get("message", "")
                trace = (error.text or "").strip()
            elif skipped is not None:
                status = "skipped"
                message = skipped.attrib.get("message", "") or (skipped.text or "").strip()

            attachments: List[Dict[str, str]] = []
            sys_out = child.find("system-out")
            if sys_out is not None and sys_out.text and sys_out.text.strip():
                attachments.append(_write_text_attachment(output_dir, "System output", sys_out.text))
            sys_err = child.find("system-err")
            if sys_err is not None and sys_err.text and sys_err.text.strip():
                attachments.append(_write_text_attachment(output_dir, "System error", sys_err.text))
            if status in ("failed", "broken"):
                attachments.extend(_find_roborazzi_attachments(roborazzi_dir, output_dir, tc_name))

            package_name = classname.rsplit(".", 1)[0] if "." in classname else ""
            short_class = classname.rsplit(".", 1)[-1]
            base_full_name = f"{classname}.{tc_name}" if classname else tc_name
            full_name = f"[{variant}] {base_full_name}" if variant else base_full_name

            labels: List[Dict[str, str]] = [
                {"name": "parentSuite", "value": parent_suite},
                {"name": "suite", "value": suite_override or short_class},
            ]
            if suite_override:
                labels.append({"name": "subSuite", "value": short_class})
            if package_name:
                labels.append({"name": "package", "value": package_name})
            if classname:
                labels.append({"name": "testClass", "value": classname})
            labels.append({"name": "framework", "value": framework})
            labels.append({"name": "language", "value": language})
            for tag in tag_list:
                labels.append({"name": "tag", "value": tag})

            payload = _build_result(
                name=tc_name,
                full_name=full_name,
                status=status,
                start_ms=start_ms,
                stop_ms=stop_ms,
                labels=labels,
                parameters=params,
                status_details={"message": message, "trace": trace},
                attachments=attachments,
            )
            _write_result(output_dir, payload)
            count += 1

    for xml_file in xml_files:
        try:
            tree = ET.parse(xml_file)
        except ET.ParseError as exc:
            print(f"warning: skipping malformed JUnit XML {xml_file}: {exc}", file=sys.stderr)
            continue
        root = tree.getroot()
        if root.tag == "testsuite":
            walk_suite(root)
        elif root.tag == "testsuites":
            for child in root:
                if child.tag == "testsuite":
                    walk_suite(child)
    return count


def _extract_panic_message(output_text: str) -> str:
    lines = [line.strip() for line in output_text.splitlines() if line.strip()]
    for idx, line in enumerate(lines):
        if "panicked at" in line:
            if idx + 1 < len(lines) and not lines[idx + 1].startswith("note:"):
                return f"{line}: {lines[idx + 1]}"
            return line
    return lines[0] if lines else "Rust test failed"


def _normalize_cargo_target(raw_target: str) -> str:
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
    base_ms = _now_ms()

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
        current_target = {
            "name": name,
            "tests": [],
            "outputs": {},
            "failure_outputs": {},
            "duration_ms": 0,
        }
        targets.append(current_target)
        return current_target

    for raw_line in output_text.splitlines():
        line = raw_line.rstrip("\r")

        running_match = CARGO_RUNNING_RE.match(line)
        if running_match:
            ensure_target(_normalize_cargo_target(running_match.group(1)))
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
            if current_target is None:
                ensure_target("cargo test")
            raw_test_name = test_match.group(1).strip()
            raw_status = test_match.group(2)
            extra = (test_match.group(3) or "").strip()
            clean_name = raw_test_name.replace(" - should panic", "").strip()
            if current_target is not None:
                current_target["tests"].append((clean_name, raw_status, extra))

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
                    attachments.append(_write_text_attachment(output_dir, "Test failure output", fail_out))
            elif status == "skipped" and extra:
                message = extra
            elif pass_out:
                attachments.append(_write_text_attachment(output_dir, "Test stdout", pass_out))

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

            payload = _build_result(
                name=short_name,
                full_name=full_name,
                status=status,
                start_ms=start_ms,
                stop_ms=stop_ms,
                labels=labels,
                status_details={"message": message, "trace": trace},
                attachments=attachments,
            )
            _write_result(output_dir, payload)
            emitted += 1

    if exit_code != 0 and not has_failed:
        tail = "\n".join(output_text.splitlines()[-120:])
        attachments = [_write_text_attachment(output_dir, "Cargo output", output_text)] if output_text.strip() else []
        labels = [
            {"name": "parentSuite", "value": parent_suite},
            {"name": "suite", "value": "cargo build / test"},
            {"name": "framework", "value": "cargo-test"},
            {"name": "language", "value": "rust"},
        ]
        for tag in tag_list:
            labels.append({"name": "tag", "value": tag})
        payload = _build_result(
            name="Cargo build / test execution",
            full_name=f"{parent_suite}::cargo_execution",
            status="broken",
            start_ms=base_ms,
            stop_ms=_now_ms(),
            labels=labels,
            status_details={"message": f"cargo test exited with code {exit_code}", "trace": tail},
            attachments=attachments,
        )
        _write_result(output_dir, payload)
        emitted += 1

    return emitted


def merge_results(
    *,
    input_dirs: Sequence[Path],
    output_dir: Path,
    env_pairs: Sequence[Tuple[str, str]] = (),
    executor: Optional[Dict[str, Any]] = None,
) -> int:
    """Merge Allure result files and attachments from one or more directories."""
    if output_dir.exists():
        shutil.rmtree(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    result_count = 0
    for input_dir in input_dirs:
        if not input_dir.exists():
            continue
        files = [input_dir] if input_dir.is_file() else sorted(p for p in input_dir.rglob("*") if p.is_file())
        for src in files:
            name = src.name
            is_result = name.endswith("-result.json")
            is_keepable = (
                is_result
                or name.endswith(("-container.json", ".xml"))
                or "-attachment" in name
                or name == "categories.json"
            )
            if not is_keepable:
                continue
            dest = output_dir / name
            if dest.exists():
                dest = output_dir / f"{uuid.uuid4().hex[:8]}-{name}"
            shutil.copy2(src, dest)
            if is_result:
                result_count += 1

    if env_pairs:
        lines = [f"{k}={v}" for k, v in env_pairs if v]
        if lines:
            (output_dir / "environment.properties").write_text("\n".join(lines) + "\n", encoding="utf-8")

    if executor:
        cleaned_executor = {k: v for k, v in executor.items() if v is not None and v != ""}
        if cleaned_executor:
            (output_dir / "executor.json").write_text(
                json.dumps(cleaned_executor, indent=2, sort_keys=True),
                encoding="utf-8",
            )

    return result_count


def _format_duration_ms(duration_ms: int) -> str:
    if duration_ms < 1000:
        return f"{duration_ms}ms"
    seconds = duration_ms / 1000.0
    if seconds < 60:
        return f"{seconds:.1f}s"
    minutes = int(seconds // 60)
    rem_seconds = seconds - minutes * 60
    return f"{minutes}m {rem_seconds:.1f}s"


def _label_value(result: Dict[str, Any], name: str, default: str = "") -> str:
    for label in result.get("labels", []):
        if label.get("name") == name and label.get("value"):
            return str(label["value"])
    return default


def _escape_md_cell(value: str) -> str:
    return value.replace("|", "\\|").replace("\r\n", " ").replace("\n", " ").strip()


def build_markdown_summary(
    results_dir: Path,
    *,
    artifact_name: str = "allure-report",
    report_url: str = "",
) -> Tuple[str, int]:
    """Build a GitHub-Flavored Markdown summary table from merged Allure results."""
    result_files = sorted(results_dir.glob("*-result.json")) if results_dir.is_dir() else []
    if not result_files:
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

    suites: Dict[str, Dict[str, int]] = {}
    totals = {"passed": 0, "failed": 0, "broken": 0, "skipped": 0, "duration_ms": 0}
    failures: List[Tuple[str, str, str, str]] = []

    for path in result_files:
        data = json.loads(path.read_text(encoding="utf-8"))
        parent = _label_value(data, "parentSuite", "Other")
        suite = _label_value(data, "suite", "")
        status = str(data.get("status", "broken"))
        if status not in ("passed", "failed", "broken", "skipped"):
            status = "broken"
        start = int(data.get("start") or 0)
        stop = int(data.get("stop") or start)
        duration_ms = max(0, stop - start)

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
            f"{s['skipped']} | {suite_total} | {_format_duration_ms(s['duration_ms'])} |"
        )
    lines.append(
        f"| **Total** | **{totals['passed']}** | **{totals['failed']}** | **{totals['broken']}** | "
        f"**{totals['skipped']}** | **{total_count}** | **{_format_duration_ms(totals['duration_ms'])}** |"
    )
    lines.append("")

    if report_url:
        lines.append(
            f"- Full interactive Allure 3 Awesome report artifact: **`{artifact_name}`** ([workflow run]({report_url}))"
        )
    else:
        lines.append(f"- Full interactive Allure 3 Awesome report artifact: **`{artifact_name}`**")

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


def _cmd_unittest(args: argparse.Namespace) -> int:
    output_dir = Path(args.output)
    output_dir.mkdir(parents=True, exist_ok=True)
    params = [{"name": k, "value": v} for k, v in _parse_key_values(args.param, "--param")]

    loader = unittest.TestLoader()
    suite = loader.discover(
        start_dir=args.start_dir,
        pattern=args.pattern,
        top_level_dir=args.top_level_dir,
    )

    def result_factory(stream: Any, descriptions: bool, verbosity: int) -> AllureTextTestResult:
        return AllureTextTestResult(
            stream,
            descriptions,
            verbosity,
            output_dir=output_dir,
            parent_suite=args.parent_suite,
            suite_override=args.suite,
            variant=args.variant,
            parameters=params,
            tags=args.tag,
        )

    runner = unittest.TextTestRunner(verbosity=args.verbosity, resultclass=result_factory)
    result = runner.run(suite)
    return 0 if result.wasSuccessful() else 1


def _cmd_junit(args: argparse.Namespace) -> int:
    params = [{"name": k, "value": v} for k, v in _parse_key_values(args.param, "--param")]
    roborazzi_dir = Path(args.roborazzi_dir) if args.roborazzi_dir else None
    count = convert_junit_xml(
        input_path=Path(args.input),
        output_dir=Path(args.output),
        parent_suite=args.parent_suite,
        framework=args.framework,
        language=args.language,
        roborazzi_dir=roborazzi_dir,
        suite_override=args.suite,
        variant=args.variant,
        parameters=params,
        tags=args.tag,
    )
    print(f"Converted {count} JUnit test result(s) into {args.output}")
    return 0


def _cmd_cargo(args: argparse.Namespace) -> int:
    output_dir = Path(args.output)
    if args.log_file:
        text = Path(args.log_file).read_text(encoding="utf-8", errors="replace")
        count = parse_cargo_test_output(
            output_text=text,
            output_dir=output_dir,
            parent_suite=args.parent_suite,
            exit_code=args.exit_code,
            tags=args.tag,
        )
        print(f"Parsed {count} Rust test result(s) into {output_dir}")
        return args.exit_code

    cmd = list(args.command)
    if cmd and cmd[0] == "--":
        cmd = cmd[1:]
    if not cmd:
        print("error: cargo subcommand requires -- <command...> or --log-file", file=sys.stderr)
        return 2

    captured_lines: List[str] = []
    with subprocess.Popen(
        cmd,
        cwd=args.cwd or None,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
        bufsize=1,
    ) as proc:
        assert proc.stdout is not None
        for line in proc.stdout:
            sys.stdout.write(line)
            sys.stdout.flush()
            captured_lines.append(line)
        exit_code = proc.wait()

    count = parse_cargo_test_output(
        output_text="".join(captured_lines),
        output_dir=output_dir,
        parent_suite=args.parent_suite,
        exit_code=exit_code,
        tags=args.tag,
    )
    print(f"Recorded {count} Rust test result(s) into {output_dir}")
    return exit_code


def _cmd_merge(args: argparse.Namespace) -> int:
    env_pairs = _parse_key_values(args.env, "--env")
    executor: Optional[Dict[str, Any]] = None
    if any((args.executor_name, args.executor_url, args.build_name, args.build_url, args.build_order)):
        executor = {
            "name": args.executor_name or "CI",
            "type": args.executor_type or "github",
            "url": args.executor_url,
            "buildName": args.build_name,
            "buildUrl": args.build_url,
            "buildOrder": args.build_order,
        }
    count = merge_results(
        input_dirs=[Path(p) for p in args.input],
        output_dir=Path(args.output),
        env_pairs=env_pairs,
        executor=executor,
    )
    print(f"Merged {count} Allure test result(s) into {args.output}")
    return 0


def _cmd_summary(args: argparse.Namespace) -> int:
    markdown, count = build_markdown_summary(
        Path(args.results_dir),
        artifact_name=args.artifact_name,
        report_url=args.report_url or "",
    )
    sys.stdout.write(markdown)
    if args.step_summary:
        summary_path = Path(args.step_summary)
        summary_path.parent.mkdir(parents=True, exist_ok=True)
        with summary_path.open("a", encoding="utf-8") as fh:
            fh.write(markdown)
    if args.github_output:
        output_path = Path(args.github_output)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        with output_path.open("a", encoding="utf-8") as fh:
            fh.write(f"result_count={count}\n")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Allure 3 test-result helper for AgentsTerm")
    subparsers = parser.add_subparsers(dest="subcommand", required=True)

    p_unit = subparsers.add_parser("unittest", help="Run Python unittest discovery and emit Allure JSON")
    p_unit.add_argument("--start-dir", required=True, help="Directory to discover tests from")
    p_unit.add_argument("--pattern", default="test*.py", help="Filename pattern (default: test*.py)")
    p_unit.add_argument("--top-level-dir", default=None, help="Top-level project directory for imports")
    p_unit.add_argument("--output", required=True, help="Allure results output directory")
    p_unit.add_argument("--parent-suite", required=True, help="Top-level Allure parentSuite label")
    p_unit.add_argument("--suite", default=None, help="Override suite label (e.g., matrix label)")
    p_unit.add_argument("--variant", default=None, help="Unique matrix variant prefix for fullName/historyId")
    p_unit.add_argument("--param", action="append", default=[], help="Test parameter KEY=VALUE (repeatable)")
    p_unit.add_argument("--tag", action="append", default=[], help="Extra Allure tag label (repeatable)")
    p_unit.add_argument("--verbosity", type=int, default=2, help="unittest verbosity (default: 2)")
    p_unit.set_defaults(func=_cmd_unittest)

    p_junit = subparsers.add_parser("junit", help="Convert JUnit XML files to Allure JSON")
    p_junit.add_argument("--input", required=True, help="JUnit XML file or directory")
    p_junit.add_argument("--output", required=True, help="Allure results output directory")
    p_junit.add_argument("--parent-suite", required=True, help="Top-level Allure parentSuite label")
    p_junit.add_argument("--suite", default=None, help="Override suite label")
    p_junit.add_argument("--framework", default="junit4", help="Framework label (default: junit4)")
    p_junit.add_argument("--language", default="kotlin", help="Language label (default: kotlin)")
    p_junit.add_argument("--roborazzi-dir", default=None, help="Optional Roborazzi diff directory")
    p_junit.add_argument("--variant", default=None, help="Unique matrix variant prefix")
    p_junit.add_argument("--param", action="append", default=[], help="Test parameter KEY=VALUE (repeatable)")
    p_junit.add_argument("--tag", action="append", default=[], help="Extra Allure tag label (repeatable)")
    p_junit.set_defaults(func=_cmd_junit)

    p_cargo = subparsers.add_parser("cargo", help="Run cargo test (or parse log) and emit Allure JSON")
    p_cargo.add_argument("--output", required=True, help="Allure results output directory")
    p_cargo.add_argument("--parent-suite", default="Rust VM Daemon", help="Top-level Allure parentSuite label")
    p_cargo.add_argument("--cwd", default=None, help="Working directory for cargo command")
    p_cargo.add_argument("--log-file", default=None, help="Parse an existing cargo test log instead of running")
    p_cargo.add_argument("--exit-code", type=int, default=0, help="Exit code to assume with --log-file")
    p_cargo.add_argument("--tag", action="append", default=[], help="Extra Allure tag label (repeatable)")
    p_cargo.add_argument("command", nargs=argparse.REMAINDER, help="Cargo command after `--`")
    p_cargo.set_defaults(func=_cmd_cargo)

    p_merge = subparsers.add_parser("merge", help="Merge Allure result directories and write metadata")
    p_merge.add_argument("--input", action="append", required=True, help="Input directory (repeatable)")
    p_merge.add_argument("--output", required=True, help="Merged output directory")
    p_merge.add_argument("--env", action="append", default=[], help="environment.properties KEY=VALUE")
    p_merge.add_argument("--executor-name", default=None)
    p_merge.add_argument("--executor-type", default="github")
    p_merge.add_argument("--executor-url", default=None)
    p_merge.add_argument("--build-name", default=None)
    p_merge.add_argument("--build-url", default=None)
    p_merge.add_argument("--build-order", default=None)
    p_merge.set_defaults(func=_cmd_merge)

    p_summary = subparsers.add_parser("summary", help="Render Markdown summary from merged Allure results")
    p_summary.add_argument("--results-dir", required=True, help="Merged Allure results directory")
    p_summary.add_argument("--artifact-name", default="allure-report", help="Uploaded artifact name")
    p_summary.add_argument("--report-url", default="", help="Workflow run URL")
    p_summary.add_argument("--step-summary", default=os.environ.get("GITHUB_STEP_SUMMARY", ""))
    p_summary.add_argument("--github-output", default="")
    p_summary.set_defaults(func=_cmd_summary)

    return parser


def main(argv: Optional[Iterable[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(list(argv) if argv is not None else None)
    return int(args.func(args))


if __name__ == "__main__":
    raise SystemExit(main())
