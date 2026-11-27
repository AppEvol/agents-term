"""Unit tests for local-development scripts in scripts/."""

from __future__ import annotations

import base64
import os
import shutil
import stat
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path
from typing import Dict

REPO_ROOT = Path(__file__).resolve().parents[2]
ENSURE_KEYSTORE = REPO_ROOT / "scripts" / "ensure-debug-keystore.sh"
INSTALL_SDK = REPO_ROOT / "scripts" / "install-android-sdk.sh"


def _write_executable(path: Path, content: str) -> None:
    path.write_text(content, encoding="utf-8")
    path.chmod(path.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)


class LocalScriptsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="agentsterm-local-scripts-"))
        self.work = self.tmp / "work"
        self.work.mkdir()
        self.fake_bin = self.tmp / "bin"
        self.fake_bin.mkdir()

    def tearDown(self) -> None:
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _base_env(self, **extra: str) -> Dict[str, str]:
        env = {**os.environ, "PATH": f"{self.fake_bin}:{os.environ.get('PATH', '')}", **extra}
        env.pop("DEBUG_KEYSTORE_BASE64", None)
        env.update(extra)
        return env

    def test_ensure_debug_keystore_keeps_existing_file(self) -> None:
        keystore = self.work / "debug.keystore"
        keystore.write_bytes(b"existing-keystore")
        _write_executable(self.fake_bin / "keytool", "#!/usr/bin/env bash\nexit 99\n")

        proc = subprocess.run(
            [str(ENSURE_KEYSTORE)],
            cwd=self.work,
            env=self._base_env(DEBUG_KEYSTORE_BASE64=base64.b64encode(b"other").decode()),
            capture_output=True,
            text=True,
        )
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)
        self.assertEqual(keystore.read_bytes(), b"existing-keystore")

    def test_ensure_debug_keystore_decodes_from_env_var(self) -> None:
        payload = b"secret-keystore-bytes"
        proc = subprocess.run(
            [str(ENSURE_KEYSTORE)],
            cwd=self.work,
            env=self._base_env(DEBUG_KEYSTORE_BASE64=base64.b64encode(payload).decode()),
            capture_output=True,
            text=True,
        )
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)
        self.assertEqual((self.work / "debug.keystore").read_bytes(), payload)

    def test_ensure_debug_keystore_decodes_from_base64_file(self) -> None:
        payload = b"file-keystore-bytes"
        (self.work / "debug.keystore.base64").write_text(base64.b64encode(payload).decode(), encoding="utf-8")

        proc = subprocess.run(
            [str(ENSURE_KEYSTORE)],
            cwd=self.work,
            env=self._base_env(),
            capture_output=True,
            text=True,
        )
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)
        self.assertEqual((self.work / "debug.keystore").read_bytes(), payload)

    def test_ensure_debug_keystore_generates_with_keytool(self) -> None:
        log = self.tmp / "keytool.log"
        _write_executable(
            self.fake_bin / "keytool",
            textwrap.dedent(
                f"""\
                #!/usr/bin/env bash
                set -euo pipefail
                printf '%s\\n' "$*" > "{log}"
                touch debug.keystore
                """
            ),
        )

        proc = subprocess.run(
            [str(ENSURE_KEYSTORE)],
            cwd=self.work,
            env=self._base_env(),
            capture_output=True,
            text=True,
        )
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)
        self.assertTrue((self.work / "debug.keystore").is_file())
        args = log.read_text(encoding="utf-8")
        self.assertIn("-genkeypair", args)
        self.assertIn("-keystore debug.keystore", args)
        self.assertIn("-alias androiddebugkey", args)

    def test_install_android_sdk_runs_sdkmanager(self) -> None:
        sdk_root = self.tmp / "android-sdk"
        sdk_bin = sdk_root / "cmdline-tools" / "latest" / "bin"
        sdk_bin.mkdir(parents=True)
        log = self.tmp / "sdkmanager.log"
        _write_executable(
            sdk_bin / "sdkmanager",
            textwrap.dedent(
                f"""\
                #!/usr/bin/env bash
                printf '%s\\n' "$*" >> "{log}"
                """
            ),
        )

        proc = subprocess.run(
            [str(INSTALL_SDK)],
            env=self._base_env(ANDROID_HOME=str(sdk_root)),
            capture_output=True,
            text=True,
        )
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)
        lines = log.read_text(encoding="utf-8").splitlines()
        self.assertEqual(
            lines,
            ["--licenses", "platforms;android-36.1 build-tools;36.0.0 ndk;29.0.14206865"],
        )

    def test_install_android_sdk_fails_without_android_home(self) -> None:
        env = self._base_env()
        env.pop("ANDROID_HOME", None)
        env.pop("ANDROID_SDK_ROOT", None)
        proc = subprocess.run([str(INSTALL_SDK)], env=env, capture_output=True, text=True)
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("ANDROID_HOME", proc.stderr)


if __name__ == "__main__":
    unittest.main()
