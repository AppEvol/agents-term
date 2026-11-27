# Daemon API Contract

AgentsTerm communicates with `agentsterm-daemon` over HTTP and WebSocket inside the Tailscale WireGuard tunnel.

## Authentication

When bearer authentication is enabled (the default unless the daemon is started with `--no-auth`):

- REST endpoints require `Authorization: Bearer <token>`.
- The WebSocket endpoint (`/ws`) accepts `Authorization: Bearer <token>` or `?token=<token>`.

Keep REST and WebSocket payloads backward compatible with released Android app builds.

## Endpoints

| Endpoint | Purpose |
| :--- | :--- |
| `GET /api/info` | Returns server version, git `commit`, hostname, Tailscale IP, `defaultCwd`, `defaultSystemPrompt`, `defaultSystemPromptFullAccess`, `attachments` (image upload limits; absent on daemons without attachment support), and detected `agents[]` (including discovered `models[]` and `defaultModel`). |
| `GET /api/agents/:agent/models` | Returns (and optionally refreshes via `?refresh=1`) available models for an agent CLI (`agy`, `claude`, `codex`). |
| `GET /api/agents/:agent/usage` | Returns usage limit windows reported by an agent CLI, if available. |
| `GET /api/tabs` | Lists all open persistent agent tabs, `activeTabId`, and `closedTabs`. |
| `POST /api/tabs` | Creates a new persistent tab (`agent`, `cwd`, `permissionMode`, optional `title`, optional `model`, optional `systemPrompt`). Returns HTTP `409` when the VM already has 10 or more open tabs. |
| `GET /api/tabs/closed` | Lists recently closed tabs retained for revival (up to 50 tabs for 24 hours). |
| `POST /api/tabs/closed/:id/revive` | Reopens a recently closed tab and returns `{tab, messages, closedTabs}` (HTTP `409` if 10 tabs are already open; `404` if expired or missing). |
| `PATCH /api/tabs/:id` | Renames a tab, updates its tool `permissionMode`, changes its `model`, updates its `systemPrompt` (pass `""` to clear), or sets its task `status` (`"done"` / `"idle"`). |
| `DELETE /api/tabs/:id` | Stops any active turn and closes/archives a persistent tab. |
| `GET /api/tabs/:id/messages` | Fetches full message/tool/thinking transcript for a tab. |
| `POST /api/tabs/:id/messages` | Sends or queues a prompt on the tab (with optional `overridePermissionMode` and optional `attachments`, a list of uploaded image IDs). `text` may be empty when attachments are provided. |
| `DELETE /api/tabs/:id/queue/:item` | Removes a queued message from a tab before it runs. |
| `POST /api/tabs/:id/attachments` | Uploads one image (raw request body; PNG, JPEG, GIF, WebP, or BMP, at most 8 MB) into `~/.agents-term/agents_tabs/<tabId>/attachments/` and returns `{attachment: {id, name, mimeType, bytes, path}}`. The agent receives the file paths in its prompt. Not available on shell tabs. |
| `GET /api/tabs/:id/attachments/:attachmentId` | Downloads an uploaded image for rendering in the chat transcript. |
| `POST /api/tabs/:id/interrupt` | Sends interrupt (`SIGINT`/`SIGTERM`) to a running agent turn; optionally accepts `processQueue`, `text`, `overridePermissionMode`, and `attachments` to immediately run the next message. |
| `POST /api/tabs/:id/read` | Marks a tab as read. |
| `GET /api/fs?path=...` | Lists directories on the VM for the New Tab directory browser. |
| `POST /api/debug/dumps` | Uploads an Android debug dump (`events.ndjson`, `state.json`, `logcat.txt`, `crash.txt`, optional `screenshot.png`) into `~/.agents-term/debug-dumps/` and updates the `latest` symlink. |
| `GET /api/debug/dumps` | Lists recent Android debug dump folders stored on the VM. |
| `GET /api/app/status` | App state reporter: reports whether a connected app has remote inspection (`ready`) and remote control (`controlReady`) enabled. |
| `POST /api/app/command` | App state reporter: relays `{command, args, timeoutMs}` to the connected app over WebSocket and returns its result (used by `agents_term_app_cli` / `agentsterm-daemon app`). |
| `WS /ws?token=...` | Real-time stream of `snapshot`, `tab_*`, `message_created`, `message_delta`, `tool_update`, and `message_updated` events. The app sends `app_hello` and `app_state_response` frames, and the daemon sends `app_state_request` frames (see [Live Debugging](live-debugging.md)). |

## Related Docs

- [Architecture](architecture.md) — `$HOME/.agents-term/` storage layout and single-writer ownership rules
- [VM Daemon Setup & Operations](vm-daemon.md) — installation, service lifecycle, and CLI commands
- [Live Debugging](live-debugging.md) — `app_hello` / `app_state_*` protocol and `agents_term_app_cli` usage
