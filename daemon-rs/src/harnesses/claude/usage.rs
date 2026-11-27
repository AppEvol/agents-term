//! Usage limits for the Claude Code harness.

use serde_json::{json, Value};
use std::collections::HashSet;
use std::path::Path;

use crate::usage::{
    clamp_percent, curl_request, error_response, ok_response, parse_rfc3339_ms, read_json_file,
};

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

pub(crate) async fn fetch_usage(home: &Path) -> Value {
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

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

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
}
