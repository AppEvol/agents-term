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

## Debugging the Live App

- When debugging behavior on the phone, inspect the running app with `app_state_cli/app_state_cli.py` (see `app_state_cli/README.md`) before guessing: `status`, `state`, `logs`, `logcat`, `crash`, `screenshot -o <png>`, `dump`.
- Run `status` first. Exit code 3 means the app is not connected or the user has not enabled **Settings > Debug sharing > Allow VM agents to inspect the app**; ask the user to do that rather than retrying.
- The CLI is read-only. Screenshots need the app in the foreground and show the user a toast.

