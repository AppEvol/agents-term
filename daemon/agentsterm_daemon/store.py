"""Persistent tabs + messages (~/.config/herdr-mobile/state.json), format-compatible with v0.1."""

from __future__ import annotations

import asyncio
import json
import os
import sys
from typing import Any, Dict, List, Optional

from .config import ensure_config_dir, home_dir, state_path
from .util import now_ms, rand_hex

SAVE_DEBOUNCE_S = 0.25

_AGENT_LABELS = {"agy": "Antigravity", "claude": "Claude", "codex": "Codex", "shell": "Shell"}


def default_agent_label(agent: str) -> str:
    return _AGENT_LABELS.get(agent, agent)


def _strip_or_none(value: Any) -> Optional[str]:
    return value.strip() if isinstance(value, str) and value.strip() else None


def _normalize_queued_messages(raw: Any) -> List[Dict[str, Any]]:
    if not isinstance(raw, list):
        return []
    out: List[Dict[str, Any]] = []
    for item in raw:
        if not isinstance(item, dict):
            continue
        qid = _strip_or_none(item.get("id"))
        text = _strip_or_none(item.get("text"))
        if not qid or not text:
            continue
        created = item.get("createdAt")
        out.append(
            {
                "id": qid,
                "text": text,
                "overridePermissionMode": _strip_or_none(item.get("overridePermissionMode")),
                "createdAt": int(created)
                if isinstance(created, (int, float)) and not isinstance(created, bool)
                else now_ms(),
            }
        )
    return out


