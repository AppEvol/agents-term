use axum::extract::ws::{Message, WebSocket, WebSocketUpgrade};
use axum::extract::{DefaultBodyLimit, Path as AxumPath, Query, State};
use axum::http::{HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{delete, get, patch, post};
use axum::{Json, Router};
use base64::Engine;
use chrono::{TimeZone, Utc};
use futures_util::{SinkExt, StreamExt};
use regex::Regex;
use serde_json::{json, Map, Value};
use std::collections::HashMap;
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::net::SocketAddr;
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::path::Path;
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;
use tokio::net::TcpListener;
use tokio::sync::mpsc;

use crate::app_state::{AppStateBroker, DEFAULT_COMMAND_TIMEOUT_MS};
use crate::attachments::{
    find_attachment, referenced_attachment_ids, resolve_attachments, stage_attachment,
    sweep_stale_uploads, StageError, MAX_ATTACHMENT_BYTES,
};
use crate::config::{
    build_server_info, daemon_commit, debug_dumps_root, ensure_config_dir, ensure_debugging_docs,
    ensure_dir_0700, home_dir, next_atomic_seq, SERVER_VERSION,
};
use crate::log::{log_info, log_warn};
use crate::models::{get_agent_models, warm_agent_models_cache};
use crate::runner::AgentRunner;
use crate::store::{resolve_abs_path, ReviveTabError, StateStore, MAX_OPEN_TABS};
use crate::usage::get_agent_usage;
use crate::util::{now_ms, rand_hex};

const PING_INTERVAL: Duration = Duration::from_secs(25);
const MAX_DEBUG_DUMP_BODY: usize = 10 * 1024 * 1024;

fn dump_segment_re() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| Regex::new(r"[^A-Za-z0-9._-]+").unwrap())
}

fn sanitize_dump_segment(raw: Option<&Value>) -> String {
    let s = raw.and_then(Value::as_str).unwrap_or("").trim();
    let cleaned = dump_segment_re().replace_all(s, "_");
    let trimmed = cleaned.trim_matches('_');
    let truncated: String = trimmed.chars().take(64).collect();
    if truncated.is_empty() {
        rand_hex(4)
    } else {
        truncated
    }
}

fn utc_stamp(epoch_ms: i64) -> String {
    Utc.timestamp_millis_opt(epoch_ms)
        .single()
        .unwrap_or_else(Utc::now)
        .format("%Y%m%d-%H%M%SZ")
        .to_string()
}

fn iso_ms(epoch_ms: i64) -> String {
    Utc.timestamp_millis_opt(epoch_ms)
        .single()
        .unwrap_or_else(Utc::now)
        .format("%Y-%m-%dT%H:%M:%S%.3fZ")
        .to_string()
}

fn nonblank(v: Option<&Value>) -> bool {
    v.and_then(Value::as_str)
        .map(|s| !s.trim().is_empty())
        .unwrap_or(false)
}

