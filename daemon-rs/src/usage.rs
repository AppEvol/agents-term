//! Shared helpers for agent usage checks; each harness fetches its own limits.

use chrono::{DateTime, TimeZone, Utc};
use serde_json::{json, Value};
use std::fs;
use std::path::{Path, PathBuf};
use std::time::Duration;
use tokio::io::AsyncWriteExt;
use tokio::process::Command;

use crate::config::{enriched_path, home_dir, resolve_binary};
use crate::harnesses;
use crate::util::now_ms;

pub(crate) const HTTP_TIMEOUT_SECS: u64 = 12;

pub(crate) fn round1(v: f64) -> f64 {
    (v * 10.0).round() / 10.0
}

pub(crate) fn clamp_percent(v: f64) -> f64 {
    round1(v.clamp(0.0, 100.0))
}

pub(crate) fn parse_rfc3339_ms(raw: &str) -> Option<i64> {
    DateTime::parse_from_rfc3339(raw.trim())
        .ok()
        .map(|dt| dt.timestamp_millis())
}

pub(crate) fn epoch_secs_to_iso(secs: i64) -> Option<String> {
    Utc.timestamp_opt(secs, 0)
        .single()
        .map(|dt| dt.format("%Y-%m-%dT%H:%M:%SZ").to_string())
}

pub(crate) fn format_window_label(seconds: i64) -> String {
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

pub(crate) fn strip_thinking_suffix(name: &str) -> String {
    let trimmed = name.trim();
    for suffix in [" (Low)", " (Medium)", " (High)", " (XHigh)", " (Max)"] {
        if let Some(base) = trimmed.strip_suffix(suffix) {
            return base.trim().to_string();
        }
    }
    trimmed.to_string()
}

pub(crate) fn escape_curl_config_value(val: &str) -> String {
    val.replace('\\', "\\\\").replace('"', "\\\"")
}

/// Runs `curl` passing all headers (including Bearer tokens) via stdin config (`-K -`) so
/// credentials are never exposed in process command-line arguments.
pub(crate) async fn curl_request(
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

pub(crate) fn read_json_file(path: &Path) -> Option<Value> {
    let raw = fs::read_to_string(path).ok()?;
    serde_json::from_str::<Value>(&raw).ok()
}

pub(crate) fn ok_response(agent: &str, plan: Option<String>, items: Vec<Value>) -> Value {
    json!({
        "agent": agent,
        "fetchedAt": now_ms(),
        "plan": plan,
        "items": items,
        "error": Value::Null,
    })
}

pub(crate) fn error_response(agent: &str, message: &str) -> Value {
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
    match harnesses::get(agent) {
        Some(harness) => harness.usage(&home).await,
        None => error_response(agent, "Usage check is not supported for this agent"),
    }
}
