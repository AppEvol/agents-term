"""AgentsTerm VM daemon: REST + WebSocket API for the Android app, plus the app state broker."""

from __future__ import annotations

import asyncio
import json
import os
import re
import secrets
import signal
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Set
from urllib.parse import unquote

from . import httpserver
from .app_state import DEFAULT_COMMAND_TIMEOUT_MS, AppStateBroker, AppStateError
from .config import SERVER_VERSION, build_server_info, daemon_commit, home_dir
from .httpserver import Request, Response, WebSocket
from .models import get_agent_models, warm_agent_models_cache
from .runner import AgentRunner
from .store import MAX_OPEN_TABS, StateStore
from .util import now_ms

PING_INTERVAL_S = 25.0
_AGENT_MODELS_RE = re.compile(r"^/api/agents/([^/]+)/models$")
_TAB_RE = re.compile(r"^/api/tabs/([^/]+)(?:/(messages|interrupt|read|queue)(?:/([^/]+))?)?$")
_DUMP_SEGMENT_RE = re.compile(r"[^A-Za-z0-9._-]+")


# ------------------------------------------------------------------ debug dumps & fs (blocking; run in threads)


def debug_dumps_root() -> Path:
    return home_dir() / ".config" / "herdr-mobile" / "debug-dumps"


def _sanitize_dump_segment(raw: Any) -> str:
    cleaned = _DUMP_SEGMENT_RE.sub("_", (raw if isinstance(raw, str) else "").strip()).strip("_")[:64]
    return cleaned or secrets.token_hex(4)


def _utc_stamp(epoch_ms: int) -> str:
    return datetime.fromtimestamp(epoch_ms / 1000, tz=timezone.utc).strftime("%Y%m%d-%H%M%SZ")


def _iso(epoch_ms: int) -> str:
    dt = datetime.fromtimestamp(epoch_ms / 1000, tz=timezone.utc)
    return dt.strftime("%Y-%m-%dT%H:%M:%S.") + f"{epoch_ms % 1000:03d}Z"


def _nonblank(value: Any) -> bool:
    return isinstance(value, str) and value.strip() != ""


