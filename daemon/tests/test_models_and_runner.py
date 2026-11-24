"""Ports of the TS daemon's models/agy-stream tests plus runner coverage for shell/claude/codex/interrupt."""

import asyncio
import json
import os
import unittest
from pathlib import Path

from helpers import FIXTURES_DIR, TempHomeTestCase, run_async

from agentsterm_daemon.config import default_system_prompt, live_debugging_doc_path
from agentsterm_daemon.models import (
    clear_models_cache_for_tests,
    parse_agy_models_output,
    read_claude_models_from_disk,
    read_codex_models_from_disk,
)
from agentsterm_daemon.runner import AgentRunner, block_separator
from agentsterm_daemon.store import StateStore
from agentsterm_daemon.util import summarize_command, summarize_tool_input


class ModelsTest(TempHomeTestCase):
    def test_parse_agy_models_output_strips_spinner_and_ansi(self):
        sample = "\n".join([
            "\r\x1b[36m⠋ Fetching available models...\x1b[0m\r\x1b[2Kgemini-4-neon-high             Gemini 4 Neon (High)",
            "gemini-4-neon-medium           Gemini 4 Neon (Medium)",
            "claude-opus-5-5-high           Claude Opus 5.5 (High)",
            "gemini-4-neon-high             Duplicate Should Be Ignored",
            "",
        ])
        self.assertEqual(parse_agy_models_output(sample), [
            {"id": "gemini-4-neon-high", "name": "Gemini 4 Neon (High)"},
            {"id": "gemini-4-neon-medium", "name": "Gemini 4 Neon (Medium)"},
            {"id": "claude-opus-5-5-high", "name": "Claude Opus 5.5 (High)"},
        ])

    def test_claude_models_from_catalog_and_settings_with_alias_fallback(self):
        fallback = read_claude_models_from_disk(self.tmp)
        self.assertIsNone(fallback["defaultModel"])
        self.assertEqual([m["id"] for m in fallback["models"]], ["opus", "sonnet", "haiku"])

        catalog_dir = self.tmp / ".claude" / "cache" / "model-catalog"
        catalog_dir.mkdir(parents=True)
        (self.tmp / ".claude" / "settings.json").write_text(json.dumps({"model": "claude-sonnet-5-5"}))
        (catalog_dir / "catalog.json").write_text(json.dumps({"catalog": {"config": {"models": [
            {"id": "claude-opus-5-5", "name": "Opus 5.5", "description": "Complex work"},
            {"id": "claude-sonnet-5-5", "name": "Sonnet 5.5", "description": "Fast & balanced"},
        ]}}}))
        found = read_claude_models_from_disk(self.tmp)
        self.assertEqual(found["defaultModel"], "claude-sonnet-5-5")
        self.assertEqual([(m["id"], m["isDefault"]) for m in found["models"]],
                         [("claude-opus-5-5", False), ("claude-sonnet-5-5", True)])

    def test_codex_models_filter_hidden_sort_by_priority_read_toml_default(self):
        codex = self.tmp / ".codex"
        codex.mkdir()
        (codex / "config.toml").write_text('model = "gpt-6-astra"\n')
        (codex / "models_cache.json").write_text(json.dumps({"models": [
            {"slug": "gpt-6-astra", "display_name": "GPT-6-Astra", "visibility": "list", "supported_in_api": True, "priority": 5},
            {"slug": "gpt-hidden", "display_name": "Hidden", "visibility": "hide", "supported_in_api": True, "priority": 1},
            {"slug": "gpt-6.2-sol", "display_name": "GPT-6.2-Sol", "visibility": "list", "supported_in_api": True, "priority": 2},
        ]}))
        found = read_codex_models_from_disk(self.tmp)
        self.assertEqual(found["defaultModel"], "gpt-6-astra")
        self.assertEqual([m["id"] for m in found["models"]], ["gpt-6.2-sol", "gpt-6-astra"])
        self.assertTrue(found["models"][1]["isDefault"])


