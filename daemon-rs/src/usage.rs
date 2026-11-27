use chrono::{DateTime, TimeZone, Utc};
use serde_json::{json, Value};
use std::collections::{BTreeMap, HashMap, HashSet};
use std::fs;
use std::path::{Path, PathBuf};
use std::time::Duration;
use tokio::io::AsyncWriteExt;
use tokio::process::Command;

use crate::config::{enriched_path, home_dir, resolve_binary};
use crate::models::discover_agy_models;
use crate::util::now_ms;

const HTTP_TIMEOUT_SECS: u64 = 12;

fn round1(v: f64) -> f64 {
    (v * 10.0).round() / 10.0
}

fn clamp_percent(v: f64) -> f64 {
    round1(v.clamp(0.0, 100.0))
}

fn parse_rfc3339_ms(raw: &str) -> Option<i64> {
    DateTime::parse_from_rfc3339(raw.trim())
        .ok()
        .map(|dt| dt.timestamp_millis())
}

fn epoch_secs_to_iso(secs: i64) -> Option<String> {
    Utc.timestamp_opt(secs, 0)
        .single()
        .map(|dt| dt.format("%Y-%m-%dT%H:%M:%SZ").to_string())
}

fn format_window_label(seconds: i64) -> String {
    if seconds <= 0 {
        return "Usage limit".to_string();
    }
    if seconds % 86_400 == 0 {
        let days = seconds / 86_400;
        if days == 7 {
            "Weekly limit".to_string()
        } else if days == 1 {
            "Daily limit".to_string()
        } else {
            format!("{days}-day limit")
        }
    } else if seconds % 3_600 == 0 {
        let hours = seconds / 3_600;
        format!("{hours}-hour limit")
    } else if seconds % 60 == 0 {
        let mins = seconds / 60;
        format!("{mins}-minute limit")
    } else {
        format!("{seconds}s limit")
    }
}

fn strip_thinking_suffix(name: &str) -> String {
    let trimmed = name.trim();
    for suffix in [" (Low)", " (Medium)", " (High)", " (XHigh)", " (Max)"] {
        if let Some(base) = trimmed.strip_suffix(suffix) {
            return base.trim().to_string();
        }
    }
    trimmed.to_string()
}

fn escape_curl_config_value(val: &str) -> String {
    val.replace('\\', "\\\\").replace('"', "\\\"")
}

/// Runs `curl` passing all headers (including Bearer tokens) via stdin config (`-K -`) so
/// credentials are never exposed in process command-line arguments.
async fn curl_request(
    method: &str,
    url: &str,
    headers: &[(&str, String)],
    body: Option<&str>,
) -> Result<(u16, String), String> {
    let curl_bin = resolve_binary("curl").unwrap_or_else(|| "curl".to_string());
    let mut cmd = Command::new(curl_bin);
    cmd.args([
        "-sS",
        "--max-time",
        &HTTP_TIMEOUT_SECS.to_string(),
        "-X",
        method,
        "-w",
        "\n%{http_code}",
        "-K",
        "-",
        url,
    ])
    .env("PATH", enriched_path())
    .stdin(std::process::Stdio::piped())
    .stdout(std::process::Stdio::piped())
    .stderr(std::process::Stdio::piped());

    if let Some(payload) = body {
        cmd.args(["--data-binary", payload]);
    }

    let mut child = cmd
        .spawn()
        .map_err(|e| format!("Failed to launch curl: {e}"))?;

    if let Some(mut stdin) = child.stdin.take() {
        let mut cfg = String::new();
        for (k, v) in headers {
            let escaped = escape_curl_config_value(&format!("{k}: {v}"));
            cfg.push_str(&format!("header = \"{escaped}\"\n"));
        }
        let _ = stdin.write_all(cfg.as_bytes()).await;
        let _ = stdin.shutdown().await;
    }

    let output = tokio::time::timeout(
        Duration::from_secs(HTTP_TIMEOUT_SECS + 3),
        child.wait_with_output(),
    )
    .await
    .map_err(|_| "Usage request timed out".to_string())?
    .map_err(|e| format!("Failed waiting for curl: {e}"))?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr).trim().to_string();
        return Err(if stderr.is_empty() {
            "Network request failed".to_string()
        } else {
            format!("Network request failed: {stderr}")
        });
    }

    let stdout = String::from_utf8_lossy(&output.stdout);
    let (resp_body, code_str) = stdout.rsplit_once('\n').unwrap_or(("", stdout.trim()));
    let status: u16 = code_str
        .trim()
        .parse()
        .map_err(|_| format!("Malformed HTTP status from curl: '{}'", code_str.trim()))?;
    Ok((status, resp_body.to_string()))
}

fn read_json_file(path: &Path) -> Option<Value> {
    let raw = fs::read_to_string(path).ok()?;
    serde_json::from_str::<Value>(&raw).ok()
}

