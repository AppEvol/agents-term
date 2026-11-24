"""Minimal asyncio HTTP/1.1 server with RFC 6455 WebSocket support (standard library only).

Covers what the AgentsTerm app (OkHttp) and the CLI need: keep-alive, Content-Length and chunked
request bodies, JSON responses, and WebSocket text/ping/pong/close frames with fragmentation.
"""

from __future__ import annotations

import asyncio
import base64
import hashlib
import json
import os
import struct
from typing import Any, Awaitable, Callable, Dict, List, Optional, Tuple
from urllib.parse import parse_qs, urlsplit

MAX_HEADER_BYTES = 64 * 1024
MAX_WS_MESSAGE_BYTES = 100 * 1024 * 1024  # same default as the `ws` npm package
WS_GUID = b"258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

REASONS = {
    101: "Switching Protocols",
    200: "OK",
    400: "Bad Request",
    401: "Unauthorized",
    403: "Forbidden",
    404: "Not Found",
    405: "Method Not Allowed",
    409: "Conflict",
    411: "Length Required",
    413: "Payload Too Large",
    500: "Internal Server Error",
    501: "Not Implemented",
    502: "Bad Gateway",
    503: "Service Unavailable",
    504: "Gateway Timeout",
}


class HttpError(Exception):
    def __init__(self, status: int, message: str) -> None:
        super().__init__(message)
        self.status = status


class BodyTooLarge(Exception):
    pass


class Request:
    def __init__(
        self, method: str, target: str, version: str, headers: Dict[str, str], reader: asyncio.StreamReader
    ) -> None:
        self.method = method.upper()
        self.target = target
        self.version = version
        self.headers = headers  # lower-cased names
        split = urlsplit(target)
        self.path = split.path or "/"
        self._query = parse_qs(split.query, keep_blank_values=True)
        self._reader = reader
        self._body: Optional[bytes] = None
        self.body_consumed = False

    def query(self, name: str) -> Optional[str]:
        values = self._query.get(name)
        return values[0] if values else None

    @property
    def keep_alive(self) -> bool:
        conn = self.headers.get("connection", "").lower()
        if self.version == "HTTP/1.0":
            return "keep-alive" in conn
        return "close" not in conn

    async def body(self, max_bytes: int) -> bytes:
        if self._body is not None:
            return self._body
        self.body_consumed = True
        te = self.headers.get("transfer-encoding", "").lower()
        if "chunked" in te:
            chunks: List[bytes] = []
            total = 0
            while True:
                size_line = await self._reader.readuntil(b"\r\n")
                size = int(size_line.split(b";", 1)[0].strip() or b"0", 16)
                if size == 0:
                    # Trailers until blank line.
                    while (await self._reader.readuntil(b"\r\n")) != b"\r\n":
                        pass
                    break
                total += size
                if total > max_bytes:
                    raise BodyTooLarge()
                chunks.append(await self._reader.readexactly(size))
                await self._reader.readexactly(2)
            self._body = b"".join(chunks)
        else:
            length = int(self.headers.get("content-length", "0") or "0")
            if length > max_bytes:
                raise BodyTooLarge()
            self._body = await self._reader.readexactly(length) if length else b""
        return self._body

    async def json(self, max_bytes: int = 2 * 1024 * 1024) -> Any:
        """readJsonBody(): empty body -> {}."""
        try:
            raw = await self.body(max_bytes)
        except BodyTooLarge:
            raise HttpError(500, "Request body too large")
        text = raw.decode("utf-8").strip()
        if not text:
            return {}
        return json.loads(text)

    @property
    def declared_length(self) -> int:
        if "chunked" in self.headers.get("transfer-encoding", "").lower():
            return -1
        try:
            return int(self.headers.get("content-length", "0") or "0")
        except ValueError:
            return -1


class Response:
    def __init__(self, status: int, body: bytes = b"", content_type: str = "application/json; charset=utf-8") -> None:
        self.status = status
        self.body = body
        self.content_type = content_type

    @classmethod
    def json(cls, status: int, payload: Any) -> Response:
        return cls(status, json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))


async def read_request(reader: asyncio.StreamReader) -> Optional[Request]:
    try:
        head = await reader.readuntil(b"\r\n\r\n")
    except asyncio.IncompleteReadError as e:
        if not e.partial.strip():
            return None  # clean EOF between requests
        raise HttpError(400, "Incomplete request head")
    except asyncio.LimitOverrunError:
        raise HttpError(400, "Request head too large")
    lines = head.decode("latin-1").split("\r\n")
    try:
        method, target, version = lines[0].split(" ", 2)
    except ValueError:
        raise HttpError(400, "Malformed request line")
    headers: Dict[str, str] = {}
    for line in lines[1:]:
        if not line:
            continue
        name, _, value = line.partition(":")
        key = name.strip().lower()
        value = value.strip()
        headers[key] = f"{headers[key]}, {value}" if key in headers else value
    return Request(method, target, version.strip(), headers, reader)


