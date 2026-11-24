"""Tests for app_state_cli against a stub daemon. Run: python3 -m unittest discover app_state_cli"""

import base64
import contextlib
import io
import json
import os
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import app_state_cli as cli  # noqa: E402

TOKEN = "tok_test"
PNG_BYTES = b"\x89PNG\r\n\x1a\nfake"


class StubDaemon(BaseHTTPRequestHandler):
    # Mutated by tests.
    ready = True
    control = True
    ui_calls = 0
    appears_after = 0
    requests = []

    def log_message(self, *args):
        pass

    def _send(self, status, payload):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _authorized(self):
        if self.headers.get("Authorization") != f"Bearer {TOKEN}":
            self._send(401, {"error": "Invalid bearer token"})
            return False
        return True

    def do_GET(self):
        if not self._authorized():
            return
        if self.path == "/api/app/status":
            self._send(200, {"ready": StubDaemon.ready, "reason": "stub", "target": None, "clients": []})
        else:
            self._send(404, {"error": f"Unknown endpoint GET {self.path}"})

    def do_POST(self):
        if not self._authorized():
            return
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        StubDaemon.requests.append(body)
        if self.path != "/api/app/command":
            self._send(404, {"error": "nope"})
            return
        if not StubDaemon.ready:
            self._send(403, {"ok": False, "code": "inspection_disabled", "error": "remote inspection is off"})
            return
        command = body["command"]
        if command == "screenshot":
            result = {"capturedAt": 1, "pngBase64": base64.b64encode(PNG_BYTES).decode()}
        elif command == "ui":
            StubDaemon.ui_calls += 1
            nodes = [
                {"id": 4, "window": 0, "bounds": [900, 2100, 1060, 2300], "center": [980, 2200], "role": "Button",
                 "description": "Send", "tag": "send_button", "clickable": True},
                {"id": 3, "window": 1, "bounds": [0, 2100, 880, 2300], "center": [440, 2200], "text": "Message",
                 "value": "dra\"ft", "tag": "composer_input", "editable": True, "focused": True, "enabled": False,
                 "toggle": "on", "selected": False},
            ]
            wanted = body.get("args", {}).get("tag")
            if wanted:
                nodes = [n for n in nodes if n["tag"] == wanted and StubDaemon.ui_calls >= StubDaemon.appears_after]
            result = {"width": 1080, "height": 2400, "nodeCount": len(nodes), "nodes": nodes,
                      "windows": [{"index": 0, "type": "activity"}, {"index": 1, "type": "popup"}]}
        elif command == "tap" and not StubDaemon.control:
            self._send(403, {"ok": False, "code": "control_disabled", "error": "remote control is off"})
            return
        elif command == "fail":
            self._send(502, {"ok": False, "code": "app_error", "error": "boom"})
            return
        else:
            result = {"echo": body.get("args")}
        self._send(200, {"ok": True, "clientId": "c1", "command": command, "elapsedMs": 3, "result": result})


class CliTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), StubDaemon)
        cls.port = cls.server.server_address[1]
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def setUp(self):
        StubDaemon.ready = True
        StubDaemon.control = True
        StubDaemon.ui_calls = 0
        StubDaemon.appears_after = 0
        StubDaemon.requests = []
        self.tmp = tempfile.TemporaryDirectory()
        self.config = Path(self.tmp.name) / "config.json"
        self.config.write_text(json.dumps({"token": TOKEN, "host": "127.0.0.1", "port": self.port}))
        self._env = {k: os.environ.pop(k) for k in list(os.environ) if k.startswith("AGENTS_TERM_")}

    def tearDown(self):
        os.environ.update(self._env)
        self.tmp.cleanup()

    def run_cli(self, *argv):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = cli.main(["--config", str(self.config), *argv])
        return code, out.getvalue(), err.getvalue()

    def test_status_ready_and_not_ready_exit_codes(self):
        code, out, _ = self.run_cli("status")
        self.assertEqual(code, 0)
        self.assertTrue(json.loads(out)["ready"])
        StubDaemon.ready = False
        code, _, _ = self.run_cli("status")
        self.assertEqual(code, cli.EXIT_NOT_READY)

    def test_global_flags_work_before_and_after_subcommand(self):
        _, out_before, _ = self.run_cli("--pretty", "--timeout", "7", "ping")
        _, out_after, _ = self.run_cli("ping", "--pretty", "--timeout", "7")
        self.assertIn("\n  ", out_before)
        self.assertEqual(out_before, out_after)
        self.assertEqual(StubDaemon.requests[-1]["timeoutMs"], 7000)
        self.assertEqual(StubDaemon.requests[-2]["timeoutMs"], 7000)

    def test_logs_builds_filter_args(self):
        code, out, _ = self.run_cli("logs", "--limit", "5", "--category", "http", "--category", "ws",
                                    "--since", "123", "--grep", "fail")
        self.assertEqual(code, 0)
        self.assertEqual(
            json.loads(out)["result"]["echo"],
            {"limit": 5, "categories": ["http", "ws"], "sinceTs": 123, "contains": "fail"},
        )

    def test_screenshot_writes_png_and_omits_base64(self):
        target = Path(self.tmp.name) / "shots" / "s.png"
        code, out, _ = self.run_cli("screenshot", "-o", str(target))
        self.assertEqual(code, 0)
        self.assertEqual(target.read_bytes(), PNG_BYTES)
        result = json.loads(out)["result"]
        self.assertNotIn("pngBase64", result)
        self.assertEqual(result["path"], str(target.resolve()))
        self.assertEqual(result["bytes"], len(PNG_BYTES))

    def test_not_ready_and_app_errors_map_to_exit_codes(self):
        code, _, err = self.run_cli("raw", "fail")
        self.assertEqual(code, cli.EXIT_APP)
        self.assertEqual(json.loads(err)["code"], "app_error")
        StubDaemon.ready = False
        code, _, err = self.run_cli("state")
        self.assertEqual(code, cli.EXIT_NOT_READY)
        self.assertEqual(json.loads(err)["code"], "inspection_disabled")

    def sent(self, *argv):
        """Runs a subcommand and returns (command, args) of the request(s) it made."""
        StubDaemon.requests = []
        code, out, err = self.run_cli(*argv)
        self.assertEqual(code, 0, err)
        return [(r["command"], r["args"]) for r in StubDaemon.requests]

    def test_control_commands_build_app_requests(self):
        self.assertEqual(self.sent("tap", "540", "1200"), [("tap", {"x": 540, "y": 1200})])
        self.assertEqual(self.sent("tap", "--tag", "send_button"), [("tap", {"tag": "send_button"})])
        self.assertEqual(self.sent("tap", "--text", "Send", "--exact", "--index", "2"),
                         [("tap", {"text": "Send", "exact": True, "index": 2})])
        self.assertEqual(self.sent("long-press", "--tag", "msg", "--duration-ms", "1200"),
                         [("long_press", {"tag": "msg", "durationMs": 1200})])
        self.assertEqual(self.sent("swipe", "500", "1500", "500", "400", "--hold-ms", "100"),
                         [("swipe", {"x1": 500, "y1": 1500, "x2": 500, "y2": 400, "durationMs": 300, "holdMs": 100})])
        self.assertEqual(self.sent("scroll"), [("scroll", {"direction": "down"})])
        self.assertEqual(self.sent("scroll", "up", "--amount", "0.3", "--tag", "messages"),
                         [("scroll", {"tag": "messages", "direction": "up", "amount": 0.3})])
        self.assertEqual(self.sent("type", "hello world"), [("type", {"text": "hello world"})])
        self.assertEqual(self.sent("type", "hi", "--tag", "composer_input", "--replace", "--submit"),
                         [("type", {"text": "hi", "tag": "composer_input", "replace": True, "submit": True})])
        self.assertEqual(self.sent("type", "22", "--field", "Port"), [("type", {"text": "22", "field": "Port"})])
        self.assertEqual(self.sent("key", "back"), [("key", {"key": "back"})])

    def test_tap_needs_exactly_one_kind_of_target(self):
        for argv in (("tap",), ("tap", "5"), ("tap", "5", "6", "--tag", "x")):
            code, _, err = self.run_cli(*argv)
            self.assertEqual((code, json.loads(err)["code"]), (cli.EXIT_USAGE, "bad_args"), argv)
        self.assertEqual(StubDaemon.requests, [])

    def test_control_disabled_is_not_ready(self):
        StubDaemon.control = False
        code, _, err = self.run_cli("tap", "1", "2")
        self.assertEqual((code, json.loads(err)["code"]), (cli.EXIT_NOT_READY, "control_disabled"))

    def test_action_can_capture_a_screenshot_afterwards(self):
        target = Path(self.tmp.name) / "after-tap.png"
        code, out, err = self.run_cli("tap", "--tag", "send_button", "--screenshot", str(target), "--settle", "0")
        self.assertEqual(code, 0, err)
        self.assertEqual(target.read_bytes(), PNG_BYTES)
        self.assertEqual(json.loads(out)["screenshot"], str(target.resolve()))
        self.assertEqual([r["command"] for r in StubDaemon.requests], ["tap", "screenshot"])

    def test_ui_json_filters_and_brief_listing(self):
        self.assertEqual(self.sent("ui", "--text", "send", "--interactive"),
                         [("ui", {"text": "send", "interactiveOnly": True})])
        code, out, _ = self.run_cli("ui")
        self.assertEqual(json.loads(out)["result"]["nodeCount"], 2)
        code, out, _ = self.run_cli("ui", "--brief")
        self.assertEqual(code, 0)
        self.assertEqual(out.splitlines(), [
            "# 1080x2400 screenshot pixels; windows 0:activity, 1:popup; [x,y] is each element's center",
            '[980,2200] Button description="Send" tag=send_button clickable',
            '[440,2200] "Message" value="dra\\"ft" tag=composer_input toggle=on selected=false editable focused '
            'disabled window=1',
        ])

    def test_wait_polls_until_element_appears_or_times_out(self):
        StubDaemon.appears_after = 3
        code, out, err = self.run_cli("wait", "--tag", "send_button", "--interval", "0.05")
        self.assertEqual(code, 0, err)
        self.assertEqual(json.loads(out)["nodes"][0]["tag"], "send_button")
        self.assertEqual(StubDaemon.ui_calls, 3)

        code, out, _ = self.run_cli("wait", "--tag", "missing", "--gone")
        self.assertEqual((code, json.loads(out)["gone"]), (0, True))

        code, _, err = self.run_cli("wait", "--tag", "missing", "--wait-timeout", "0.2", "--interval", "0.05")
        self.assertEqual((code, json.loads(err)["code"]), (cli.EXIT_APP, "wait_timeout"))
        code, _, err = self.run_cli("wait", "--tag", "send_button", "--gone", "--wait-timeout", "0.1", "--interval", "0.05")
        self.assertEqual((code, json.loads(err)["code"]), (cli.EXIT_APP, "wait_timeout"))
        code, _, err = self.run_cli("wait")
        self.assertEqual(code, cli.EXIT_USAGE)

    def test_bad_token_and_unreachable_daemon(self):
        code, _, err = self.run_cli("--token", "wrong", "ping")
        self.assertEqual(code, cli.EXIT_DAEMON)
        self.assertEqual(json.loads(err)["code"], "unauthorized")
        code, _, err = self.run_cli("--port", "1", "ping")
        self.assertEqual(code, cli.EXIT_DAEMON)
        self.assertEqual(json.loads(err)["code"], "daemon_unreachable")

    def test_old_daemon_without_app_endpoints(self):
        self.config.write_text(json.dumps({"token": TOKEN, "host": "127.0.0.1", "port": self.port}))
        # Stub answers 404 for unknown GET paths, like a daemon that predates /api/app/*.
        client = cli.DaemonClient(f"http://127.0.0.1:{self.port}", TOKEN, 5)
        with self.assertRaises(cli.CliError) as ctx:
            client._request("GET", "/api/app/old")
        self.assertEqual(ctx.exception.code, "daemon_unsupported")

    def test_env_overrides_config(self):
        os.environ["AGENTS_TERM_TOKEN"] = "wrong"
        code, _, _ = self.run_cli("ping")
        self.assertEqual(code, cli.EXIT_DAEMON)

    def test_logs_follow_dedupes_inclusive_since_and_keeps_same_ms_events(self):
        batches = [
            [{"ts": 10, "event": "a"}, {"ts": 20, "event": "b"}],
            [{"ts": 20, "event": "b"}, {"ts": 20, "event": "c"}, {"ts": 30, "event": "d"}],
            [{"ts": 30, "event": "d"}],
        ]
        sent_since = []

        class FakeClient:
            def command(self, command, args, timeout_s):
                sent_since.append(args.get("sinceTs"))
                if not batches:
                    raise KeyboardInterrupt
                return {"result": {"events": batches.pop(0)}}

        args = cli.build_parser().parse_args(["logs", "-f", "--interval", "0"])
        out = io.StringIO()
        with contextlib.redirect_stdout(out), self.assertRaises(KeyboardInterrupt):
            cli.cmd_logs(FakeClient(), args)
        self.assertEqual([json.loads(l)["event"] for l in out.getvalue().splitlines()], ["a", "b", "c", "d"])
        self.assertEqual(sent_since, [None, 20, 30, 30])

    def test_raw_rejects_non_object_args(self):
        code, _, err = self.run_cli("raw", "ping", "--args", "[1]")
        self.assertEqual(code, cli.EXIT_USAGE)
        self.assertEqual(json.loads(err)["code"], "bad_args")


if __name__ == "__main__":
    unittest.main()
