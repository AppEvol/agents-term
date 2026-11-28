# Repository Agent Rules

## Git Commits and Attribution

- All commits must be authored and committed solely under the repository's configured git account (`git config user.name` and `git config user.email`).
- Do **not** include the names or attribution of AI agents (for example, Claude, Antigravity, Codex, Gemini, etc.) anywhere in commit metadata, commit messages, or pull request descriptions.
- Specifically:
  - Run plain `git commit` without overriding author or committer identity (`--author`, `-c user.name=...`, `-c user.email=...`, `GIT_AUTHOR_*`, or `GIT_COMMITTER_*`).
  - Do not modify `user.name` or `user.email` in git config.
  - Do not add `Co-Authored-By:` trailers, "Generated with ..." notices, or any other agent attribution lines.

## Toolchain Dependencies

- Before starting work in this repository, run `./install_deps.sh --check` from the repository root. It needs no root, changes nothing, and exits non-zero when a required toolchain (JDK, Rust with the musl targets, Gradle, Android SDK, and the base tools) is missing.
- If the check fails, stop and ask the user to run `sudo ./install_deps.sh`, then run the check again. Do not work around a missing toolchain (for example by building in Docker), and do not push changes whose tests you could not run for that reason.
- The check also reports whether the Android emulator is set up. It is optional (it needs x86_64 with KVM) and does not fail the check. Use it to run the instrumented tests or to try an app change without the user's phone; see "Running the Emulator" in `docs/development.md`.
- If the check passes but notes that `ANDROID_HOME` is not set in your shell, export it as the note says before running `gradle`.
- The app's unit tests run on Java 21 even when the default JDK is older: Gradle picks an installed JDK 21 or downloads one into `~/.gradle/jdks` on the first run. Do not switch the default JDK or set `JAVA_HOME` to work around a test failure.
- When CI starts using a different tool or version (`.github/workflows/ci.yml`), update the versions at the top of `install_deps.sh` in the same change.

## Workflow

- Before starting significant work that changes code in this repository (a new feature, a refactor, a change to the app ↔ daemon contract, or anything touching several components), brief the user on the architecture of the change — what will change, where, and the main trade-offs — and wait for their confirmation before implementing it. Small, contained fixes and documentation-only changes do not need this step.
- After the dependency check passes, check the shared hooks from any worktree. Run this at the start of each task. If either hook is missing or lacks execute permission, the command runs the installer automatically. If the installer reports a custom hook conflict, preserve the custom setup and report the conflict.

  ```sh
  hooks=$(git rev-parse --path-format=absolute --git-path hooks) &&
    { [ -x "$hooks/pre-commit" ] && [ -x "$hooks/pre-push" ] || scripts/install-git-hooks.sh; }
  ```

- Run `scripts/install-git-hooks.sh` again after changing the hook scripts. The commit hook blocks direct commits on `main`. Before a push to `origin`, the push hook tries to fast-forward local `main`. A busy, dirty, or diverged checkout skips the sync and the feature push proceeds.
- Always do your work in your own git worktree on a dedicated work branch. Multiple agents may work in this repository on the same machine at the same time, and separate worktrees keep their changes from conflicting.
- `main` is protected and requires a pull request and passing required CI checks (including for simple changes). Do not push directly to `origin/main` or merge locally into `main` and push `main`.
- Push your work branch to `origin`, open a pull request targeting `main`, and once all required CI checks pass, add it to the merge queue with `gh pr merge` (or the **Merge when ready** button). The queue reruns CI on your change combined with `main` and the pull requests ahead of it, then merges it. You do not need to rebase onto `main` unless there is a conflict. If the queue run fails, the pull request is removed from the queue: fix it and queue it again.
- After the pull request is merged into `main` and the work is finished, clean up your local worktree and delete the merged local branch (e.g., `git worktree remove <worktree-path>` and `git branch -d <branch>`) so stale worktrees do not pile up.

## Testing & CI

