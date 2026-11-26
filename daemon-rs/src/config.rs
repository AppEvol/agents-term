use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use rand::RngCore;
use regex::Regex;
use serde_json::{json, Map, Value};
use std::collections::HashSet;
use std::env;
use std::fs::{self, File, OpenOptions};
use std::io::Write;
use std::os::fd::AsRawFd;
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

use crate::attachments::{supported_mime_types, MAX_ATTACHMENTS_PER_MESSAGE, MAX_ATTACHMENT_BYTES};
use crate::models::get_cached_agent_models;

pub const SERVER_VERSION: &str = env!("CARGO_PKG_VERSION");
pub const BUILD_HASH: &str = env!("AGENTSTERM_BUILD_HASH");
pub const EMBEDDED_LIVE_DEBUGGING_MD: &str = include_str!("../../docs/live-debugging.md");

const TAILSCALE_CACHE_TTL: Duration = Duration::from_secs(30);
static ATOMIC_WRITE_SEQ: AtomicU64 = AtomicU64::new(0);

pub fn next_atomic_seq() -> u64 {
    ATOMIC_WRITE_SEQ.fetch_add(1, Ordering::Relaxed)
}

pub const FULL_ACCESS_COMPLETION_INSTRUCTION: &str =
    "When the user asks you to accomplish a task in this tab, once the task is complete \
clearly let them know it is finished and that they can close this tab.";

pub const WORK_DONE_SYSTEM_INSTRUCTION: &str =
    "When the user's requested task in this tab is completely finished and no further action is \
needed from you, output the exact token <this-work-is-done-172728> on its own line at the very \
end of your final response. Only output this token when the task is truly complete (do not output \
it if you are asking a clarifying question, waiting for input, blocked, or still have remaining \
work), and never mention or explain this token to the user.";

pub fn daemon_commit() -> String {
    if let Ok(v) = env::var("AGENTSTERM_DAEMON_COMMIT") {
        let trimmed = v.trim();
        if !trimmed.is_empty() {
            return trimmed.to_string();
        }
    }
    BUILD_HASH.to_string()
}

pub fn home_dir() -> PathBuf {
    if let Ok(h) = env::var("HOME") {
        if !h.is_empty() {
            return PathBuf::from(h);
        }
    }
    PathBuf::from("/")
}

pub fn agents_term_home() -> PathBuf {
    if let Ok(override_dir) = env::var("AGENTSTERM_HOME") {
        let trimmed = override_dir.trim();
        if !trimmed.is_empty() {
            return PathBuf::from(trimmed);
        }
    }
    home_dir().join(".agents-term")
}

pub fn config_dir() -> PathBuf {
    agents_term_home()
}

pub fn bin_dir() -> PathBuf {
    agents_term_home().join("bin")
}

pub fn config_path() -> PathBuf {
    agents_term_home().join("config.json")
}

pub fn daemon_lock_path() -> PathBuf {
    agents_term_home().join("daemon.lock")
}

pub fn daemon_pid_path() -> PathBuf {
    agents_term_home().join("daemon.pid")
}

pub fn daemon_log_path() -> PathBuf {
    agents_term_home().join("daemon.log")
}

pub fn debug_dumps_root() -> PathBuf {
    agents_term_home().join("debug-dumps")
}

pub fn tmp_dir() -> PathBuf {
    agents_term_home().join("tmp")
}

pub fn tabs_root() -> PathBuf {
    agents_term_home().join("agents_tabs")
}

pub fn workspace_path() -> PathBuf {
    tabs_root().join("workspace.json")
}

pub fn tabs_index_path() -> PathBuf {
    tabs_root().join("tabs.jsonl")
}

pub fn is_safe_tab_id(tab_id: &str) -> bool {
    !tab_id.is_empty()
        && tab_id != "."
        && tab_id != ".."
        && !tab_id.contains('/')
        && !tab_id.contains('\\')
        && !tab_id.contains('\0')
}

pub fn tab_dir(tab_id: &str) -> Option<PathBuf> {
    if is_safe_tab_id(tab_id) {
        Some(tabs_root().join(tab_id))
    } else {
        None
    }
}

pub fn tab_messages_path(tab_id: &str) -> Option<PathBuf> {
    tab_dir(tab_id).map(|d| d.join("messages.json"))
}

/// Folder holding the images the app attached to a tab's messages.
pub fn tab_attachments_dir(tab_id: &str) -> Option<PathBuf> {
    tab_dir(tab_id).map(|d| d.join("attachments"))
}

