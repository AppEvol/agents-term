//! Shared model-list cache and the `/api/agents/{agent}/models` entry points.
//! Each harness discovers its own models (see `crate::harnesses`).

use serde_json::{json, Value};
use std::collections::HashMap;
use std::fs;
use std::path::Path;
use std::sync::{Mutex, OnceLock};

use crate::harnesses::{self, ModelList};
use crate::util::now_ms;

const DISK_CACHE_TTL_MS: i64 = 60_000;

/// Reads a harness's default model and model list from its local files (`None` = real home).
pub(crate) type DiskModelReader = fn(Option<&Path>) -> (Option<String>, Vec<Value>);

#[derive(Clone)]
pub(crate) struct CachedModels {
    pub(crate) fetched_at: i64,
    pub(crate) default_model: Option<String>,
    pub(crate) models: Vec<Value>,
}

fn cache() -> &'static Mutex<HashMap<String, CachedModels>> {
    static CACHE: OnceLock<Mutex<HashMap<String, CachedModels>>> = OnceLock::new();
    CACHE.get_or_init(|| Mutex::new(HashMap::new()))
}

pub(crate) fn cached(kind: &str) -> Option<CachedModels> {
    cache().lock().ok().and_then(|c| c.get(kind).cloned())
}

pub(crate) fn store(kind: &str, entry: CachedModels) {
    if let Ok(mut guard) = cache().lock() {
        guard.insert(kind.to_string(), entry);
    }
}

pub(crate) fn nonblank(v: Option<&Value>) -> Option<String> {
    v.and_then(Value::as_str)
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_string)
}

pub(crate) fn read_json_safe(path: &Path) -> Option<serde_json::Map<String, Value>> {
    let raw = fs::read_to_string(path).ok()?;
    match serde_json::from_str::<Value>(&raw).ok()? {
        Value::Object(map) => Some(map),
        _ => None,
    }
}

/// Reads a harness's model list from its local files, reusing a read from the last minute.
pub(crate) fn cached_disk_models(kind: &str, read: DiskModelReader) -> ModelList {
    if let Some(c) = cached(kind).filter(|c| now_ms() - c.fetched_at < DISK_CACHE_TTL_MS) {
        return ModelList {
            default_model: c.default_model,
            models: c.models,
        };
    }
    refresh_disk_models(kind, read)
}

/// Reads a harness's model list from its local files and caches it.
pub(crate) fn refresh_disk_models(kind: &str, read: DiskModelReader) -> ModelList {
    let (default_model, models) = read(None);
    store(
        kind,
        CachedModels {
            fetched_at: now_ms(),
            default_model: default_model.clone(),
            models: models.clone(),
        },
    );
    ModelList {
        default_model,
        models,
    }
}

pub fn get_cached_agent_models(agent: &str) -> Value {
    let list = harnesses::get(agent)
        .map(|h| h.cached_models())
        .unwrap_or_default();
    json!({ "defaultModel": list.default_model, "models": list.models })
}

pub async fn get_agent_models(agent: &str, force_refresh: bool) -> Value {
    let list = match harnesses::get(agent) {
        Some(h) => h.models(force_refresh).await,
        None => ModelList::default(),
    };
    json!({
        "agent": agent,
        "defaultModel": list.default_model,
        "models": list.models,
    })
}

pub fn warm_agent_models_cache() {
    for harness in harnesses::all() {
        let _ = harness.cached_models();
    }
}