pub fn save_debug_dump(payload: &Map<String, Value>) -> Value {
    let _ = ensure_config_dir();
    let root = debug_dumps_root();
    let _ = ensure_dir_0700(&root);

    let created_at = payload
        .get("createdAt")
        .and_then(|v| {
            v.as_i64()
                .or_else(|| v.as_f64().filter(|f| f.is_finite()).map(|f| f as i64))
        })
        .unwrap_or_else(now_ms);
    let safe_id = sanitize_dump_segment(payload.get("dumpId"));
    let dir_name = format!("{}_{safe_id}", utc_stamp(created_at));
    let remote_dir = root.join(&dir_name);
    let _ = fs::create_dir_all(&remote_dir);
    let _ = fs::set_permissions(&remote_dir, fs::Permissions::from_mode(0o700));

    let mut files: Vec<String> = Vec::new();
    let write_file = |files: &mut Vec<String>, name: &str, bytes: &[u8]| {
        if let Ok(mut f) = OpenOptions::new()
            .write(true)
            .create(true)
            .truncate(true)
            .mode(0o600)
            .open(remote_dir.join(name))
        {
            let _ = f.write_all(bytes);
            files.push(name.to_string());
        }
    };

    let events_ndjson = payload
        .get("eventsNdjson")
        .and_then(Value::as_str)
        .unwrap_or("");
    let event_count = events_ndjson
        .lines()
        .filter(|l| !l.trim().is_empty())
        .count();
    let has_logcat = nonblank(payload.get("logcat"));
    let has_last_crash = nonblank(payload.get("lastCrash"));
    let has_screenshot = nonblank(payload.get("screenshotPngBase64"));

    let meta = json!({
        "dumpId": safe_id,
        "dirName": dir_name,
        "createdAt": created_at,
        "createdAtIso": iso_ms(created_at),
        "note": payload.get("note").cloned().unwrap_or(Value::Null),
        "appVersion": payload.get("appVersion").and_then(Value::as_str).filter(|s| !s.is_empty()).unwrap_or("unknown"),
        "deviceInfo": payload.get("deviceInfo").and_then(Value::as_str).filter(|s| !s.is_empty()).unwrap_or("unknown"),
        "connectionSummary": payload.get("connectionSummary").and_then(Value::as_str).unwrap_or(""),
        "eventCount": event_count,
        "hasLogcat": has_logcat,
        "hasLastCrash": has_last_crash,
        "hasScreenshot": has_screenshot,
    });
    write_file(
        &mut files,
        "meta.json",
        format!(
            "{}\n",
            serde_json::to_string_pretty(&meta).unwrap_or_default()
        )
        .as_bytes(),
    );

    if let Some(state_json) = payload
        .get("workspaceStateJson")
        .and_then(Value::as_str)
        .filter(|s| !s.trim().is_empty())
    {
        let formatted = match serde_json::from_str::<Value>(state_json) {
            Ok(parsed) => format!(
                "{}\n",
                serde_json::to_string_pretty(&parsed).unwrap_or_default()
            ),
            Err(_) => state_json.to_string(),
        };
        write_file(&mut files, "state.json", formatted.as_bytes());
    }

    write_file(
        &mut files,
        "events.ndjson",
        format!("{}\n", events_ndjson.trim_end()).as_bytes(),
    );
    if let Some(logcat) = payload
        .get("logcat")
        .and_then(Value::as_str)
        .filter(|s| !s.trim().is_empty())
    {
        write_file(&mut files, "logcat.txt", logcat.as_bytes());
    }
    if let Some(crash) = payload
        .get("lastCrash")
        .and_then(Value::as_str)
        .filter(|s| !s.trim().is_empty())
    {
        write_file(&mut files, "crash.txt", crash.as_bytes());
    }
    if let Some(b64) = payload
        .get("screenshotPngBase64")
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|s| !s.is_empty())
    {
        if let Ok(png) = base64::engine::general_purpose::STANDARD.decode(b64) {
            if !png.is_empty() {
                write_file(&mut files, "screenshot.png", &png);
            }
        }
    }

    let yes_no = |flag: bool, file: &str| {
        if flag {
            format!("yes (`{file}`)")
        } else {
            "no".to_string()
        }
    };
    let mut summary = vec![
        format!("# AgentsTerm Android Debug Dump ({safe_id})"),
        String::new(),
        format!(
            "- **Captured (UTC):** {}",
            meta["createdAtIso"].as_str().unwrap_or("")
        ),
        format!(
            "- **App Version:** {}",
            meta["appVersion"].as_str().unwrap_or("unknown")
        ),
        format!(
            "- **Device:** {}",
            meta["deviceInfo"].as_str().unwrap_or("unknown")
        ),
        format!(
            "- **Connection:** {}",
            meta["connectionSummary"]
                .as_str()
                .filter(|s| !s.is_empty())
                .unwrap_or("n/a")
        ),
        format!("- **Buffered Events:** {event_count}"),
        format!(
            "- **Crash Trace Included:** {}",
            yes_no(has_last_crash, "crash.txt")
        ),
        format!(
            "- **Screenshot Included:** {}",
            yes_no(has_screenshot, "screenshot.png")
        ),
        format!(
            "- **Logcat Included:** {}",
            yes_no(has_logcat, "logcat.txt")
        ),
        String::new(),
    ];
    if let Some(note) = meta["note"]
        .as_str()
        .map(str::trim)
        .filter(|s| !s.is_empty())
    {
        summary.push("## User Note".to_string());
        summary.push(String::new());
        summary.push(note.to_string());
        summary.push(String::new());
    }
    summary.push("## Files in This Dump".to_string());
    for f in &files {
        summary.push(format!("- `{f}`"));
    }
    summary.push("- `summary.md`".to_string());
    summary.push(String::new());
    write_file(&mut files, "summary.md", summary.join("\n").as_bytes());

    let latest = root.join("latest");
    let tmp_link = root.join(format!(
        ".latest.tmp.{}.{}",
        std::process::id(),
        next_atomic_seq()
    ));
    let _ = fs::remove_file(&tmp_link);
    if std::os::unix::fs::symlink(&dir_name, &tmp_link).is_ok() {
        let _ = fs::rename(&tmp_link, &latest);
    }

    json!({
        "ok": true,
        "dumpId": safe_id,
        "remoteDir": remote_dir.to_string_lossy(),
        "latestSymlink": latest.to_string_lossy(),
        "files": files,
    })
}

