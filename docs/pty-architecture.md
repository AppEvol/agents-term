# Interactive PTY Architecture

This document describes the end-to-end architecture of AgentsTerm's opt-in interactive PTY terminal (`shellMode: "pty"`), spanning the Android Jetpack Compose UI, `libghostty-vt` native terminal emulation, the WebSocket wire protocol, and the Rust VM daemon's Linux PTY subsystem.

---

## Overview & Design Goals

AgentsTerm offers two execution modes for `shell` tabs:

| `shellMode` | Default? | Execution Model | UI |
| :--- | :--- | :--- | :--- |
| `"command"` | Yes | Turn-based persistent `bash` over pipes (`POST /api/tabs/:id/messages`, `POST /api/tabs/:id/stdin`) | Chat transcript view |
| `"pty"` | Opt-in | Full interactive Linux pseudoterminal (`WS /api/tabs/:id/pty`) with `libghostty-vt` emulation | Interactive terminal canvas + mobile extra-keys bar |

### Backward Compatibility

- **Capability advertisement:** `GET /api/info` (and the initial `snapshot.server` frame on `WS /ws`) includes a `pty` capability block (`version`, `maxCols`, `maxRows`, `bufferBytes`). Older daemons omit `pty`.
- **Client fallback:** When `ServerInfo.pty` is absent (`supportsPtyShell == false`), `NewTabSheet` hides the shell mode selector and omits `shellMode` from `POST /api/tabs`, keeping `"command"` mode intact.
- **Server defaults & validation:** On PTY-capable daemons, `POST /api/tabs` with `agent: "shell"` defaults to `shellMode: "command"` when `shellMode` is omitted, rejects unsupported `shellMode` values with HTTP `400`, and names default PTY tabs `Terminal · <folder>` (versus `Shell · <folder>` for command mode).
- **Endpoint isolation:** PTY tabs reject command-mode endpoints (`POST /api/tabs/:id/messages`, `POST /api/tabs/:id/stdin`, `POST /api/tabs/:id/interrupt`) with HTTP `400`, and image attachments (`POST /api/tabs/:id/attachments`) remain disabled for all shell tabs. Command-mode tabs similarly reject `WS /api/tabs/:id/pty` with HTTP `400`.

### Privacy Boundary

Interactive shell sessions routinely handle passwords (`sudo`, `ssh`), tokens, and sensitive terminal output. Raw PTY traffic is strictly isolated from AgentsTerm's persistence and diagnostics pipelines:

- **No chat or disk persistence:** Raw PTY input and output bytes are never written to `agents_tabs/<tab_id>/messages.json`, `raw.jsonl`, or daemon logs (`daemon.log`). The daemon keeps output strictly in an in-memory 2 MiB ring buffer per live session.
- **No telemetry in debug dumps:** On Android, `PtyTabSession` never logs raw input or output bytes to `DebugRecorder` (`events.ndjson`), `logcat`, or uploaded debug dumps (`debug-dumps/`). Only structural lifecycle metadata is recorded (`pty_connecting`, `pty_ready`, `pty_exit`, `pty_closed`, `pty_error` with tab ID, dimensions, sequence offsets, exit code/signal, or HTTP status).
- **Keyboard privacy:** `TerminalImeInputView` configures `EditorInfo` with `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | TYPE_TEXT_FLAG_NO_SUGGESTIONS` and `IME_FLAG_NO_PERSONALIZED_LEARNING` (API 26+) so Android soft keyboards do not learn or cache typed commands or secrets.
- **Password prompt indicator:** When `libghostty-vt` detects no-echo password entry (`cursor.password_input`), the terminal canvas displays a subtle lock badge (`pty_password_indicator`).

---

## End-to-End Architecture

```text
┌───────────────────────────────────────────────────────────────────────────┐
│ Android App (app/)                                                        │
│                                                                           │
│  PtyTerminalWorkspace (PtyTerminalScreen.kt)                              │
│   ├── TerminalGridCanvas (Compose nativeCanvas: batched ASCII + wide)     │
│   ├── TerminalImeInputView (raw InputConnection, codepoint-safe IME)      │
│   └── PtyControlToolbar (Tab, Ctrl/Alt latch, ^C, Esc, arrows, paste...)  │
│                       │                                                   │
│                       ▼                                                   │
│  PtySessionManager / PtyTabSession (PtySessionManager.kt)                 │
│   ├── Lifecycle, server-switch isolation, reconnect backoff, 16ms flush   │
│   └── GhosttyTerminalEngine (GhosttyVtNative.kt / TerminalFrame.kt)       │
│                       │ JNI (compact binary dirty-row frames)             │
│                       ▼                                                   │
│  ghostty_vt_jni.c ──▶ static libghostty-vt 1.0.2 (arm64/armv7/x86_64)     │
└───────────────────────┬───────────────────────────────────────────────────┘
                        │
                        │ WS /api/tabs/:id/pty (Tailscale, Bearer auth)
                        │ Binary: [u64 BE start_seq][raw bytes] / raw input
                        │ Text:   JSON control (ready, replay_end, resize...)
                        ▼
┌───────────────────────────────────────────────────────────────────────────┐
│ VM Daemon (daemon-rs/)                                                    │
│                                                                           │
│  server.rs (pty_ws_handler / handle_pty_ws)                               │
│   ├── Bearer / ?token auth, tab & shellMode validation                    │
│   ├── 15s WS write timeout, 25s ping keepalive, ws_shutdown select        │
│   └── PtyManager / PtySession (pty.rs)                                    │
│        ├── Non-blocking AsyncFd<OwnedFd> master reader & writer tasks     │
│        ├── 2 MiB sequenced VecDeque ring buffer + lock-atomic attach      │
│        ├── Bounded subscriber queues (256 slots) with lagging eviction    │
│        └── pidfd waiter + session/pgrp SIGHUP/SIGCONT -> SIGKILL cleanup  │
└───────────────────────┬───────────────────────────────────────────────────┘
                        │ Linux PTY (/dev/pts/<N>, setsid + TIOCSCTTY)
                        ▼
               Interactive Shell (bash -i / sh -i)
```

