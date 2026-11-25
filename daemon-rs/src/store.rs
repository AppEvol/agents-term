use serde_json::{json, Map, Value};
use std::collections::HashMap;
use std::fs;
use std::path::Path;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::sync::Notify;

use crate::config::{ensure_config_dir, home_dir, state_path, write_atomic_file};
use crate::log::{log_error, log_warn};
use crate::util::{derive_task_summary_from_messages, now_ms, rand_hex};

pub const RESTART_INTERRUPTED_REASON: &str = "Interrupted because the VM daemon restarted";
pub const MAX_OPEN_TABS: usize = 10;
const SAVE_DEBOUNCE: Duration = Duration::from_millis(250);

pub fn default_agent_label(agent: &str) -> &str {
    match agent {
        "agy" => "Antigravity",
        "claude" => "Claude",
        "codex" => "Codex",
        "shell" => "Shell",
        other => other,
    }
}

fn strip_or_none(v: Option<&Value>) -> Option<String> {
    v.and_then(Value::as_str)
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_string)
}

fn normalize_queued_messages(raw: Option<&Value>) -> Vec<Value> {
    let Some(arr) = raw.and_then(Value::as_array) else {
        return Vec::new();
    };
    let mut out = Vec::new();
    for item in arr {
        let Some(obj) = item.as_object() else {
            continue;
        };
        let Some(qid) = strip_or_none(obj.get("id")) else {
            continue;
        };
        let Some(text) = strip_or_none(obj.get("text")) else {
            continue;
        };
        let created = obj
            .get("createdAt")
            .and_then(|v| v.as_i64().or_else(|| v.as_f64().map(|f| f as i64)))
            .unwrap_or_else(now_ms);
        out.push(json!({
            "id": qid,
            "text": text,
            "overridePermissionMode": strip_or_none(obj.get("overridePermissionMode")),
            "createdAt": created,
        }));
    }
    out
}

pub fn resolve_abs_path(raw: &str) -> String {
    let p = Path::new(raw);
    let joined = if p.is_absolute() {
        p.to_path_buf()
    } else {
        std::env::current_dir()
            .unwrap_or_else(|_| home_dir())
            .join(p)
    };
    let mut normalized = std::path::PathBuf::new();
    for comp in joined.components() {
        match comp {
            std::path::Component::CurDir => {}
            std::path::Component::ParentDir => {
                normalized.pop();
            }
            other => normalized.push(other),
        }
    }
    if normalized.as_os_str().is_empty() {
        "/".to_string()
    } else {
        normalized.to_string_lossy().to_string()
    }
}

struct StoreInner {
    tabs: Vec<Value>,
    active_tab_id: Option<String>,
    messages_by_tab: HashMap<String, Vec<Value>>,
    dirty: bool,
}

#[derive(Clone)]
pub struct StateStore {
    inner: Arc<Mutex<StoreInner>>,
    save_notify: Arc<Notify>,
}

impl StateStore {
    pub fn new() -> Self {
        let store = Self {
            inner: Arc::new(Mutex::new(StoreInner {
                tabs: Vec::new(),
                active_tab_id: None,
                messages_by_tab: HashMap::new(),
                dirty: false,
            })),
            save_notify: Arc::new(Notify::new()),
        };
        let recovered = store.load();
        if recovered {
            store.save_now();
        }
        if let Ok(handle) = tokio::runtime::Handle::try_current() {
            let clone = store.clone();
            handle.spawn(async move {
                clone.save_loop().await;
            });
        }
        store
    }

    async fn save_loop(&self) {
        loop {
            self.save_notify.notified().await;
            tokio::time::sleep(SAVE_DEBOUNCE).await;
            let should_save = self.inner.lock().map(|g| g.dirty).unwrap_or(false);
            if should_save {
                self.save_now();
            }
        }
    }

