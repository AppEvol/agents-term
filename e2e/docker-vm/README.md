# Docker VM End-to-End Test Harness

The `vm-minimal` container runs the Rust daemon on a Linux VM without Git or Python. OpenSSH and a local HTTP release mirror exercise the app's bootstrap flow. The VM provides deterministic stand-ins for Tailscale and the agent CLIs.

## Run Headless Docker VM E2E Locally

```bash
python3 -W error::ResourceWarning -m unittest discover -s e2e/docker-vm -v
```

## Run Android Emulator ↔ Docker VM E2E

```bash
eval "$(bash e2e/docker-vm/start_vm.sh --name agentsterm-ci-vm --ssh-port 2222 --daemon-port 7591 --target vm-minimal)"
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
