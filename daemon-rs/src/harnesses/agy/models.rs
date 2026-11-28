//! Model discovery for the Antigravity harness: runs `agy models` and caches the result.

use regex::Regex;
use serde_json::{json, Value};
use std::collections::HashSet;
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};
use std::time::Duration;
use tokio::process::Command;
use tokio::sync::broadcast;

use crate::config::{enriched_path, home_dir, resolve_binary};
use crate::util::now_ms;

use crate::harnesses::ModelList;
use crate::models::{cached, nonblank, read_json_safe, store, CachedModels};

const AGY_CACHE_TTL_MS: i64 = 5 * 60_000;

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
    RE.get_or_init(|| Regex::new(r"^([A-Za-z0-9][A-Za-z0-9._:/-]*)(?:\t+|\s{2,})(.+)$").unwrap())
}

fn fetching_re() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| Regex::new(r"(?i)fetching available models").unwrap())
}

pub(crate) fn parse_agy_models_output(raw: &str) -> Vec<Value> {
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
        .stderr(std::process::Stdio::piped())
        .kill_on_drop(true);

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

/// Clears the single-flight slot on normal completion, cancellation, or panic so
/// subsequent callers can never deadlock waiting on a dropped leader.
struct InFlightGuard;

impl Drop for InFlightGuard {
    fn drop(&mut self) {
        if let Ok(mut guard) = agy_in_flight().lock() {
            guard.take();
        }
    }
}

async fn execute_agy_discovery<F, Fut>(
    existing_cached: Option<CachedModels>,
    home: Option<PathBuf>,
    run_models: F,
) -> Value
where
    F: FnOnce() -> Fut,
    Fut: std::future::Future<Output = Option<(String, bool)>>,
{
    let default_model = read_agy_default_model(home.as_deref());
    let result_cached = if let Some((combined, err)) = run_models().await {
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

    store("agy", result_cached.clone());
    json!({
        "fetchedAt": result_cached.fetched_at,
        "defaultModel": result_cached.default_model,
        "models": result_cached.models,
    })
}

async fn discover_agy_models_with<F, Fut>(force: bool, home: Option<&Path>, run_models: F) -> Value
where
    F: Fn() -> Fut + Send + Sync + 'static,
    Fut: std::future::Future<Output = Option<(String, bool)>> + Send + 'static,
{
    let existing_cached = cached("agy");
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

    loop {
        let (rx, is_leader) = {
            let mut guard = agy_in_flight().lock().unwrap();
            if let Some(ref tx) = *guard {
                (tx.subscribe(), false)
            } else {
                let (tx, rx) = broadcast::channel(1);
                *guard = Some(tx);
                (rx, true)
            }
        };

        if !is_leader {
            let mut rx = rx;
            if let Ok(val) = rx.recv().await {
                return val;
            }
            // The previous leader dropped without publishing (e.g. cancelled or panicked);
            // loop to either join a replacement leader or start a fresh discovery.
            continue;
        }

        let _in_flight = InFlightGuard;
        let val =
            execute_agy_discovery(existing_cached.clone(), home.map(Path::to_path_buf), || {
                run_models()
            })
            .await;
        if let Ok(mut guard) = agy_in_flight().lock() {
            if let Some(tx) = guard.take() {
                let _ = tx.send(val.clone());
            }
        }
        return val;
    }
}

pub(crate) async fn discover_agy_models(force: bool, home: Option<&Path>) -> Value {
    discover_agy_models_with(force, home, || async {
        match resolve_binary("agy") {
            Some(binary) => Some(run_agy_models(&binary).await),
            None => None,
        }
    })
    .await
}

fn kick_agy_discovery() {
    if let Ok(handle) = tokio::runtime::Handle::try_current() {
        handle.spawn(async {
            let _ = discover_agy_models(false, None).await;
        });
    }
}

/// The cached model list; starts a background `agy models` run when it is stale.
pub(crate) fn cached_models() -> ModelList {
    let now = now_ms();
    let cached = cached("agy");
    let stale = cached
        .as_ref()
        .map(|c| now - c.fetched_at >= AGY_CACHE_TTL_MS)
        .unwrap_or(true);
    let in_flight = agy_in_flight().lock().map(|g| g.is_some()).unwrap_or(false);
    if stale && !in_flight {
        kick_agy_discovery();
    }
    match cached {
        Some(c) => ModelList {
            default_model: c.default_model,
            models: c.models,
        },
        None => ModelList {
            default_model: read_agy_default_model(None),
            models: Vec::new(),
        },
    }
}

pub(crate) async fn models(force_refresh: bool) -> ModelList {
    let res = discover_agy_models(force_refresh, None).await;
    ModelList {
        default_model: res
            .get("defaultModel")
            .and_then(Value::as_str)
            .map(str::to_string),
        models: res
            .get("models")
            .and_then(Value::as_array)
            .cloned()
            .unwrap_or_default(),
    }
}

#[cfg(test)]
mod tests {
    use super::{agy_in_flight, discover_agy_models_with, parse_agy_models_output};
    use serde_json::json;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::Arc;
    use std::time::Duration;
    use tokio::sync::Notify;

    #[test]
    fn parses_space_and_tab_separated_agy_models_output() {
        let sample = [
            "Fetching available models...",
            "\r\x1b[36m⠋ Fetching available models...\x1b[0m\r\x1b[2Kgemini-4-neon-high\tGemini 4 Neon (High)",
            "gemini-4-neon-xhigh\tGemini 4 Neon (XHigh)",
            "claude-opus-5-5-high           Claude Opus 5.5 (High)",
            "gemini-4-neon-high\tDuplicate Should Be Ignored",
            "Not a model line",
            "",
        ]
        .join("\n");
        assert_eq!(
            parse_agy_models_output(&sample),
            vec![
                json!({ "id": "gemini-4-neon-high", "name": "Gemini 4 Neon (High)" }),
                json!({ "id": "gemini-4-neon-xhigh", "name": "Gemini 4 Neon (XHigh)" }),
                json!({ "id": "claude-opus-5-5-high", "name": "Claude Opus 5.5 (High)" }),
            ]
        );
    }

    #[tokio::test]
    async fn cancelled_leader_does_not_deadlock_waiting_or_subsequent_callers() {
        // Ensure a clean in-flight slot before the test starts.
        agy_in_flight().lock().unwrap().take();

        let started = Arc::new(Notify::new());
        let gate = Arc::new(Notify::new());
        let calls = Arc::new(AtomicUsize::new(0));

        let leader_started = Arc::clone(&started);
        let leader_gate = Arc::clone(&gate);
        let leader_calls = Arc::clone(&calls);
        let leader = tokio::spawn(async move {
            discover_agy_models_with(true, None, move || {
                let started = Arc::clone(&leader_started);
                let gate = Arc::clone(&leader_gate);
                let calls = Arc::clone(&leader_calls);
                async move {
                    calls.fetch_add(1, Ordering::SeqCst);
                    started.notify_one();
                    gate.notified().await;
                    Some(("model-a\tModel A".to_string(), false))
                }
            })
            .await
        });

        // Wait until the leader has entered the single-flight runner.
        started.notified().await;

        // Spawn a waiter that subscribes to the leader's broadcast channel.
        let waiter_calls = Arc::clone(&calls);
        let waiter = tokio::spawn(async move {
            discover_agy_models_with(true, None, move || {
                let calls = Arc::clone(&waiter_calls);
                async move {
                    calls.fetch_add(1, Ordering::SeqCst);
                    Some(("model-b\tModel B".to_string(), false))
                }
            })
            .await
        });

        // Give the waiter a moment to subscribe to the in-flight broadcast channel, then abort the leader.
        tokio::task::yield_now().await;
        tokio::time::sleep(Duration::from_millis(20)).await;
        leader.abort();
        let _ = leader.await;

        // Waiter must wake up and succeed instead of hanging forever.
        let waiter_result = tokio::time::timeout(Duration::from_secs(2), waiter)
            .await
            .expect("waiter deadlocked after leader cancellation")
            .expect("waiter task panicked");
        let models = waiter_result
            .get("models")
            .and_then(|v| v.as_array())
            .expect("expected models array");
        assert_eq!(models.len(), 1);
        assert_eq!(models[0]["id"], "model-b");
        assert!(
            agy_in_flight().lock().unwrap().is_none(),
            "in-flight slot should be cleared after completion"
        );
        assert!(calls.load(Ordering::SeqCst) >= 2);
    }
}