fn parse_agy_access_token(parsed: &Value) -> Option<String> {
    parsed
        .get("token")
        .and_then(|t| t.get("access_token"))
        .and_then(Value::as_str)
        .or_else(|| parsed.get("access_token").and_then(Value::as_str))
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_string)
}

const SECRET_SERVICE: &str = "org.freedesktop.secrets";
const AGY_KEYRING_TIMEOUT: Duration = Duration::from_secs(3);

/// Reads the secret agy keeps in the Secret Service keyring (service `gemini`,
/// username `antigravity`). Locked items are skipped: unlocking would prompt on the VM's desktop.
async fn read_agy_keyring_secret() -> Option<String> {
    use zbus::zvariant::{OwnedObjectPath, OwnedValue, Value as ZValue};

    let conn = zbus::Connection::session().await.ok()?;
    let reply = conn
        .call_method(
            Some(SECRET_SERVICE),
            "/org/freedesktop/secrets",
            Some("org.freedesktop.Secret.Service"),
            "OpenSession",
            &("plain", ZValue::from("")),
        )
        .await
        .ok()?;
    let (_, session): (OwnedValue, OwnedObjectPath) = reply.body().deserialize().ok()?;

    let attributes = HashMap::from([("service", "gemini"), ("username", "antigravity")]);
    let reply = conn
        .call_method(
            Some(SECRET_SERVICE),
            "/org/freedesktop/secrets",
            Some("org.freedesktop.Secret.Service"),
            "SearchItems",
            &(attributes,),
        )
        .await
        .ok()?;
    let (unlocked, _locked): (Vec<OwnedObjectPath>, Vec<OwnedObjectPath>) =
        reply.body().deserialize().ok()?;
    let item = unlocked.first()?;

    let reply = conn
        .call_method(
            Some(SECRET_SERVICE),
            item,
            Some("org.freedesktop.Secret.Item"),
            "GetSecret",
            &(session,),
        )
        .await
        .ok()?;
    let (_, _, secret, _): (OwnedObjectPath, Vec<u8>, Vec<u8>, String) =
        reply.body().deserialize().ok()?;
    String::from_utf8(secret).ok()
}

/// agy keeps its sign-in in the system keyring when one is available, and in a token file otherwise.
async fn read_agy_access_token(home: &Path) -> Option<String> {
    let from_keyring = tokio::time::timeout(AGY_KEYRING_TIMEOUT, read_agy_keyring_secret())
        .await
        .ok()
        .flatten()
        .and_then(|raw| serde_json::from_str::<Value>(&raw).ok())
        .and_then(|parsed| parse_agy_access_token(&parsed));
    if from_keyring.is_some() {
        return from_keyring;
    }
    let path = home
        .join(".gemini")
        .join("antigravity-cli")
        .join("antigravity-oauth-token");
    parse_agy_access_token(&read_json_file(&path)?)
}