    fn load(&self) -> bool {
        let path = state_path();
        if !path.exists() {
            return false;
        }
        let Ok(raw_text) = fs::read_to_string(&path) else {
            return false;
        };
        let Ok(Value::Object(raw)) = serde_json::from_str::<Value>(&raw_text) else {
            log_warn(&format!(
                "Ignoring malformed state file at {}",
                path.display()
            ));
            return false;
        };

        let now = now_ms();
        let mut recovered_any = false;
        let mut tabs: Vec<Value> = Vec::new();
        if let Some(arr) = raw.get("tabs").and_then(Value::as_array) {
            for item in arr {
                let Some(mut obj) = item.as_object().cloned() else {
                    continue;
                };
                let queued = normalize_queued_messages(obj.get("queuedMessages"));
                obj.insert("queuedMessages".into(), Value::Array(queued));
                if obj.get("status").and_then(Value::as_str) == Some("working") {
                    let tab_id = obj
                        .get("id")
                        .and_then(Value::as_str)
                        .unwrap_or("unknown")
                        .to_string();
                    log_warn(&format!(
                        "Startup recovery: resetting working tab {tab_id} to idle after daemon restart"
                    ));
                    obj.insert("status".into(), json!("idle"));
                    obj.insert("statusDetail".into(), Value::Null);
                    obj.insert("updatedAt".into(), json!(now));
                    recovered_any = true;
                }
                tabs.push(Value::Object(obj));
            }
        }

        let active_tab_id = raw
            .get("activeTabId")
            .and_then(Value::as_str)
            .filter(|id| {
                tabs.iter()
                    .any(|t| t.get("id").and_then(Value::as_str) == Some(*id))
            })
            .map(str::to_string)
            .or_else(|| {
                tabs.first()
                    .and_then(|t| t.get("id"))
                    .and_then(Value::as_str)
                    .map(str::to_string)
            });

        let mut messages_by_tab: HashMap<String, Vec<Value>> = HashMap::new();
        if let Some(map) = raw.get("messagesByTab").and_then(Value::as_object) {
            for (tab_id, val) in map {
                let Some(arr) = val.as_array() else {
                    continue;
                };
                let mut msgs = Vec::with_capacity(arr.len());
                for item in arr {
                    let Some(mut obj) = item.as_object().cloned() else {
                        continue;
                    };
                    if obj.get("status").and_then(Value::as_str) == Some("streaming") {
                        let msg_id = obj
                            .get("id")
                            .and_then(Value::as_str)
                            .unwrap_or("unknown")
                            .to_string();
                        let has_err = obj
                            .get("error")
                            .and_then(Value::as_str)
                            .map(|s| !s.trim().is_empty())
                            .unwrap_or(false);
                        obj.insert("status".into(), json!("interrupted"));
                        if !has_err {
                            obj.insert("error".into(), json!(RESTART_INTERRUPTED_REASON));
                        }
                        obj.insert("updatedAt".into(), json!(now));
                        log_warn(&format!(
                            "Startup recovery: marked streaming message {msg_id} in tab {tab_id} as interrupted ({RESTART_INTERRUPTED_REASON})"
                        ));
                        recovered_any = true;
                    }
                    msgs.push(Value::Object(obj));
                }
                messages_by_tab.insert(tab_id.clone(), msgs);
            }
        }

        for tab in &mut tabs {
            if let Some(obj) = tab.as_object_mut() {
                let existing = strip_or_none(obj.get("taskSummary"));
                let summary = existing.or_else(|| {
                    let tid = obj.get("id").and_then(Value::as_str).unwrap_or("");
                    messages_by_tab
                        .get(tid)
                        .and_then(|msgs| derive_task_summary_from_messages(msgs, 160))
                });
                obj.insert(
                    "taskSummary".into(),
                    summary.map(Value::String).unwrap_or(Value::Null),
                );
            }
        }

        let mut inner = self.inner.lock().unwrap();
        inner.tabs = tabs;
        inner.active_tab_id = active_tab_id;
        inner.messages_by_tab = messages_by_tab;
        recovered_any
    }

    pub fn schedule_save(&self) {
        if let Ok(mut inner) = self.inner.lock() {
            inner.dirty = true;
        }
        if tokio::runtime::Handle::try_current().is_ok() {
            self.save_notify.notify_one();
        } else {
            self.save_now();
        }
    }

