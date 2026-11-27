# AgentsTerm Architecture

This document records the core architectural principles and target filesystem layout for the AgentsTerm VM environment.

> **Guiding Principle:** Specific filenames, subfolder layouts, and serialization formats below may evolve over time. What stays constant is the core goal: **consolidate all AgentsTerm-related files and data on the VM under a single folder (`$HOME/.agents-term/`), with a clear layout convention and an explicit protocol for reading, writing, and modifying them.**

## High-Level Topology

```text
Android App ──HTTP/WS (Tailscale)──▶ agentsterm-daemon (Rust) ──spawns──▶ Coding Agents / Shell
                                              ▲
                                              │ localhost HTTP
                                     agents_term_app_cli (read/control bridge)
```

- **Android client (`app/`):** Connects over Tailscale to the VM daemon via REST + WebSocket using bearer token authentication.
- **VM daemon (`daemon-rs/`):** Single static Rust binary (`agentsterm-daemon`) that owns tab lifecycle, message persistence, agent process execution, uploaded debug dumps, and the app-inspection/control bridge.
- **VM CLI (`agents_term_app_cli`):** Symlink to `agentsterm-daemon` (dispatched via `argv[0]`) so agents running on the VM can inspect or drive the connected Android app through the daemon without duplicating binaries.

---

## Unified VM Directory (`$HOME/.agents-term/`)