pub fn parse_agy_usage(data: &Value) -> Vec<Value> {
    let Some(models_obj) = data.get("models").and_then(Value::as_object) else {
        return Vec::new();
    };

    struct RawEntry {
        id: String,
        display_name: Option<String>,
        remaining_pct: f64,
        resets_at: Option<String>,
        resets_at_ms: Option<i64>,
    }

    let mut entries: Vec<RawEntry> = Vec::new();
    for (id, val) in models_obj {
        if id.starts_with("tab_") || id.ends_with("-image") {
            continue;
        }
        let Some(qi) = val.get("quotaInfo").and_then(Value::as_object) else {
            continue;
        };
        let Some(frac) = qi.get("remainingFraction").and_then(Value::as_f64) else {
            continue;
        };
        let remaining_pct = clamp_percent(frac * 100.0);
        let resets_at = qi
            .get("resetTime")
            .and_then(Value::as_str)
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(str::to_string);
        let resets_at_ms = resets_at.as_deref().and_then(parse_rfc3339_ms);
        let display_name = val
            .get("displayName")
            .and_then(Value::as_str)
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(str::to_string);
        entries.push(RawEntry {
            id: id.clone(),
            display_name,
            remaining_pct,
            resets_at,
            resets_at_ms,
        });
    }

    // Group entries cleanly:
    // - Gemini models that share the exact same (remaining_pct, resets_at) merge into a
    //   single "Gemini Models" bucket (with `modelPrefixes: ["gemini-"]`) when > 1 model
    //   shares the bucket, so the mobile sheet isn't flooded with duplicate rows.
    // - Other models group by stripped display name (merging Low/Medium/High thinking tiers
    //   that share the same quota).
    #[derive(Default)]
    struct Bucket {
        label: String,
        remaining_pct: f64,
        resets_at: Option<String>,
        resets_at_ms: Option<i64>,
        model_ids: Vec<String>,
        model_prefixes: Vec<String>,
    }

    let mut gemini_by_quota: BTreeMap<(i64, Option<String>), Vec<&RawEntry>> = BTreeMap::new();
    let mut non_gemini: Vec<&RawEntry> = Vec::new();
    for entry in &entries {
        if entry.id.starts_with("gemini-") {
            let key = (
                (entry.remaining_pct * 10.0).round() as i64,
                entry.resets_at.clone(),
            );
            gemini_by_quota.entry(key).or_default().push(entry);
        } else if entry.display_name.is_some() {
            non_gemini.push(entry);
        }
    }

    let mut buckets: BTreeMap<String, Bucket> = BTreeMap::new();

    // Non-Gemini model families (e.g. Claude Opus 5.5, Claude Sonnet 5.5, GPT-OSS 120B).
    for entry in non_gemini {
        let base_name = entry
            .display_name
            .as_deref()
            .map(strip_thinking_suffix)
            .unwrap_or_else(|| entry.id.clone());
        let key = format!(
            "other:{}:{}:{}",
            base_name.to_lowercase(),
            (entry.remaining_pct * 10.0).round() as i64,
            entry.resets_at.as_deref().unwrap_or("")
        );
        let b = buckets.entry(key).or_insert_with(|| Bucket {
            label: base_name,
            remaining_pct: entry.remaining_pct,
            resets_at: entry.resets_at.clone(),
            resets_at_ms: entry.resets_at_ms,
            model_ids: Vec::new(),
            model_prefixes: Vec::new(),
        });
        b.model_ids.push(entry.id.clone());
    }

    // Gemini quota buckets.
    let single_gemini_bucket = gemini_by_quota.len() == 1;
    for ((pct_tenths, resets_at), group) in gemini_by_quota {
        let first = group[0];
        let label = if group.len() > 1 {
            if single_gemini_bucket {
                "Gemini Models".to_string()
            } else {
                let first_named = group
                    .iter()
                    .find_map(|e| e.display_name.as_deref())
                    .map(strip_thinking_suffix)
                    .unwrap_or_else(|| "Gemini Models".to_string());
                format!("{first_named} (+{} more)", group.len() - 1)
            }
        } else {
            first
                .display_name
                .as_deref()
                .map(strip_thinking_suffix)
                .unwrap_or_else(|| first.id.clone())
        };
        let mut model_ids: Vec<String> = group.iter().map(|e| e.id.clone()).collect();
        model_ids.sort();
        let model_prefixes = if single_gemini_bucket {
            vec!["gemini-".to_string()]
        } else {
            Vec::new()
        };
        let key = format!(
            "gemini:{}:{}",
            pct_tenths,
            resets_at.as_deref().unwrap_or("")
        );
        buckets.insert(
            key,
            Bucket {
                label,
                remaining_pct: first.remaining_pct,
                resets_at,
                resets_at_ms: first.resets_at_ms,
                model_ids,
                model_prefixes,
            },
        );
    }

    let mut out: Vec<Value> = buckets
        .into_values()
        .map(|mut b| {
            b.model_ids.sort();
            b.model_ids.dedup();
            let used_pct = clamp_percent(100.0 - b.remaining_pct);
            let id = b
                .model_ids
                .first()
                .cloned()
                .unwrap_or_else(|| b.label.to_lowercase().replace(' ', "-"));
            json!({
                "id": id,
                "label": b.label,
                "subtitle": Value::Null,
                "remainingPercent": b.remaining_pct,
                "usedPercent": used_pct,
                "resetsAt": b.resets_at,
                "resetsAtMs": b.resets_at_ms,
                "modelIds": b.model_ids,
                "modelPrefixes": b.model_prefixes,
            })
        })
        .collect();

    // Sort Gemini first, then alphabetically by label for stable display.
    out.sort_by(|a, b| {
        let la = a.get("label").and_then(Value::as_str).unwrap_or("");
        let lb = b.get("label").and_then(Value::as_str).unwrap_or("");
        let ga = !la.starts_with("Gemini");
        let gb = !lb.starts_with("Gemini");
        ga.cmp(&gb).then_with(|| la.cmp(lb))
    });
    out
}

