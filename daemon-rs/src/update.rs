//! Self-update, backup, automatic rollback watchdog, and breakglass revert for `agentsterm-daemon`.

use regex::Regex;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::collections::HashSet;
use std::env;
use std::fs::{self, File, OpenOptions};
use std::io::Read;
use std::os::unix::fs::{MetadataExt, OpenOptionsExt, PermissionsExt};
use std::os::unix::process::CommandExt;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::{Arc, Mutex, OnceLock};
use std::thread;
use std::time::{Duration, Instant};

use crate::config::{
    bin_dir, daemon_commit, daemon_lock_path, daemon_log_path, daemon_pid_path, ensure_config_dir,
    installed_daemon_path, load_or_create_config, next_atomic_seq, prev_daemon_path,
    staged_daemon_path, tmp_dir, update_last_error_path, update_pending_path,
    update_prev_meta_path, write_atomic_file, BUILD_HASH, SERVER_VERSION,
};
use crate::install::{
    cmd_exists, ensure_app_cli_symlinks, env_is_one, kill_pid_gracefully, probe_info,
    spawn_detached_daemon, UNIT_NAME,
};
use crate::log::{log_error, log_info, log_warn};
use crate::runner::AgentRunner;
use crate::util::now_ms;

const DEFAULT_GITHUB_RELEASES_BASE: &str = "https://github.com/AppEvol/agents-term/releases";
const DEFAULT_ROLLBACK_WATCH_TIMEOUT_SECS: u64 = 15;
const VERIFY_BINARY_TIMEOUT: Duration = Duration::from_secs(10);
const RESTART_FLUSH_DELAY: Duration = Duration::from_millis(150);

/// Describes an in-place `execv()` restart to perform after graceful server shutdown.
#[derive(Clone, Debug)]
pub struct RestartPlan {
    pub binary_path: PathBuf,
    pub serve_args: Vec<String>,
}

