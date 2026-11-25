"""App state broker: relays inspection commands from REST callers to the Android app.

    CLI --HTTP--> POST /api/app/command --> broker --WS {type:"app_state_request"}--> app
    CLI <--HTTP-- result <-- broker <--WS {type:"app_state_response"}-- app

The app announces itself (and whether the user enabled remote inspection) with an
{type:"app_hello"} frame after every connect and whenever the setting changes.

Commands that drive the app (tap, swipe, type...) need a second opt-in on the phone. The app names
them in its hello ("controlCommands") and says whether they are allowed ("controlEnabled"); the app
enforces this itself, the broker only refuses early with a clearer error.
"""

from __future__ import annotations

import asyncio
import json
import re
import uuid
from typing import Any, Dict, List, Optional, Protocol

from .util import now_ms

WS_OPEN = 1
DEFAULT_COMMAND_TIMEOUT_MS = 15_000
MAX_COMMAND_TIMEOUT_MS = 120_000
_COMMAND_NAME_RE = re.compile(r"^[a-z][a-z0-9_.-]{0,63}$")

_STATUS_BY_CODE = {
    "no_app_connected": 503,
    "unsupported_app": 501,
    "inspection_disabled": 403,
    "control_disabled": 403,
    "app_disconnected": 503,
    "timeout": 504,
    "app_error": 502,
    "bad_request": 400,
}


class BrokerSocket(Protocol):
    ready_state: int

    def send(self, data: str) -> None: ...


class AppStateError(Exception):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.http_status = _STATUS_BY_CODE[code]


def _str_or_none(value: Any) -> Optional[str]:
    return value.strip() if isinstance(value, str) and value.strip() else None


def _parse_hello(raw: Any) -> Dict[str, Any]:
    obj = raw if isinstance(raw, dict) else {}
    commands = obj.get("commands")
    control_commands = obj.get("controlCommands")
    return {
        "enabled": obj.get("enabled") is True,
        "appVersion": _str_or_none(obj.get("appVersion")),
        "device": _str_or_none(obj.get("device")),
        "commands": [c for c in commands if isinstance(c, str)] if isinstance(commands, list) else [],
        # Absent from app builds that cannot be controlled.
        "controlEnabled": obj.get("controlEnabled") is True,
        "controlCommands": [c for c in control_commands if isinstance(c, str)]
        if isinstance(control_commands, list)
        else [],
    }


class _Client:
    def __init__(self, seq: int, socket: BrokerSocket) -> None:
        self.seq = seq
        self.client_id = f"c{seq}"
        self.socket = socket
        self.connected_at = now_ms()
        self.hello: Optional[Dict[str, Any]] = None
        self.hello_at: Optional[int] = None

    @property
    def is_open(self) -> bool:
        return self.socket.ready_state == WS_OPEN

    def summary(self) -> Dict[str, Any]:
        hello = self.hello or {}
        return {
            "clientId": self.client_id,
            "connectedAt": self.connected_at,
            "helloAt": self.hello_at,
            "enabled": hello.get("enabled") is True,
            "supportsReporter": self.hello is not None,
            "appVersion": hello.get("appVersion"),
            "device": hello.get("device"),
            "commands": hello.get("commands", []),
            "supportsControl": bool(hello.get("controlCommands")),
            "controlEnabled": hello.get("enabled") is True and hello.get("controlEnabled") is True,
        }


class _Pending:
    def __init__(
        self, client_id: str, command: str, future: asyncio.Future[Dict[str, Any]], timer: asyncio.TimerHandle
    ) -> None:
        self.client_id = client_id
        self.command = command
        self.started_at = now_ms()
        self.future = future
        self.timer = timer


