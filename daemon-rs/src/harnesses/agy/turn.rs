//! Turn execution for the Antigravity harness: CLI arguments and stream-json event parsing.

use serde_json::{json, Map, Value};
use std::collections::HashMap;

use crate::harnesses::turn::{
    denied_from, num_or_null, tool_output_text, truncate_chars, PreparedTurn, TurnOutcome,
    TurnParser, TurnRequest, TurnSink, MAX_TOOL_OUTPUT,
};
use crate::util::{
    block_separator, is_finite_number, is_number, js_round, js_str, js_truthy, json_pretty, now_ms,
    rand_hex, summarize_tool_input, BlockBreaks,
};

/// Builds the CLI arguments for one turn and the parser for its output.
pub(crate) fn prepare_turn(req: TurnRequest) -> PreparedTurn {
    let TurnRequest {
        tab,
        prompt,
        permission_mode,
        system_prompt,
        system_prompt_dirty,
        attachments_dir,
        ..
    } = req;

    let mut args = vec!["--output-format".to_string(), "stream-json".to_string()];
    match permission_mode.as_str() {
        "full-access" => args.push("--dangerously-skip-permissions".to_string()),
        "accept-edits" => {
            args.push("--mode".to_string());
            args.push("accept-edits".to_string());
        }
        "plan" => {
            args.push("--mode".to_string());
            args.push("plan".to_string());
        }
        _ => {
            args.push("--mode".to_string());
            args.push("auto".to_string());
        }
    }
    let session = tab.get("sessionRef").cloned().filter(|v| !v.is_null());
    let resume_val = session
        .as_ref()
        .and_then(Value::as_object)
        .filter(|o| o.get("kind").and_then(Value::as_str) == Some("agy"))
        .and_then(|o| o.get("value").and_then(Value::as_str))
        .filter(|s| !s.is_empty())
        .map(str::to_string);
    let is_resume = resume_val.is_some();
    if let Some(ref conv) = resume_val {
        args.push("--conversation".to_string());
        args.push(conv.clone());
    }
    if let Some(model) = tab
        .get("model")
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty())
    {
        args.push("--model".to_string());
        args.push(model.to_string());
    }
    let effective_prompt = if !is_resume || system_prompt_dirty {
        format!("<system_instructions>\n{system_prompt}\n</system_instructions>\n\n{prompt}")
    } else {
        prompt
    };
    if let Some(dir) = attachments_dir {
        args.push(format!("--add-dir={dir}"));
    }
    args.push(format!("--print={effective_prompt}"));

    let parser = AgyTurn {
        session_ref: session,
        usage: None,
        saw_text: false,
        text_step: None,
        think_step: None,
        denied: Vec::new(),
        tool_starts: HashMap::new(),
        breaks: BlockBreaks::default(),
    };
    PreparedTurn {
        args,
        status: "Antigravity is thinking…",
        parser: Box::new(parser),
    }
}

struct AgyTurn {
    session_ref: Option<Value>,
    usage: Option<Value>,
    saw_text: bool,
    text_step: Option<Value>,
    think_step: Option<Value>,
    denied: Vec<Value>,
    tool_starts: HashMap<String, i64>,
    breaks: BlockBreaks,
}

fn parse_agy_usage(raw_usage: Option<&Value>, duration_seconds: Option<&Value>) -> Option<Value> {
    let u = raw_usage
        .filter(|v| js_truthy(v))
        .and_then(Value::as_object)?;
    let dur = duration_seconds
        .and_then(is_finite_number)
        .map(|d| json!(js_round(d * 1000.0)))
        .or_else(|| u.get("duration_ms").filter(|v| is_number(v)).cloned())
        .unwrap_or(Value::Null);
    let cost = u
        .get("total_cost_usd")
        .filter(|v| is_number(v))
        .cloned()
        .unwrap_or_else(|| num_or_null(u.get("cost_usd")));
    Some(json!({
        "inputTokens": num_or_null(u.get("input_tokens")),
        "outputTokens": num_or_null(u.get("output_tokens")),
        "thinkingTokens": num_or_null(u.get("thinking_tokens")),
        "costUsd": cost,
        "durationMs": dur,
    }))
}

