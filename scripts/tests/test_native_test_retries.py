"""Exercise CI fail/recover gate decisions at the native runners' CLI entry points."""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

ENTRY = Path(__file__).resolve().parents[1] / "native_test_retries.py"


class NativeRetryTest(unittest.TestCase):
    def setUp(self):
        scratch = tempfile.TemporaryDirectory()
        self.addCleanup(scratch.cleanup)
        self.root = Path(scratch.name)
        self.out = self.root / "results"

    def run_suite(self, *arguments, env=None):
        return subprocess.run(
            [sys.executable, str(ENTRY), *arguments],
            env=env,
            cwd=self.root,
            text=True,
            capture_output=True,
            timeout=30,
        )

    def records(self):
        return [json.loads(file.read_text()) for file in self.out.glob("*-result.json")]

    def run_python_suite(self, body):
        (self.root / "test_case.py").write_text(textwrap.dedent(body))
        return self.run_suite(
            "unittest", "--parent-suite", "installer", "--variant", "py3", "--start-dir", ".", "--output", str(self.out)
        )

    def test_python_isolates_case_and_keeps_variant_and_subtest_failures(self):
        proc = self.run_python_suite(
            """\
            import unittest
            polluted = False
            class Cases(unittest.TestCase):
                def test_a(self):
                    global polluted
                    polluted = True
                def test_b(self):
                    with self.subTest(scenario=1):
                        self.assertFalse(polluted)
            """
        )
        self.assertEqual(proc.returncode, 0, proc.stderr)
        recovered = [case for case in self.records() if case["fullName"] == "[py3] test_case.Cases.test_b"]
        self.assertCountEqual([case["status"] for case in recovered], ["passed", "passed", "failed"])
        self.assertEqual(len({case["historyId"] for case in recovered}), 1)
        self.assertEqual(len(self.records()), 4)

    def test_python_process_exit_after_an_assertion_cannot_recover(self):
        proc = self.run_python_suite(
            """\
            import os
            import unittest
            class Cases(unittest.TestCase):
                def test_a(self): self.fail("failure")
                def test_b(self): os._exit(1)
            """
        )
        self.assertNotEqual(proc.returncode, 0)
        self.assertNotIn("Isolated retry", proc.stdout)
        self.assertEqual(len(self.records()), 1)

    def run_cargo_suite(self, extra_failed=0, incomplete=False):
        # Model two targets with the same test name. A retry must select both the target and the case.
        cargo = self.root / "cargo"
        cargo.write_text(
            textwrap.dedent(
                f"""\
                #!{sys.executable}
                import sys
                first = sys.argv[1:] == ["test", "--no-fail-fast", "--", "--show-output"]
                if first:
                    print("     Running unittests src/main.rs (target/debug/unit)")
                    print("running 1 test\\ntest duplicate ... ok")
                    print("test result: ok. 1 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.01s")
                else:
                    assert sys.argv[1:] == ["test", "--test", "integration", "--", "--exact", "duplicate", "--show-output"]
                print("     Running tests/integration.rs (target/debug/integration)")
                print("running 1 test")
                print("test duplicate ... " + ("FAILED" if first else "ok"))
                outcome = "FAILED" if first else "ok"
                print(f"test result: {{outcome}}. {{int(not first)}} passed; {{int(first) + {extra_failed}}} failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.01s")
                if first:
                    print("error: test failed, to rerun pass `--test integration`")
                    if {incomplete}:
                        print("     Running tests/crash.rs (target/debug/crash)")
                        print("error: test failed, to rerun pass `--test crash`")
                    print("error: 1 target failed:")
                    sys.exit(101)
                """
            )
        )
        cargo.chmod(0o755)
        return self.run_suite(
            "cargo",
            "--output",
            str(self.out),
            "--",
            "cargo",
            "test",
            "--no-fail-fast",
            "--",
            "--show-output",
            env={**os.environ, "PATH": f"{self.root}:{os.environ['PATH']}"},
        )

    def test_cargo_retries_only_the_exact_target_and_case(self):
        proc = self.run_cargo_suite()
        self.assertEqual(proc.returncode, 0, proc.stderr + proc.stdout)
        self.assertEqual(len(self.records()), 4)
        self.assertCountEqual(
            [label["value"] for case in self.records() for label in case["labels"] if label["name"] == "retryAttempt"],
            ["1", "2"],
        )

    def test_cargo_blocks_when_some_failures_could_not_be_parsed(self):
        proc = self.run_cargo_suite(extra_failed=1)
        self.assertNotEqual(proc.returncode, 0)
        self.assertNotIn("Isolated retry", proc.stdout)

    def test_cargo_blocks_when_another_target_crashed(self):
        proc = self.run_cargo_suite(incomplete=True)
        self.assertNotEqual(proc.returncode, 0)
        self.assertNotIn("Isolated retry", proc.stdout)
