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
