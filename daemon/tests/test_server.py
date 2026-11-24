"""Broker unit tests + end-to-end tests against a real daemon subprocess."""

import asyncio
import base64
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from helpers import DAEMON_DIR, FIXTURES_DIR, DaemonProcess, run_async

from agentsterm_daemon.app_state import AppStateBroker, AppStateError
from agentsterm_daemon.httpserver import connect_websocket

REPO_DIR = DAEMON_DIR.parent
CLI = REPO_DIR / "app_state_cli" / "app_state_cli.py"
# The original daemon's node_modules has the `ws` package; used to cross-check our WebSocket server.
NODE_WS_DIRS = [Path.home() / "agent-terminal" / "server", Path.home() / "projects" / "agent-terminal-app-state" / "server"]


class FakeSocket:
    def __init__(self):
        self.ready_state = 1
        self.sent = []

    def send(self, data):
        self.sent.append(json.loads(data))


def hello(enabled):
    return json.dumps({"type": "app_hello", "reporter": {"enabled": enabled, "appVersion": "1.2.3",
                                                         "device": "Pixel", "commands": ["ping"]}})


class BrokerTest(unittest.TestCase):
    def test_not_ready_reasons(self):
        async def go():
            b = AppStateBroker()
            with self.assertRaises(AppStateError) as e:
                await b.request("ping")
            self.assertEqual(e.exception.code, "no_app_connected")
            ws = FakeSocket()
            b.add_socket(ws)
            with self.assertRaises(AppStateError) as e:
                await b.request("ping")
            self.assertEqual(e.exception.code, "unsupported_app")
            self.assertTrue(b.handle_message(ws, hello(False)))
            with self.assertRaises(AppStateError) as e:
                await b.request("ping")
            self.assertEqual((e.exception.code, e.exception.http_status), ("inspection_disabled", 403))
            b.handle_message(ws, hello(True))
            st = b.status()
            self.assertTrue(st["ready"])
            self.assertEqual((st["target"]["appVersion"], st["target"]["device"]), ("1.2.3", "Pixel"))
        run_async(go())

    def test_relay_error_timeout_disconnect_and_routing(self):
        async def go():
            b = AppStateBroker()
            older, newer, disabled = FakeSocket(), FakeSocket(), FakeSocket()
            for s, en in ((older, True), (newer, True), (disabled, False)):
                b.add_socket(s)
                b.handle_message(s, hello(en))

            task = asyncio.ensure_future(b.request("logs", {"limit": 5}))
            await asyncio.sleep(0)
            self.assertEqual((len(older.sent), len(newer.sent), len(disabled.sent)), (0, 1, 0))
            req = newer.sent[-1]
            self.assertEqual((req["type"], req["command"], req["args"]), ("app_state_request", "logs", {"limit": 5}))
            # Spoofed answer from another socket is ignored.
            b.handle_message(older, json.dumps({"type": "app_state_response", "requestId": req["requestId"],
                                                "ok": True, "result": "spoof"}))
            b.handle_message(newer, json.dumps({"type": "app_state_response", "requestId": req["requestId"],
                                                "ok": True, "result": {"events": []}}))
            self.assertEqual((await task)["result"], {"events": []})

            task = asyncio.ensure_future(b.request("screenshot"))
            await asyncio.sleep(0)
            b.handle_message(newer, json.dumps({"type": "app_state_response", "requestId": newer.sent[-1]["requestId"],
                                                "ok": False, "error": "in background"}))
            with self.assertRaises(AppStateError) as e:
                await task
            self.assertEqual((e.exception.code, str(e.exception)), ("app_error", "in background"))

            with self.assertRaises(AppStateError) as e:
                await b.request("state", {}, 120)
            self.assertEqual(e.exception.code, "timeout")

            task = asyncio.ensure_future(b.request("state"))
            await asyncio.sleep(0)
            b.remove_socket(newer)
            with self.assertRaises(AppStateError) as e:
                await task
            self.assertEqual(e.exception.code, "app_disconnected")
            self.assertEqual(b.pending_count(), 0)

            with self.assertRaises(AppStateError) as e:
                await b.request("Bad Name!")
            self.assertEqual(e.exception.code, "bad_request")

            # Closed-but-not-yet-removed sockets are skipped.
            task = asyncio.ensure_future(b.request("ping", {}, 100))
            await asyncio.sleep(0)
            self.assertEqual(len(older.sent), 1)
            with self.assertRaises(AppStateError):
                await task
            self.assertFalse(b.handle_message(older, "not json"))
            self.assertFalse(b.handle_message(older, json.dumps({"type": "other"})))
        run_async(go())