pub fn list_debug_dumps() -> Value {
    let root = debug_dumps_root();
    if !root.exists() {
        return json!({ "root": root.to_string_lossy(), "dumps": [] });
    }
    let mut dumps: Vec<(String, Value)> = Vec::new();
    if let Ok(entries) = fs::read_dir(&root) {
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().to_string();
            if name == "latest" {
                continue;
            }
            let Ok(ft) = entry.file_type() else { continue };
            if !ft.is_dir() || ft.is_symlink() {
                continue;
            }
            let mut files: Vec<String> = fs::read_dir(entry.path())
                .ok()
                .map(|it| {
                    it.flatten()
                        .map(|p| p.file_name().to_string_lossy().to_string())
                        .collect()
                })
                .unwrap_or_default();
            files.sort();
            dumps.push((
                name.clone(),
                json!({
                    "dirName": name,
                    "remoteDir": entry.path().to_string_lossy(),
                    "files": files,
                }),
            ));
        }
    }
    dumps.sort_by(|a, b| b.0.cmp(&a.0));
    let out: Vec<Value> = dumps.into_iter().take(25).map(|(_, v)| v).collect();
    json!({ "root": root.to_string_lossy(), "dumps": out })
}

pub fn list_directories(requested: Option<&str>) -> Value {
    let target = match requested.map(str::trim).filter(|s| !s.is_empty()) {
        Some(r) => resolve_abs_path(r),
        None => home_dir().to_string_lossy().to_string(),
    };
    let target_path = Path::new(&target);
    let parent = target_path
        .parent()
        .filter(|p| *p != target_path)
        .map(|p| p.to_string_lossy().to_string());
    let mut dirs: Vec<(String, Value)> = Vec::new();
    if let Ok(entries) = fs::read_dir(target_path) {
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().to_string();
            if name.starts_with('.') {
                continue;
            }
            let p = entry.path();
            if fs::metadata(&p).map(|m| m.is_dir()).unwrap_or(false) {
                dirs.push((
                    name.clone(),
                    json!({ "name": name, "path": p.to_string_lossy() }),
                ));
            }
        }
    }
    dirs.sort_by(|a, b| {
        a.0.to_lowercase()
            .cmp(&b.0.to_lowercase())
            .then_with(|| a.0.cmp(&b.0))
    });
    let directories: Vec<Value> = dirs.into_iter().map(|(_, v)| v).collect();
    json!({
        "path": target,
        "parent": parent,
        "directories": directories,
        "exists": target_path.is_dir(),
    })
}

struct AppState {
    host: String,
    token: String,
    no_auth: bool,
    store: StateStore,
    app_state: AppStateBroker,
    runner: AgentRunner,
    ws_clients: Mutex<HashMap<u64, mpsc::UnboundedSender<String>>>,
}

impl AppState {
    fn authorized(
        &self,
        headers: &HeaderMap,
        query_token: Option<&str>,
        allow_query: bool,
    ) -> bool {
        if self.no_auth || self.token.is_empty() {
            return true;
        }
        if let Some(auth) = headers.get("authorization").and_then(|v| v.to_str().ok()) {
            if auth == format!("Bearer {}", self.token) {
                return true;
            }
        }
        allow_query && query_token == Some(self.token.as_str())
    }

    fn broadcast_raw(&self, raw: String) {
        let mut clients = self.ws_clients.lock().unwrap();
        clients.retain(|_, tx| tx.send(raw.clone()).is_ok());
    }
}

fn unauthorized() -> Response {
    (
        StatusCode::UNAUTHORIZED,
        Json(json!({ "error": "Invalid bearer token" })),
    )
        .into_response()
}

fn json_status(status: StatusCode, val: Value) -> Response {
    (status, Json(val)).into_response()
}

async fn get_info(State(st): State<Arc<AppState>>, headers: HeaderMap) -> Response {
    if !st.authorized(&headers, None, false) {
        return unauthorized();
    }
    let host = st.host.clone();
    let info = tokio::task::spawn_blocking(move || build_server_info(Some(&host)))
        .await
        .unwrap_or_else(|_| build_server_info(None));
    json_status(StatusCode::OK, info)
}

async fn get_models(
    State(st): State<Arc<AppState>>,
    headers: HeaderMap,
    AxumPath(agent): AxumPath<String>,
    Query(params): Query<HashMap<String, String>>,
) -> Response {
    if !st.authorized(&headers, None, false) {
        return unauthorized();
    }
    let refresh = matches!(
        params.get("refresh").map(String::as_str),
        Some("1" | "true")
    );
    json_status(StatusCode::OK, get_agent_models(&agent, refresh).await)
}

async fn get_usage(
    State(st): State<Arc<AppState>>,
    headers: HeaderMap,
    AxumPath(agent): AxumPath<String>,
) -> Response {
    if !st.authorized(&headers, None, false) {
        return unauthorized();
    }
    json_status(StatusCode::OK, get_agent_usage(&agent).await)
}

async fn get_fs(
    State(st): State<Arc<AppState>>,
    headers: HeaderMap,
    Query(params): Query<HashMap<String, String>>,
) -> Response {
    if !st.authorized(&headers, None, false) {
        return unauthorized();
    }
    let req_path = params.get("path").cloned();
    let res = tokio::task::spawn_blocking(move || list_directories(req_path.as_deref()))
        .await
        .unwrap_or_else(
            |_| json!({ "path": "/", "parent": Value::Null, "directories": [], "exists": false }),
        );
    json_status(StatusCode::OK, res)
}