    pub fn save_now(&self) {
        let snapshot = {
            let mut inner = self.inner.lock().unwrap();
            inner.dirty = false;
            json!({
                "tabs": inner.tabs,
                "activeTabId": inner.active_tab_id,
                "messagesByTab": inner.messages_by_tab,
            })
        };
        if let Err(err) = ensure_config_dir() {
            log_error(&format!("Failed to create config dir: {err}"));
            return;
        }
        match serde_json::to_string_pretty(&snapshot) {
            Ok(serialized) => {
                if let Err(err) = write_atomic_file(&state_path(), serialized.as_bytes(), 0o600) {
                    log_error(&format!("Failed to save state: {err}"));
                }
            }
            Err(err) => log_error(&format!("Failed to serialize state: {err}")),
        }
    }

    pub fn list_tabs(&self) -> Value {
        let inner = self.inner.lock().unwrap();
        let active = inner.active_tab_id.clone().or_else(|| {
            inner
                .tabs
                .first()
                .and_then(|t| t.get("id"))
                .and_then(Value::as_str)
                .map(str::to_string)
        });
        json!({
            "tabs": inner.tabs,
            "activeTabId": active,
        })
    }

    pub fn get_tab(&self, tab_id: &str) -> Option<Value> {
        let inner = self.inner.lock().unwrap();
        inner
            .tabs
            .iter()
            .find(|t| t.get("id").and_then(Value::as_str) == Some(tab_id))
            .cloned()
    }

    pub fn create_tab(
        &self,
        agent: &str,
        title: Option<&Value>,
        cwd: Option<&Value>,
        permission_mode: Option<&Value>,
        model: Option<&Value>,
        system_prompt: Option<&Value>,
    ) -> Option<Value> {
        let now = now_ms();
        let tab_id = format!("tab_{}", rand_hex(6));
        let raw_cwd =
            strip_or_none(cwd).unwrap_or_else(|| home_dir().to_string_lossy().to_string());
        let resolved_cwd = resolve_abs_path(&raw_cwd);
        let folder = Path::new(&resolved_cwd)
            .file_name()
            .and_then(|s| s.to_str())
            .filter(|s| !s.is_empty())
            .unwrap_or(&resolved_cwd)
            .to_string();
        let resolved_title = strip_or_none(title)
            .unwrap_or_else(|| format!("{} · {}", default_agent_label(agent), folder));
        let resolved_perm =
            strip_or_none(permission_mode).unwrap_or_else(|| "full-access".to_string());
        let resolved_model = strip_or_none(model);
        let resolved_sp = if agent == "shell" {
            None
        } else {
            strip_or_none(system_prompt)
        };

        let tab = json!({
            "id": tab_id,
            "title": resolved_title,
            "agent": agent,
            "cwd": resolved_cwd,
            "permissionMode": resolved_perm,
            "model": resolved_model,
            "systemPrompt": resolved_sp,
            "status": "idle",
            "statusDetail": Value::Null,
            "sessionRef": Value::Null,
            "unread": false,
            "lastMessagePreview": Value::Null,
            "taskSummary": Value::Null,
            "queuedMessages": [],
            "createdAt": now,
            "updatedAt": now,
        });

        {
            let mut inner = self.inner.lock().unwrap();
            if inner.tabs.len() >= MAX_OPEN_TABS {
                return None;
            }
            inner.tabs.push(tab.clone());
            inner.messages_by_tab.insert(tab_id.clone(), Vec::new());
            inner.active_tab_id = Some(tab_id);
        }
        self.schedule_save();
        Some(tab)
    }

    pub fn update_tab(&self, tab_id: &str, patch: Map<String, Value>) -> Option<Value> {
        let updated = {
            let mut inner = self.inner.lock().unwrap();
            let tab = inner
                .tabs
                .iter_mut()
                .find(|t| t.get("id").and_then(Value::as_str) == Some(tab_id))?;
            let obj = tab.as_object_mut()?;
            for (k, v) in patch {
                obj.insert(k, v);
            }
            obj.insert("id".into(), json!(tab_id));
            obj.insert("updatedAt".into(), json!(now_ms()));
            tab.clone()
        };
        self.schedule_save();
        Some(updated)
    }

