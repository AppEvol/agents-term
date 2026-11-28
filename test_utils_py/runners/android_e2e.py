"""Forward CI Docker VM ports into the Android emulator and run connectedDebugAndroidTest."""

from __future__ import annotations

import subprocess
import sys
from typing import Optional, Sequence, Tuple

from test_utils_py.runners.android_retries import run_suite_locked

REQUIRED_ADB_COMMANDS: Tuple[Tuple[str, ...], ...] = (
    ("adb", "reverse", "tcp:2222", "tcp:2222"),
    ("adb", "reverse", "tcp:7591", "tcp:7591"),
)

# Prevent system crash/ANR dialogs and lock-screen overlays from stealing window focus.
BEST_EFFORT_ADB_COMMANDS: Tuple[Tuple[str, ...], ...] = (
    ("adb", "shell", "settings", "put", "global", "hide_error_dialogs", "1"),
    ("adb", "shell", "settings", "put", "secure", "anr_show_background", "0"),
    ("adb", "shell", "wm", "dismiss-keyguard"),
)

DEFAULT_INSTRUMENTATION_ARGS: Tuple[str, ...] = (
    "-Pandroid.testInstrumentationRunnerArguments.realVmSshHost=127.0.0.1",
    "-Pandroid.testInstrumentationRunnerArguments.realVmSshPort=2222",
    "-Pandroid.testInstrumentationRunnerArguments.realVmSshUser=vmuser",
    "-Pandroid.testInstrumentationRunnerArguments.realVmSshPass=agentsterm-e2e-pass",
    "-Pandroid.testInstrumentationRunnerArguments.realVmDaemonPort=7591",
)


def prepare_emulator() -> None:
    """Configure ADB reverse tunnels and suppress system dialogs on the connected emulator."""
    for cmd in REQUIRED_ADB_COMMANDS:
        subprocess.run(list(cmd), check=True)
    for cmd in BEST_EFFORT_ADB_COMMANDS:
        subprocess.run(list(cmd), check=False)


def run_android_e2e(extra_gradle_args: Sequence[str] = ()) -> int:
    """Prepare the emulator and run the locked android-e2e Gradle suite with isolated retries."""
    prepare_emulator()
    gradle_args = [*DEFAULT_INSTRUMENTATION_ARGS, *extra_gradle_args]
    return 0 if run_suite_locked("android-e2e", gradle_args) else 1


def main(argv: Optional[Sequence[str]] = None) -> int:
    extra = list(sys.argv[1:] if argv is None else argv)
    try:
        return run_android_e2e(extra)
    except subprocess.CalledProcessError as exc:
        return exc.returncode or 1