---

## Rust Daemon PTY Subsystem

Implementation lives in [`daemon-rs/src/pty.rs`](../daemon-rs/src/pty.rs), wired into HTTP/WS routes in [`daemon-rs/src/server.rs`](../daemon-rs/src/server.rs), tab persistence in [`daemon-rs/src/store.rs`](../daemon-rs/src/store.rs), and capability reporting in [`daemon-rs/src/config.rs`](../daemon-rs/src/config.rs).

### Key Constants

| Constant | Value | Purpose |
| :--- | :--- | :--- |
| `PTY_PROTOCOL_VERSION` | `1` | Wire protocol version advertised in `GET /api/info` |
| `PTY_DEFAULT_COLS` / `ROWS` | `80` / `24` | Fallback initial dimensions |
| `PTY_MIN_COLS`..`PTY_MAX_COLS` | `10`..`512` | Clamped column bounds |
| `PTY_MIN_ROWS`..`PTY_MAX_ROWS` | `4`..`256` | Clamped row bounds |
| `PTY_BUFFER_BYTES` | `2 MiB` (`2 * 1024 * 1024`) | Per-session in-memory output ring buffer |
| `PTY_REPLAY_CHUNK_BYTES` | `64 KiB` | Max payload size per binary replay frame |
| `PTY_READ_CHUNK_BYTES` | `16 KiB` | Per-`read(2)` buffer size on master fd |
| `PTY_SUBSCRIBER_QUEUE_CAPACITY` | `256` | Per-WS bounded event channel (<= 4 MiB worst-case) |
| `PTY_INPUT_QUEUE_CAPACITY` | `256` | Bounded client-to-PTY write queue |
| `PTY_EXIT_DRAIN_TIMEOUT` | `200 ms` | Max wait for trailing output before emitting `Exit` |
| `PTY_TERMINATE_GRACE` / `POLL` | `100 ms` / `5 ms` | Grace window between `SIGHUP`+`SIGCONT` and `SIGKILL` |
| `PTY_WS_WRITE_TIMEOUT` | `15 s` | Send timeout per WebSocket frame (`server.rs`) |
| `PING_INTERVAL` | `25 s` | Daemon-initiated WebSocket ping interval (`server.rs`) |

### PTY Allocation & Process Hygiene

1. **Master/slave pair (`open_pty_pair`):** Calls `posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC)`, `grantpt`, `unlockpt`, and `ioctl(TIOCGPTN)` to resolve `/dev/pts/<n>`. Opens the slave with `O_NOCTTY | O_CLOEXEC` and passes it through `ensure_fd_ge_3` (`fcntl(F_DUPFD_CLOEXEC, 3)`) so the slave descriptor never collides with stdio `0`/`1`/`2` during `dup2`. Captures the Linux `tty_nr` from `fstat(slave)` (`encode_linux_tty_nr`), applies initial `TIOCSWINSZ`, sets `O_NONBLOCK` on the master fd, and registers the master fd with Tokio's `AsyncFd<OwnedFd>`.
2. **Shell selection & environment (`PtySession::spawn`):** Resolves `bash -i` via `enriched_path()` (falling back to `sh -i` or `/bin/sh -i`), runs in the tab's validated `cwd`, sets `PATH`, `TERM=xterm-256color`, `COLORTERM=truecolor`, removes `NO_COLOR` and `FORCE_COLOR`, and defaults unset `SHELL` (to the resolved binary) and `LANG` (to `C.UTF-8`).
3. **`pre_exec` setup:** Before `exec`, clears the inherited signal mask (`sigprocmask(SIG_SETMASK)` with an empty set), resets `SIGHUP`, `SIGTERM`, `SIGPIPE`, `SIGINT`, `SIGQUIT`, `SIGTSTP`, `SIGTTIN`, `SIGTTOU`, and `SIGCHLD` to `SIG_DFL`, calls `setsid()` to create a new session, acquires the slave as controlling terminal via `ioctl(slave_raw, TIOCSCTTY, 0)`, redirects `stdin`/`stdout`/`stderr` with `dup2`, and closes `slave_raw` if `> 2`.
4. **Parent slave close:** Immediately after `Command::spawn()`, the parent drops its `slave` `OwnedFd` so the kernel delivers `EIO` / EOF on the master fd as soon as the shell and its children exit.