pub fn parse_claude_usage(data: &Value) -> Vec<Value> {
    let mut items: Vec<Value> = Vec::new();
    let mut seen_kinds: HashSet<String> = HashSet::new();

    if let Some(limits) = data.get("limits").and_then(Value::as_array) {
        for (idx, lim) in limits.iter().enumerate() {
            let Some(obj) = lim.as_object() else {
                continue;
            };
            let Some(used_raw) = obj.get("percent").and_then(Value::as_f64) else {
                continue;
            };
            let kind = obj.get("kind").and_then(Value::as_str).unwrap_or("limit");
            let used_pct = clamp_percent(used_raw);
            let remaining_pct = clamp_percent(100.0 - used_pct);
            let resets_at = obj
                .get("resets_at")
                .and_then(Value::as_str)
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(str::to_string);
            let resets_at_ms = resets_at.as_deref().and_then(parse_rfc3339_ms);

            let mut model_ids: Vec<String> = Vec::new();
            let (id, label, subtitle) = match kind {
                "session" => {
                    seen_kinds.insert("five_hour".to_string());
                    (
                        "claude-session".to_string(),
                        "5-hour session".to_string(),
                        Some("All models".to_string()),
                    )
                }
                "weekly_all" => {
                    seen_kinds.insert("seven_day".to_string());
                    (
                        "claude-weekly-all".to_string(),
                        "Weekly limit".to_string(),
                        Some("All models".to_string()),
                    )
                }
                "weekly_scoped" => {
                    let scope = obj.get("scope").and_then(Value::as_object);
                    let model_obj = scope
                        .and_then(|s| s.get("model"))
                        .and_then(Value::as_object);
                    let scope_name = model_obj
                        .and_then(|m| m.get("display_name").and_then(Value::as_str))
                        .or_else(|| model_obj.and_then(|m| m.get("id").and_then(Value::as_str)))
                        .or_else(|| scope.and_then(|s| s.get("surface").and_then(Value::as_str)))
                        .unwrap_or("Scoped");
                    if let Some(mid) = model_obj
                        .and_then(|m| m.get("id"))
                        .and_then(Value::as_str)
                        .filter(|s| !s.is_empty())
                    {
                        model_ids.push(mid.to_string());
                    }
                    seen_kinds.insert(format!("scoped:{}", scope_name.to_lowercase()));
                    (
                        format!("claude-weekly-scoped-{idx}"),
                        format!("Weekly · {scope_name}"),
                        Some("Model-specific limit".to_string()),
                    )
                }
                other => (
                    format!("claude-{other}-{idx}"),
                    other.replace('_', " "),
                    None,
                ),
            };

            items.push(json!({
                "id": id,
                "label": label,
                "subtitle": subtitle,
                "remainingPercent": remaining_pct,
                "usedPercent": used_pct,
                "resetsAt": resets_at,
                "resetsAtMs": resets_at_ms,
                "modelIds": model_ids,
                "modelPrefixes": [],
            }));
        }
    }

    struct TopFieldSpec {
        field: &'static str,
        id: &'static str,
        label: &'static str,
        subtitle: Option<&'static str>,
        model_ids: &'static [&'static str],
    }

    // Supplement / fallback from top-level fields if not already covered by `limits`.
    let top_fields: &[TopFieldSpec] = &[
        TopFieldSpec {
            field: "five_hour",
            id: "claude-five-hour",
            label: "5-hour session",
            subtitle: Some("All models"),
            model_ids: &[],
        },
        TopFieldSpec {
            field: "seven_day",
            id: "claude-seven-day",
            label: "Weekly limit",
            subtitle: Some("All models"),
            model_ids: &[],
        },
        TopFieldSpec {
            field: "seven_day_opus",
            id: "claude-seven-day-opus",
            label: "Weekly · Opus",
            subtitle: Some("Opus models"),
            model_ids: &["opus"],
        },
        TopFieldSpec {
            field: "seven_day_sonnet",
            id: "claude-seven-day-sonnet",
            label: "Weekly · Sonnet",
            subtitle: Some("Sonnet models"),
            model_ids: &["sonnet"],
        },
    ];

    for spec in top_fields {
        if seen_kinds.contains(spec.field) {
            continue;
        }
        let Some(obj) = data.get(spec.field).and_then(Value::as_object) else {
            continue;
        };
        let Some(used_raw) = obj.get("utilization").and_then(Value::as_f64) else {
            continue;
        };
        let used_pct = clamp_percent(used_raw);
        let remaining_pct = clamp_percent(100.0 - used_pct);
        let resets_at = obj
            .get("resets_at")
            .and_then(Value::as_str)
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(str::to_string);
        let resets_at_ms = resets_at.as_deref().and_then(parse_rfc3339_ms);
        let model_ids: Vec<String> = spec.model_ids.iter().map(|s| (*s).to_string()).collect();
        items.push(json!({
            "id": spec.id,
            "label": spec.label,
            "subtitle": spec.subtitle,
            "remainingPercent": remaining_pct,
            "usedPercent": used_pct,
            "resetsAt": resets_at,
            "resetsAtMs": resets_at_ms,
            "modelIds": model_ids,
            "modelPrefixes": [],
        }));
    }

    items
}

