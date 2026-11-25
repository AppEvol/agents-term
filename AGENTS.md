# Repository Agent Rules

## Git Commits and Attribution

- All commits must be authored and committed solely under the repository's configured git account (`git config user.name` and `git config user.email`).
- Do **not** include the names or attribution of AI agents (for example, Claude, Antigravity, Codex, Gemini, etc.) anywhere in commit metadata, commit messages, or pull request descriptions.
- Specifically:
  - Run plain `git commit` without overriding author or committer identity (`--author`, `-c user.name=...`, `-c user.email=...`, `GIT_AUTHOR_*`, or `GIT_COMMITTER_*`).
  - Do not modify `user.name` or `user.email` in git config.
  - Do not add `Co-Authored-By:` trailers, "Generated with ..." notices, or any other agent attribution lines.

## Workflow

- Always do your work in your own git worktree. Multiple agents may work in this repository on the same machine at the same time, and separate worktrees keep their changes from conflicting.
- For simple changes, commit in your worktree and push directly to `origin/main` when done.
- For non-trivial work, push your work branch to the remote and keep working on it until the work is done, then merge the work branch into `main` and push `main`.
- After the remote branch is merged into `main` (or pushed directly to `origin/main`) and the work is finished, clean up your local worktree and delete the merged local branch (e.g., `git worktree remove <worktree-path>` and `git branch -d <branch>`) so stale worktrees do not pile up.

## Static Checks

- Run `scripts/static-checks.sh` before pushing; CI runs the same script and blocks the release on it. It needs a JDK on `PATH` plus `uv` or `python3 -m venv`, and downloads its pinned tools into `~/.cache/agentsterm-static-checks` on first use.
- `scripts/static-checks.sh --fix` applies the automatic fixes (formatting, import order, safe lint fixes). Fix what remains by hand.
- What runs: `ruff` (lint, import order, formatting) and `vulture` (dead code) for Python, configured in `pyproject.toml`; `ktlint` (formatting, import order, `.editorconfig`) and `detekt` (code smells, unused private code, `config/detekt/detekt.yml`) for Kotlin; `shellcheck` for shell scripts; `actionlint` for workflows. Android Lint runs separately as `gradle :app:lintDebug` (configured in `app/build.gradle.kts`).
- Fix findings instead of silencing them. Do not add entries to `config/detekt/baseline.xml` or `app/lint-baseline.xml`: they only grandfather findings that predate the checks, and should shrink as that code is cleaned up. Suppress a single finding inline (`# noqa: RULE`, `@Suppress("Rule")`, `# shellcheck disable=SCxxxx`) only with a reason in a comment.

## Markdown Rendering (`app/src/main/java/com/example/ui/markdown/`)

- Chat Markdown is parsed by commonmark-java (`MarkdownParser`) into an immutable block tree, then drawn by the composables in that package. Change syntax handling in the parser, not with string matching in the UI.
- `MarkdownGoldenTest` renders the documents in `app/src/test/resources/markdown-samples/` with `MarkdownText` alone, at several phone widths and densities, and compares the pixels with the golden images in `app/src/test/snapshots/markdown/`. It runs as part of `:app:testDebugUnitTest` (Robolectric, no emulator).
- When a rendering change is intended, record new images with `gradle :app:testDebugUnitTest --tests '*MarkdownGoldenTest*' -Pgoldens=record`, look at every changed PNG, and commit them with the change. Record on Linux, as CI does: text is drawn slightly differently on other systems.
- When the test fails, `app/build/outputs/roborazzi/` (the `golden-image-differences` artifact in CI) has a `*_compare.png` for each failure showing the golden, the new rendering and the difference.
- To cover a new Markdown feature, add it to a sample document or add a new sample to the list in `MarkdownGoldenTest`.

## Debugging the Live App

- When debugging behavior on the phone, inspect the running app with `app_state_cli/app_state_cli.py` (see `app_state_cli/README.md`) before guessing: `status`, `state`, `logs`, `logcat`, `crash`, `screenshot -o <png>`, `ui`, `dump`.
- Run `status` first. Exit code 3 means the app is not connected or the user has not enabled **Settings > Debug sharing > Allow VM agents to inspect the app**; ask the user to do that rather than retrying.
- These commands are read-only. Screenshots need the app in the foreground and show the user a toast.
- The same CLI can drive the app to reproduce a bug or QA test a flow: `ui --brief` lists what is on screen, then `tap`, `long-press`, `type`, `scroll`, `swipe`, `key back` and `wait` (see "Driving the app" in `app_state_cli/README.md`). These need a second switch, **Allow VM agents to control the app**; `status` reports `controlReady`, and exit code 3 with `control_disabled` means it is off, so ask the user rather than retrying. Only drive the app when the task calls for it: the user is holding that phone.

## VM Daemon (`daemon-rs/` & `daemon/`)

- The production VM daemon is a single static Rust binary in `daemon-rs/` (built for `x86_64` and `aarch64` Linux musl). Run its tests with `cargo test` inside `daemon-rs/`. The legacy Python daemon tests and POSIX `daemon/install.sh` tests run with `python3 -m unittest discover -s daemon/tests`.
- **APK ↔ daemon release sync:** Every official CI release on `main` (`v0.3.<run>-<sha>`) and branch preview pre-release (`v0.3.<run>-<sha>-preview`) pins `BuildConfig.DAEMON_RELEASE_BASE_URL` to its own GitHub Release tag so each released APK bootstraps the exact daemon binary built from the same commit. Unreleased local/PR APK builds default to `releases/latest/download` (override with `-PdaemonReleaseBaseUrl=...` or `AGENTSTERM_DAEMON_RELEASE_BASE_URL` when testing unmerged daemon changes).
- Agent sessions started from the app are child processes of the running daemon. Never restart the daemon or `herdr-mobile.service`, and never run `agentsterm-daemon install` or `daemon/install.sh` against your real `HOME`, from inside such a session: it ends your own session. Test on a scratch port with a temporary `HOME` instead (`HOME=$(mktemp -d) cargo run --manifest-path daemon-rs/Cargo.toml -- serve --host 127.0.0.1 --port 7599`).
- Keep the REST/WebSocket contract backward compatible with released app builds.

## Agent Productivity Bugs (`#agent-productivity`)

- While executing tasks, if you hit surprises, friction, or gotchas that trip up or slow down execution, file a bug in this repository with the `agent-productivity` label (e.g., `gh issue create --label bug --label agent-productivity ...`) and note the `#agent-productivity` category.
- Check existing issues first (`gh issue list --label agent-productivity` or `gh issue list --search "..."`) to avoid filing duplicates.
- If a matching issue already exists, do not open a new one—react to it instead to signal-boost it (e.g., `gh api repos/nedn/agents-term/issues/<number>/reactions -f content=+1`).

## Feature Change Logs (`docs/features_logs/`)

- Record notable feature changes (added, modified, removed) in `docs/features_logs/` so future retrospectives can easily track how the product evolved.
- Start a new Markdown file each week, named with the first date of the week (Monday, `YYYY-MM-DD.md`, e.g., `docs/features_logs/2026-11-23.md`).
- Organize each doc with concise bullet points so entries stay brief and scannable.
- When a feature change is implemented via a branch merge, include a link to the merge commit (e.g., `[<short-sha>](https://github.com/nedn/agents-term/commit/<sha>)`).

