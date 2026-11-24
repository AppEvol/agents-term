"""Runs agent turns (shell / agy / claude / codex) and streams progress to WebSocket clients.

Each turn spawns the agent CLI non-interactively, parses its streaming output into an assistant
ChatMessage (text, thinking, tool calls, usage), and broadcasts message_* / tool_update / tab_updated
envelopes as it goes. Parsing intentionally mirrors the original TypeScript daemon (including JS
truthiness and `??` semantics) so the phone sees identical events.
"""

from __future__ import annotations

import asyncio
import codecs
import json
import os
import re
import signal
import sys
import traceback
from typing import Any, Callable, Dict, List, Optional

from .config import enriched_path, home_dir, resolve_binary
from .store import StateStore
from .util import (
    first_not_none,
    is_finite_number,
    is_number,
    js_round,
    js_str,
    js_truthy,
    json_compact,
    json_pretty,
    now_ms,
    rand_hex,
    summarize_tool_input,
    truncate,
)

Broadcast = Callable[[Dict[str, Any]], None]

CWD_MARKER = "__AGENTSTERM_CWD__"
MAX_TOOL_OUTPUT = 12000
_LINE_SPLIT_RE = re.compile(r"\r?\n")
_MODEL_CMD_RE = re.compile(r"^/model(?:\s+(.*))?$", re.I)
_MODEL_RESET_RE = re.compile(r"(default|reset|auto)", re.I)
_FENCED_CODE_RE = re.compile(r"```[\s\S]*?```")
_NEGATED_CLOSE_TAB_RE = re.compile(
    r"\b(?:do\s+not|don't|cannot|can't|avoid|never|wait\s+before)\b[^\n.!?]{0,40}\bclose\s+(?:this|the)\s+tab\b",
    re.I,
)
_CLOSE_TAB_CUE_RE = re.compile(
    r"(?:"
    r"\b(?:you\s+(?:can|may)|feel\s+free\s+to|(?:it(?:'s|\s+is)\s+)?safe\s+to|safely|good\s+to|ready\s+to)\s+"
    r"(?:now\s+)?(?:safely\s+)?close\s+(?:this|the)\s+tab\b"
    r"|\bclose\s+(?:this|the)\s+tab\s+(?:now|whenever|if\s+you(?:'d|\s+would)?\s+like)\b"
    r")",
    re.I,
)


def is_task_finished_response(text: Optional[str]) -> bool:
    """Detect when a coding agent signals in its closing paragraph that the task is done and the tab can be closed."""
    if not isinstance(text, str) or not text.strip():
        return False
    without_code = _FENCED_CODE_RE.sub("", text).strip()
    if not without_code:
        return False
    paragraphs = [p.strip() for p in re.split(r"\n\s*\n", without_code) if p.strip()]
    tail = (paragraphs[-1] if paragraphs else without_code)[-320:]
    if _NEGATED_CLOSE_TAB_RE.search(tail):
        return False
    return bool(_CLOSE_TAB_CUE_RE.search(tail))


def _reject_constant(name: str) -> Any:
    raise ValueError(f"invalid JSON constant {name}")


def parse_json_line(text: str) -> Any:
    """JSON.parse: rejects NaN/Infinity, which Python's json accepts by default."""
    return json.loads(text, parse_constant=_reject_constant)


def as_obj(value: Any) -> Optional[Dict[str, Any]]:
    """`value && typeof value === "object"`: dicts as-is, arrays as an empty record, else None."""
    if isinstance(value, dict):
        return value
    if isinstance(value, list):
        return {}
    return None


def _num_or_none(value: Any) -> Any:
    return value if is_number(value) else None


def _tool_output_text(raw_out: Any) -> Optional[str]:
    if isinstance(raw_out, str):
        return raw_out
    if isinstance(raw_out, list):
        return "\n".join(
            js_str(x["text"]) if isinstance(x, dict) and "text" in x else json_compact(x) for x in raw_out
        )
    if raw_out is not None:
        return json_pretty(raw_out)
    return None


def block_separator(existing: Optional[str]) -> str:
    """Paragraph break to put before a new content block appended to `existing` text.

    Agents emit text in separate blocks around tool calls ("Starting." <tool> "Done."). Appending
    them directly glued sentences together ("Starting.Done.").
    """
    if not existing or existing.endswith("\n\n"):
        return ""
    return "\n" if existing.endswith("\n") else "\n\n"


class _BlockBreaks:
    """Tracks "a new block started" so the break is only emitted once the block has content."""

    def __init__(self) -> None:
        self.text = False
        self.thinking = False

    def start(self, block_type: Any) -> None:
        if block_type == "text":
            self.text = True
        elif block_type == "thinking":
            self.thinking = True

    def take_text(self, existing: Optional[str]) -> str:
        pending, self.text = self.text, False
        return block_separator(existing) if pending else ""

    def take_thinking(self, existing: Optional[str]) -> str:
        pending, self.thinking = self.thinking, False
        return block_separator(existing) if pending else ""


def _denied_from(rec: Dict[str, Any], default_action: str, default_name: str, use_alt_keys: bool) -> Dict[str, Any]:
    if use_alt_keys:
        action = js_str(first_not_none(rec.get("tool_name"), rec.get("action"), default_action))
        display = js_str(first_not_none(rec.get("tool_name"), rec.get("displayName"), default_name))
    else:
        action = js_str(first_not_none(rec.get("tool_name"), default_action))
        display = js_str(first_not_none(rec.get("tool_name"), default_name))
    tool_input = rec.get("tool_input")
    return {
        "action": action,
        "displayName": display,
        "detail": summarize_tool_input(tool_input) if js_truthy(tool_input) else None,
    }


class _ActiveTurn:
    def __init__(self, assistant_msg_id: str) -> None:
        self.assistant_msg_id = assistant_msg_id
        self.proc: Optional[asyncio.subprocess.Process] = None
        self.interrupted = False
        self.signal_on_spawn: Optional[int] = None
        self.kill_handles: List[asyncio.TimerHandle] = []

    def send_signal(self, sig: int) -> None:
        if self.proc is None:
            # Not spawned yet: deliver once it is.
            self.signal_on_spawn = sig
            return
        try:
            self.proc.send_signal(sig)
        except (ProcessLookupError, OSError):
            pass

    def cancel_kill_timers(self) -> None:
        for h in self.kill_handles:
            h.cancel()
        self.kill_handles.clear()


def _tab_system_prompt(tab: Dict[str, Any]) -> Optional[str]:
    raw = tab.get("systemPrompt")
    return raw.strip() if isinstance(raw, str) and raw.strip() else None