impl TurnParser for AgyTurn {
    fn handle_line(&mut self, line: &str, sink: &TurnSink) {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            return;
        }
        let ev: Value = match serde_json::from_str(trimmed) {
            Ok(v) => v,
            Err(_) => {
                let has_prev = !sink
                    .message()
                    .get("text")
                    .and_then(Value::as_str)
                    .unwrap_or("")
                    .is_empty();
                let delta = if has_prev {
                    format!("\n{trimmed}")
                } else {
                    trimmed.to_string()
                };
                sink.emit_delta(Some(delta), None, false);
                return;
            }
        };
        let Some(obj) = ev.as_object() else {
            return;
        };

        let state = &mut *self;
        for key in [
            "conversation_id",
            "conversationId",
            "session_id",
            "sessionId",
        ] {
            if let Some(val) = obj
                .get(key)
                .and_then(Value::as_str)
                .filter(|s| !s.is_empty())
            {
                sink.persist_session_ref(&mut state.session_ref, "agy", val);
                break;
            }
        }

        let agy_event = obj.get("event").and_then(Value::as_str).unwrap_or("");
        if agy_event == "init" {
            if let Some(cid) = obj
                .get("init")
                .and_then(Value::as_object)
                .and_then(|i| i.get("conversation_id"))
                .and_then(Value::as_str)
                .filter(|s| !s.is_empty())
            {
                sink.persist_session_ref(&mut state.session_ref, "agy", cid);
            }
            return;
        }
        if agy_event == "step_update" {
            if let Some(step) = obj.get("step_update").and_then(Value::as_object) {
                if let Some(cid) = step
                    .get("conversation_id")
                    .and_then(Value::as_str)
                    .filter(|s| !s.is_empty())
                {
                    sink.persist_session_ref(&mut state.session_ref, "agy", cid);
                }
                let step_type = step.get("step_type").and_then(Value::as_str).unwrap_or("");
                let step_state = step
                    .get("state")
                    .and_then(Value::as_str)
                    .unwrap_or("")
                    .to_ascii_uppercase();

                let step_idx = step.get("step_index").filter(|v| !v.is_null()).cloned();

                if step_type == "agent_response" {
                    if let Some(td) = step
                        .get("thinking_delta")
                        .and_then(Value::as_str)
                        .filter(|s| !s.is_empty())
                    {
                        let sep = if let Some(ref idx) = step_idx {
                            let prev = state.think_step.replace(idx.clone());
                            if prev.as_ref().map(|p| p != idx).unwrap_or(false) {
                                let existing = sink
                                    .message()
                                    .get("thinking")
                                    .and_then(Value::as_str)
                                    .map(str::to_string);
                                block_separator(existing.as_deref())
                            } else {
                                ""
                            }
                        } else {
                            ""
                        };
                        sink.emit_delta(None, Some(format!("{sep}{td}")), false);
                    }
                    let maybe_text = step
                        .get("text_delta")
                        .and_then(Value::as_str)
                        .filter(|s| !s.is_empty())
                        .or_else(|| {
                            if !state.saw_text {
                                step.get("text")
                                    .and_then(Value::as_str)
                                    .filter(|s| !s.is_empty())
                            } else {
                                None
                            }
                        });
                    if let Some(td) = maybe_text {
                        state.saw_text = true;
                        let sep = if let Some(ref idx) = step_idx {
                            let prev = state.text_step.replace(idx.clone());
                            if prev.as_ref().map(|p| p != idx).unwrap_or(false) {
                                let existing = sink
                                    .message()
                                    .get("text")
                                    .and_then(Value::as_str)
                                    .map(str::to_string);
                                block_separator(existing.as_deref())
                            } else {
                                ""
                            }
                        } else {
                            ""
                        };
                        sink.emit_delta(Some(format!("{sep}{td}")), None, false);
                    }
                    if let Some(u) =
                        parse_agy_usage(step.get("usage"), step.get("duration_seconds"))
                    {
                        state.usage = Some(u);
                    }
                    return;
                }

                if matches!(step_type, "thinking" | "thought" | "reasoning") {
                    let think_text =
                        ["thinking_delta", "text_delta", "text"]
                            .iter()
                            .find_map(|k| {
                                step.get(*k)
                                    .and_then(Value::as_str)
                                    .filter(|s| !s.is_empty())
                            });
                    if let Some(th) = think_text {
                        let sep = if let Some(ref idx) = step_idx {
                            let prev = state.think_step.replace(idx.clone());
                            if prev.as_ref().map(|p| p != idx).unwrap_or(false) {
                                let existing = sink
                                    .message()
                                    .get("thinking")
                                    .and_then(Value::as_str)
                                    .map(str::to_string);
                                block_separator(existing.as_deref())
                            } else {
                                ""
                            }
                        } else {
                            ""
                        };
                        sink.emit_delta(None, Some(format!("{sep}{th}")), false);
                    }
                    return;
                }

                if step_type == "tool" {
                    let empty_map = Map::new();
                    let tool_info = step
                        .get("tool_info")
                        .and_then(Value::as_object)
                        .unwrap_or(&empty_map);
                    let tool_id = step
                        .get("tool_call_id")
                        .or_else(|| step.get("tool_use_id"))
                        .or_else(|| tool_info.get("id"))
                        .filter(|v| !v.is_null())
                        .map(js_str)
                        .unwrap_or_else(|| match &step_idx {
                            Some(idx) => format!("agy_step_{}", js_str(idx)),
                            None => format!("tool_{}", rand_hex(4)),
                        });
                    let name = step
                        .get("tool_name")
                        .or_else(|| tool_info.get("name"))
                        .filter(|v| !v.is_null())
                        .map(js_str)
                        .unwrap_or_else(|| "Tool".to_string());
                    let params = tool_info
                        .get("parameters")
                        .or_else(|| tool_info.get("input"))
                        .or_else(|| tool_info.get("args"));
                    let summary = summarize_tool_input(params);
                    let existing = sink.find_tool(&tool_id);

                    if matches!(step_state.as_str(), "ACTIVE" | "RUNNING" | "PENDING") {
                        state
                            .tool_starts
                            .entry(tool_id.clone())
                            .or_insert_with(now_ms);
                        sink.running_status(&name, &summary);
                        let input_summary = if !summary.is_empty() {
                            summary
                        } else {
                            existing
                                .as_ref()
                                .and_then(|e| e.get("inputSummary").and_then(Value::as_str))
                                .unwrap_or("")
                                .to_string()
                        };
                        let input_json = params
                            .filter(|v| !v.is_null())
                            .map(|v| Value::String(json_pretty(v)))
                            .or_else(|| existing.as_ref().and_then(|e| e.get("inputJson").cloned()))
                            .unwrap_or(Value::Null);
                        let output = existing
                            .as_ref()
                            .and_then(|e| e.get("output").cloned())
                            .unwrap_or(Value::Null);
                        sink.upsert_tool_call(json!({
                            "id": tool_id,
                            "name": name,
                            "inputSummary": input_summary,
                            "inputJson": input_json,
                            "output": output,
                            "status": "running",
                            "durationMs": Value::Null,
                        }));
                    } else {
                        let raw_out = tool_info
                            .get("output")
                            .or_else(|| tool_info.get("result"))
                            .or_else(|| tool_info.get("error"))
                            .or_else(|| step.get("output"))
                            .or_else(|| step.get("error"));
                        let out_text = raw_out.and_then(|v| {
                            if v.is_null() {
                                None
                            } else if let Some(s) = v.as_str() {
                                Some(truncate_chars(s, MAX_TOOL_OUTPUT))
                            } else {
                                Some(truncate_chars(&json_pretty(v), MAX_TOOL_OUTPUT))
                            }
                        });
                        let is_denied = step_state == "DENIED"
                            || tool_info.get("denied").map(js_truthy).unwrap_or(false);
                        let is_err = !is_denied
                            && (matches!(step_state.as_str(), "ERROR" | "FAILED")
                                || tool_info.get("is_error").map(js_truthy).unwrap_or(false)
                                || tool_info.get("error").map(js_truthy).unwrap_or(false));
                        let dur = step
                            .get("duration_seconds")
                            .and_then(is_finite_number)
                            .map(|d| json!(js_round(d * 1000.0)))
                            .or_else(|| {
                                state.tool_starts.get(&tool_id).map(|s| json!(now_ms() - s))
                            })
                            .unwrap_or(Value::Null);
                        if is_denied {
                            state.denied.push(json!({
                                    "action": name,
                                    "displayName": name,
                                    "detail": if summary.is_empty() { Value::Null } else { json!(summary) },
                                }));
                        }
                        let final_name = existing
                            .as_ref()
                            .and_then(|e| e.get("name").and_then(Value::as_str))
                            .unwrap_or(&name)
                            .to_string();
                        let final_summary = existing
                            .as_ref()
                            .and_then(|e| e.get("inputSummary").and_then(Value::as_str))
                            .filter(|s| !s.is_empty())
                            .unwrap_or(&summary)
                            .to_string();
                        let final_input_json = existing
                            .as_ref()
                            .and_then(|e| e.get("inputJson").cloned())
                            .filter(|v| !v.is_null())
                            .or_else(|| {
                                params
                                    .filter(|v| !v.is_null())
                                    .map(|v| json!(json_pretty(v)))
                            })
                            .unwrap_or(Value::Null);
                        let status_str = if is_denied {
                            "denied"
                        } else if is_err {
                            "error"
                        } else {
                            "done"
                        };
                        sink.upsert_tool_call(json!({
                            "id": tool_id,
                            "name": final_name,
                            "inputSummary": final_summary,
                            "inputJson": final_input_json,
                            "output": out_text,
                            "status": status_str,
                            "durationMs": dur,
                        }));
                        sink.set_status_detail("Antigravity is thinking…");
                    }
                }
            }
            return;
        }
        if agy_event == "result" {
            if let Some(res) = obj.get("result").and_then(Value::as_object) {
                if let Some(cid) = res
                    .get("conversation_id")
                    .and_then(Value::as_str)
                    .filter(|s| !s.is_empty())
                {
                    sink.persist_session_ref(&mut state.session_ref, "agy", cid);
                }
                let response_text = res.get("response").and_then(Value::as_str).unwrap_or("");
                let cur_empty = sink
                    .message()
                    .get("text")
                    .and_then(Value::as_str)
                    .unwrap_or("")
                    .trim()
                    .is_empty();
                if !response_text.trim().is_empty() && cur_empty {
                    state.saw_text = true;
                    sink.emit_delta(Some(response_text.to_string()), None, false);
                }
                let res_status = res
                    .get("status")
                    .and_then(Value::as_str)
                    .unwrap_or("")
                    .to_ascii_uppercase();
                if let Some(err_s) = res
                    .get("error")
                    .and_then(Value::as_str)
                    .map(str::trim)
                    .filter(|s| !s.is_empty())
                {
                    sink.message()
                        .as_object_mut()
                        .unwrap()
                        .insert("error".into(), json!(err_s));
                } else if matches!(res_status.as_str(), "ERROR" | "FAILED") {
                    let mut m = sink.message();
                    if m.get("error").map(Value::is_null).unwrap_or(true) {
                        let msg_err = if !response_text.trim().is_empty() {
                            response_text.trim().to_string()
                        } else {
                            format!(
                                "Antigravity finished with status {}",
                                res.get("status").map(js_str).unwrap_or_default()
                            )
                        };
                        m.as_object_mut()
                            .unwrap()
                            .insert("error".into(), json!(msg_err));
                    }
                }
                if let Some(u) = parse_agy_usage(res.get("usage"), res.get("duration_seconds")) {
                    state.usage = Some(u);
                }
                if let Some(denials) = res.get("permission_denials").and_then(Value::as_array) {
                    for d in denials {
                        if let Some(dobj) = d.as_object() {
                            state.denied.push(denied_from(
                                dobj,
                                "permission",
                                "Permission Required",
                                true,
                            ));
                        }
                    }
                }
            }
            return;
        }