### Non-Blocking I/O, Ring Buffer & Backpressure

- **Async writer task:** Reads byte chunks from `mpsc::channel(256)` (`PTY_INPUT_QUEUE_CAPACITY`), awaits `master_write.writable()` biased against a `watch` shutdown channel, and loops `libc::write` to handle partial writes, `WouldBlock` (`guard.clear_ready()`), and `EINTR`. Because the channel is bounded, a stalled child process cannot grow daemon memory without bound.
- **Async reader task:** Awaits `master_read.readable()` biased against `shutdown_read`, performs up to 4 × 16 KiB `libc::read` calls per wakeup (yielding via `tokio::task::yield_now()` if still readable to avoid starving other tasks), and pushes bytes into `append_pty_chunk`. Treats `n == 0` and Linux PTY `EIO` as EOF and signals `reader_done_tx`.
- **2 MiB sequenced ring buffer:** `PtyStreamState` stores output in a `VecDeque<u8>` tracked by monotonically increasing byte offsets `[base_seq, next_seq)`. When appending a chunk exceeds `PTY_BUFFER_BYTES`, oldest bytes are drained and `base_seq` advances accordingly.
- **Lock-atomic attach (`PtySession::attach`):** Under a single mutex lock on `PtyStreamState`, the daemon evaluates truncation (`session_mismatch || after_seq < base_seq || after_seq > next_seq`), slices replay bytes `[replay_from, next_seq)` into 64 KiB chunks, and inserts the subscriber's bounded `mpsc::Sender` into `subscribers` before releasing the lock. This guarantees zero gaps and zero duplicated live events across the replay boundary.
- **Subscriber backpressure:** `append_pty_chunk` fans out live chunks using `try_send` and immediately evicts any subscriber whose 256-slot channel is full (`subscribers.retain(...)`). Combined with `PTY_WS_WRITE_TIMEOUT` (`15 s`) on every WebSocket send, a slow or half-open mobile connection cannot stall the PTY reader or exhaust daemon memory; the client simply reconnects and replays from the ring buffer.

### Exit Drain, `pidfd` Cleanup & Graceful Shutdown

- **Trailing output drain before `Exit`:** On spawn, `PtySession` opens a `pidfd` (`SYS_pidfd_open`) and registers a `dup_cloexec` copy with `AsyncFd` (`Interest::READABLE`). When the shell exits, the waiter task wakes on `pidfd_async.readable()` **before** reaping the child (preventing PID reuse while `reaped` is still `false`), waits up to `PTY_EXIT_DRAIN_TIMEOUT` (`200 ms`) for the reader task to hit EOF, marks `reaped = true`, calls `child.wait().await`, closes `input_tx`, sets `running = false`, and emits `PtyServerEvent::Exit { exit_code, signal }`. If `pidfd_open` is unavailable on an older kernel, it falls back to `child.wait().await` followed by the same bounded drain.
- **Process-tree termination (`begin_terminate` / `terminate` / `escalate_sigkill`):** Idempotent via `terminated: AtomicBool`. Phase 1 collects the foreground process group (`tcgetpgrp`), the shell's process group (if unreaped), and any surviving processes in `/proc` sharing `sid == shell_pid` (additionally filtering by `slave_tty_nr` once reaped to avoid PID-reuse collisions, and always excluding PID `<= 1`, `daemon_pid`, and `daemon_pgid`). It sends `SIGHUP` then `SIGCONT` (using `SYS_pidfd_send_signal` for the shell leader when available), signals `shutdown_tx`, aborts reader/writer tasks, drops `master_ctrl` to close the master fd, and clears subscribers. It then polls every `5 ms` for up to `100 ms` (`PTY_TERMINATE_GRACE`) and escalates any survivors (such as `trap '' HUP` background jobs) to `SIGKILL`.
- **Tab deletion & spawn races:** `DELETE /api/tabs/:id` calls `st.pty.kill_and_remove(&tab_id)`. Both initial connect and in-socket `restart` in `handle_pty_ws` re-verify that the tab still exists as a PTY tab immediately after spawning and kill/remove the session if a concurrent `DELETE` raced with creation.
- **Graceful daemon shutdown & self-update integration:** On `SIGINT`, `SIGTERM`, or an in-place self-update restart (`restart_rx`), `run_daemon` broadcasts `ws_shutdown_tx.send(true)` and calls `state_shutdown.pty.shutdown_all()`. Every `handle_pty_ws` loop selects on `ws_shutdown.changed()` so open and exited PTY WebSockets close immediately, while `shutdown_all()` sets `shutting_down = true` (rejecting new spawns) and runs Phase 1 across all live sessions concurrently with one shared `100 ms` grace period before `SIGKILL` escalation.