async fn get_dumps(State(st): State<Arc<AppState>>, headers: HeaderMap) -> Response {
    if !st.authorized(&headers, None, false) {
        return unauthorized();
    }
    let res = tokio::task::spawn_blocking(list_debug_dumps)
        .await
        .unwrap_or_else(|_| json!({ "root": "", "dumps": [] }));
    json_status(StatusCode::OK, res)
}

async fn post_dumps(
    State(st): State<Arc<AppState>>,
    headers: HeaderMap,
    body: axum::body::Bytes,
) -> Response {
    if !st.authorized(&headers, None, false) {
        return unauthorized();
    }
    let parsed: Map<String, Value> = serde_json::from_slice::<Value>(&body)
        .ok()
        .and_then(|v| v.as_object().cloned())
        .unwrap_or_default();
    let res = tokio::task::spawn_blocking(move || save_debug_dump(&parsed))
        .await
        .unwrap_or_else(|_| json!({ "ok": false }));
    json_status(StatusCode::OK, res)
}

async fn get_app_status(State(st): State<Arc<AppState>>, headers: HeaderMap) -> Response {
    if !st.authorized(&headers, None, false) {
        return unauthorized();
    }
    json_status(StatusCode::OK, st.app_state.status())
}

async fn post_app_command(
    State(st): State<Arc<AppState>>,
    headers: HeaderMap,
    body: axum::body::Bytes,
) -> Response {
    if !st.authorized(&headers, None, false) {
        return unauthorized();
    }
    let parsed: Map<String, Value> = serde_json::from_slice::<Value>(&body)
        .ok()
        .and_then(|v| v.as_object().cloned())
        .unwrap_or_default();
    let command = parsed
        .get("command")
        .and_then(Value::as_str)
        .map(str::trim)
        .unwrap_or("");
    let args = parsed
        .get("args")
        .filter(|v| v.is_object())
        .cloned()
        .unwrap_or_else(|| json!({}));
    let timeout_ms = parsed
        .get("timeoutMs")
        .and_then(|v| {
            v.as_u64()
                .or_else(|| v.as_f64().filter(|f| *f > 0.0).map(|f| f as u64))
        })
        .unwrap_or(DEFAULT_COMMAND_TIMEOUT_MS);

    match st.app_state.request(command, args, timeout_ms).await {
        Ok(mut res) => {
            if let Some(obj) = res.as_object_mut() {
                obj.insert("ok".into(), json!(true));
            }
            json_status(StatusCode::OK, res)
        }
        Err(err) => {
            let status =
                StatusCode::from_u16(err.http_status).unwrap_or(StatusCode::INTERNAL_SERVER_ERROR);
            json_status(
                status,
                json!({ "ok": false, "code": err.code, "error": err.message }),
            )
        }
    }
}

async fn get_tabs(State(st): State<Arc<AppState>>, headers: HeaderMap) -> Response {
    if !st.authorized(&headers, None, false) {
        return unauthorized();
    }
    json_status(StatusCode::OK, st.store.list_tabs())
}

async fn post_tabs(
    State(st): State<Arc<AppState>>,
    headers: HeaderMap,
    body: axum::body::Bytes,
) -> Response {
    if !st.authorized(&headers, None, false) {
        return unauthorized();
    }
    let parsed: Map<String, Value> = serde_json::from_slice::<Value>(&body)
        .ok()
        .and_then(|v| v.as_object().cloned())
        .unwrap_or_default();
    let Some(agent) = parsed
        .get("agent")
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty())
    else {
        return json_status(
            StatusCode::BAD_REQUEST,
            json!({ "error": "Missing required field: agent" }),
        );
    };
    let Some(tab) = st.store.create_tab(
        agent,
        parsed.get("title"),
        parsed.get("cwd"),
        parsed.get("permissionMode"),
        parsed.get("model"),
        parsed.get("systemPrompt"),
    ) else {
        return json_status(
            StatusCode::CONFLICT,
            json!({ "error": format!("The limit is {MAX_OPEN_TABS} open tabs. Close a tab to make room.") }),
        );
    };
    st.broadcast_raw(json!({ "type": "tab_created", "tab": tab }).to_string());
    json_status(StatusCode::OK, json!({ "tab": tab }))
}

