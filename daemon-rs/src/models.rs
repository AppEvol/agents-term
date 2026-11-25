use regex::Regex;
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};
use std::time::Duration;
use tokio::process::Command;
use tokio::sync::broadcast;

use crate::config::{enriched_path, home_dir, resolve_binary};
use crate::util::{is_number, now_ms};

const AGY_CACHE_TTL_MS: i64 = 5 * 60_000;
const DISK_CACHE_TTL_MS: i64 = 60_000;

#[derive(Clone)]
struct CachedModels {
    fetched_at: i64,
    default_model: Option<String>,
    models: Vec<Value>,
}

fn cache() -> &'static Mutex<HashMap<String, CachedModels>> {
    static CACHE: OnceLock<Mutex<HashMap<String, CachedModels>>> = OnceLock::new();
    CACHE.get_or_init(|| Mutex::new(HashMap::new()))
}

fn agy_in_flight() -> &'static Mutex<Option<broadcast::Sender<Value>>> {
    static FLIGHT: OnceLock<Mutex<Option<broadcast::Sender<Value>>>> = OnceLock::new();
    FLIGHT.get_or_init(|| Mutex::new(None))
}

fn ansi_re() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| Regex::new(r"\x1b\[[0-9;?]*[ -/]*[@-~]").unwrap())
}

fn agy_model_line_re() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| Regex::new(r"^([A-Za-z0-9][A-Za-z0-9._:/-]*)\s{2,}(.+)$").unwrap())
}

fn fetching_re() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| Regex::new(r"(?i)fetching available models").unwrap())
}

fn codex_model_re() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| Regex::new(r#"(?m)^\s*model\s*=\s*"([^"]+)""#).unwrap())
}

fn nonblank(v: Option<&Value>) -> Option<String> {
    v.and_then(Value::as_str)
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_string)
}

fn read_json_safe(path: &Path) -> Option<serde_json::Map<String, Value>> {
    let raw = fs::read_to_string(path).ok()?;
    match serde_json::from_str::<Value>(&raw).ok()? {
        Value::Object(map) => Some(map),
        _ => None,
    }
}

pub fn parse_agy_models_output(raw: &str) -> Vec<Value> {
    let cleaned = ansi_re().replace_all(raw, "");
    let mut seen = HashSet::new();
    let mut models = Vec::new();
    for raw_line in cleaned.split('\n') {
        let line = raw_line.rsplit('\r').next().unwrap_or("").trim();
        if line.is_empty() || fetching_re().is_match(line) {
            continue;
        }
        let Some(caps) = agy_model_line_re().captures(line) else {
            continue;
        };
        let model_id = caps.get(1).map(|m| m.as_str().trim()).unwrap_or("");
        let name = caps.get(2).map(|m| m.as_str().trim()).unwrap_or("");
        if model_id.is_empty() || name.is_empty() || !seen.insert(model_id.to_string()) {
            continue;
        }
        models.push(json!({ "id": model_id, "name": name }));
    }
    models
}

pub fn read_claude_models_from_disk(home: Option<&Path>) -> (Option<String>, Vec<Value>) {
    let base: PathBuf = home.map(Path::to_path_buf).unwrap_or_else(home_dir);
    let claude_dir = base.join(".claude");
    let default_model =
        read_json_safe(&claude_dir.join("settings.json")).and_then(|s| nonblank(s.get("model")));

    let catalog_dir = claude_dir.join("cache").join("model-catalog");
    let mut models: Vec<Value> = Vec::new();
    let mut seen = HashSet::new();
    if let Ok(entries) = fs::read_dir(&catalog_dir) {
        let mut files: Vec<PathBuf> = entries
            .filter_map(|e| e.ok().map(|e| e.path()))
            .filter(|p| p.extension().and_then(|s| s.to_str()) == Some("json"))
            .collect();
        files.sort_by(|a, b| {
            let ma = fs::metadata(a).and_then(|m| m.modified()).ok();
            let mb = fs::metadata(b).and_then(|m| m.modified()).ok();
            mb.cmp(&ma)
        });
        for f in files {
            let Some(parsed) = read_json_safe(&f) else {
                continue;
            };
            let raw_models = parsed
                .get("catalog")
                .and_then(Value::as_object)
                .and_then(|c| c.get("config"))
                .and_then(Value::as_object)
                .and_then(|c| c.get("models"))
                .and_then(Value::as_array);
            let Some(arr) = raw_models else {
                continue;
            };
            if arr.is_empty() {
                continue;
            }
            for item in arr {
                let Some(obj) = item.as_object() else {
                    continue;
                };
                let model_id = obj
                    .get("id")
                    .and_then(Value::as_str)
                    .map(str::trim)
                    .unwrap_or("");
                if model_id.is_empty() || !seen.insert(model_id.to_string()) {
                    continue;
                }
                let name = nonblank(obj.get("name")).unwrap_or_else(|| model_id.to_string());
                let description = nonblank(obj.get("description"));
                let is_default = default_model.as_deref() == Some(model_id);
                models.push(json!({
                    "id": model_id,
                    "name": name,
                    "description": description,
                    "isDefault": is_default,
                }));
            }
            if !models.is_empty() {
                break;
            }
        }
    }

    if models.is_empty() {
        models = vec![
            json!({ "id": "opus", "name": "Opus", "description": "Most capable Claude model alias" }),
            json!({ "id": "sonnet", "name": "Sonnet", "description": "Balanced Claude model alias" }),
            json!({ "id": "haiku", "name": "Haiku", "description": "Fastest Claude model alias" }),
        ];
    }
    (default_model, models)
}

