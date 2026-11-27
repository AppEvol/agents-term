//! Turn execution for the Claude Code harness: CLI arguments and stream-json event parsing.

use serde_json::{json, Value};
use std::collections::HashMap;

use crate::harnesses::turn::{
    denied_from, num_or_null, tool_output_text, truncate_chars, PreparedTurn, TurnOutcome,
    TurnParser, TurnRequest, TurnSink, MAX_TOOL_OUTPUT,
};
use crate::util::{block_separator, js_truthy, now_ms, BlockBreaks};

/// Builds the CLI arguments for one turn and the parser for its output.
pub(crate) fn prepare_turn(req: TurnRequest) -> PreparedTurn {
    let TurnRequest {
        tab,
        prompt,
        permission_mode,
        system_prompt,
        attachments_dir,
        ..
    } = req;

    let mut args = vec![
        "-p".to_string(),
        prompt,
        "--output-format".to_string(),
        "stream-json".to_string(),
        "--verbose".to_string(),
        "--include-partial-messages".to_string(),
    ];
    match permission_mode.as_str() {
        "full-access" => args.push("--dangerously-skip-permissions".to_string()),
        "accept-edits" => {
            args.push("--permission-mode".to_string());
            args.push("acceptEdits".to_string());
        }
        "plan" => {
            args.push("--permission-mode".to_string());
            args.push("plan".to_string());
        }
        _ => {
            args.push("--permission-mode".to_string());
            args.push("default".to_string());
        }
    }
    let session = tab.get("sessionRef").cloned().filter(|v| !v.is_null());
    if let Some(sid) = session
        .as_ref()
        .and_then(Value::as_object)
        .filter(|o| o.get("kind").and_then(Value::as_str) == Some("claude"))
        .and_then(|o| o.get("value").and_then(Value::as_str))
        .filter(|s| !s.is_empty())
    {
        args.push("--resume".to_string());
        args.push(sid.to_string());
    }
    if let Some(model) = tab
        .get("model")
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty())
    {
        args.push("--model".to_string());
        args.push(model.to_string());
    }
    args.push("--append-system-prompt".to_string());
    args.push(system_prompt);
    if let Some(dir) = attachments_dir {
        // Lets the agent open attached images when its permission mode limits file access.
        args.push(format!("--add-dir={dir}"));
    }

    let parser = ClaudeTurn {
        session_ref: session,
        usage: None,
        saw_partial: false,
        denied: Vec::new(),
        tool_starts: HashMap::new(),
        breaks: BlockBreaks::default(),
    };
    PreparedTurn {
        args,
        status: "Claude is thinking…",
        parser: Box::new(parser),
    }
}

struct ClaudeTurn {
    session_ref: Option<Value>,
    usage: Option<Value>,
    saw_partial: bool,
    denied: Vec<Value>,
    tool_starts: HashMap<String, i64>,
    breaks: BlockBreaks,
}

impl TurnParser for ClaudeTurn {
    fn handle_line(&mut self, line: &str, sink: &TurnSink) {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            return;
        }
        let Ok(Value::Object(obj)) = serde_json::from_str::<Value>(trimmed) else {
            return;
        };
        if let Some(sid) = obj
            .get("session_id")
            .and_then(Value::as_str)
            .filter(|s| !s.is_empty())
        {
            sink.persist_session_ref(&mut self.session_ref, "claude", sid);
        }