pub fn live_debugging_doc_path() -> PathBuf {
    agents_term_home().join("live-debugging.md")
}

pub fn ensure_dir_0700(dir: &Path) -> std::io::Result<()> {
    fs::create_dir_all(dir)?;
    let _ = fs::set_permissions(dir, fs::Permissions::from_mode(0o700));
    Ok(())
}

pub fn ensure_config_dir() -> std::io::Result<()> {
    ensure_dir_0700(&agents_term_home())
}

pub struct DaemonLock {
    _file: File,
}

pub fn acquire_daemon_lock() -> Result<DaemonLock, String> {
    ensure_config_dir().map_err(|e| format!("Failed to create {}: {e}", config_dir().display()))?;
    let path = daemon_lock_path();
    let file = OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .truncate(false)
        .mode(0o600)
        .open(&path)
        .map_err(|e| format!("Failed to open {}: {e}", path.display()))?;
    let _ = fs::set_permissions(&path, fs::Permissions::from_mode(0o600));
    let rc = unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) };
    if rc != 0 {
        let err = std::io::Error::last_os_error();
        return Err(format!(
            "Another agentsterm-daemon instance already holds {} ({err})",
            path.display()
        ));
    }
    let _ = file.set_len(0);
    let mut f_ref = &file;
    let _ = writeln!(f_ref, "{}", std::process::id());
    let _ = f_ref.sync_all();
    Ok(DaemonLock { _file: file })
}

pub fn write_atomic_file(path: &Path, content: &[u8], mode: u32) -> std::io::Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let tmp_name = format!(
        ".{}.tmp.{}.{}",
        path.file_name().and_then(|n| n.to_str()).unwrap_or("file"),
        std::process::id(),
        next_atomic_seq()
    );
    let tmp_path = path.with_file_name(tmp_name);
    let write_res = (|| -> std::io::Result<()> {
        let mut f = OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(mode)
            .open(&tmp_path)?;
        f.write_all(content)?;
        f.sync_all()?;
        Ok(())
    })();
    if let Err(err) = write_res {
        let _ = fs::remove_file(&tmp_path);
        return Err(err);
    }
    if let Err(err) = fs::rename(&tmp_path, path) {
        let _ = fs::remove_file(&tmp_path);
        return Err(err);
    }
    Ok(())
}

pub fn ensure_live_debugging_doc() {
    let _ = ensure_config_dir();
    let _ = write_atomic_file(
        &live_debugging_doc_path(),
        EMBEDDED_LIVE_DEBUGGING_MD.as_bytes(),
        0o644,
    );
}

pub fn default_system_prompt(permission_mode: Option<&str>) -> String {
    let root = agents_term_home();
    let doc_path = live_debugging_doc_path();
    let dumps_latest = debug_dumps_root().join("latest");
    let base = format!(
        "You are responding inside AgentsTerm, a mobile app terminal/chat view on a phone or tablet. \
Keep formatting mobile-friendly (concise paragraphs, short lists, avoid overly wide tables or ASCII art).\n\n\
Live app debugging: when troubleshooting AgentsTerm itself, you can inspect or drive the live phone app \
(state, logs, logcat, crash traces, screenshots, UI tree/control) via `agents_term_app_cli` \
(run `agents_term_app_cli --help` for usage, or `agentsterm-daemon app`) or check uploaded dumps in \
`{}/`. For commands, prerequisites, and rules, read `{}`.\n\n\
VM state rule: treat `{}/` as read-only — never create, edit, or delete files inside it directly; \
only `agentsterm-daemon` manages files in that directory.",
        dumps_latest.display(),
        doc_path.display(),
        root.display(),
    );
    if permission_mode == Some("full-access") {
        format!("{base}\n\n{FULL_ACCESS_COMPLETION_INSTRUCTION}")
    } else {
        base
    }
}

pub fn generate_token() -> String {
    let mut bytes = [0u8; 24];
    rand::thread_rng().fill_bytes(&mut bytes);
    URL_SAFE_NO_PAD.encode(bytes)
}

#[derive(Clone, Debug)]
pub struct DaemonConfig {
    pub token: String,
    pub host: Option<String>,
    pub port: Option<u16>,
}