pub fn read_codex_models_from_disk(home: Option<&Path>) -> (Option<String>, Vec<Value>) {
    let base: PathBuf = home.map(Path::to_path_buf).unwrap_or_else(home_dir);
    let codex_dir = base.join(".codex");
    let default_model = fs::read_to_string(codex_dir.join("config.toml"))
        .ok()
        .and_then(|content| {
            codex_model_re()
                .captures(&content)
                .and_then(|c| c.get(1))
                .map(|m| m.as_str().trim().to_string())
                .filter(|s| !s.is_empty())
        });

    let mut seen = HashSet::new();
    let mut sortable: Vec<(f64, Value)> = Vec::new();
    if let Some(cache_map) = read_json_safe(&codex_dir.join("models_cache.json")) {
        if let Some(raw_models) = cache_map.get("models").and_then(Value::as_array) {
            for item in raw_models {
                let Some(obj) = item.as_object() else {
                    continue;
                };
                if obj.get("visibility").and_then(Value::as_str) == Some("hide")
                    || obj.get("supported_in_api").and_then(Value::as_bool) == Some(false)
                {
                    continue;
                }
                let model_id = obj
                    .get("slug")
                    .and_then(Value::as_str)
                    .map(str::trim)
                    .unwrap_or("");
                if model_id.is_empty() || !seen.insert(model_id.to_string()) {
                    continue;
                }
                let priority = obj
                    .get("priority")
                    .filter(|v| is_number(v))
                    .and_then(Value::as_f64)
                    .unwrap_or(999.0);
                let name =
                    nonblank(obj.get("display_name")).unwrap_or_else(|| model_id.to_string());
                let description = nonblank(obj.get("description"));
                let is_default = default_model.as_deref() == Some(model_id);
                sortable.push((
                    priority,
                    json!({
                        "id": model_id,
                        "name": name,
                        "description": description,
                        "isDefault": is_default,
                    }),
                ));
            }
        }
    }
    sortable.sort_by(|a, b| a.0.total_cmp(&b.0));
    let mut models: Vec<Value> = sortable.into_iter().map(|(_, m)| m).collect();
    if let Some(ref def) = default_model {
        if !seen.contains(def) {
            models.insert(0, json!({ "id": def, "name": def, "isDefault": true }));
        }
    }
    (default_model, models)
}

fn read_agy_default_model(home: Option<&Path>) -> Option<String> {
    let base: PathBuf = home.map(Path::to_path_buf).unwrap_or_else(home_dir);
    read_json_safe(
        &base
            .join(".gemini")
            .join("antigravity-cli")
            .join("settings.json"),
    )
    .and_then(|s| nonblank(s.get("model")))
}

async fn run_agy_models(binary: &str) -> (String, bool) {
    let mut cmd = Command::new(binary);
    cmd.arg("models")
        .env("PATH", enriched_path())
        .env("NO_COLOR", "1")
        .env("FORCE_COLOR", "0")
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped());

    let Ok(child) = cmd.spawn() else {
        return (String::new(), true);
    };
    let output_res = tokio::time::timeout(Duration::from_secs(10), child.wait_with_output()).await;
    match output_res {
        Ok(Ok(out)) => {
            let stdout = String::from_utf8_lossy(&out.stdout);
            let stderr = String::from_utf8_lossy(&out.stderr);
            let mut parts = Vec::new();
            if !stdout.is_empty() {
                parts.push(stdout.into_owned());
            }
            if !stderr.is_empty() {
                parts.push(stderr.into_owned());
            }
            (parts.join("\n"), !out.status.success())
        }
        _ => (String::new(), true),
    }
}

