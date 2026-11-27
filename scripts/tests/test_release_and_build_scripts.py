"""Unit tests for CI release, build, and helper scripts under scripts/ci/."""

from __future__ import annotations

import json
import os
import shutil
import stat
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path
from typing import Dict, List

REPO_ROOT = Path(__file__).resolve().parents[2]
CI_SCRIPTS = REPO_ROOT / "scripts" / "ci"


def _write_executable(path: Path, content: str) -> None:
    path.write_text(content, encoding="utf-8")
    path.chmod(path.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)


def _create_dist_files(workdir: Path) -> None:
    dist = workdir / "dist"
    dist.mkdir(parents=True, exist_ok=True)
    (dist / "app-debug.apk").write_bytes(b"fake-apk")
    for name in (
        "agentsterm-daemon-linux-x86_64",
        "agentsterm-daemon-linux-x86_64.sha256",
        "agentsterm-daemon-linux-aarch64",
        "agentsterm-daemon-linux-aarch64.sha256",
    ):
        (dist / name).write_bytes(b"fake-asset")


class ReleaseAndBuildScriptsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="agentsterm-ci-scripts-"))
        self.work = self.tmp / "work"
        self.work.mkdir()
        self.fake_bin = self.tmp / "bin"
        self.fake_bin.mkdir()
        self.log_file = self.tmp / "invocations.jsonl"

    def tearDown(self) -> None:
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _install_fake_gh(
        self,
        *,
        existing_tags: List[str],
        existing_release_views: List[str],
        latest_ref_exists: bool = True,
    ) -> None:
        tags_text = "\n".join(existing_tags)
        views_json = json.dumps(existing_release_views)
        _write_executable(
            self.fake_bin / "gh",
            textwrap.dedent(
                f"""\
                #!/usr/bin/env python3
                import json, sys
                args = sys.argv[1:]
                with open({str(self.log_file)!r}, "a", encoding="utf-8") as fh:
                    fh.write(json.dumps(args) + "\\n")

                if args[:1] == ["api"] and len(args) >= 2:
                    endpoint = args[1]
                    if "releases?per_page=30" in endpoint:
                        print({tags_text!r})
                        sys.exit(0)
                    if endpoint.endswith("/git/ref/tags/latest"):
                        sys.exit(0 if {latest_ref_exists!r} else 1)
                    if endpoint.endswith("/git/refs/tags/latest"):
                        sys.exit(0)

                if args[:2] == ["release", "view"] and len(args) >= 3:
                    tag = args[2]
                    sys.exit(0 if tag in {views_json} else 1)

                sys.exit(0)
                """
            ),
        )

    def _read_invocations(self) -> List[List[str]]:
        if not self.log_file.exists():
            return []
        return [json.loads(line) for line in self.log_file.read_text(encoding="utf-8").splitlines() if line]

    def _base_env(self, **extra: str) -> Dict[str, str]:
        return {
            **os.environ,
            "PATH": f"{self.fake_bin}:{os.environ.get('PATH', '')}",
            **extra,
        }

    def test_publish_newest_versioned_release_updates_latest(self) -> None:
        _create_dist_files(self.work)
        self._install_fake_gh(
            existing_tags=["latest", "v0.3.285-aaaaaaa", "v0.3.286-bbbbbbb-preview", "v0.3.280-1111111"],
            existing_release_views=["latest"],
            latest_ref_exists=True,
        )

        proc = subprocess.run(
            [str(CI_SCRIPTS / "publish_versioned_releases.sh")],
            cwd=self.work,
            env=self._base_env(
                APP_MAJOR_MINOR="0.3",
                GITHUB_SHA="1234567890abcdef1234567890abcdef12345678",
                GITHUB_RUN_ID="999",
                GITHUB_RUN_NUMBER="287",
                GITHUB_REPOSITORY="AppEvol/agents-term",
            ),
            capture_output=True,
            text=True,
        )
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)

        calls = self._read_invocations()
        create_call = next(c for c in calls if c[:2] == ["release", "create"])
        self.assertEqual(create_call[2], "v0.3.287-1234567")
        self.assertIn("--latest", create_call)
        self.assertNotIn("--latest=false", create_call)
        self.assertIn("--notes-start-tag", create_call)
        self.assertEqual(create_call[create_call.index("--notes-start-tag") + 1], "v0.3.285-aaaaaaa")
        self.assertTrue((self.work / "dist" / "AgentsTerm-debug.apk").is_file())
        self.assertTrue((self.work / "dist" / "AgentsTerm-v0.3.287-1234567-debug.apk").is_file())

        patch_call = next(c for c in calls if c[:1] == ["api"] and "-X" in c and "PATCH" in c)
        self.assertEqual(patch_call[1], "repos/AppEvol/agents-term/git/refs/tags/latest")

        upload_latest = next(c for c in calls if c[:3] == ["release", "upload", "latest"])
        self.assertIn("--clobber", upload_latest)
        edit_latest = next(c for c in calls if c[:3] == ["release", "edit", "latest"])
        self.assertIn("--latest=false", edit_latest)

    def test_publish_older_rerun_skips_rolling_latest(self) -> None:
        _create_dist_files(self.work)
        self._install_fake_gh(
            existing_tags=["v0.3.290-fffffff", "v0.3.285-aaaaaaa", "v0.3.280-1111111"],
            existing_release_views=["latest"],
            latest_ref_exists=True,
        )

        proc = subprocess.run(
            [str(CI_SCRIPTS / "publish_versioned_releases.sh")],
            cwd=self.work,
            env=self._base_env(
                APP_MAJOR_MINOR="0.3",
                GITHUB_SHA="abcdef1234567890abcdef1234567890abcdef12",
                GITHUB_RUN_ID="998",
                GITHUB_RUN_NUMBER="287",
                GITHUB_REPOSITORY="AppEvol/agents-term",
            ),
            capture_output=True,
            text=True,
        )
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)

        calls = self._read_invocations()
        create_call = next(c for c in calls if c[:2] == ["release", "create"])
        self.assertIn("--latest=false", create_call)
        self.assertEqual(create_call[create_call.index("--notes-start-tag") + 1], "v0.3.285-aaaaaaa")
        self.assertFalse(any(c[:3] == ["release", "upload", "latest"] for c in calls))
        self.assertFalse(any("-X" in c and "PATCH" in c for c in calls))

    def test_publish_existing_tag_uploads_and_edits(self) -> None:
        _create_dist_files(self.work)
        self._install_fake_gh(
            existing_tags=["v0.3.287-1234567"],
            existing_release_views=["v0.3.287-1234567", "latest"],
        )

        proc = subprocess.run(
            [str(CI_SCRIPTS / "publish_versioned_releases.sh")],
            cwd=self.work,
            env=self._base_env(
                APP_MAJOR_MINOR="0.3",
                GITHUB_SHA="1234567890abcdef1234567890abcdef12345678",
                GITHUB_RUN_ID="1000",
                GITHUB_RUN_NUMBER="287",
                GITHUB_REPOSITORY="AppEvol/agents-term",
            ),
            capture_output=True,
            text=True,
        )
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)

        calls = self._read_invocations()
        self.assertFalse(any(c[:2] == ["release", "create"] for c in calls))
        self.assertTrue(any(c[:3] == ["release", "upload", "v0.3.287-1234567"] for c in calls))
        self.assertTrue(any(c[:3] == ["release", "edit", "v0.3.287-1234567"] for c in calls))

    def test_publish_branch_prerelease_creates_preview_release(self) -> None:
        _create_dist_files(self.work)
        self._install_fake_gh(existing_tags=[], existing_release_views=[])

        proc = subprocess.run(
            [str(CI_SCRIPTS / "publish_branch_prerelease.sh")],
            cwd=self.work,
            env=self._base_env(
                APP_MAJOR_MINOR="0.3",
                GITHUB_SHA="deadbeef1234567890",
                GITHUB_RUN_ID="777",
                GITHUB_RUN_NUMBER="42",
                GITHUB_REPOSITORY="AppEvol/agents-term",
                GITHUB_REF_NAME="feat/demo",
            ),
            capture_output=True,
            text=True,
        )
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)

        calls = self._read_invocations()
        self.assertEqual(len(calls), 1)
        create_call = calls[0]
        self.assertEqual(create_call[:3], ["release", "create", "v0.3.42-deadbee-preview"])
        self.assertIn("--prerelease", create_call)
        self.assertIn("--latest=false", create_call)
        notes = create_call[create_call.index("--notes") + 1]
        self.assertIn("`feat/demo`", notes)
        self.assertIn("https://github.com/AppEvol/agents-term/releases/download/v0.3.42-deadbee-preview", notes)
        self.assertTrue((self.work / "dist" / "AgentsTerm-v0.3.42-deadbee-preview-debug.apk").is_file())

    def test_build_and_unit_test_sets_version_and_daemon_url(self) -> None:
        capture_file = self.tmp / "gradle_capture.json"
        _write_executable(
            self.fake_bin / "gradle",
            textwrap.dedent(
                f"""\
                #!/usr/bin/env python3
                import json, os, sys
                from pathlib import Path
                if ":app:testDebugUnitTest" in sys.argv:
                    reports = Path("app/build/test-results/testDebugUnitTest")
                    reports.mkdir(parents=True)
                    (reports / "TEST-Smoke.xml").write_text(
                        '<testsuite tests="1"><testcase classname="Smoke" name="run"/></testsuite>'
                    )
                with open({str(capture_file)!r}, "w", encoding="utf-8") as fh:
                    json.dump({{
                        "args": sys.argv[1:],
                        "APP_VERSION_NAME": os.environ.get("APP_VERSION_NAME"),
                        "AGENTSTERM_DAEMON_RELEASE_BASE_URL": os.environ.get("AGENTSTERM_DAEMON_RELEASE_BASE_URL"),
                        "AGENTSTERM_EXPECTED_DAEMON_RELEASE_TAG": os.environ.get(
                            "AGENTSTERM_EXPECTED_DAEMON_RELEASE_TAG"
                        ),
                    }}, fh)
                """
            ),
        )

        cases = [
            ("false", "false", None, None),
            (
                "true",
                "false",
                "https://github.com/AppEvol/agents-term/releases/download/v0.3.55-1234567",
                "v0.3.55-1234567",
            ),
            (
                "false",
                "true",
                "https://github.com/AppEvol/agents-term/releases/download/v0.3.55-1234567-preview",
                "v0.3.55-1234567-preview",
            ),
        ]
        for pub_rel, pub_pre, expected_url, expected_tag in cases:
            with self.subTest(pub_rel=pub_rel, pub_pre=pub_pre):
                env = self._base_env(
                    APP_MAJOR_MINOR="0.3",
                    APP_VERSION_CODE="55",
                    GITHUB_SHA_FULL="1234567890abcdef",
                    PUBLISH_RELEASE=pub_rel,
                    PUBLISH_PRERELEASE=pub_pre,
                    GITHUB_REPOSITORY="AppEvol/agents-term",
                )
                env.pop("AGENTSTERM_DAEMON_RELEASE_BASE_URL", None)
                env.pop("AGENTSTERM_EXPECTED_DAEMON_RELEASE_TAG", None)
                proc = subprocess.run(
                    [str(CI_SCRIPTS / "build_and_unit_test.sh")],
                    cwd=self.work,
                    env=env,
                    capture_output=True,
                    text=True,
                )
                self.assertEqual(proc.returncode, 0, msg=proc.stderr)
                captured = json.loads(capture_file.read_text(encoding="utf-8"))
                self.assertEqual(captured["APP_VERSION_NAME"], "0.3.55-1234567")
                self.assertEqual(captured["AGENTSTERM_DAEMON_RELEASE_BASE_URL"], expected_url)
                self.assertEqual(captured["AGENTSTERM_EXPECTED_DAEMON_RELEASE_TAG"], expected_tag)
                self.assertIn(":app:testDebugUnitTest", captured["args"])

    def test_set_build_version_env_stage_and_stop_helpers(self) -> None:
        github_env = self.tmp / "github_env.txt"
        github_env.write_text("", encoding="utf-8")
        proc = subprocess.run(
            [str(CI_SCRIPTS / "set_build_version_env.sh")],
            env=self._base_env(
                APP_MAJOR_MINOR="0.3",
                GITHUB_SHA="feedface9999",
                GITHUB_RUN_NUMBER="101",
                GITHUB_ENV=str(github_env),
            ),
            capture_output=True,
            text=True,
        )
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)
        self.assertEqual(
            github_env.read_text(encoding="utf-8").splitlines(),
            ["APP_VERSION_CODE=101", "APP_VERSION_NAME=0.3.101-feedfac"],
        )

        src = self.work / "dist-daemon" / "agentsterm-daemon-linux-x86_64"
        src.parent.mkdir(parents=True)
        src.write_bytes(b"binary")
        proc = subprocess.run(
            [str(CI_SCRIPTS / "stage_daemon_binary.sh")],
            cwd=self.work,
            env=self._base_env(),
            capture_output=True,
            text=True,
        )
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)
        staged = self.work / "daemon-rs/target/x86_64-unknown-linux-musl/release/agentsterm-daemon"
        self.assertEqual(staged.read_bytes(), b"binary")
        self.assertTrue(os.access(staged, os.X_OK))

        _write_executable(
            self.fake_bin / "docker",
            f'#!/usr/bin/env bash\necho "$@" >> "{self.log_file}"\n',
        )
        snap = self.tmp / "snapshot-dir"
        snap.mkdir()
        (snap / "marker").write_text("x", encoding="utf-8")
        proc = subprocess.run(
            [str(CI_SCRIPTS / "stop_docker_vm.sh")],
            env=self._base_env(SNAPSHOT_DIR=str(snap)),
            capture_output=True,
            text=True,
        )
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)
        self.assertFalse(snap.exists())
        docker_log = self.log_file.read_text(encoding="utf-8")
        self.assertIn("logs agentsterm-ci-vm", docker_log)
        self.assertIn("rm -f agentsterm-ci-vm", docker_log)

    def test_start_docker_vm_writes_github_output(self) -> None:
        fake_binary = self.work / "dist-daemon" / "agentsterm-daemon-linux-x86_64"
        fake_binary.parent.mkdir(parents=True)
        fake_binary.write_bytes(b"fake")

        start_vm = self.work / "e2e" / "docker-vm" / "start_vm.sh"
        start_vm.parent.mkdir(parents=True)
        start_vm.write_text(
            "#!/usr/bin/env bash\necho 'SNAPSHOT_DIR=/tmp/fake-snap'\n",
            encoding="utf-8",
        )

        github_output = self.tmp / "vm_output.txt"
        github_output.write_text("", encoding="utf-8")
        proc = subprocess.run(
            [str(CI_SCRIPTS / "start_docker_vm.sh")],
            cwd=self.work,
            env=self._base_env(GITHUB_OUTPUT=str(github_output)),
            capture_output=True,
            text=True,
        )
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)
        self.assertTrue(os.access(fake_binary, os.X_OK))
        self.assertEqual(github_output.read_text(encoding="utf-8").strip(), "SNAPSHOT_DIR=/tmp/fake-snap")


if __name__ == "__main__":
    unittest.main()
