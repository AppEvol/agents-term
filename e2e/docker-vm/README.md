# Docker VM End-to-End Test Harness

Provides a multi-distro matrix of realistic Linux Docker VM targets (`e2e/docker-vm/Dockerfile`) for testing the static Rust VM daemon end-to-end without involving the Android app:

| Target | Base Image | Highlights |
| --- | --- | --- |
| `vm-ubuntu-2404` (`vm-minimal`) | `ubuntu:24.04` | Zero `git`/`python3`, `dash` `/bin/sh`, `curl`, Vixie `cron` |
| `vm-ubuntu-2204` | `ubuntu:22.04` | Zero `git`/`python3`, `dash` `/bin/sh`, `curl`, Vixie `cron` |
| `vm-debian-12` | `debian:12-slim` | Zero `git`/`python3`, **`wget`-only (no `curl`)**, `dash`, `cron` |
| `vm-almalinux-9` | `almalinux:9` | RHEL 9 compatible, `bash`, `curl`, `cronie`, zero `git` |
| `vm-fedora-41` | `fedora:41` | Modern Fedora, `bash`, `curl`, **no `cron`** (detached-only fallback) |
| `vm-amazonlinux-2023` | `amazonlinux:2023` | AWS EC2 default Linux, `bash`, `curl`, **no `cron`** |
| `vm-alpine-321` | `alpine:3.21` | Pure **musl libc**, BusyBox `ash`, **`wget`-only (no `curl`)**, zero `git`/`python3` |

## Run Headless Multi-Distro Rust Daemon E2E Locally

Run the default target (`vm-ubuntu-2404`):

```bash
python3 -W error::ResourceWarning -m unittest discover -s e2e/docker-vm -v
```

Run a specific Linux VM environment:

```bash
E2E_VM_TARGET=vm-alpine-321 python3 -W error::ResourceWarning -m unittest discover -s e2e/docker-vm -v
E2E_VM_TARGET=vm-debian-12 python3 -W error::ResourceWarning -m unittest discover -s e2e/docker-vm -v
E2E_VM_TARGET=vm-almalinux-9 python3 -W error::ResourceWarning -m unittest discover -s e2e/docker-vm -v
E2E_VM_TARGET=vm-fedora-41 python3 -W error::ResourceWarning -m unittest discover -s e2e/docker-vm -v
E2E_VM_TARGET=vm-amazonlinux-2023 python3 -W error::ResourceWarning -m unittest discover -s e2e/docker-vm -v
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