class AppStateBroker:
    def __init__(self) -> None:
        self._clients: Dict[int, _Client] = {}  # keyed by id(socket)
        self._pending: Dict[str, _Pending] = {}
        self._next_seq = 1

    def add_socket(self, socket: BrokerSocket) -> None:
        if id(socket) in self._clients:
            return
        self._clients[id(socket)] = _Client(self._next_seq, socket)
        self._next_seq += 1

    def remove_socket(self, socket: BrokerSocket) -> None:
        client = self._clients.pop(id(socket), None)
        if not client:
            return
        for request_id, req in list(self._pending.items()):
            if req.client_id == client.client_id:
                del self._pending[request_id]
                req.timer.cancel()
                self._fail(req, AppStateError("app_disconnected", f"App disconnected before answering '{req.command}'"))

    def handle_message(self, socket: BrokerSocket, raw: str) -> bool:
        """Returns True if the frame was an app-state frame (consumed here)."""
        try:
            msg = json.loads(raw)
        except ValueError:
            return False
        if not isinstance(msg, dict):
            return False
        client = self._clients.get(id(socket))
        if not client:
            return False

        if msg.get("type") == "app_hello":
            client.hello = _parse_hello(msg.get("reporter"))
            client.hello_at = now_ms()
            return True

        if msg.get("type") == "app_state_response":
            request_id = msg.get("requestId") if isinstance(msg.get("requestId"), str) else ""
            req = self._pending.get(request_id)
            # Ignore unknown/late responses and responses from a client other than the one asked.
            if not req or req.client_id != client.client_id:
                return True
            del self._pending[request_id]
            req.timer.cancel()
            if msg.get("ok") is True:
                if not req.future.done():
                    req.future.set_result(
                        {
                            "clientId": client.client_id,
                            "command": req.command,
                            "elapsedMs": now_ms() - req.started_at,
                            "result": msg.get("result"),
                        }
                    )
            else:
                err = _str_or_none(msg.get("error")) or "App reported an unspecified error"
                self._fail(req, AppStateError("app_error", err))
            return True

        return False

    def status(self) -> Dict[str, Any]:
        clients = sorted((c for c in self._clients.values() if c.is_open), key=lambda c: -c.seq)
        target = self._pick_target()
        if target:
            version = (target.hello or {}).get("appVersion") or "unknown version"
            reason = f"Ready: app {version} is connected with remote inspection enabled"
            summary = target.summary()
            if summary["controlEnabled"]:
                reason += " and remote control enabled"
            elif summary["supportsControl"]:
                reason += (
                    "; remote control is off (to tap/swipe/type, enable 'Allow VM agents to control the app' "
                    "in Settings > Debug sharing)"
                )
            else:
                reason += "; this app build cannot be remote controlled"
        elif not clients:
            reason = "No app is connected to the daemon. Open AgentsTerm on the phone and connect to this VM."
        elif not any(c.hello is not None for c in clients):
            reason = "The connected app build does not support app state reporting. Install a newer AgentsTerm build."
        else:
            reason = (
                "The app is connected but remote inspection is off. On the phone: Settings > Debug sharing > "
                "enable debug sharing and 'Allow VM agents to inspect the app'."
            )
        return {
            "ready": target is not None,
            "controlReady": bool(target and target.summary()["controlEnabled"]),
            "reason": reason,
            "target": target.summary() if target else None,
            "clients": [c.summary() for c in clients],
        }

    async def request(
        self, command: str, args: Optional[Dict[str, Any]] = None, timeout_ms: Any = DEFAULT_COMMAND_TIMEOUT_MS
    ) -> Dict[str, Any]:
        if not isinstance(command, str) or not _COMMAND_NAME_RE.match(command):
            raise AppStateError("bad_request", f"Invalid command name: '{command}'")
        target = self._pick_target()
        if not target:
            st = self.status()
            if not st["clients"]:
                code = "no_app_connected"
            elif any(c["supportsReporter"] for c in st["clients"]):
                code = "inspection_disabled"
            else:
                code = "unsupported_app"
            raise AppStateError(code, st["reason"])

        hello = target.hello or {}
        if command in hello.get("controlCommands", []) and not hello.get("controlEnabled"):
            raise AppStateError(
                "control_disabled",
                f"'{command}' controls the app, and remote control is off. On the phone: Settings > Debug sharing > "
                "'Allow VM agents to control the app'.",
            )

        if not isinstance(timeout_ms, (int, float)) or isinstance(timeout_ms, bool) or timeout_ms != timeout_ms:
            timeout_ms = DEFAULT_COMMAND_TIMEOUT_MS
        bounded = min(max(float(timeout_ms), 100.0), float(MAX_COMMAND_TIMEOUT_MS))
        bounded_label = int(bounded) if bounded == int(bounded) else bounded
        request_id = str(uuid.uuid4())
        loop = asyncio.get_running_loop()
        future: asyncio.Future[Dict[str, Any]] = loop.create_future()

        def on_timeout() -> None:
            req = self._pending.pop(request_id, None)
            if req:
                self._fail(
                    req,
                    AppStateError(
                        "timeout",
                        f"App did not answer '{command}' within {bounded_label}ms (is the app frozen or backgrounded?)",
                    ),
                )

        timer = loop.call_later(bounded / 1000.0, on_timeout)
        self._pending[request_id] = _Pending(target.client_id, command, future, timer)
        try:
            target.socket.send(
                json.dumps(
                    {"type": "app_state_request", "requestId": request_id, "command": command, "args": args or {}},
                    ensure_ascii=False,
                    separators=(",", ":"),
                )
            )
        except Exception as err:  # Any send failure means the socket is gone
            self._pending.pop(request_id, None)
            timer.cancel()
            raise AppStateError("app_disconnected", f"Failed to send request to app: {err}") from err
        return await future

    @staticmethod
    def _fail(req: _Pending, err: AppStateError) -> None:
        if not req.future.done():
            req.future.set_exception(err)

    def _pick_target(self) -> Optional[_Client]:
        best: Optional[_Client] = None
        for c in self._clients.values():
            if not c.is_open or not (c.hello or {}).get("enabled"):
                continue
            if best is None or c.seq > best.seq:
                best = c
        return best

    def pending_count(self) -> int:
        return len(self._pending)

    def clients(self) -> List[Dict[str, Any]]:
        return [c.summary() for c in self._clients.values()]