def save_debug_dump(payload: Dict[str, Any]) -> Dict[str, Any]:
    import base64
    import binascii
    import time

    root = debug_dumps_root()
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    created = payload.get("createdAt")
    created_at = (
        int(created)
        if isinstance(created, (int, float))
        and not isinstance(created, bool)
        and created == created
        and abs(created) != float("inf")
        else int(time.time() * 1000)
    )
    safe_id = _sanitize_dump_segment(payload.get("dumpId"))
    dir_name = f"{_utc_stamp(created_at)}_{safe_id}"
    remote_dir = root / dir_name
    remote_dir.mkdir(parents=True, exist_ok=True, mode=0o700)

    files: List[str] = []

    def write(name: str, content: bytes) -> None:
        fd = os.open(remote_dir / name, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "wb") as f:
            f.write(content)
        files.append(name)

    def write_text(name: str, content: str) -> None:
        write(name, content.encode("utf-8"))

    events_ndjson = payload.get("eventsNdjson") if isinstance(payload.get("eventsNdjson"), str) else ""
    event_lines = [ln for ln in re.split(r"\r?\n", events_ndjson) if ln.strip()]
    meta = {
        "dumpId": safe_id,
        "dirName": dir_name,
        "createdAt": created_at,
        "createdAtIso": _iso(created_at),
        "note": payload.get("note"),
        "appVersion": payload.get("appVersion") or "unknown",
        "deviceInfo": payload.get("deviceInfo") or "unknown",
        "connectionSummary": payload.get("connectionSummary") or "",
        "eventCount": len(event_lines),
        "hasLogcat": _nonblank(payload.get("logcat")),
        "hasLastCrash": _nonblank(payload.get("lastCrash")),
        "hasScreenshot": _nonblank(payload.get("screenshotPngBase64")),
    }
    write_text("meta.json", json.dumps(meta, indent=2, ensure_ascii=False) + "\n")

    state_json = payload.get("workspaceStateJson")
    if _nonblank(state_json):
        try:
            formatted = json.dumps(json.loads(state_json), indent=2, ensure_ascii=False) + "\n"
        except ValueError:
            formatted = state_json  # keep raw if not valid JSON
        write_text("state.json", formatted)

    write_text("events.ndjson", events_ndjson.rstrip() + "\n")
    if _nonblank(payload.get("logcat")):
        write_text("logcat.txt", payload["logcat"])
    if _nonblank(payload.get("lastCrash")):
        write_text("crash.txt", payload["lastCrash"])
    if _nonblank(payload.get("screenshotPngBase64")):
        try:
            png = base64.b64decode(payload["screenshotPngBase64"].strip())
            if png:
                write("screenshot.png", png)
        except (binascii.Error, ValueError):
            pass  # ignore malformed screenshot payload

    yes_no = lambda flag, f: f"yes (`{f}`)" if flag else "no"  # noqa: E731
    summary = [
        f"# AgentsTerm Android Debug Dump ({safe_id})",
        "",
        f"- **Captured (UTC):** {meta['createdAtIso']}",
        f"- **App Version:** {meta['appVersion']}",
        f"- **Device:** {meta['deviceInfo']}",
        f"- **Connection:** {meta['connectionSummary'] or 'n/a'}",
        f"- **Buffered Events:** {meta['eventCount']}",
        f"- **Crash Trace Included:** {yes_no(meta['hasLastCrash'], 'crash.txt')}",
        f"- **Screenshot Included:** {yes_no(meta['hasScreenshot'], 'screenshot.png')}",
        f"- **Logcat Included:** {yes_no(meta['hasLogcat'], 'logcat.txt')}",
        "",
    ]
    if _nonblank(meta["note"]):
        summary += ["## User Note", "", meta["note"].strip(), ""]
    summary += ["## Files in This Dump", *[f"- `{f}`" for f in files], "- `summary.md`", ""]
    write_text("summary.md", "\n".join(summary))

    latest = root / "latest"
    try:
        if latest.is_symlink() or latest.exists():
            latest.unlink()
        latest.symlink_to(remote_dir, target_is_directory=True)
    except OSError:
        pass  # non-fatal

    return {"ok": True, "dumpId": safe_id, "remoteDir": str(remote_dir), "latestSymlink": str(latest), "files": files}


def list_debug_dumps() -> Dict[str, Any]:
    root = debug_dumps_root()
    if not root.exists():
        return {"root": str(root), "dumps": []}
    dumps = []
    for entry in root.iterdir():
        if entry.name == "latest" or not entry.is_dir() or entry.is_symlink():
            continue
        try:
            files = sorted(p.name for p in entry.iterdir())
        except OSError:
            files = []
        dumps.append({"dirName": entry.name, "remoteDir": str(entry), "files": files})
    dumps.sort(key=lambda d: d["dirName"], reverse=True)
    return {"root": str(root), "dumps": dumps[:25]}


def list_directories(requested: Optional[str]) -> Dict[str, Any]:
    target = os.path.abspath(requested.strip()) if requested and requested.strip() else str(home_dir())
    parent = None if os.path.dirname(target) == target else os.path.dirname(target)
    directories = []
    try:
        for entry in os.scandir(target):
            if entry.name.startswith("."):
                continue
            try:
                is_dir = entry.is_dir(follow_symlinks=True)
            except OSError:
                is_dir = False
            if is_dir:
                directories.append({"name": entry.name, "path": os.path.join(target, entry.name)})
        # localeCompare-like: case-insensitive first, then case.
        directories.sort(key=lambda d: (d["name"].casefold(), d["name"]))
    except OSError:
        pass  # empty listing if unreadable
    # `exists` lets the app tell a deleted folder from an empty one (new-tab folder fallback).
    return {"path": target, "parent": parent, "directories": directories, "exists": os.path.isdir(target)}