def encode_response(resp: Response, keep_alive: bool) -> bytes:
    head = [
        f"HTTP/1.1 {resp.status} {REASONS.get(resp.status, 'Status')}",
        f"Content-Type: {resp.content_type}",
        f"Content-Length: {len(resp.body)}",
        f"Connection: {'keep-alive' if keep_alive else 'close'}",
    ]
    return ("\r\n".join(head) + "\r\n\r\n").encode("latin-1") + resp.body


# ------------------------------------------------------------------ WebSocket

OP_CONT, OP_TEXT, OP_BINARY, OP_CLOSE, OP_PING, OP_PONG = 0x0, 0x1, 0x2, 0x8, 0x9, 0xA

CONNECTING, OPEN, CLOSING, CLOSED = 0, 1, 2, 3


def websocket_accept_key(client_key: str) -> str:
    return base64.b64encode(hashlib.sha1(client_key.encode("latin-1") + WS_GUID).digest()).decode("ascii")


def _apply_mask(data: bytes, mask: bytes) -> bytes:
    if not data:
        return data
    n = len(data)
    key = (mask * (n // 4 + 1))[:n]
    return (int.from_bytes(data, "little") ^ int.from_bytes(key, "little")).to_bytes(n, "little")


def encode_frame(opcode: int, payload: bytes, fin: bool = True, mask: bool = False) -> bytes:
    first = (0x80 if fin else 0) | opcode
    n = len(payload)
    mask_bit = 0x80 if mask else 0
    if n < 126:
        header = struct.pack("!BB", first, mask_bit | n)
    elif n < 1 << 16:
        header = struct.pack("!BBH", first, mask_bit | 126, n)
    else:
        header = struct.pack("!BBQ", first, mask_bit | 127, n)
    if mask:
        key = os.urandom(4)
        return header + key + _apply_mask(payload, key)
    return header + payload


class WebSocketClosed(Exception):
    pass


class WebSocket:
    """Server- or client-side WebSocket over asyncio streams (client side masks its frames)."""

    def __init__(
        self,
        reader: asyncio.StreamReader,
        writer: asyncio.StreamWriter,
        *,
        client: bool = False,
        max_message: int = MAX_WS_MESSAGE_BYTES,
    ) -> None:
        self.reader = reader
        self.writer = writer
        self.client = client
        self.max_message = max_message
        self.ready_state = OPEN
        self.close_code: Optional[int] = None
        self.close_reason = ""

    # -- sending (synchronous: frames are buffered on the transport) --

    def _write(self, data: bytes) -> None:
        if self.writer.is_closing():
            raise WebSocketClosed("transport closed")
        self.writer.write(data)

    def send(self, text: str) -> None:
        if self.ready_state != OPEN:
            raise WebSocketClosed("socket not open")
        self._write(encode_frame(OP_TEXT, text.encode("utf-8"), mask=self.client))

    def ping(self, payload: bytes = b"") -> None:
        if self.ready_state == OPEN:
            try:
                self._write(encode_frame(OP_PING, payload, mask=self.client))
            except WebSocketClosed:
                pass

    def close(self, code: int = 1000, reason: str = "") -> None:
        if self.ready_state in (CLOSING, CLOSED):
            return
        self.ready_state = CLOSING
        payload = struct.pack("!H", code) + reason.encode("utf-8")[:123]
        try:
            self._write(encode_frame(OP_CLOSE, payload, mask=self.client))
        except WebSocketClosed:
            self._finish()

    def _finish(self) -> None:
        self.ready_state = CLOSED
        try:
            self.writer.close()
        except Exception:  # noqa: BLE001
            pass

    async def drain(self) -> None:
        try:
            await self.writer.drain()
        except (ConnectionError, RuntimeError):
            pass

    # -- receiving --

    async def _read_frame(self) -> Tuple[bool, int, bytes]:
        b1, b2 = await self.reader.readexactly(2)
        fin = bool(b1 & 0x80)
        opcode = b1 & 0x0F
        masked = bool(b2 & 0x80)
        length = b2 & 0x7F
        if length == 126:
            (length,) = struct.unpack("!H", await self.reader.readexactly(2))
        elif length == 127:
            (length,) = struct.unpack("!Q", await self.reader.readexactly(8))
        if length > self.max_message:
            raise _ProtocolError(1009, "Message too big")
        key = await self.reader.readexactly(4) if masked else b""
        payload = await self.reader.readexactly(length) if length else b""
        if masked:
            payload = _apply_mask(payload, key)
        return fin, opcode, payload

    async def messages(self):
        """Async iterator over complete text messages; handles control frames internally."""
        fragments: List[bytes] = []
        frag_opcode: Optional[int] = None
        size = 0
        try:
            while True:
                fin, opcode, payload = await self._read_frame()
                if opcode in (OP_CLOSE, OP_PING, OP_PONG):
                    if not fin or len(payload) > 125:
                        raise _ProtocolError(1002, "Invalid control frame")
                    if opcode == OP_PING:
                        if self.ready_state == OPEN:
                            try:
                                self._write(encode_frame(OP_PONG, payload, mask=self.client))
                            except WebSocketClosed:
                                pass
                        continue
                    if opcode == OP_PONG:
                        continue
                    # Close frame: echo it (if we didn't start the close) and finish.
                    self.close_code = struct.unpack("!H", payload[:2])[0] if len(payload) >= 2 else 1005
                    self.close_reason = payload[2:].decode("utf-8", "replace")
                    if self.ready_state == OPEN:
                        self.ready_state = CLOSING
                        try:
                            echo = struct.pack("!H", self.close_code) if self.close_code != 1005 else b""
                            self._write(encode_frame(OP_CLOSE, echo, mask=self.client))
                        except WebSocketClosed:
                            pass
                    return
                if opcode == OP_CONT:
                    if frag_opcode is None:
                        raise _ProtocolError(1002, "Unexpected continuation frame")
                elif opcode in (OP_TEXT, OP_BINARY):
                    if frag_opcode is not None:
                        raise _ProtocolError(1002, "Expected continuation frame")
                    frag_opcode = opcode
                else:
                    raise _ProtocolError(1002, f"Unknown opcode {opcode}")
                size += len(payload)
                if size > self.max_message:
                    raise _ProtocolError(1009, "Message too big")
                fragments.append(payload)
                if fin:
                    data = b"".join(fragments)
                    op = frag_opcode
                    fragments, frag_opcode, size = [], None, 0
                    if op == OP_TEXT:
                        try:
                            yield data.decode("utf-8")
                        except UnicodeDecodeError:
                            raise _ProtocolError(1007, "Invalid UTF-8")
                    # Binary messages are ignored (the protocol is JSON text only).
        except _ProtocolError as err:
            self.close(err.code, err.reason)
        except (asyncio.IncompleteReadError, ConnectionError):
            pass
        finally:
            self._finish()


class _ProtocolError(Exception):
    def __init__(self, code: int, reason: str) -> None:
        super().__init__(reason)
        self.code = code
        self.reason = reason


def handshake_response(req: Request) -> Optional[bytes]:
    """Returns the 101 response bytes, or None if this is not a valid WebSocket upgrade."""
    if "websocket" not in req.headers.get("upgrade", "").lower():
        return None
    key = req.headers.get("sec-websocket-key")
    if not key or req.method != "GET":
        return None
    return (
        "HTTP/1.1 101 Switching Protocols\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        f"Sec-WebSocket-Accept: {websocket_accept_key(key)}\r\n\r\n"
    ).encode("latin-1")


async def connect_websocket(host: str, port: int, path: str, headers: Optional[Dict[str, str]] = None) -> WebSocket:
    """Client-side helper (used by tests and tools)."""
    reader, writer = await asyncio.open_connection(host, port, limit=MAX_HEADER_BYTES)
    key = base64.b64encode(os.urandom(16)).decode("ascii")
    lines = [
        f"GET {path} HTTP/1.1",
        f"Host: {host}:{port}",
        "Upgrade: websocket",
        "Connection: Upgrade",
        f"Sec-WebSocket-Key: {key}",
        "Sec-WebSocket-Version: 13",
    ]
    lines += [f"{k}: {v}" for k, v in (headers or {}).items()]
    writer.write(("\r\n".join(lines) + "\r\n\r\n").encode("latin-1"))
    head = await reader.readuntil(b"\r\n\r\n")
    status_line = head.split(b"\r\n", 1)[0].decode("latin-1")
    if " 101 " not in status_line + " ":
        writer.close()
        raise ConnectionError(f"WebSocket upgrade failed: {status_line}")
    if websocket_accept_key(key).encode() not in head:
        writer.close()
        raise ConnectionError("WebSocket upgrade failed: bad Sec-WebSocket-Accept")
    return WebSocket(reader, writer, client=True)


Handler = Callable[[Request], Awaitable[Response]]
UpgradeHandler = Callable[[Request, asyncio.StreamReader, asyncio.StreamWriter], Awaitable[None]]


async def serve_connection(
    reader: asyncio.StreamReader, writer: asyncio.StreamWriter, handler: Handler, upgrade_handler: UpgradeHandler
) -> None:
    """Serves requests on one connection until it closes or is upgraded to a WebSocket."""
    try:
        while True:
            try:
                req = await read_request(reader)
            except HttpError as err:
                writer.write(encode_response(Response.json(err.status, {"error": str(err)}), keep_alive=False))
                await writer.drain()
                return
            if req is None:
                return
            if req.headers.get("upgrade"):
                await upgrade_handler(req, reader, writer)
                return  # upgrade handler owns the connection
            keep_alive = req.keep_alive
            try:
                resp = await handler(req)
            except HttpError as err:
                resp = Response.json(err.status, {"error": str(err)})
                keep_alive = keep_alive and req.body_consumed
            # If the handler ignored the body, drain it so the next request parses cleanly.
            if not req.body_consumed:
                length = req.declared_length
                if length < 0 or length > 10 * 1024 * 1024:
                    keep_alive = False
                elif length:
                    await reader.readexactly(length)
            writer.write(encode_response(resp, keep_alive))
            await writer.drain()
            if not keep_alive:
                return
    except (asyncio.IncompleteReadError, ConnectionError):
        return
    finally:
        if not writer.is_closing():
            try:
                writer.close()
            except Exception:  # noqa: BLE001
                pass
