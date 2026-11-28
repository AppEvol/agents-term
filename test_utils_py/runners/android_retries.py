"""Run a Gradle test task and verify each isolated retry against fresh JUnit XML."""

from __future__ import annotations

import argparse
import json
import re
import shutil
import sys
import xml.etree.ElementTree as ET
from contextlib import contextmanager
from dataclasses import dataclass
from functools import partial
from pathlib import Path
from typing import Dict, Iterator, List, Optional, Sequence, Tuple

from test_utils_py.process import acquire_file_lock, stream_command
from test_utils_py.process import lock_path_for as process_lock_path_for
from test_utils_py.reporters.junit_converter import convert_junit_xml
from test_utils_py.retry import record_attempt, retry_case

Case = Tuple[str, str]
BUILD = Path("app/build")


@dataclass(frozen=True)
class Suite:
    task: str
    results: Path
    report: Path
    parent: str
    framework: str
    vm_wide_lock: bool = False


SUITES = {
    "android-unit": Suite(
        ":app:testDebugUnitTest",
        BUILD / "test-results/testDebugUnitTest",
        BUILD / "reports/tests/testDebugUnitTest",
        "Android Unit Tests",
        "junit4",
    ),
    "android-e2e": Suite(
        ":app:connectedDebugAndroidTest",
        BUILD / "outputs/androidTest-results/connected",
        BUILD / "reports/androidTests/connected",
        "Android Instrumented E2E",
        "android-junit",
        vm_wide_lock=True,
    ),
}


def read_cases(results: Path) -> Dict[Case, str]:
    cases: Dict[Case, str] = {}
    for report in sorted(results.rglob("TEST-*.xml")):
        root = ET.parse(report).getroot()
        suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
        tests = [test for suite in suites for test in suite.findall("testcase")]
        if root.tag not in ("testsuite", "testsuites") or int(root.attrib["tests"]) != len(tests):
            raise ValueError(f"Incomplete JUnit suite in {report}")
        if any(int(suite.attrib["tests"]) != len(suite.findall("testcase")) for suite in suites):
            raise ValueError(f"Incomplete JUnit suite in {report}")
        for test in tests:
            case = test.attrib["classname"], test.attrib["name"]
            if not all(case) or case in cases:
                raise ValueError(f"Ambiguous case {case} in {report}")
            status = "passed"
            for tag, outcome in (("failure", "failed"), ("error", "broken"), ("skipped", "skipped")):
                if test.find(tag) is not None:
                    status = outcome
                    break
            cases[case] = status
    return cases


def validate_run(suite: Suite, log: str, code: int, cases: Dict[Case, str]) -> None:
    failures = sum(s in ("failed", "broken") for s in cases.values())
    if not cases or bool(code) != bool(failures):
        raise ValueError(f"Gradle exit {code} disagrees with {len(cases)} cases and {failures} failures")
    if code:
        tasks = re.findall(r"^Execution failed for task '([^']+)'\.$", log, re.MULTILINE)
        if tasks != [suite.task] or not re.search(
            r"^> There were failing tests\. See the report at:", log, re.MULTILINE
        ):
            raise ValueError("Gradle failed outside test assertions")
    if suite.framework == "android-junit":
        for marker in ("Starting", "Finished"):
            counts = re.findall(rf"^{marker} (\d+) tests? on ", log, re.MULTILINE)
            if counts != [str(len(cases))]:
                raise ValueError(f"Emulator {marker} count {counts} disagrees with {len(cases)} JUnit cases")
    elif code:
        counts = re.findall(r"^(\d+) tests? completed, (\d+) failed", log, re.MULTILINE)
        if counts != [(str(len(cases)), str(failures))]:
            raise ValueError("Gradle's completed/failed counts disagree with the JUnit cases")


