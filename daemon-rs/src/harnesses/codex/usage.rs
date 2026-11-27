//! Usage limits for the Codex harness.

use serde_json::{json, Value};
use std::path::Path;
use std::time::Duration;
use tokio::io::AsyncWriteExt;
use tokio::process::Command;

use crate::config::{enriched_path, resolve_binary};
use crate::usage::{
    clamp_percent, curl_request, epoch_secs_to_iso, error_response, format_window_label,
    ok_response, read_json_file,
};
use crate::util::now_ms;

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

async fn fetch_usage_via_rpc() -> Result<(Option<String>, Vec<Value>), String> {
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

pub(crate) async fn fetch_usage(home: &Path) -> Value {
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

    match fetch_usage_via_rpc().await {
        Ok((plan, items)) => ok_response("codex", plan, items),
        Err(err) => error_response("codex", &err),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

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