impl RestartPlan {
    pub fn exec(self) -> Result<(), String> {
        let err = Command::new(&self.binary_path)
            .args(&self.serve_args)
            .env_remove("AGENTSTERM_DAEMON_COMMIT")
            .exec();
        Err(format!(
            "Failed to execv {}: {err}",
            self.binary_path.display()
        ))
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PrevMeta {
    build_hash: String,
    version: String,
    saved_at: i64,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PendingUpdate {
    target_hash: String,
    previous_hash: Option<String>,
    started_at: i64,
    attempts: u32,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct LastUpdateError {
    error: String,
    target_hash: Option<String>,
    recorded_at: i64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum WhenBusy {
    Defer,
    Reject,
    Force,
}

impl WhenBusy {
    pub fn parse(raw: Option<&str>) -> Result<Self, String> {
        match raw.map(str::trim).filter(|s| !s.is_empty()) {
            None | Some("defer") => Ok(Self::Defer),
            Some("reject") => Ok(Self::Reject),
            Some("force") => Ok(Self::Force),
            Some(other) => Err(format!(
                "Invalid whenBusy value '{other}': expected 'defer', 'reject', or 'force'"
            )),
        }
    }

    pub fn as_str(self) -> &'static str {
        match self {
            Self::Defer => "defer",
            Self::Reject => "reject",
            Self::Force => "force",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum UpdatePhase {
    Idle,
    Checking,
    Downloading,
    Verifying,
    PendingRestart,
    Restarting,
    UpToDate,
    Error,
}

impl UpdatePhase {
    fn as_str(self) -> &'static str {
        match self {
            Self::Idle => "idle",
            Self::Checking => "checking",
            Self::Downloading => "downloading",
            Self::Verifying => "verifying",
            Self::PendingRestart => "pending_restart",
            Self::Restarting => "restarting",
            Self::UpToDate => "up_to_date",
            Self::Error => "error",
        }
    }

    fn is_in_flight(self) -> bool {
        matches!(
            self,
            Self::Checking | Self::Downloading | Self::Verifying | Self::Restarting
        )
    }
}

#[derive(Clone, Debug)]
pub struct ApiError {
    pub http_status: u16,
    pub code: &'static str,
    pub message: String,
    pub extra: Option<Value>,
}

impl ApiError {
    fn new(http_status: u16, code: &'static str, message: impl Into<String>) -> Self {
        Self {
            http_status,
            code,
            message: message.into(),
            extra: None,
        }
    }

    fn with_extra(mut self, extra: Value) -> Self {
        self.extra = Some(extra);
        self
    }

    pub fn to_json(&self) -> Value {
        let mut obj = serde_json::Map::new();
        obj.insert("error".into(), json!(self.message));
        obj.insert("code".into(), json!(self.code));
        if let Some(Value::Object(extra_map)) = &self.extra {
            for (k, v) in extra_map {
                obj.insert(k.clone(), v.clone());
            }
        }
        Value::Object(obj)
    }
}

pub struct StagedBinary {
    pub staged_path: PathBuf,
    pub build_hash: String,
    #[allow(dead_code)]
    pub sha256: String,
}

struct TempDirGuard(PathBuf);

impl Drop for TempDirGuard {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

fn version_tag_re() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| Regex::new(r"^v[0-9]+\.[0-9]+\.[0-9]+-[0-9a-f]{7,40}(-preview)?$").unwrap())
}

pub fn validate_version_tag(raw: &str) -> Result<String, String> {
    let trimmed = raw.trim();
    if trimmed == "latest" {
        return Ok("latest".to_string());
    }
    if version_tag_re().is_match(trimmed) {
        return Ok(trimmed.to_string());
    }
    Err(format!(
        "Invalid release version tag '{trimmed}': expected 'latest' or 'v<major>.<minor>.<run>-<sha>[-preview]'"
    ))
}

pub fn validate_build_hash(raw: &str) -> Result<String, String> {
    let normalized = raw.trim().to_ascii_lowercase();
    if normalized.len() == 40 && normalized.bytes().all(|b| b.is_ascii_hexdigit()) {
        Ok(normalized)
    } else {
        Err(format!(
            "Invalid build hash '{}': expected a 40-character hex SHA-1 hash",
            raw.trim()
        ))
    }
}

pub fn parse_sha256_file(raw: &str) -> Result<String, String> {
    let first_line = raw
        .lines()
        .map(str::trim)
        .find(|l| !l.is_empty())
        .ok_or_else(|| "Checksum file is empty".to_string())?;
    let token = first_line
        .split_whitespace()
        .next()
        .ok_or_else(|| "Checksum file is empty".to_string())?
        .to_ascii_lowercase();
    if token.len() == 64 && token.bytes().all(|b| b.is_ascii_hexdigit()) {
        Ok(token)
    } else {
        Err(format!("Invalid SHA-256 checksum format: '{token}'"))
    }
}

pub fn compute_file_sha256(path: &Path) -> Result<String, String> {
    let mut file =
        File::open(path).map_err(|e| format!("Failed to open {}: {e}", path.display()))?;
    let mut hasher = Sha256::new();
    let mut buf = [0u8; 64 * 1024];
    loop {
        let n = file
            .read(&mut buf)
            .map_err(|e| format!("Failed to read {}: {e}", path.display()))?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    let digest = hasher.finalize();
    Ok(digest.iter().map(|b| format!("{b:02x}")).collect())
}

pub fn release_asset_name() -> Result<&'static str, String> {
    if env::consts::OS != "linux" {
        return Err(format!(
            "Unsupported OS for daemon self-update: {}",
            env::consts::OS
        ));
    }
    match env::consts::ARCH {
        "x86_64" => Ok("agentsterm-daemon-linux-x86_64"),
        "aarch64" => Ok("agentsterm-daemon-linux-aarch64"),
        other => Err(format!(
            "Unsupported architecture for daemon self-update: {other}"
        )),
    }
}

pub fn resolve_release_urls(version: &str) -> Result<(String, String), String> {
    let validated = validate_version_tag(version)?;
    let asset = release_asset_name()?;
    let env_override = env::var("AGENTSTERM_RELEASE_BASE_URL")
        .ok()
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty());
    resolve_release_urls_with_base(&validated, asset, env_override.as_deref())
}

fn resolve_release_urls_with_base(
    validated_version: &str,
    asset: &str,
    env_base_override: Option<&str>,
) -> Result<(String, String), String> {
    let base = match env_base_override.map(str::trim).filter(|s| !s.is_empty()) {
        Some(custom) => custom.trim_end_matches('/').to_string(),
        None if validated_version == "latest" => {
            format!("{DEFAULT_GITHUB_RELEASES_BASE}/latest/download")
        }
        None => format!("{DEFAULT_GITHUB_RELEASES_BASE}/download/{validated_version}"),
    };
    let bin_url = format!("{base}/{asset}");
    let sha_url = format!("{bin_url}.sha256");
    Ok((bin_url, sha_url))
}

pub fn is_self_update_supported() -> bool {
    !env_is_one("AGENTSTERM_DISABLE_SELF_UPDATE")
}

pub fn current_running_hash() -> String {
    daemon_commit()
}

fn info_matches_hash(info: &Value, target_hash: &str) -> bool {
    let commit = info.get("commit").and_then(Value::as_str).unwrap_or("");
    if !commit.is_empty() {
        return commit.eq_ignore_ascii_case(target_hash);
    }
    let build_hash = info.get("buildHash").and_then(Value::as_str).unwrap_or("");
    !build_hash.is_empty() && build_hash.eq_ignore_ascii_case(target_hash)
}

fn is_executable_file(path: &Path) -> bool {
    fs::metadata(path)
        .map(|m| m.is_file() && (m.permissions().mode() & 0o111 != 0))
        .unwrap_or(false)
}

fn read_prev_meta() -> Option<PrevMeta> {
    let raw = fs::read_to_string(update_prev_meta_path()).ok()?;
    serde_json::from_str::<PrevMeta>(&raw).ok()
}

fn read_pending_update() -> Option<PendingUpdate> {
    let raw = fs::read_to_string(update_pending_path()).ok()?;
    serde_json::from_str::<PendingUpdate>(&raw).ok()
}

fn write_pending_update(pending: &PendingUpdate) -> Result<(), String> {
    ensure_config_dir().map_err(|e| format!("Failed to create config dir: {e}"))?;
    let content = format!(
        "{}\n",
        serde_json::to_string_pretty(pending).unwrap_or_default()
    );
    write_atomic_file(&update_pending_path(), content.as_bytes(), 0o600)
        .map_err(|e| format!("Failed to write update-pending.json: {e}"))
}

fn clear_pending_update() {
    let _ = fs::remove_file(update_pending_path());
}

fn read_last_error() -> Option<String> {
    let raw = fs::read_to_string(update_last_error_path()).ok()?;
    let parsed = serde_json::from_str::<LastUpdateError>(&raw).ok()?;
    let trimmed = parsed.error.trim();
    if trimmed.is_empty() {
        None
    } else {
        Some(trimmed.to_string())
    }
}

fn record_last_error(message: &str, target_hash: Option<&str>) {
    let _ = ensure_config_dir();
    let payload = LastUpdateError {
        error: message.to_string(),
        target_hash: target_hash.map(str::to_string),
        recorded_at: now_ms(),
    };
    let serialized = format!(
        "{}\n",
        serde_json::to_string_pretty(&payload).unwrap_or_default()
    );
    let _ = write_atomic_file(&update_last_error_path(), serialized.as_bytes(), 0o600);
}

fn clear_last_error() {
    let _ = fs::remove_file(update_last_error_path());
}

fn rollback_status_snapshot() -> (bool, Option<String>) {
    if !is_executable_file(&prev_daemon_path()) {
        return (false, None);
    }
    let running = current_running_hash();
    match read_prev_meta() {
        Some(meta) => {
            if meta.build_hash.eq_ignore_ascii_case(&running) {
                (false, None)
            } else {
                (true, Some(meta.build_hash))
            }
        }
        None => (true, None),
    }
}

pub fn self_update_info() -> Value {
    let (rollback_available, previous_build_hash) = rollback_status_snapshot();
    json!({
        "supported": is_self_update_supported(),
        "rollbackAvailable": rollback_available,
        "previousBuildHash": previous_build_hash,
        "lastError": read_last_error(),
    })
}

fn download_to_file(url: &str, dest: &Path) -> Result<(), String> {
    let (tool, output) = if cmd_exists("curl") {
        let out = Command::new("curl")
            .args([
                "-fsSL",
                "--retry",
                "3",
                "--connect-timeout",
                "15",
                "--max-time",
                "180",
                "-o",
            ])
            .arg(dest)
            .arg(url)
            .output()
            .map_err(|e| format!("Failed to invoke curl: {e}"))?;
        ("curl", out)
    } else if cmd_exists("wget") {
        // BusyBox/Alpine compatible flags only (no GNU-only flags).
        let out = Command::new("wget")
            .args(["-q", "-O"])
            .arg(dest)
            .arg(url)
            .output()
            .map_err(|e| format!("Failed to invoke wget: {e}"))?;
        ("wget", out)
    } else {
        return Err("Neither curl nor wget is available on PATH".to_string());
    };

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr).trim().to_string();
        let detail = if stderr.is_empty() {
            format!("exit status {}", output.status)
        } else {
            stderr
        };
        return Err(format!("{tool} failed to download {url}: {detail}"));
    }
    Ok(())
}

fn spawn_retry_etxtbsy(cmd: &mut Command) -> std::io::Result<std::process::Child> {
    for attempt in 0..5 {
        match cmd.spawn() {
            Ok(child) => return Ok(child),
            Err(e) if e.raw_os_error() == Some(libc::ETXTBSY) && attempt < 4 => {
                thread::sleep(Duration::from_millis(20 * (attempt as u64 + 1)));
            }
            Err(e) => return Err(e),
        }
    }
    cmd.spawn()
}

fn run_command_with_timeout(
    mut cmd: Command,
    timeout: Duration,
) -> Result<(bool, String, String), String> {
    cmd.stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    let mut child =
        spawn_retry_etxtbsy(&mut cmd).map_err(|e| format!("Failed to spawn command: {e}"))?;
    let deadline = Instant::now() + timeout;
    loop {
        match child.try_wait() {
            Ok(Some(_)) => {
                let output = child
                    .wait_with_output()
                    .map_err(|e| format!("Failed to collect command output: {e}"))?;
                return Ok((
                    output.status.success(),
                    String::from_utf8_lossy(&output.stdout).to_string(),
                    String::from_utf8_lossy(&output.stderr).to_string(),
                ));
            }
            Ok(None) => {
                if Instant::now() >= deadline {
                    let _ = child.kill();
                    let _ = child.wait();
                    return Err(format!(
                        "Timed out after {}s while verifying binary",
                        timeout.as_secs()
                    ));
                }
                thread::sleep(Duration::from_millis(25));
            }
            Err(e) => {
                let _ = child.kill();
                let _ = child.wait();
                return Err(format!("Failed while waiting for binary verification: {e}"));
            }
        }
    }
}

fn query_binary_hash(path: &Path) -> Result<String, String> {
    let mut cmd = Command::new(path);
    cmd.args(["version", "--hash-only"])
        .env_remove("AGENTSTERM_DAEMON_COMMIT");
    let (ok, stdout, stderr) = run_command_with_timeout(cmd, VERIFY_BINARY_TIMEOUT)?;
    if !ok {
        let err_detail = stderr.trim();
        return Err(format!(
            "Binary verification failed ({} version --hash-only){}",
            path.display(),
            if err_detail.is_empty() {
                String::new()
            } else {
                format!(": {err_detail}")
            }
        ));
    }
    validate_build_hash(stdout.trim())
}

fn query_binary_version(path: &Path) -> String {
    let mut cmd = Command::new(path);
    cmd.arg("version").env_remove("AGENTSTERM_DAEMON_COMMIT");
    if let Ok((true, stdout, _)) = run_command_with_timeout(cmd, VERIFY_BINARY_TIMEOUT) {
        if let Some(first) = stdout.split_whitespace().next() {
            if !first.is_empty() {
                return first.to_string();
            }
        }
    }
    SERVER_VERSION.to_string()
}

fn verify_staged_binary(path: &Path, expected_hash: Option<&str>) -> Result<String, String> {
    fs::set_permissions(path, fs::Permissions::from_mode(0o755))
        .map_err(|e| format!("Failed to chmod 0755 {}: {e}", path.display()))?;
    let actual_hash = query_binary_hash(path)?;
    if let Some(expected) = expected_hash.map(str::trim).filter(|s| !s.is_empty()) {
        let expected_norm = validate_build_hash(expected)?;
        if actual_hash != expected_norm {
            return Err(format!(
                "Downloaded daemon build hash {actual_hash} does not match expected hash {expected_norm}"
            ));
        }
    }
    Ok(actual_hash)
}

fn atomic_copy_or_link(src: &Path, dest: &Path, mode: u32) -> Result<(), String> {
    if let (Ok(src_meta), Ok(dest_meta)) = (fs::metadata(src), fs::metadata(dest)) {
        if src_meta.dev() == dest_meta.dev() && src_meta.ino() == dest_meta.ino() {
            return Ok(());
        }
    }
    if let Some(parent) = dest.parent() {
        fs::create_dir_all(parent)
            .map_err(|e| format!("Failed to create {}: {e}", parent.display()))?;
    }
    let tmp_name = format!(
        ".{}.tmp.{}.{}",
        dest.file_name().and_then(|n| n.to_str()).unwrap_or("bin"),
        std::process::id(),
        next_atomic_seq()
    );
    let tmp_path = dest.with_file_name(tmp_name);
    let _ = fs::remove_file(&tmp_path);

    let linked = fs::hard_link(src, &tmp_path).is_ok();
    if !linked {
        if let Err(err) = fs::copy(src, &tmp_path) {
            let _ = fs::remove_file(&tmp_path);
            return Err(format!(
                "Failed to copy {} to {}: {err}",
                src.display(),
                tmp_path.display()
            ));
        }
        let _ = fs::set_permissions(&tmp_path, fs::Permissions::from_mode(mode));
    }

    if let Err(err) = fs::rename(&tmp_path, dest) {
        let _ = fs::remove_file(&tmp_path);
        return Err(format!(
            "Failed to atomically replace {}: {err}",
            dest.display()
        ));
    }
    // POSIX rename() is a no-op if tmp_path and dest refer to the same hardlinked inode.
    let _ = fs::remove_file(&tmp_path);
    Ok(())
}

fn atomic_move_file(src: &Path, dest: &Path, mode: u32) -> Result<(), String> {
    fs::set_permissions(src, fs::Permissions::from_mode(mode))
        .map_err(|e| format!("Failed to chmod {}: {e}", src.display()))?;
    if let Some(parent) = dest.parent() {
        fs::create_dir_all(parent)
            .map_err(|e| format!("Failed to create {}: {e}", parent.display()))?;
    }
    if fs::rename(src, dest).is_ok() {
        return Ok(());
    }
    atomic_copy_or_link(src, dest, mode)?;
    let _ = fs::remove_file(src);
    Ok(())
}

pub fn download_and_stage<F>(
    version: &str,
    expected_hash: Option<&str>,
    on_phase: F,
) -> Result<StagedBinary, String>
where
    F: Fn(&str),
{
    let validated_version = validate_version_tag(version)?;
    let normalized_expected = match expected_hash.map(str::trim).filter(|s| !s.is_empty()) {
        Some(h) => Some(validate_build_hash(h)?),
        None => None,
    };

    on_phase("checking");
    let (bin_url, sha_url) = resolve_release_urls(&validated_version)?;

    ensure_config_dir().map_err(|e| format!("Failed to create config dir: {e}"))?;
    let tmp_root = tmp_dir();
    fs::create_dir_all(&tmp_root)
        .map_err(|e| format!("Failed to create {}: {e}", tmp_root.display()))?;
    let _ = fs::set_permissions(&tmp_root, fs::Permissions::from_mode(0o700));
    fs::create_dir_all(bin_dir())
        .map_err(|e| format!("Failed to create {}: {e}", bin_dir().display()))?;

    let work_dir = tmp_root.join(format!(
        "self-update-{}-{}",
        std::process::id(),
        next_atomic_seq()
    ));
    fs::create_dir_all(&work_dir)
        .map_err(|e| format!("Failed to create {}: {e}", work_dir.display()))?;
    let _guard = TempDirGuard(work_dir.clone());

    let sha_file = work_dir.join("checksum.sha256");
    download_to_file(&sha_url, &sha_file)?;
    let sha_raw = fs::read_to_string(&sha_file)
        .map_err(|e| format!("Failed to read downloaded checksum: {e}"))?;
    let expected_sha = parse_sha256_file(&sha_raw)?;

    on_phase("downloading");
    let bin_file = work_dir.join("agentsterm-daemon");
    download_to_file(&bin_url, &bin_file)?;

    on_phase("verifying");
    let actual_sha = compute_file_sha256(&bin_file)?;
    if actual_sha != expected_sha {
        return Err(format!(
            "SHA-256 mismatch for {bin_url}: expected {expected_sha}, got {actual_sha}"
        ));
    }

    let build_hash = verify_staged_binary(&bin_file, normalized_expected.as_deref())?;

    let staged_path = staged_daemon_path();
    atomic_move_file(&bin_file, &staged_path, 0o755)?;
    Ok(StagedBinary {
        staged_path,
        build_hash,
        sha256: expected_sha,
    })
}

/// Anti-clobber backup: only overwrites `agentsterm-daemon.prev` when replacing a confirmed-healthy
/// binary (no `update-pending.json`) whose build hash differs from `new_build_hash`.
pub fn backup_current_binary_if_healthy(new_build_hash: &str) -> Result<Option<String>, String> {
    if update_pending_path().exists() {
        log_warn("Skipping .prev backup because an unconfirmed update is still pending");
        return Ok(read_prev_meta().map(|m| m.build_hash));
    }

    let installed = installed_daemon_path();
    let src = if installed.is_file() {
        installed
    } else if let Ok(exe) = env::current_exe() {
        if exe.is_file() {
            exe
        } else {
            return Ok(read_prev_meta().map(|m| m.build_hash));
        }
    } else {
        return Ok(read_prev_meta().map(|m| m.build_hash));
    };

    let current_hash = match query_binary_hash(&src) {
        Ok(h) => h,
        Err(err) => {
            log_warn(&format!(
                "Skipping .prev backup because current binary {} could not be verified: {err}",
                src.display()
            ));
            return Ok(read_prev_meta().map(|m| m.build_hash));
        }
    };

    if current_hash.eq_ignore_ascii_case(new_build_hash) {
        return Ok(read_prev_meta().map(|m| m.build_hash));
    }

    let current_version = query_binary_version(&src);
    atomic_copy_or_link(&src, &prev_daemon_path(), 0o755)?;
    let meta = PrevMeta {
        build_hash: current_hash.clone(),
        version: current_version,
        saved_at: now_ms(),
    };
    let serialized = format!(
        "{}\n",
        serde_json::to_string_pretty(&meta).unwrap_or_default()
    );
    write_atomic_file(&update_prev_meta_path(), serialized.as_bytes(), 0o600)
        .map_err(|e| format!("Failed to write update-prev.json: {e}"))?;
    Ok(Some(current_hash))
}

fn swap_staged_into_installed(staged_path: &Path) -> Result<PathBuf, String> {
    let dest = installed_daemon_path();
    atomic_move_file(staged_path, &dest, 0o755)?;
    ensure_app_cli_symlinks(&dest);
    Ok(dest)
}

pub fn restore_prev_into_installed() -> Result<PathBuf, String> {
    let prev = prev_daemon_path();
    if !prev.is_file() {
        return Err(format!(
            "No previous daemon backup found at {}",
            prev.display()
        ));
    }
    let dest = installed_daemon_path();
    atomic_copy_or_link(&prev, &dest, 0o755)?;
    ensure_app_cli_symlinks(&dest);
    Ok(dest)
}

fn is_running_under_systemd() -> bool {
    if env_is_one("AGENTSTERM_NO_SYSTEMD") {
        return false;
    }
    env::var("INVOCATION_ID")
        .map(|v| !v.trim().is_empty())
        .unwrap_or(false)
}

fn rollback_watch_timeout_secs() -> u64 {
    env::var("AGENTSTERM_ROLLBACK_WATCH_TIMEOUT_SECS")
        .ok()
        .and_then(|s| s.trim().parse::<u64>().ok())
        .filter(|&s| s >= 1)
        .unwrap_or(DEFAULT_ROLLBACK_WATCH_TIMEOUT_SECS)
}

fn open_watchdog_log_stdio() -> (Stdio, Stdio) {
    let _ = ensure_config_dir();
    if let Ok(file) = OpenOptions::new()
        .create(true)
        .append(true)
        .mode(0o600)
        .open(daemon_log_path())
    {
        if let Ok(err_file) = file.try_clone() {
            return (Stdio::from(file), Stdio::from(err_file));
        }
    }
    (Stdio::null(), Stdio::null())
}

fn spawn_rollback_watchdog(host: &str, port: u16, target_hash: &str, no_auth: bool) {
    let prev = prev_daemon_path();
    if !is_executable_file(&prev) {
        log_warn("Cannot spawn rollback-watch: no executable agentsterm-daemon.prev backup");
        return;
    }

    let timeout = rollback_watch_timeout_secs();
    let use_systemd = is_running_under_systemd();

    let mut watch_args: Vec<String> = vec![
        "rollback-watch".to_string(),
        "--host".to_string(),
        host.to_string(),
        "--port".to_string(),
        port.to_string(),
        "--target-hash".to_string(),
        target_hash.to_string(),
        "--timeout".to_string(),
        timeout.to_string(),
    ];
    if use_systemd {
        watch_args.push("--systemd".to_string());
    }
    if no_auth {
        watch_args.push("--no-auth".to_string());
    }

    if use_systemd && cmd_exists("systemd-run") {
        let (out_io, err_io) = open_watchdog_log_stdio();
        let mut cmd = Command::new("systemd-run");
        cmd.args(["--user", "--scope", "--quiet", "--collect", "--"])
            .arg(&prev)
            .args(&watch_args)
            .env_remove("AGENTSTERM_DAEMON_COMMIT")
            .stdin(Stdio::null())
            .stdout(out_io)
            .stderr(err_io);
        match cmd.spawn() {
            Ok(_) => {
                log_info("Spawned rollback-watch in isolated systemd --user scope");
                return;
            }
            Err(err) => {
                log_warn(&format!(
                    "systemd-run --user --scope failed ({err}); falling back to setsid for rollback-watch"
                ));
            }
        }
    }

    let (out_io, err_io) = open_watchdog_log_stdio();
    let mut cmd = Command::new(&prev);
    cmd.args(&watch_args)
        .env_remove("AGENTSTERM_DAEMON_COMMIT")
        .stdin(Stdio::null())
        .stdout(out_io)
        .stderr(err_io);
    unsafe {
        cmd.pre_exec(|| {
            if libc::setsid() == -1 {
                return Err(std::io::Error::last_os_error());
            }
            Ok(())
        });
    }
    if let Err(err) = cmd.spawn() {
        log_warn(&format!("Failed to spawn rollback-watch watchdog: {err}"));
    } else {
        log_info("Spawned detached rollback-watch watchdog");
    }
}

/// Startup crash-loop guard called in `serve` right after acquiring `DaemonLock`.
/// If `update-pending.json` shows `attempts >= 2`, restores `.prev` and returns a `RestartPlan`
/// to `execv()` `.prev`; otherwise increments `attempts`.
pub fn check_startup_crash_loop_guard(
    serve_args: &[String],
) -> Result<Option<RestartPlan>, String> {
    let pending_path = update_pending_path();
    if !pending_path.exists() {
        return Ok(None);
    }
    let Some(mut pending) = read_pending_update() else {
        clear_pending_update();
        return Ok(None);
    };

    if pending.attempts >= 2 {
        log_error(&format!(
            "Update crash-loop detected for target build {} after {} failed attempts; restoring previous daemon",
            &pending.target_hash[..pending.target_hash.len().min(12)],
            pending.attempts
        ));
        if is_executable_file(&prev_daemon_path()) {
            record_last_error(
                &format!(
                    "Updated daemon ({}) crash-looped on startup; automatically rolled back to previous build.",
                    &pending.target_hash[..pending.target_hash.len().min(12)]
                ),
                Some(&pending.target_hash),
            );
            let restored = restore_prev_into_installed()?;
            clear_pending_update();
            return Ok(Some(RestartPlan {
                binary_path: restored,
                serve_args: serve_args.to_vec(),
            }));
        }
        clear_pending_update();
        return Ok(None);
    }

    pending.attempts += 1;
    let _ = write_pending_update(&pending);
    Ok(None)
}

/// Health confirmation called once `StateStore::new()` and `TcpListener::bind()` succeed in `run_daemon`.
pub fn confirm_daemon_healthy() {
    let Some(pending) = read_pending_update() else {
        return;
    };
    let running = current_running_hash();
    if pending.target_hash.eq_ignore_ascii_case(&running)
        || pending.target_hash.eq_ignore_ascii_case(BUILD_HASH)
    {
        clear_pending_update();
        clear_last_error();
        log_info(&format!(
            "Confirmed healthy daemon update ({}); cleared update-pending.json",
            &pending.target_hash[..pending.target_hash.len().min(12)]
        ));
    }
}

fn read_pid_file(path: &Path) -> Option<i32> {
    fs::read_to_string(path)
        .ok()?
        .lines()
        .next()?
        .trim()
        .parse::<i32>()
        .ok()
        .filter(|&p| p > 1)
}

fn stop_stuck_daemon_for_this_home() {
    let my_pid = std::process::id() as i32;
    let mut pids = HashSet::new();
    if let Some(p) = read_pid_file(&daemon_lock_path()) {
        pids.insert(p);
    }
    if let Some(p) = read_pid_file(&daemon_pid_path()) {
        pids.insert(p);
    }
    for pid in pids {
        if pid != my_pid {
            log_warn(&format!(
                "rollback-watch stopping stuck daemon pid {pid} for this AGENTSTERM_HOME"
            ));
            kill_pid_gracefully(pid);
        }
    }
}

fn spawn_detached_daemon_with_auth(
    exe: &Path,
    host: &str,
    port: u16,
    no_auth: bool,
) -> Result<u32, String> {
    if !no_auth {
        return spawn_detached_daemon(exe, host, port);
    }
    ensure_config_dir().map_err(|e| format!("Failed to create config dir: {e}"))?;
    let (out_io, err_io) = open_watchdog_log_stdio();
    let mut cmd = Command::new(exe);
    cmd.args([
        "serve",
        "--host",
        host,
        "--port",
        &port.to_string(),
        "--no-auth",
    ])
    .env_remove("AGENTSTERM_DAEMON_COMMIT")
    .stdin(Stdio::null())
    .stdout(out_io)
    .stderr(err_io);
    unsafe {
        cmd.pre_exec(|| {
            if libc::setsid() == -1 {
                return Err(std::io::Error::last_os_error());
            }
            Ok(())
        });
    }
    let child = cmd
        .spawn()
        .map_err(|e| format!("Failed to spawn {}: {e}", exe.display()))?;
    let pid = child.id();
    let _ = write_atomic_file(&daemon_pid_path(), format!("{pid}\n").as_bytes(), 0o600);
    Ok(pid)
}

fn run_rollback_watch(
    host: String,
    port: u16,
    target_hash: String,
    timeout_secs: u64,
    use_systemd: bool,
    no_auth: bool,
) -> i32 {
    env::remove_var("AGENTSTERM_DAEMON_COMMIT");
    let cfg = load_or_create_config();
    let probe_host = if host == "0.0.0.0" {
        "127.0.0.1"
    } else {
        host.as_str()
    };

    // Give the parent daemon a brief window to finish graceful shutdown and execv().
    thread::sleep(Duration::from_millis(250));

    let deadline = Instant::now() + Duration::from_secs(timeout_secs.max(1));
    let mut last_err = "no response yet".to_string();

    while Instant::now() < deadline {
        match probe_info(probe_host, port, &cfg.token) {
            Ok(info) => {
                if info_matches_hash(&info, &target_hash) {
                    clear_pending_update();
                    log_info(&format!(
                        "rollback-watch confirmed target build {} is healthy on {probe_host}:{port}",
                        &target_hash[..target_hash.len().min(12)]
                    ));
                    return 0;
                }
                let got = info
                    .get("commit")
                    .or_else(|| info.get("buildHash"))
                    .and_then(Value::as_str)
                    .unwrap_or("unknown");
                if !update_pending_path().exists() {
                    // Already rolled back (e.g., by crash-loop guard or manual rollback) and healthy.
                    log_info(&format!(
                        "rollback-watch observed update-pending.json cleared and daemon healthy on {got}; exiting"
                    ));
                    return 0;
                }
                last_err = format!(
                    "daemon reported build {}, expected {}",
                    &got[..got.len().min(12)],
                    &target_hash[..target_hash.len().min(12)]
                );
            }
            Err(err) => {
                last_err = err;
            }
        }
        thread::sleep(Duration::from_millis(300));
    }

    let short_target = &target_hash[..target_hash.len().min(12)];
    let reason = format!(
        "Updated daemon ({short_target}) failed to become healthy within {timeout_secs}s ({last_err}); automatically rolled back to previous build."
    );
    log_error(&format!("rollback-watch: {reason}"));
    record_last_error(&reason, Some(&target_hash));
    clear_pending_update();

    let restored = match restore_prev_into_installed() {
        Ok(path) => path,
        Err(err) => {
            log_error(&format!("rollback-watch failed to restore .prev: {err}"));
            return 1;
        }
    };

    stop_stuck_daemon_for_this_home();

    if use_systemd && !env_is_one("AGENTSTERM_NO_SYSTEMD") && cmd_exists("systemctl") {
        let status = Command::new("systemctl")
            .args(["--user", "restart", UNIT_NAME])
            .status();
        match status {
            Ok(s) if s.success() => {
                log_info("rollback-watch restarted restored daemon via systemctl --user");
            }
            other => {
                log_warn(&format!(
                    "rollback-watch systemctl restart failed ({other:?}); falling back to detached spawn"
                ));
                if let Err(err) = spawn_detached_daemon_with_auth(&restored, &host, port, no_auth) {
                    log_error(&format!(
                        "rollback-watch failed to spawn restored daemon: {err}"
                    ));
                    return 1;
                }
            }
        }
    } else if let Err(err) = spawn_detached_daemon_with_auth(&restored, &host, port, no_auth) {
        log_error(&format!(
            "rollback-watch failed to spawn restored daemon: {err}"
        ));
        return 1;
    }

    let recovery_deadline = Instant::now() + Duration::from_secs(10);
    while Instant::now() < recovery_deadline {
        if probe_info(probe_host, port, &cfg.token).is_ok() {
            log_info("rollback-watch confirmed restored previous daemon is healthy");
            return 0;
        }
        thread::sleep(Duration::from_millis(250));
    }
    log_error("rollback-watch restored previous binary but daemon did not become healthy in 10s");
    1
}

/// CLI entrypoint for `agentsterm-daemon update <version> [--expected-hash HASH] [--stage-only] [--force] [--host HOST] [--port PORT]`.
pub fn run_update_cli(args: &[String], default_port: u16) -> i32 {
    if !is_self_update_supported() {
        eprintln!("ERROR: Self-update is disabled via AGENTSTERM_DISABLE_SELF_UPDATE=1");
        return 1;
    }

    let mut version: Option<String> = None;
    let mut expected_hash: Option<String> = None;
    let mut stage_only = false;
    let mut force = false;
    let mut host: Option<String> = None;
    let mut port_override: Option<u16> = None;

    let mut i = 0;
    while i < args.len() {
        let arg = &args[i];
        if arg == "-h" || arg == "--help" {
            println!(
                "usage: agentsterm-daemon update [<version>] [--expected-hash HASH] [--stage-only] [--force] [--host HOST] [--port PORT]"
            );
            return 0;
        } else if arg == "--expected-hash" && i + 1 < args.len() {
            i += 1;
            expected_hash = Some(args[i].clone());
        } else if let Some(v) = arg.strip_prefix("--expected-hash=") {
            expected_hash = Some(v.to_string());
        } else if arg == "--stage-only" {
            stage_only = true;
        } else if arg == "--force" {
            force = true;
        } else if arg == "--host" && i + 1 < args.len() {
            i += 1;
            host = Some(args[i].clone());
        } else if let Some(v) = arg.strip_prefix("--host=") {
            host = Some(v.to_string());
        } else if arg == "--port" && i + 1 < args.len() {
            i += 1;
            match args[i].parse::<u16>() {
                Ok(p) if p >= 1 => port_override = Some(p),
                _ => {
                    eprintln!("ERROR: Invalid --port value '{}'", args[i]);
                    return 2;
                }
            }
        } else if let Some(v) = arg.strip_prefix("--port=") {
            match v.parse::<u16>() {
                Ok(p) if p >= 1 => port_override = Some(p),
                _ => {
                    eprintln!("ERROR: Invalid --port value '{v}'");
                    return 2;
                }
            }
        } else if !arg.starts_with('-') && version.is_none() {
            version = Some(arg.clone());
        } else {
            eprintln!("ERROR: Unknown argument for update: {arg}");
            return 2;
        }
        i += 1;
    }

    let target_version = version.unwrap_or_else(|| "latest".to_string());
    if let Err(err) = validate_version_tag(&target_version) {
        eprintln!("ERROR: {err}");
        return 2;
    }
    if let Some(ref h) = expected_hash {
        if let Err(err) = validate_build_hash(h) {
            eprintln!("ERROR: {err}");
            return 2;
        }
    }

    let staged = match download_and_stage(&target_version, expected_hash.as_deref(), |phase| {
        println!(">>> Self-update phase: {phase}");
    }) {
        Ok(s) => s,
        Err(err) => {
            eprintln!("ERROR: {err}");
            return 3;
        }
    };

    if stage_only {
        println!(
            ">>> Staged verified daemon build {} at {}",
            staged.build_hash,
            staged.staged_path.display()
        );
        return 0;
    }

    let installed = installed_daemon_path();
    if !force && installed.is_file() {
        if let Ok(installed_hash) = query_binary_hash(&installed) {
            if installed_hash.eq_ignore_ascii_case(&staged.build_hash) {
                let _ = fs::remove_file(&staged.staged_path);
                println!(
                    ">>> Installed daemon already matches target build {} (use --force to reinstall)",
                    staged.build_hash
                );
                return 0;
            }
        }
    }

    if let Err(err) = backup_current_binary_if_healthy(&staged.build_hash) {
        eprintln!("ERROR: Failed to back up current daemon binary: {err}");
        let _ = fs::remove_file(&staged.staged_path);
        return 1;
    }

    let new_bin = match swap_staged_into_installed(&staged.staged_path) {
        Ok(p) => p,
        Err(err) => {
            eprintln!("ERROR: Failed to replace installed daemon binary: {err}");
            return 1;
        }
    };

    let cfg = load_or_create_config();
    let effective_host = host.or(cfg.host);
    let effective_port = port_override.or(cfg.port).unwrap_or(default_port);

    let install_ok = run_install_child(&new_bin, effective_host.as_deref(), effective_port, true);
    if install_ok {
        clear_pending_update();
        clear_last_error();
        return 0;
    }

    let err_msg = format!(
        "CLI update to build {} failed during install; attempting automatic rollback",
        &staged.build_hash[..staged.build_hash.len().min(12)]
    );
    eprintln!("ERROR: {err_msg}");
    record_last_error(&err_msg, Some(&staged.build_hash));

    if is_executable_file(&prev_daemon_path()) {
        match restore_prev_into_installed() {
            Ok(restored) => {
                clear_pending_update();
                let _ =
                    run_install_child(&restored, effective_host.as_deref(), effective_port, true);
            }
            Err(err) => {
                eprintln!("ERROR: Auto-rollback failed to restore previous binary: {err}");
            }
        }
    }
    4
}

/// CLI entrypoint for `agentsterm-daemon rollback [--host HOST] [--port PORT]`
/// (and `~/.agents-term/bin/agentsterm-daemon.prev rollback`).
pub fn run_rollback_cli(args: &[String], default_port: u16) -> i32 {
    let mut host: Option<String> = None;
    let mut port_override: Option<u16> = None;

    let mut i = 0;
    while i < args.len() {
        let arg = &args[i];
        if arg == "-h" || arg == "--help" {
            println!("usage: agentsterm-daemon rollback [--host HOST] [--port PORT]");
            return 0;
        } else if arg == "--host" && i + 1 < args.len() {
            i += 1;
            host = Some(args[i].clone());
        } else if let Some(v) = arg.strip_prefix("--host=") {
            host = Some(v.to_string());
        } else if arg == "--port" && i + 1 < args.len() {
            i += 1;
            match args[i].parse::<u16>() {
                Ok(p) if p >= 1 => port_override = Some(p),
                _ => {
                    eprintln!("ERROR: Invalid --port value '{}'", args[i]);
                    return 2;
                }
            }
        } else if let Some(v) = arg.strip_prefix("--port=") {
            match v.parse::<u16>() {
                Ok(p) if p >= 1 => port_override = Some(p),
                _ => {
                    eprintln!("ERROR: Invalid --port value '{v}'");
                    return 2;
                }
            }
        } else {
            eprintln!("ERROR: Unknown argument for rollback: {arg}");
            return 2;
        }
        i += 1;
    }

    let restored = match restore_prev_into_installed() {
        Ok(p) => p,
        Err(err) => {
            eprintln!("ERROR: {err}");
            return 1;
        }
    };
    clear_pending_update();
    clear_last_error();
    let _ = fs::remove_file(staged_daemon_path());

    let cfg = load_or_create_config();
    let effective_host = host.or(cfg.host);
    let effective_port = port_override.or(cfg.port).unwrap_or(default_port);

    if run_install_child(&restored, effective_host.as_deref(), effective_port, true) {
        0
    } else {
        4
    }
}

/// CLI entrypoint for `agentsterm-daemon rollback-watch ...`.
pub fn run_rollback_watch_cli(args: &[String], default_port: u16) -> i32 {
    let mut host: Option<String> = None;
    let mut port: u16 = default_port;
    let mut target_hash: Option<String> = None;
    let mut timeout_secs: u64 = rollback_watch_timeout_secs();
    let mut use_systemd = false;
    let mut no_auth = false;

    let mut i = 0;
    while i < args.len() {
        let arg = &args[i];
        if arg == "--host" && i + 1 < args.len() {
            i += 1;
            host = Some(args[i].clone());
        } else if let Some(v) = arg.strip_prefix("--host=") {
            host = Some(v.to_string());
        } else if arg == "--port" && i + 1 < args.len() {
            i += 1;
            port = args[i].parse::<u16>().unwrap_or(default_port);
        } else if let Some(v) = arg.strip_prefix("--port=") {
            port = v.parse::<u16>().unwrap_or(default_port);
        } else if arg == "--target-hash" && i + 1 < args.len() {
            i += 1;
            target_hash = Some(args[i].clone());
        } else if let Some(v) = arg.strip_prefix("--target-hash=") {
            target_hash = Some(v.to_string());
        } else if arg == "--timeout" && i + 1 < args.len() {
            i += 1;
            if let Ok(t) = args[i].parse::<u64>() {
                if t >= 1 {
                    timeout_secs = t;
                }
            }
        } else if let Some(v) = arg.strip_prefix("--timeout=") {
            if let Ok(t) = v.parse::<u64>() {
                if t >= 1 {
                    timeout_secs = t;
                }
            }
        } else if arg == "--systemd" {
            use_systemd = true;
        } else if arg == "--no-auth" {
            no_auth = true;
        }
        i += 1;
    }

    let Some(target) = target_hash else {
        eprintln!("ERROR: rollback-watch requires --target-hash");
        return 2;
    };
    let resolved_host = host
        .or_else(|| load_or_create_config().host)
        .unwrap_or_else(|| "127.0.0.1".to_string());
    run_rollback_watch(
        resolved_host,
        port,
        target,
        timeout_secs,
        use_systemd,
        no_auth,
    )
}

fn run_install_child(bin: &Path, host: Option<&str>, port: u16, force_restart: bool) -> bool {
    let mut cmd = Command::new(bin);
    cmd.arg("install");
    if let Some(h) = host.filter(|s| !s.trim().is_empty()) {
        cmd.args(["--host", h]);
    }
    cmd.args(["--port", &port.to_string()]);
    if force_restart {
        cmd.arg("--force-restart");
    }
    cmd.env_remove("AGENTSTERM_DAEMON_COMMIT");
    match cmd.status() {
        Ok(status) => status.success(),
        Err(err) => {
            eprintln!("ERROR: Failed to execute {} install: {err}", bin.display());
            false
        }
    }
}

struct UpdateStateInner {
    generation: u64,
    phase: UpdatePhase,
    target_version: Option<String>,
    target_build_hash: Option<String>,
    when_busy: Option<WhenBusy>,
    staged_path: Option<PathBuf>,
    pending_is_rollback: bool,
    detail: Option<String>,
    error: Option<String>,
    updated_at: i64,
}

impl UpdateStateInner {
    fn new() -> Self {
        Self {
            generation: 0,
            phase: UpdatePhase::Idle,
            target_version: None,
            target_build_hash: None,
            when_busy: None,
            staged_path: None,
            pending_is_rollback: false,
            detail: None,
            error: None,
            updated_at: now_ms(),
        }
    }
}

pub struct UpdateManager {
    host: String,
    port: u16,
    no_auth: bool,
    runner: AgentRunner,
    broadcast: Arc<dyn Fn(Value) + Send + Sync>,
    restart_tx: Mutex<Option<tokio::sync::oneshot::Sender<RestartPlan>>>,
    inner: Mutex<UpdateStateInner>,
}

impl UpdateManager {
    pub fn new(
        host: String,
        port: u16,
        no_auth: bool,
        runner: AgentRunner,
        broadcast: Arc<dyn Fn(Value) + Send + Sync>,
        restart_tx: tokio::sync::oneshot::Sender<RestartPlan>,
    ) -> Arc<Self> {
        Arc::new(Self {
            host,
            port,
            no_auth,
            runner,
            broadcast,
            restart_tx: Mutex::new(Some(restart_tx)),
            inner: Mutex::new(UpdateStateInner::new()),
        })
    }

    fn serve_args(&self) -> Vec<String> {
        let mut args = vec![
            "serve".to_string(),
            "--host".to_string(),
            self.host.clone(),
            "--port".to_string(),
            self.port.to_string(),
        ];
        if self.no_auth {
            args.push("--no-auth".to_string());
        }
        args
    }

    fn status_from_locked(&self, inner: &UpdateStateInner) -> Value {
        let active_tabs = self.runner.active_tab_ids();
        let (rollback_available, previous_build_hash) = rollback_status_snapshot();
        json!({
            "state": inner.phase.as_str(),
            "targetVersion": inner.target_version,
            "targetBuildHash": inner.target_build_hash,
            "currentBuildHash": current_running_hash(),
            "activeTabIds": active_tabs,
            "activeTurnsCount": active_tabs.len(),
            "whenBusy": inner.when_busy.map(WhenBusy::as_str),
            "detail": inner.detail,
            "error": inner.error,
            "rollbackAvailable": rollback_available,
            "previousBuildHash": previous_build_hash,
            "lastError": read_last_error(),
            "updatedAt": inner.updated_at,
        })
    }

    pub fn status(&self) -> Value {
        let inner = self.inner.lock().unwrap();
        self.status_from_locked(&inner)
    }

    fn broadcast_status(&self, status: Value) {
        (self.broadcast)(json!({
            "type": "daemon_update_status",
            "update": status,
            "ts": now_ms(),
        }));
    }

    fn trigger_restart_async(self: &Arc<Self>, staged_path: PathBuf, target_hash: String) {
        let mgr = Arc::clone(self);
        thread::spawn(move || {
            // Allow HTTP 202 and WebSocket `daemon_update_status` ("restarting") frames to flush.
            thread::sleep(RESTART_FLUSH_DELAY);

            let prev_hash = match backup_current_binary_if_healthy(&target_hash) {
                Ok(h) => h,
                Err(err) => {
                    mgr.fail_restart(format!("Failed to back up current daemon binary: {err}"));
                    return;
                }
            };

            let installed = match swap_staged_into_installed(&staged_path) {
                Ok(p) => p,
                Err(err) => {
                    mgr.fail_restart(format!("Failed to install staged daemon binary: {err}"));
                    return;
                }
            };

            let pending = PendingUpdate {
                target_hash: target_hash.clone(),
                previous_hash: prev_hash,
                started_at: now_ms(),
                attempts: 0,
            };
            if let Err(err) = write_pending_update(&pending) {
                log_warn(&format!("Failed to write update-pending.json: {err}"));
            }

            spawn_rollback_watchdog(&mgr.host, mgr.port, &target_hash, mgr.no_auth);

            let plan = RestartPlan {
                binary_path: installed,
                serve_args: mgr.serve_args(),
            };
            if let Some(tx) = mgr.restart_tx.lock().unwrap().take() {
                let _ = tx.send(plan);
            }
        });
    }

    fn trigger_rollback_restart_async(self: &Arc<Self>) {
        let mgr = Arc::clone(self);
        thread::spawn(move || {
            thread::sleep(RESTART_FLUSH_DELAY);
            clear_pending_update();
            clear_last_error();
            let restored = match restore_prev_into_installed() {
                Ok(p) => p,
                Err(err) => {
                    mgr.fail_restart(format!("Rollback failed to restore previous binary: {err}"));
                    return;
                }
            };
            let plan = RestartPlan {
                binary_path: restored,
                serve_args: mgr.serve_args(),
            };
            if let Some(tx) = mgr.restart_tx.lock().unwrap().take() {
                let _ = tx.send(plan);
            }
        });
    }

    fn fail_restart(&self, message: String) {
        log_error(&format!("Self-update restart aborted: {message}"));
        let status = {
            let mut inner = self.inner.lock().unwrap();
            inner.phase = UpdatePhase::Error;
            inner.detail = None;
            inner.error = Some(message);
            inner.updated_at = now_ms();
            self.status_from_locked(&inner)
        };
        self.broadcast_status(status);
    }

    /// Triggered by `AgentRunner` whenever active turns drain to 0.
    pub fn on_runner_idle(self: &Arc<Self>) {
        enum IdleAction {
            None,
            BroadcastError(Value),
            RestartUpdate(PathBuf, String, Value),
            RestartRollback(Value),
        }

        let action = {
            let mut inner = self.inner.lock().unwrap();
            if inner.phase != UpdatePhase::PendingRestart
                || !self.runner.active_tab_ids().is_empty()
            {
                IdleAction::None
            } else if inner.pending_is_rollback {
                if !is_executable_file(&prev_daemon_path()) {
                    inner.phase = UpdatePhase::Error;
                    inner.pending_is_rollback = false;
                    inner.detail = None;
                    inner.error = Some("Previous daemon backup is missing".to_string());
                    inner.updated_at = now_ms();
                    IdleAction::BroadcastError(self.status_from_locked(&inner))
                } else {
                    inner.phase = UpdatePhase::Restarting;
                    inner.pending_is_rollback = false;
                    inner.detail = Some("Reverting to previous daemon...".to_string());
                    inner.error = None;
                    inner.updated_at = now_ms();
                    IdleAction::RestartRollback(self.status_from_locked(&inner))
                }
            } else if let (Some(staged_path), Some(target_hash)) =
                (inner.staged_path.clone(), inner.target_build_hash.clone())
            {
                if !staged_path.is_file() {
                    inner.phase = UpdatePhase::Error;
                    inner.detail = None;
                    inner.error = Some("Staged daemon binary is missing".to_string());
                    inner.updated_at = now_ms();
                    IdleAction::BroadcastError(self.status_from_locked(&inner))
                } else {
                    inner.phase = UpdatePhase::Restarting;
                    inner.detail = Some("Restarting daemon to apply update...".to_string());
                    inner.error = None;
                    inner.updated_at = now_ms();
                    IdleAction::RestartUpdate(
                        staged_path,
                        target_hash,
                        self.status_from_locked(&inner),
                    )
                }
            } else {
                IdleAction::None
            }
        };

        match action {
            IdleAction::None => {}
            IdleAction::BroadcastError(st) => self.broadcast_status(st),
            IdleAction::RestartUpdate(staged_path, target_hash, st) => {
                log_info("All active turns completed; applying deferred daemon update");
                self.broadcast_status(st);
                self.trigger_restart_async(staged_path, target_hash);
            }
            IdleAction::RestartRollback(st) => {
                log_info("All active turns completed; applying deferred daemon rollback");
                self.broadcast_status(st);
                self.trigger_rollback_restart_async();
            }
        }
    }

    /// Handles `POST /api/daemon/update`.
    /// Returns `(http_status, status_json)` on success.
    pub fn start_update(
        self: &Arc<Self>,
        raw_version: Option<&str>,
        raw_expected_hash: Option<&str>,
        raw_when_busy: Option<&str>,
        force: bool,
    ) -> Result<(u16, Value), ApiError> {
        if !is_self_update_supported() {
            return Err(ApiError::new(
                409,
                "self_update_disabled",
                "Self-update is disabled on this daemon",
            ));
        }

        let version_input = raw_version
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .unwrap_or("latest");
        let version = validate_version_tag(version_input)
            .map_err(|e| ApiError::new(400, "invalid_version", e))?;

        let expected_hash = match raw_expected_hash.map(str::trim).filter(|s| !s.is_empty()) {
            Some(h) => Some(
                validate_build_hash(h)
                    .map_err(|e| ApiError::new(400, "invalid_expected_hash", e))?,
            ),
            None => None,
        };

        let when_busy = WhenBusy::parse(raw_when_busy)
            .map_err(|e| ApiError::new(400, "invalid_when_busy", e))?;

        // Check state and handle force-restart of an already-staged pending update.
        let (immediate_status, apply_existing_staged) = {
            let mut inner = self.inner.lock().unwrap();
            if inner.phase.is_in_flight() {
                let st = self.status_from_locked(&inner);
                return Err(ApiError::new(
                    409,
                    "update_in_progress",
                    "A daemon update is already in progress",
                )
                .with_extra(json!({ "status": st })));
            }

            // If we already have a verified staged binary in `pending_restart` and the caller
            // explicitly requests `whenBusy: "force"`, apply it immediately without re-downloading.
            if inner.phase == UpdatePhase::PendingRestart
                && !inner.pending_is_rollback
                && when_busy == WhenBusy::Force
            {
                let version_matches = raw_version
                    .map(str::trim)
                    .filter(|s| !s.is_empty())
                    .map(|v| inner.target_version.as_deref() == Some(v))
                    .unwrap_or(true);
                let hash_matches = match (&expected_hash, &inner.target_build_hash) {
                    (Some(req_h), Some(cur_h)) => req_h.eq_ignore_ascii_case(cur_h),
                    (None, _) => true,
                    _ => false,
                };
                if version_matches && hash_matches {
                    if let (Some(staged_path), Some(target_hash)) =
                        (inner.staged_path.clone(), inner.target_build_hash.clone())
                    {
                        if staged_path.is_file() {
                            clear_last_error();
                            inner.phase = UpdatePhase::Restarting;
                            inner.when_busy = Some(WhenBusy::Force);
                            inner.detail =
                                Some("Restarting daemon to apply staged update...".to_string());
                            inner.error = None;
                            inner.updated_at = now_ms();
                            let st = self.status_from_locked(&inner);
                            (st, Some((staged_path, target_hash)))
                        } else {
                            (Value::Null, None)
                        }
                    } else {
                        (Value::Null, None)
                    }
                } else {
                    (Value::Null, None)
                }
            } else {
                (Value::Null, None)
            }
        };

        if let Some((staged_path, target_hash)) = apply_existing_staged {
            self.broadcast_status(immediate_status.clone());
            self.trigger_restart_async(staged_path, target_hash);
            return Ok((202, immediate_status));
        }

        // Busy guard for `whenBusy == "reject"`.
        let active_tabs = self.runner.active_tab_ids();
        if when_busy == WhenBusy::Reject && !active_tabs.is_empty() {
            let st = self.status();
            return Err(ApiError::new(
                409,
                "busy",
                format!(
                    "Cannot update while {} agent turn(s) are active",
                    active_tabs.len()
                ),
            )
            .with_extra(json!({
                "activeTabIds": active_tabs,
                "activeTurnsCount": active_tabs.len(),
                "status": st,
            })));
        }

        // Fast-path up-to-date check when `expectedHash` already matches running daemon.
        if !force {
            if let Some(ref exp) = expected_hash {
                if exp.eq_ignore_ascii_case(&current_running_hash()) {
                    let st = {
                        let mut inner = self.inner.lock().unwrap();
                        if let Some(old_staged) = inner.staged_path.take() {
                            let _ = fs::remove_file(old_staged);
                        }
                        clear_last_error();
                        inner.generation += 1;
                        inner.phase = UpdatePhase::UpToDate;
                        inner.target_version = Some(version);
                        inner.target_build_hash = Some(exp.clone());
                        inner.when_busy = Some(when_busy);
                        inner.pending_is_rollback = false;
                        inner.detail = Some("Daemon is already up to date".to_string());
                        inner.error = None;
                        inner.updated_at = now_ms();
                        self.status_from_locked(&inner)
                    };
                    self.broadcast_status(st.clone());
                    return Ok((200, st));
                }
            }
        }

        // Start background download/verify task.
        let (job_gen, checking_status) = {
            let mut inner = self.inner.lock().unwrap();
            if inner.phase.is_in_flight() {
                let st = self.status_from_locked(&inner);
                return Err(ApiError::new(
                    409,
                    "update_in_progress",
                    "A daemon update is already in progress",
                )
                .with_extra(json!({ "status": st })));
            }
            if let Some(old_staged) = inner.staged_path.take() {
                let _ = fs::remove_file(old_staged);
            }
            clear_last_error();
            inner.generation += 1;
            let gen = inner.generation;
            inner.phase = UpdatePhase::Checking;
            inner.target_version = Some(version.clone());
            inner.target_build_hash = expected_hash.clone();
            inner.when_busy = Some(when_busy);
            inner.pending_is_rollback = false;
            inner.detail = Some("Checking release checksum...".to_string());
            inner.error = None;
            inner.updated_at = now_ms();
            let st = self.status_from_locked(&inner);
            (gen, st)
        };

        self.broadcast_status(checking_status.clone());

        let mgr = Arc::clone(self);
        thread::spawn(move || {
            mgr.run_update_worker(job_gen, version, expected_hash, when_busy, force);
        });

        Ok((202, checking_status))
    }

    fn run_update_worker(
        self: &Arc<Self>,
        job_gen: u64,
        version: String,
        expected_hash: Option<String>,
        when_busy: WhenBusy,
        force: bool,
    ) {
        let mgr_phase = Arc::clone(self);
        let staged =
            match download_and_stage(&version, expected_hash.as_deref(), move |phase_str| {
                let (phase, detail) = match phase_str {
                    "checking" => (UpdatePhase::Checking, "Checking release checksum..."),
                    "downloading" => (UpdatePhase::Downloading, "Downloading daemon binary..."),
                    "verifying" => (
                        UpdatePhase::Verifying,
                        "Verifying SHA-256 and binary health...",
                    ),
                    _ => return,
                };
                let maybe_status = {
                    let mut inner = mgr_phase.inner.lock().unwrap();
                    if inner.generation != job_gen {
                        return;
                    }
                    if inner.phase == phase {
                        return;
                    }
                    inner.phase = phase;
                    inner.detail = Some(detail.to_string());
                    inner.updated_at = now_ms();
                    Some(mgr_phase.status_from_locked(&inner))
                };
                if let Some(st) = maybe_status {
                    mgr_phase.broadcast_status(st);
                }
            }) {
                Ok(s) => s,
                Err(err) => {
                    let maybe_status = {
                        let mut inner = self.inner.lock().unwrap();
                        if inner.generation != job_gen {
                            return;
                        }
                        inner.phase = UpdatePhase::Error;
                        inner.detail = None;
                        inner.error = Some(err);
                        inner.updated_at = now_ms();
                        Some(self.status_from_locked(&inner))
                    };
                    if let Some(st) = maybe_status {
                        self.broadcast_status(st);
                    }
                    return;
                }
            };

        // Check if cancelled while downloading.
        {
            let inner = self.inner.lock().unwrap();
            if inner.generation != job_gen {
                let _ = fs::remove_file(&staged.staged_path);
                return;
            }
        }

        // Check if already running the downloaded build hash.
        if !force
            && staged
                .build_hash
                .eq_ignore_ascii_case(&current_running_hash())
        {
            let _ = fs::remove_file(&staged.staged_path);
            let maybe_status = {
                let mut inner = self.inner.lock().unwrap();
                if inner.generation != job_gen {
                    return;
                }
                inner.phase = UpdatePhase::UpToDate;
                inner.target_build_hash = Some(staged.build_hash);
                inner.staged_path = None;
                inner.detail = Some("Daemon is already up to date".to_string());
                inner.error = None;
                inner.updated_at = now_ms();
                Some(self.status_from_locked(&inner))
            };
            if let Some(st) = maybe_status {
                self.broadcast_status(st);
            }
            return;
        }

        // Decide whether to restart immediately, defer, or reject.
        enum NextAction {
            None,
            BroadcastOnly(Value),
            RestartNow(PathBuf, String, Value),
            DeferredCheckIdle(Value),
        }

        let action = {
            let mut inner = self.inner.lock().unwrap();
            if inner.generation != job_gen {
                let _ = fs::remove_file(&staged.staged_path);
                NextAction::None
            } else {
                let active_tabs = self.runner.active_tab_ids();
                inner.target_build_hash = Some(staged.build_hash.clone());
                if active_tabs.is_empty() || when_busy == WhenBusy::Force {
                    inner.phase = UpdatePhase::Restarting;
                    inner.staged_path = Some(staged.staged_path.clone());
                    inner.pending_is_rollback = false;
                    inner.detail = Some("Restarting daemon to apply update...".to_string());
                    inner.error = None;
                    inner.updated_at = now_ms();
                    let st = self.status_from_locked(&inner);
                    NextAction::RestartNow(staged.staged_path, staged.build_hash, st)
                } else if when_busy == WhenBusy::Reject {
                    let _ = fs::remove_file(&staged.staged_path);
                    inner.phase = UpdatePhase::Error;
                    inner.staged_path = None;
                    inner.pending_is_rollback = false;
                    inner.detail = None;
                    inner.error = Some(format!(
                        "Cannot restart while {} agent turn(s) are active",
                        active_tabs.len()
                    ));
                    inner.updated_at = now_ms();
                    let st = self.status_from_locked(&inner);
                    NextAction::BroadcastOnly(st)
                } else {
                    inner.phase = UpdatePhase::PendingRestart;
                    inner.staged_path = Some(staged.staged_path);
                    inner.pending_is_rollback = false;
                    inner.detail = Some(format!(
                        "Update verified; waiting for {} active turn(s) to finish",
                        active_tabs.len()
                    ));
                    inner.error = None;
                    inner.updated_at = now_ms();
                    let st = self.status_from_locked(&inner);
                    NextAction::DeferredCheckIdle(st)
                }
            }
        };

        match action {
            NextAction::None => {}
            NextAction::BroadcastOnly(st) => {
                self.broadcast_status(st);
            }
            NextAction::RestartNow(staged_path, target_hash, st) => {
                self.broadcast_status(st);
                self.trigger_restart_async(staged_path, target_hash);
            }
            NextAction::DeferredCheckIdle(st) => {
                self.broadcast_status(st);
                // Close any race where the last active turn finished right as we transitioned.
                if self.runner.active_tab_ids().is_empty() {
                    self.on_runner_idle();
                }
            }
        }
    }

    /// Handles `DELETE /api/daemon/update`.
    pub fn cancel_update(&self) -> Result<Value, ApiError> {
        let status = {
            let mut inner = self.inner.lock().unwrap();
            if inner.phase == UpdatePhase::Restarting {
                return Err(ApiError::new(
                    409,
                    "restart_in_progress",
                    "Cannot cancel update while daemon restart is already in progress",
                ));
            }
            inner.generation += 1;
            if let Some(staged) = inner.staged_path.take() {
                let _ = fs::remove_file(staged);
            }
            let _ = fs::remove_file(staged_daemon_path());
            inner.phase = UpdatePhase::Idle;
            inner.target_version = None;
            inner.target_build_hash = None;
            inner.when_busy = None;
            inner.pending_is_rollback = false;
            inner.detail = None;
            inner.error = None;
            inner.updated_at = now_ms();
            self.status_from_locked(&inner)
        };
        self.broadcast_status(status.clone());
        Ok(status)
    }

    /// Handles `POST /api/daemon/rollback`.
    pub fn start_rollback(
        self: &Arc<Self>,
        raw_when_busy: Option<&str>,
    ) -> Result<Value, ApiError> {
        let when_busy = match raw_when_busy.map(str::trim).filter(|s| !s.is_empty()) {
            None => WhenBusy::Force,
            Some(v) => {
                WhenBusy::parse(Some(v)).map_err(|e| ApiError::new(400, "invalid_when_busy", e))?
            }
        };

        let (rollback_available, prev_hash) = rollback_status_snapshot();
        if !rollback_available {
            return Err(ApiError::new(
                409,
                "no_rollback_available",
                "No previous daemon backup is available for rollback",
            ));
        }

        let active_tabs = self.runner.active_tab_ids();
        if when_busy == WhenBusy::Reject && !active_tabs.is_empty() {
            let st = self.status();
            return Err(ApiError::new(
                409,
                "busy",
                format!(
                    "Cannot rollback while {} agent turn(s) are active",
                    active_tabs.len()
                ),
            )
            .with_extra(json!({
                "activeTabIds": active_tabs,
                "activeTurnsCount": active_tabs.len(),
                "status": st,
            })));
        }

        let (status, should_defer) = {
            let mut inner = self.inner.lock().unwrap();
            if inner.phase.is_in_flight() {
                let st = self.status_from_locked(&inner);
                return Err(ApiError::new(
                    409,
                    "update_in_progress",
                    "A daemon update or restart is already in progress",
                )
                .with_extra(json!({ "status": st })));
            }
            clear_last_error();
            if let Some(staged) = inner.staged_path.take() {
                let _ = fs::remove_file(staged);
            }
            let _ = fs::remove_file(staged_daemon_path());
            inner.generation += 1;
            inner.target_version = Some("rollback".to_string());
            inner.target_build_hash = prev_hash;
            inner.when_busy = Some(when_busy);
            inner.error = None;
            inner.updated_at = now_ms();

            let active_now = self.runner.active_tab_ids();
            if when_busy == WhenBusy::Defer && !active_now.is_empty() {
                inner.phase = UpdatePhase::PendingRestart;
                inner.pending_is_rollback = true;
                inner.detail = Some(format!(
                    "Rollback queued; waiting for {} active turn(s) to finish",
                    active_now.len()
                ));
                (self.status_from_locked(&inner), true)
            } else {
                inner.phase = UpdatePhase::Restarting;
                inner.pending_is_rollback = false;
                inner.detail = Some("Reverting to previous daemon...".to_string());
                (self.status_from_locked(&inner), false)
            }
        };

        self.broadcast_status(status.clone());

        if should_defer {
            if self.runner.active_tab_ids().is_empty() {
                self.on_runner_idle();
            }
        } else {
            self.trigger_rollback_restart_async();
        }

        Ok(status)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    static ENV_LOCK: OnceLock<Mutex<()>> = OnceLock::new();

    fn lock_env() -> std::sync::MutexGuard<'static, ()> {
        ENV_LOCK.get_or_init(|| Mutex::new(())).lock().unwrap()
    }

    fn make_temp_dir(prefix: &str) -> PathBuf {
        let dir = env::temp_dir().join(format!(
            "{prefix}-{}-{}",
            std::process::id(),
            next_atomic_seq()
        ));
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn write_fake_daemon_script(path: &Path, hash: &str, version: &str) {
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent).unwrap();
        }
        let script = format!(
            "#!/bin/sh\nif [ \"$1\" = \"version\" ] && [ \"$2\" = \"--hash-only\" ]; then\n  echo \"{hash}\"\n  exit 0\nfi\nif [ \"$1\" = \"version\" ]; then\n  echo \"{version} {hash}\"\n  exit 0\nfi\nexit 0\n"
        );
        write_atomic_file(path, script.as_bytes(), 0o755).unwrap();
    }

    #[test]
    fn validate_version_tag_accepts_valid_and_rejects_malicious() {
        assert_eq!(validate_version_tag("latest").unwrap(), "latest");
        assert_eq!(validate_version_tag("  latest  ").unwrap(), "latest");
        assert_eq!(
            validate_version_tag("v0.3.42-abcdef1").unwrap(),
            "v0.3.42-abcdef1"
        );
        assert_eq!(
            validate_version_tag("v0.3.123-0123456789abcdef0123456789abcdef01234567-preview")
                .unwrap(),
            "v0.3.123-0123456789abcdef0123456789abcdef01234567-preview"
        );

        for bad in [
            "",
            "   ",
            "v0.3.1",
            "v0.3.1-abc",
            "v0.3.1-ABCDEF1",
            "v0.3.1-abcdef1-beta",
            "../v0.3.1-abcdef1",
            "v0.3.1-abcdef1/evil",
            "https://evil.example/bin",
            "v0.3.1-abcdef1?foo=bar",
            "v0.3.1-abcdef1;rm -rf /",
        ] {
            assert!(
                validate_version_tag(bad).is_err(),
                "expected tag '{bad}' to be rejected"
            );
        }
    }

    #[test]
    fn resolve_release_urls_builds_github_and_env_override_urls() {
        let (bin, sha) =
            resolve_release_urls_with_base("latest", "agentsterm-daemon-linux-x86_64", None)
                .unwrap();
        assert_eq!(
            bin,
            "https://github.com/AppEvol/agents-term/releases/latest/download/agentsterm-daemon-linux-x86_64"
        );
        assert_eq!(
            sha,
            "https://github.com/AppEvol/agents-term/releases/latest/download/agentsterm-daemon-linux-x86_64.sha256"
        );

        let (bin_tag, sha_tag) = resolve_release_urls_with_base(
            "v0.3.99-1234abc-preview",
            "agentsterm-daemon-linux-aarch64",
            None,
        )
        .unwrap();
        assert_eq!(
            bin_tag,
            "https://github.com/AppEvol/agents-term/releases/download/v0.3.99-1234abc-preview/agentsterm-daemon-linux-aarch64"
        );
        assert_eq!(
            sha_tag,
            "https://github.com/AppEvol/agents-term/releases/download/v0.3.99-1234abc-preview/agentsterm-daemon-linux-aarch64.sha256"
        );

        let (bin_override, sha_override) = resolve_release_urls_with_base(
            "v0.3.99-1234abc",
            "agentsterm-daemon-linux-x86_64",
            Some("http://127.0.0.1:18080/"),
        )
        .unwrap();
        assert_eq!(
            bin_override,
            "http://127.0.0.1:18080/agentsterm-daemon-linux-x86_64"
        );
        assert_eq!(
            sha_override,
            "http://127.0.0.1:18080/agentsterm-daemon-linux-x86_64.sha256"
        );
    }

    #[test]
    fn sha256_parsing_and_file_hashing() {
        let dir = make_temp_dir("agentsterm-sha256-unit");
        let file = dir.join("payload.bin");
        fs::write(&file, b"hello agentsterm\n").unwrap();
        let digest = compute_file_sha256(&file).unwrap();
        assert_eq!(digest.len(), 64);

        let parsed = parse_sha256_file(&format!(
            "  {}  agentsterm-daemon-linux-x86_64\n",
            digest.to_uppercase()
        ))
        .unwrap();
        assert_eq!(parsed, digest);

        assert!(parse_sha256_file("").is_err());
        assert!(parse_sha256_file("not-a-sha256  file").is_err());
        assert!(parse_sha256_file("deadbeef  file").is_err());

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn anti_clobber_prev_backup_preserves_known_good_binary() {
        let _guard = lock_env();
        let tmp_home = make_temp_dir("agentsterm-anticlobber");
        let prev_env = env::var("AGENTSTERM_HOME").ok();
        env::set_var("AGENTSTERM_HOME", &tmp_home);

        let hash_v1 = "1111111111111111111111111111111111111111";
        let hash_v2 = "2222222222222222222222222222222222222222";
        let hash_v3 = "3333333333333333333333333333333333333333";

        // Install healthy v1.
        write_fake_daemon_script(&installed_daemon_path(), hash_v1, "0.3.0");

        // Replacing v1 with the same hash must NOT create/clobber .prev.
        backup_current_binary_if_healthy(hash_v1).unwrap();
        assert!(!prev_daemon_path().exists());

        // Replacing healthy v1 with v2 saves v1 to .prev + update-prev.json.
        backup_current_binary_if_healthy(hash_v2).unwrap();
        assert!(prev_daemon_path().is_file());
        assert_eq!(query_binary_hash(&prev_daemon_path()).unwrap(), hash_v1);
        assert_eq!(read_prev_meta().unwrap().build_hash, hash_v1);

        // Simulate v2 installed while update-pending.json is still present (unconfirmed):
        // attempting another backup must NOT clobber .prev (must keep v1!).
        write_fake_daemon_script(&installed_daemon_path(), hash_v2, "0.3.1");
        write_pending_update(&PendingUpdate {
            target_hash: hash_v2.to_string(),
            previous_hash: Some(hash_v1.to_string()),
            started_at: now_ms(),
            attempts: 1,
        })
        .unwrap();
        backup_current_binary_if_healthy(hash_v3).unwrap();
        assert_eq!(query_binary_hash(&prev_daemon_path()).unwrap(), hash_v1);
        assert_eq!(read_prev_meta().unwrap().build_hash, hash_v1);

        // Once update-pending.json is cleared (v2 confirmed healthy), updating to v3 saves v2 to .prev.
        clear_pending_update();
        backup_current_binary_if_healthy(hash_v3).unwrap();
        assert_eq!(query_binary_hash(&prev_daemon_path()).unwrap(), hash_v2);
        assert_eq!(read_prev_meta().unwrap().build_hash, hash_v2);

        match prev_env {
            Some(v) => env::set_var("AGENTSTERM_HOME", v),
            None => env::remove_var("AGENTSTERM_HOME"),
        }
        let _ = fs::remove_dir_all(&tmp_home);
    }

    #[test]
    fn repeated_atomic_copy_or_link_same_inode_leaves_no_temp_files() {
        let dir = make_temp_dir("agentsterm-hardlink-repeat");
        let src = dir.join("agentsterm-daemon.prev");
        let dest = dir.join("agentsterm-daemon");
        fs::write(&src, b"#!/bin/sh\necho ok\n").unwrap();
        fs::set_permissions(&src, fs::Permissions::from_mode(0o755)).unwrap();

        atomic_copy_or_link(&src, &dest, 0o755).unwrap();
        atomic_copy_or_link(&src, &dest, 0o755).unwrap();

        let entries: Vec<String> = fs::read_dir(&dir)
            .unwrap()
            .flatten()
            .map(|e| e.file_name().to_string_lossy().to_string())
            .collect();
        assert!(
            !entries.iter().any(|n| n.contains(".tmp.")),
            "unexpected leaked temp files: {entries:?}"
        );
        let _ = fs::remove_dir_all(&dir);
    }
}