def publish(tag: str, stage: Path, attempt: int) -> None:
    suite = SUITES[tag]
    output = Path("allure-results") / tag
    converted = stage / "allure"
    for report in sorted((stage / "results").rglob("TEST-*.xml")):
        convert_junit_xml(
            input_path=report,
            output_dir=converted,
            parent_suite=suite.parent,
            framework=suite.framework,
            tags=[tag],
            roborazzi_dir=stage / "goldens",
        )
    output.mkdir(parents=True, exist_ok=True)
    for file in converted.iterdir():
        if attempt and file.name.endswith("-result.json"):
            payload = json.loads(file.read_text(encoding="utf-8"))
            record_attempt(payload, attempt)
            file.write_text(json.dumps(payload), encoding="utf-8")
        file.rename(output / file.name)
    converted.rmdir()


def run_gradle(tag: str, args: Sequence[str], stage: Path, selected: Optional[Case], attempt: int) -> Dict[Case, str]:
    suite = SUITES[tag]
    # --rerun forces only this task, including consecutive identical filters.
    command: List[str] = ["gradle", "--console=plain", "--stacktrace", suite.task, "--rerun", *args]
    if selected:
        cls, method = selected
        if tag == "android-unit":
            command.extend(["--tests", f"{cls}.{method}"])
        else:
            command.append(f"-Pandroid.testInstrumentationRunnerArguments.class={cls}#{method}")
    stage.mkdir(parents=True)
    outputs = {suite.results: "results", suite.report: "html"}
    if tag == "android-unit":
        outputs[BUILD / "outputs/roborazzi"] = "goldens"
    for source in outputs:
        if source.is_dir():
            shutil.rmtree(source)

    log_path = stage / "gradle.log"
    code, log_text = stream_command(command, log_path=log_path)

    for source, name in outputs.items():
        if source.is_dir():
            source.rename(stage / name)
    cases = read_cases(stage / "results")
    if not attempt and cases:
        publish(tag, stage, attempt)
    validate_run(suite, log_text, code, cases)
    if selected and list(cases) != [selected]:
        raise ValueError(f"Requested {selected} but Gradle ran {list(cases)}")
    if attempt:
        publish(tag, stage, attempt)
    return cases


def run_isolated(tag: str, args: Sequence[str], folder: Path, case: Case, attempt: int) -> Optional[str]:
    try:
        return run_gradle(tag, args, folder / f"attempt-{attempt}", case, attempt)[case]
    except (KeyError, ValueError, ET.ParseError) as exc:
        print(f"Invalid retry for {case}: {exc}", file=sys.stderr)
        return None


def lock_path_for(tag: str, cwd: Optional[Path] = None) -> Path:
    return process_lock_path_for(tag, vm_wide=SUITES[tag].vm_wide_lock, cwd=cwd)


@contextmanager
def acquire_suite_lock(tag: str) -> Iterator[None]:
    # Gradle's own lock ends before we archive results or launch an isolated retry.
    # Keeping the lock file outside app/build ensures `gradle clean` cannot unlink a held lock.
    with acquire_file_lock(tag, vm_wide=SUITES[tag].vm_wide_lock):
        yield


def run_suite(tag: str, gradle_args: Sequence[str], folder: Path) -> bool:
    for old in (folder, Path("allure-results") / tag):
        if old.is_dir():
            shutil.rmtree(old)
    try:
        cases = run_gradle(tag, gradle_args, folder / "original", None, 0)
    except (KeyError, ValueError, ET.ParseError) as exc:
        print(f"Incomplete original suite: {exc}", file=sys.stderr)
        return False
    passed = True
    failures = (case for case, status in cases.items() if status in ("failed", "broken"))
    for index, case in enumerate(failures, 1):
        callback = partial(run_isolated, tag, gradle_args, folder / f"failure-{index}", case)
        if not retry_case(".".join(case), callback):
            passed = False
    return passed


def run_suite_locked(tag: str, gradle_args: Sequence[str] = ()) -> bool:
    folder = BUILD / "ci-test-attempts" / tag
    with acquire_suite_lock(tag):
        return run_suite(tag, gradle_args, folder)


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tag", choices=SUITES)
    parser.add_argument("gradle_args", nargs=argparse.REMAINDER)
    args = parser.parse_args(list(argv) if argv is not None else None)
    return 0 if run_suite_locked(args.tag, args.gradle_args) else 1