---

## WebSocket Protocol & Reconnect Lifecycle

### Capability Discovery

`GET /api/info` and `WS /ws` `snapshot.server` include:

```json
{
  "pty": {
    "version": 1,
    "maxCols": 512,
    "maxRows": 256,
    "bufferBytes": 2097152
  }
}
```

### Connection & Query Parameters

Endpoint: `WS /api/tabs/:id/pty`

Authentication uses `Authorization: Bearer <token>` (preferred by the Android client) or `?token=<token>` (unless `--no-auth` is enabled). Requests fail before WebSocket upgrade with HTTP `401` (unauthorized), `404` (tab missing), or `400` (tab is not `shellMode: "pty"`).

| Query Param | Default | Description |
| :--- | :--- | :--- |
| `cols` | `80` | Desired columns (clamped to `10..=512`) |
| `rows` | `24` | Desired rows (clamped to `4..=256`) |
| `sessionId` | *(none)* | Client's previously attached PTY `sessionId` for warm reconnect |
| `afterSeq` | `0` | Last contiguous output byte offset already applied by the client |
| `restart` | `false` | If `1` or `true`, terminates any existing session and spawns a fresh PTY before attaching at `afterSeq = 0` |
| `token` | *(none)* | Optional bearer token fallback when headers cannot be supplied |

### Frame Formats

- **Server → Client binary frame:** 8-byte unsigned big-endian `u64` `start_seq` followed by raw PTY output bytes.
  ```text
  ┌───────────────────────────┬────────────────────────────┐
  │ start_seq (8 bytes, u64 BE)│ raw PTY stdout/stderr bytes│
  └───────────────────────────┴────────────────────────────┘
  ```
- **Client → Server binary frame:** Raw terminal input bytes (UTF-8 text, key sequences, paste payloads, or terminal query replies).
- **Server → Client JSON text frames:**
  - `ready`: `{ "type": "ready", "sessionId": "<16-hex>", "cols": 80, "rows": 24, "baseSeq": 0, "nextSeq": 1024, "replayFrom": 0, "truncated": false, "running": true, "exitCode": null, "signal": null }`
  - `replay_end`: `{ "type": "replay_end", "seq": 1024 }`
  - `exit`: `{ "type": "exit", "exitCode": 0, "signal": null }`
  - `error`: `{ "type": "error", "error": "..." }`
- **Client → Server JSON text frames:**
  - `resize`: `{ "type": "resize", "cols": 100, "rows": 36 }`
  - `restart`: `{ "type": "restart", "cols": 80, "rows": 24 }` (`cols`/`rows` optional, default to current session dimensions; spawns a fresh PTY and streams a new `ready` → replay → `replay_end` sequence on the same socket)

### Reconnect, Replay & Reply Suppression

```text
Client                                                    Daemon
  │  WS connect ?afterSeq=S&sessionId=ID&cols=C&rows=R      │
  ├────────────────────────────────────────────────────────▶│
  │                                                         │ get_or_spawn() / restart()
  │                                                         │ attach(ID, S) [atomic lock]
  │  Text:  ready { sessionId, cols, rows, replayFrom, ... }│
  │◀────────────────────────────────────────────────────────┤
  │  Bin:   [start_seq][replay chunk 0..64 KiB]   (x N)     │  (client: suppressReplies = true,
  │◀────────────────────────────────────────────────────────┤   keeps server cols x rows)
  │  Text:  replay_end { seq }                              │
  │◀────────────────────────────────────────────────────────┤
  │  (if !running) Text: exit { exitCode, signal }          │
  │◀────────────────────────────────────────────────────────┤
  │  (if desired != server) Text: resize { cols, rows }     │  (client: post-replay resize +
  ├────────────────────────────────────────────────────────▶│   forceFull frame capture)
  │  Bin:   raw input / live [start_seq][output]            │  (client: suppressReplies = false,
  │◀═══════════════════════════════════════════════════════▶│   forwards WRITE_PTY replies)
```

- **Warm reconnect vs. cold reset:** On `ready`, the client performs a **cold reset** if `truncated == true`, `currentSessionId == null`, or `sessionId` changed — resetting `GhosttyTerminalEngine`, re-applying theme colors, resizing to `serverCols x serverRows`, and setting `appliedSeq = replayFrom`. Otherwise it performs a **warm reconnect**, retaining local terminal state and applying only bytes beyond `appliedSeq` (skipping any overlapping prefix if `start_seq < appliedSeq`).
- **`WRITE_PTY` reply suppression during replay:** Between `ready` and `replay_end`, `PtyTabSession` passes `suppressReplies = true` to `GhosttyTerminalEngine.writeVt`. Historical escape queries inside the ring buffer (e.g., DSR/CPR cursor queries, DA device attribute queries, OSC color queries) do not send stale responses back to the live shell. Once live (`!isReplaying`), replies captured by `on_write_pty` are immediately sent back as binary WebSocket frames.
- **Post-replay resize:** During replay, the local VT engine matches the daemon's `cols x rows` so replayed escape sequences wrap identically to how they were emitted. On `replay_end`, if the viewport's `desiredCols x desiredRows` differ from `daemonCols x daemonRows`, the client resizes the local engine, publishes a full frame, and sends a `resize` control message to the daemon.