def _toml_basic_string(value: str) -> str:
    # JSON string escaping (\", \\, \b, \f, \n, \r, \t, \u00xx) is valid TOML basic string syntax.
    return json.dumps(value, ensure_ascii=False)


class AgentRunner:
    def __init__(self, store: StateStore, broadcast: Broadcast) -> None:
        self.store = store
        self.broadcast = broadcast
        self._active: Dict[str, _ActiveTurn] = {}
        self._tasks: set = set()
        self._agy_prompt_dirty: set = set()

    def is_running(self, tab_id: str) -> bool:
        return tab_id in self._active

    def mark_system_prompt_updated(self, tab_id: str) -> None:
        self._agy_prompt_dirty.add(tab_id)

    # ---------------------------------------------------------------- public API

    def start_turn(
        self, tab: Dict[str, Any], prompt_text: str, override_permission_mode: Optional[str] = None
    ) -> Dict[str, Any]:
        now = now_ms()
        user_message = self._new_message(tab["id"], "user", prompt_text, "complete", now)
        assistant_message = self._new_message(tab["id"], "assistant", "", "streaming", now + 1)
        self.store.add_message(tab["id"], user_message)
        self.store.add_message(tab["id"], assistant_message)

        # Handle /model locally on coding-agent tabs so model switches persist across turns.
        model_cmd = _MODEL_CMD_RE.match(prompt_text.strip()) if tab.get("agent") != "shell" else None
        if model_cmd:
            return self._handle_model_command(tab, user_message, assistant_message, (model_cmd.group(1) or "").strip())

        effective_mode = (
            (override_permission_mode or "").strip() or tab.get("permissionMode") or "full-access"
        )
        working_tab = self.store.update_tab(
            tab["id"],
            {
                "status": "working",
                "statusDetail": f"Starting {tab.get('agent')}…",
                "lastMessagePreview": truncate(prompt_text, 120),
                "unread": False,
            },
        )
        self.broadcast({"type": "message_created", "tabId": tab["id"], "message": user_message})
        self.broadcast({"type": "message_created", "tabId": tab["id"], "message": assistant_message})
        if working_tab:
            self.broadcast({"type": "tab_updated", "tab": working_tab})

        run_cwd = tab.get("cwd") if tab.get("cwd") and os.path.exists(tab["cwd"]) else (os.environ.get("HOME") or "/")
        agent = tab.get("agent")
        # Snapshot before any streaming mutates the message (the caller serializes this).
        result = {
            "userMessage": self._snapshot_message(user_message),
            "assistantMessage": self._snapshot_message(assistant_message),
        }
        if agent == "shell":
            self._run_shell_turn(tab, assistant_message, prompt_text, run_cwd)
        elif agent == "agy":
            self._run_agy_turn(tab, assistant_message, prompt_text, run_cwd, effective_mode)
        elif agent == "claude":
            self._run_claude_turn(tab, assistant_message, prompt_text, run_cwd, effective_mode)
        elif agent == "codex":
            self._run_codex_turn(tab, assistant_message, prompt_text, run_cwd, effective_mode)
        else:
            self._finish_turn_with_error(tab["id"], assistant_message, f"Unsupported agent type: {agent}")
        return result

    def interrupt(self, tab_id: str) -> bool:
        active = self._active.get(tab_id)
        if not active:
            idle = self.store.update_tab(tab_id, {"status": "idle", "statusDetail": None})
            if idle:
                self.broadcast({"type": "tab_updated", "tab": idle})
            return False

        active.interrupted = True
        active.send_signal(signal.SIGINT)
        loop = asyncio.get_running_loop()

        def escalate_term() -> None:
            active.send_signal(signal.SIGTERM)
            active.kill_handles.append(loop.call_later(2.0, lambda: active.send_signal(signal.SIGKILL)))

        active.kill_handles.append(loop.call_later(1.5, escalate_term))

        existing = self.store.get_message(tab_id, active.assistant_msg_id)
        if existing and existing.get("status") == "streaming":
            interrupted_msg = {**existing, "status": "interrupted", "updatedAt": now_ms()}
            self.store.replace_message(tab_id, interrupted_msg)
            self.broadcast({"type": "message_updated", "tabId": tab_id, "message": interrupted_msg})
        idle_tab = self.store.update_tab(tab_id, {"status": "idle", "statusDetail": None})
        if idle_tab:
            self.broadcast({"type": "tab_updated", "tab": idle_tab})
        return True

    def kill_if_running(self, tab_id: str) -> None:
        self._agy_prompt_dirty.discard(tab_id)
        active = self._active.pop(tab_id, None)
        if not active:
            return
        active.interrupted = True
        active.cancel_kill_timers()
        active.send_signal(signal.SIGKILL)

    async def wait_idle(self, tab_id: str, timeout: float = 30.0) -> None:
        """Test helper: wait until no turn is running on the tab."""
        deadline = asyncio.get_running_loop().time() + timeout
        while self.is_running(tab_id):
            if asyncio.get_running_loop().time() > deadline:
                raise TimeoutError(f"turn on {tab_id} still running after {timeout}s")
            await asyncio.sleep(0.02)

    # ---------------------------------------------------------------- helpers

    @staticmethod
    def _new_message(tab_id: str, role: str, text: str, status: str, ts: int) -> Dict[str, Any]:
        return {
            "id": f"msg_{rand_hex(6)}",
            "tabId": tab_id,
            "role": role,
            "text": text,
            "thinking": None,
            "toolCalls": [],
            "segments": [],
            "deniedActions": [],
            "status": status,
            "error": None,
            "usage": None,
            "createdAt": ts,
            "updatedAt": ts,
        }

    @staticmethod
    def _snapshot_message(msg: Dict[str, Any]) -> Dict[str, Any]:
        return {
            **msg,
            "toolCalls": list(msg.get("toolCalls") or []),
            "segments": [dict(s) for s in (msg.get("segments") or [])],
        }

    def _handle_model_command(
        self, tab: Dict[str, Any], user_message: Dict[str, Any], assistant_message: Dict[str, Any], arg: str
    ) -> Dict[str, Any]:
        updated_tab: Optional[Dict[str, Any]] = tab
        if not arg:
            current = f"`{tab['model']}`" if tab.get("model") else "CLI default"
            reply = (
                f"Current model for this tab: {current}. Use the model picker or send "
                "`/model <model-id>` (or `/model default`) to change it."
            )
        elif _MODEL_RESET_RE.fullmatch(arg):
            updated_tab = self.store.update_tab(
                tab["id"],
                {"model": None, "status": "idle", "statusDetail": None,
                 "lastMessagePreview": "Model reset to default", "unread": False},
            )
            reply = "Model reset to CLI default for this tab."
        else:
            updated_tab = self.store.update_tab(
                tab["id"],
                {"model": arg, "status": "idle", "statusDetail": None,
                 "lastMessagePreview": f"Model: {arg}", "unread": False},
            )
            reply = f"Model switched to `{arg}` for this tab. Subsequent messages will use this model."
        complete_msg = {
            **self._snapshot_message(assistant_message),
            "text": reply,
            "segments": [{"type": "text", "text": reply}],
            "status": "complete",
            "updatedAt": now_ms(),
        }
        self.store.replace_message(tab["id"], complete_msg)
        self.store.save_now()
        self.broadcast({"type": "message_created", "tabId": tab["id"], "message": user_message})
        self.broadcast({"type": "message_created", "tabId": tab["id"], "message": complete_msg})
        if updated_tab:
            self.broadcast({"type": "tab_updated", "tab": updated_tab})
        return {"userMessage": user_message, "assistantMessage": complete_msg}

    @staticmethod
    def _child_env() -> Dict[str, str]:
        return {**os.environ, "PATH": enriched_path(), "FORCE_COLOR": "0", "NO_COLOR": "1", "TERM": "dumb"}

    def _emit_delta(
        self, tab_id: str, msg: Dict[str, Any], text_delta: Optional[str] = None, thinking_delta: Optional[str] = None
    ) -> None:
        if text_delta:
            msg["text"] += text_delta
            segments = msg.setdefault("segments", [])
            if segments and segments[-1].get("type") == "text":
                segments[-1]["text"] += text_delta
            else:
                chunk = text_delta.lstrip("\r\n") if segments else text_delta
                if chunk:
                    segments.append({"type": "text", "text": chunk})
        if thinking_delta:
            msg["thinking"] = (msg["thinking"] or "") + thinking_delta
        msg["updatedAt"] = now_ms()
        self.store.replace_message(tab_id, self._snapshot_message(msg))
        env: Dict[str, Any] = {"type": "message_delta", "tabId": tab_id, "messageId": msg["id"]}
        if text_delta:
            env["textDelta"] = text_delta
        if thinking_delta:
            env["thinkingDelta"] = thinking_delta
        self.broadcast(env)

    def _upsert_tool_call(self, tab_id: str, msg: Dict[str, Any], tool_call: Dict[str, Any]) -> None:
        calls = msg["toolCalls"]
        for idx, t in enumerate(calls):
            if t["id"] == tool_call["id"]:
                calls[idx] = tool_call
                break
        else:
            calls.append(tool_call)
        segments = msg.setdefault("segments", [])
        if not any(s.get("type") == "tool" and s.get("toolCallId") == tool_call["id"] for s in segments):
            segments.append({"type": "tool", "toolCallId": tool_call["id"]})
        msg["updatedAt"] = now_ms()
        self.store.replace_message(tab_id, self._snapshot_message(msg))
        self.broadcast({"type": "tool_update", "tabId": tab_id, "messageId": msg["id"], "toolCall": tool_call})

    @staticmethod
    def _find_tool(msg: Dict[str, Any], tool_id: str) -> Optional[Dict[str, Any]]:
        return next((t for t in msg["toolCalls"] if t["id"] == tool_id), None)

    def _set_status_detail(self, tab_id: str, detail: str) -> None:
        updated = self.store.update_tab(tab_id, {"statusDetail": detail})
        if updated:
            self.broadcast({"type": "tab_updated", "tab": updated})

    def _running_status(self, tab_id: str, name: str, summary: str) -> None:
        self._set_status_detail(tab_id, f"Running {name}" + (f": {truncate(summary, 40)}" if summary else ""))

    def _finish_turn_with_error(self, tab_id: str, msg: Dict[str, Any], error_text: str) -> None:
        final_msg = {**msg, "status": "error", "error": error_text, "updatedAt": now_ms()}
        self.store.replace_message(tab_id, final_msg)
        updated_tab = self.store.update_tab(tab_id, {"status": "error", "statusDetail": None, "unread": True})
        self.broadcast({"type": "message_updated", "tabId": tab_id, "message": final_msg})
        if updated_tab:
            self.broadcast({"type": "tab_updated", "tab": updated_tab})

    def _finalize_turn(
        self,
        tab_id: str,
        msg: Dict[str, Any],
        *,
        interrupted: bool,
        exit_code: Optional[int],
        stderr_text: str,
        duration_ms: int,
        session_ref: Optional[Dict[str, Any]] = None,
        new_cwd: Optional[str] = None,
        usage: Optional[Dict[str, Any]] = None,
        denied_actions: Optional[List[Dict[str, Any]]] = None,
    ) -> None:
        active = self._active.pop(tab_id, None)
        if active:
            active.cancel_kill_timers()
        tab = self.store.get_tab(tab_id)
        if tab is None:
            return  # Tab was deleted mid-turn; don't resurrect its message list.

        denied = first_not_none(denied_actions, msg.get("deniedActions"), [])
        if usage:
            final_usage = {**usage, "durationMs": first_not_none(usage.get("durationMs"), duration_ms)}
        else:
            final_usage = {"inputTokens": None, "outputTokens": None, "thinkingTokens": None,
                           "costUsd": None, "durationMs": duration_ms}

        final_status, final_error = "complete", msg.get("error")
        tab_status, status_detail = "idle", None
        failed = bool(exit_code) and exit_code != 0
        if interrupted:
            final_status, tab_status = "interrupted", "idle"
        elif denied:
            final_status, tab_status, status_detail = "complete", "blocked", "Waiting for approval"
        elif failed and not msg["text"].strip():
            final_status = "error"
            final_error = stderr_text.strip() or f"Process exited with code {exit_code}"
            tab_status = "error"
        elif failed and stderr_text.strip():
            final_error = f"Exited with code {exit_code}: {truncate(stderr_text.strip(), 240)}"
        elif not failed and tab.get("agent") != "shell" and is_task_finished_response(msg["text"]):
            tab_status, status_detail = "done", "Task finished"

        final_msg = {
            **self._snapshot_message(msg),
            "deniedActions": denied,
            "status": final_status,
            "error": final_error,
            "usage": final_usage,
            "updatedAt": now_ms(),
        }
        self.store.replace_message(tab_id, final_msg)

        patch: Dict[str, Any] = {"status": tab_status, "statusDetail": status_detail, "unread": True}
        if session_ref:
            patch["sessionRef"] = session_ref
        if new_cwd:
            patch["cwd"] = new_cwd
        if final_msg["text"].strip():
            patch["lastMessagePreview"] = truncate(final_msg["text"], 120)
        updated_tab = self.store.update_tab(tab_id, patch)
        self.store.save_now()

        self.broadcast({"type": "message_updated", "tabId": tab_id, "message": final_msg})
        if updated_tab:
            self.broadcast({"type": "tab_updated", "tab": updated_tab})

    # ---------------------------------------------------------------- process plumbing

    def _spawn(
        self,
        tab_id: str,
        msg: Dict[str, Any],
        argv: List[str],
        cwd: str,
        label: str,
        on_stdout: Callable[[str], None],
        on_stderr: Callable[[str], None],
        on_close: Callable[[Optional[int], bool], None],
    ) -> None:
        """Registers the turn as running now, then spawns and pumps the process in a task."""
        active = _ActiveTurn(msg["id"])
        self._active[tab_id] = active

        async def run() -> None:
            try:
                proc = await asyncio.create_subprocess_exec(
                    *argv,
                    cwd=cwd,
                    env=self._child_env(),
                    stdin=asyncio.subprocess.DEVNULL,
                    stdout=asyncio.subprocess.PIPE,
                    stderr=asyncio.subprocess.PIPE,
                )
            except OSError as err:
                if self._active.get(tab_id) is active:
                    del self._active[tab_id]
                self._finish_turn_with_error(tab_id, msg, f"Failed to start {label}: {err}")
                return
            active.proc = proc
            if active.signal_on_spawn is not None:
                active.send_signal(active.signal_on_spawn)

            async def pump(stream: asyncio.StreamReader, cb: Callable[[str], None]) -> None:
                decoder = codecs.getincrementaldecoder("utf-8")("replace")
                while True:
                    chunk = await stream.read(65536)
                    if not chunk:
                        break
                    text = decoder.decode(chunk)
                    if text:
                        cb(text)
                tail = decoder.decode(b"", final=True)
                if tail:
                    cb(tail)

            assert proc.stdout is not None and proc.stderr is not None
            await asyncio.gather(pump(proc.stdout, on_stdout), pump(proc.stderr, on_stderr))
            code = await proc.wait()
            # Node reports a null exit code when the process died from a signal.
            on_close(code if code >= 0 else None, active.interrupted)

        async def guarded() -> None:
            try:
                await run()
            except asyncio.CancelledError:
                raise
            except Exception as err:  # Parser bugs must not leave the tab stuck "working".
                traceback.print_exc(file=sys.stderr)
                if self._active.get(tab_id) is active:
                    del self._active[tab_id]
                if self.store.get_tab(tab_id) is not None:
                    self._finish_turn_with_error(tab_id, msg, f"Internal daemon error while running {label}: {err}")

        task = asyncio.get_running_loop().create_task(guarded())
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)

    def _spawn_json_lines(
        self,
        tab: Dict[str, Any],
        msg: Dict[str, Any],
        argv: List[str],
        cwd: str,
        label: str,
        handle_line: Callable[[str], None],
        finish: Callable[[Optional[int], bool, str, int], None],
    ) -> None:
        start = now_ms()
        state = {"line_buf": "", "stderr": ""}

        def on_stdout(chunk: str) -> None:
            state["line_buf"] += chunk
            lines = _LINE_SPLIT_RE.split(state["line_buf"])
            state["line_buf"] = lines.pop()
            for line in lines:
                handle_line(line)

        def on_stderr(chunk: str) -> None:
            state["stderr"] += chunk

        def on_close(code: Optional[int], interrupted: bool) -> None:
            if state["line_buf"].strip():
                handle_line(state["line_buf"])
            finish(code, interrupted, state["stderr"], now_ms() - start)

        self._spawn(tab["id"], msg, argv, cwd, label, on_stdout, on_stderr, on_close)

    # ---------------------------------------------------------------- shell

    def _run_shell_turn(self, tab: Dict[str, Any], msg: Dict[str, Any], command: str, cwd: str) -> None:
        tab_id = tab["id"]
        start = now_ms()
        bash = resolve_binary("bash") or "/bin/bash"
        wrapped = f'{command}\n__ec=$?\nprintf "\\n{CWD_MARKER}%s\\n" "$PWD"\nexit $__ec'
        self._set_status_detail(tab_id, f"$ {truncate(command, 60)}")

        st: Dict[str, Any] = {"new_cwd": None, "raw": "", "stderr": "", "fence": False, "pending": ""}
        marker_len = len(CWD_MARKER)

        def append_text(chunk: str) -> None:
            if not chunk:
                return
            if not st["fence"]:
                st["fence"] = True
                self._emit_delta(tab_id, msg, text_delta="```text\n" + chunk)
            else:
                self._emit_delta(tab_id, msg, text_delta=chunk)

        def detect_cwd(candidate: str) -> None:
            candidate = candidate.strip()
            if candidate and os.path.exists(candidate):
                st["new_cwd"] = os.path.abspath(candidate)

        def on_stdout(chunk: str) -> None:
            st["pending"] += chunk
            pending = st["pending"]
            idx = pending.find(CWD_MARKER)
            if idx >= 0:
                before = pending[:idx]
                if before:
                    st["raw"] += before
                    append_text(before)
                after = pending[idx + marker_len:]
                nl = after.find("\n")
                if nl >= 0:
                    detect_cwd(after[:nl])
                    st["pending"] = after[nl + 1:]
                else:
                    st["pending"] = pending[idx:]
            elif len(pending) > marker_len + 2:
                flush_len = len(pending) - (marker_len + 2)
                safe = pending[:flush_len]
                st["pending"] = pending[flush_len:]
                st["raw"] += safe
                append_text(safe)

        def on_stderr(chunk: str) -> None:
            st["stderr"] += chunk
            append_text(chunk)

        def on_close(code: Optional[int], interrupted: bool) -> None:
            pending = st["pending"]
            if pending and CWD_MARKER not in pending:
                st["raw"] += pending
                append_text(pending)
            elif CWD_MARKER in pending:
                idx = pending.find(CWD_MARKER)
                before = pending[:idx]
                if before:
                    append_text(before)
                detect_cwd(pending[idx + marker_len:])

            if st["fence"]:
                self._emit_delta(tab_id, msg, text_delta="```" if msg["text"].endswith("\n") else "\n```")
            elif not st["raw"].strip() and not st["stderr"].strip():
                self._emit_delta(tab_id, msg, text_delta="_Command completed with no output._" if code == 0 else "")

            self._finalize_turn(
                tab_id, msg, interrupted=interrupted, exit_code=code, stderr_text=st["stderr"],
                new_cwd=st["new_cwd"], duration_ms=now_ms() - start,
            )

        self._spawn(tab_id, msg, [bash, "-lc", wrapped], cwd, "bash", on_stdout, on_stderr, on_close)

    # ---------------------------------------------------------------- agy

    def _run_agy_turn(
        self, tab: Dict[str, Any], msg: Dict[str, Any], prompt: str, cwd: str, permission_mode: str
    ) -> None:
        tab_id = tab["id"]
        binary = resolve_binary("agy")
        if not binary:
            self._finish_turn_with_error(tab_id, msg, "'agy' binary not found in PATH on this VM.")
            return

        args = ["--output-format", "stream-json"]
        if permission_mode == "full-access":
            args.append("--dangerously-skip-permissions")
        elif permission_mode == "accept-edits":
            args += ["--mode", "accept-edits"]
        elif permission_mode == "plan":
            args += ["--mode", "plan"]
        else:
            args += ["--mode", "auto"]
        session = tab.get("sessionRef")
        is_resume = isinstance(session, dict) and session.get("kind") == "agy" and bool(session.get("value"))
        if is_resume:
            args += ["--conversation", session["value"]]
        if tab.get("model"):
            args += ["--model", tab["model"]]
        system_prompt = _tab_system_prompt(tab)
        prompt_dirty = tab_id in self._agy_prompt_dirty
        self._agy_prompt_dirty.discard(tab_id)
        if system_prompt and (not is_resume or prompt_dirty):
            effective_prompt = f"<system_instructions>\n{system_prompt}\n</system_instructions>\n\n{prompt}"
        else:
            effective_prompt = prompt
        args.append(f"--print={effective_prompt}")

        self._set_status_detail(tab_id, "Antigravity is thinking…")

        st: Dict[str, Any] = {"session_ref": session, "usage": None, "saw_text": False,
                              "text_step": None, "think_step": None}
        denied: List[Dict[str, Any]] = []
        tool_starts: Dict[str, int] = {}
        breaks = _BlockBreaks()

        def step_break(kind: str, step: Dict[str, Any], existing: Optional[str]) -> str:
            """Paragraph break when text/thinking continues in a different step (e.g. after a tool)."""
            idx = step.get("step_index")
            if idx is None:
                return ""
            previous, st[kind] = st[kind], idx
            return block_separator(existing) if previous is not None and previous != idx else ""

        def text(delta: str) -> None:
            self._emit_delta(tab_id, msg, text_delta=delta)

        def think(delta: str) -> None:
            self._emit_delta(tab_id, msg, thinking_delta=delta)

        def set_session(value: Any) -> None:
            st["session_ref"] = {"kind": "agy", "value": value}

        def parse_agy_usage(raw_usage: Any, duration_seconds: Any) -> Optional[Dict[str, Any]]:
            u = as_obj(raw_usage) if js_truthy(raw_usage) else None
            if u is None:
                return None
            if is_finite_number(duration_seconds):
                dur = js_round(duration_seconds * 1000)
            elif is_number(u.get("duration_ms")):
                dur = u["duration_ms"]
            else:
                dur = None
            cost = u.get("total_cost_usd") if is_number(u.get("total_cost_usd")) else _num_or_none(u.get("cost_usd"))
            return {
                "inputTokens": _num_or_none(u.get("input_tokens")),
                "outputTokens": _num_or_none(u.get("output_tokens")),
                "thinkingTokens": _num_or_none(u.get("thinking_tokens")),
                "costUsd": cost,
                "durationMs": dur,
            }

        def handle_step(step: Dict[str, Any]) -> None:
            if isinstance(step.get("conversation_id"), str) and step["conversation_id"]:
                set_session(step["conversation_id"])
            step_type = step["step_type"] if isinstance(step.get("step_type"), str) else ""
            step_state = step["state"].upper() if isinstance(step.get("state"), str) else ""

            if step_type == "agent_response":
                if isinstance(step.get("thinking_delta"), str) and step["thinking_delta"]:
                    think(step_break("think_step", step, msg["thinking"]) + step["thinking_delta"])
                if isinstance(step.get("text_delta"), str) and step["text_delta"]:
                    st["saw_text"] = True
                    text(step_break("text_step", step, msg["text"]) + step["text_delta"])
                elif isinstance(step.get("text"), str) and step["text"] and not st["saw_text"]:
                    st["saw_text"] = True
                    text(step_break("text_step", step, msg["text"]) + step["text"])
                step_usage = parse_agy_usage(step.get("usage"), step.get("duration_seconds"))
                if step_usage:
                    st["usage"] = step_usage
                return

            if step_type in ("thinking", "thought", "reasoning"):
                think_text = ""
                for key in ("thinking_delta", "text_delta", "text"):
                    if isinstance(step.get(key), str) and step[key]:
                        think_text = step[key]
                        break
                if think_text:
                    think(step_break("think_step", step, msg["thinking"]) + think_text)
                return

            if step_type == "tool":
                tool_info = as_obj(step.get("tool_info")) or {}
                step_idx = js_str(step["step_index"]) if step.get("step_index") is not None else None
                tool_id = js_str(first_not_none(
                    step.get("tool_call_id"), step.get("tool_use_id"), tool_info.get("id"),
                    f"agy_step_{step_idx}" if step_idx else f"tool_{rand_hex(4)}",
                ))
                name = js_str(first_not_none(step.get("tool_name"), tool_info.get("name"), "Tool"))
                params = first_not_none(tool_info.get("parameters"), tool_info.get("input"), tool_info.get("args"))
                summary = summarize_tool_input(params)
                existing = self._find_tool(msg, tool_id)

                if step_state in ("ACTIVE", "RUNNING", "PENDING"):
                    tool_starts.setdefault(tool_id, now_ms())
                    self._running_status(tab_id, name, summary)
                    self._upsert_tool_call(tab_id, msg, {
                        "id": tool_id,
                        "name": name,
                        "inputSummary": summary or (existing or {}).get("inputSummary") or "",
                        "inputJson": json_pretty(params) if params is not None else (existing or {}).get("inputJson"),
                        "output": (existing or {}).get("output"),
                        "status": "running",
                        "durationMs": None,
                    })
                else:
                    raw_out = first_not_none(tool_info.get("output"), tool_info.get("result"), tool_info.get("error"),
                                             step.get("output"), step.get("error"))
                    if isinstance(raw_out, str):
                        out_text: Optional[str] = raw_out
                    elif raw_out is not None:
                        out_text = json_pretty(raw_out)
                    else:
                        out_text = None
                    is_denied = step_state == "DENIED" or js_truthy(tool_info.get("denied"))
                    is_err = not is_denied and (
                        step_state in ("ERROR", "FAILED")
                        or js_truthy(tool_info.get("is_error")) or js_truthy(tool_info.get("error"))
                    )
                    started = tool_starts.get(tool_id)
                    if is_finite_number(step.get("duration_seconds")):
                        dur: Optional[int] = js_round(step["duration_seconds"] * 1000)
                    elif started:
                        dur = now_ms() - started
                    else:
                        dur = None
                    if is_denied:
                        denied.append({"action": name, "displayName": name, "detail": summary or None})
                    self._upsert_tool_call(tab_id, msg, {
                        "id": tool_id,
                        "name": first_not_none((existing or {}).get("name"), name),
                        "inputSummary": (existing or {}).get("inputSummary") or summary,
                        "inputJson": first_not_none(
                            (existing or {}).get("inputJson"), json_pretty(params) if params is not None else None
                        ),
                        "output": out_text[:MAX_TOOL_OUTPUT] if out_text else None,
                        "status": "denied" if is_denied else "error" if is_err else "done",
                        "durationMs": dur,
                    })
                    self._set_status_detail(tab_id, "Antigravity is thinking…")

        def handle_result(res: Dict[str, Any]) -> None:
            if isinstance(res.get("conversation_id"), str) and res["conversation_id"]:
                set_session(res["conversation_id"])
            response_text = res["response"] if isinstance(res.get("response"), str) else ""
            if response_text.strip() and not msg["text"].strip():
                st["saw_text"] = True
                text(response_text)
            res_status = res["status"].upper() if isinstance(res.get("status"), str) else ""
            if isinstance(res.get("error"), str) and res["error"].strip():
                msg["error"] = res["error"].strip()
            elif res_status in ("ERROR", "FAILED") and not msg["error"]:
                msg["error"] = response_text.strip() or f"Antigravity finished with status {js_str(res.get('status'))}"
            res_usage = parse_agy_usage(res.get("usage"), res.get("duration_seconds"))
            if res_usage:
                st["usage"] = res_usage
            if isinstance(res.get("permission_denials"), list):
                for d in res["permission_denials"]:
                    if isinstance(d, dict):
                        denied.append(_denied_from(d, "permission", "Permission Required", use_alt_keys=True))

        def handle_line(line: str) -> None:
            trimmed = line.strip()
            if not trimmed:
                return
            try:
                ev = parse_json_line(trimmed)
            except ValueError:
                text(("\n" if msg["text"] else "") + trimmed)  # Non-JSON line fallback
                return
            if not isinstance(ev, dict):
                return

            for key in ("conversation_id", "conversationId", "session_id", "sessionId"):
                if isinstance(ev.get(key), str) and ev[key]:
                    set_session(ev[key])
                    break

            # Antigravity CLI native schema: {event: "init" | "step_update" | "result", ...}
            agy_event = ev["event"] if isinstance(ev.get("event"), str) else ""
            if agy_event == "init":
                init = as_obj(ev.get("init")) if js_truthy(ev.get("init")) else None
                if init and isinstance(init.get("conversation_id"), str) and init["conversation_id"]:
                    set_session(init["conversation_id"])
                return
            if agy_event == "step_update" and as_obj(ev.get("step_update")) is not None:
                handle_step(as_obj(ev["step_update"]) or {})
                return
            if agy_event == "result" and as_obj(ev.get("result")) is not None:
                handle_result(as_obj(ev["result"]) or {})
                return

            ev_type = ev["type"] if isinstance(ev.get("type"), str) else ""

            if isinstance(ev.get("delta"), str) and ev["delta"]:
                if "think" in ev_type or "reason" in ev_type:
                    think(ev["delta"])
                else:
                    st["saw_text"] = True
                    text(ev["delta"])
                return

            if ev_type == "stream_event" and as_obj(ev.get("event")) is not None:
                inner = as_obj(ev["event"]) or {}
                d = as_obj(inner.get("delta"))
                if inner.get("type") == "content_block_start":
                    breaks.start((as_obj(inner.get("content_block")) or {}).get("type"))
                elif inner.get("type") == "content_block_delta" and d is not None:
                    if d.get("type") == "text_delta" and isinstance(d.get("text"), str):
                        st["saw_text"] = True
                        if d["text"]:
                            text(breaks.take_text(msg["text"]) + d["text"])
                    elif d.get("type") == "thinking_delta" and isinstance(d.get("thinking"), str):
                        if d["thinking"]:
                            think(breaks.take_thinking(msg["thinking"]) + d["thinking"])
                return

            if ev_type == "assistant" and as_obj(ev.get("message")) is not None:
                content = (as_obj(ev["message"]) or {}).get("content")
                for block in content if isinstance(content, list) else []:
                    b = as_obj(block)
                    if b is None:
                        continue
                    if b.get("type") == "thinking" and isinstance(b.get("thinking"), str) and b["thinking"]:
                        if not (msg["thinking"] is not None and b["thinking"] in msg["thinking"]):
                            think(block_separator(msg["thinking"]) + b["thinking"])
                    elif b.get("type") == "text" and isinstance(b.get("text"), str) and b["text"]:
                        if not st["saw_text"]:
                            text(block_separator(msg["text"]) + b["text"])
                    elif b.get("type") == "tool_use":
                        self._tool_use_started(tab_id, msg, tool_starts, b.get("id"), b.get("name"), b.get("input"))
                return

            if ev_type in ("tool_use", "tool_call", "tool_start"):
                input_obj = first_not_none(ev.get("input"), ev.get("args"), ev.get("arguments"))
                self._tool_use_started(
                    tab_id, msg, tool_starts,
                    first_not_none(ev.get("id"), ev.get("tool_use_id")),
                    first_not_none(ev.get("name"), ev.get("tool")),
                    input_obj,
                )
                return

            if ev_type in ("tool_result", "tool_end", "user"):
                if ev_type == "user" and as_obj(ev.get("message")) is not None:
                    blocks = (as_obj(ev["message"]) or {}).get("content")
                else:
                    blocks = [ev]
                for raw_block in blocks if isinstance(blocks, list) else []:
                    b = as_obj(raw_block)
                    if b is None:
                        continue
                    tool_id = js_str(first_not_none(b.get("tool_use_id"), b.get("id"), ""))
                    if not tool_id:
                        continue
                    existing = self._find_tool(msg, tool_id)
                    out_text = _tool_output_text(first_not_none(b.get("content"), b.get("output"), b.get("result")))
                    is_err = js_truthy(b.get("is_error")) or js_truthy(b.get("error"))
                    started = tool_starts.get(tool_id)
                    self._upsert_tool_call(tab_id, msg, {
                        "id": tool_id,
                        "name": first_not_none((existing or {}).get("name"), js_str(first_not_none(b.get("name"), "Tool"))),
                        "inputSummary": first_not_none((existing or {}).get("inputSummary"), ""),
                        "inputJson": (existing or {}).get("inputJson"),
                        "output": out_text[:MAX_TOOL_OUTPUT] if out_text else None,
                        "status": "error" if is_err else "done",
                        "durationMs": now_ms() - started if started else None,
                    })
                return

            if ev_type == "result":
                result_val = ev.get("result")
                if isinstance(result_val, str) and result_val and not msg["text"].strip():
                    text(result_val)
                if js_truthy(ev.get("is_error")) and isinstance(result_val, str):
                    msg["error"] = result_val
                u = as_obj(ev.get("usage"))
                if u is not None:
                    st["usage"] = {
                        "inputTokens": _num_or_none(u.get("input_tokens")),
                        "outputTokens": _num_or_none(u.get("output_tokens")),
                        "thinkingTokens": _num_or_none(u.get("thinking_tokens")),
                        "costUsd": _num_or_none(ev.get("total_cost_usd")),
                        "durationMs": _num_or_none(ev.get("duration_ms")),
                    }
                if isinstance(ev.get("permission_denials"), list):
                    for d in ev["permission_denials"]:
                        if isinstance(d, dict):
                            denied.append(_denied_from(d, "permission", "Permission Required", use_alt_keys=True))
                return

            if isinstance(ev.get("text"), str) and ev["text"]:  # Generic text fallback
                st["saw_text"] = True
                text(ev["text"])

        def finish(code: Optional[int], interrupted: bool, stderr: str, duration: int) -> None:
            self._finalize_turn(
                tab_id, msg, interrupted=interrupted, exit_code=code, stderr_text=stderr,
                session_ref=st["session_ref"], usage=st["usage"], denied_actions=denied, duration_ms=duration,
            )

        self._spawn_json_lines(tab, msg, [binary, *args], cwd, "agy", handle_line, finish)

    def _tool_use_started(
        self, tab_id: str, msg: Dict[str, Any], tool_starts: Dict[str, int], raw_id: Any, raw_name: Any, input_obj: Any
    ) -> None:
        tool_id = js_str(first_not_none(raw_id, f"tool_{rand_hex(4)}"))
        name = js_str(first_not_none(raw_name, "Tool"))
        tool_starts[tool_id] = now_ms()
        summary = summarize_tool_input(input_obj)
        self._running_status(tab_id, name, summary)
        self._upsert_tool_call(tab_id, msg, {
            "id": tool_id,
            "name": name,
            "inputSummary": summary,
            "inputJson": json_pretty(input_obj) if input_obj is not None else None,
            "output": None,
            "status": "running",
            "durationMs": None,
        })

    # ---------------------------------------------------------------- claude

    def _run_claude_turn(
        self, tab: Dict[str, Any], msg: Dict[str, Any], prompt: str, cwd: str, permission_mode: str
    ) -> None:
        tab_id = tab["id"]
        binary = resolve_binary("claude")
        if not binary:
            self._finish_turn_with_error(tab_id, msg, "'claude' binary not found in PATH on this VM.")
            return

        args = ["-p", prompt, "--output-format", "stream-json", "--verbose", "--include-partial-messages"]
        if permission_mode == "full-access":
            args.append("--dangerously-skip-permissions")
        elif permission_mode == "accept-edits":
            args += ["--permission-mode", "acceptEdits"]
        elif permission_mode == "plan":
            args += ["--permission-mode", "plan"]
        else:
            args += ["--permission-mode", "default"]
        session = tab.get("sessionRef")
        if isinstance(session, dict) and session.get("kind") == "claude" and session.get("value"):
            args += ["--resume", session["value"]]
        if tab.get("model"):
            args += ["--model", tab["model"]]
        system_prompt = _tab_system_prompt(tab)
        if system_prompt:
            args += ["--append-system-prompt", system_prompt]

        self._set_status_detail(tab_id, "Claude is thinking…")

        st: Dict[str, Any] = {"session_ref": session, "usage": None, "saw_partial": False}
        denied: List[Dict[str, Any]] = []
        tool_starts: Dict[str, int] = {}
        breaks = _BlockBreaks()

        def handle_line(line: str) -> None:
            trimmed = line.strip()
            if not trimmed:
                return
            try:
                ev = parse_json_line(trimmed)
            except ValueError:
                return
            if not isinstance(ev, dict):
                return
            if isinstance(ev.get("session_id"), str) and ev["session_id"]:
                st["session_ref"] = {"kind": "claude", "value": ev["session_id"]}

            ev_type = ev.get("type")
            if ev_type == "stream_event" and as_obj(ev.get("event")) is not None:
                inner = as_obj(ev["event"]) or {}
                d = as_obj(inner.get("delta"))
                if inner.get("type") == "content_block_start":
                    breaks.start((as_obj(inner.get("content_block")) or {}).get("type"))
                elif inner.get("type") == "content_block_delta" and d is not None:
                    if d.get("type") == "text_delta" and isinstance(d.get("text"), str):
                        st["saw_partial"] = True
                        if d["text"]:
                            self._emit_delta(tab_id, msg, text_delta=breaks.take_text(msg["text"]) + d["text"])
                    elif d.get("type") == "thinking_delta" and isinstance(d.get("thinking"), str):
                        if d["thinking"]:
                            self._emit_delta(
                                tab_id, msg, thinking_delta=breaks.take_thinking(msg["thinking"]) + d["thinking"]
                            )
            elif ev_type == "assistant" and as_obj(ev.get("message")) is not None:
                content = (as_obj(ev["message"]) or {}).get("content")
                for block in content if isinstance(content, list) else []:
                    b = as_obj(block)
                    if b is None:
                        continue
                    if b.get("type") == "tool_use":
                        self._tool_use_started(tab_id, msg, tool_starts, b.get("id"), b.get("name"), b.get("input"))
                    elif b.get("type") == "text" and isinstance(b.get("text"), str) and not st["saw_partial"]:
                        if b["text"]:
                            self._emit_delta(tab_id, msg, text_delta=block_separator(msg["text"]) + b["text"])
            elif ev_type == "user" and as_obj(ev.get("message")) is not None:
                content = (as_obj(ev["message"]) or {}).get("content")
                for block in content if isinstance(content, list) else []:
                    b = as_obj(block)
                    if b is None:
                        continue
                    if b.get("type") == "tool_result" and isinstance(b.get("tool_use_id"), str):
                        tool_id = b["tool_use_id"]
                        existing = self._find_tool(msg, tool_id)
                        out_text = _tool_output_text(b.get("content"))
                        started = tool_starts.get(tool_id)
                        self._upsert_tool_call(tab_id, msg, {
                            "id": tool_id,
                            "name": first_not_none((existing or {}).get("name"), "Tool"),
                            "inputSummary": first_not_none((existing or {}).get("inputSummary"), ""),
                            "inputJson": (existing or {}).get("inputJson"),
                            "output": out_text[:MAX_TOOL_OUTPUT] if out_text else None,
                            "status": "error" if js_truthy(b.get("is_error")) else "done",
                            "durationMs": now_ms() - started if started else None,
                        })
            elif ev_type == "result":
                result_val = ev.get("result")
                if isinstance(result_val, str) and result_val and not msg["text"].strip():
                    self._emit_delta(tab_id, msg, text_delta=result_val)
                if js_truthy(ev.get("is_error")) and isinstance(result_val, str):
                    msg["error"] = result_val
                u = as_obj(ev.get("usage"))
                if u is not None:
                    st["usage"] = {
                        "inputTokens": _num_or_none(u.get("input_tokens")),
                        "outputTokens": _num_or_none(u.get("output_tokens")),
                        "thinkingTokens": None,
                        "costUsd": _num_or_none(ev.get("total_cost_usd")),
                        "durationMs": _num_or_none(ev.get("duration_ms")),
                    }
                if isinstance(ev.get("permission_denials"), list):
                    for d in ev["permission_denials"]:
                        if isinstance(d, dict):
                            denied.append(_denied_from(d, "tool", "Permission Required", use_alt_keys=False))

        def finish(code: Optional[int], interrupted: bool, stderr: str, duration: int) -> None:
            self._finalize_turn(
                tab_id, msg, interrupted=interrupted, exit_code=code, stderr_text=stderr,
                session_ref=st["session_ref"], usage=st["usage"], denied_actions=denied, duration_ms=duration,
            )

        self._spawn_json_lines(tab, msg, [binary, *args], cwd, "claude", handle_line, finish)

    # ---------------------------------------------------------------- codex

    def _run_codex_turn(
        self, tab: Dict[str, Any], msg: Dict[str, Any], prompt: str, cwd: str, permission_mode: str
    ) -> None:
        tab_id = tab["id"]
        binary = resolve_binary("codex")
        if not binary:
            self._finish_turn_with_error(tab_id, msg, "'codex' binary not found in PATH on this VM.")
            return

        session = tab.get("sessionRef")
        is_resume = isinstance(session, dict) and session.get("kind") == "codex" and bool(session.get("value"))
        args = (["exec", "resume", "--json", "--skip-git-repo-check"] if is_resume
                else ["exec", "--json", "--skip-git-repo-check", "-C", cwd])
        if permission_mode == "full-access":
            args.append("--dangerously-bypass-approvals-and-sandbox")
        elif not is_resume:
            args += ["-s", "read-only"] if permission_mode == "plan" else ["-s", "workspace-write"]
        if tab.get("model"):
            args += ["-m", tab["model"]]
        system_prompt = _tab_system_prompt(tab)
        if system_prompt:
            args += ["-c", f"developer_instructions={_toml_basic_string(system_prompt)}"]
        if is_resume:
            args += [session["value"], prompt]
        else:
            args.append(prompt)

        self._set_status_detail(tab_id, "Codex is working…")

        st: Dict[str, Any] = {"session_ref": session, "usage": None}
        tool_starts: Dict[str, int] = {}

        def handle_line(line: str) -> None:
            trimmed = line.strip()
            if not trimmed:
                return
            try:
                ev = parse_json_line(trimmed)
            except ValueError:
                return
            if not isinstance(ev, dict):
                return
            ev_type = ev.get("type")
            if ev_type == "thread.started" and isinstance(ev.get("thread_id"), str):
                st["session_ref"] = {"kind": "codex", "value": ev["thread_id"]}
            elif ev_type in ("item.started", "item.updated", "item.completed") and as_obj(ev.get("item")) is not None:
                item = as_obj(ev["item"]) or {}
                item_id = js_str(first_not_none(item.get("id"), f"item_{rand_hex(4)}"))
                item_type = js_str(first_not_none(item.get("type"), ""))
                completed = ev_type == "item.completed"
                if item_type == "reasoning" and isinstance(item.get("text"), str) and completed:
                    self._emit_delta(tab_id, msg, thinking_delta=("\n" if msg["thinking"] else "") + item["text"])
                elif item_type == "agent_message" and isinstance(item.get("text"), str) and completed:
                    self._emit_delta(tab_id, msg, text_delta=("\n\n" if msg["text"] else "") + item["text"])
                elif item_type == "command_execution":
                    cmd = js_str(first_not_none(item.get("command"), "command"))
                    cmd_summary = summarize_tool_input({"command": cmd})
                    if ev_type == "item.started":
                        tool_starts[item_id] = now_ms()
                        self._set_status_detail(tab_id, f"$ {truncate(cmd_summary, 50)}")
                    started = tool_starts.get(item_id)
                    exit_code = item.get("exit_code") if is_number(item.get("exit_code")) else None
                    agg = item.get("aggregated_output")
                    self._upsert_tool_call(tab_id, msg, {
                        "id": item_id,
                        "name": "Bash",
                        "inputSummary": cmd_summary,
                        "inputJson": json_pretty({"command": cmd}),
                        "output": agg[:MAX_TOOL_OUTPUT] if isinstance(agg, str) else None,
                        "status": ("error" if exit_code and exit_code != 0 else "done") if completed else "running",
                        "durationMs": now_ms() - started if completed and started else None,
                    })
            elif ev_type == "turn.completed" and as_obj(ev.get("usage")) is not None:
                u = as_obj(ev["usage"]) or {}
                st["usage"] = {
                    "inputTokens": _num_or_none(u.get("input_tokens")),
                    "outputTokens": _num_or_none(u.get("output_tokens")),
                    "thinkingTokens": None,
                    "costUsd": None,
                    "durationMs": None,
                }
            elif ev_type == "error" and isinstance(ev.get("message"), str):
                msg["error"] = ev["message"]

        def finish(code: Optional[int], interrupted: bool, stderr: str, duration: int) -> None:
            self._finalize_turn(
                tab_id, msg, interrupted=interrupted, exit_code=code, stderr_text=stderr,
                session_ref=st["session_ref"], usage=st["usage"], duration_ms=duration,
            )

        self._spawn_json_lines(tab, msg, [binary, *args], cwd, "codex", handle_line, finish)
