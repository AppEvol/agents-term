//! What a harness needs to run one turn: the request it builds CLI arguments from, the parser
//! that turns the CLI's output into chat updates, and the [`TurnSink`] it reports them to.

use serde_json::{json, Map, Value};
use std::collections::HashMap;
use std::sync::{Arc, Mutex, MutexGuard};

use crate::runner::AgentRunner as Runner;
use crate::util::{is_number, js_str, js_truthy, json_compact, json_pretty, summarize_tool_input};

/// Tool output longer than this many characters is truncated in the chat.
pub(crate) const MAX_TOOL_OUTPUT: usize = 12_000;

/// Everything a harness needs to build the CLI arguments for one turn.
#[derive(Clone)]
pub(crate) struct TurnRequest {
    /// The tab as stored (model, sessionRef, ...).
    pub tab: Value,
    /// The prompt to send, with attachments and any summary request already added.
    pub prompt: String,
    pub cwd: String,
    /// `"full-access"`, `"accept-edits"`, `"plan"` or the default mode.
    pub permission_mode: String,
    /// The tab's system prompt, including the daemon's standing instructions.
    pub system_prompt: String,
    /// True once after the tab's system prompt changed, for harnesses that only send it on new sessions.
    pub system_prompt_dirty: bool,
    /// Directory holding the tab's attached images, if any.
    pub attachments_dir: Option<String>,
}

/// A turn ready to spawn: CLI arguments (without the binary), the status shown while it runs,
/// and the parser for its output.
pub(crate) struct PreparedTurn {
    pub args: Vec<String>,
    pub status: &'static str,
    pub parser: Box<dyn TurnParser>,
}

/// What a finished turn hands back to the runner.
pub(crate) struct TurnOutcome {
    pub session_ref: Option<Value>,
    pub usage: Option<Value>,
    pub denied: Vec<Value>,
}

/// Parses a harness CLI's output, one line at a time.
pub(crate) trait TurnParser: Send {
    fn handle_line(&mut self, line: &str, sink: &TurnSink);
    fn finish(self: Box<Self>) -> TurnOutcome;
}

/// The chat message and tab a turn reports to.
pub(crate) struct TurnSink {
    runner: Runner,
    tab_id: String,
    message: Arc<Mutex<Value>>,
}

impl TurnSink {
    pub(crate) fn new(runner: Runner, tab_id: String, message: Arc<Mutex<Value>>) -> Self {
        Self {
            runner,
            tab_id,
            message,
        }
    }

    /// The assistant message being built.
    pub(crate) fn message(&self) -> MutexGuard<'_, Value> {
        self.message.lock().unwrap()
    }

    pub(crate) fn emit_delta(
        &self,
        text: Option<String>,
        thinking: Option<String>,
        bypass_filters: bool,
    ) {
        self.runner
            .emit_delta(&self.tab_id, &self.message, text, thinking, bypass_filters);
    }

    pub(crate) fn upsert_tool_call(&self, tool_call: Value) {
        self.runner
            .upsert_tool_call(&self.tab_id, &self.message, tool_call);
    }

    pub(crate) fn tool_use_started(
        &self,
        tool_starts: &mut HashMap<String, i64>,
        raw_id: Option<&Value>,
        raw_name: Option<&Value>,
        input: Option<&Value>,
    ) {
        self.runner.tool_use_started(
            &self.tab_id,
            &self.message,
            tool_starts,
            raw_id,
            raw_name,
            input,
        );
    }

    pub(crate) fn find_tool(&self, tool_id: &str) -> Option<Value> {
        Runner::find_tool(&self.message, tool_id)
    }

    /// Records the harness's session ID on the tab so the next turn resumes it.
    pub(crate) fn persist_session_ref(&self, current: &mut Option<Value>, kind: &str, value: &str) {
        self.runner
            .persist_session_ref(&self.tab_id, current, kind, value);
    }

    pub(crate) fn set_status_detail(&self, detail: &str) {
        self.runner.set_status_detail(&self.tab_id, detail);
    }

    pub(crate) fn running_status(&self, name: &str, summary: &str) {
        self.runner.running_status(&self.tab_id, name, summary);
    }
}

pub(crate) fn toml_basic_string(value: &str) -> String {
    serde_json::to_string(value).unwrap_or_else(|_| "\"\"".to_string())
}

pub(crate) fn num_or_null(v: Option<&Value>) -> Value {
    match v {
        Some(val) if is_number(val) => val.clone(),
        _ => Value::Null,
    }
}

pub(crate) fn tool_output_text(raw: Option<&Value>) -> Option<String> {
    let val = raw?;
    if val.is_null() {
        return None;
    }
    if let Some(s) = val.as_str() {
        return Some(s.to_string());
    }
    if let Some(arr) = val.as_array() {
        let parts: Vec<String> = arr
            .iter()
            .map(|x| {
                if let Some(t) = x.as_object().and_then(|o| o.get("text")) {
                    js_str(t)
                } else {
                    json_compact(x)
                }
            })
            .collect();
        return Some(parts.join("\n"));
    }
    Some(json_pretty(val))
}

pub(crate) fn denied_from(
    rec: &Map<String, Value>,
    default_action: &str,
    default_name: &str,
    use_alt_keys: bool,
) -> Value {
    let action = if use_alt_keys {
        rec.get("tool_name")
            .or_else(|| rec.get("action"))
            .filter(|v| !v.is_null())
            .map(js_str)
            .unwrap_or_else(|| default_action.to_string())
    } else {
        rec.get("tool_name")
            .filter(|v| !v.is_null())
            .map(js_str)
            .unwrap_or_else(|| default_action.to_string())
    };
    let display = if use_alt_keys {
        rec.get("tool_name")
            .or_else(|| rec.get("displayName"))
            .filter(|v| !v.is_null())
            .map(js_str)
            .unwrap_or_else(|| default_name.to_string())
    } else {
        rec.get("tool_name")
            .filter(|v| !v.is_null())
            .map(js_str)
            .unwrap_or_else(|| default_name.to_string())
    };
    let detail = rec
        .get("tool_input")
        .filter(|v| js_truthy(v))
        .map(|v| summarize_tool_input(Some(v)));
    json!({
        "action": action,
        "displayName": display,
        "detail": detail,
    })
}

pub(crate) fn truncate_chars(s: &str, max: usize) -> String {
    if s.chars().count() <= max {
        s.to_string()
    } else {
        s.chars().take(max).collect()
    }
}

/// A turn request for argument tests: tab `tab_test`, cwd `/work`, system prompt `SYS`.
#[cfg(test)]
pub(crate) fn test_request(tab: Value, permission_mode: &str, dirty: bool) -> TurnRequest {
    TurnRequest {
        tab,
        prompt: "hello".to_string(),
        cwd: "/work".to_string(),
        permission_mode: permission_mode.to_string(),
        system_prompt: "SYS".to_string(),
        system_prompt_dirty: dirty,
        attachments_dir: None,
    }
}