---

## Android `libghostty-vt` 1.0.2 Native Build & JNI Bridge

### Reproducible Native Build (`scripts/build-libghostty-vt.sh`)

[`scripts/build-libghostty-vt.sh`](../scripts/build-libghostty-vt.sh) builds and caches `libghostty-vt` `1.0.2` and compiles the thin C JNI wrapper [`app/src/main/jni/ghostty_vt_jni.c`](../app/src/main/jni/ghostty_vt_jni.c):

- **Pinned, SHA-256 verified inputs:** Downloads and verifies `libghostty-vt` `1.0.2` source and offline dependency bundle (`--system "$DEPS_ROOT"`), plus Zig `0.17.0` (`x86_64-linux` and `aarch64-linux`).
- **Android NDK resolution:** Targets NDK `29.0.14206865` (`ANDROID_NDK_VERSION`, min API `24`), checking `GHOSTTY_VT_ANDROID_NDK_HOME`, `$ANDROID_HOME/ndk/$ANDROID_NDK_VERSION`, major-version-matching `ANDROID_NDK_HOME`/`ANDROID_NDK_ROOT`, then highest available NDK under `$ANDROID_HOME/ndk`.
- **Cache & `flock` serialization:** Stores downloaded toolchains and compiled static libraries (`libghostty-vt.a` + headers) under `~/.cache/agentsterm-ghostty-vt` (`GHOSTTY_VT_CACHE_DIR`). Static builds are serialized with an exclusive `flock` on `$CACHE_DIR/.lock` so concurrent Gradle tasks or parallel worktrees on the same machine never corrupt shared cache entries. Per-target `.libghostty_vt_jni.stamp` files (`<version>:<zig_target>:<sha256(ghostty_vt_jni.c)>`) skip unnecessary recompiles.
- **Targets & 16 KB ELF alignment:**
  - **Android ABIs** (`--android-only`, `ReleaseSmall`): `arm64-v8a` (`aarch64-linux-android`), `x86_64` (`x86_64-linux-android`), and `armeabi-v7a` (`arm-linux-androideabi` / `armv7a-linux-androideabi24-clang`). Linked with `-Wl,-z,max-page-size=16384` (Android 15+ 16 KB page-size compatibility), `-Wl,--gc-sections`, `-Wl,--exclude-libs,ALL`, and `-s` into `app/build/ghostty-vt/jniLibs/<abi>/libghostty_vt_jni.so`.
  - **Host Linux JVM** (`--host-only`, `ReleaseFast`): Compiles `app/build/ghostty-vt/host/libghostty_vt_jni.so` with host `cc` against the JDK's `jni.h` so JVM unit tests exercise the real `libghostty-vt` library without an emulator.
- **Gradle wiring ([`app/build.gradle.kts`](../app/build.gradle.kts)):** Registers `buildGhosttyVtHostNative` (wired into all `Test` tasks with `java.library.path` prepended) and `buildGhosttyVtAndroidNative` (wired into `merge*JniLibFolders` and added to `main.jniLibs.directories`).

### JNI Wrapper & Compact Binary Frame Protocol

[`app/src/main/jni/ghostty_vt_jni.c`](../app/src/main/jni/ghostty_vt_jni.c) exposes a flat C JNI surface bound by `GhosttyVtNative` and wrapped in the thread-safe `AutoCloseable` `GhosttyTerminalEngine` ([`GhosttyVtNative.kt`](../app/src/main/java/com/appevol/agentsterm/terminal/GhosttyVtNative.kt), [`TerminalFrame.kt`](../app/src/main/java/com/appevol/agentsterm/terminal/TerminalFrame.kt)):