> **Status:** Implemented in [#94](https://github.com/AppEvol/agents-term/issues/94), replacing the legacy split across `~/.config/herdr-mobile/`, `~/.local/bin/`, `/tmp/`, and monolithic `state.json`.

All binaries, configuration, persistent state, logs, debug dumps, and temporary files owned by AgentsTerm on the VM live under a single root directory:

- **Default root:** `$HOME/.agents-term/` (mode `0700`)
- **Override:** `AGENTSTERM_HOME` environment variable (defaults to `$HOME/.agents-term`). Using a dedicated override allows tests and scratch daemon instances to run in isolation (`AGENTSTERM_HOME=$(mktemp -d)`) without overriding `HOME` and breaking discovery of user-owned CLI configs and credentials.

### Target Layout

```text
$HOME/.agents-term/                # mode 0700 (owner-only)
├── bin/
│   ├── agentsterm-daemon          # static Rust binary (0755)
│   └── agents_term_app_cli        # symlink -> agentsterm-daemon
├── config.json                    # mode 0600 — bearer token, bind host, port
├── daemon.lock                    # exclusive flock held by the running daemon
├── daemon.pid                     # PID file (detached mode)
├── daemon.log                     # daemon stdout/stderr (detached mode, size-capped/rotated)
├── live-debugging.md              # mode 0644 — embedded agent guide kept in sync by daemon
├── debug-dumps/                   # mode 0700 — uploaded Android debug bundles
│   ├── <timestamp>_<dumpId>/
│   └── latest -> <timestamp>_<dumpId>
├── tmp/                           # ephemeral CLI outputs (e.g. default screenshots) & staging
└── agents_tabs/                   # mode 0700 — tab index & per-tab state
    ├── workspace.json             # schemaVersion, activeTabId
    ├── tabs.jsonl                 # current snapshot: 1 compact JSON object per open/closed tab
    └── <tab_id>/                  # per-tab folder; removed when closed tab expires
        ├── messages.json          # normalized AgentsTerm chat history for this tab
        ├── attachments/           # mode 0700 — images sent from the app (att_<id>.<ext>, 0600)
        └── raw.jsonl              # optional raw CLI stdout/stderr stream & trajectory metadata
```

### Why This Structure

1. **No VM disk litter:** Everything owned by AgentsTerm is contained in one hidden directory (which the app's folder picker already hides because it skips dotfiles).
2. **Clean `PATH` separation:** Keeping executables in `$HOME/.agents-term/bin/` lets the daemon prepend that directory to `PATH` for spawned agents without exposing config or state files on `PATH`.
3. **Single binary footprint:** `agents_term_app_cli` stays a symlink to `agentsterm-daemon` rather than a second copy of the binary.
4. **Fast inspection and debugging:** Agents and humans can inspect workspace state, active/archived tabs, logs, and phone debug dumps from one predictable location.
5. **Single source of truth for paths:** Daemon code, default system prompts, `docs/live-debugging.md`, and Android bootstrap/UI strings all reference `$HOME/.agents-term/` directly.

### Intentional External Exceptions

Two categories stay outside `$HOME/.agents-term/`:

- **Systemd user unit (`~/.config/systemd/user/`):** `systemd --user` requires unit files in its standard XDG directory; the unit's `ExecStart` points to `$HOME/.agents-term/bin/agentsterm-daemon`.
- **External agent CLI native directories (`~/.gemini/`, `~/.claude/`, `~/.codex/`):** AgentsTerm must **not** relocate or override the underlying agent CLIs' home/config directories, as doing so breaks user authentication, global rules, skills, MCP server configs, and model caches.

---

## Single-Writer Ownership & Access Model

### Core Invariant

**Only `agentsterm-daemon` writes to `$HOME/.agents-term/`.**

Agents running inside tabs—and CLI tools they invoke—treat `$HOME/.agents-term/` as **read/execute-only**. Any state change (updating tab metadata, uploading a debug dump, etc.) goes through the daemon's HTTP API so concurrent agents never race on files or corrupt state.

### Why Architectural Enforcement (Not OS Sandboxing)

`agentsterm-daemon` and the coding agents it spawns run under the **same Linux user (UID)** so that:

- Zero-root SSH bootstrap and `systemd --user` work without `sudo`.
- Agents retain normal access to user repositories, SSH keys, git config, and CLI credentials.

Under a single UID, POSIX mode bits (such as `chmod 0500`) cannot enforce a hard boundary against same-UID child processes (and would block the daemon itself), while user/mount-namespace read-only bind mounts break setuid tools like `sudo` inside agent tabs and fail on restricted kernels.

Instead, single-writer integrity is enforced through concrete engineering invariants:

1. **Daemon-only writes in code:** No CLI subcommand or helper may write to `$HOME/.agents-term/`. In particular, `agents_term_app_cli` must use read-only config loading and never create or rewrite `config.json`.
2. **Exclusive daemon lock (`daemon.lock`):** The daemon acquires an exclusive `flock` on `$HOME/.agents-term/daemon.lock` on startup so two daemon processes cannot write concurrently to the same state directory.
3. **Collision-free atomic writes:** All file updates write to a uniquely named temporary file in the same directory (including a per-write counter/nonce, not just PID), `fsync`, and atomically `rename` into place.
4. **Owner-only permissions:** `$HOME/.agents-term/` and sensitive subdirectories use `0700`, and `config.json` / state files use `0600`, isolating data from other OS users on the VM.
5. **Explicit agent instructions:** Default agent guidance instructs agents never to modify files under `$HOME/.agents-term/` directly.

### Security Scope Note

Because `agents_term_app_cli` reads the bearer token from `config.json`, agents with read access to `$HOME/.agents-term/` can authenticate to the daemon API. Single-writer filesystem ownership is designed to prevent concurrent file corruption and state races—not to act as an API privilege boundary. If finer-grained agent authorization is needed later, it should be introduced via scoped daemon tokens rather than filesystem permissions.

---

## Tab State & Per-Tab Storage (`agents_tabs/`)

### Motivation

Previously, all open tabs, recently closed tabs (up to 50 retained for 24 hours), and every tab's full message history lived in one monolithic `state.json` that was serialized and rewritten atomically on every debounced 250 ms save. Splitting tab metadata from per-tab histories eliminates full-workspace rewrite amplification during streaming and makes individual tabs trivial to inspect or prune.

### File Responsibilities

- **`agents_tabs/workspace.json`:** Minimal workspace-level metadata (`schemaVersion`, `activeTabId`).
- **`agents_tabs/tabs.jsonl`:** Current-state snapshot containing one compact JSON object per active or recently archived tab:
  - Always written atomically as a complete snapshot (not an unbounded append-only event log), so tools like `grep` and `jq` see exact current state without replay or compaction.
  - Distinguishes active and archived tabs explicitly (`state: "open" | "closed"`, `closedAt`).
  - Carries compact, high-value metadata (`id`, `title`, `agent`, `cwd`, `status`, `statusDetail`, `taskSummary`, `sessionRef`, timestamps) and stays extensible for structured task/lifecycle and cross-tab awareness fields ([#30](https://github.com/AppEvol/agents-term/issues/30)).
- **`agents_tabs/<tab_id>/`:** Per-tab directory holding heavy data isolated from the index:
  - `messages.json` — Normalized AgentsTerm chat history for that tab; streaming updates rewrite only this tab's file.
  - Optional diagnostic/trajectory artifacts (e.g., raw CLI stdout/stderr stream logs and references to the underlying CLI's native session/trajectory via `sessionRef`).
  - **Lifecycle pruning:** When a closed tab ages past retention (24 hours or >50 archived tabs), deleting `agents_tabs/<tab_id>/` reclaims all of its storage in one operation.

---

## CI

GitHub Actions orchestration lives in [`.github/workflows/ci.yml`](https://github.com/AppEvol/agents-term/blob/main/.github/workflows/ci.yml) and stays intentionally lean: YAML defines job wiring, caches, artifact handoffs, permissions, and environment variables, while non-trivial shell logic lives in standalone executable scripts (`chmod +x`, `#!/usr/bin/env bash`, `set -euo pipefail`).

### Script Layout Convention (`scripts/` vs `scripts/ci/`)

- **`scripts/` — Shared & local-development scripts (kebab-case):** Reusable helpers that developers and CI both invoke directly from the repository root:
  - `scripts/static-checks.sh` — Runs `ruff`, `vulture`, `ktlint`, `detekt`, `shellcheck`, and `actionlint` using pinned cached binaries.
  - `scripts/daemon-build-hash.py` — Computes the 40-character `AGENTSTERM_BUILD_HASH` matching `daemon-rs/build.rs`.
  - `scripts/ensure-debug-keystore.sh` — Ensures `debug.keystore` exists (from `DEBUG_KEYSTORE_BASE64`, `debug.keystore.base64`, or `keytool`).
  - `scripts/install-android-sdk.sh` — Accepts Android SDK licenses and installs the required platform and build-tools via `sdkmanager`.
  - `scripts/allure_results.py` — Stdlib-only CLI that emits Allure 3 JSON test results (`<uuid>-result.json` + attachments) for Python `unittest`, Gradle JUnit XML (including Roborazzi golden diff PNGs), and Rust `cargo test -- --show-output`, plus merges shards and renders Markdown summaries.
  - `scripts/allure-report.sh` — Installs pinned `allure@3.33.0` into `~/.cache/agentsterm-allure` (Node.js >= 22.18) and builds (or opens with `--open`) the unified Allure 3 Awesome HTML report using `allurerc.json`.
- **`scripts/ci/` — CI-only workflow scripts (snake_case):** Scripts tied to GitHub Actions runners, `GITHUB_OUTPUT` / `GITHUB_ENV` / `GITHUB_STEP_SUMMARY`, or GitHub Release publishing:
  - `scripts/ci/daemon_change_check.sh` — Verifies whether prebuilt `latest` daemon binaries match the current source hash and classifies changed files (daemon tests, installer tests, Linux VM E2E harness, docs-only PRs).
  - `scripts/ci/setup_rust_toolchain.sh` & `scripts/ci/build_rust_daemon.sh` — Installs Rust musl targets, cross-compiles static `x86_64`/`aarch64` daemon binaries, writes `.sha256` checksums, and verifies the embedded build hash.
  - `scripts/ci/build_and_unit_test.sh` — Computes APK version metadata and pinned `AGENTSTERM_DAEMON_RELEASE_BASE_URL` for release/preview builds, then runs Android Lint, unit tests, and debug/test APK assembly.
  - `scripts/ci/stage_daemon_binary.sh` — Stages the downloaded `x86_64` daemon binary for the multi-distro Linux VM E2E matrix.
  - `scripts/ci/enable_kvm.sh`, `scripts/ci/set_build_version_env.sh`, `scripts/ci/start_docker_vm.sh`, `scripts/ci/run_android_e2e.sh`, `scripts/ci/stop_docker_vm.sh` — Manages runner KVM permissions, Docker E2E VM lifecycle, and Android emulator instrumented tests.
  - `scripts/ci/generate_allure_report.sh` — Merges downloaded `allure-results-*` artifacts with GitHub Actions `environment.properties` and `executor.json`, appends a per-suite Markdown summary table to `$GITHUB_STEP_SUMMARY`, and builds the single-file Allure 3 HTML report.
  - `scripts/ci/ci_gate.sh` — Evaluates all required and conditionally-skipped job outcomes for the `All Required CI Checks` branch-protection gate.
  - `scripts/ci/publish_branch_prerelease.sh` & `scripts/ci/publish_versioned_releases.sh` — Creates branch preview pre-releases (`v<version>-preview`) or versioned `main` releases (`v<version>`) and updates the rolling `latest` tag/release alias.
- **`scripts/tests/` — Script unit tests:** Fast `unittest` coverage for the extracted scripts and Allure 3 result helpers, executed unconditionally in CI via the `script-tests` job and hard-required by `ci-gate`.

### Job Topology & Gating

1. **Always-run checks:** `static-checks`, `script-tests`, `daemon-change-check`, and `allure-report` run on every pull request, merge queue group (`merge_group`), push to `main`, and manual dispatch.
2. **Change-aware daemon checks:** When `daemon-change-check` verifies that the `latest` GitHub Release binaries already match `scripts/daemon-build-hash.py`, it reuses them as the `daemon-binaries` artifact and skips `rust-daemon-build-and-test`, `installer-tests`, and `docker-vm-e2e` unless their respective test paths changed or `force_daemon_tests` is enabled.
3. **Documentation-only fast path:** Pull requests (and their merge queue runs) that modify only `docs/` or top-level Markdown files (excluding the daemon-embedded docs `docs/live-debugging.md` and `docs/agents_debugging_tips.md`) skip `build-and-unit-test` and `e2e-tests`; pushes to `main` and manual runs always build so releases stay verifiable.
4. **Branch-protection gate & publishing:** `ci-gate` ("All Required CI Checks") checks out the repository and runs `scripts/ci/ci_gate.sh` to enforce that all required jobs (including `allure-report`) succeeded and optional jobs were only skipped when permitted. On green `main` pushes/dispatches, `publish-apk` runs `scripts/ci/publish_versioned_releases.sh`; on manual non-`main` dispatches, `publish-branch-prerelease` runs `scripts/ci/publish_branch_prerelease.sh`.

### Test Reporting (Allure 3)

Allure 3 (`allure@3.33.0`, configured via [`allurerc.json`](https://github.com/AppEvol/agents-term/blob/main/allurerc.json)) produces a unified interactive test report across every test suite in CI and locally:

- **Per-suite result emission (zero extra test dependencies):**
  - **Android Unit Tests** (`build-and-unit-test`) & **Android Instrumented E2E** (`e2e-tests`): `scripts/allure_results.py junit` converts Gradle JUnit XML reports and automatically attaches matching Roborazzi `*_compare.png` / `*_actual.png` diffs on `MarkdownGoldenTest` failures.
  - **Rust VM Daemon** (`rust-daemon-build-and-test`): `scripts/allure_results.py cargo` wraps `cargo test -- --show-output`, preserving exact exit codes while recording every unit/integration test, panic trace, and test stdout attachment.
  - **Daemon Installer Tests** (`installer-tests`, Python 3.11 & 3.13), **Linux VM E2E Matrix** (`docker-vm-e2e`, 7 Linux distros), and **CI & Utility Script Tests** (`script-tests`): `scripts/allure_results.py unittest` wraps `unittest` discovery with `-W error::ResourceWarning`. Matrix shards use unique artifact names (`allure-results-installer-py-<ver>`, `allure-results-docker-vm-<target>`), distinct `suite`/`parameters`, and per-shard `historyId`/`testCaseId` so Allure 3 displays every shard separately instead of collapsing them as retries.
- **CI report aggregation (`allure-report` job):** Runs with `if: always() && !cancelled()` so failing test runs still produce a full diagnostic report. It downloads all `allure-results-*` artifacts, runs `scripts/ci/generate_allure_report.sh` to write a per-suite Markdown summary table to `$GITHUB_STEP_SUMMARY`, builds a self-contained single-file Allure 3 Awesome report (`allure-report/index.html`, using Node.js 22 and cached `~/.cache/agentsterm-allure`), and uploads the `allure-report` artifact (30-day retention).
- **Local usage:** After running local tests (for example `gradle :app:testDebugUnitTest` or emitting results into `allure-results/`), generate and open the report with Node.js >= 22.18:

```sh
scripts/allure-report.sh --open
```

### Verification

Run locally before pushing CI or script changes:

```sh
python3 -W error::ResourceWarning -m unittest discover -s scripts/tests -v
scripts/static-checks.sh
```

Note that `scripts/static-checks.sh` runs `shellcheck` over `git ls-files '*.sh'` (so stage new shell scripts with `git add` before running it) and `actionlint` over `.github/workflows/*.yml`.