class RunnerTest(TempHomeTestCase):
    def setUp(self):
        super().setUp()
        clear_models_cache_for_tests()
        self.events = []

    def make(self):
        store = StateStore()
        return store, AgentRunner(store, self.events.append)

    def run_turn(self, runner, store, tab, prompt, timeout=15.0):
        async def go():
            res = runner.start_turn(tab, prompt)
            await runner.wait_idle(tab["id"], timeout)
            return res
        res = run_async(go(), timeout + 5)
        return res, store.get_message(tab["id"], res["assistantMessage"]["id"])

    def test_agy_native_stream_json_init_tool_text_and_result(self):
        self.fake_binary("agy", self.emit_lines_script([
            {"event": "init", "conversation_id": "conv-agy-e2e-99", "init": {"permission_mode": "always-proceed"}},
            {"event": "step_update", "step_update": {"conversation_id": "conv-agy-e2e-99", "step_index": 0,
                                                     "state": "DONE", "step_type": "user_input"}},
            {"event": "step_update", "step_update": {
                "conversation_id": "conv-agy-e2e-99", "step_index": 1, "state": "ACTIVE", "step_type": "tool",
                "tool_name": "run_command",
                "tool_info": {"name": "run_command", "parameters": {"CommandLine": "git status", "toolSummary": "Check git status"}}}},
            {"event": "step_update", "step_update": {
                "conversation_id": "conv-agy-e2e-99", "step_index": 1, "state": "DONE", "step_type": "tool",
                "tool_name": "run_command", "duration_seconds": 0.42,
                "tool_info": {"name": "run_command", "parameters": {"CommandLine": "git status"},
                              "output": "On branch main\nnothing to commit"}}},
            {"event": "step_update", "step_update": {"conversation_id": "conv-agy-e2e-99", "step_index": 2,
                                                     "state": "ACTIVE", "step_type": "agent_response",
                                                     "text_delta": "Loud and clear! "}},
            {"event": "step_update", "step_update": {
                "conversation_id": "conv-agy-e2e-99", "step_index": 2, "state": "DONE", "step_type": "agent_response",
                "text_delta": "AgentsTerm is working.\n", "duration_seconds": 1.5,
                "usage": {"input_tokens": 9127, "output_tokens": 73, "thinking_tokens": 14}}},
            {"event": "result", "result": {
                "conversation_id": "conv-agy-e2e-99", "status": "SUCCESS",
                "response": "Loud and clear! AgentsTerm is working.\n", "duration_seconds": 1.95, "num_turns": 1,
                "usage": {"input_tokens": 9127, "output_tokens": 73, "thinking_tokens": 14}}},
        ]))
        store, runner = self.make()
        tab = store.create_tab("agy", title="agents-term", cwd=str(self.tmp), permission_mode="full-access")
        _, msg = self.run_turn(runner, store, tab, "Can you say something?")

        self.assertEqual(msg["status"], "complete")
        self.assertEqual(msg["text"].strip(), "Loud and clear! AgentsTerm is working.")
        self.assertEqual(len(msg["toolCalls"]), 1)
        tool = msg["toolCalls"][0]
        self.assertEqual((tool["id"], tool["name"], tool["status"], tool["durationMs"]),
                         ("agy_step_1", "run_command", "done", 420))
        self.assertIn("On branch main", tool["output"])
        self.assertEqual(tool["inputSummary"], "Check git status")
        self.assertEqual(msg["segments"], [
            {"type": "tool", "toolCallId": "agy_step_1"},
            {"type": "text", "text": "Loud and clear! AgentsTerm is working.\n"},
        ])
        self.assertEqual((msg["usage"]["inputTokens"], msg["usage"]["outputTokens"], msg["usage"]["thinkingTokens"],
                          msg["usage"]["durationMs"]), (9127, 73, 14, 1950))
        self.assertEqual(store.get_tab(tab["id"])["sessionRef"], {"kind": "agy", "value": "conv-agy-e2e-99"})
        self.assertTrue(any(e["type"] == "message_delta" and "Loud and clear!" in e.get("textDelta", "")
                            for e in self.events))
        self.assertTrue(any(e["type"] == "tool_update" and e["toolCall"]["name"] == "run_command" for e in self.events))

    def test_agy_falls_back_to_result_response_and_resumes_conversation(self):
        args_log = self.tmp / "args.log"
        self.fake_binary("agy", self.emit_lines_script([
            {"event": "init", "conversation_id": "conv-resumed-77"},
            {"event": "result", "result": {"conversation_id": "conv-resumed-77", "status": "SUCCESS",
                                           "response": "Fallback response text from result event.",
                                           "duration_seconds": 0.8, "usage": {"input_tokens": 500, "output_tokens": 25}}},
        ], args_log))
        store, runner = self.make()
        tab = store.create_tab("agy", title="resume-test", cwd=str(self.tmp), permission_mode="full-access")
        _, msg1 = self.run_turn(runner, store, tab, "First turn")
        self.assertEqual(msg1["text"], "Fallback response text from result event.")
        updated = store.get_tab(tab["id"])
        self.assertEqual(updated["sessionRef"]["value"], "conv-resumed-77")
        _, msg2 = self.run_turn(runner, store, updated, "Second turn")
        self.assertEqual(msg2["text"], "Fallback response text from result event.")
        calls = [json.loads(line) for line in args_log.read_text().splitlines()]
        self.assertEqual(len(calls), 2)
        self.assertEqual(calls[0][-1], "--print=First turn")
        self.assertIn("--dangerously-skip-permissions", calls[0])
        self.assertIn("--conversation", calls[1])
        self.assertIn("conv-resumed-77", calls[1])

    def test_model_slash_command_is_local_and_passes_model_on_next_turn(self):
        args_log = self.tmp / "args.log"
        self.fake_binary("agy", self.emit_lines_script(
            [{"event": "result", "result": {"status": "SUCCESS", "response": "ok"}}], args_log))
        store, runner = self.make()
        tab = store.create_tab("agy", title="model-test", cwd=str(self.tmp))
        res = runner.start_turn(tab, "/model gemini-4-neon-xhigh")
        self.assertEqual(res["assistantMessage"]["status"], "complete")
        self.assertIn("gemini-4-neon-xhigh", res["assistantMessage"]["text"])
        self.assertEqual(store.get_tab(tab["id"])["model"], "gemini-4-neon-xhigh")

        self.run_turn(runner, store, store.get_tab(tab["id"]), "Hello")
        calls = [json.loads(line) for line in args_log.read_text().splitlines()]
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0][calls[0].index("--model") + 1], "gemini-4-neon-xhigh")

        reset = runner.start_turn(store.get_tab(tab["id"]), "/model default")
        self.assertRegex(reset["assistantMessage"]["text"].lower(), "reset to cli default")
        self.assertIsNone(store.get_tab(tab["id"])["model"])

    def test_shell_turn_fences_output_tracks_cwd_and_reports_failures(self):
        store, runner = self.make()
        sub = self.tmp / "work"
        sub.mkdir()
        tab = store.create_tab("shell", cwd=str(self.tmp))
        _, msg = self.run_turn(runner, store, tab, f"echo hello; cd {sub}")
        # The wrapper's printf "\n<marker>" adds a blank line before the fence (same as the TS daemon).
        self.assertEqual(msg["text"], "```text\nhello\n\n```")
        self.assertEqual(msg["status"], "complete")
        self.assertEqual(store.get_tab(tab["id"])["cwd"], str(sub))

        _, msg = self.run_turn(runner, store, store.get_tab(tab["id"]), "true")
        # Parity with the TS daemon: the wrapper's leading "\n" opens a fence even with no output.
        self.assertEqual(msg["text"], "```text\n\n```")

        _, msg = self.run_turn(runner, store, store.get_tab(tab["id"]), "echo oops >&2; exit 3")
        self.assertTrue(msg["text"].startswith("```text\n") and "oops" in msg["text"], msg["text"])
        self.assertEqual(msg["error"], "Exited with code 3: oops")
        self.assertEqual(store.get_tab(tab["id"])["status"], "idle")

    def test_claude_stream_json_partials_tools_usage_and_denials(self):
        args_log = self.tmp / "args.log"
        self.fake_binary("claude", self.emit_lines_script([
            {"type": "system", "session_id": "sess-1"},
            {"type": "stream_event", "event": {"type": "content_block_delta", "delta": {"type": "thinking_delta", "thinking": "hmm"}}},
            {"type": "stream_event", "event": {"type": "content_block_delta", "delta": {"type": "text_delta", "text": "Hi "}}},
            {"type": "assistant", "message": {"content": [
                {"type": "tool_use", "id": "tu1", "name": "Bash", "input": {"command": "ls -la"}},
                {"type": "text", "text": "ignored because partial deltas were seen"}]}},
            {"type": "user", "message": {"content": [
                {"type": "tool_result", "tool_use_id": "tu1", "content": [{"type": "text", "text": "file.txt"}]}]}},
            {"type": "stream_event", "event": {"type": "content_block_delta", "delta": {"type": "text_delta", "text": "there"}}},
            {"type": "result", "result": "Hi there", "usage": {"input_tokens": 10, "output_tokens": 2},
             "total_cost_usd": 0.01, "duration_ms": 1234,
             "permission_denials": [{"tool_name": "Write", "tool_input": {"file_path": "/etc/x"}}]},
        ], args_log))
        store, runner = self.make()
        tab = store.create_tab("claude", cwd=str(self.tmp), permission_mode="accept-edits", model="opus")
        _, msg = self.run_turn(runner, store, tab, "hello")
        self.assertEqual(msg["text"], "Hi there")
        self.assertEqual(msg["thinking"], "hmm")
        self.assertEqual(msg["toolCalls"][0]["output"], "file.txt")
        self.assertEqual(msg["toolCalls"][0]["inputSummary"], "ls -la")
        self.assertEqual(msg["usage"], {"inputTokens": 10, "outputTokens": 2, "thinkingTokens": None,
                                        "costUsd": 0.01, "durationMs": 1234})
        self.assertEqual(msg["deniedActions"], [{"action": "Write", "displayName": "Write", "detail": "/etc/x"}])
        t = store.get_tab(tab["id"])
        self.assertEqual((t["status"], t["statusDetail"]), ("blocked", "Waiting for approval"))
        self.assertEqual(t["sessionRef"], {"kind": "claude", "value": "sess-1"})
        argv = json.loads(args_log.read_text().splitlines()[0])
        self.assertEqual(argv[:2], ["-p", "hello"])
        self.assertIn("acceptEdits", argv)
        self.assertEqual(argv[argv.index("--model") + 1], "opus")

    def test_codex_exec_json_items_and_resume_args(self):
        args_log = self.tmp / "args.log"
        self.fake_binary("codex", self.emit_lines_script([
            {"type": "thread.started", "thread_id": "th-9"},
            {"type": "item.completed", "item": {"id": "r1", "type": "reasoning", "text": "thinking it over"}},
            {"type": "item.started", "item": {"id": "c1", "type": "command_execution", "command": "ls"}},
            {"type": "item.completed", "item": {"id": "c1", "type": "command_execution", "command": "ls",
                                                "aggregated_output": "a\nb", "exit_code": 1}},
            {"type": "item.completed", "item": {"id": "m1", "type": "agent_message", "text": "Done."}},
            {"type": "turn.completed", "usage": {"input_tokens": 7, "output_tokens": 3}},
        ], args_log))
        store, runner = self.make()
        tab = store.create_tab("codex", cwd=str(self.tmp), permission_mode="plan")
        _, msg = self.run_turn(runner, store, tab, "go")
        self.assertEqual(msg["text"], "Done.")
        self.assertEqual(msg["thinking"], "thinking it over")
        self.assertEqual((msg["toolCalls"][0]["status"], msg["toolCalls"][0]["output"]), ("error", "a\nb"))
        self.assertEqual(msg["usage"]["inputTokens"], 7)
        _, _ = self.run_turn(runner, store, store.get_tab(tab["id"]), "again")
        first, second = [json.loads(line) for line in args_log.read_text().splitlines()]
        self.assertEqual(first, ["exec", "--json", "--skip-git-repo-check", "-C", str(self.tmp), "-s", "read-only", "go"])
        self.assertEqual(second, ["exec", "resume", "--json", "--skip-git-repo-check", "th-9", "again"])

    def test_block_separator(self):
        self.assertEqual(block_separator(None), "")
        self.assertEqual(block_separator(""), "")
        self.assertEqual(block_separator("Done."), "\n\n")
        self.assertEqual(block_separator("Done.\n"), "\n")
        self.assertEqual(block_separator("Done.\n\n"), "")

    def test_claude_real_stream_separates_text_blocks_around_tool_calls(self):
        """Replays a stream captured from the real CLI: text, a Bash tool call, then more text."""
        self.fake_binary("claude", f"sys.stdout.write(open({str(FIXTURES_DIR / 'claude_tool_turn.ndjson')!r}).read())\n")
        store, runner = self.make()
        tab = store.create_tab("claude", cwd=str(self.tmp))
        _, msg = self.run_turn(runner, store, tab, "go")
        # Was "Starting now.All done." before the fix.
        self.assertEqual(msg["text"], "Starting now.\n\nAll done.")
        self.assertEqual([(t["name"], t["status"], t["output"]) for t in msg["toolCalls"]], [("Bash", "done", "hi")])
        self.assertEqual(msg["toolCalls"][0]["inputSummary"], "Print hi")
        self.assertEqual(msg["segments"], [
            {"type": "text", "text": "Starting now."},
            {"type": "tool", "toolCallId": "toolu_fixture_1"},
            {"type": "text", "text": "All done."},
        ])
        self.assertEqual(msg["status"], "complete")
        self.assertEqual(store.get_tab(tab["id"])["sessionRef"], {"kind": "claude", "value": "sess-fixture-1"})
        # Streamed deltas must add up to exactly the stored text (the app applies deltas).
        deltas = "".join(e.get("textDelta", "") for e in self.events if e["type"] == "message_delta")
        self.assertEqual(deltas, msg["text"])

    def test_claude_block_breaks_cover_thinking_and_skip_empty_blocks(self):
        def start(kind):
            return {"type": "stream_event", "event": {"type": "content_block_start", "content_block": {"type": kind}}}

        def delta(kind, value):
            key = "thinking" if kind == "thinking_delta" else "text"
            return {"type": "stream_event", "event": {"type": "content_block_delta", "delta": {"type": kind, key: value}}}

        self.fake_binary("claude", self.emit_lines_script([
            start("thinking"), delta("thinking_delta", "plan A"),
            start("text"), delta("text_delta", "One"), delta("text_delta", " more."),
            start("text"),                      # empty block: must not add blank lines
            start("tool_use"),
            start("thinking"), delta("thinking_delta", "plan B"),
            start("text"), delta("text_delta", ""), delta("text_delta", "Two.\n"),
            start("text"), delta("text_delta", "Three."),
        ]))
        store, runner = self.make()
        tab = store.create_tab("claude", cwd=str(self.tmp))
        _, msg = self.run_turn(runner, store, tab, "go")
        self.assertEqual(msg["text"], "One more.\n\nTwo.\n\nThree.")
        self.assertEqual(msg["thinking"], "plan A\n\nplan B")

    def test_claude_without_partial_messages_separates_full_text_blocks(self):
        self.fake_binary("claude", self.emit_lines_script([
            {"type": "assistant", "message": {"content": [{"type": "text", "text": "Starting."}]}},
            {"type": "assistant", "message": {"content": [{"type": "tool_use", "id": "t", "name": "Bash", "input": {}}]}},
            {"type": "assistant", "message": {"content": [{"type": "text", "text": "Done."}]}},
        ]))
        store, runner = self.make()
        tab = store.create_tab("claude", cwd=str(self.tmp))
        _, msg = self.run_turn(runner, store, tab, "go")
        self.assertEqual(msg["text"], "Starting.\n\nDone.")

    def test_agy_separates_responses_from_different_steps(self):
        def resp(idx, **kw):
            return {"event": "step_update", "step_update": {"step_index": idx, "step_type": "agent_response", **kw}}

        self.fake_binary("agy", self.emit_lines_script([
            resp(1, thinking_delta="think 1"),
            resp(1, text_delta="Let me "), resp(1, text_delta="check."),
            {"event": "step_update", "step_update": {"step_index": 2, "state": "DONE", "step_type": "tool",
                                                     "tool_name": "run_command", "tool_info": {"output": "ok"}}},
            resp(3, thinking_delta="think 2"),
            resp(3, text_delta="All "), resp(3, text_delta="good."),
            {"event": "result", "result": {"status": "SUCCESS", "response": "ignored"}},
        ]))
        store, runner = self.make()
        tab = store.create_tab("agy", cwd=str(self.tmp))
        _, msg = self.run_turn(runner, store, tab, "go")
        self.assertEqual(msg["text"], "Let me check.\n\nAll good.")
        self.assertEqual(msg["thinking"], "think 1\n\nthink 2")
        self.assertEqual(msg["segments"], [
            {"type": "text", "text": "Let me check."},
            {"type": "tool", "toolCallId": "agy_step_2"},
            {"type": "text", "text": "All good."},
        ])

    def test_summarize_tool_input_prefers_description_and_strips_leading_cd(self):
        self.assertEqual(
            summarize_tool_input({"command": "cd ~/projects/app && git status", "description": "Check git status"}),
            "Check git status",
        )
        self.assertEqual(
            summarize_tool_input({"CommandLine": "cd ~/projects/app && ./gradlew test", "toolSummary": "Run unit tests"}),
            "Run unit tests",
        )
        self.assertEqual(
            summarize_tool_input({"command": "cd ~/projects/app && git diff --stat", "description": "   "}),
            "git diff --stat",
        )
        self.assertEqual(
            summarize_command("cd ~/projects/agents-term-app-state && cd daemon && python3 -m unittest"),
            "python3 -m unittest",
        )
        self.assertEqual(summarize_command('cd "/tmp/my dir" && pytest -q'), "pytest -q")
        self.assertEqual(summarize_command("cd ~/projects/app; git log -n 5"), "git log -n 5")
        self.assertEqual(summarize_command("cd ~/projects/app\ngit status"), "git status")
        self.assertEqual(summarize_command("cd ~/projects/app"), "cd ~/projects/app")
        self.assertEqual(summarize_command("/bin/bash -lc 'cd /work/repo && ls -la'"), "ls -la")
        self.assertEqual(summarize_command("bash -lc 'a' && bash -lc 'b'"), "bash -lc 'a' && bash -lc 'b'")
        self.assertEqual(summarize_tool_input({"file_path": "/home/ned/app/ChatScreen.kt"}), "/home/ned/app/ChatScreen.kt")

    def test_claude_interleaved_text_and_multiple_tools_keep_order_in_segments(self):
        def start(kind):
            return {"type": "stream_event", "event": {"type": "content_block_start", "content_block": {"type": kind}}}

        def delta(text):
            return {"type": "stream_event", "event": {"type": "content_block_delta", "delta": {"type": "text_delta", "text": text}}}

        self.fake_binary("claude", self.emit_lines_script([
            start("text"), delta("Step 1: inspect."),
            {"type": "assistant", "message": {"content": [
                {"type": "tool_use", "id": "t1", "name": "Bash",
                 "input": {"command": "cd ~/projects/app && git status", "description": "Check status"}},
                {"type": "tool_use", "id": "t2", "name": "Bash",
                 "input": {"command": "cd ~/projects/app && git diff --stat"}},
            ]}},
            {"type": "user", "message": {"content": [
                {"type": "tool_result", "tool_use_id": "t1", "content": "clean"},
                {"type": "tool_result", "tool_use_id": "t2", "content": "0 files"},
            ]}},
            start("text"), delta("Step 2: verify."),
            {"type": "assistant", "message": {"content": [
                {"type": "tool_use", "id": "t3", "name": "Bash",
                 "input": {"command": "cd ~/projects/app && pytest"}},
            ]}},
            {"type": "user", "message": {"content": [
                {"type": "tool_result", "tool_use_id": "t3", "content": "ok"},
            ]}},
            start("text"), delta("All green."),
        ]))
        store, runner = self.make()
        tab = store.create_tab("claude", cwd=str(self.tmp))
        _, msg = self.run_turn(runner, store, tab, "go")
        self.assertEqual(msg["text"], "Step 1: inspect.\n\nStep 2: verify.\n\nAll green.")
        self.assertEqual([t["inputSummary"] for t in msg["toolCalls"]], ["Check status", "git diff --stat", "pytest"])
        self.assertEqual(msg["segments"], [
            {"type": "text", "text": "Step 1: inspect."},
            {"type": "tool", "toolCallId": "t1"},
            {"type": "tool", "toolCallId": "t2"},
            {"type": "text", "text": "Step 2: verify."},
            {"type": "tool", "toolCallId": "t3"},
            {"type": "text", "text": "All green."},
        ])

    def test_codex_command_summary_strips_leading_cd_and_keeps_segments(self):
        self.fake_binary("codex", self.emit_lines_script([
            {"type": "item.started", "item": {"id": "c1", "type": "command_execution",
                                              "command": "/bin/bash -lc 'cd /home/ned/app && git status'"}},
            {"type": "item.completed", "item": {"id": "c1", "type": "command_execution",
                                                "command": "/bin/bash -lc 'cd /home/ned/app && git status'",
                                                "aggregated_output": "clean", "exit_code": 0}},
            {"type": "item.completed", "item": {"id": "m1", "type": "agent_message", "text": "Clean repo."}},
        ]))
        store, runner = self.make()
        tab = store.create_tab("codex", cwd=str(self.tmp))
        _, msg = self.run_turn(runner, store, tab, "check")
        self.assertEqual(msg["toolCalls"][0]["inputSummary"], "git status")
        self.assertEqual(msg["segments"], [
            {"type": "tool", "toolCallId": "c1"},
            {"type": "text", "text": "Clean repo."},
        ])

    def test_interrupt_stops_turn_and_marks_message_interrupted(self):
        self.fake_binary("agy", "sys.stdout.write(json.dumps({'event':'step_update','step_update':"
                                "{'step_type':'agent_response','text_delta':'partial'}})+'\\n'); sys.stdout.flush()\n"
                                "time.sleep(30)\n")
        store, runner = self.make()
        tab = store.create_tab("agy", cwd=str(self.tmp))

        async def go():
            res = runner.start_turn(tab, "long task")
            for _ in range(200):
                m = store.get_message(tab["id"], res["assistantMessage"]["id"])
                if m["text"]:
                    break
                await asyncio.sleep(0.02)
            self.assertTrue(runner.interrupt(tab["id"]))
            await runner.wait_idle(tab["id"], 10)
            return res

        res = run_async(go())
        msg = store.get_message(tab["id"], res["assistantMessage"]["id"])
        self.assertEqual(msg["status"], "interrupted")
        self.assertEqual(msg["text"], "partial")
        self.assertEqual(store.get_tab(tab["id"])["status"], "idle")

    def test_missing_binary_and_state_persistence_roundtrip(self):
        os.environ["PATH"] = str(self.bin_dir)  # no claude anywhere
        store, runner = self.make()
        tab = store.create_tab("claude", cwd=str(self.tmp))
        res = runner.start_turn(tab, "hi")
        msg = store.get_message(tab["id"], res["assistantMessage"]["id"])
        if msg["status"] != "error":
            self.skipTest("a real claude binary is installed in a system dir")
        self.assertIn("not found", msg["error"])
        store.save_now()
        reloaded = StateStore()
        self.assertEqual(reloaded.get_tab(tab["id"])["status"], "error")
        self.assertEqual(len(reloaded.get_messages(tab["id"])), 2)

    def test_default_system_prompt_references_embedded_live_debugging_doc(self):
        doc_path = live_debugging_doc_path()
        self.assertTrue(doc_path.is_file(), f"Missing live debugging doc: {doc_path}")
        prompt = default_system_prompt("default")
        self.assertIn("mobile", prompt.lower())
        self.assertIn("app_state_cli", prompt)
        self.assertIn(str(doc_path), prompt)
        self.assertNotIn("close this tab", prompt.lower())

        full_access_prompt = default_system_prompt("full-access")
        self.assertTrue(full_access_prompt.startswith(prompt))
        self.assertIn("close this tab", full_access_prompt.lower())

    def test_system_prompt_passed_to_claude_codex_agy_and_supports_empty(self):
        import tomllib

        # 1) Claude: --append-system-prompt when set, omitted when empty
        claude_args = self.tmp / "claude_args.log"
        self.fake_binary("claude", self.emit_lines_script([
            {"type": "system", "session_id": "cl-1"},
            {"type": "result", "result": "ok"},
        ], claude_args))
        store, runner = self.make()
        cl_with = store.create_tab("claude", cwd=str(self.tmp), system_prompt="Be concise for mobile.")
        cl_empty = store.create_tab("claude", cwd=str(self.tmp), system_prompt="   ")
        self.assertIsNone(cl_empty["systemPrompt"])
        self.run_turn(runner, store, cl_with, "hi")
        self.run_turn(runner, store, cl_empty, "hi")
        c1, c2 = [json.loads(line) for line in claude_args.read_text().splitlines()]
        self.assertEqual(c1[c1.index("--append-system-prompt") + 1], "Be concise for mobile.")
        self.assertNotIn("--append-system-prompt", c2)

        # 2) Codex: -c developer_instructions=<toml string> on exec & resume, omitted when empty
        codex_args = self.tmp / "codex_args.log"
        self.fake_binary("codex", self.emit_lines_script([
            {"type": "thread.started", "thread_id": "th-sp"},
            {"type": "item.completed", "item": {"id": "m1", "type": "agent_message", "text": "ok"}},
        ], codex_args))
        tricky = 'Mobile view.\nUse "app_state_cli" in C:\\tmp.'
        cx_with = store.create_tab("codex", cwd=str(self.tmp), system_prompt=tricky)
        cx_empty = store.create_tab("codex", cwd=str(self.tmp), system_prompt="")
        self.run_turn(runner, store, cx_with, "turn 1")
        self.run_turn(runner, store, store.get_tab(cx_with["id"]), "turn 2")
        self.run_turn(runner, store, cx_empty, "turn 3")
        x1, x2, x3 = [json.loads(line) for line in codex_args.read_text().splitlines()]
        for argv in (x1, x2):
            cfg_val = argv[argv.index("-c") + 1]
            self.assertEqual(tomllib.loads(cfg_val)["developer_instructions"], tricky)
        self.assertNotIn("-c", x3)

        # 3) Agy: injected once on turn 1, omitted on turn 2, re-injected once when updated, clean user message
        agy_args = self.tmp / "agy_args.log"
        self.fake_binary("agy", self.emit_lines_script([
            {"event": "init", "conversation_id": "conv-sp"},
            {"event": "result", "result": {"conversation_id": "conv-sp", "status": "SUCCESS", "response": "ok"}},
        ], agy_args))
        agy_tab = store.create_tab("agy", cwd=str(self.tmp), system_prompt="Initial mobile prompt.")
        self.run_turn(runner, store, agy_tab, "First question")
        stored_user_msg = store.get_messages(agy_tab["id"])[0]
        self.assertEqual(stored_user_msg["text"], "First question")
        self.run_turn(runner, store, store.get_tab(agy_tab["id"]), "Second question")
        store.update_tab(agy_tab["id"], {"systemPrompt": "Updated mobile prompt."})
        runner.mark_system_prompt_updated(agy_tab["id"])
        self.run_turn(runner, store, store.get_tab(agy_tab["id"]), "Third question")
        self.run_turn(runner, store, store.get_tab(agy_tab["id"]), "Fourth question")
        agy_empty = store.create_tab("agy", cwd=str(self.tmp), system_prompt=None)
        self.run_turn(runner, store, agy_empty, "Plain prompt")
        a1, a2, a3, a4, a5 = [json.loads(line) for line in agy_args.read_text().splitlines()]
        self.assertIn("<system_instructions>\nInitial mobile prompt.\n</system_instructions>", a1[-1])
        self.assertTrue(a1[-1].endswith("First question"))
        self.assertEqual(a2[-1], "--print=Second question")
        self.assertIn("<system_instructions>\nUpdated mobile prompt.\n</system_instructions>", a3[-1])
        self.assertTrue(a3[-1].endswith("Third question"))
        self.assertEqual(a4[-1], "--print=Fourth question")
        self.assertEqual(a5[-1], "--print=Plain prompt")

        # 4) Shell tabs never store a systemPrompt
        sh = store.create_tab("shell", cwd=str(self.tmp), system_prompt="Should be ignored")
        self.assertIsNone(sh["systemPrompt"])


if __name__ == "__main__":
    unittest.main()