- Every test written in this repository must run in CI (`.github/workflows/ci.yml`) and hard-block PR merges into `main` (wire any new CI test job into the `ci-gate` / `All Required CI Checks` job).
- If a test is not valuable or reliable enough to run in CI and block PR merges, do not add or keep it in the repository.
- Avoid running the full Android instrumented E2E suite (`android-e2e`, `scripts/ci/run_android_e2e.py`, or `:app:connectedDebugAndroidTest`) locally and rely on CI instead. Multiple agents typically share the same VM space, Android emulator, ADB server, and forwarded ports (`2222`/`7591`), so `scripts/android_test_retries.py android-e2e` serializes runs VM-wide across worktrees. Reserve local instrumented runs for narrowly targeted single-test debugging when needed.
- A pull request that only changes documentation (`docs/` and top-level `*.md`, except `docs/live-debugging.md` and `docs/agents_debugging_tips.md`, which are embedded in the daemon binary) skips the Android build, unit test and emulator jobs. Keep documentation changes in their own pull request to get the fast path.

## Static Checks

- Run `scripts/static-checks.sh` before pushing; CI runs the same script and blocks the release on it. It needs a JDK on `PATH` plus `uv` or `python3 -m venv`, and downloads its pinned tools into `~/.cache/agentsterm-static-checks` on first use.
- `scripts/static-checks.sh --fix` applies the automatic fixes (formatting, import order, safe lint fixes). Fix what remains by hand.
- What runs: `ruff` (lint, import order, formatting) and `vulture` (dead code) for Python, configured in `pyproject.toml`; `ktlint` (formatting, import order, `.editorconfig`) and `detekt` (code smells, unused private code, `config/detekt/detekt.yml`) for Kotlin; `shellcheck` for shell scripts; `actionlint` for workflows. Android Lint runs separately as `gradle :app:lintDebug` (configured in `app/build.gradle.kts`).
- Fix findings instead of silencing them. Do not add entries to `config/detekt/baseline.xml` or `app/lint-baseline.xml`: they only grandfather findings that predate the checks, and should shrink as that code is cleaned up. Suppress a single finding inline (`# noqa: RULE`, `@Suppress("Rule")`, `# shellcheck disable=SCxxxx`) only with a reason in a comment.

## Markdown Rendering (`app/src/main/java/com/appevol/agentsterm/ui/markdown/`)

- Chat Markdown is parsed by commonmark-java (`MarkdownParser`) into an immutable block tree, then drawn by the composables in that package. Change syntax handling in the parser, not with string matching in the UI.
- `MarkdownGoldenTest` renders the documents in `app/src/test/resources/markdown-samples/` with `MarkdownText` alone, at several phone widths and densities, and compares the pixels with the golden images in `app/src/test/snapshots/markdown/`. It runs as part of `:app:testDebugUnitTest` (Robolectric, no emulator).
- When a rendering change is intended, record new images with `gradle :app:testDebugUnitTest --tests '*MarkdownGoldenTest*' -Pgoldens=record`, look at every changed PNG, and commit them with the change. Record on Linux, as CI does: text is drawn slightly differently on other systems.
- When the test fails, `app/build/outputs/roborazzi/` (the `golden-image-differences` artifact in CI) has a `*_compare.png` for each failure showing the golden, the new rendering and the difference.
- To cover a new Markdown feature, add it to a sample document or add a new sample to the list in `MarkdownGoldenTest`.

## Debugging the Live App

- When debugging behavior on the phone, inspect the running app with `agents_term_app_cli` (installed alongside the Rust daemon at `~/.agents-term/bin/agents_term_app_cli`, or run `agentsterm-daemon app`; see `agents_term_app_cli --help` and `docs/live-debugging.md`) before guessing: `status`, `ping`, `state`, `logs`, `logcat`, `crash`, `screenshot -o <png>`, `ui`, `dump`.
- Run `agents_term_app_cli status` first. Exit code 3 means the app is not connected or the user has not enabled **Settings > Debug sharing > Allow VM agents to inspect the app**; ask the user to do that rather than retrying.
- These commands are read-only. Screenshots need the app in the foreground and show the user a toast.
- The same CLI can drive the app to reproduce a bug or QA test a flow: `ui --brief` lists what is on screen, then `tap`, `long-press`, `type`, `scroll`, `swipe`, `key back`/`key enter`, and `wait` (see `agents_term_app_cli --help` and "Driving the app" in `docs/live-debugging.md`). These need a second switch, **Allow VM agents to control the app**; `status` reports `controlReady`, and exit code 3 with `control_disabled` means it is off, so ask the user rather than retrying. Only drive the app when the task calls for it: the user is holding that phone.

