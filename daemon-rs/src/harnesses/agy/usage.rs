//! Usage limits for the Antigravity harness.

use serde_json::{json, Value};
use std::collections::{BTreeMap, HashMap};
use std::path::Path;
use std::time::Duration;

use crate::models::discover_agy_models;
use crate::usage::{
    clamp_percent, curl_request, error_response, ok_response, parse_rfc3339_ms, read_json_file,
    strip_thinking_suffix,
};

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

pub(crate) async fn fetch_usage(home: &Path) -> Value {
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

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

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
}