pub async fn discover_agy_models(force: bool, home: Option<&Path>) -> Value {
    let existing_cached = cache().lock().ok().and_then(|c| c.get("agy").cloned());
    if !force {
        if let Some(ref cached) = existing_cached {
            if now_ms() - cached.fetched_at < AGY_CACHE_TTL_MS && !cached.models.is_empty() {
                return json!({
                    "fetchedAt": cached.fetched_at,
                    "defaultModel": cached.default_model,
                    "models": cached.models,
                });
            }
        }
    }

    let maybe_rx = {
        let mut guard = agy_in_flight().lock().unwrap();
        if let Some(ref tx) = *guard {
            Some(tx.subscribe())
        } else {
            let (tx, _) = broadcast::channel(1);
            *guard = Some(tx);
            None
        }
    };
    if let Some(mut rx) = maybe_rx {
        if let Ok(val) = rx.recv().await {
            return val;
        }
    }

    let default_model = read_agy_default_model(home);
    let result_cached = if let Some(binary) = resolve_binary("agy") {
        let (combined, err) = run_agy_models(&binary).await;
        let parsed: Vec<Value> = parse_agy_models_output(&combined)
            .into_iter()
            .map(|mut m| {
                let is_def = m.get("id").and_then(Value::as_str) == default_model.as_deref()
                    && default_model.is_some();
                if let Some(obj) = m.as_object_mut() {
                    obj.insert("isDefault".into(), json!(is_def));
                }
                m
            })
            .collect();
        if !parsed.is_empty() {
            CachedModels {
                fetched_at: now_ms(),
                default_model,
                models: parsed,
            }
        } else if err {
            if let Some(prev) = existing_cached.filter(|c| !c.models.is_empty()) {
                prev
            } else {
                CachedModels {
                    fetched_at: now_ms(),
                    default_model,
                    models: parsed,
                }
            }
        } else {
            CachedModels {
                fetched_at: now_ms(),
                default_model,
                models: parsed,
            }
        }
    } else {
        CachedModels {
            fetched_at: now_ms(),
            default_model,
            models: Vec::new(),
        }
    };

    if let Ok(mut guard) = cache().lock() {
        guard.insert("agy".to_string(), result_cached.clone());
    }
    let val = json!({
        "fetchedAt": result_cached.fetched_at,
        "defaultModel": result_cached.default_model,
        "models": result_cached.models,
    });
    if let Ok(mut guard) = agy_in_flight().lock() {
        if let Some(tx) = guard.take() {
            let _ = tx.send(val.clone());
        }
    }
    val
}

fn kick_agy_discovery() {
    if let Ok(handle) = tokio::runtime::Handle::try_current() {
        handle.spawn(async {
            let _ = discover_agy_models(false, None).await;
        });
    }
}

pub fn get_cached_agent_models(agent: &str) -> Value {
    if agent == "shell" {
        return json!({ "defaultModel": Value::Null, "models": [] });
    }
    let now = now_ms();
    if agent == "agy" {
        let cached = cache().lock().ok().and_then(|c| c.get("agy").cloned());
        let stale = cached
            .as_ref()
            .map(|c| now - c.fetched_at >= AGY_CACHE_TTL_MS)
            .unwrap_or(true);
        let in_flight = agy_in_flight().lock().map(|g| g.is_some()).unwrap_or(false);
        if stale && !in_flight {
            kick_agy_discovery();
        }
        return match cached {
            Some(c) => json!({ "defaultModel": c.default_model, "models": c.models }),
            None => json!({ "defaultModel": read_agy_default_model(None), "models": [] }),
        };
    }
    if agent == "claude" || agent == "codex" {
        if let Ok(guard) = cache().lock() {
            if let Some(c) = guard.get(agent) {
                if now - c.fetched_at < DISK_CACHE_TTL_MS {
                    return json!({ "defaultModel": c.default_model, "models": c.models });
                }
            }
        }
        let (default_model, models) = if agent == "claude" {
            read_claude_models_from_disk(None)
        } else {
            read_codex_models_from_disk(None)
        };
        if let Ok(mut guard) = cache().lock() {
            guard.insert(
                agent.to_string(),
                CachedModels {
                    fetched_at: now,
                    default_model: default_model.clone(),
                    models: models.clone(),
                },
            );
        }
        return json!({ "defaultModel": default_model, "models": models });
    }
    json!({ "defaultModel": Value::Null, "models": [] })
}

pub async fn get_agent_models(agent: &str, force_refresh: bool) -> Value {
    if agent == "agy" {
        let res = discover_agy_models(force_refresh, None).await;
        return json!({
            "agent": agent,
            "defaultModel": res.get("defaultModel").cloned().unwrap_or(Value::Null),
            "models": res.get("models").cloned().unwrap_or_else(|| json!([])),
        });
    }
    if agent == "claude" || agent == "codex" {
        let (default_model, models) = if agent == "claude" {
            read_claude_models_from_disk(None)
        } else {
            read_codex_models_from_disk(None)
        };
        if let Ok(mut guard) = cache().lock() {
            guard.insert(
                agent.to_string(),
                CachedModels {
                    fetched_at: now_ms(),
                    default_model: default_model.clone(),
                    models: models.clone(),
                },
            );
        }
        return json!({
            "agent": agent,
            "defaultModel": default_model,
            "models": models,
        });
    }
    json!({ "agent": agent, "defaultModel": Value::Null, "models": [] })
}

pub fn warm_agent_models_cache() {
    kick_agy_discovery();
    let _ = get_cached_agent_models("claude");
    let _ = get_cached_agent_models("codex");
}
