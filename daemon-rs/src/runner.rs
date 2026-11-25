use regex::Regex;
use serde_json::{json, Map, Value};
use std::collections::{HashMap, HashSet};
use std::path::Path;
use std::process::Stdio;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;
use tokio::io::{AsyncRead, AsyncReadExt};
use tokio::process::Command;

use crate::config::{enriched_path, resolve_binary, WORK_DONE_SYSTEM_INSTRUCTION};
use crate::log::{log_error, log_info, log_warn};
use crate::store::{resolve_abs_path, StateStore, RESTART_INTERRUPTED_REASON};
use crate::util::{
    block_separator, is_finite_number, is_number, is_task_finished_response, js_round, js_str,
    js_truthy, json_compact, json_pretty, now_ms, pad_first_prompt_for_task_summary, rand_hex,
    summarize_task_prompt, summarize_tool_input, truncate, BlockBreaks, TaskSummaryStreamFilter,
    WorkDoneStreamFilter,
};

const CWD_MARKER: &str = "__AGENTSTERM_CWD__";
const MAX_TOOL_OUTPUT: usize = 12_000;

pub type BroadcastFn = Arc<dyn Fn(Value) + Send + Sync>;

fn model_cmd_re() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| Regex::new(r"(?i)^/model(?:\s+(.*))?$").unwrap())
}

fn model_reset_re() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| Regex::new(r"(?i)^(?:default|reset|auto)$").unwrap())
}

fn tab_system_prompt(tab: &Value) -> Option<String> {
    tab.get("systemPrompt")
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_string)
}

fn effective_system_prompt(tab: &Value) -> String {
    match tab_system_prompt(tab) {
        Some(sp) if sp.contains(WORK_DONE_SYSTEM_INSTRUCTION) => sp,
        Some(sp) => format!("{sp}\n\n{WORK_DONE_SYSTEM_INSTRUCTION}"),
        None => WORK_DONE_SYSTEM_INSTRUCTION.to_string(),
    }
}

fn toml_basic_string(value: &str) -> String {
    serde_json::to_string(value).unwrap_or_else(|_| "\"\"".to_string())
}

fn num_or_null(v: Option<&Value>) -> Value {
    match v {
        Some(val) if is_number(val) => val.clone(),
        _ => Value::Null,
    }
}

