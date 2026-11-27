//! Turn execution for the Codex harness: CLI arguments and stream-json event parsing.

use serde_json::{json, Value};
use std::collections::HashMap;

use crate::harnesses::turn::{
    num_or_null, toml_basic_string, truncate_chars, PreparedTurn, TurnOutcome, TurnParser,
    TurnRequest, TurnSink, MAX_TOOL_OUTPUT,
};
use crate::util::{js_str, json_pretty, now_ms, rand_hex, summarize_tool_input, truncate};

/// Builds the CLI arguments for one turn and the parser for its output.
pub(crate) fn prepare_turn(req: TurnRequest) -> PreparedTurn {
    let TurnRequest {
        tab,
        prompt,
        cwd,
        permission_mode,
        system_prompt,
        ..
    } = req;

    let session = tab.get("sessionRef").cloned().filter(|v| !v.is_null());
    let resume_id = session
        .as_ref()
        .and_then(Value::as_object)
        .filter(|o| o.get("kind").and_then(Value::as_str) == Some("codex"))
        .and_then(|o| o.get("value").and_then(Value::as_str))
        .filter(|s| !s.is_empty())
        .map(str::to_string);
    let is_resume = resume_id.is_some();

    let mut args = if is_resume {
        vec![
            "exec".to_string(),
            "resume".to_string(),
            "--json".to_string(),
            "--skip-git-repo-check".to_string(),
        ]
    } else {
        vec![
            "exec".to_string(),
            "--json".to_string(),
            "--skip-git-repo-check".to_string(),
            "-C".to_string(),
            cwd.clone(),
        ]
    };

    if permission_mode == "full-access" {
        args.push("--dangerously-bypass-approvals-and-sandbox".to_string());
    } else if !is_resume {
        args.push("-s".to_string());
        args.push(if permission_mode == "plan" {
            "read-only".to_string()
        } else {
            "workspace-write".to_string()
        });
    }
    if let Some(model) = tab
        .get("model")
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty())
    {
        args.push("-m".to_string());
        args.push(model.to_string());
    }
    args.push("-c".to_string());
    args.push(format!(
        "developer_instructions={}",
        toml_basic_string(&system_prompt)
    ));
    if let Some(ref tid) = resume_id {
        args.push(tid.clone());
        args.push(prompt);
    } else {
        args.push(prompt);
    }

    let parser = CodexTurn {
        session_ref: session,
        usage: None,
        tool_starts: HashMap::new(),
    };
    PreparedTurn {
        args,
        status: "Codex is working…",
        parser: Box::new(parser),
    }
}

struct CodexTurn {
    session_ref: Option<Value>,
    usage: Option<Value>,
    tool_starts: HashMap<String, i64>,
}

