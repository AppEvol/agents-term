"""Unit tests for scripts/ci/daemon_change_check.sh."""

from __future__ import annotations

import hashlib
import os
import shutil
import stat
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path
from typing import Dict, Optional, Tuple

REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "scripts" / "ci" / "daemon_change_check.sh"
EXPECTED_HASH = "a" * 40


def _write_executable(path: Path, content: str) -> None:
    path.write_text(content, encoding="utf-8")
    path.chmod(path.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)


def _parse_kv_file(path: Path) -> Dict[str, str]:
    result: Dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            result[key] = value
    return result


class DaemonChangeCheckTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="agentsterm-change-check-"))
        self.repo = self.tmp / "repo"
        self.repo.mkdir()
        self.fake_bin = self.tmp / "bin"
        self.fake_bin.mkdir()
        self.hash_script = self.tmp / "fake-hash.py"
        self.hash_script.write_text(f"print({EXPECTED_HASH!r})\n", encoding="utf-8")

        self._git("init", "-q")
        self._git("config", "user.name", "Test User")
        self._git("config", "user.email", "test@example.com")

        self._write_repo_file("README.md", "# Repo\n")
        self._write_repo_file("docs/architecture.md", "# Arch\n")
        self._write_repo_file("docs/live-debugging.md", "# Live\n")
        self._write_repo_file("docs/agents_debugging_tips.md", "# Tips\n")
        self._write_repo_file("app/Main.kt", "fun main() {}\n")
        self._write_repo_file("daemon-rs/install.sh", "#!/bin/sh\n")
        self._write_repo_file("daemon-rs/tests/integration.rs", "// test\n")
        self._write_repo_file("daemon-rs/tests/test_install.py", "# test\n")
        self._write_repo_file("e2e/docker-vm/test_e2e.py", "# test\n")
        self._git("add", ".")
        self._git("commit", "-q", "-m", "initial")
        self.base_sha = self._git("rev-parse", "HEAD").stdout.strip()

    def tearDown(self) -> None:
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _git(self, *args: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            ["git", *args],
            cwd=self.repo,
            check=True,
            capture_output=True,
            text=True,
        )

    def _write_repo_file(self, rel: str, content: str) -> None:
        path = self.repo / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    def _commit_change(self, rel: str, content: str) -> None:
        self._write_repo_file(rel, content)
        self._git("add", rel)
        self._git("commit", "-q", "-m", f"update {rel}")

    def _install_fake_gh(
        self,
        *,
        published_hash: Optional[str] = EXPECTED_HASH,
        fail_download: bool = False,
        bad_sha256: bool = False,
    ) -> None:
        if fail_download:
            _write_executable(self.fake_bin / "gh", "#!/usr/bin/env bash\nexit 1\n")
            return

        assert published_hash is not None
        x86_bytes = (
            "#!/usr/bin/env bash\n"
            f'if [ "${{1:-}}" = "version" ] && [ "${{2:-}}" = "--hash-only" ]; then echo "{published_hash}"; fi\n'
        ).encode()
        arm_bytes = b"#!/usr/bin/env bash\necho arm64\n"

        x86_sha = "0" * 64 if bad_sha256 else hashlib.sha256(x86_bytes).hexdigest()
        arm_sha = hashlib.sha256(arm_bytes).hexdigest()

        assets_dir = self.tmp / "release-assets"
        assets_dir.mkdir(exist_ok=True)
        (assets_dir / "agentsterm-daemon-linux-x86_64").write_bytes(x86_bytes)
        (assets_dir / "agentsterm-daemon-linux-x86_64.sha256").write_text(
            f"{x86_sha}  agentsterm-daemon-linux-x86_64\n",
            encoding="utf-8",
        )
        (assets_dir / "agentsterm-daemon-linux-aarch64").write_bytes(arm_bytes)
        (assets_dir / "agentsterm-daemon-linux-aarch64.sha256").write_text(
            f"{arm_sha}  agentsterm-daemon-linux-aarch64\n",
            encoding="utf-8",
        )

        _write_executable(
            self.fake_bin / "gh",
            textwrap.dedent(
                f"""\
                #!/usr/bin/env bash
                set -euo pipefail
                dir="."
                while [ $# -gt 0 ]; do
                  if [ "$1" = "--dir" ]; then
                    dir="$2"
                    shift 2
                  else
                    shift
                  fi
                done
                cp "{assets_dir}"/agentsterm-daemon-linux-* "$dir/"
                """
            ),
        )

    def _run_script(self, **extra_env: str) -> Tuple[Dict[str, str], str]:
        output_file = self.tmp / "github_output.txt"
        summary_file = self.tmp / "github_summary.md"
        output_file.write_text("", encoding="utf-8")
        summary_file.write_text("", encoding="utf-8")

        env = {
            **os.environ,
            "PATH": f"{self.fake_bin}:{os.environ.get('PATH', '')}",
            "GITHUB_REPOSITORY": "AppEvol/agents-term",
            "GITHUB_OUTPUT": str(output_file),
            "GITHUB_STEP_SUMMARY": str(summary_file),
            "DAEMON_BUILD_HASH_SCRIPT": str(self.hash_script),
            **extra_env,
        }
        proc = subprocess.run(
            [str(SCRIPT)],
            cwd=self.repo,
            env=env,
            capture_output=True,
            text=True,
        )
        self.assertEqual(proc.returncode, 0, msg=f"stdout:\n{proc.stdout}\nstderr:\n{proc.stderr}")
        return _parse_kv_file(output_file), summary_file.read_text(encoding="utf-8")

    def test_matching_published_hash_reuses_binaries(self) -> None:
        self._install_fake_gh(published_hash=EXPECTED_HASH)
        self._commit_change("app/Main.kt", "fun main() { println(1) }\n")

        outputs, summary = self._run_script(EVENT_NAME="pull_request", PR_BASE_SHA=self.base_sha)
        self.assertEqual(
            outputs,
            {
                "binary_changed": "false",
                "run_rust_daemon_build": "false",
                "run_daemon_vm_e2e": "false",
                "run_installer_tests": "false",
                "run_android_build": "true",
            },
        )
        self.assertTrue((self.repo / "dist-daemon" / "agentsterm-daemon-linux-x86_64").is_file())
        self.assertIn("Binary changed: `false`", summary)

    def test_mismatched_published_hash_rebuilds_and_cleans_dist(self) -> None:
        self._install_fake_gh(published_hash="b" * 40)
        self._commit_change("app/Main.kt", "fun main() { println(2) }\n")

        outputs, _ = self._run_script(EVENT_NAME="pull_request", PR_BASE_SHA=self.base_sha)
        self.assertEqual(outputs["binary_changed"], "true")
        self.assertEqual(outputs["run_rust_daemon_build"], "true")
        self.assertEqual(outputs["run_daemon_vm_e2e"], "true")
        self.assertEqual(outputs["run_installer_tests"], "true")
        self.assertFalse((self.repo / "dist-daemon").exists())

    def test_bad_sha256_triggers_rebuild(self) -> None:
        self._install_fake_gh(published_hash=EXPECTED_HASH, bad_sha256=True)

        outputs, _ = self._run_script(EVENT_NAME="pull_request", PR_BASE_SHA=self.base_sha)
        self.assertEqual(outputs["binary_changed"], "true")
        self.assertFalse((self.repo / "dist-daemon").exists())

    def test_missing_release_triggers_rebuild(self) -> None:
        self._install_fake_gh(fail_download=True)

        outputs, _ = self._run_script(EVENT_NAME="pull_request", PR_BASE_SHA=self.base_sha)
        self.assertEqual(outputs["binary_changed"], "true")
        self.assertFalse((self.repo / "dist-daemon").exists())

    def test_docs_only_pull_request_skips_android_build(self) -> None:
        self._install_fake_gh(published_hash=EXPECTED_HASH)
        self._commit_change("docs/architecture.md", "# Updated\n")
        self._commit_change("README.md", "# Updated README\n")

        outputs, summary = self._run_script(EVENT_NAME="pull_request", PR_BASE_SHA=self.base_sha)
        self.assertEqual(outputs["run_android_build"], "false")
        self.assertIn("Documentation-only pull request: `true`", summary)

    def test_docs_only_merge_group_skips_android_build(self) -> None:
        self._install_fake_gh(published_hash=EXPECTED_HASH)
        self._commit_change("docs/architecture.md", "# Updated\n")

        outputs, summary = self._run_script(EVENT_NAME="merge_group", MERGE_GROUP_BASE_SHA=self.base_sha)
        self.assertEqual(outputs["run_android_build"], "false")
        self.assertIn("Documentation-only pull request: `true`", summary)

    def test_embedded_docs_do_not_skip_android_build(self) -> None:
        self._install_fake_gh(published_hash=EXPECTED_HASH)
        for doc in ("docs/live-debugging.md", "docs/agents_debugging_tips.md"):
            with self.subTest(doc=doc):
                self._git("reset", "--hard", "-q", self.base_sha)
                self._commit_change(doc, "# Embedded doc change\n")
                outputs, _ = self._run_script(EVENT_NAME="pull_request", PR_BASE_SHA=self.base_sha)
                self.assertEqual(outputs["run_android_build"], "true")

    def test_docs_only_push_and_dispatch_still_run_android_build(self) -> None:
        self._install_fake_gh(published_hash=EXPECTED_HASH)
        self._commit_change("docs/architecture.md", "# Updated\n")

        push_outputs, _ = self._run_script(EVENT_NAME="push", PUSH_BEFORE_SHA=self.base_sha)
        self.assertEqual(push_outputs["run_android_build"], "true")

        dispatch_outputs, _ = self._run_script(EVENT_NAME="workflow_dispatch")
        self.assertEqual(dispatch_outputs["run_android_build"], "true")

    def test_specific_test_path_triggers_and_force_flag(self) -> None:
        self._install_fake_gh(published_hash=EXPECTED_HASH)

        cases = [
            ("daemon-rs/tests/integration.rs", "true", "false", "false"),
            ("e2e/docker-vm/test_e2e.py", "false", "true", "false"),
            ("test_utils_py/retry.py", "true", "true", "true"),
            ("scripts/native_test_retries.py", "true", "true", "true"),
            ("daemon-rs/install.sh", "false", "false", "true"),
            ("daemon-rs/tests/test_install.py", "false", "false", "true"),
        ]
        for path, exp_rust, exp_vm, exp_inst in cases:
            with self.subTest(path=path):
                self._git("reset", "--hard", "-q", self.base_sha)
                self._commit_change(path, "# changed\n")
                outputs, _ = self._run_script(EVENT_NAME="pull_request", PR_BASE_SHA=self.base_sha)
                self.assertEqual(outputs["binary_changed"], "false")
                self.assertEqual(outputs["run_rust_daemon_build"], exp_rust)
                self.assertEqual(outputs["run_daemon_vm_e2e"], exp_vm)
                self.assertEqual(outputs["run_installer_tests"], exp_inst)

        self._git("reset", "--hard", "-q", self.base_sha)
        outputs, _ = self._run_script(EVENT_NAME="workflow_dispatch", FORCE_DAEMON_TESTS="true")
        self.assertEqual(outputs["binary_changed"], "false")
        self.assertEqual(outputs["run_rust_daemon_build"], "true")
        self.assertEqual(outputs["run_daemon_vm_e2e"], "true")
        self.assertEqual(outputs["run_installer_tests"], "true")


if __name__ == "__main__":
    unittest.main()