async fn patch_tab(
    State(st): State<Arc<AppState>>,
    headers: HeaderMap,
    AxumPath(tab_id): AxumPath<String>,
    body: axum::body::Bytes,
) -> Response {
    if !st.authorized(&headers, None, false) {
        return unauthorized();
    }
    let Some(existing) = st.store.get_tab(&tab_id) else {
        return json_status(StatusCode::NOT_FOUND, json!({ "error": "Tab not found" }));
    };
    let parsed: Map<String, Value> = serde_json::from_slice::<Value>(&body)
        .ok()
        .and_then(|v| v.as_object().cloned())
        .unwrap_or_default();

    let mut patch = Map::new();
    if let Some(title) = parsed.get("title").filter(|v| !v.is_null()) {
        patch.insert("title".into(), title.clone());
    }
    if let Some(cwd) = parsed.get("cwd").and_then(Value::as_str) {
        patch.insert("cwd".into(), json!(resolve_abs_path(cwd)));
    }
    if let Some(pm) = parsed.get("permissionMode").filter(|v| !v.is_null()) {
        patch.insert("permissionMode".into(), pm.clone());
    }
    if let Some(model_val) = parsed.get("model") {
        let cleaned = model_val
            .as_str()
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(|s| Value::String(s.to_string()))
            .unwrap_or(Value::Null);
        patch.insert("model".into(), cleaned);
    }
    let mut system_prompt_changed = false;
    if let Some(sp_val) = parsed.get("systemPrompt") {
        let new_sp = if existing.get("agent").and_then(Value::as_str) == Some("shell") {
            Value::Null
        } else {
            sp_val
                .as_str()
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(|s| Value::String(s.to_string()))
                .unwrap_or(Value::Null)
        };
        let prev_sp = existing.get("systemPrompt").cloned().unwrap_or(Value::Null);
        system_prompt_changed = new_sp != prev_sp;
        patch.insert("systemPrompt".into(), new_sp);
    }
    if let Some(status_s) = parsed.get("status").and_then(Value::as_str) {
        if matches!(status_s, "done" | "idle")
            && !st.runner.is_running(&tab_id)
            && existing.get("agent").and_then(Value::as_str) != Some("shell")
        {
            patch.insert("status".into(), json!(status_s));
            patch.insert(
                "statusDetail".into(),
                if status_s == "done" {
                    json!("Task finished")
                } else {
                    Value::Null
                },
            );
        }
    }

    if let Some(updated) = st.store.update_tab(&tab_id, patch) {
        if system_prompt_changed {
            st.runner.mark_system_prompt_updated(&tab_id);
        }
        st.broadcast_raw(json!({ "type": "tab_updated", "tab": updated }).to_string());
        json_status(StatusCode::OK, json!({ "tab": updated }))
    } else {
        json_status(StatusCode::NOT_FOUND, json!({ "error": "Tab not found" }))
    }
}

async fn delete_tab(
    State(st): State<Arc<AppState>>,
    headers: HeaderMap,
    AxumPath(tab_id): AxumPath<String>,
) -> Response {
    if !st.authorized(&headers, None, false) {
        return unauthorized();
    }
    st.runner.kill_if_running(&tab_id);
    let closed_tab = st.store.delete_tab(&tab_id);
    let closed_tabs = st.store.list_closed_tabs();
    st.broadcast_raw(
        json!({
            "type": "tab_deleted",
            "tabId": tab_id,
            "closedTab": closed_tab,
            "closedTabs": closed_tabs,
        })
        .to_string(),
    );
    json_status(
        StatusCode::OK,
        json!({
            "ok": true,
            "closedTab": closed_tab,
            "closedTabs": closed_tabs,
        }),
    )
}

async fn get_closed_tabs(State(st): State<Arc<AppState>>, headers: HeaderMap) -> Response {
    if !st.authorized(&headers, None, false) {
        return unauthorized();
    }
    json_status(
        StatusCode::OK,
        json!({ "closedTabs": st.store.list_closed_tabs() }),
    )
}

async fn post_revive_closed_tab(
    State(st): State<Arc<AppState>>,
    headers: HeaderMap,
    AxumPath(tab_id): AxumPath<String>,
) -> Response {
    if !st.authorized(&headers, None, false) {
        return unauthorized();
    }
    match st.store.revive_tab(&tab_id) {
        Ok(tab) => {
            let messages = st.store.get_messages(&tab_id);
            let closed_tabs = st.store.list_closed_tabs();
            st.broadcast_raw(
                json!({
                    "type": "tab_created",
                    "tab": tab,
                    "closedTabs": closed_tabs,
                })
                .to_string(),
            );
            json_status(
                StatusCode::OK,
                json!({
                    "tab": tab,
                    "messages": messages,
                    "closedTabs": closed_tabs,
                }),
            )
        }
        Err(ReviveTabError::OpenTabLimitReached) => json_status(
            StatusCode::CONFLICT,
            json!({ "error": format!("The limit is {MAX_OPEN_TABS} open tabs. Close a tab to make room.") }),
        ),
        Err(ReviveTabError::NotFound) => json_status(
            StatusCode::NOT_FOUND,
            json!({ "error": "Closed tab not found or expired" }),
        ),
    }
}

async fn get_tab_messages(
    State(st): State<Arc<AppState>>,
    headers: HeaderMap,
    AxumPath(tab_id): AxumPath<String>,
) -> Response {
    if !st.authorized(&headers, None, false) {
        return unauthorized();
    }
    let Some(tab) = st.store.get_tab(&tab_id) else {
        return json_status(StatusCode::NOT_FOUND, json!({ "error": "Tab not found" }));
    };
    json_status(
        StatusCode::OK,
        json!({ "tab": tab, "messages": st.store.get_messages(&tab_id) }),
    )
}