fn push_codex_window(
    items: &mut Vec<Value>,
    id: String,
    prefix_label: Option<&str>,
    window: &Value,
    model_ids: Vec<String>,
) {
    let Some(obj) = window.as_object() else {
        return;
    };
    let Some(used_raw) = obj
        .get("used_percent")
        .or_else(|| obj.get("usedPercent"))
        .and_then(Value::as_f64)
    else {
        return;
    };
    let used_pct = clamp_percent(used_raw);
    let remaining_pct = clamp_percent(100.0 - used_pct);

    let window_secs = obj
        .get("limit_window_seconds")
        .and_then(Value::as_i64)
        .or_else(|| {
            obj.get("windowDurationMins")
                .and_then(Value::as_i64)
                .map(|m| m * 60)
        })
        .unwrap_or(0);
    let window_label = format_window_label(window_secs);
    let (label, subtitle) = match prefix_label {
        Some(p) if !p.is_empty() => (p.to_string(), Some(window_label)),
        _ => (window_label, None),
    };

    let reset_secs = obj
        .get("reset_at")
        .or_else(|| obj.get("resetsAt"))
        .and_then(Value::as_i64)
        .or_else(|| {
            obj.get("reset_after_seconds")
                .and_then(Value::as_i64)
                .map(|after| (now_ms() / 1000) + after)
        });
    let resets_at = reset_secs.and_then(epoch_secs_to_iso);
    let resets_at_ms = reset_secs.map(|s| s * 1000);

    items.push(json!({
        "id": id,
        "label": label,
        "subtitle": subtitle,
        "remainingPercent": remaining_pct,
        "usedPercent": used_pct,
        "resetsAt": resets_at,
        "resetsAtMs": resets_at_ms,
        "modelIds": model_ids,
        "modelPrefixes": [],
    }));
}

pub fn parse_codex_usage(data: &Value) -> (Option<String>, Vec<Value>) {
    let plan = data
        .get("plan_type")
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_string);

    let mut items = Vec::new();
    if let Some(rl) = data.get("rate_limit").and_then(Value::as_object) {
        if let Some(primary) = rl.get("primary_window").filter(|v| v.is_object()) {
            push_codex_window(
                &mut items,
                "codex-primary".to_string(),
                None,
                primary,
                Vec::new(),
            );
        }
        if let Some(secondary) = rl.get("secondary_window").filter(|v| v.is_object()) {
            push_codex_window(
                &mut items,
                "codex-secondary".to_string(),
                None,
                secondary,
                Vec::new(),
            );
        }
    }

    if let Some(extra_list) = data.get("additional_rate_limits").and_then(Value::as_array) {
        for (idx, entry) in extra_list.iter().enumerate() {
            let Some(obj) = entry.as_object() else {
                continue;
            };
            let name = obj
                .get("limit_name")
                .and_then(Value::as_str)
                .or_else(|| obj.get("metered_feature").and_then(Value::as_str))
                .unwrap_or("Additional limit");
            if let Some(rl) = obj.get("rate_limit").and_then(Value::as_object) {
                if let Some(primary) = rl.get("primary_window").filter(|v| v.is_object()) {
                    push_codex_window(
                        &mut items,
                        format!("codex-extra-{idx}-primary"),
                        Some(name),
                        primary,
                        Vec::new(),
                    );
                }
                if let Some(secondary) = rl.get("secondary_window").filter(|v| v.is_object()) {
                    push_codex_window(
                        &mut items,
                        format!("codex-extra-{idx}-secondary"),
                        Some(name),
                        secondary,
                        Vec::new(),
                    );
                }
            }
        }
    }

    if let Some(cr) = data
        .get("code_review_rate_limit")
        .and_then(Value::as_object)
    {
        if let Some(primary) = cr.get("primary_window").filter(|v| v.is_object()) {
            push_codex_window(
                &mut items,
                "codex-code-review".to_string(),
                Some("Code review"),
                primary,
                Vec::new(),
            );
        }
    }

    (plan, items)
}

pub fn parse_codex_rpc_usage(result: &Value) -> (Option<String>, Vec<Value>) {
    let mut plan: Option<String> = None;
    let mut items = Vec::new();

    if let Some(by_id) = result.get("rateLimitsByLimitId").and_then(Value::as_object) {
        for (limit_id, entry) in by_id {
            let Some(obj) = entry.as_object() else {
                continue;
            };
            if plan.is_none() {
                plan = obj
                    .get("planType")
                    .and_then(Value::as_str)
                    .map(str::to_string);
            }
            let prefix = obj
                .get("limitName")
                .and_then(Value::as_str)
                .filter(|s| !s.is_empty());
            let mut model_ids = Vec::new();
            if let Some(slug) = obj
                .get("normalModelSlug")
                .and_then(Value::as_str)
                .filter(|s| !s.is_empty())
            {
                model_ids.push(slug.to_string());
            }
            if let Some(primary) = obj.get("primary").filter(|v| v.is_object()) {
                push_codex_window(
                    &mut items,
                    format!("codex-{limit_id}-primary"),
                    prefix,
                    primary,
                    model_ids.clone(),
                );
            }
            if let Some(secondary) = obj.get("secondary").filter(|v| v.is_object()) {
                push_codex_window(
                    &mut items,
                    format!("codex-{limit_id}-secondary"),
                    prefix,
                    secondary,
                    model_ids,
                );
            }
        }
    } else if let Some(rl) = result.get("rateLimits").and_then(Value::as_object) {
        plan = rl
            .get("planType")
            .and_then(Value::as_str)
            .map(str::to_string);
        if let Some(primary) = rl.get("primary").filter(|v| v.is_object()) {
            push_codex_window(
                &mut items,
                "codex-primary".to_string(),
                None,
                primary,
                Vec::new(),
            );
        }
        if let Some(secondary) = rl.get("secondary").filter(|v| v.is_object()) {
            push_codex_window(
                &mut items,
                "codex-secondary".to_string(),
                None,
                secondary,
                Vec::new(),
            );
        }
    }

    (plan, items)
}

