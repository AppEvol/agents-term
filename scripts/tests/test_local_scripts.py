"""Unit tests for local-development scripts in scripts/."""

from __future__ import annotations

import base64
import fcntl
import os
import platform
import shutil
import stat
import subprocess
import tempfile
import textwrap
import time
import unittest
from pathlib import Path
from typing import Dict

REPO_ROOT = Path(__file__).resolve().parents[2]
ENSURE_KEYSTORE = REPO_ROOT / "scripts" / "ensure-debug-keystore.sh"
INSTALL_SDK = REPO_ROOT / "scripts" / "install-android-sdk.sh"
BUILD_GHOSTTY_VT = REPO_ROOT / "scripts" / "build-libghostty-vt.sh"


def _write_executable(path: Path, content: str) -> None:
    path.write_text(content, encoding="utf-8")
    path.chmod(path.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)


def _create_fake_ndk(path: Path, revision: str | None = None) -> Path:
    (path / "toolchains" / "llvm").mkdir(parents=True, exist_ok=True)
    if revision is not None:
        (path / "source.properties").write_text(
            f"Pkg.Desc = Android NDK\nPkg.Revision = {revision}\n",
            encoding="utf-8",
        )
    return path


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
        env = {**os.environ, "PATH": f"{self.fake_bin}:{os.environ.get('PATH', '')}"}
        for key in (
            "DEBUG_KEYSTORE_BASE64",
            "ANDROID_PLATFORM",
            "ANDROID_BUILD_TOOLS",
            "ANDROID_NDK_PACKAGE",
            "ANDROID_NDK",
            "ANDROID_NDK_HOME",
            "ANDROID_NDK_ROOT",
            "ANDROID_NDK_VERSION",
            "GHOSTTY_VT_ANDROID_NDK_HOME",
        ):
            env.pop(key, None)
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

    def test_install_android_sdk_ignores_preexported_android_ndk_path(self) -> None:
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
            env=self._base_env(
                ANDROID_HOME=str(sdk_root),
                ANDROID_NDK="/usr/local/lib/android/sdk/ndk/27.3.13750724",
            ),
            capture_output=True,
            text=True,
        )
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)
        lines = log.read_text(encoding="utf-8").splitlines()
        self.assertEqual(
            lines,
            ["--licenses", "platforms;android-36.1 build-tools;36.0.0 ndk;29.0.14206865"],
        )

    def test_install_android_sdk_respects_android_ndk_package_override(self) -> None:
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
            env=self._base_env(
                ANDROID_HOME=str(sdk_root),
                ANDROID_NDK="/usr/local/lib/android/sdk/ndk/27.3.13750724",
                ANDROID_NDK_PACKAGE="ndk;29.1.00000000",
            ),
            capture_output=True,
            text=True,
        )
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)
        lines = log.read_text(encoding="utf-8").splitlines()
        self.assertEqual(
            lines,
            ["--licenses", "platforms;android-36.1 build-tools;36.0.0 ndk;29.1.00000000"],
        )

    def test_build_libghostty_vt_prefers_pinned_sdk_ndk_over_older_env_ndk(self) -> None:
        sdk_root = self.tmp / "android-sdk"
        ndk_27 = _create_fake_ndk(sdk_root / "ndk" / "27.3.13750724", "27.3.13750724")
        ndk_29 = _create_fake_ndk(sdk_root / "ndk" / "29.0.14206865", "29.0.14206865")

        proc = subprocess.run(
            [str(BUILD_GHOSTTY_VT), "--print-ndk-home"],
            env=self._base_env(
                ANDROID_HOME=str(sdk_root),
                ANDROID_NDK=str(ndk_27),
                ANDROID_NDK_HOME=str(ndk_27),
                ANDROID_NDK_ROOT=str(ndk_27),
            ),
            capture_output=True,
            text=True,
        )
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)
        self.assertEqual(proc.stdout.strip(), str(ndk_29))

    def test_build_libghostty_vt_respects_explicit_ndk_override(self) -> None:
        sdk_root = self.tmp / "android-sdk"
        _create_fake_ndk(sdk_root / "ndk" / "29.0.14206865", "29.0.14206865")
        custom_ndk = _create_fake_ndk(self.tmp / "custom-ndk", "29.0.14206865")

        proc = subprocess.run(
            [str(BUILD_GHOSTTY_VT), "--print-ndk-home"],
            env=self._base_env(
                ANDROID_HOME=str(sdk_root),
                GHOSTTY_VT_ANDROID_NDK_HOME=str(custom_ndk),
            ),
            capture_output=True,
            text=True,
        )
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)
        self.assertEqual(proc.stdout.strip(), str(custom_ndk))

        bad_proc = subprocess.run(
            [str(BUILD_GHOSTTY_VT), "--print-ndk-home"],
            env=self._base_env(
                ANDROID_HOME=str(sdk_root),
                GHOSTTY_VT_ANDROID_NDK_HOME=str(self.tmp / "missing-ndk"),
            ),
            capture_output=True,
            text=True,
        )
        self.assertNotEqual(bad_proc.returncode, 0)
        self.assertIn("GHOSTTY_VT_ANDROID_NDK_HOME", bad_proc.stderr)

    def test_build_libghostty_vt_ignores_older_env_ndk_when_newer_sdk_ndk_exists(self) -> None:
        sdk_root = self.tmp / "android-sdk"
        ndk_27 = _create_fake_ndk(sdk_root / "ndk" / "27.3.13750724", "27.3.13750724")
        ndk_29_newer = _create_fake_ndk(sdk_root / "ndk" / "29.1.99999999", "29.1.99999999")

        proc = subprocess.run(
            [str(BUILD_GHOSTTY_VT), "--print-ndk-home"],
            env=self._base_env(
                ANDROID_HOME=str(sdk_root),
                ANDROID_NDK_HOME=str(ndk_27),
                ANDROID_NDK_ROOT=str(ndk_27),
            ),
            capture_output=True,
            text=True,
        )
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)
        self.assertEqual(proc.stdout.strip(), str(ndk_29_newer))

    def test_build_libghostty_vt_uses_matching_major_env_ndk_when_sdk_lacks_pinned(self) -> None:
        sdk_root = self.tmp / "android-sdk"
        _create_fake_ndk(sdk_root / "ndk" / "27.3.13750724", "27.3.13750724")
        standalone_ndk_29 = _create_fake_ndk(self.tmp / "standalone-ndk-r29", "29.0.14206865")

        proc = subprocess.run(
            [str(BUILD_GHOSTTY_VT), "--print-ndk-home"],
            env=self._base_env(
                ANDROID_HOME=str(sdk_root),
                ANDROID_NDK_HOME=str(standalone_ndk_29),
            ),
            capture_output=True,
            text=True,
        )
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)
        self.assertEqual(proc.stdout.strip(), str(standalone_ndk_29))

    def test_build_libghostty_vt_serializes_shared_cache_with_flock(self) -> None:
        if shutil.which("flock") is None:
            self.skipTest("flock not installed")

        machine = platform.machine()
        if machine == "x86_64":
            host_zig_target = "x86_64-linux-gnu"
        elif machine in ("aarch64", "arm64"):
            host_zig_target = "aarch64-linux-gnu"
        else:
            self.skipTest(f"unsupported host architecture: {machine}")

        cache_dir = self.tmp / "ghostty-cache"
        out_dir = self.tmp / "ghostty-out"
        prefix = cache_dir / "built-1.0.2" / f"{host_zig_target}-ReleaseFast"
        (prefix / "lib").mkdir(parents=True)
        (prefix / "include" / "ghostty").mkdir(parents=True)
        (prefix / "lib" / "libghostty-vt.a").write_bytes(b"fake-archive")
        (prefix / "include" / "ghostty" / "vt.h").write_text("// fake header\n", encoding="utf-8")

        fake_jdk = self.tmp / "fake-jdk"
        (fake_jdk / "include" / "linux").mkdir(parents=True)
        (fake_jdk / "include" / "jni.h").write_text("// fake jni\n", encoding="utf-8")

        _write_executable(
            self.fake_bin / "cc",
            textwrap.dedent(
                """\
                #!/usr/bin/env bash
                set -euo pipefail
                out=""
                while [ $# -gt 0 ]; do
                  if [ "$1" = "-o" ]; then
                    out="$2"
                    shift 2
                    continue
                  fi
                  shift
                done
                printf 'fake-so' > "$out"
                """
            ),
        )

        lock_path = cache_dir / ".lock"
        with lock_path.open("w", encoding="utf-8") as lock_file:
            fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX)
            with subprocess.Popen(
                [str(BUILD_GHOSTTY_VT), "--host-only"],
                env=self._base_env(
                    GHOSTTY_VT_CACHE_DIR=str(cache_dir),
                    GHOSTTY_VT_OUT_DIR=str(out_dir),
                    JAVA_HOME=str(fake_jdk),
                ),
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
            ) as proc:
                try:
                    time.sleep(0.2)
                    self.assertIsNone(proc.poll(), "build-libghostty-vt.sh should block while cache lock is held")
                finally:
                    fcntl.flock(lock_file.fileno(), fcntl.LOCK_UN)
                stdout, stderr = proc.communicate(timeout=10)
                self.assertEqual(proc.returncode, 0, msg=f"stdout={stdout}\nstderr={stderr}")

        self.assertTrue((out_dir / "host" / "libghostty_vt_jni.so").is_file())


if __name__ == "__main__":
    unittest.main()
