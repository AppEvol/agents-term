"""Python unittest runner that emits Allure 3 JSON per test case and subTest."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from test_utils_py.reporters.allure_model import build_result, format_exc_info, now_ms, write_result


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
        self._test_starts[key] = now_ms()
        self._subtest_steps[key] = []

    def addSuccess(self, test: unittest.TestCase) -> None:
        super().addSuccess(test)
        self._record(test, "passed")

    def addFailure(self, test: unittest.TestCase, err: Any) -> None:
        super().addFailure(test, err)
        message, trace = format_exc_info(err)
        self._record(test, "failed", message=message, trace=trace)

    def addError(self, test: unittest.TestCase, err: Any) -> None:
        super().addError(test, err)
        message, trace = format_exc_info(err)
        key = self._test_key(test)
        if key not in self._test_starts:
            # Class/module fixture errors (setUpClass, tearDownClass, setUpModule,
            # or import errors) invoke addError(_ErrorHolder, err) without
            # startTest/stopTest, so emit them immediately.
            now = now_ms()
            self._emit_test(test, "broken", now, now, message, trace, [])
            return
        self._record(test, "broken", message=message, trace=trace)

    def addSkip(self, test: unittest.TestCase, reason: str) -> None:
        super().addSkip(test, reason)
        key = self._test_key(test)
        if key not in self._test_starts:
            now = now_ms()
            self._emit_test(test, "skipped", now, now, reason, "", [])
            return
        self._record(test, "skipped", message=reason)

    def addExpectedFailure(self, test: unittest.TestCase, err: Any) -> None:
        super().addExpectedFailure(test, err)
        message, trace = format_exc_info(err)
        self._record(test, "skipped", message=f"Expected failure: {message}", trace=trace)

    def addUnexpectedSuccess(self, test: unittest.TestCase) -> None:
        super().addUnexpectedSuccess(test)
        self._record(test, "failed", message="Unexpected success")

    def addSubTest(self, test: unittest.TestCase, subtest: unittest.TestCase, err: Any) -> None:
        super().addSubTest(test, subtest, err)
        key = self._test_key(test)
        now = now_ms()
        describe = getattr(subtest, "_subDescription", None)
        sub_name = describe() if callable(describe) else str(subtest)
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
            message, trace = format_exc_info(err)
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
        stop_ms = now_ms()
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

        payload = build_result(
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
        write_result(self._output_dir, payload)


def run_unittest_suite(
    *,
    start_dir: str,
    output_dir: Path,
    parent_suite: str,
    pattern: str = "test*.py",
    top_level_dir: Optional[str] = None,
    suite_override: Optional[str] = None,
    variant: Optional[str] = None,
    parameters: Optional[List[Dict[str, str]]] = None,
    tags: Optional[List[str]] = None,
    verbosity: int = 2,
    test_id: Optional[str] = None,
    completion_file: Optional[Path] = None,
) -> int:
    """Discover (or load a single) unittest case, run it, and emit Allure JSON results."""
    output_dir.mkdir(parents=True, exist_ok=True)
    params = list(parameters or [])
    tag_list = list(tags or [])

    loader = unittest.TestLoader()
    if test_id:
        sys.path.insert(0, str(Path(top_level_dir or start_dir).resolve()))
        suite = loader.loadTestsFromName(test_id)
    else:
        suite = loader.discover(
            start_dir=start_dir,
            pattern=pattern,
            top_level_dir=top_level_dir,
        )
    planned = suite.countTestCases()

    def result_factory(stream: Any, descriptions: bool, verb: int) -> AllureTextTestResult:
        return AllureTextTestResult(
            stream,
            descriptions,
            verb,
            output_dir=output_dir,
            parent_suite=parent_suite,
            suite_override=suite_override,
            variant=variant,
            parameters=params,
            tags=tag_list,
        )

    runner = unittest.TextTestRunner(verbosity=verbosity, resultclass=result_factory)
    result = runner.run(suite)
    if completion_file is not None:
        # Class/module teardown failures can arrive after every test has run.
        fixture_error = any(not isinstance(test, unittest.TestCase) for test, _ in result.errors)
        if loader.errors or fixture_error or not planned or result.testsRun != planned or result.shouldStop:
            print("Incomplete unittest suite or fixture failure", file=sys.stderr)
            return 2
        completion_file.touch()
    return 0 if result.wasSuccessful() else 1