class StateStore:
    def __init__(self) -> None:
        self._tabs: List[Dict[str, Any]] = []
        self._active_tab_id: Optional[str] = None
        self._messages_by_tab: Dict[str, List[Dict[str, Any]]] = {}
        self._save_handle: Optional[asyncio.TimerHandle] = None
        self._load()

    # ---- persistence ----

    def _load(self) -> None:
        path = state_path()
        if not path.exists():
            return
        try:
            raw = json.loads(path.read_text(encoding="utf-8"))
            if not isinstance(raw, dict):
                raise ValueError("state.json is not an object")
            now = now_ms()
            tabs = raw.get("tabs")
            self._tabs = [
                {
                    **t,
                    "queuedMessages": _normalize_queued_messages(t.get("queuedMessages")),
                    **(
                        {"status": "idle", "statusDetail": None, "updatedAt": now}
                        if t.get("status") == "working"
                        else {}
                    ),
                }
                for t in (tabs if isinstance(tabs, list) else [])
                if isinstance(t, dict)
            ]
            active = raw.get("activeTabId")
            if isinstance(active, str) and any(t.get("id") == active for t in self._tabs):
                self._active_tab_id = active
            else:
                self._active_tab_id = self._tabs[0]["id"] if self._tabs else None
            msgs_by_tab = raw.get("messagesByTab")
            if isinstance(msgs_by_tab, dict):
                for tab_id, msgs in msgs_by_tab.items():
                    if not isinstance(msgs, list):
                        continue
                    self._messages_by_tab[tab_id] = [
                        {**m, "status": "interrupted", "updatedAt": now} if m.get("status") == "streaming" else m
                        for m in msgs
                        if isinstance(m, dict)
                    ]
        except (OSError, ValueError, AttributeError, KeyError, TypeError):
            self._tabs = []
            self._active_tab_id = None
            self._messages_by_tab = {}

    def schedule_save(self) -> None:
        if self._save_handle is not None:
            return
        try:
            loop = asyncio.get_running_loop()
        except RuntimeError:
            self.save_now()  # No loop (sync callers/tests): save immediately.
            return

        def fire() -> None:
            self._save_handle = None
            self.save_now()

        self._save_handle = loop.call_later(SAVE_DEBOUNCE_S, fire)

    def save_now(self) -> None:
        if self._save_handle is not None:
            self._save_handle.cancel()
            self._save_handle = None
        try:
            ensure_config_dir()
            obj = {
                "tabs": self._tabs,
                "activeTabId": self._active_tab_id,
                "messagesByTab": self._messages_by_tab,
            }
            path = state_path()
            tmp = path.with_name(path.name + ".tmp")
            fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                f.write(json.dumps(obj, indent=2, ensure_ascii=False))
            os.replace(tmp, path)
        except (OSError, TypeError, ValueError) as err:
            print(f"Failed to save state: {err}", file=sys.stderr)

    # ---- tabs ----

    def list_tabs(self) -> Dict[str, Any]:
        active = self._active_tab_id or (self._tabs[0]["id"] if self._tabs else None)
        return {"tabs": list(self._tabs), "activeTabId": active}

    def get_tab(self, tab_id: str) -> Optional[Dict[str, Any]]:
        return next((t for t in self._tabs if t.get("id") == tab_id), None)

    def create_tab(
        self,
        agent: str,
        title: Any = None,
        cwd: Any = None,
        permission_mode: Any = None,
        model: Any = None,
        system_prompt: Any = None,
    ) -> Dict[str, Any]:
        now = now_ms()
        tab_id = f"tab_{rand_hex(6)}"
        raw_cwd = _strip_or_none(cwd) or str(home_dir())
        resolved_cwd = os.path.abspath(raw_cwd)
        folder = os.path.basename(resolved_cwd) or resolved_cwd
        tab = {
            "id": tab_id,
            "title": _strip_or_none(title) or f"{default_agent_label(agent)} · {folder}",
            "agent": agent,
            "cwd": resolved_cwd,
            "permissionMode": _strip_or_none(permission_mode) or "full-access",
            "model": _strip_or_none(model),
            "systemPrompt": None if agent == "shell" else _strip_or_none(system_prompt),
            "status": "idle",
            "statusDetail": None,
            "sessionRef": None,
            "unread": False,
            "lastMessagePreview": None,
            "queuedMessages": [],
            "createdAt": now,
            "updatedAt": now,
        }
        self._tabs.append(tab)
        self._messages_by_tab[tab_id] = []
        self._active_tab_id = tab_id
        self.schedule_save()
        return tab

    def update_tab(self, tab_id: str, patch: Dict[str, Any]) -> Optional[Dict[str, Any]]:
        for idx, tab in enumerate(self._tabs):
            if tab.get("id") == tab_id:
                updated = {**tab, **patch, "id": tab_id, "updatedAt": now_ms()}
                self._tabs[idx] = updated
                self.schedule_save()
                return updated
        return None

    def enqueue_message(
        self, tab_id: str, text: str, override_permission_mode: Optional[str] = None
    ) -> Optional[tuple[Dict[str, Any], Dict[str, Any]]]:
        tab = self.get_tab(tab_id)
        if not tab:
            return None
        item = {
            "id": f"q_{rand_hex(6)}",
            "text": text,
            "overridePermissionMode": _strip_or_none(override_permission_mode),
            "createdAt": now_ms(),
        }
        queued = [*list(tab.get("queuedMessages") or []), item]
        updated = self.update_tab(tab_id, {"queuedMessages": queued})
        return (updated, item) if updated is not None else None

    def remove_queued_message(self, tab_id: str, queued_id: str) -> Optional[Dict[str, Any]]:
        tab = self.get_tab(tab_id)
        if not tab:
            return None
        queued = [m for m in (tab.get("queuedMessages") or []) if m.get("id") != queued_id]
        return self.update_tab(tab_id, {"queuedMessages": queued})

    def pop_queued_messages(self, tab_id: str) -> Optional[tuple[Dict[str, Any], List[Dict[str, Any]]]]:
        tab = self.get_tab(tab_id)
        if not tab:
            return None
        popped = list(tab.get("queuedMessages") or [])
        if not popped:
            return tab, []
        updated = self.update_tab(tab_id, {"queuedMessages": []})
        return (updated, popped) if updated is not None else None

    def delete_tab(self, tab_id: str) -> bool:
        before = len(self._tabs)
        self._tabs = [t for t in self._tabs if t.get("id") != tab_id]
        self._messages_by_tab.pop(tab_id, None)
        if self._active_tab_id == tab_id:
            self._active_tab_id = self._tabs[-1]["id"] if self._tabs else None
        if len(self._tabs) != before:
            self.schedule_save()
            return True
        return False

    # ---- messages ----

    def get_messages(self, tab_id: str) -> List[Dict[str, Any]]:
        return list(self._messages_by_tab.get(tab_id, []))

    def add_message(self, tab_id: str, msg: Dict[str, Any]) -> None:
        self._messages_by_tab.setdefault(tab_id, []).append(msg)
        self.schedule_save()

    def replace_message(self, tab_id: str, updated: Dict[str, Any]) -> None:
        msgs = self._messages_by_tab.setdefault(tab_id, [])
        for idx, m in enumerate(msgs):
            if m.get("id") == updated.get("id"):
                msgs[idx] = updated
                break
        else:
            msgs.append(updated)
        self.schedule_save()

    def get_message(self, tab_id: str, msg_id: str) -> Optional[Dict[str, Any]]:
        return next((m for m in self._messages_by_tab.get(tab_id, []) if m.get("id") == msg_id), None)