async fn fetch_agy_usage(home: &Path) -> Value {
    let mut token = read_agy_access_token(home).await;
    if token.is_none() {
        // Trigger `agy models` once in case agy can refresh credentials.
        let _ = discover_agy_models(true, Some(home)).await;
        token = read_agy_access_token(home).await;
    }
    let Some(mut tok) = token else {
        return error_response(
            "agy",
            "Antigravity sign-in not found in the system keyring or ~/.gemini/antigravity-cli/antigravity-oauth-token",
        );
    };

    let url = "https://daily-cloudcode-pa.googleapis.com/v1internal:fetchAvailableModels";
    for attempt in 0..2 {
        let headers = vec![
            ("Authorization", format!("Bearer {tok}")),
            ("Content-Type", "application/json".to_string()),
            ("User-Agent", "antigravity".to_string()),
        ];
        match curl_request("POST", url, &headers, Some("{}")).await {
            Ok((200, body)) => {
                let Ok(parsed) = serde_json::from_str::<Value>(&body) else {
                    return error_response("agy", "Failed to parse Antigravity usage response");
                };
                let items = parse_agy_usage(&parsed);
                return ok_response("agy", None, items);
            }
            Ok((401, _)) if attempt == 0 => {
                // Refresh OAuth token via `agy models` and retry once.
                let _ = discover_agy_models(true, Some(home)).await;
                if let Some(refreshed) = read_agy_access_token(home).await {
                    tok = refreshed;
                    continue;
                }
                return error_response(
                    "agy",
                    "Antigravity OAuth token expired — sign in with `agy` on the VM",
                );
            }
            Ok((status, _)) => {
                return error_response(
                    "agy",
                    &format!("Antigravity usage request failed (HTTP {status})"),
                );
            }
            Err(err) => return error_response("agy", &err),
        }
    }
    error_response("agy", "Antigravity usage request failed")
}

async fn fetch_claude_usage(home: &Path) -> Value {
    let creds_path = home.join(".claude").join(".credentials.json");
    let Some(creds) = read_json_file(&creds_path) else {
        return error_response(
            "claude",
            "Claude OAuth credentials not found (~/.claude/.credentials.json)",
        );
    };
    let oauth = creds.get("claudeAiOauth").and_then(Value::as_object);
    let Some(tok) = oauth
        .and_then(|o| o.get("accessToken"))
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|s| !s.is_empty())
    else {
        return error_response(
            "claude",
            "Claude OAuth access token not found (usage check requires Claude subscription login)",
        );
    };
    let plan = oauth
        .and_then(|o| o.get("subscriptionType"))
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_string);

    let headers = vec![
        ("Authorization", format!("Bearer {tok}")),
        ("anthropic-beta", "oauth-2025-04-20".to_string()),
        ("User-Agent", "claude-code/2.0".to_string()),
    ];
    match curl_request(
        "GET",
        "https://api.anthropic.com/api/oauth/usage",
        &headers,
        None,
    )
    .await
    {
        Ok((200, body)) => {
            let Ok(parsed) = serde_json::from_str::<Value>(&body) else {
                return error_response("claude", "Failed to parse Claude usage response");
            };
            let items = parse_claude_usage(&parsed);
            ok_response("claude", plan, items)
        }
        Ok((401, _)) => error_response(
            "claude",
            "Claude OAuth token expired — run `claude auth login` on the VM",
        ),
        Ok((status, _)) => error_response(
            "claude",
            &format!("Claude usage request failed (HTTP {status})"),
        ),
        Err(err) => error_response("claude", &err),
    }
}

