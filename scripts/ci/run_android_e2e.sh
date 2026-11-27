#!/usr/bin/env bash
# Forwards the CI Docker VM's SSH and daemon ports into the running Android
# emulator and executes the connectedDebugAndroidTest suite.
set -euo pipefail

adb reverse tcp:2222 tcp:2222
adb reverse tcp:7591 tcp:7591
gradle :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.realVmSshHost=127.0.0.1 \
  -Pandroid.testInstrumentationRunnerArguments.realVmSshPort=2222 \
  -Pandroid.testInstrumentationRunnerArguments.realVmSshUser=vmuser \
  -Pandroid.testInstrumentationRunnerArguments.realVmSshPass=agentsterm-e2e-pass \
  -Pandroid.testInstrumentationRunnerArguments.realVmDaemonPort=7591 \
  --stacktrace