- **Per-handle native state (`GhosttyHandle`):** Owns `GhosttyTerminal` (4 MiB default scrollback, `CONTINUATION_MAX_BYTES = 4096`), `GhosttyRenderState`, reusable row/cell iterators, `GhosttyKeyEncoder`, `GhosttyKeyEvent`, reusable `ByteBuf` buffers (`pty_out`, `frame_buf`), `suppress_pty_writes`, and latched `bell_rung` / `title_changed` flags.
- **Core operations:**
  - `nativeCreate` / `nativeDestroy` / `nativeReset` / `nativeResize`
  - `nativeSetColors` — configures foreground, background, cursor RGB, and expands a 16-color theme palette to 256 colors via `ghostty_color_palette_generate`
  - `nativeWriteVt` — bounds-checks `offset`/`length`, feeds bytes into `ghostty_terminal_vt_write`, and returns any `on_write_pty` reply bytes (unless `suppress_replies` is true)
  - `nativeEncodeKey` — syncs `GhosttyKeyEncoder` from the terminal's live modes (`ghostty_key_encoder_set_from_terminal`: application cursor keys, Kitty keyboard flags, etc.) and encodes key/modifier/action/UTF-8/unshifted codepoint into wire bytes
  - `nativeIsPasteSafe` / `nativeEncodePaste` — exposes `ghostty_paste_is_safe` and `ghostty_terminal_paste` (applying bracketed paste `ESC[200~`…`ESC[201~` when active and gating unsafe newlines/control chars unless `allow_unsafe` is true)
  - `nativeScrollViewport` — supports `TOP` (`0`), `BOTTOM` (`1`), and `DELTA` (`2`)
  - `nativeGetTitleUtf8` — reads OSC window title when `title_changed` is flagged
  - `nativeSnapshotEncode` / `nativeSnapshotDecode` — serializes/restores full terminal state via `ghostty_snapshot_encode_alloc` and `ghostty_snapshot_decoder_decode`, re-registering callbacks and re-applying the theme palette on restore
- **Compact dirty-row binary frame protocol (`nativeCaptureFrame`):** Avoids per-cell JNI calls by serializing all dirty viewport rows into one reusable big-endian byte buffer, returning `null` when nothing is dirty (`DIRTY_FALSE`) and `force_full == false`:
  - **Header (37 bytes):** `cols(u16)`, `rows(u16)`, `dirty(u8: 0=clean, 1=partial, 2=full)`, `defaultFg(u32 ARGB)`, `defaultBg(u32 ARGB)`, `cursorColor(u32 ARGB, 0 if unset)`, `cursorFlags(u8)`, `cursorStyle(u8: 0=bar, 1=block, 2=underline, 3=block_hollow)`, `cursorX(u16)`, `cursorY(u16)`, `scrollTotal(u32)`, `scrollOffset(u32)`, `scrollLen(u32)`, `dirtyRowCount(u16)`.
  - **`cursorFlags` bits:** `0` visible, `1` in-viewport, `2` blinking, `3` password input, `4` wide tail, `5` alternate screen, `6` bell rung, `7` title changed.
  - **Row records (`dirtyRowCount` entries):** `rowY(u16)`, `cellCount(u16)`, followed by `cellCount` cell records.
  - **Cell record:** `fgArgb(u32)`, `bgArgb(u32, 0 = default bg; INVERSE swapped in C)`, `styleFlags(u16: bits 0..7 bold/italic/faint/blink/inverse/invisible/strikethrough/overline; bits 8..10 underline style)`, `wide(u8: 0=narrow, 1=wide, 2=spacer_tail, 3=spacer_head)`, `utf8Len(u8, <= 64)`, `utf8[utf8Len]`.
  - Calls `ghostty_render_state_clean` after serialization. On the Kotlin side, `GhosttyTerminalEngine` caches `Array<TerminalRow>` so partial dirty frames only decode and replace modified rows, emitting an immutable `TerminalFrame` with a monotonic `revision`.

---

## Android Session Management & Mobile Terminal UI

### Session Lifecycle (`PtySessionManager` & `PtyTabSession`)

[`app/src/main/java/com/appevol/agentsterm/terminal/PtySessionManager.kt`](../app/src/main/java/com/appevol/agentsterm/terminal/PtySessionManager.kt) is owned by `MainViewModel` (`ptySessions`) and integrated into [`HerdrApp.kt`](../app/src/main/java/com/appevol/agentsterm/ui/HerdrApp.kt) (routing `activeTab.isPtyShell` to `PtyTerminalWorkspace`, keyed by `activeTab.id`) and [`NewTabSheet.kt`](../app/src/main/java/com/appevol/agentsterm/ui/NewTabSheet.kt):