async fn post_tab_messages(
    State(st): State<Arc<AppState>>,
    headers: HeaderMap,
    AxumPath(tab_id): AxumPath<String>,
    body: axum::body::Bytes,
) -> Response {
    if !st.authorized(&headers, None, false) {
        return unauthorized();
    }
    let Some(tab) = st.store.get_tab(&tab_id) else {
        return json_status(StatusCode::NOT_FOUND, json!({ "error": "Tab not found" }));
    };
    let parsed: Map<String, Value> = serde_json::from_slice::<Value>(&body)
        .ok()
        .and_then(|v| v.as_object().cloned())
        .unwrap_or_default();
    let text = parsed
        .get("text")
        .and_then(Value::as_str)
        .map(str::trim)
        .unwrap_or("");
    let attachments = match message_attachments(&tab, parsed.get("attachments")) {
        Ok(found) => found,
        Err(reason) => return json_status(StatusCode::BAD_REQUEST, json!({ "error": reason })),
    };
    if text.is_empty() && attachments.is_empty() {
        return json_status(
            StatusCode::BAD_REQUEST,
            json!({ "error": "Message text cannot be empty" }),
        );
    }
    let override_mode = parsed.get("overridePermissionMode").and_then(Value::as_str);
    let result = st
        .runner
        .submit_message(&tab, text, override_mode, &attachments);
    json_status(StatusCode::OK, result)
}

fn is_shell_tab(tab: &Value) -> bool {
    tab.get("agent").and_then(Value::as_str) == Some("shell")
}

/// Resolves the attachment ids a message refers to into the images staged for its tab.
fn message_attachments(tab: &Value, raw: Option<&Value>) -> Result<Vec<Value>, String> {
    let tab_id = tab.get("id").and_then(Value::as_str).unwrap_or("");
    let attachments = resolve_attachments(tab_id, raw)?;
    if !attachments.is_empty() && is_shell_tab(tab) {
        return Err("Shell tabs cannot receive images".to_string());
    }
    Ok(attachments)
}

async fn post_tab_attachment(
    State(st): State<Arc<AppState>>,
    headers: HeaderMap,
    AxumPath(tab_id): AxumPath<String>,
    body: axum::body::Bytes,
) -> Response {
    if !st.authorized(&headers, None, false) {
        return unauthorized();
    }
    let Some(tab) = st.store.get_tab(&tab_id) else {
        return json_status(StatusCode::NOT_FOUND, json!({ "error": "Tab not found" }));
    };
    if is_shell_tab(&tab) {
        return json_status(
            StatusCode::BAD_REQUEST,
            json!({ "error": "Shell tabs cannot receive images" }),
        );
    }
    let referenced = referenced_attachment_ids(&tab, &st.store.get_messages(&tab_id));
    let staged = tokio::task::spawn_blocking(move || {
        sweep_stale_uploads(&tab_id, &referenced);
        stage_attachment(&tab_id, &body)
    })
    .await
    .unwrap_or_else(|e| Err(StageError::Io(e.to_string())));
    match staged {
        Ok(attachment) => json_status(StatusCode::OK, json!({ "attachment": attachment })),
        Err(StageError::Empty) => json_status(
            StatusCode::BAD_REQUEST,
            json!({ "error": "The image is empty" }),
        ),
        Err(StageError::TooLarge) => json_status(
            StatusCode::PAYLOAD_TOO_LARGE,
            json!({ "error": format!("Images can be at most {} MB", MAX_ATTACHMENT_BYTES / (1024 * 1024)) }),
        ),
        Err(StageError::UnsupportedType) => json_status(
            StatusCode::UNSUPPORTED_MEDIA_TYPE,
            json!({ "error": "Unsupported image format. Use PNG, JPEG, GIF, WebP or BMP." }),
        ),
        Err(StageError::Io(reason)) => {
            log_warn(&format!("Failed to store an image attachment: {reason}"));
            json_status(
                StatusCode::INTERNAL_SERVER_ERROR,
                json!({ "error": "The VM could not store the image" }),
            )
        }
    }
}

async fn get_tab_attachment(
    State(st): State<Arc<AppState>>,
    headers: HeaderMap,
    AxumPath((tab_id, attachment_id)): AxumPath<(String, String)>,
) -> Response {
    if !st.authorized(&headers, None, false) {
        return unauthorized();
    }
    let loaded = tokio::task::spawn_blocking(move || {
        let found = find_attachment(&tab_id, &attachment_id)?;
        let bytes = fs::read(found.get("path")?.as_str()?).ok()?;
        let mime = found.get("mimeType")?.as_str()?.to_string();
        Some((mime, bytes))
    })
    .await
    .ok()
    .flatten();
    match loaded {
        Some((mime, bytes)) => (
            StatusCode::OK,
            [
                (axum::http::header::CONTENT_TYPE, mime),
                // An attachment never changes once stored, so the app may keep it.
                (
                    axum::http::header::CACHE_CONTROL,
                    "private, max-age=31536000, immutable".to_string(),
                ),
            ],
            bytes,
        )
            .into_response(),
        None => json_status(
            StatusCode::NOT_FOUND,
            json!({ "error": "Attachment not found" }),
        ),
    }
}

