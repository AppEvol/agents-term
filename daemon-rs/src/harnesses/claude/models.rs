//! Model list for the Claude Code harness, read from Claude's settings and model catalog.

use serde_json::{json, Value};
use std::collections::HashSet;
use std::fs;
use std::path::{Path, PathBuf};

use crate::config::home_dir;

use crate::models::{nonblank, read_json_safe};

pub(crate) fn read_models_from_disk(home: Option<&Path>) -> (Option<String>, Vec<Value>) {
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