impl DaemonConfig {
    pub fn to_json(&self) -> Value {
        let mut map = Map::new();
        map.insert("token".into(), Value::String(self.token.clone()));
        if let Some(ref h) = self.host {
            map.insert("host".into(), Value::String(h.clone()));
        }
        if let Some(p) = self.port {
            map.insert("port".into(), json!(p));
        }
        Value::Object(map)
    }
}

pub fn load_or_create_config() -> DaemonConfig {
    let _ = ensure_config_dir();
    let path = config_path();
    if let Ok(raw) = fs::read_to_string(&path) {
        if let Ok(Value::Object(obj)) = serde_json::from_str::<Value>(&raw) {
            if let Some(token) = obj.get("token").and_then(Value::as_str).map(str::trim) {
                if !token.is_empty() {
                    let host = obj.get("host").and_then(Value::as_str).map(str::to_string);
                    let port = obj
                        .get("port")
                        .and_then(Value::as_u64)
                        .and_then(|p| u16::try_from(p).ok());
                    return DaemonConfig {
                        token: token.to_string(),
                        host,
                        port,
                    };
                }
            }
        }
    }
    let cfg = DaemonConfig {
        token: generate_token(),
        host: None,
        port: None,
    };
    let _ = save_config(&cfg);
    cfg
}

pub fn save_config(cfg: &DaemonConfig) -> std::io::Result<()> {
    ensure_config_dir()?;
    let serialized = format!(
        "{}\n",
        serde_json::to_string_pretty(&cfg.to_json()).unwrap_or_default()
    );
    write_atomic_file(&config_path(), serialized.as_bytes(), 0o600)
}

pub fn rotate_token() -> DaemonConfig {
    let mut cfg = load_or_create_config();
    cfg.token = generate_token();
    let _ = save_config(&cfg);
    cfg
}

pub fn hostname() -> String {
    let mut buf = [0u8; 256];
    let rc = unsafe { libc::gethostname(buf.as_mut_ptr() as *mut libc::c_char, buf.len()) };
    if rc == 0 {
        let end = buf.iter().position(|&b| b == 0).unwrap_or(buf.len());
        if let Ok(s) = std::str::from_utf8(&buf[..end]) {
            if !s.is_empty() {
                return s.to_string();
            }
        }
    }
    env::var("HOSTNAME").unwrap_or_else(|_| "unknown".to_string())
}

fn ipv4_re() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| Regex::new(r"^\d+\.\d+\.\d+\.\d+$").unwrap())
}

fn ip_addr_re() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| Regex::new(r"^\d+:\s+(\S+)\s+inet\s+(\d+\.\d+\.\d+\.\d+)/").unwrap())
}

fn is_cgnat(ip: &str) -> bool {
    let parts: Vec<u8> = ip.split('.').filter_map(|p| p.parse::<u8>().ok()).collect();
    parts.len() == 4 && parts[0] == 100 && (64..=127).contains(&parts[1])
}

struct TailscaleCache {
    at: Option<Instant>,
    ip: Option<String>,
}

fn ts_cache() -> &'static Mutex<TailscaleCache> {
    static CACHE: OnceLock<Mutex<TailscaleCache>> = OnceLock::new();
    CACHE.get_or_init(|| Mutex::new(TailscaleCache { at: None, ip: None }))
}

pub fn detect_tailscale_ip(use_cache: bool) -> Option<String> {
    if use_cache {
        if let Ok(guard) = ts_cache().lock() {
            if let Some(at) = guard.at {
                if at.elapsed() < TAILSCALE_CACHE_TTL {
                    return guard.ip.clone();
                }
            }
        }
    }
    let ip = detect_tailscale_ip_uncached();
    if let Ok(mut guard) = ts_cache().lock() {
        guard.at = Some(Instant::now());
        guard.ip = ip.clone();
    }
    ip
}

fn detect_tailscale_ip_uncached() -> Option<String> {
    if let Ok(out) = Command::new("tailscale").args(["ip", "-4"]).output() {
        if out.status.success() {
            let stdout = String::from_utf8_lossy(&out.stdout);
            for line in stdout.lines() {
                let trimmed = line.trim();
                if ipv4_re().is_match(trimmed) {
                    return Some(trimmed.to_string());
                }
            }
        }
    }
    if let Ok(out) = Command::new("ip")
        .args(["-4", "-o", "addr", "show"])
        .output()
    {
        if out.status.success() {
            let stdout = String::from_utf8_lossy(&out.stdout);
            for line in stdout.lines() {
                if let Some(caps) = ip_addr_re().captures(line) {
                    let name = caps.get(1).map(|m| m.as_str()).unwrap_or("");
                    let addr = caps.get(2).map(|m| m.as_str()).unwrap_or("");
                    if addr.starts_with("127.") {
                        continue;
                    }
                    if name.contains("tailscale") || is_cgnat(addr) {
                        return Some(addr.to_string());
                    }
                }
            }
        }
    }
    None
}