- **Workspace synchronization:** `observeWorkspace` collects `(config, connected, openPtyIds)` from `WorkspaceState`, propagating config updates and pruning closed PTY tabs once connected. Deleting a tab calls `remove(tabId)`; `MainViewModel.onCleared()` calls `closeAll()`.
- **Server-switch isolation:** Tracks `lastEndpoint = (host, port)`. When the active VM endpoint changes, `getOrCreate` and `updateConfig` immediately close and evict all sessions from the previous host so old WebSockets never attempt to reconnect to a different VM.
- **Connection state & backoff:** Uses exponential reconnect backoff (`1.5 s` base, doubling up to `15 s`) on transient network drops while in the foreground. Non-retryable failures (HTTP `401`/`403` auth errors, `400`, `404`, or an explicit daemon `error` JSON frame) enter `PtyConnectionState.ERROR` without looping. Backgrounding (`onForegroundChanged(false)`) cancels pending reconnect timers; returning to foreground resumes automatically unless `EXITED` or `ERROR`.
- **Restart handling:** Tapping **Restart** in the exited banner sends `{ "type": "restart", "cols": ..., "rows": ... }` over an open socket; if the socket is already closed, `PtyTabSession` sets `pendingRestart = true`, resets `sessionId`/`appliedSeq`, reconnects, and emits the `restart` payload on `onOpen`.
- **16 ms frame coalescing:** Live output frames schedule a single coalesced `captureFrame(forceFull = false)` after `16 ms` (`DEFAULT_FRAME_FLUSH_MS`) to cap UI recomposition around 60 fps under bursty output, while `withFrameIfNewer` guards against out-of-order frame publication using `TerminalFrame.revision`.
- **Resize debouncing:** `TerminalGridCanvas` measures `JetBrains Mono` cell dimensions, computes `cols`/`rows` via `floor(availablePx / cellPx)` clamped to `10..512` × `4..256`, dispatches the initial resize immediately on mount, and debounces subsequent layout/font-zoom/IME-inset resizes by `120 ms` (`RESIZE_DEBOUNCE_MS`).

### Mobile Terminal UI (`PtyTerminalScreen.kt`)

[`app/src/main/java/com/appevol/agentsterm/ui/PtyTerminalScreen.kt`](../app/src/main/java/com/appevol/agentsterm/ui/PtyTerminalScreen.kt) provides the Compose terminal canvas, IME input bridge, and extra-keys toolbar:

- **Three-pass `Canvas` rendering (`TerminalGridCanvas`):**
  - Uses bundled `JetBrains Mono` (`R.font.jetbrains_mono` / `jetbrains_mono_bold`, fallback `Typeface.MONOSPACE`), zoomable between `9 sp` and `22 sp` (default `13 sp`, step `1.5 sp`, saved per tab with `rememberSaveable`).
  - **Pass 1 (backgrounds):** Coalesces contiguous cells sharing a non-zero `bgArgb` into single `drawRect` calls.
  - **Pass 2 (text & decorations):** Skips `SPACER_TAIL`, `SPACER_HEAD`, and `isInvisible` cells. Batches contiguous narrow printable ASCII runs (`' '..'~'` or blank) sharing `fgArgb` and `styleFlags` into one `canvas.drawText` call via a reused `StringBuilder`. Renders non-ASCII, combining graphemes, and `WIDE` cells individually anchored to exact column coordinates (`padPx + startCol * cw`) with 2-column decoration spans for wide glyphs. Supports bold, italic, bold-italic, faint alpha (`145/255`), underline, strikethrough, and overline.
  - **Pass 3 (cursor):** Draws `BAR`, `UNDERLINE`, `BLOCK_HOLLOW`, or semi-transparent (`0x88`) block cursors. Handles wide characters by backing up to `x - 1` when `cursor.wideTail` is set and drawing a `2 * cw` cursor width over wide cells.
  - Exposes `frame.toPlainText()` through Compose semantics on `pty_terminal_canvas` for accessibility and UI tests.
- **Raw IME & hardware keyboard bridge (`TerminalImeInputView`):**
  - Hosted as a 1×1 dp focusable `AndroidView` inside the terminal container; tapping the canvas focuses it and shows the soft keyboard.
  - **Codepoint-safe composing:** `TerminalInputConnection` diffs `composingText` against incoming composing/committed text by Unicode codepoints (`codePointAt`, `Character.charCount`, `codePointCount`) and emits exact `GhosttyKey.BACKSPACE` counts before writing the new suffix, preventing UTF-16 surrogate-pair corruption on emoji and CJK input. `deleteSurroundingText` and `deleteSurroundingTextInCodePoints` similarly trim `composingText` on surrogate boundaries.
  - **`Enter` → CR:** `emitCommittedText` splits `\r`, `\n`, and `\r\n` into `GhosttyKey.ENTER` events (encoded by Ghostty to `0x0D`), matching `performEditorAction` and hardware `KEYCODE_ENTER` / `KEYCODE_NUMPAD_ENTER`.
  - **Hardware keys:** Translates navigation/function keys (`mapAndroidSpecialKey`) and `Ctrl`/`Alt` ASCII chords (`dispatchModifiedAsciiKey`) through Ghostty's mode-aware key encoder, tracking `handledKeyCodes` to suppress duplicate `ACTION_UP` handling.
  - **API 31+-safe keyboard toggle:** `toggleKeyboard()` queries `ViewCompat.getRootWindowInsets(this)?.isVisible(WindowInsetsCompat.Type.ime())` instead of deprecated `toggleSoftInput`, calling `hideSoftInputFromWindow` or `focusAndShowKeyboard()`.