async fn delete_tab_queue_item(
    State(st): State<Arc<AppState>>,
    headers: HeaderMap,
    AxumPath((tab_id, item_id)): AxumPath<(String, String)>,
) -> Response {
    if !st.authorized(&headers, None, false) {
        return unauthorized();
    }
    if st.store.get_tab(&tab_id).is_none() {
        return json_status(StatusCode::NOT_FOUND, json!({ "error": "Tab not found" }));
    }
    let updated = st.runner.remove_queued_message(&tab_id, &item_id);
    json_status(StatusCode::OK, json!({ "ok": true, "tab": updated }))
}

async fn post_tab_interrupt(
    State(st): State<Arc<AppState>>,
    headers: HeaderMap,
    AxumPath(tab_id): AxumPath<String>,
    body: axum::body::Bytes,
) -> Response {
    if !st.authorized(&headers, None, false) {
        return unauthorized();
    }
    let Some(tab) = st.store.get_tab(&tab_id) else {
        return json_status(StatusCode::NOT_FOUND, json!({ "error": "Tab not found" }));
    };
    let parsed: Map<String, Value> = serde_json::from_slice::<Value>(&body)
        .ok()
        .and_then(|v| v.as_object().cloned())
        .unwrap_or_default();
    let attachments = match message_attachments(&tab, parsed.get("attachments")) {
        Ok(found) => found,
        Err(reason) => return json_status(StatusCode::BAD_REQUEST, json!({ "error": reason })),
    };
    let process_queue = parsed
        .get("processQueue")
        .and_then(Value::as_bool)
        .unwrap_or(false);
    let text = parsed
        .get("text")
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|s| !s.is_empty());
    let override_mode = parsed.get("overridePermissionMode").and_then(Value::as_str);
    st.runner
        .interrupt(&tab_id, process_queue, text, override_mode, &attachments);
    json_status(
        StatusCode::OK,
        json!({ "ok": true, "tab": st.store.get_tab(&tab_id) }),
    )
}

async fn post_tab_read(
    State(st): State<Arc<AppState>>,
    headers: HeaderMap,
    AxumPath(tab_id): AxumPath<String>,
) -> Response {
    if !st.authorized(&headers, None, false) {
        return unauthorized();
    }
    if st.store.get_tab(&tab_id).is_none() {
        return json_status(StatusCode::NOT_FOUND, json!({ "error": "Tab not found" }));
    }
    let mut patch = Map::new();
    patch.insert("unread".into(), json!(false));
    if let Some(updated) = st.store.update_tab(&tab_id, patch) {
        st.broadcast_raw(json!({ "type": "tab_updated", "tab": updated }).to_string());
    }
    json_status(StatusCode::OK, json!({ "ok": true }))
}

async fn ws_handler(
    State(st): State<Arc<AppState>>,
    headers: HeaderMap,
    Query(params): Query<HashMap<String, String>>,
    ws: WebSocketUpgrade,
) -> Response {
    let q_tok = params.get("token").map(String::as_str);
    if !st.authorized(&headers, q_tok, true) {
        return unauthorized();
    }
    ws.on_upgrade(move |socket| handle_ws(st, socket))
}

async fn handle_ws(st: Arc<AppState>, socket: WebSocket) {
    let (mut sink, mut stream) = socket.split();
    let (tx, mut rx) = mpsc::unbounded_channel::<String>();
    let client_id = st.app_state.add_socket(tx.clone());
    st.ws_clients.lock().unwrap().insert(client_id, tx.clone());

    let listing = st.store.list_tabs();
    let host = st.host.clone();
    let server_info = tokio::task::spawn_blocking(move || build_server_info(Some(&host)))
        .await
        .unwrap_or_else(|_| build_server_info(None));
    let snapshot = json!({
        "type": "snapshot",
        "server": server_info,
        "tabs": listing["tabs"],
        "activeTabId": listing["activeTabId"],
        "closedTabs": listing["closedTabs"],
        "ts": now_ms(),
    });
    let _ = tx.send(snapshot.to_string());

    let writer_task = tokio::spawn(async move {
        let mut ping_interval = tokio::time::interval(PING_INTERVAL);
        ping_interval.tick().await; // skip immediate tick
        loop {
            tokio::select! {
                maybe_msg = rx.recv() => {
                    match maybe_msg {
                        Some(text) => {
                            if sink.send(Message::Text(text.into())).await.is_err() {
                                break;
                            }
                        }
                        None => break,
                    }
                }
                _ = ping_interval.tick() => {
                    if sink.send(Message::Ping(Vec::new().into())).await.is_err() {
                        break;
                    }
                }
            }
        }
    });

    while let Some(Ok(msg)) = stream.next().await {
        match msg {
            Message::Text(text) => {
                st.app_state.handle_message(client_id, &text);
            }
            Message::Close(_) => break,
            _ => {}
        }
    }

    writer_task.abort();
    st.ws_clients.lock().unwrap().remove(&client_id);
    st.app_state.remove_socket(client_id);
}

