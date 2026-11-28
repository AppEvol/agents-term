"""Convert Gradle JUnit XML reports into Allure 3 JSON with Roborazzi golden diff attachments."""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Dict, List, Optional, Set

from test_utils_py.reporters.allure_model import (
    build_result,
    copy_file_attachment,
    now_ms,
    parse_iso_timestamp_ms,
    write_result,
    write_text_attachment,
)

ROBORAZZI_GOLDEN_RE = re.compile(r"\[([A-Za-z0-9_]+)\s+on\s+([A-Za-z0-9_]+)\]")


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
                    attachments.append(copy_file_attachment(output_dir, candidate, label, "image/png"))
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
        cursor_ms = parse_iso_timestamp_ms(suite_elem.attrib.get("timestamp")) or now_ms()

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
                attachments.append(write_text_attachment(output_dir, "System output", sys_out.text))
            sys_err = child.find("system-err")
            if sys_err is not None and sys_err.text and sys_err.text.strip():
                attachments.append(write_text_attachment(output_dir, "System error", sys_err.text))
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

            payload = build_result(
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
            write_result(output_dir, payload)
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