impl TurnParser for CodexTurn {
    fn handle_line(&mut self, line: &str, sink: &TurnSink) {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            return;
        }
        let Ok(Value::Object(obj)) = serde_json::from_str::<Value>(trimmed) else {
            return;
        };
        let state = &mut *self;
        let ev_type = obj.get("type").and_then(Value::as_str).unwrap_or("");
        if ev_type == "thread.started" {
            if let Some(thread_id) = obj
                .get("thread_id")
                .and_then(Value::as_str)
                .filter(|s| !s.is_empty())
            {
                sink.persist_session_ref(&mut state.session_ref, "codex", thread_id);
            }
        } else if matches!(ev_type, "item.started" | "item.updated" | "item.completed") {
            if let Some(item) = obj.get("item").and_then(Value::as_object) {
                let item_id = item
                    .get("id")
                    .filter(|v| !v.is_null())
                    .map(js_str)
                    .unwrap_or_else(|| format!("item_{}", rand_hex(4)));
                let item_type = item.get("type").and_then(Value::as_str).unwrap_or("");
                let completed = ev_type == "item.completed";
                if item_type == "reasoning" && completed {
                    if let Some(t) = item.get("text").and_then(Value::as_str) {
                        let has_prev = sink
                            .message()
                            .get("thinking")
                            .and_then(Value::as_str)
                            .map(|s| !s.is_empty())
                            .unwrap_or(false);
                        let prefix = if has_prev { "\n" } else { "" };
                        sink.emit_delta(None, Some(format!("{prefix}{t}")), false);
                    }
                } else if item_type == "agent_message" && completed {
                    if let Some(t) = item.get("text").and_then(Value::as_str) {
                        let has_prev = sink
                            .message()
                            .get("text")
                            .and_then(Value::as_str)
                            .map(|s| !s.is_empty())
                            .unwrap_or(false);
                        let prefix = if has_prev { "\n\n" } else { "" };
                        sink.emit_delta(Some(format!("{prefix}{t}")), None, false);
                    }
                } else if item_type == "command_execution" {
                    let cmd = item
                        .get("command")
                        .filter(|v| !v.is_null())
                        .map(js_str)
                        .unwrap_or_else(|| "command".to_string());
                    let cmd_summary = summarize_tool_input(Some(&json!({ "command": cmd })));
                    if ev_type == "item.started" {
                        state.tool_starts.insert(item_id.clone(), now_ms());
                        sink.set_status_detail(&format!("$ {}", truncate(&cmd_summary, 50)));
                    }
                    let exit_code = item.get("exit_code").and_then(Value::as_i64);
                    let agg = item
                        .get("aggregated_output")
                        .and_then(Value::as_str)
                        .map(|s| truncate_chars(s, MAX_TOOL_OUTPUT));
                    let dur = if completed {
                        state.tool_starts.get(&item_id).map(|s| json!(now_ms() - s))
                    } else {
                        None
                    };
                    let status_str = if completed {
                        if exit_code.map(|c| c != 0).unwrap_or(false) {
                            "error"
                        } else {
                            "done"
                        }
                    } else {
                        "running"
                    };
                    sink.upsert_tool_call(json!({
                        "id": item_id,
                        "name": "Bash",
                        "inputSummary": cmd_summary,
                        "inputJson": json_pretty(&json!({ "command": cmd })),
                        "output": agg,
                        "status": status_str,
                        "durationMs": dur,
                    }));
                }
            }
        } else if ev_type == "turn.completed" {
            if let Some(u) = obj.get("usage").and_then(Value::as_object) {
                state.usage = Some(json!({
                    "inputTokens": num_or_null(u.get("input_tokens")),
                    "outputTokens": num_or_null(u.get("output_tokens")),
                    "thinkingTokens": Value::Null,
                    "costUsd": Value::Null,
                    "durationMs": Value::Null,
                }));
            }
        } else if ev_type == "error" {
            if let Some(err_msg) = obj.get("message").and_then(Value::as_str) {
                sink.message()
                    .as_object_mut()
                    .unwrap()
                    .insert("error".into(), json!(err_msg));
            }
        }
    }

    fn finish(self: Box<Self>) -> TurnOutcome {
        TurnOutcome {
            session_ref: self.session_ref,
            usage: self.usage,
            denied: Vec::new(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::harnesses::turn::test_request;

    #[test]
    fn new_session_arguments() {
        let turn = prepare_turn(test_request(json!({ "id": "tab_test" }), "plan", false));
        assert_eq!(
            turn.args,
            [
                "exec",
                "--json",
                "--skip-git-repo-check",
                "-C",
                "/work",
                "-s",
                "read-only",
                "-c",
                "developer_instructions=\"SYS\"",
                "hello",
            ]
        );
    }

    #[test]
    fn resume_arguments() {
        let tab = json!({
            "id": "tab_test",
            "model": "gpt-x",
            "sessionRef": { "kind": "codex", "value": "thread-1" },
        });
        let turn = prepare_turn(test_request(tab, "default", false));
        assert_eq!(
            turn.args,
            [
                "exec",
                "resume",
                "--json",
                "--skip-git-repo-check",
                "-m",
                "gpt-x",
                "-c",
                "developer_instructions=\"SYS\"",
                "thread-1",
                "hello",
            ]
        );
    }
}