class DaemonE2ETest(unittest.TestCase):
    def setUp(self):
        self.home = Path(tempfile.mkdtemp(prefix="agentsterm-e2e-"))
        self.daemon = DaemonProcess(self.home)

    def tearDown(self):
        self.daemon.stop()
        shutil.rmtree(self.home, ignore_errors=True)

    def ws(self, path="/ws", token=None):
        return connect_websocket("127.0.0.1", self.daemon.port, path,
                                 {"Authorization": f"Bearer {token or self.daemon.token}"})

    def test_rest_auth_tabs_shell_turn_and_ws_events(self):
        d = self.daemon
        self.assertEqual(d.api("GET", "/api/tabs", token=None)[0], 401)
        self.assertEqual(d.api("GET", "/api/tabs", token="wrong")[0], 401)
        status, info = d.api("GET", "/api/info")
        self.assertEqual((status, info["version"]), (200, "0.2.0"))
        self.assertIn("docs/live-debugging.md", info["defaultSystemPrompt"])
        self.assertNotIn("close this tab", info["defaultSystemPrompt"].lower())
        self.assertIn("close this tab", info["defaultSystemPromptFullAccess"].lower())
        self.assertEqual({a["type"] for a in info["agents"]}, {"agy", "claude", "codex", "shell"})
        self.assertEqual(d.api("POST", "/api/tabs", {})[1], {"error": "Missing required field: agent"})

        async def go():
            ws = await self.ws()
            msgs = ws.messages()
            snapshot = json.loads(await msgs.__anext__())
            self.assertEqual(snapshot["type"], "snapshot")
            self.assertEqual(snapshot["tabs"], [])

            status, body = await asyncio.to_thread(d.api, "POST", "/api/tabs", {"agent": "shell", "cwd": str(self.home)})
            self.assertEqual(status, 200)
            tab = body["tab"]
            self.assertEqual(tab["title"], f"Shell · {self.home.name}")
            self.assertEqual(json.loads(await msgs.__anext__())["type"], "tab_created")

            status, body = await asyncio.to_thread(d.api, "POST", f"/api/tabs/{tab['id']}/messages", {"text": "echo hi"})
            self.assertEqual(status, 200)
            self.assertEqual(body["assistantMessage"]["status"], "streaming")
            final = None
            types = []
            while final is None:
                env = json.loads(await msgs.__anext__())
                types.append(env["type"])
                if env["type"] == "message_updated" and env["message"]["role"] == "assistant":
                    final = env["message"]
            self.assertIn("message_delta", types)
            self.assertEqual(final["status"], "complete")
            self.assertIn("hi", final["text"])
            ws.close()
            return tab

        tab = run_async(go())
        status, body = d.api("GET", f"/api/tabs/{tab['id']}/messages")
        self.assertEqual([m["role"] for m in body["messages"]], ["user", "assistant"])
        self.assertEqual(d.api("PATCH", f"/api/tabs/{tab['id']}", {"title": "Renamed", "model": "  "})[1]["tab"]["title"],
                         "Renamed")
        self.assertEqual(d.api("POST", f"/api/tabs/{tab['id']}/messages", {"text": "  "})[0], 400)
        self.assertEqual(d.api("GET", f"/api/tabs/{tab['id']}/interrupt")[0], 404)
        self.assertEqual(d.api("POST", f"/api/tabs/{tab['id']}/read")[1], {"ok": True})
        self.assertEqual(d.api("DELETE", f"/api/tabs/{tab['id']}")[1], {"ok": True})
        self.assertEqual(d.api("GET", f"/api/tabs/{tab['id']}/messages")[0], 404)
        self.assertEqual(d.api("GET", "/api/nope")[1], {"error": "Unknown endpoint GET /api/nope"})

    def test_tab_system_prompt_create_patch_and_clear_via_rest(self):
        d = self.daemon
        status, body = d.api("POST", "/api/tabs", {
            "agent": "claude",
            "cwd": str(self.home),
            "systemPrompt": "  Stay concise for mobile.  ",
        })
        self.assertEqual(status, 200)
        tab = body["tab"]
        self.assertEqual(tab["systemPrompt"], "Stay concise for mobile.")

        # Title-only PATCH preserves systemPrompt
        status, body = d.api("PATCH", f"/api/tabs/{tab['id']}", {"title": "Mobile Claude"})
        self.assertEqual(status, 200)
        self.assertEqual(body["tab"]["title"], "Mobile Claude")
        self.assertEqual(body["tab"]["systemPrompt"], "Stay concise for mobile.")

        # Explicit PATCH updates systemPrompt; empty string clears it
        status, body = d.api("PATCH", f"/api/tabs/{tab['id']}", {"systemPrompt": "Custom prompt"})
        self.assertEqual((status, body["tab"]["systemPrompt"]), (200, "Custom prompt"))
        status, body = d.api("PATCH", f"/api/tabs/{tab['id']}", {"systemPrompt": ""})
        self.assertEqual((status, body["tab"]["systemPrompt"]), (200, None))

        # Creating a tab with empty systemPrompt or shell tab leaves it None
        status, body = d.api("POST", "/api/tabs", {"agent": "agy", "systemPrompt": ""})
        self.assertEqual((status, body["tab"]["systemPrompt"]), (200, None))
        status, body = d.api("POST", "/api/tabs", {"agent": "shell", "systemPrompt": "ignored"})
        self.assertEqual((status, body["tab"]["systemPrompt"]), (200, None))

    def test_claude_turn_streams_separated_text_blocks_end_to_end(self):
        """Real captured claude stream through a real daemon: WS deltas == stored message text."""
        self.daemon.stop()
        bin_dir = self.home / "bin"
        bin_dir.mkdir()
        fake = bin_dir / "claude"
        fake.write_text(f"#!/usr/bin/env python3\nimport sys\n"
                        f"sys.stdout.write(open({str(FIXTURES_DIR / 'claude_tool_turn.ndjson')!r}).read())\n")
        fake.chmod(0o755)
        self.daemon = DaemonProcess(self.home, extra_path=bin_dir)
        d = self.daemon

        async def go():
            ws = await self.ws()
            msgs = ws.messages()
            await msgs.__anext__()  # snapshot
            _, body = await asyncio.to_thread(d.api, "POST", "/api/tabs", {"agent": "claude", "cwd": str(self.home)})
            tab = body["tab"]
            status, _ = await asyncio.to_thread(d.api, "POST", f"/api/tabs/{tab['id']}/messages", {"text": "go"})
            self.assertEqual(status, 200)
            streamed, tools, final = "", [], None
            while final is None:
                env = json.loads(await msgs.__anext__())
                if env["type"] == "message_delta":
                    streamed += env.get("textDelta", "")
                elif env["type"] == "tool_update":
                    tools.append((env["toolCall"]["name"], env["toolCall"]["status"]))
                elif env["type"] == "message_updated" and env["message"]["role"] == "assistant":
                    final = env["message"]
            ws.close()
            return tab, streamed, tools, final

        tab, streamed, tools, final = run_async(go())
        self.assertEqual(final["text"], "Starting now.\n\nAll done.")
        self.assertEqual(streamed, final["text"])
        self.assertEqual(tools, [("Bash", "running"), ("Bash", "done")])
        stored = d.api("GET", f"/api/tabs/{tab['id']}/messages")[1]["messages"][-1]
        self.assertEqual(stored["text"], final["text"])
        self.assertEqual(stored["usage"]["outputTokens"], 85)
        self.assertEqual(stored["toolCalls"][0]["inputSummary"], "Print hi")
        self.assertEqual(stored["segments"], [
            {"type": "text", "text": "Starting now."},
            {"type": "tool", "toolCallId": "toolu_fixture_1"},
            {"type": "text", "text": "All done."},
        ])

    def test_ws_rejects_bad_auth_and_path_and_accepts_query_token(self):
        async def go():
            with self.assertRaises(ConnectionError):
                await self.ws(token="wrong")
            with self.assertRaises(ConnectionError):
                await connect_websocket("127.0.0.1", self.daemon.port, "/other",
                                        {"Authorization": f"Bearer {self.daemon.token}"})
            ws = await connect_websocket("127.0.0.1", self.daemon.port, f"/ws?token={self.daemon.token}")
            self.assertEqual(json.loads(await ws.messages().__anext__())["type"], "snapshot")
            ws.close()
        run_async(go())

    def test_fs_listing_and_debug_dump_upload(self):
        (self.home / "Beta").mkdir()
        (self.home / "alpha").mkdir()
        (self.home / ".hidden").mkdir()
        listing = self.daemon.api("GET", f"/api/fs?path={self.home}")[1]
        self.assertEqual([e["name"] for e in listing["directories"]][:2], ["alpha", "Beta"])
        self.assertNotIn(".hidden", [e["name"] for e in listing["directories"]])
        self.assertIs(listing["exists"], True)
        # Hidden folders are not listed by their parent, so `exists` is the only way to know.
        self.assertIs(self.daemon.api("GET", f"/api/fs?path={self.home}/.hidden")[1]["exists"], True)
        missing = self.daemon.api("GET", f"/api/fs?path={self.home}/deleted-folder")[1]
        self.assertEqual((missing["exists"], missing["directories"]), (False, []))

        png = base64.b64encode(b"\x89PNG\r\n\x1a\nxx").decode()
        status, res = self.daemon.api("POST", "/api/debug/dumps", {
            "dumpId": "ab/../cd", "createdAt": 1795536752575, "note": "hi", "eventsNdjson": "{}\n{}\n",
            "workspaceStateJson": '{"a":1}', "logcat": "log", "screenshotPngBase64": png})
        self.assertEqual(status, 200)
        self.assertEqual(res["dumpId"], "ab_.._cd")
        self.assertEqual(sorted(res["files"]), sorted(["meta.json", "state.json", "events.ndjson", "logcat.txt",
                                                       "screenshot.png", "summary.md"]))
        dump_dir = Path(res["remoteDir"])
        self.assertTrue(dump_dir.name.startswith("20261124-"))
        meta = json.loads((dump_dir / "meta.json").read_text())
        self.assertEqual((meta["eventCount"], meta["hasScreenshot"], meta["createdAtIso"]),
                         (2, True, "2026-11-24T16:12:32.575Z"))
        self.assertEqual(os.path.realpath(res["latestSymlink"]), str(dump_dir))
        self.assertEqual(self.daemon.api("GET", "/api/debug/dumps")[1]["dumps"][0]["dirName"], dump_dir.name)

    def test_keep_alive_and_chunked_request_body(self):
        with socket.create_connection(("127.0.0.1", self.daemon.port)) as s:
            body = json.dumps({"agent": "shell"}).encode()
            chunked = b"%x\r\n%s\r\n0\r\n\r\n" % (len(body), body)
            s.sendall(
                b"GET /api/tabs HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer " + self.daemon.token.encode() + b"\r\n\r\n"
                + b"POST /api/tabs HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\nAuthorization: Bearer "
                + self.daemon.token.encode() + b"\r\n\r\n" + chunked
            )
            data = b""
            while data.count(b"HTTP/1.1 200") < 2 or not data.rstrip().endswith(b"}}"):
                chunk = s.recv(65536)
                if not chunk:
                    break
                data += chunk
        self.assertEqual(data.count(b"HTTP/1.1 200 OK"), 2)
        self.assertIn(b'"agent":"shell"', data)

    def test_app_state_relay_with_large_frame_and_real_cli(self):
        big_png = base64.b64encode(os.urandom(3 * 1024 * 1024)).decode()

        async def fake_app(ready: asyncio.Event, stop: asyncio.Event):
            ws = await self.ws()
            async def loop():
                async for text in ws.messages():
                    msg = json.loads(text)
                    if msg["type"] == "snapshot":
                        ws.send(hello(True))
                        await asyncio.sleep(0.05)
                        ready.set()
                    elif msg["type"] == "app_state_request":
                        result = {"pngBase64": big_png} if msg["command"] == "screenshot" else {"pong": True}
                        ws.send(json.dumps({"type": "app_state_response", "requestId": msg["requestId"],
                                            "ok": True, "result": result}))
            task = asyncio.ensure_future(loop())
            await stop.wait()
            ws.close()
            await asyncio.wait_for(task, 5)

        def cli(*args):
            return subprocess.run([sys.executable, str(CLI), "--config",
                                   str(self.home / ".config" / "herdr-mobile" / "config.json"),
                                   "--host", "127.0.0.1", *args], capture_output=True, text=True, timeout=60)

        async def go():
            r = await asyncio.to_thread(cli, "status")
            self.assertEqual(r.returncode, 3, r.stdout + r.stderr)
            ready, stop = asyncio.Event(), asyncio.Event()
            app = asyncio.ensure_future(fake_app(ready, stop))
            await asyncio.wait_for(ready.wait(), 5)
            r = await asyncio.to_thread(cli, "ping")
            self.assertEqual((r.returncode, json.loads(r.stdout)["result"]), (0, {"pong": True}), r.stderr)
            out = self.home / "shot.png"
            r = await asyncio.to_thread(cli, "screenshot", "-o", str(out))
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertEqual(out.read_bytes(), base64.b64decode(big_png))
            stop.set()
            await app
            await asyncio.sleep(0.1)
            r = await asyncio.to_thread(cli, "status")
            self.assertEqual(r.returncode, 3)
        run_async(go(), 90)

    def test_node_ws_client_interop(self):
        node = shutil.which("node")
        ws_dir = next((p for p in NODE_WS_DIRS if (p / "node_modules" / "ws").exists()), None)
        if not node or not ws_dir:
            self.skipTest("node + ws package not available")
        script = f"""
const WebSocket = require({json.dumps(str(ws_dir / "node_modules" / "ws"))});
const ws = new WebSocket("ws://127.0.0.1:{self.daemon.port}/ws", {{ headers: {{ Authorization: "Bearer {self.daemon.token}" }} }});
const big = "x".repeat(200000);
ws.on("message", (raw) => {{
  const m = JSON.parse(raw);
  if (m.type === "snapshot") {{
    ws.ping();
    ws.send(JSON.stringify({{type: "app_hello", reporter: {{enabled: true}}, pad: big}}));
    setTimeout(() => ws.close(1000, "bye"), 200);
  }}
}});
ws.on("pong", () => console.log("PONG"));
ws.on("close", (code) => {{ console.log("CLOSED", code); process.exit(0); }});
ws.on("error", (e) => {{ console.log("ERROR", e.message); process.exit(1); }});
"""
        r = subprocess.run([node, "-e", script], capture_output=True, text=True, timeout=20)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn("PONG", r.stdout)
        self.assertIn("CLOSED 1000", r.stdout)

    def test_message_queue_and_force_interrupt_via_rest_and_ws(self):
        d = self.daemon

        async def go():
            ws = await self.ws()
            msgs = ws.messages()
            await msgs.__anext__()  # snapshot

            status, body = await asyncio.to_thread(d.api, "POST", "/api/tabs", {"agent": "shell", "cwd": str(self.home)})
            self.assertEqual(status, 200)
            tab_id = body["tab"]["id"]

            # Start a slow shell turn
            status, _ = await asyncio.to_thread(
                d.api, "POST", f"/api/tabs/{tab_id}/messages", {"text": "echo first; sleep 10"}
            )
            self.assertEqual(status, 200)

            # Queue two follow-ups while the first turn is still running (must return 200, not 409)
            status, q1 = await asyncio.to_thread(
                d.api, "POST", f"/api/tabs/{tab_id}/messages", {"text": "echo second"}
            )
            self.assertEqual((status, q1["queued"]), (200, True))
            status, q2 = await asyncio.to_thread(
                d.api, "POST", f"/api/tabs/{tab_id}/messages", {"text": "echo drop_me"}
            )
            self.assertEqual((status, q2["queued"], len(q2["tab"]["queuedMessages"])), (200, True, 2))

            # Remove the second queued message via DELETE /api/tabs/:id/queue/:queuedId
            status, removed = await asyncio.to_thread(
                d.api, "DELETE", f"/api/tabs/{tab_id}/queue/{q2['queuedMessage']['id']}"
            )
            self.assertEqual((status, removed["ok"]), (200, True))
            self.assertEqual([m["text"] for m in removed["tab"]["queuedMessages"]], ["echo second"])

            # Force interrupt & process queue + current draft text
            status, intr = await asyncio.to_thread(
                d.api, "POST", f"/api/tabs/{tab_id}/interrupt",
                {"processQueue": True, "text": "echo third"},
            )
            self.assertEqual((status, intr["ok"]), (200, True))

            # Wait for the combined queued turn to complete over WebSocket
            completed = None
            while completed is None:
                env = json.loads(await asyncio.wait_for(msgs.__anext__(), timeout=15))
                if env["type"] == "message_updated" and env["message"]["role"] == "assistant" \
                        and env["message"]["status"] == "complete":
                    completed = env["message"]
            ws.close()
            return tab_id, completed

        tab_id, completed = run_async(go(), 30)
        self.assertIn("second", completed["text"])
        self.assertIn("third", completed["text"])
        self.assertNotIn("drop_me", completed["text"])
        status, res = d.api("GET", f"/api/tabs/{tab_id}/messages")
        self.assertEqual(status, 200)
        self.assertEqual(res["tab"]["queuedMessages"], [])
        self.assertEqual(len(res["messages"]), 4)
        self.assertEqual(res["messages"][1]["status"], "interrupted")
        self.assertEqual(res["messages"][2]["text"], "echo second\n\necho third")
        self.assertEqual(res["messages"][3]["status"], "complete")


if __name__ == "__main__":
    unittest.main()