async fn fetch_codex_usage_via_rpc() -> Result<(Option<String>, Vec<Value>), String> {
    let Some(binary) = resolve_binary("codex") else {
        return Err("Codex CLI not found on PATH".to_string());
    };
    let mut cmd = Command::new(binary);
    cmd.args(["app-server", "--stdio"])
        .env("PATH", enriched_path())
        .stdin(std::process::Stdio::piped())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::null());

    let mut child = cmd
        .spawn()
        .map_err(|e| format!("Failed to start codex app-server: {e}"))?;

    if let Some(mut stdin) = child.stdin.take() {
        let init = json!({
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": {
                "clientInfo": { "name": "agentsterm", "version": "0.3.0" },
                "capabilities": {}
            }
        });
        let read_rl = json!({
            "jsonrpc": "2.0",
            "id": 2,
            "method": "account/rateLimits/read",
            "params": {}
        });
        let payload = format!("{init}\n{read_rl}\n");
        let _ = stdin.write_all(payload.as_bytes()).await;
        let _ = stdin.flush().await;
    }

    let stdout = child
        .stdout
        .take()
        .ok_or_else(|| "Missing codex app-server stdout".to_string())?;
    let mut reader = tokio::io::BufReader::new(stdout);
    let read_fut = async {
        use tokio::io::AsyncBufReadExt;
        let mut line = String::new();
        loop {
            line.clear();
            let n = reader
                .read_line(&mut line)
                .await
                .map_err(|e| format!("Failed reading codex app-server output: {e}"))?;
            if n == 0 {
                return Err("Codex app-server exited before returning usage".to_string());
            }
            if let Ok(msg) = serde_json::from_str::<Value>(line.trim()) {
                if msg.get("id").and_then(Value::as_i64) == Some(2) {
                    if let Some(err) = msg.get("error") {
                        let emsg = err
                            .get("message")
                            .and_then(Value::as_str)
                            .unwrap_or("Codex usage RPC error");
                        return Err(emsg.to_string());
                    }
                    if let Some(res) = msg.get("result") {
                        return Ok(parse_codex_rpc_usage(res));
                    }
                }
            }
        }
    };

    let outcome = tokio::time::timeout(Duration::from_secs(10), read_fut).await;
    let _ = child.kill().await;
    match outcome {
        Ok(res) => res,
        Err(_) => Err("Timed out waiting for Codex usage".to_string()),
    }
}

async fn fetch_codex_usage(home: &Path) -> Value {
    let auth_path = home.join(".codex").join("auth.json");
    if let Some(auth) = read_json_file(&auth_path) {
        if let Some(tokens) = auth.get("tokens").and_then(Value::as_object) {
            if let Some(tok) = tokens
                .get("access_token")
                .and_then(Value::as_str)
                .map(str::trim)
                .filter(|s| !s.is_empty())
            {
                let mut headers = vec![
                    ("Authorization", format!("Bearer {tok}")),
                    ("User-Agent", "codex-cli".to_string()),
                    ("Accept", "application/json".to_string()),
                ];
                if let Some(acct) = tokens
                    .get("account_id")
                    .and_then(Value::as_str)
                    .map(str::trim)
                    .filter(|s| !s.is_empty())
                {
                    headers.push(("ChatGPT-Account-Id", acct.to_string()));
                }
                match curl_request(
                    "GET",
                    "https://chatgpt.com/backend-api/wham/usage",
                    &headers,
                    None,
                )
                .await
                {
                    Ok((200, body)) => {
                        if let Ok(parsed) = serde_json::from_str::<Value>(&body) {
                            let (plan, items) = parse_codex_usage(&parsed);
                            return ok_response("codex", plan, items);
                        }
                    }
                    Ok((401, _)) => {
                        // Fall through to `codex app-server` which refreshes OAuth tokens.
                    }
                    Ok((status, _)) => {
                        return error_response(
                            "codex",
                            &format!("Codex usage request failed (HTTP {status})"),
                        );
                    }
                    Err(err) => return error_response("codex", &err),
                }
            }
        }
    }

    match fetch_codex_usage_via_rpc().await {
        Ok((plan, items)) => ok_response("codex", plan, items),
        Err(err) => error_response("codex", &err),
    }
}

fn ok_response(agent: &str, plan: Option<String>, items: Vec<Value>) -> Value {
    json!({
        "agent": agent,
        "fetchedAt": now_ms(),
        "plan": plan,
        "items": items,
        "error": Value::Null,
    })
}

fn error_response(agent: &str, message: &str) -> Value {
    json!({
        "agent": agent,
        "fetchedAt": now_ms(),
        "plan": Value::Null,
        "items": [],
        "error": message,
    })
}