- **Extra-keys toolbar (`PtyControlToolbar`) & gestures:**
  - Horizontally scrollable bar docked above the IME: `Tab`, latchable `Ctrl`, `^C`, `Esc`, `←`, `↓`, `↑`, `→`, latchable `Alt`, `Paste` (bracketed paste via Ghostty with confirmation dialog when `nativeEncodePaste` flags unsafe multi-line or control content), `Copy screen`, `Home`, `End`, `PgUp`, `PgDn`, `Zoom -`, `Zoom +`, and `Keyboard` toggle.
  - Latching `Ctrl` or `Alt` applies to the next IME character or toolbar/hardware key and automatically clears upon consumption (`consumeLatchedMods`).
  - **Primary vs. alternate-screen scroll:** Vertical drag gestures scroll local Ghostty scrollback on the primary screen (revealing a jump-to-bottom FAB `pty_scroll_bottom_button` when scrolled up) and emit `ARROW_UP` / `ARROW_DOWN` key events (capped at 5 per gesture step) when `frame.isAlternateScreen` is active so full-screen TUIs (`vim`, `less`, `htop`, `tmux`) scroll naturally by touch.

---

## Testing & CI Coverage

All PTY tests run in GitHub Actions (`.github/workflows/ci.yml`) and are required by the `All Required CI Checks` (`ci-gate`) merge gate:

| Layer | Suite / File | What It Verifies | CI Job |
| :--- | :--- | :--- | :--- |
| Rust daemon unit | [`daemon-rs/src/pty.rs`](../daemon-rs/src/pty.rs) | Echo, `stty size` resize, warm/cold replay, `terminate()` killing foreground + `trap '' HUP` background jobs, trailing output drained before `Exit`, `shutdown_all()` rejecting new spawns, lagging-subscriber eviction | `rust-daemon-build-and-test` |
| Rust daemon integration | [`daemon-rs/tests/daemon_integration.rs`](../daemon-rs/tests/daemon_integration.rs) | `/api/info` `pty` capability, default `"command"` vs opt-in `"pty"`, HTTP `400` on command/stdin/interrupt APIs, auth enforcement, tab-completion, `Ctrl+C`, `stty -echo` secret non-echo, resize, warm reconnect, `?restart=1`, trailing output before `exit`, `DELETE /api/tabs/:id` killing active jobs | `rust-daemon-build-and-test` |
| Android native JNI unit | [`GhosttyVtEngineTest.kt`](../app/src/test/java/com/appevol/agentsterm/terminal/GhosttyVtEngineTest.kt) | Styled cells & dirty-row batching, live vs. replay `WRITE_PTY` reply suppression, mode-synchronized key encoding, JNI bounds checks, paste safety & bracketed paste, alternate screen, title, scrollback, snapshot round-trip (runs against real host `libghostty_vt_jni.so`) | `build-and-unit-test` |
| Android session unit | [`PtySessionManagerTest.kt`](../app/src/test/java/com/appevol/agentsterm/terminal/PtySessionManagerTest.kt) | Replay suppression & privacy check (no raw PTY bytes in `DebugRecorder`), warm reconnect with `afterSeq` + `sessionId`, non-retryable server errors & HTTP `404`, disconnected restart, server-switch isolation, latched `Ctrl` punctuation | `build-and-unit-test` |
| Android Compose UI unit | [`PtyTerminalScreenTest.kt`](../app/src/test/java/com/appevol/agentsterm/ui/PtyTerminalScreenTest.kt), [`NewTabSheetTest.kt`](../app/src/test/java/com/appevol/agentsterm/ui/NewTabSheetTest.kt) | Extra-keys toolbar & `Ctrl` latch, IME `Enter` → CR & codepoint-safe emoji composing, `NewTabSheet` Command default vs. PTY opt-in and hiding selector on older daemons | `build-and-unit-test` |
| Android Emulator ↔ Docker VM E2E | [`RealVmDockerE2ETest.kt`](../app/src/androidTest/java/com/appevol/agentsterm/RealVmDockerE2ETest.kt) (`test04_interactivePtyShellTabSupportsAutocompleteAndCtrlC`) | Real SSH bootstrap into Docker VM, creating an opt-in PTY tab, rendering live `bash` prompt on native `libghostty-vt` canvas, IME focus, and `Tab` / `^C` toolbar dispatch | `e2e-tests` |

### CI Caching & Local Toolchains

- Both `build-and-unit-test` and `e2e-tests` cache `~/.cache/agentsterm-ghostty-vt` keyed by `hashFiles('scripts/build-libghostty-vt.sh')` so Zig `0.17.0` and the compiled `libghostty-vt` `1.0.2` static archives are reused across runs.
- [`install_deps.sh`](../install_deps.sh) installs `ndk;29.0.14206865` alongside the Android SDK platform and build tools so local Android builds match CI.

---

## Related Docs

- [Architecture](architecture.md) — `$HOME/.agents-term/` storage layout, single-writer ownership, and CI job topology
- [Daemon API Contract](daemon-api.md) — HTTP and WebSocket endpoints served by `agentsterm-daemon`
- [Development, Testing & Releases](development.md) — local toolchain setup, static checks, and running test suites