    pub fn enqueue_message(
        &self,
        tab_id: &str,
        text: &str,
        override_permission_mode: Option<&str>,
    ) -> Option<(Value, Value)> {
        let tab = self.get_tab(tab_id)?;
        let item = json!({
            "id": format!("q_{}", rand_hex(6)),
            "text": text,
            "overridePermissionMode": override_permission_mode.map(str::trim).filter(|s| !s.is_empty()),
            "createdAt": now_ms(),
        });
        let mut queued = tab
            .get("queuedMessages")
            .and_then(Value::as_array)
            .cloned()
            .unwrap_or_default();
        queued.push(item.clone());
        let mut patch = Map::new();
        patch.insert("queuedMessages".into(), Value::Array(queued));
        let updated = self.update_tab(tab_id, patch)?;
        Some((updated, item))
    }

    pub fn remove_queued_message(&self, tab_id: &str, queued_id: &str) -> Option<Value> {
        let tab = self.get_tab(tab_id)?;
        let queued: Vec<Value> = tab
            .get("queuedMessages")
            .and_then(Value::as_array)
            .cloned()
            .unwrap_or_default()
            .into_iter()
            .filter(|m| m.get("id").and_then(Value::as_str) != Some(queued_id))
            .collect();
        let mut patch = Map::new();
        patch.insert("queuedMessages".into(), Value::Array(queued));
        self.update_tab(tab_id, patch)
    }

    pub fn pop_queued_messages(&self, tab_id: &str) -> Option<(Value, Vec<Value>)> {
        let tab = self.get_tab(tab_id)?;
        let popped = tab
            .get("queuedMessages")
            .and_then(Value::as_array)
            .cloned()
            .unwrap_or_default();
        if popped.is_empty() {
            return Some((tab, Vec::new()));
        }
        let mut patch = Map::new();
        patch.insert("queuedMessages".into(), Value::Array(Vec::new()));
        let updated = self.update_tab(tab_id, patch)?;
        Some((updated, popped))
    }

    pub fn delete_tab(&self, tab_id: &str) -> bool {
        let removed = {
            let mut inner = self.inner.lock().unwrap();
            let before = inner.tabs.len();
            inner
                .tabs
                .retain(|t| t.get("id").and_then(Value::as_str) != Some(tab_id));
            inner.messages_by_tab.remove(tab_id);
            if inner.active_tab_id.as_deref() == Some(tab_id) {
                inner.active_tab_id = inner
                    .tabs
                    .last()
                    .and_then(|t| t.get("id"))
                    .and_then(Value::as_str)
                    .map(str::to_string);
            }
            inner.tabs.len() != before
        };
        if removed {
            self.schedule_save();
        }
        removed
    }

    pub fn get_messages(&self, tab_id: &str) -> Vec<Value> {
        let inner = self.inner.lock().unwrap();
        inner
            .messages_by_tab
            .get(tab_id)
            .cloned()
            .unwrap_or_default()
    }

    pub fn add_message(&self, tab_id: &str, msg: Value) {
        {
            let mut inner = self.inner.lock().unwrap();
            inner
                .messages_by_tab
                .entry(tab_id.to_string())
                .or_default()
                .push(msg);
        }
        self.schedule_save();
    }

    pub fn replace_message(&self, tab_id: &str, updated: Value) {
        let target_id = updated
            .get("id")
            .and_then(Value::as_str)
            .map(str::to_string);
        {
            let mut inner = self.inner.lock().unwrap();
            let msgs = inner.messages_by_tab.entry(tab_id.to_string()).or_default();
            if let Some(ref tid) = target_id {
                if let Some(existing) = msgs
                    .iter_mut()
                    .find(|m| m.get("id").and_then(Value::as_str) == Some(tid.as_str()))
                {
                    *existing = updated;
                } else {
                    msgs.push(updated);
                }
            } else {
                msgs.push(updated);
            }
        }
        self.schedule_save();
    }
}