pub async fn get_agent_usage(agent: &str) -> Value {
    let home: PathBuf = home_dir();
    match agent {
        "agy" => fetch_agy_usage(&home).await,
        "claude" => fetch_claude_usage(&home).await,
        "codex" => fetch_codex_usage(&home).await,
        _ => error_response(agent, "Usage check is not supported for this agent"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_agy_access_token_from_keyring_and_legacy_shapes() {
        let keyring = json!({
            "token": { "access_token": " tok-1 ", "refresh_token": "r", "expiry": "2026-11-27T08:56:14Z" },
            "auth_method": "consumer"
        });
        assert_eq!(parse_agy_access_token(&keyring).as_deref(), Some("tok-1"));
        let flat = json!({ "access_token": "tok-2" });
        assert_eq!(parse_agy_access_token(&flat).as_deref(), Some("tok-2"));
        assert_eq!(
            parse_agy_access_token(&json!({ "token": { "access_token": "" } })),
            None
        );
        assert_eq!(parse_agy_access_token(&json!({})), None);
    }

    #[test]
    fn parses_agy_usage_and_groups_thinking_levels_and_gemini() {
        let sample = json!({
            "models": {
                "claude-opus-5-5-low": {
                    "displayName": "Claude Opus 5.5 (Low)",
                    "quotaInfo": { "remainingFraction": 0.9, "resetTime": "2026-11-26T05:59:04Z" }
                },
                "claude-opus-5-5-high": {
                    "displayName": "Claude Opus 5.5 (High)",
                    "quotaInfo": { "remainingFraction": 0.9, "resetTime": "2026-11-26T05:59:04Z" }
                },
                "gemini-3-flash": {
                    "displayName": "Gemini 3 Flash",
                    "quotaInfo": { "remainingFraction": 0.756, "resetTime": "2026-11-26T03:34:04Z" }
                },
                "gemini-3.5-flash-lite": {
                    "displayName": "Gemini 3.5 Flash Lite",
                    "quotaInfo": { "remainingFraction": 0.756, "resetTime": "2026-11-26T03:34:04Z" }
                },
                "tab_flash_lite_preview": {
                    "quotaInfo": { "remainingFraction": 1.0 }
                }
            }
        });

        let items = parse_agy_usage(&sample);
        assert_eq!(items.len(), 2);
        assert_eq!(items[0]["label"], "Gemini Models");
        assert_eq!(items[0]["remainingPercent"], 75.6);
        assert_eq!(items[0]["usedPercent"], 24.4);
        assert_eq!(items[0]["modelPrefixes"], json!(["gemini-"]));
        assert!(items[0]["resetsAtMs"].as_i64().unwrap() > 0);

        assert_eq!(items[1]["label"], "Claude Opus 5.5");
        assert_eq!(items[1]["remainingPercent"], 90.0);
        assert_eq!(
            items[1]["modelIds"],
            json!(["claude-opus-5-5-high", "claude-opus-5-5-low"])
        );
    }

    #[test]
    fn parses_claude_usage_from_limits_and_top_level() {
        let sample = json!({
            "five_hour": { "utilization": 10.0, "resets_at": "2026-11-26T06:00:00Z" },
            "seven_day": { "utilization": 22.0, "resets_at": "2026-11-30T01:59:59+00:00" },
            "seven_day_opus": { "utilization": 45.0, "resets_at": "2026-11-30T01:59:59+00:00" },
            "limits": [
                { "kind": "session", "percent": 10, "resets_at": "2026-11-26T06:00:00Z", "scope": null },
                { "kind": "weekly_all", "percent": 22, "resets_at": "2026-11-30T01:59:59+00:00", "scope": null },
                { "kind": "weekly_scoped", "percent": 39, "resets_at": "2026-11-30T01:59:59+00:00", "scope": { "model": { "id": "fable", "display_name": "Fable" } } }
            ]
        });

        let items = parse_claude_usage(&sample);
        assert_eq!(items.len(), 4);
        assert_eq!(items[0]["label"], "5-hour session");
        assert_eq!(items[0]["remainingPercent"], 90.0);
        assert_eq!(items[1]["label"], "Weekly limit");
        assert_eq!(items[1]["remainingPercent"], 78.0);
        assert_eq!(items[2]["label"], "Weekly · Fable");
        assert_eq!(items[2]["remainingPercent"], 61.0);
        assert_eq!(items[2]["modelIds"], json!(["fable"]));
        assert_eq!(items[3]["label"], "Weekly · Opus");
        assert_eq!(items[3]["remainingPercent"], 55.0);
    }

    #[test]
    fn parses_codex_usage_http_and_rpc() {
        let http_sample = json!({
            "plan_type": "pro",
            "rate_limit": {
                "primary_window": {
                    "used_percent": 12,
                    "limit_window_seconds": 604800,
                    "reset_at": 1796238718
                },
                "secondary_window": {
                    "used_percent": 5,
                    "limit_window_seconds": 18000,
                    "reset_at": 1795670000
                }
            }
        });
        let (plan, items) = parse_codex_usage(&http_sample);
        assert_eq!(plan.as_deref(), Some("pro"));
        assert_eq!(items.len(), 2);
        assert_eq!(items[0]["label"], "Weekly limit");
        assert_eq!(items[0]["remainingPercent"], 88.0);
        assert_eq!(items[0]["resetsAtMs"], 1796238718000_i64);
        assert_eq!(items[1]["label"], "5-hour limit");
        assert_eq!(items[1]["remainingPercent"], 95.0);

        let rpc_sample = json!({
            "rateLimitsByLimitId": {
                "codex": {
                    "limitId": "codex",
                    "limitName": null,
                    "normalModelSlug": null,
                    "planType": "pro",
                    "primary": {
                        "usedPercent": 2,
                        "windowDurationMins": 10080,
                        "resetsAt": 1796238718
                    }
                }
            }
        });
        let (rpc_plan, rpc_items) = parse_codex_rpc_usage(&rpc_sample);
        assert_eq!(rpc_plan.as_deref(), Some("pro"));
        assert_eq!(rpc_items.len(), 1);
        assert_eq!(rpc_items[0]["label"], "Weekly limit");
        assert_eq!(rpc_items[0]["remainingPercent"], 98.0);
    }
}
