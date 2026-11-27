# Feature Change Logs

Weekly logs of features added, modified, or removed for retrospective review.

## Adding an Entry

- Create a new Markdown fragment file inside the directory for the week (named with the Monday of that week, `YYYY-MM-DD`):
  ```text
  docs/features_logs/<monday-YYYY-MM-DD>/<YYYY-MM-DD>-<short-slug>.md
  ```
  For example: `docs/features_logs/2026-11-23/2026-11-26-copyable-tab-id.md`.
- Do **not** edit a shared weekly `.md` file. Giving each pull request its own fragment file prevents merge conflicts when multiple pull requests land in the same week (GitHub's server-side merge and merge queue do not honor `.gitattributes` `merge=union`).
- Keep entries brief and organized as bullet points, linking to the pull request or issue:
  ```markdown
  - **Short feature title** ([PR #123](https://github.com/AppEvol/agents-term/pull/123)):
    - Concise summary of what was added, modified, or removed.
  ```

## Reading a Week's Log

- Browse `docs/features_logs/<monday-YYYY-MM-DD>/` (files sort chronologically by date prefix), or concatenate them with `cat docs/features_logs/<monday-YYYY-MM-DD>/*.md`.
- Older single-file weekly logs (such as `2026-11-23.md`) are kept as historical archives and should not be appended to by new pull requests.