# ------------------------------------------------------------------ daemon


class Daemon:
    def __init__(self, host: str, port: int, token: str, no_auth: bool = False) -> None:
        self.host = host
        self.port = port
        self.token = token
        self.no_auth = no_auth
        self.store = StateStore()
        self.sockets: Set[WebSocket] = set()
        self.app_state = AppStateBroker()
        self.runner = AgentRunner(self.store, self.broadcast)
        self._server: Optional[asyncio.AbstractServer] = None
        self._ping_task: Optional[asyncio.Task] = None

    # -- infra --

    def broadcast(self, envelope: Dict[str, Any]) -> None:
        raw = json.dumps(envelope, ensure_ascii=False, separators=(",", ":"))
        for ws in list(self.sockets):
            if ws.ready_state == httpserver.OPEN:
                try:
                    ws.send(raw)
                except httpserver.WebSocketClosed:
                    pass

    def _authorized(self, req: Request) -> bool:
        if self.no_auth or not self.token:
            return True
        if req.headers.get("authorization") == f"Bearer {self.token}":
            return True
        return req.path == "/ws" and req.query("token") == self.token

    async def start(self) -> None:
        warm_agent_models_cache()
        commit = daemon_commit()  # resolve now: it must describe the code this process loaded
        self._server = await asyncio.start_server(
            lambda r, w: httpserver.serve_connection(r, w, self.handle, self.handle_upgrade),
            self.host,
            self.port,
            limit=httpserver.MAX_HEADER_BYTES,
        )
        self._ping_task = asyncio.get_running_loop().create_task(self._ping_loop())
        auth = "disabled" if self.no_auth else "bearer"
        print(
            f"AgentsTerm daemon v{SERVER_VERSION} (python, commit {(commit or 'unknown')[:12]}) "
            f"listening on http://{self.host}:{self.port} (auth={auth})",
            flush=True,
        )

    async def _ping_loop(self) -> None:
        while True:
            await asyncio.sleep(PING_INTERVAL_S)
            for ws in list(self.sockets):
                ws.ping()

    async def shutdown(self) -> None:
        self.store.save_now()
        for ws in list(self.sockets):
            ws.close(1001, "Server shutting down")
            await ws.drain()
        if self._ping_task:
            self._ping_task.cancel()
        if self._server:
            self._server.close()

    # -- websocket --

    async def handle_upgrade(self, req: Request, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        if req.path != "/ws":
            writer.write(b"HTTP/1.1 404 Not Found\r\n\r\n")
            return
        if not self._authorized(req):
            writer.write(b"HTTP/1.1 401 Unauthorized\r\n\r\n")
            return
        response = httpserver.handshake_response(req)
        if response is None:
            writer.write(b"HTTP/1.1 400 Bad Request\r\n\r\n")
            return
        writer.write(response)
        ws = WebSocket(reader, writer)
        self.sockets.add(ws)
        self.app_state.add_socket(ws)
        try:
            listing = self.store.list_tabs()
            ws.send(
                json.dumps(
                    {
                        "type": "snapshot",
                        "server": build_server_info(self.host),
                        "tabs": listing["tabs"],
                        "activeTabId": listing["activeTabId"],
                        "ts": now_ms(),
                    },
                    ensure_ascii=False,
                    separators=(",", ":"),
                )
            )
            async for text in ws.messages():
                self.app_state.handle_message(ws, text)
        finally:
            self.sockets.discard(ws)
            self.app_state.remove_socket(ws)

    # -- REST --

    async def handle(self, req: Request) -> Response:
        try:
            if not self._authorized(req):
                return Response.json(401, {"error": "Invalid bearer token"})
            return await self._route(req)
        except httpserver.HttpError:
            raise
        except Exception as err:  # noqa: BLE001 - mirror the TS catch-all
            return Response.json(500, {"error": str(err) or "Internal server error"})

    async def _route(self, req: Request) -> Response:
        method, path = req.method, req.path

        if method == "GET" and path == "/api/info":
            return Response.json(200, build_server_info(self.host))

        m = _AGENT_MODELS_RE.match(path)
        if method == "GET" and m:
            refresh = req.query("refresh")
            return Response.json(200, await get_agent_models(unquote(m.group(1)), refresh in ("1", "true")))

        if method == "GET" and path == "/api/fs":
            return Response.json(200, await asyncio.to_thread(list_directories, req.query("path")))

        if path == "/api/debug/dumps":
            if method == "GET":
                return Response.json(200, await asyncio.to_thread(list_debug_dumps))
            if method == "POST":
                body = await req.json(max_bytes=10 * 1024 * 1024)
                return Response.json(
                    200, await asyncio.to_thread(save_debug_dump, body if isinstance(body, dict) else {})
                )
            return Response.json(405, {"error": "Method not allowed"})

        if path == "/api/app/status":
            if method != "GET":
                return Response.json(405, {"error": "Method not allowed"})
            return Response.json(200, self.app_state.status())

        if path == "/api/app/command":
            if method != "POST":
                return Response.json(405, {"error": "Method not allowed"})
            body = await req.json()
            body = body if isinstance(body, dict) else {}
            command = body.get("command").strip() if isinstance(body.get("command"), str) else ""
            args = body.get("args") if isinstance(body.get("args"), dict) else {}
            timeout = (
                body.get("timeoutMs")
                if isinstance(body.get("timeoutMs"), (int, float)) and not isinstance(body.get("timeoutMs"), bool)
                else DEFAULT_COMMAND_TIMEOUT_MS
            )
            try:
                result = await self.app_state.request(command, args, timeout)
            except AppStateError as err:
                return Response.json(err.http_status, {"ok": False, "code": err.code, "error": str(err)})
            return Response.json(200, {"ok": True, **result})

        if method == "GET" and path == "/api/tabs":
            return Response.json(200, self.store.list_tabs())

        if method == "POST" and path == "/api/tabs":
            body = await req.json()
            body = body if isinstance(body, dict) else {}
            agent = body.get("agent")
            if not agent or not isinstance(agent, str):
                return Response.json(400, {"error": "Missing required field: agent"})
            tab = self.store.create_tab(
                agent,
                title=body.get("title"),
                cwd=body.get("cwd"),
                permission_mode=body.get("permissionMode"),
                model=body.get("model"),
                system_prompt=body.get("systemPrompt"),
            )
            if tab is None:
                return Response.json(
                    409, {"error": f"The limit is {MAX_OPEN_TABS} open tabs. Close a tab to make room."}
                )
            self.broadcast({"type": "tab_created", "tab": tab})
            return Response.json(200, {"tab": tab})

        m = _TAB_RE.match(path)
        if m:
            item_id = unquote(m.group(3)) if m.group(3) else None
            return await self._route_tab(req, unquote(m.group(1)), m.group(2) or "", item_id)

        return Response.json(404, {"error": f"Unknown endpoint {method} {path}"})

    async def _route_tab(self, req: Request, tab_id: str, sub: str, item_id: Optional[str] = None) -> Response:
        method = req.method
        if item_id is not None and sub != "queue":
            return Response.json(404, {"error": f"Unknown endpoint {method} {req.path}"})
        if not sub:
            if method == "PATCH":
                body = await req.json()
                body = body if isinstance(body, dict) else {}
                existing = self.store.get_tab(tab_id)
                if not existing:
                    return Response.json(404, {"error": "Tab not found"})
                patch: Dict[str, Any] = {}
                if body.get("title") is not None:
                    patch["title"] = body["title"]
                if body.get("cwd") is not None:
                    patch["cwd"] = os.path.abspath(body["cwd"])
                if body.get("permissionMode") is not None:
                    patch["permissionMode"] = body["permissionMode"]
                if "model" in body:
                    model = body["model"]
                    patch["model"] = model.strip() if isinstance(model, str) and model.strip() else None
                system_prompt_changed = False
                if "systemPrompt" in body:
                    sp = body["systemPrompt"]
                    new_sp = (
                        None
                        if existing.get("agent") == "shell"
                        else (sp.strip() if isinstance(sp, str) and sp.strip() else None)
                    )
                    patch["systemPrompt"] = new_sp
                    system_prompt_changed = new_sp != existing.get("systemPrompt")
                if body.get("status") in ("done", "idle") and not self.runner.is_running(tab_id):
                    if existing.get("agent") != "shell":
                        new_status = body["status"]
                        patch["status"] = new_status
                        patch["statusDetail"] = "Task finished" if new_status == "done" else None
                updated = self.store.update_tab(tab_id, patch)
                if updated:
                    if system_prompt_changed:
                        self.runner.mark_system_prompt_updated(tab_id)
                    self.broadcast({"type": "tab_updated", "tab": updated})
                    return Response.json(200, {"tab": updated})
                return Response.json(404, {"error": "Tab not found"})
            if method == "DELETE":
                self.runner.kill_if_running(tab_id)
                self.store.delete_tab(tab_id)
                self.broadcast({"type": "tab_deleted", "tabId": tab_id})
                return Response.json(200, {"ok": True})
            return Response.json(405, {"error": "Method not allowed"})

        if sub == "messages":
            tab = self.store.get_tab(tab_id)
            if not tab:
                return Response.json(404, {"error": "Tab not found"})
            if method == "GET":
                return Response.json(200, {"tab": tab, "messages": self.store.get_messages(tab_id)})
            if method == "POST":
                body = await req.json()
                body = body if isinstance(body, dict) else {}
                text = body["text"].strip() if isinstance(body.get("text"), str) else ""
                if not text:
                    return Response.json(400, {"error": "Message text cannot be empty"})
                override = body.get("overridePermissionMode")
                result = self.runner.submit_message(tab, text, override if isinstance(override, str) else None)
                return Response.json(200, result)
            return Response.json(405, {"error": "Method not allowed"})

        if sub == "queue":
            tab = self.store.get_tab(tab_id)
            if not tab:
                return Response.json(404, {"error": "Tab not found"})
            if item_id and method == "DELETE":
                updated = self.runner.remove_queued_message(tab_id, item_id)
                return Response.json(200, {"ok": True, "tab": updated})
            return Response.json(405, {"error": "Method not allowed"})

        if sub == "interrupt" and method == "POST":
            body = await req.json()
            body = body if isinstance(body, dict) else {}
            process_queue = bool(body.get("processQueue"))
            text = body["text"].strip() if isinstance(body.get("text"), str) and body["text"].strip() else None
            override = (
                body.get("overridePermissionMode") if isinstance(body.get("overridePermissionMode"), str) else None
            )
            self.runner.interrupt(
                tab_id,
                process_queue=process_queue,
                text=text,
                override_permission_mode=override,
            )
            return Response.json(200, {"ok": True, "tab": self.store.get_tab(tab_id)})

        if sub == "read" and method == "POST":
            updated = self.store.update_tab(tab_id, {"unread": False})
            if updated:
                self.broadcast({"type": "tab_updated", "tab": updated})
            return Response.json(200, {"ok": True})

        return Response.json(404, {"error": f"Unknown endpoint {method} {req.path}"})


async def run_daemon(host: str, port: int, token: str, no_auth: bool = False) -> None:
    daemon = Daemon(host, port, token, no_auth)
    await daemon.start()
    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, stop.set)
        except (NotImplementedError, RuntimeError):
            pass
    await stop.wait()
    try:
        await asyncio.wait_for(daemon.shutdown(), timeout=3)
    except asyncio.TimeoutError:
        pass
    print("AgentsTerm daemon stopped", file=sys.stderr, flush=True)