## VM Daemon (`daemon-rs/`)

- The VM daemon is a single static Rust binary in `daemon-rs/` (built for `x86_64` and `aarch64` Linux musl). From the repository root, run `cargo test --manifest-path daemon-rs/Cargo.toml` for the daemon and `python3 -m unittest discover -s daemon-rs/tests` for its POSIX installer.
- **APK ↔ daemon release sync:** Every official CI release on `main` (`v0.3.<run>-<sha>`) and branch preview pre-release (`v0.3.<run>-<sha>-preview`) pins `BuildConfig.DAEMON_RELEASE_BASE_URL` to its own GitHub Release tag so each released APK bootstraps the exact daemon binary built from the same commit. Unreleased local/PR APK builds default to `releases/latest/download` (override with `-PdaemonReleaseBaseUrl=...` or `AGENTSTERM_DAEMON_RELEASE_BASE_URL` when testing unmerged daemon changes).
- All VM daemon binaries, config, logs, debug dumps, and split per-tab state live under `$HOME/.agents-term/` (overridable via `AGENTSTERM_HOME`; see `docs/architecture.md`). Treat `$HOME/.agents-term/` as read-only from agent tabs — only `agentsterm-daemon` writes to it.
- Agent sessions started from the app are child processes of the running daemon. Never restart the daemon or `agentsterm-daemon.service`, and never run `agentsterm-daemon install` or `daemon-rs/install.sh` against your real `HOME` from inside such a session. Doing so ends your session. Test on a scratch port with a temporary `AGENTSTERM_HOME` instead (`AGENTSTERM_HOME=$(mktemp -d) cargo run --manifest-path daemon-rs/Cargo.toml -- serve --host 127.0.0.1 --port 7599`).
- Keep the REST/WebSocket contract backward compatible with released app builds.

## Agent Productivity Bugs (`#agent-productivity`)

- While executing tasks, if you hit surprises, friction, or gotchas that trip up or slow down execution, file a bug in this repository with the `agent-productivity` label (e.g., `gh issue create --label bug --label agent-productivity ...`) and note the `#agent-productivity` category.
- Check existing issues first (`gh issue list --label agent-productivity` or `gh issue list --search "..."`) to avoid filing duplicates.
- If a matching issue already exists, do not open a new one—react to it instead to signal-boost it (e.g., `gh api repos/AppEvol/agents-term/issues/<number>/reactions -f content=+1`).

## Feature Change Logs (`docs/features_logs/`)

- Record notable feature changes (added, modified, removed) in `docs/features_logs/` so future retrospectives can easily track how the product evolved.
- Put each feature change in its own new Markdown file under the week's directory, named with the Monday of that week (`YYYY-MM-DD`) and a dated kebab-case slug: `docs/features_logs/<monday-YYYY-MM-DD>/<YYYY-MM-DD>-<short-slug>.md` (e.g., `docs/features_logs/2026-11-23/2026-11-26-copyable-tab-id.md`).
- Do **not** append to a shared weekly `.md` file (such as legacy `docs/features_logs/2026-11-23.md`). Separate per-PR fragment files prevent parallel pull requests in the same week from colliding in the merge queue.
- Keep each fragment brief and organized as concise bullet points so entries stay scannable (see `docs/features_logs/README.md` for the template).
- Include a link to the pull request or issue (e.g., `[PR #123](https://github.com/AppEvol/agents-term/pull/123)` or `[#97](https://github.com/AppEvol/agents-term/issues/97)`).