fn tool_output_text(raw: Option<&Value>) -> Option<String> {
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

fn denied_from(
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

fn truncate_chars(s: &str, max: usize) -> String {
    if s.chars().count() <= max {
        s.to_string()
    } else {
        s.chars().take(max).collect()
    }
}

fn signal_process_group(pid: u32, sig: libc::c_int) {
    let pgid = -(pid as libc::pid_t);
    unsafe {
        let _ = libc::kill(pgid, sig);
        let _ = libc::kill(pid as libc::pid_t, sig);
    }
}

struct ActiveTurn {
    assistant_msg_id: String,
    agent: String,
    pid: Option<u32>,
    interrupted: bool,
    interrupt_reason: Option<String>,
    drain_queue_on_exit: bool,
    kill_scheduled: bool,
    signal_on_spawn: Option<libc::c_int>,
    escalation_handle: Option<tokio::task::JoinHandle<()>>,
}

impl ActiveTurn {
    fn new(assistant_msg_id: String, agent: String) -> Self {
        Self {
            assistant_msg_id,
            agent,
            pid: None,
            interrupted: false,
            interrupt_reason: None,
            drain_queue_on_exit: false,
            kill_scheduled: false,
            signal_on_spawn: None,
            escalation_handle: None,
        }
    }

    fn send_signal(&mut self, sig: libc::c_int) {
        if let Some(pid) = self.pid {
            signal_process_group(pid, sig);
        } else {
            self.signal_on_spawn = Some(sig);
        }
    }

    fn cancel_escalation(&mut self) {
        if let Some(h) = self.escalation_handle.take() {
            h.abort();
        }
    }
}

#[derive(Clone)]
pub struct AgentRunner {
    store: StateStore,
    broadcast: BroadcastFn,
    active: Arc<Mutex<HashMap<String, Arc<Mutex<ActiveTurn>>>>>,
    agy_prompt_dirty: Arc<Mutex<HashSet<String>>>,
    summary_filters: Arc<Mutex<HashMap<String, TaskSummaryStreamFilter>>>,
    done_filters: Arc<Mutex<HashMap<String, WorkDoneStreamFilter>>>,
    shutting_down: Arc<AtomicBool>,
}

impl AgentRunner {
    pub fn new(store: StateStore, broadcast: BroadcastFn) -> Self {
        Self {
            store,
            broadcast,
            active: Arc::new(Mutex::new(HashMap::new())),
            agy_prompt_dirty: Arc::new(Mutex::new(HashSet::new())),
            summary_filters: Arc::new(Mutex::new(HashMap::new())),
            done_filters: Arc::new(Mutex::new(HashMap::new())),
            shutting_down: Arc::new(AtomicBool::new(false)),
        }
    }

    pub fn is_running(&self, tab_id: &str) -> bool {
        self.active.lock().unwrap().contains_key(tab_id)
    }

    pub fn mark_system_prompt_updated(&self, tab_id: &str) {
        self.agy_prompt_dirty
            .lock()
            .unwrap()
            .insert(tab_id.to_string());
    }

    pub fn shutdown_active_turns(&self, reason: &str) {
        self.shutting_down.store(true, Ordering::SeqCst);
        let entries: Vec<(String, Arc<Mutex<ActiveTurn>>)> = {
            let mut map = self.active.lock().unwrap();
            map.drain().collect()
        };
        if entries.is_empty() {
            return;
        }
        log_warn(&format!(
            "Stopping {} active turn(s) due to shutdown ({reason})",
            entries.len()
        ));
        for (tab_id, turn_arc) in entries {
            let mut turn = turn_arc.lock().unwrap();
            turn.cancel_escalation();
            let msg_id = turn.assistant_msg_id.clone();
            let pid = turn.pid;
            log_warn(&format!(
                "Stopping active turn tab={tab_id} msg={msg_id} agent={} pid={:?}",
                turn.agent, pid
            ));
            turn.interrupted = true;
            turn.interrupt_reason = Some(RESTART_INTERRUPTED_REASON.to_string());
            turn.send_signal(libc::SIGTERM);
            if let Some(p) = pid {
                std::thread::sleep(Duration::from_millis(100));
                signal_process_group(p, libc::SIGKILL);
            }
            // Persist explicit restart interruption reason before daemon exits (#61).
            let mut msgs = self.store.get_messages(&tab_id);
            if let Some(m) = msgs
                .iter_mut()
                .find(|m| m.get("id").and_then(Value::as_str) == Some(msg_id.as_str()))
            {
                if let Some(obj) = m.as_object_mut() {
                    let st = obj.get("status").and_then(Value::as_str).unwrap_or("");
                    if st == "streaming"
                        || (st == "interrupted"
                            && obj.get("error").map(Value::is_null).unwrap_or(true))
                    {
                        obj.insert("status".into(), json!("interrupted"));
                        obj.insert("error".into(), json!(RESTART_INTERRUPTED_REASON));
                        obj.insert("updatedAt".into(), json!(now_ms()));
                        self.store.replace_message(&tab_id, m.clone());
                    }
                }
            }
            let mut patch = Map::new();
            patch.insert("status".into(), json!("idle"));
            patch.insert("statusDetail".into(), Value::Null);
            let _ = self.store.update_tab(&tab_id, patch);
        }
    }

    pub fn submit_message(
        &self,
        tab: &Value,
        prompt_text: &str,
        override_permission_mode: Option<&str>,
    ) -> Value {
        let tab_id = tab.get("id").and_then(Value::as_str).unwrap_or("");
        if self.is_running(tab_id) {
            return self
                .enqueue_message(tab_id, prompt_text, override_permission_mode)
                .unwrap_or_else(|| json!({ "queued": false }));
        }
        let has_queued = tab
            .get("queuedMessages")
            .and_then(Value::as_array)
            .map(|a| !a.is_empty())
            .unwrap_or(false);
        if has_queued {
            self.enqueue_message(tab_id, prompt_text, override_permission_mode);
            if let Some(res) = self.drain_and_start_queued(tab_id) {
                return res;
            }
        }
        self.start_turn(tab, prompt_text, override_permission_mode)
    }

    pub fn enqueue_message(
        &self,
        tab_id: &str,
        prompt_text: &str,
        override_permission_mode: Option<&str>,
    ) -> Option<Value> {
        let (updated_tab, queued_item) =
            self.store
                .enqueue_message(tab_id, prompt_text, override_permission_mode)?;
        (self.broadcast)(json!({ "type": "tab_updated", "tab": updated_tab }));
        Some(json!({
            "queued": true,
            "queuedMessage": queued_item,
            "tab": updated_tab,
        }))
    }

    pub fn remove_queued_message(&self, tab_id: &str, queued_id: &str) -> Option<Value> {
        let updated = self.store.remove_queued_message(tab_id, queued_id)?;
        (self.broadcast)(json!({ "type": "tab_updated", "tab": updated }));
        Some(updated)
    }

    fn combine_queued_messages(items: &[Value]) -> (String, Option<String>) {
        let texts: Vec<&str> = items
            .iter()
            .filter_map(|m| m.get("text").and_then(Value::as_str).map(str::trim))
            .filter(|s| !s.is_empty())
            .collect();
        let overrides: Vec<&str> = items
            .iter()
            .filter_map(|m| {
                m.get("overridePermissionMode")
                    .and_then(Value::as_str)
                    .map(str::trim)
            })
            .filter(|s| !s.is_empty())
            .collect();
        let combined_override = if overrides.contains(&"full-access") {
            Some("full-access".to_string())
        } else {
            overrides.last().map(|s| (*s).to_string())
        };
        (texts.join("\n\n"), combined_override)
    }

    pub fn drain_and_start_queued(&self, tab_id: &str) -> Option<Value> {
        let (updated_tab, popped) = self.store.pop_queued_messages(tab_id)?;
        if popped.is_empty() {
            return None;
        }
        let (combined_text, combined_override) = Self::combine_queued_messages(&popped);
        if combined_text.is_empty() {
            (self.broadcast)(json!({ "type": "tab_updated", "tab": updated_tab }));
            return None;
        }
        Some(self.start_turn(&updated_tab, &combined_text, combined_override.as_deref()))
    }

    pub fn interrupt(
        &self,
        tab_id: &str,
        process_queue: bool,
        text: Option<&str>,
        override_permission_mode: Option<&str>,
    ) -> bool {
        if let Some(t) = text.map(str::trim).filter(|s| !s.is_empty()) {
            self.enqueue_message(tab_id, t, override_permission_mode);
        }

        let turn_arc = self.active.lock().unwrap().get(tab_id).cloned();
        let Some(turn_arc) = turn_arc else {
            if process_queue && self.drain_and_start_queued(tab_id).is_some() {
                return true;
            }
            let mut patch = Map::new();
            patch.insert("status".into(), json!("idle"));
            patch.insert("statusDetail".into(), Value::Null);
            if let Some(idle) = self.store.update_tab(tab_id, patch) {
                (self.broadcast)(json!({ "type": "tab_updated", "tab": idle }));
            }
            return false;
        };

        let has_queue = self
            .store
            .get_tab(tab_id)
            .and_then(|t| {
                t.get("queuedMessages")
                    .and_then(Value::as_array)
                    .map(|a| !a.is_empty())
            })
            .unwrap_or(false);

        let drain_queue;
        {
            let mut turn = turn_arc.lock().unwrap();
            turn.interrupted = true;
            if process_queue && has_queue {
                turn.drain_queue_on_exit = true;
            }
            drain_queue = turn.drain_queue_on_exit;

            log_info(&format!(
                "User interrupt requested for tab={tab_id} msg={} pid={:?} process_queue={drain_queue}",
                turn.assistant_msg_id, turn.pid
            ));

            if !turn.kill_scheduled {
                turn.kill_scheduled = true;
                turn.send_signal(libc::SIGINT);
                let weak_turn = Arc::downgrade(&turn_arc);
                let tid_log = tab_id.to_string();
                let handle = tokio::spawn(async move {
                    tokio::time::sleep(Duration::from_millis(1500)).await;
                    if let Some(t_arc) = weak_turn.upgrade() {
                        let mut t = t_arc.lock().unwrap();
                        if t.pid.is_some() {
                            log_warn(&format!(
                                "Escalating interrupt to SIGTERM for tab={tid_log} pid={:?}",
                                t.pid
                            ));
                            t.send_signal(libc::SIGTERM);
                        }
                    } else {
                        return;
                    }
                    tokio::time::sleep(Duration::from_millis(2000)).await;
                    if let Some(t_arc) = weak_turn.upgrade() {
                        let mut t = t_arc.lock().unwrap();
                        if t.pid.is_some() {
                            log_warn(&format!(
                                "Escalating interrupt to SIGKILL for tab={tid_log} pid={:?}",
                                t.pid
                            ));
                            t.send_signal(libc::SIGKILL);
                        }
                    }
                });
                turn.escalation_handle = Some(handle);
            }
        }

        // Keep tab in "working" (stopping) state until the child process group has actually exited (#52).
        let detail = if drain_queue {
            "Interrupting to run queued messages…"
        } else {
            "Stopping…"
        };
        let mut patch = Map::new();
        patch.insert("status".into(), json!("working"));
        patch.insert("statusDetail".into(), json!(detail));
        if let Some(stopping_tab) = self.store.update_tab(tab_id, patch) {
            (self.broadcast)(json!({ "type": "tab_updated", "tab": stopping_tab }));
        }
        true
    }

    pub fn kill_if_running(&self, tab_id: &str) {
        self.agy_prompt_dirty.lock().unwrap().remove(tab_id);
        let turn_arc = self.active.lock().unwrap().remove(tab_id);
        let Some(turn_arc) = turn_arc else {
            return;
        };
        let mut turn = turn_arc.lock().unwrap();
        self.summary_filters
            .lock()
            .unwrap()
            .remove(&turn.assistant_msg_id);
        self.done_filters
            .lock()
            .unwrap()
            .remove(&turn.assistant_msg_id);
        turn.interrupted = true;
        turn.cancel_escalation();
        turn.send_signal(libc::SIGKILL);
    }

    fn new_message(tab_id: &str, role: &str, text: &str, status: &str, ts: i64) -> Value {
        json!({
            "id": format!("msg_{}", rand_hex(6)),
            "tabId": tab_id,
            "role": role,
            "text": text,
            "thinking": Value::Null,
            "toolCalls": [],
            "segments": [],
            "deniedActions": [],
            "status": status,
            "error": Value::Null,
            "usage": Value::Null,
            "createdAt": ts,
            "updatedAt": ts,
        })
    }

    pub fn start_turn(
        &self,
        tab: &Value,
        prompt_text: &str,
        override_permission_mode: Option<&str>,
    ) -> Value {
        let tab_id = tab
            .get("id")
            .and_then(Value::as_str)
            .unwrap_or("")
            .to_string();
        let agent = tab
            .get("agent")
            .and_then(Value::as_str)
            .unwrap_or("shell")
            .to_string();

        let has_prior_task = self.store.get_messages(&tab_id).iter().any(|m| {
            m.get("role").and_then(Value::as_str) == Some("user")
                && m.get("text")
                    .and_then(Value::as_str)
                    .map(str::trim)
                    .map(|t| !t.is_empty() && !model_cmd_re().is_match(t))
                    .unwrap_or(false)
        });
        let is_first_task_prompt = !has_prior_task;

        let now = now_ms();
        let user_message = Self::new_message(&tab_id, "user", prompt_text, "complete", now);
        let assistant_message = Self::new_message(&tab_id, "assistant", "", "streaming", now + 1);
        self.store.add_message(&tab_id, user_message.clone());
        self.store.add_message(&tab_id, assistant_message.clone());

        if agent != "shell" {
            if let Some(caps) = model_cmd_re().captures(prompt_text.trim()) {
                let arg = caps.get(1).map(|m| m.as_str().trim()).unwrap_or("");
                return self.handle_model_command(tab, user_message, assistant_message, arg);
            }
        }

        let effective_mode = override_permission_mode
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .or_else(|| tab.get("permissionMode").and_then(Value::as_str))
            .unwrap_or("full-access")
            .to_string();

        let mut working_patch = Map::new();
        working_patch.insert("status".into(), json!("working"));
        working_patch.insert("statusDetail".into(), json!(format!("Starting {agent}…")));
        working_patch.insert(
            "lastMessagePreview".into(),
            json!(truncate(prompt_text, 120)),
        );
        working_patch.insert("unread".into(), json!(false));
        let has_summary = tab
            .get("taskSummary")
            .and_then(Value::as_str)
            .map(|s| !s.trim().is_empty())
            .unwrap_or(false);
        if !has_summary {
            if let Some(fallback) = summarize_task_prompt(prompt_text, 160) {
                working_patch.insert("taskSummary".into(), json!(fallback));
            }
        }
        let working_tab = self.store.update_tab(&tab_id, working_patch);
        (self.broadcast)(
            json!({ "type": "message_created", "tabId": tab_id, "message": user_message }),
        );
        (self.broadcast)(
            json!({ "type": "message_created", "tabId": tab_id, "message": assistant_message }),
        );
        if let Some(wt) = working_tab {
            (self.broadcast)(json!({ "type": "tab_updated", "tab": wt }));
        }

        let run_cwd = tab
            .get("cwd")
            .and_then(Value::as_str)
            .filter(|p| !p.is_empty() && Path::new(p).exists())
            .map(str::to_string)
            .unwrap_or_else(|| std::env::var("HOME").unwrap_or_else(|_| "/".to_string()));

        let response = json!({
            "userMessage": user_message,
            "assistantMessage": assistant_message.clone(),
        });

        let msg_id = assistant_message
            .get("id")
            .and_then(Value::as_str)
            .unwrap_or("")
            .to_string();

        let is_coding_agent = matches!(agent.as_str(), "agy" | "claude" | "codex");
        if is_coding_agent {
            self.done_filters
                .lock()
                .unwrap()
                .insert(msg_id.clone(), WorkDoneStreamFilter::new());
        }
        let cli_prompt = if is_coding_agent && is_first_task_prompt {
            self.summary_filters
                .lock()
                .unwrap()
                .insert(msg_id.clone(), TaskSummaryStreamFilter::new());
            pad_first_prompt_for_task_summary(prompt_text)
        } else {
            prompt_text.to_string()
        };

        log_info(&format!(
            "Turn started tab={tab_id} msg={msg_id} agent={agent} model={:?} cwd={run_cwd}",
            tab.get("model").and_then(Value::as_str)
        ));

        let msg_arc = Arc::new(Mutex::new(assistant_message));
        match agent.as_str() {
            "shell" => self.run_shell_turn(tab.clone(), msg_arc, prompt_text.to_string(), run_cwd),
            "agy" => self.run_agy_turn(tab.clone(), msg_arc, cli_prompt, run_cwd, effective_mode),
            "claude" => {
                self.run_claude_turn(tab.clone(), msg_arc, cli_prompt, run_cwd, effective_mode)
            }
            "codex" => {
                self.run_codex_turn(tab.clone(), msg_arc, cli_prompt, run_cwd, effective_mode)
            }
            other => {
                self.finish_turn_with_error(
                    &tab_id,
                    &msg_arc,
                    &format!("Unsupported agent type: {other}"),
                );
            }
        }
        response
    }

    fn handle_model_command(
        &self,
        tab: &Value,
        user_message: Value,
        mut assistant_message: Value,
        arg: &str,
    ) -> Value {
        let tab_id = tab.get("id").and_then(Value::as_str).unwrap_or("");
        let (updated_tab, reply) = if arg.is_empty() {
            let current = match tab.get("model").and_then(Value::as_str) {
                Some(m) if !m.is_empty() => format!("`{m}`"),
                _ => "CLI default".to_string(),
            };
            (
                Some(tab.clone()),
                format!(
                    "Current model for this tab: {current}. Use the model picker or send \
`/model <model-id>` (or `/model default`) to change it."
                ),
            )
        } else if model_reset_re().is_match(arg) {
            let mut patch = Map::new();
            patch.insert("model".into(), Value::Null);
            patch.insert("status".into(), json!("idle"));
            patch.insert("statusDetail".into(), Value::Null);
            patch.insert("lastMessagePreview".into(), json!("Model reset to default"));
            patch.insert("unread".into(), json!(false));
            (
                self.store.update_tab(tab_id, patch),
                "Model reset to CLI default for this tab.".to_string(),
            )
        } else {
            let mut patch = Map::new();
            patch.insert("model".into(), json!(arg));
            patch.insert("status".into(), json!("idle"));
            patch.insert("statusDetail".into(), Value::Null);
            patch.insert("lastMessagePreview".into(), json!(format!("Model: {arg}")));
            patch.insert("unread".into(), json!(false));
            (
                self.store.update_tab(tab_id, patch),
                format!("Model switched to `{arg}` for this tab. Subsequent messages will use this model."),
            )
        };

        if let Some(obj) = assistant_message.as_object_mut() {
            obj.insert("text".into(), json!(reply));
            obj.insert(
                "segments".into(),
                json!([{ "type": "text", "text": reply }]),
            );
            obj.insert("status".into(), json!("complete"));
            obj.insert("updatedAt".into(), json!(now_ms()));
        }
        self.store
            .replace_message(tab_id, assistant_message.clone());
        self.store.save_now();
        (self.broadcast)(
            json!({ "type": "message_created", "tabId": tab_id, "message": user_message }),
        );
        (self.broadcast)(
            json!({ "type": "message_created", "tabId": tab_id, "message": assistant_message }),
        );
        if let Some(ut) = updated_tab {
            (self.broadcast)(json!({ "type": "tab_updated", "tab": ut }));
        }
        json!({ "userMessage": user_message, "assistantMessage": assistant_message })
    }

    fn persist_session_ref(
        &self,
        tab_id: &str,
        current: &mut Option<Value>,
        kind: &str,
        value: &str,
    ) {
        if value.is_empty() {
            return;
        }
        let already_same = current
            .as_ref()
            .and_then(Value::as_object)
            .map(|o| {
                o.get("kind").and_then(Value::as_str) == Some(kind)
                    && o.get("value").and_then(Value::as_str) == Some(value)
            })
            .unwrap_or(false);
        if already_same {
            return;
        }
        let new_ref = json!({ "kind": kind, "value": value });
        *current = Some(new_ref.clone());
        // Persist sessionRef immediately on discovery so restarts mid-turn 1 never lose context (#62).
        let mut patch = Map::new();
        patch.insert("sessionRef".into(), new_ref);
        if let Some(updated_tab) = self.store.update_tab(tab_id, patch) {
            self.store.save_now();
            log_info(&format!(
                "Persisted sessionRef immediately for tab={tab_id} kind={kind} value={value}"
            ));
            (self.broadcast)(json!({ "type": "tab_updated", "tab": updated_tab }));
        }
    }

    fn emit_delta(
        &self,
        tab_id: &str,
        msg_arc: &Arc<Mutex<Value>>,
        text_delta: Option<String>,
        thinking_delta: Option<String>,
        bypass_filters: bool,
    ) {
        let msg_id = {
            let m = msg_arc.lock().unwrap();
            m.get("id")
                .and_then(Value::as_str)
                .unwrap_or("")
                .to_string()
        };

        let mut final_text = text_delta;
        if !bypass_filters {
            if let Some(raw_td) = final_text.as_deref() {
                if !raw_td.is_empty() {
                    let filtered = {
                        let mut filters = self.summary_filters.lock().unwrap();
                        filters.get_mut(&msg_id).map(|f| f.feed(raw_td))
                    };
                    if let Some((vis, extracted)) = filtered {
                        if let Some(summary) = extracted {
                            let mut patch = Map::new();
                            patch.insert("taskSummary".into(), json!(summary));
                            if let Some(updated_tab) = self.store.update_tab(tab_id, patch) {
                                (self.broadcast)(
                                    json!({ "type": "tab_updated", "tab": updated_tab }),
                                );
                            }
                        }
                        final_text = if vis.is_empty() { None } else { Some(vis) };
                    }
                }
            }
            if let Some(after_summary) = final_text.as_deref() {
                if !after_summary.is_empty() {
                    let done_filtered = {
                        let mut filters = self.done_filters.lock().unwrap();
                        filters.get_mut(&msg_id).map(|f| f.feed(after_summary))
                    };
                    if let Some(vis) = done_filtered {
                        final_text = if vis.is_empty() { None } else { Some(vis) };
                    }
                }
            }
        }

        let has_text = final_text
            .as_deref()
            .map(|s| !s.is_empty())
            .unwrap_or(false);
        let has_think = thinking_delta
            .as_deref()
            .map(|s| !s.is_empty())
            .unwrap_or(false);
        if !has_text && !has_think {
            return;
        }

        let snapshot = {
            let mut m = msg_arc.lock().unwrap();
            if let Some(obj) = m.as_object_mut() {
                if let Some(ref td) = final_text {
                    if !td.is_empty() {
                        let prev = obj.get("text").and_then(Value::as_str).unwrap_or("");
                        let new_text = format!("{prev}{td}");
                        obj.insert("text".into(), json!(new_text));
                        let segments = obj
                            .entry("segments")
                            .or_insert_with(|| Value::Array(Vec::new()))
                            .as_array_mut()
                            .unwrap();
                        let appended = if let Some(last) = segments.last_mut() {
                            if last.get("type").and_then(Value::as_str) == Some("text") {
                                if let Some(lo) = last.as_object_mut() {
                                    let prev_s =
                                        lo.get("text").and_then(Value::as_str).unwrap_or("");
                                    lo.insert("text".into(), json!(format!("{prev_s}{td}")));
                                }
                                true
                            } else {
                                false
                            }
                        } else {
                            false
                        };
                        if !appended {
                            let chunk = if !segments.is_empty() {
                                td.trim_start_matches(['\r', '\n'])
                            } else {
                                td.as_str()
                            };
                            if !chunk.is_empty() {
                                segments.push(json!({ "type": "text", "text": chunk }));
                            }
                        }
                    }
                }
                if let Some(ref th) = thinking_delta {
                    if !th.is_empty() {
                        let prev = obj.get("thinking").and_then(Value::as_str).unwrap_or("");
                        obj.insert("thinking".into(), json!(format!("{prev}{th}")));
                    }
                }
                obj.insert("updatedAt".into(), json!(now_ms()));
            }
            m.clone()
        };

        self.store.replace_message(tab_id, snapshot);
        let mut env = Map::new();
        env.insert("type".into(), json!("message_delta"));
        env.insert("tabId".into(), json!(tab_id));
        env.insert("messageId".into(), json!(msg_id));
        if let Some(td) = final_text.filter(|s| !s.is_empty()) {
            env.insert("textDelta".into(), json!(td));
        }
        if let Some(th) = thinking_delta.filter(|s| !s.is_empty()) {
            env.insert("thinkingDelta".into(), json!(th));
        }
        (self.broadcast)(Value::Object(env));
    }

    fn upsert_tool_call(&self, tab_id: &str, msg_arc: &Arc<Mutex<Value>>, tool_call: Value) {
        let tool_id = tool_call
            .get("id")
            .and_then(Value::as_str)
            .unwrap_or("")
            .to_string();
        let (msg_id, snapshot) = {
            let mut m = msg_arc.lock().unwrap();
            let obj = m.as_object_mut().unwrap();
            let msg_id = obj
                .get("id")
                .and_then(Value::as_str)
                .unwrap_or("")
                .to_string();
            {
                let calls = obj
                    .entry("toolCalls")
                    .or_insert_with(|| Value::Array(Vec::new()))
                    .as_array_mut()
                    .unwrap();
                if let Some(existing) = calls
                    .iter_mut()
                    .find(|t| t.get("id").and_then(Value::as_str) == Some(tool_id.as_str()))
                {
                    *existing = tool_call.clone();
                } else {
                    calls.push(tool_call.clone());
                }
            }
            {
                let segments = obj
                    .entry("segments")
                    .or_insert_with(|| Value::Array(Vec::new()))
                    .as_array_mut()
                    .unwrap();
                let exists = segments.iter().any(|s| {
                    s.get("type").and_then(Value::as_str) == Some("tool")
                        && s.get("toolCallId").and_then(Value::as_str) == Some(tool_id.as_str())
                });
                if !exists {
                    segments.push(json!({ "type": "tool", "toolCallId": tool_id }));
                }
            }
            obj.insert("updatedAt".into(), json!(now_ms()));
            (msg_id, m.clone())
        };
        self.store.replace_message(tab_id, snapshot);
        (self.broadcast)(json!({
            "type": "tool_update",
            "tabId": tab_id,
            "messageId": msg_id,
            "toolCall": tool_call,
        }));
    }

    fn find_tool(msg_arc: &Arc<Mutex<Value>>, tool_id: &str) -> Option<Value> {
        let m = msg_arc.lock().unwrap();
        m.get("toolCalls")
            .and_then(Value::as_array)
            .and_then(|arr| {
                arr.iter()
                    .find(|t| t.get("id").and_then(Value::as_str) == Some(tool_id))
                    .cloned()
            })
    }

    fn set_status_detail(&self, tab_id: &str, detail: &str) {
        let mut patch = Map::new();
        patch.insert("statusDetail".into(), json!(detail));
        if let Some(updated) = self.store.update_tab(tab_id, patch) {
            (self.broadcast)(json!({ "type": "tab_updated", "tab": updated }));
        }
    }

    fn running_status(&self, tab_id: &str, name: &str, summary: &str) {
        let detail = if summary.is_empty() {
            format!("Running {name}")
        } else {
            format!("Running {name}: {}", truncate(summary, 40))
        };
        self.set_status_detail(tab_id, &detail);
    }

    fn finish_turn_with_error(&self, tab_id: &str, msg_arc: &Arc<Mutex<Value>>, error_text: &str) {
        let (msg_id, final_msg) = {
            let mut m = msg_arc.lock().unwrap();
            let obj = m.as_object_mut().unwrap();
            let msg_id = obj
                .get("id")
                .and_then(Value::as_str)
                .unwrap_or("")
                .to_string();
            obj.insert("status".into(), json!("error"));
            obj.insert("error".into(), json!(error_text));
            obj.insert("updatedAt".into(), json!(now_ms()));
            (msg_id, m.clone())
        };
        self.summary_filters.lock().unwrap().remove(&msg_id);
        self.done_filters.lock().unwrap().remove(&msg_id);
        log_error(&format!(
            "Turn error tab={tab_id} msg={msg_id}: {error_text}"
        ));
        self.store.replace_message(tab_id, final_msg.clone());
        let mut patch = Map::new();
        patch.insert("status".into(), json!("error"));
        patch.insert("statusDetail".into(), Value::Null);
        patch.insert("unread".into(), json!(true));
        let updated_tab = self.store.update_tab(tab_id, patch);
        self.store.save_now();
        (self.broadcast)(
            json!({ "type": "message_updated", "tabId": tab_id, "message": final_msg }),
        );
        if let Some(ut) = updated_tab {
            (self.broadcast)(json!({ "type": "tab_updated", "tab": ut }));
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn finalize_turn(
        &self,
        tab_id: &str,
        msg_arc: &Arc<Mutex<Value>>,
        interrupted: bool,
        exit_code: Option<i32>,
        stderr_text: &str,
        duration_ms: i64,
        session_ref: Option<Value>,
        new_cwd: Option<String>,
        usage: Option<Value>,
        denied_actions: Vec<Value>,
    ) {
        let active_arc = self.active.lock().unwrap().remove(tab_id);
        let mut drain_queue_on_exit = false;
        let mut shutdown_reason: Option<String> = None;
        if let Some(ref a_arc) = active_arc {
            let mut a = a_arc.lock().unwrap();
            a.cancel_escalation();
            drain_queue_on_exit = a.drain_queue_on_exit;
            shutdown_reason = a.interrupt_reason.clone();
            if let Some(pid) = a.pid {
                if interrupted {
                    // Final sweep to ensure zero orphan grandchildren remain in the process group (#52).
                    signal_process_group(pid, libc::SIGKILL);
                }
            }
        }

        let msg_id = {
            let m = msg_arc.lock().unwrap();
            m.get("id")
                .and_then(Value::as_str)
                .unwrap_or("")
                .to_string()
        };
        let summary_leftover = self
            .summary_filters
            .lock()
            .unwrap()
            .remove(&msg_id)
            .map(|mut f| f.flush())
            .unwrap_or_default();
        let (leftover, work_done_detected) = {
            let done_filter = self.done_filters.lock().unwrap().remove(&msg_id);
            if let Some(mut f) = done_filter {
                let mut out = String::new();
                if !summary_leftover.is_empty() {
                    out.push_str(&f.feed(&summary_leftover));
                }
                out.push_str(&f.flush());
                (out, f.detected())
            } else {
                (summary_leftover, false)
            }
        };

        let Some(tab) = self.store.get_tab(tab_id) else {
            return;
        };
        if !leftover.is_empty() {
            self.emit_delta(tab_id, msg_arc, Some(leftover), None, true);
        }
        if work_done_detected {
            let mut m = msg_arc.lock().unwrap();
            if let Some(obj) = m.as_object_mut() {
                if let Some(trimmed) = obj
                    .get("text")
                    .and_then(Value::as_str)
                    .map(|s| s.trim_end().to_string())
                {
                    obj.insert("text".into(), json!(trimmed));
                }
                if let Some(segments) = obj.get_mut("segments").and_then(Value::as_array_mut) {
                    if let Some(last) = segments.last_mut() {
                        if last.get("type").and_then(Value::as_str) == Some("text") {
                            let trimmed = last
                                .get("text")
                                .and_then(Value::as_str)
                                .map(|s| s.trim_end().to_string())
                                .unwrap_or_default();
                            if trimmed.is_empty() {
                                segments.pop();
                            } else if let Some(lo) = last.as_object_mut() {
                                lo.insert("text".into(), json!(trimmed));
                            }
                        }
                    }
                }
            }
        }

        let final_usage = if let Some(mut u) = usage {
            if let Some(uo) = u.as_object_mut() {
                if uo.get("durationMs").map(Value::is_null).unwrap_or(true) {
                    uo.insert("durationMs".into(), json!(duration_ms));
                }
            }
            u
        } else {
            json!({
                "inputTokens": Value::Null,
                "outputTokens": Value::Null,
                "thinkingTokens": Value::Null,
                "costUsd": Value::Null,
                "durationMs": duration_ms,
            })
        };

        let (msg_text, existing_err, existing_denied) = {
            let m = msg_arc.lock().unwrap();
            (
                m.get("text")
                    .and_then(Value::as_str)
                    .unwrap_or("")
                    .to_string(),
                m.get("error").and_then(Value::as_str).map(str::to_string),
                m.get("deniedActions")
                    .and_then(Value::as_array)
                    .cloned()
                    .unwrap_or_default(),
            )
        };
        let denied = if !denied_actions.is_empty() {
            denied_actions
        } else {
            existing_denied
        };

        let failed = exit_code.map(|c| c != 0).unwrap_or(false);
        let mut final_status = "complete";
        let mut final_error = existing_err;
        let mut tab_status = "idle";
        let mut status_detail: Option<String> = None;

        if interrupted {
            final_status = "interrupted";
            tab_status = "idle";
            if final_error.is_none() {
                if let Some(reason) = shutdown_reason {
                    final_error = Some(reason);
                } else if self.shutting_down.load(Ordering::SeqCst) {
                    final_error = Some(RESTART_INTERRUPTED_REASON.to_string());
                }
            }
        } else if !denied.is_empty() {
            final_status = "complete";
            tab_status = "blocked";
            status_detail = Some("Waiting for approval".to_string());
        } else if failed && msg_text.trim().is_empty() {
            final_status = "error";
            final_error = Some(if !stderr_text.trim().is_empty() {
                stderr_text.trim().to_string()
            } else {
                format!("Process exited with code {}", exit_code.unwrap_or(-1))
            });
            tab_status = "error";
        } else if failed && !stderr_text.trim().is_empty() {
            final_error = Some(format!(
                "Exited with code {}: {}",
                exit_code.unwrap_or(-1),
                truncate(stderr_text.trim(), 240)
            ));
        } else if !failed
            && tab.get("agent").and_then(Value::as_str) != Some("shell")
            && (work_done_detected || is_task_finished_response(Some(&msg_text)))
        {
            tab_status = "done";
            status_detail = Some("Task finished".to_string());
        }

        let final_msg = {
            let mut m = msg_arc.lock().unwrap();
            let obj = m.as_object_mut().unwrap();
            obj.insert("deniedActions".into(), Value::Array(denied));
            obj.insert("status".into(), json!(final_status));
            obj.insert(
                "error".into(),
                final_error
                    .clone()
                    .map(Value::String)
                    .unwrap_or(Value::Null),
            );
            obj.insert("usage".into(), final_usage);
            obj.insert("updatedAt".into(), json!(now_ms()));
            m.clone()
        };

        log_info(&format!(
            "Turn finished tab={tab_id} msg={msg_id} status={final_status} tab_status={tab_status} exit_code={exit_code:?} interrupted={interrupted} duration_ms={duration_ms}"
        ));

        self.store.replace_message(tab_id, final_msg.clone());
        let mut patch = Map::new();
        patch.insert("status".into(), json!(tab_status));
        patch.insert(
            "statusDetail".into(),
            status_detail.map(Value::String).unwrap_or(Value::Null),
        );
        patch.insert("unread".into(), json!(true));
        if let Some(sref) = session_ref {
            patch.insert("sessionRef".into(), sref);
        }
        if let Some(cwd) = new_cwd {
            patch.insert("cwd".into(), json!(cwd));
        }
        if !msg_text.trim().is_empty() {
            patch.insert("lastMessagePreview".into(), json!(truncate(&msg_text, 120)));
        }
        let updated_tab = self.store.update_tab(tab_id, patch);
        self.store.save_now();

        (self.broadcast)(
            json!({ "type": "message_updated", "tabId": tab_id, "message": final_msg }),
        );
        if let Some(ref ut) = updated_tab {
            (self.broadcast)(json!({ "type": "tab_updated", "tab": ut }));
        }

        let has_queue = updated_tab
            .as_ref()
            .and_then(|t| t.get("queuedMessages").and_then(Value::as_array))
            .map(|a| !a.is_empty())
            .unwrap_or(false);
        let should_drain = (!interrupted && matches!(tab_status, "idle" | "done"))
            || (interrupted && drain_queue_on_exit);
        if should_drain && has_queue {
            self.drain_and_start_queued(tab_id);
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn spawn_process<FOut, FErr, FClose>(
        &self,
        tab_id: String,
        msg_arc: Arc<Mutex<Value>>,
        argv: Vec<String>,
        cwd: String,
        label: String,
        on_stdout: FOut,
        on_stderr: FErr,
        on_close: FClose,
    ) where
        FOut: FnMut(String) + Send + 'static,
        FErr: FnMut(String) + Send + 'static,
        FClose: FnOnce(Option<i32>, bool) + Send + 'static,
    {
        let msg_id = msg_arc
            .lock()
            .unwrap()
            .get("id")
            .and_then(Value::as_str)
            .unwrap_or("")
            .to_string();
        let active_arc = Arc::new(Mutex::new(ActiveTurn::new(msg_id, label.clone())));
        self.active
            .lock()
            .unwrap()
            .insert(tab_id.clone(), active_arc.clone());

        let runner = self.clone();
        tokio::spawn(async move {
            let mut cmd = Command::new(&argv[0]);
            if argv.len() > 1 {
                cmd.args(&argv[1..]);
            }
            cmd.current_dir(&cwd)
                .env("PATH", enriched_path())
                .env("FORCE_COLOR", "0")
                .env("NO_COLOR", "1")
                .env("TERM", "dumb")
                .stdin(Stdio::null())
                .stdout(Stdio::piped())
                .stderr(Stdio::piped());

            // Put child and all its descendants into a new session / process group so
            // interrupt can cleanly signal and kill the entire subtree (#52).
            unsafe {
                cmd.pre_exec(|| {
                    if libc::setsid() == -1 {
                        return Err(std::io::Error::last_os_error());
                    }
                    Ok(())
                });
            }

            let mut child = match cmd.spawn() {
                Ok(c) => c,
                Err(err) => {
                    {
                        let mut map = runner.active.lock().unwrap();
                        if map
                            .get(&tab_id)
                            .map(|a| Arc::ptr_eq(a, &active_arc))
                            .unwrap_or(false)
                        {
                            map.remove(&tab_id);
                        }
                    }
                    runner.finish_turn_with_error(
                        &tab_id,
                        &msg_arc,
                        &format!("Failed to start {label}: {err}"),
                    );
                    return;
                }
            };

            let child_pid = child.id();
            {
                let mut turn = active_arc.lock().unwrap();
                turn.pid = child_pid;
                if let Some(sig) = turn.signal_on_spawn.take() {
                    if let Some(pid) = child_pid {
                        signal_process_group(pid, sig);
                    }
                }
            }

            let stdout = child.stdout.take().unwrap();
            let stderr = child.stderr.take().unwrap();

            async fn pump_utf8<R: AsyncRead + Unpin, F: FnMut(String)>(mut reader: R, mut cb: F) {
                let mut buf = [0u8; 65_536];
                let mut pending = Vec::new();
                loop {
                    match reader.read(&mut buf).await {
                        Ok(0) => break,
                        Ok(n) => {
                            pending.extend_from_slice(&buf[..n]);
                            loop {
                                match std::str::from_utf8(&pending) {
                                    Ok(s) => {
                                        if !s.is_empty() {
                                            cb(s.to_string());
                                        }
                                        pending.clear();
                                        break;
                                    }
                                    Err(err) => {
                                        let valid_len = err.valid_up_to();
                                        if valid_len > 0 {
                                            let valid =
                                                String::from_utf8_lossy(&pending[..valid_len])
                                                    .into_owned();
                                            cb(valid);
                                            pending.drain(..valid_len);
                                        }
                                        if let Some(err_len) = err.error_len() {
                                            cb("\u{FFFD}".to_string());
                                            pending.drain(..err_len);
                                        } else {
                                            // Incomplete trailing multi-byte UTF-8 sequence; wait for more bytes.
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                        Err(_) => break,
                    }
                }
                if !pending.is_empty() {
                    cb(String::from_utf8_lossy(&pending).into_owned());
                }
            }

            let (_, _, status) = tokio::join!(
                pump_utf8(stdout, on_stdout),
                pump_utf8(stderr, on_stderr),
                child.wait()
            );

            let code = status.ok().and_then(|s| s.code());
            let interrupted = active_arc.lock().unwrap().interrupted;
            on_close(code, interrupted);
        });
    }

    #[allow(clippy::too_many_arguments)]
    fn spawn_json_lines<FLine, FFinish>(
        &self,
        tab_id: String,
        msg_arc: Arc<Mutex<Value>>,
        argv: Vec<String>,
        cwd: String,
        label: String,
        handle_line: FLine,
        finish: FFinish,
    ) where
        FLine: FnMut(&str) + Send + 'static,
        FFinish: FnOnce(Option<i32>, bool, String, i64) + Send + 'static,
    {
        let start = now_ms();
        let line_buf = Arc::new(Mutex::new(String::new()));
        let stderr_buf = Arc::new(Mutex::new(String::new()));
        let handler = Arc::new(Mutex::new(handle_line));

        let lb_out = line_buf.clone();
        let h_out = handler.clone();
        let on_stdout = move |chunk: String| {
            let mut lb = lb_out.lock().unwrap();
            lb.push_str(&chunk);
            let mut complete_lines = Vec::new();
            while let Some(idx) = lb.find('\n') {
                let mut line = lb[..idx].to_string();
                if line.ends_with('\r') {
                    line.pop();
                }
                complete_lines.push(line);
                lb.drain(..=idx);
            }
            drop(lb);
            let mut h = h_out.lock().unwrap();
            for line in complete_lines {
                h(&line);
            }
        };

        let sb_err = stderr_buf.clone();
        let on_stderr = move |chunk: String| {
            sb_err.lock().unwrap().push_str(&chunk);
        };

        let on_close = move |code: Option<i32>, interrupted: bool| {
            let tail = std::mem::take(&mut *line_buf.lock().unwrap());
            if !tail.trim().is_empty() {
                let mut h = handler.lock().unwrap();
                h(&tail);
            }
            let stderr = std::mem::take(&mut *stderr_buf.lock().unwrap());
            finish(code, interrupted, stderr, now_ms() - start);
        };

        self.spawn_process(
            tab_id, msg_arc, argv, cwd, label, on_stdout, on_stderr, on_close,
        );
    }

    // ------------------------------------------------------------------ shell

    fn run_shell_turn(&self, tab: Value, msg_arc: Arc<Mutex<Value>>, command: String, cwd: String) {
        let tab_id = tab
            .get("id")
            .and_then(Value::as_str)
            .unwrap_or("")
            .to_string();
        let start = now_ms();
        let bash = resolve_binary("bash").unwrap_or_else(|| "/bin/bash".to_string());
        let wrapped =
            format!("{command}\n__ec=$?\nprintf \"\\n{CWD_MARKER}%s\\n\" \"$PWD\"\nexit $__ec");
        self.set_status_detail(&tab_id, &format!("$ {}", truncate(&command, 60)));

        struct ShellState {
            new_cwd: Option<String>,
            raw: String,
            stderr: String,
            fence: bool,
            pending: String,
        }

        let st = Arc::new(Mutex::new(ShellState {
            new_cwd: None,
            raw: String::new(),
            stderr: String::new(),
            fence: false,
            pending: String::new(),
        }));

        let append_text = {
            let runner = self.clone();
            let tid = tab_id.clone();
            let ma = msg_arc.clone();
            move |state: &mut ShellState, chunk: &str| {
                if chunk.is_empty() {
                    return;
                }
                if !state.fence {
                    state.fence = true;
                    runner.emit_delta(&tid, &ma, Some(format!("```text\n{chunk}")), None, false);
                } else {
                    runner.emit_delta(&tid, &ma, Some(chunk.to_string()), None, false);
                }
            }
        };

        let detect_cwd = |state: &mut ShellState, candidate: &str| {
            let trimmed = candidate.trim();
            if !trimmed.is_empty() && Path::new(trimmed).exists() {
                state.new_cwd = Some(resolve_abs_path(trimmed));
            }
        };

        let st_out = st.clone();
        let append_out = append_text.clone();
        let marker_len = CWD_MARKER.len();
        let on_stdout = move |chunk: String| {
            let mut state = st_out.lock().unwrap();
            state.pending.push_str(&chunk);
            if let Some(idx) = state.pending.find(CWD_MARKER) {
                let before = state.pending[..idx].to_string();
                if !before.is_empty() {
                    state.raw.push_str(&before);
                    append_out(&mut state, &before);
                }
                let after = state.pending[idx + marker_len..].to_string();
                if let Some(nl) = after.find('\n') {
                    detect_cwd(&mut state, &after[..nl]);
                    state.pending = after[nl + 1..].to_string();
                } else {
                    state.pending = state.pending[idx..].to_string();
                }
            } else if state.pending.len() > marker_len + 2 {
                let mut flush_len = state.pending.len() - (marker_len + 2);
                while flush_len > 0 && !state.pending.is_char_boundary(flush_len) {
                    flush_len -= 1;
                }
                if flush_len > 0 {
                    let safe = state.pending[..flush_len].to_string();
                    state.pending = state.pending[flush_len..].to_string();
                    state.raw.push_str(&safe);
                    append_out(&mut state, &safe);
                }
            }
        };

        let st_err = st.clone();
        let append_err = append_text.clone();
        let on_stderr = move |chunk: String| {
            let mut state = st_err.lock().unwrap();
            state.stderr.push_str(&chunk);
            append_err(&mut state, &chunk);
        };

        let runner_close = self.clone();
        let tid_close = tab_id.clone();
        let msg_close = msg_arc.clone();
        let st_close = st.clone();
        let append_close = append_text;
        let on_close = move |code: Option<i32>, interrupted: bool| {
            let (stderr, new_cwd) = {
                let mut state = st_close.lock().unwrap();
                let pending = std::mem::take(&mut state.pending);
                if !pending.is_empty() && !pending.contains(CWD_MARKER) {
                    state.raw.push_str(&pending);
                    append_close(&mut state, &pending);
                } else if let Some(idx) = pending.find(CWD_MARKER) {
                    let before = &pending[..idx];
                    if !before.is_empty() {
                        append_close(&mut state, before);
                    }
                    detect_cwd(&mut state, &pending[idx + marker_len..]);
                }

                if state.fence {
                    let ends_nl = msg_close
                        .lock()
                        .unwrap()
                        .get("text")
                        .and_then(Value::as_str)
                        .map(|s| s.ends_with('\n'))
                        .unwrap_or(false);
                    runner_close.emit_delta(
                        &tid_close,
                        &msg_close,
                        Some(if ends_nl { "```" } else { "\n```" }.to_string()),
                        None,
                        false,
                    );
                } else if state.raw.trim().is_empty()
                    && state.stderr.trim().is_empty()
                    && code == Some(0)
                {
                    runner_close.emit_delta(
                        &tid_close,
                        &msg_close,
                        Some("_Command completed with no output._".to_string()),
                        None,
                        false,
                    );
                }
                (state.stderr.clone(), state.new_cwd.clone())
            };

            runner_close.finalize_turn(
                &tid_close,
                &msg_close,
                interrupted,
                code,
                &stderr,
                now_ms() - start,
                None,
                new_cwd,
                None,
                Vec::new(),
            );
        };

        self.spawn_process(
            tab_id,
            msg_arc,
            vec![bash, "-lc".to_string(), wrapped],
            cwd,
            "bash".to_string(),
            on_stdout,
            on_stderr,
            on_close,
        );
    }

    // ------------------------------------------------------------------ agy

    fn tool_use_started(
        &self,
        tab_id: &str,
        msg_arc: &Arc<Mutex<Value>>,
        tool_starts: &mut HashMap<String, i64>,
        raw_id: Option<&Value>,
        raw_name: Option<&Value>,
        input_obj: Option<&Value>,
    ) {
        let tool_id = raw_id
            .filter(|v| !v.is_null())
            .map(js_str)
            .unwrap_or_else(|| format!("tool_{}", rand_hex(4)));
        let name = raw_name
            .filter(|v| !v.is_null())
            .map(js_str)
            .unwrap_or_else(|| "Tool".to_string());
        tool_starts.insert(tool_id.clone(), now_ms());
        let summary = summarize_tool_input(input_obj);
        self.running_status(tab_id, &name, &summary);
        self.upsert_tool_call(
            tab_id,
            msg_arc,
            json!({
                "id": tool_id,
                "name": name,
                "inputSummary": summary,
                "inputJson": input_obj.filter(|v| !v.is_null()).map(json_pretty),
                "output": Value::Null,
                "status": "running",
                "durationMs": Value::Null,
            }),
        );
    }

    fn run_agy_turn(
        &self,
        tab: Value,
        msg_arc: Arc<Mutex<Value>>,
        prompt: String,
        cwd: String,
        permission_mode: String,
    ) {
        let tab_id = tab
            .get("id")
            .and_then(Value::as_str)
            .unwrap_or("")
            .to_string();
        let Some(binary) = resolve_binary("agy") else {
            self.finish_turn_with_error(
                &tab_id,
                &msg_arc,
                "'agy' binary not found in PATH on this VM.",
            );
            return;
        };

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
        let system_prompt = effective_system_prompt(&tab);
        let prompt_dirty = self.agy_prompt_dirty.lock().unwrap().remove(&tab_id);
        let effective_prompt = if !is_resume || prompt_dirty {
            format!("<system_instructions>\n{system_prompt}\n</system_instructions>\n\n{prompt}")
        } else {
            prompt
        };
        args.push(format!("--print={effective_prompt}"));

        self.set_status_detail(&tab_id, "Antigravity is thinking…");

        struct AgyState {
            session_ref: Option<Value>,
            usage: Option<Value>,
            saw_text: bool,
            text_step: Option<Value>,
            think_step: Option<Value>,
            denied: Vec<Value>,
            tool_starts: HashMap<String, i64>,
            breaks: BlockBreaks,
        }

        let st = Arc::new(Mutex::new(AgyState {
            session_ref: session,
            usage: None,
            saw_text: false,
            text_step: None,
            think_step: None,
            denied: Vec::new(),
            tool_starts: HashMap::new(),
            breaks: BlockBreaks::default(),
        }));

        let parse_agy_usage =
            |raw_usage: Option<&Value>, duration_seconds: Option<&Value>| -> Option<Value> {
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
            };

        let runner_line = self.clone();
        let tid_line = tab_id.clone();
        let msg_line = msg_arc.clone();
        let st_line = st.clone();

        let handle_line = move |line: &str| {
            let trimmed = line.trim();
            if trimmed.is_empty() {
                return;
            }
            let ev: Value = match serde_json::from_str(trimmed) {
                Ok(v) => v,
                Err(_) => {
                    let has_prev = !msg_line
                        .lock()
                        .unwrap()
                        .get("text")
                        .and_then(Value::as_str)
                        .unwrap_or("")
                        .is_empty();
                    let delta = if has_prev {
                        format!("\n{trimmed}")
                    } else {
                        trimmed.to_string()
                    };
                    runner_line.emit_delta(&tid_line, &msg_line, Some(delta), None, false);
                    return;
                }
            };
            let Some(obj) = ev.as_object() else {
                return;
            };

            let mut state = st_line.lock().unwrap();
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
                    runner_line.persist_session_ref(&tid_line, &mut state.session_ref, "agy", val);
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
                    runner_line.persist_session_ref(&tid_line, &mut state.session_ref, "agy", cid);
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
                        runner_line.persist_session_ref(
                            &tid_line,
                            &mut state.session_ref,
                            "agy",
                            cid,
                        );
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
                                    let existing = msg_line
                                        .lock()
                                        .unwrap()
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
                            runner_line.emit_delta(
                                &tid_line,
                                &msg_line,
                                None,
                                Some(format!("{sep}{td}")),
                                false,
                            );
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
                                    let existing = msg_line
                                        .lock()
                                        .unwrap()
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
                            runner_line.emit_delta(
                                &tid_line,
                                &msg_line,
                                Some(format!("{sep}{td}")),
                                None,
                                false,
                            );
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
                                    let existing = msg_line
                                        .lock()
                                        .unwrap()
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
                            runner_line.emit_delta(
                                &tid_line,
                                &msg_line,
                                None,
                                Some(format!("{sep}{th}")),
                                false,
                            );
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
                        let existing = Self::find_tool(&msg_line, &tool_id);

                        if matches!(step_state.as_str(), "ACTIVE" | "RUNNING" | "PENDING") {
                            state
                                .tool_starts
                                .entry(tool_id.clone())
                                .or_insert_with(now_ms);
                            runner_line.running_status(&tid_line, &name, &summary);
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
                                .or_else(|| {
                                    existing.as_ref().and_then(|e| e.get("inputJson").cloned())
                                })
                                .unwrap_or(Value::Null);
                            let output = existing
                                .as_ref()
                                .and_then(|e| e.get("output").cloned())
                                .unwrap_or(Value::Null);
                            runner_line.upsert_tool_call(
                                &tid_line,
                                &msg_line,
                                json!({
                                    "id": tool_id,
                                    "name": name,
                                    "inputSummary": input_summary,
                                    "inputJson": input_json,
                                    "output": output,
                                    "status": "running",
                                    "durationMs": Value::Null,
                                }),
                            );
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
                            runner_line.upsert_tool_call(
                                &tid_line,
                                &msg_line,
                                json!({
                                    "id": tool_id,
                                    "name": final_name,
                                    "inputSummary": final_summary,
                                    "inputJson": final_input_json,
                                    "output": out_text,
                                    "status": status_str,
                                    "durationMs": dur,
                                }),
                            );
                            runner_line.set_status_detail(&tid_line, "Antigravity is thinking…");
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
                        runner_line.persist_session_ref(
                            &tid_line,
                            &mut state.session_ref,
                            "agy",
                            cid,
                        );
                    }
                    let response_text = res.get("response").and_then(Value::as_str).unwrap_or("");
                    let cur_empty = msg_line
                        .lock()
                        .unwrap()
                        .get("text")
                        .and_then(Value::as_str)
                        .unwrap_or("")
                        .trim()
                        .is_empty();
                    if !response_text.trim().is_empty() && cur_empty {
                        state.saw_text = true;
                        runner_line.emit_delta(
                            &tid_line,
                            &msg_line,
                            Some(response_text.to_string()),
                            None,
                            false,
                        );
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
                        msg_line
                            .lock()
                            .unwrap()
                            .as_object_mut()
                            .unwrap()
                            .insert("error".into(), json!(err_s));
                    } else if matches!(res_status.as_str(), "ERROR" | "FAILED") {
                        let mut m = msg_line.lock().unwrap();
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
                    if let Some(u) = parse_agy_usage(res.get("usage"), res.get("duration_seconds"))
                    {
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
                    runner_line.emit_delta(
                        &tid_line,
                        &msg_line,
                        None,
                        Some(delta.to_string()),
                        false,
                    );
                } else {
                    state.saw_text = true;
                    runner_line.emit_delta(
                        &tid_line,
                        &msg_line,
                        Some(delta.to_string()),
                        None,
                        false,
                    );
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
                                        let existing = msg_line
                                            .lock()
                                            .unwrap()
                                            .get("text")
                                            .and_then(Value::as_str)
                                            .map(str::to_string);
                                        let sep = state.breaks.take_text(existing.as_deref());
                                        runner_line.emit_delta(
                                            &tid_line,
                                            &msg_line,
                                            Some(format!("{sep}{t}")),
                                            None,
                                            false,
                                        );
                                    }
                                }
                            } else if dtype == "thinking_delta" {
                                if let Some(t) = d
                                    .get("thinking")
                                    .and_then(Value::as_str)
                                    .filter(|s| !s.is_empty())
                                {
                                    let existing = msg_line
                                        .lock()
                                        .unwrap()
                                        .get("thinking")
                                        .and_then(Value::as_str)
                                        .map(str::to_string);
                                    let sep = state.breaks.take_thinking(existing.as_deref());
                                    runner_line.emit_delta(
                                        &tid_line,
                                        &msg_line,
                                        None,
                                        Some(format!("{sep}{t}")),
                                        false,
                                    );
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
                                    let existing = msg_line
                                        .lock()
                                        .unwrap()
                                        .get("thinking")
                                        .and_then(Value::as_str)
                                        .map(str::to_string);
                                    if !existing.as_deref().map(|e| e.contains(th)).unwrap_or(false)
                                    {
                                        let sep = block_separator(existing.as_deref());
                                        runner_line.emit_delta(
                                            &tid_line,
                                            &msg_line,
                                            None,
                                            Some(format!("{sep}{th}")),
                                            false,
                                        );
                                    }
                                }
                            }
                            Some("text") if !state.saw_text => {
                                if let Some(t) = b
                                    .get("text")
                                    .and_then(Value::as_str)
                                    .filter(|s| !s.is_empty())
                                {
                                    let existing = msg_line
                                        .lock()
                                        .unwrap()
                                        .get("text")
                                        .and_then(Value::as_str)
                                        .map(str::to_string);
                                    let sep = block_separator(existing.as_deref());
                                    runner_line.emit_delta(
                                        &tid_line,
                                        &msg_line,
                                        Some(format!("{sep}{t}")),
                                        None,
                                        false,
                                    );
                                }
                            }
                            Some("tool_use") => {
                                runner_line.tool_use_started(
                                    &tid_line,
                                    &msg_line,
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
                runner_line.tool_use_started(
                    &tid_line,
                    &msg_line,
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
                    let existing = Self::find_tool(&msg_line, &tool_id);
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
                    runner_line.upsert_tool_call(
                        &tid_line,
                        &msg_line,
                        json!({
                            "id": tool_id,
                            "name": name,
                            "inputSummary": input_summary,
                            "inputJson": input_json,
                            "output": out_text,
                            "status": if is_err { "error" } else { "done" },
                            "durationMs": dur,
                        }),
                    );
                }
                return;
            }

            if ev_type == "result" {
                if let Some(res_val) = obj.get("result").and_then(Value::as_str) {
                    let cur_empty = msg_line
                        .lock()
                        .unwrap()
                        .get("text")
                        .and_then(Value::as_str)
                        .unwrap_or("")
                        .trim()
                        .is_empty();
                    if !res_val.is_empty() && cur_empty {
                        runner_line.emit_delta(
                            &tid_line,
                            &msg_line,
                            Some(res_val.to_string()),
                            None,
                            false,
                        );
                    }
                    if obj.get("is_error").map(js_truthy).unwrap_or(false) {
                        msg_line
                            .lock()
                            .unwrap()
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
                runner_line.emit_delta(&tid_line, &msg_line, Some(t.to_string()), None, false);
            }
        };

        let runner_fin = self.clone();
        let tid_fin = tab_id.clone();
        let msg_fin = msg_arc.clone();
        let st_fin = st;
        let finish = move |code: Option<i32>, interrupted: bool, stderr: String, duration: i64| {
            let (sref, usage, denied) = {
                let s = st_fin.lock().unwrap();
                (s.session_ref.clone(), s.usage.clone(), s.denied.clone())
            };
            runner_fin.finalize_turn(
                &tid_fin,
                &msg_fin,
                interrupted,
                code,
                &stderr,
                duration,
                sref,
                None,
                usage,
                denied,
            );
        };

        let mut argv = vec![binary];
        argv.extend(args);
        self.spawn_json_lines(
            tab_id,
            msg_arc,
            argv,
            cwd,
            "agy".to_string(),
            handle_line,
            finish,
        );
    }

    // ------------------------------------------------------------------ claude

    fn run_claude_turn(
        &self,
        tab: Value,
        msg_arc: Arc<Mutex<Value>>,
        prompt: String,
        cwd: String,
        permission_mode: String,
    ) {
        let tab_id = tab
            .get("id")
            .and_then(Value::as_str)
            .unwrap_or("")
            .to_string();
        let Some(binary) = resolve_binary("claude") else {
            self.finish_turn_with_error(
                &tab_id,
                &msg_arc,
                "'claude' binary not found in PATH on this VM.",
            );
            return;
        };

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
        args.push(effective_system_prompt(&tab));

        self.set_status_detail(&tab_id, "Claude is thinking…");

        struct ClaudeState {
            session_ref: Option<Value>,
            usage: Option<Value>,
            saw_partial: bool,
            denied: Vec<Value>,
            tool_starts: HashMap<String, i64>,
            breaks: BlockBreaks,
        }

        let st = Arc::new(Mutex::new(ClaudeState {
            session_ref: session,
            usage: None,
            saw_partial: false,
            denied: Vec::new(),
            tool_starts: HashMap::new(),
            breaks: BlockBreaks::default(),
        }));

        let runner_line = self.clone();
        let tid_line = tab_id.clone();
        let msg_line = msg_arc.clone();
        let st_line = st.clone();

        let handle_line = move |line: &str| {
            let trimmed = line.trim();
            if trimmed.is_empty() {
                return;
            }
            let Ok(Value::Object(obj)) = serde_json::from_str::<Value>(trimmed) else {
                return;
            };
            let mut state = st_line.lock().unwrap();
            if let Some(sid) = obj
                .get("session_id")
                .and_then(Value::as_str)
                .filter(|s| !s.is_empty())
            {
                runner_line.persist_session_ref(&tid_line, &mut state.session_ref, "claude", sid);
            }

            let ev_type = obj.get("type").and_then(Value::as_str).unwrap_or("");
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
                                    state.saw_partial = true;
                                    if !t.is_empty() {
                                        let existing = msg_line
                                            .lock()
                                            .unwrap()
                                            .get("text")
                                            .and_then(Value::as_str)
                                            .map(str::to_string);
                                        let sep = state.breaks.take_text(existing.as_deref());
                                        runner_line.emit_delta(
                                            &tid_line,
                                            &msg_line,
                                            Some(format!("{sep}{t}")),
                                            None,
                                            false,
                                        );
                                    }
                                }
                            } else if dtype == "thinking_delta" {
                                if let Some(t) = d
                                    .get("thinking")
                                    .and_then(Value::as_str)
                                    .filter(|s| !s.is_empty())
                                {
                                    let existing = msg_line
                                        .lock()
                                        .unwrap()
                                        .get("thinking")
                                        .and_then(Value::as_str)
                                        .map(str::to_string);
                                    let sep = state.breaks.take_thinking(existing.as_deref());
                                    runner_line.emit_delta(
                                        &tid_line,
                                        &msg_line,
                                        None,
                                        Some(format!("{sep}{t}")),
                                        false,
                                    );
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
                                runner_line.tool_use_started(
                                    &tid_line,
                                    &msg_line,
                                    &mut state.tool_starts,
                                    b.get("id"),
                                    b.get("name"),
                                    b.get("input"),
                                );
                            }
                            Some("text") if !state.saw_partial => {
                                if let Some(t) = b
                                    .get("text")
                                    .and_then(Value::as_str)
                                    .filter(|s| !s.is_empty())
                                {
                                    let existing = msg_line
                                        .lock()
                                        .unwrap()
                                        .get("text")
                                        .and_then(Value::as_str)
                                        .map(str::to_string);
                                    let sep = block_separator(existing.as_deref());
                                    runner_line.emit_delta(
                                        &tid_line,
                                        &msg_line,
                                        Some(format!("{sep}{t}")),
                                        None,
                                        false,
                                    );
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
                                let existing = Self::find_tool(&msg_line, tool_id);
                                let out_text = tool_output_text(b.get("content"))
                                    .map(|s| truncate_chars(&s, MAX_TOOL_OUTPUT));
                                let dur =
                                    state.tool_starts.get(tool_id).map(|s| json!(now_ms() - s));
                                let is_err = b.get("is_error").map(js_truthy).unwrap_or(false);
                                runner_line.upsert_tool_call(
                                    &tid_line,
                                    &msg_line,
                                    json!({
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
                    let cur_empty = msg_line
                        .lock()
                        .unwrap()
                        .get("text")
                        .and_then(Value::as_str)
                        .unwrap_or("")
                        .trim()
                        .is_empty();
                    if !res_val.is_empty() && cur_empty {
                        runner_line.emit_delta(
                            &tid_line,
                            &msg_line,
                            Some(res_val.to_string()),
                            None,
                            false,
                        );
                    }
                    if obj.get("is_error").map(js_truthy).unwrap_or(false) {
                        msg_line
                            .lock()
                            .unwrap()
                            .as_object_mut()
                            .unwrap()
                            .insert("error".into(), json!(res_val));
                    }
                }
                if let Some(u) = obj.get("usage").and_then(Value::as_object) {
                    state.usage = Some(json!({
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
                            state.denied.push(denied_from(
                                dobj,
                                "tool",
                                "Permission Required",
                                false,
                            ));
                        }
                    }
                }
            }
        };

        let runner_fin = self.clone();
        let tid_fin = tab_id.clone();
        let msg_fin = msg_arc.clone();
        let st_fin = st;
        let finish = move |code: Option<i32>, interrupted: bool, stderr: String, duration: i64| {
            let (sref, usage, denied) = {
                let s = st_fin.lock().unwrap();
                (s.session_ref.clone(), s.usage.clone(), s.denied.clone())
            };
            runner_fin.finalize_turn(
                &tid_fin,
                &msg_fin,
                interrupted,
                code,
                &stderr,
                duration,
                sref,
                None,
                usage,
                denied,
            );
        };

        let mut argv = vec![binary];
        argv.extend(args);
        self.spawn_json_lines(
            tab_id,
            msg_arc,
            argv,
            cwd,
            "claude".to_string(),
            handle_line,
            finish,
        );
    }

    // ------------------------------------------------------------------ codex

    fn run_codex_turn(
        &self,
        tab: Value,
        msg_arc: Arc<Mutex<Value>>,
        prompt: String,
        cwd: String,
        permission_mode: String,
    ) {
        let tab_id = tab
            .get("id")
            .and_then(Value::as_str)
            .unwrap_or("")
            .to_string();
        let Some(binary) = resolve_binary("codex") else {
            self.finish_turn_with_error(
                &tab_id,
                &msg_arc,
                "'codex' binary not found in PATH on this VM.",
            );
            return;
        };

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
            toml_basic_string(&effective_system_prompt(&tab))
        ));
        if let Some(ref tid) = resume_id {
            args.push(tid.clone());
            args.push(prompt);
        } else {
            args.push(prompt);
        }

        self.set_status_detail(&tab_id, "Codex is working…");

        struct CodexState {
            session_ref: Option<Value>,
            usage: Option<Value>,
            tool_starts: HashMap<String, i64>,
        }

        let st = Arc::new(Mutex::new(CodexState {
            session_ref: session,
            usage: None,
            tool_starts: HashMap::new(),
        }));

        let runner_line = self.clone();
        let tid_line = tab_id.clone();
        let msg_line = msg_arc.clone();
        let st_line = st.clone();

        let handle_line = move |line: &str| {
            let trimmed = line.trim();
            if trimmed.is_empty() {
                return;
            }
            let Ok(Value::Object(obj)) = serde_json::from_str::<Value>(trimmed) else {
                return;
            };
            let mut state = st_line.lock().unwrap();
            let ev_type = obj.get("type").and_then(Value::as_str).unwrap_or("");
            if ev_type == "thread.started" {
                if let Some(thread_id) = obj
                    .get("thread_id")
                    .and_then(Value::as_str)
                    .filter(|s| !s.is_empty())
                {
                    runner_line.persist_session_ref(
                        &tid_line,
                        &mut state.session_ref,
                        "codex",
                        thread_id,
                    );
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
                            let has_prev = msg_line
                                .lock()
                                .unwrap()
                                .get("thinking")
                                .and_then(Value::as_str)
                                .map(|s| !s.is_empty())
                                .unwrap_or(false);
                            let prefix = if has_prev { "\n" } else { "" };
                            runner_line.emit_delta(
                                &tid_line,
                                &msg_line,
                                None,
                                Some(format!("{prefix}{t}")),
                                false,
                            );
                        }
                    } else if item_type == "agent_message" && completed {
                        if let Some(t) = item.get("text").and_then(Value::as_str) {
                            let has_prev = msg_line
                                .lock()
                                .unwrap()
                                .get("text")
                                .and_then(Value::as_str)
                                .map(|s| !s.is_empty())
                                .unwrap_or(false);
                            let prefix = if has_prev { "\n\n" } else { "" };
                            runner_line.emit_delta(
                                &tid_line,
                                &msg_line,
                                Some(format!("{prefix}{t}")),
                                None,
                                false,
                            );
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
                            runner_line.set_status_detail(
                                &tid_line,
                                &format!("$ {}", truncate(&cmd_summary, 50)),
                            );
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
                        runner_line.upsert_tool_call(
                            &tid_line,
                            &msg_line,
                            json!({
                                "id": item_id,
                                "name": "Bash",
                                "inputSummary": cmd_summary,
                                "inputJson": json_pretty(&json!({ "command": cmd })),
                                "output": agg,
                                "status": status_str,
                                "durationMs": dur,
                            }),
                        );
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
                    msg_line
                        .lock()
                        .unwrap()
                        .as_object_mut()
                        .unwrap()
                        .insert("error".into(), json!(err_msg));
                }
            }
        };

        let runner_fin = self.clone();
        let tid_fin = tab_id.clone();
        let msg_fin = msg_arc.clone();
        let st_fin = st;
        let finish = move |code: Option<i32>, interrupted: bool, stderr: String, duration: i64| {
            let (sref, usage) = {
                let s = st_fin.lock().unwrap();
                (s.session_ref.clone(), s.usage.clone())
            };
            runner_fin.finalize_turn(
                &tid_fin,
                &msg_fin,
                interrupted,
                code,
                &stderr,
                duration,
                sref,
                None,
                usage,
                Vec::new(),
            );
        };

        let mut argv = vec![binary];
        argv.extend(args);
        self.spawn_json_lines(
            tab_id,
            msg_arc,
            argv,
            cwd,
            "codex".to_string(),
            handle_line,
            finish,
        );
    }
}
