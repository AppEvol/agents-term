# Docker VM End-to-End Test Harness

Provides a realistic Linux VM container (`ubuntu:24.04` with OpenSSH, `cron`, a fake `tailscale` shim, and deterministic POSIX `sh` fakes for `agy`, `claude`, and `codex`) that exercises the real SSH bootstrap, HTTP/WebSocket daemon, agent runners, live app debugging (`app_state_cli`), and live daemon upgrade flows.

## Run Headless Docker VM E2E Locally

```bash
python3 -W error::ResourceWarning -m unittest discover -s e2e/docker-vm -v
```

## Run Android Emulator ↔ Docker VM E2E

```bash
eval "$(bash e2e/docker-vm/start_vm.sh --name agentsterm-ci-vm --ssh-port 2222 --daemon-port 7591)"
adb reverse tcp:2222 tcp:2222
adb reverse tcp:7591 tcp:7591
gradle :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.RealVmDockerE2ETest \
  -Pandroid.testInstrumentationRunnerArguments.realVmSshHost=127.0.0.1 \
  -Pandroid.testInstrumentationRunnerArguments.realVmSshPort=2222 \
  -Pandroid.testInstrumentationRunnerArguments.realVmSshUser=vmuser \
  -Pandroid.testInstrumentationRunnerArguments.realVmSshPass=agentsterm-e2e-pass \
  -Pandroid.testInstrumentationRunnerArguments.realVmDaemonPort=7591
```
