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
