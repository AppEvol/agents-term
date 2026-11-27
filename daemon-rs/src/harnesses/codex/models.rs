//! Model list for the Codex harness, read from Codex's config and model cache.

use regex::Regex;
use serde_json::{json, Value};
use std::collections::HashSet;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::OnceLock;

use crate::config::home_dir;
use crate::util::is_number;

use crate::models::{nonblank, read_json_safe};

fn codex_model_re() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| Regex::new(r#"(?m)^\s*model\s*=\s*"([^"]+)""#).unwrap())
}

pub(crate) fn read_models_from_disk(home: Option<&Path>) -> (Option<String>, Vec<Value>) {
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