        let ev_type = obj.get("type").and_then(Value::as_str).unwrap_or("");
        if let Some(delta) = obj
            .get("delta")
            .and_then(Value::as_str)
            .filter(|s| !s.is_empty())
        {
            if ev_type.contains("think") || ev_type.contains("reason") {
                sink.emit_delta(None, Some(delta.to_string()), false);
            } else {
                state.saw_text = true;
                sink.emit_delta(Some(delta.to_string()), None, false);
            }
            return;
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
                    state.breaks.start(bt);
                } else if itype == "content_block_delta" {
                    if let Some(d) = inner.get("delta").and_then(Value::as_object) {
                        let dtype = d.get("type").and_then(Value::as_str).unwrap_or("");
                        if dtype == "text_delta" {
                            if let Some(t) = d.get("text").and_then(Value::as_str) {
                                state.saw_text = true;
                                if !t.is_empty() {
                                    let existing = sink
                                        .message()
                                        .get("text")
                                        .and_then(Value::as_str)
                                        .map(str::to_string);
                                    let sep = state.breaks.take_text(existing.as_deref());
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
                                let sep = state.breaks.take_thinking(existing.as_deref());
                                sink.emit_delta(None, Some(format!("{sep}{t}")), false);
                            }
                        }
                    }
                }
            }
            return;
        }

        if ev_type == "assistant" {
            if let Some(content) = obj
                .get("message")
                .and_then(Value::as_object)
                .and_then(|m| m.get("content"))
                .and_then(Value::as_array)
            {
                for block in content {
                    let Some(b) = block.as_object() else { continue };
                    match b.get("type").and_then(Value::as_str) {
                        Some("thinking") => {
                            if let Some(th) = b
                                .get("thinking")
                                .and_then(Value::as_str)
                                .filter(|s| !s.is_empty())
                            {
                                let existing = sink
                                    .message()
                                    .get("thinking")
                                    .and_then(Value::as_str)
                                    .map(str::to_string);
                                if !existing.as_deref().map(|e| e.contains(th)).unwrap_or(false) {
                                    let sep = block_separator(existing.as_deref());
                                    sink.emit_delta(None, Some(format!("{sep}{th}")), false);
                                }
                            }
                        }
                        Some("text") if !state.saw_text => {
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
                        Some("tool_use") => {
                            sink.tool_use_started(
                                &mut state.tool_starts,
                                b.get("id"),
                                b.get("name"),
                                b.get("input"),
                            );
                        }
                        _ => {}
                    }
                }
            }
            return;
        }

        if matches!(ev_type, "tool_use" | "tool_call" | "tool_start") {
            let input_obj = obj
                .get("input")
                .or_else(|| obj.get("args"))
                .or_else(|| obj.get("arguments"));
            sink.tool_use_started(
                &mut state.tool_starts,
                obj.get("id").or_else(|| obj.get("tool_use_id")),
                obj.get("name").or_else(|| obj.get("tool")),
                input_obj,
            );
            return;
        }

        if matches!(ev_type, "tool_result" | "tool_end" | "user") {
            let blocks: Vec<Value> = if ev_type == "user" {
                obj.get("message")
                    .and_then(Value::as_object)
                    .and_then(|m| m.get("content"))
                    .and_then(Value::as_array)
                    .cloned()
                    .unwrap_or_default()
            } else {
                vec![ev.clone()]
            };
            for raw_block in blocks {
                let Some(b) = raw_block.as_object() else {
                    continue;
                };
                let tool_id = b
                    .get("tool_use_id")
                    .or_else(|| b.get("id"))
                    .filter(|v| !v.is_null())
                    .map(js_str)
                    .unwrap_or_default();
                if tool_id.is_empty() {
                    continue;
                }
                let existing = sink.find_tool(&tool_id);
                let out_text = tool_output_text(
                    b.get("content")
                        .or_else(|| b.get("output"))
                        .or_else(|| b.get("result")),
                )
                .map(|s| truncate_chars(&s, MAX_TOOL_OUTPUT));
                let is_err = b.get("is_error").map(js_truthy).unwrap_or(false)
                    || b.get("error").map(js_truthy).unwrap_or(false);
                let dur = state.tool_starts.get(&tool_id).map(|s| json!(now_ms() - s));
                let name = existing
                    .as_ref()
                    .and_then(|e| e.get("name").and_then(Value::as_str))
                    .map(str::to_string)
                    .unwrap_or_else(|| {
                        b.get("name")
                            .map(js_str)
                            .unwrap_or_else(|| "Tool".to_string())
                    });
                let input_summary = existing
                    .as_ref()
                    .and_then(|e| e.get("inputSummary").and_then(Value::as_str))
                    .unwrap_or("")
                    .to_string();
                let input_json = existing
                    .as_ref()
                    .and_then(|e| e.get("inputJson").cloned())
                    .unwrap_or(Value::Null);
                sink.upsert_tool_call(json!({
                    "id": tool_id,
                    "name": name,
                    "inputSummary": input_summary,
                    "inputJson": input_json,
                    "output": out_text,
                    "status": if is_err { "error" } else { "done" },
                    "durationMs": dur,
                }));
            }
            return;
        }

        if ev_type == "result" {
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
                state.usage = Some(json!({
                    "inputTokens": num_or_null(u.get("input_tokens")),
                    "outputTokens": num_or_null(u.get("output_tokens")),
                    "thinkingTokens": num_or_null(u.get("thinking_tokens")),
                    "costUsd": num_or_null(obj.get("total_cost_usd")),
                    "durationMs": num_or_null(obj.get("duration_ms")),
                }));
            }
            if let Some(denials) = obj.get("permission_denials").and_then(Value::as_array) {
                for d in denials {
                    if let Some(dobj) = d.as_object() {
                        state.denied.push(denied_from(
                            dobj,
                            "permission",
                            "Permission Required",
                            true,
                        ));
                    }
                }
            }
            return;
        }

        if let Some(t) = obj
            .get("text")
            .and_then(Value::as_str)
            .filter(|s| !s.is_empty())
        {
            state.saw_text = true;
            sink.emit_delta(Some(t.to_string()), None, false);
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
    fn new_session_sends_system_prompt_in_the_print_argument() {
        let turn = prepare_turn(test_request(json!({ "id": "tab_test" }), "default", false));
        assert_eq!(
            turn.args,
            [
                "--output-format",
                "stream-json",
                "--mode",
                "auto",
                "--print=<system_instructions>\nSYS\n</system_instructions>\n\nhello",
            ]
        );
    }

    #[test]
    fn resume_passes_the_conversation_and_skips_an_unchanged_system_prompt() {
        let tab = json!({
            "id": "tab_test",
            "model": "gemini-x",
            "sessionRef": { "kind": "agy", "value": "conv-1" },
        });
        let turn = prepare_turn(test_request(tab.clone(), "plan", false));
        assert_eq!(
            turn.args,
            [
                "--output-format",
                "stream-json",
                "--mode",
                "plan",
                "--conversation",
                "conv-1",
                "--model",
                "gemini-x",
                "--print=hello",
            ]
        );
        let dirty = prepare_turn(test_request(tab, "full-access", true));
        assert_eq!(dirty.args[2], "--dangerously-skip-permissions");
        assert!(dirty
            .args
            .last()
            .unwrap()
            .starts_with("--print=<system_instructions>"));
    }
}