async fn not_found() -> Response {
    json_status(
        StatusCode::NOT_FOUND,
        json!({ "error": "Unknown endpoint" }),
    )
}

pub async fn run_daemon(
    host: String,
    port: u16,
    token: String,
    no_auth: bool,
) -> Result<(), String> {
    ensure_debugging_docs();
    warm_agent_models_cache();

    let store = StateStore::new();
    let app_state = AppStateBroker::new();
    let ws_clients: Arc<Mutex<HashMap<u64, mpsc::UnboundedSender<String>>>> =
        Arc::new(Mutex::new(HashMap::new()));

    let state = Arc::new_cyclic(|weak: &std::sync::Weak<AppState>| {
        let weak_b = weak.clone();
        let broadcast = Arc::new(move |env: Value| {
            if let Some(st) = weak_b.upgrade() {
                st.broadcast_raw(env.to_string());
            }
        });
        let runner = AgentRunner::new(store.clone(), broadcast);
        AppState {
            host: host.clone(),
            token: token.clone(),
            no_auth,
            store: store.clone(),
            app_state: app_state.clone(),
            runner,
            ws_clients: Mutex::new(HashMap::new()),
        }
    });
    let _ = ws_clients;

    let app = Router::new()
        .route("/ws", get(ws_handler))
        .route("/api/info", get(get_info))
        .route("/api/agents/{agent}/models", get(get_models))
        .route("/api/agents/{agent}/usage", get(get_usage))
        .route("/api/fs", get(get_fs))
        .route(
            "/api/debug/dumps",
            get(get_dumps)
                .post(post_dumps)
                .layer(DefaultBodyLimit::max(MAX_DEBUG_DUMP_BODY)),
        )
        .route("/api/app/status", get(get_app_status))
        .route("/api/app/command", post(post_app_command))
        .route("/api/tabs", get(get_tabs).post(post_tabs))
        .route("/api/tabs/closed", get(get_closed_tabs))
        .route("/api/tabs/closed/{id}/revive", post(post_revive_closed_tab))
        .route("/api/tabs/{id}", patch(patch_tab).delete(delete_tab))
        .route(
            "/api/tabs/{id}/messages",
            get(get_tab_messages).post(post_tab_messages),
        )
        .route(
            "/api/tabs/{id}/attachments",
            // The limit sits just above the cap so an oversized image gets the JSON error.
            post(post_tab_attachment).layer(DefaultBodyLimit::max(MAX_ATTACHMENT_BYTES + 1)),
        )
        .route(
            "/api/tabs/{id}/attachments/{attachment}",
            get(get_tab_attachment),
        )
        .route("/api/tabs/{id}/queue/{item}", delete(delete_tab_queue_item))
        .route("/api/tabs/{id}/interrupt", post(post_tab_interrupt))
        .route("/api/tabs/{id}/read", post(post_tab_read))
        .fallback(not_found)
        .with_state(state.clone());

    let bind_addr = format!("{host}:{port}");
    let addr: SocketAddr = bind_addr
        .parse()
        .map_err(|e| format!("Invalid bind address {bind_addr}: {e}"))?;
    let listener = TcpListener::bind(addr)
        .await
        .map_err(|e| format!("Failed to bind {bind_addr}: {e}"))?;

    let commit = daemon_commit();
    let short_commit: String = commit.chars().take(12).collect();
    let auth = if no_auth { "disabled" } else { "bearer" };
    log_info(&format!(
        "AgentsTerm daemon v{SERVER_VERSION} (rust, commit {short_commit}, pid {}) listening on http://{host}:{port} (auth={auth})",
        std::process::id()
    ));

    let state_shutdown = state.clone();
    let shutdown_signal = async move {
        let mut sigterm =
            tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate()).ok();
        let reason = tokio::select! {
            _ = tokio::signal::ctrl_c() => "SIGINT",
            _ = async {
                if let Some(ref mut s) = sigterm {
                    s.recv().await;
                } else {
                    std::future::pending::<()>().await;
                }
            } => "SIGTERM",
        };
        log_warn(&format!(
            "Received {reason}; shutting down AgentsTerm daemon"
        ));
        state_shutdown.runner.shutdown_active_turns(reason);
        state_shutdown.store.save_now();
    };

    axum::serve(listener, app)
        .with_graceful_shutdown(shutdown_signal)
        .await
        .map_err(|e| format!("Server error: {e}"))?;

    log_info("AgentsTerm daemon stopped");
    Ok(())
}