        let ev_type = obj.get("type").and_then(Value::as_str).unwrap_or("");
        if ev_type == "system" && obj.get("subtype").and_then(Value::as_str) == Some("init") {
            if let Some(model) = obj
                .get("model")
                .and_then(Value::as_str)
                .filter(|s| !s.is_empty())
            {
                let mut message = sink.message();
                if message["model"].is_null() {
                    message["model"] = json!(model);
                }
            }
        }
        if ev_type == "stream_event" {
            if let Some(inner) = obj.get("event").and_then(Value::as_object) {
                let itype = inner.get("type").and_then(Value::as_str).unwrap_or("");
                if itype == "content_block_start" {
                    let bt = inner
                        .get("content_block")
                        .and_then(Value::as_object)
                        .and_then(|b| b.get("type"))
                        .and_then(Value::as_str);
                    self.breaks.start(bt);
                } else if itype == "content_block_delta" {
                    if let Some(d) = inner.get("delta").and_then(Value::as_object) {
                        let dtype = d.get("type").and_then(Value::as_str).unwrap_or("");
                        if dtype == "text_delta" {
                            if let Some(t) = d.get("text").and_then(Value::as_str) {
                                self.saw_partial = true;
                                if !t.is_empty() {
                                    let existing = sink
                                        .message()
                                        .get("text")
                                        .and_then(Value::as_str)
                                        .map(str::to_string);
                                    let sep = self.breaks.take_text(existing.as_deref());
                                    sink.emit_delta(Some(format!("{sep}{t}")), None, false);
                                }
                            }
                        } else if dtype == "thinking_delta" {
                            if let Some(t) = d
                                .get("thinking")
                                .and_then(Value::as_str)
                                .filter(|s| !s.is_empty())
                            {
                                let existing = sink
                                    .message()
                                    .get("thinking")
                                    .and_then(Value::as_str)
                                    .map(str::to_string);
                                let sep = self.breaks.take_thinking(existing.as_deref());
                                sink.emit_delta(None, Some(format!("{sep}{t}")), false);
                            }
                        }
                    }
                }
            }
        } else if ev_type == "assistant" {
            if let Some(content) = obj
                .get("message")
                .and_then(Value::as_object)
                .and_then(|m| m.get("content"))
                .and_then(Value::as_array)
            {
                for block in content {
                    let Some(b) = block.as_object() else { continue };
                    match b.get("type").and_then(Value::as_str) {
                        Some("tool_use") => {
                            sink.tool_use_started(
                                &mut self.tool_starts,
                                b.get("id"),
                                b.get("name"),
                                b.get("input"),
                            );
                        }
                        Some("text") if !self.saw_partial => {
                            if let Some(t) = b
                                .get("text")
                                .and_then(Value::as_str)
                                .filter(|s| !s.is_empty())
                            {
                                let existing = sink
                                    .message()
                                    .get("text")
                                    .and_then(Value::as_str)
                                    .map(str::to_string);
                                let sep = block_separator(existing.as_deref());
                                sink.emit_delta(Some(format!("{sep}{t}")), None, false);
                            }
                        }
                        _ => {}
                    }
                }
            }
        } else if ev_type == "user" {
            if let Some(content) = obj
                .get("message")
                .and_then(Value::as_object)
                .and_then(|m| m.get("content"))
                .and_then(Value::as_array)
            {
                for block in content {
                    let Some(b) = block.as_object() else { continue };
                    if b.get("type").and_then(Value::as_str) == Some("tool_result") {
                        if let Some(tool_id) = b.get("tool_use_id").and_then(Value::as_str) {
                            let existing = sink.find_tool(tool_id);
                            let out_text = tool_output_text(b.get("content"))
                                .map(|s| truncate_chars(&s, MAX_TOOL_OUTPUT));
                            let dur = self.tool_starts.get(tool_id).map(|s| json!(now_ms() - s));
                            let is_err = b.get("is_error").map(js_truthy).unwrap_or(false);
                            sink.upsert_tool_call(json!({
                                        "id": tool_id,
                                        "name": existing.as_ref().and_then(|e| e.get("name").and_then(Value::as_str)).unwrap_or("Tool"),
                                        "inputSummary": existing.as_ref().and_then(|e| e.get("inputSummary").and_then(Value::as_str)).unwrap_or(""),
                                        "inputJson": existing.as_ref().and_then(|e| e.get("inputJson").cloned()).unwrap_or(Value::Null),
                                        "output": out_text,
                                        "status": if is_err { "error" } else { "done" },
                                        "durationMs": dur,
                                    }),
                                );
                        }
                    }
                }
            }
        } else if ev_type == "result" {
            if let Some(res_val) = obj.get("result").and_then(Value::as_str) {
                let cur_empty = sink
                    .message()
                    .get("text")
                    .and_then(Value::as_str)
                    .unwrap_or("")
                    .trim()
                    .is_empty();
                if !res_val.is_empty() && cur_empty {
                    sink.emit_delta(Some(res_val.to_string()), None, false);
                }
                if obj.get("is_error").map(js_truthy).unwrap_or(false) {
                    sink.message()
                        .as_object_mut()
                        .unwrap()
                        .insert("error".into(), json!(res_val));
                }
            }
            if let Some(u) = obj.get("usage").and_then(Value::as_object) {
                self.usage = Some(json!({
                    "inputTokens": num_or_null(u.get("input_tokens")),
                    "outputTokens": num_or_null(u.get("output_tokens")),
                    "thinkingTokens": Value::Null,
                    "costUsd": num_or_null(obj.get("total_cost_usd")),
                    "durationMs": num_or_null(obj.get("duration_ms")),
                }));
            }
            if let Some(denials) = obj.get("permission_denials").and_then(Value::as_array) {
                for d in denials {
                    if let Some(dobj) = d.as_object() {
                        self.denied
                            .push(denied_from(dobj, "tool", "Permission Required", false));
                    }
                }
            }
        }
    }

    fn finish(self: Box<Self>) -> TurnOutcome {
        TurnOutcome {
            session_ref: self.session_ref,
            usage: self.usage,
            denied: self.denied,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::harnesses::turn::test_request;

    #[test]
    fn new_session_arguments() {
        let turn = prepare_turn(test_request(
            json!({ "id": "tab_test" }),
            "accept-edits",
            false,
        ));
        assert_eq!(
            turn.args,
            [
                "-p",
                "hello",
                "--output-format",
                "stream-json",
                "--verbose",
                "--include-partial-messages",
                "--permission-mode",
                "acceptEdits",
                "--append-system-prompt",
                "SYS",
            ]
        );
    }

    #[test]
    fn resume_ignores_another_harness_session() {
        let tab = json!({
            "id": "tab_test",
            "model": "opus",
            "sessionRef": { "kind": "claude", "value": "sess-1" },
        });
        let turn = prepare_turn(test_request(tab, "full-access", false));
        assert_eq!(
            turn.args,
            [
                "-p",
                "hello",
                "--output-format",
                "stream-json",
                "--verbose",
                "--include-partial-messages",
                "--dangerously-skip-permissions",
                "--resume",
                "sess-1",
                "--model",
                "opus",
                "--append-system-prompt",
                "SYS",
            ]
        );
        let other = json!({ "id": "tab_test", "sessionRef": { "kind": "codex", "value": "t-1" } });
        let turn = prepare_turn(test_request(other, "default", false));
        assert!(!turn.args.iter().any(|a| a == "--resume"));
    }
}