pub fn enriched_path() -> String {
    let home = home_dir();
    let extra = [
        bin_dir().to_string_lossy().to_string(),
        home.join(".local/bin").to_string_lossy().to_string(),
        home.join(".npm-global/bin").to_string_lossy().to_string(),
        home.join(".cargo/bin").to_string_lossy().to_string(),
        "/usr/local/bin".to_string(),
        "/usr/bin".to_string(),
        "/bin".to_string(),
    ];
    let existing = env::var("PATH").unwrap_or_default();
    let mut seen = HashSet::new();
    let mut combined = Vec::new();
    for d in extra.into_iter().chain(
        existing
            .split(':')
            .filter(|s| !s.is_empty())
            .map(str::to_string),
    ) {
        if seen.insert(d.clone()) {
            combined.push(d);
        }
    }
    combined.join(":")
}

pub fn resolve_binary(binary: &str) -> Option<String> {
    for d in enriched_path().split(':') {
        let candidate = Path::new(d).join(binary);
        if let Ok(meta) = fs::metadata(&candidate) {
            if meta.is_file() && (meta.permissions().mode() & 0o111 != 0) {
                return Some(candidate.to_string_lossy().to_string());
            }
        }
    }
    None
}

pub struct AgentSpec {
    pub kind: &'static str,
    pub name: &'static str,
    pub description: &'static str,
    pub binary: &'static str,
}

pub const AGENT_SPECS: &[AgentSpec] = &[
    AgentSpec {
        kind: "agy",
        name: "Antigravity",
        description: "Antigravity CLI",
        binary: "agy",
    },
    AgentSpec {
        kind: "claude",
        name: "Claude Code",
        description: "Claude CLI",
        binary: "claude",
    },
    AgentSpec {
        kind: "codex",
        name: "Codex",
        description: "Codex CLI",
        binary: "codex",
    },
    AgentSpec {
        kind: "shell",
        name: "Shell",
        description: "Persistent bash",
        binary: "bash",
    },
];

pub fn detect_available_agents() -> Vec<Value> {
    AGENT_SPECS
        .iter()
        .map(|spec| {
            let binary_path = resolve_binary(spec.binary);
            let available = binary_path.is_some();
            let discovered = if available {
                get_cached_agent_models(spec.kind)
            } else {
                json!({ "defaultModel": Value::Null, "models": [] })
            };
            json!({
                "type": spec.kind,
                "name": spec.name,
                "description": spec.description,
                "binary": spec.binary,
                "available": available,
                "binaryPath": binary_path,
                "defaultModel": discovered.get("defaultModel").cloned().unwrap_or(Value::Null),
                "models": discovered.get("models").cloned().unwrap_or_else(|| json!([])),
            })
        })
        .collect()
}

pub fn build_server_info(bind_host: Option<&str>) -> Value {
    let tailscale_ip = detect_tailscale_ip(true).or_else(|| {
        bind_host
            .filter(|h| !h.is_empty() && *h != "0.0.0.0")
            .map(str::to_string)
    });
    let commit = daemon_commit();
    json!({
        "version": SERVER_VERSION,
        "commit": commit,
        "buildHash": BUILD_HASH,
        "runtime": "rust",
        "hostname": hostname(),
        "tailscaleIp": tailscale_ip,
        "defaultCwd": home_dir().to_string_lossy(),
        "defaultSystemPrompt": default_system_prompt(Some("default")),
        "defaultSystemPromptFullAccess": default_system_prompt(Some("full-access")),
        "agents": detect_available_agents(),
        "attachments": {
            "maxBytes": MAX_ATTACHMENT_BYTES,
            "maxPerMessage": MAX_ATTACHMENTS_PER_MESSAGE,
            "mimeTypes": supported_mime_types(),
        },
    })
}

pub fn pairing_uri(host: &str, port: u16, token: &str) -> String {
    let mut uri = format!(
        "herdr://connect?host={}&port={}",
        urlencoding::encode(host),
        port
    );
    if !token.is_empty() {
        uri.push_str("&token=");
        uri.push_str(&urlencoding::encode(token));
    }
    uri
}
