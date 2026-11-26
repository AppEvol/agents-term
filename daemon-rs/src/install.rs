//! Native lifecycle management subcommands: `install` and `ensure-running`.
//!
//! Installs the user service or a detached daemon with an `@reboot` crontab entry.

use serde_json::Value;
use std::env;
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::net::TcpListener;
use std::os::unix::fs::{symlink, PermissionsExt};
use std::os::unix::process::CommandExt;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::thread;
use std::time::{Duration, Instant};

use crate::config::{
    config_dir, daemon_commit, detect_tailscale_ip, ensure_config_dir, ensure_live_debugging_doc,
    home_dir, load_or_create_config, pairing_uri, save_config, BUILD_HASH, SERVER_VERSION,
};
use crate::http_client::http_request;

const UNIT_NAME: &str = "herdr-mobile.service";
const CRON_MARKER: &str = "# agentsterm-daemon-reboot";

fn log_step(msg: &str) {
    println!(">>> {msg}");
}

fn die(code: i32, msg: &str) -> i32 {
    eprintln!("ERROR: {msg}");
    code
}

fn self_exe() -> PathBuf {
    env::current_exe().unwrap_or_else(|_| home_dir().join(".local/bin/agentsterm-daemon"))
}

fn env_is_one(key: &str) -> bool {
    env::var(key).map(|v| v.trim() == "1").unwrap_or(false)
}

fn probe_info(host: &str, port: u16, token: &str) -> Result<Value, String> {
    let resp = http_request(
        "GET",
        host,
        port,
        "/api/info",
        Some(token),
        None,
        Duration::from_secs(3),
    )?;
    if resp.status != 200 {
        return Err(format!("HTTP {}", resp.status));
    }
    resp.json()
}

fn running_matches_target(info: &Value, expected_commit: &str) -> bool {
    let commit = info.get("commit").and_then(Value::as_str).unwrap_or("");
    let build_hash = info.get("buildHash").and_then(Value::as_str).unwrap_or("");
    commit == expected_commit || (!build_hash.is_empty() && build_hash == BUILD_HASH)
}

fn pid_alive(pid: i32) -> bool {
    if pid <= 1 {
        return false;
    }
    unsafe { libc::kill(pid, 0) == 0 }
}

fn kill_pid_gracefully(pid: i32) {
    if !pid_alive(pid) || pid == std::process::id() as i32 {
        return;
    }
    unsafe {
        let _ = libc::kill(pid, libc::SIGTERM);
    }
    let deadline = Instant::now() + Duration::from_secs(3);
    while Instant::now() < deadline && pid_alive(pid) {
        thread::sleep(Duration::from_millis(100));
    }
    if pid_alive(pid) {
        unsafe {
            let _ = libc::kill(pid, libc::SIGKILL);
        }
    }
}

fn stop_detached() {
    let pid_file = config_dir().join("daemon.pid");
    if let Ok(raw) = fs::read_to_string(&pid_file) {
        if let Ok(pid) = raw.trim().parse::<i32>() {
            if pid_alive(pid) {
                log_step(&format!("Stopping previous detached daemon (pid {pid})"));
                kill_pid_gracefully(pid);
            }
        }
    }
    let _ = fs::remove_file(&pid_file);
}

fn stop_legacy_daemons() {
    if env_is_one("AGENTSTERM_KEEP_LEGACY") {
        return;
    }
    let my_pid = std::process::id() as i32;
    let Ok(entries) = fs::read_dir("/proc") else {
        return;
    };
    for entry in entries.flatten() {
        let Ok(pid) = entry.file_name().to_string_lossy().parse::<i32>() else {
            continue;
        };
        if pid == my_pid || pid <= 1 {
            continue;
        }
        let Ok(cmdline_bytes) = fs::read(entry.path().join("cmdline")) else {
            continue;
        };
        if cmdline_bytes.is_empty() {
            continue;
        }
        let cmdline = String::from_utf8_lossy(&cmdline_bytes).replace('\0', " ");
        let is_legacy_python = cmdline.contains("agentsterm-daemon")
            && (cmdline.contains("python") || cmdline.contains("agentsterm_daemon"));
        let is_legacy_node = cmdline.contains("agent-terminal") && cmdline.contains("node");
        let is_other_rust_serve = cmdline.contains("agentsterm-daemon")
            && cmdline.contains(" serve")
            && !cmdline.contains("install");
        if is_legacy_python || is_legacy_node || is_other_rust_serve {
            log_step(&format!("Stopping previous daemon process (pid {pid})"));
            kill_pid_gracefully(pid);
        }
    }
}

fn ensure_app_cli_symlinks(exe: &Path) {
    let bin_dir = home_dir().join(".local").join("bin");
    let _ = fs::create_dir_all(&bin_dir);
    for name in ["agents_term_app_cli", "app_state_cli"] {
        let link_path = bin_dir.join(name);
        if let Ok(meta) = fs::symlink_metadata(&link_path) {
            if meta.file_type().is_symlink() {
                let _ = fs::remove_file(&link_path);
            } else {
                continue;
            }
        }
        let _ = symlink(exe, &link_path);
    }
}

fn current_username() -> String {
    env::var("USER")
        .ok()
        .filter(|s| !s.is_empty())
        .or_else(|| {
            Command::new("id")
                .arg("-un")
                .output()
                .ok()
                .filter(|o| o.status.success())
                .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string())
                .filter(|s| !s.is_empty())
        })
        .unwrap_or_else(|| "root".to_string())
}

fn cmd_exists(name: &str) -> bool {
    Command::new("sh")
        .args(["-c", &format!("command -v {name} >/dev/null 2>&1")])
        .status()
        .map(|s| s.success())
        .unwrap_or(false)
}

fn ensure_user_bus_env() {
    let uid = unsafe { libc::getuid() };
    let runtime_dir = format!("/run/user/{uid}");
    if env::var("XDG_RUNTIME_DIR")
        .map(|s| s.is_empty())
        .unwrap_or(true)
        && Path::new(&runtime_dir).is_dir()
    {
        env::set_var("XDG_RUNTIME_DIR", &runtime_dir);
    }
    if env::var("DBUS_SESSION_BUS_ADDRESS")
        .map(|s| s.is_empty())
        .unwrap_or(true)
    {
        let bus = format!("{runtime_dir}/bus");
        if Path::new(&bus).exists() {
            env::set_var("DBUS_SESSION_BUS_ADDRESS", format!("unix:path={bus}"));
        }
    }
}

fn check_linger(user: &str) -> bool {
    let out = Command::new("loginctl")
        .args(["show-user", user, "-p", "Linger", "--value"])
        .output();
    if let Ok(o) = out {
        if o.status.success() && String::from_utf8_lossy(&o.stdout).trim() == "yes" {
            return true;
        }
    }
    Path::new(&format!("/var/lib/systemd/linger/{user}")).exists()
}

fn try_enable_linger(user: &str) -> bool {
    if check_linger(user) {
        return true;
    }
    if !cmd_exists("loginctl") {
        return false;
    }
    let _ = Command::new("loginctl")
        .args(["enable-linger", user])
        .status();
    if check_linger(user) {
        return true;
    }
    if cmd_exists("sudo") {
        let _ = Command::new("sudo")
            .args(["-n", "loginctl", "enable-linger", user])
            .status();
    }
    check_linger(user)
}

fn systemd_user_ready(user: &str) -> (bool, bool) {
    if env_is_one("AGENTSTERM_NO_SYSTEMD") || !cmd_exists("systemctl") {
        return (false, false);
    }
    let linger = try_enable_linger(user);
    // Give user@<uid>.service a brief window to bring up the user bus after enable-linger.
    let deadline = Instant::now() + Duration::from_secs(if linger { 5 } else { 1 });
    loop {
        ensure_user_bus_env();
        let ok = Command::new("systemctl")
            .args(["--user", "show-environment"])
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status()
            .map(|s| s.success())
            .unwrap_or(false);
        if ok {
            return (true, linger);
        }
        if Instant::now() >= deadline {
            return (false, linger);
        }
        thread::sleep(Duration::from_millis(250));
    }
}

fn shell_quote(s: &str) -> String {
    format!("'{}'", s.replace('\'', "'\"'\"'"))
}

fn read_crontab() -> Option<String> {
    if env_is_one("AGENTSTERM_NO_CRON") || !cmd_exists("crontab") {
        return None;
    }
    let out = Command::new("crontab").arg("-l").output().ok()?;
    if out.status.success() {
        Some(String::from_utf8_lossy(&out.stdout).to_string())
    } else {
        let stderr = String::from_utf8_lossy(&out.stderr).to_ascii_lowercase();
        if stderr.contains("no crontab") {
            Some(String::new())
        } else {
            None
        }
    }
}

fn write_crontab(content: &str) -> bool {
    let Ok(mut child) = Command::new("crontab")
        .arg("-")
        .stdin(Stdio::piped())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
    else {
        return false;
    };
    if let Some(mut stdin) = child.stdin.take() {
        let _ = stdin.write_all(content.as_bytes());
    }
    child.wait().map(|s| s.success()).unwrap_or(false)
}

fn has_cron_reboot() -> bool {
    read_crontab()
        .map(|c| c.lines().any(|l| l.contains(CRON_MARKER)))
        .unwrap_or(false)
}

fn remove_cron_reboot() {
    let Some(existing) = read_crontab() else {
        return;
    };
    if !existing.contains(CRON_MARKER) {
        return;
    }
    let filtered: Vec<&str> = existing
        .lines()
        .filter(|l| !l.contains(CRON_MARKER) && !l.contains("herdr-mobile/start-daemon.sh"))
        .collect();
    let new_content = if filtered.is_empty() {
        String::new()
    } else {
        format!("{}\n", filtered.join("\n"))
    };
    let _ = write_crontab(&new_content);
}

fn install_cron_reboot(exe: &Path, host: &str, port: u16) -> bool {
    let Some(existing) = read_crontab() else {
        return false;
    };
    let mut lines: Vec<String> = existing
        .lines()
        .filter(|l| !l.contains(CRON_MARKER) && !l.contains("herdr-mobile/start-daemon.sh"))
        .map(str::to_string)
        .collect();
    let entry = format!(
        "@reboot {} ensure-running --host {} --port {} {}",
        shell_quote(&exe.to_string_lossy()),
        shell_quote(host),
        port,
        CRON_MARKER
    );
    lines.push(entry);
    write_crontab(&format!("{}\n", lines.join("\n")))
}

fn spawn_detached_daemon(exe: &Path, host: &str, port: u16) -> Result<u32, String> {
    ensure_config_dir().map_err(|e| format!("Failed to create config dir: {e}"))?;
    let log_path = config_dir().join("daemon.log");
    let log_file = OpenOptions::new()
        .create(true)
        .append(true)
        .open(&log_path)
        .map_err(|e| format!("Failed to open {}: {e}", log_path.display()))?;
    let log_err = log_file
        .try_clone()
        .map_err(|e| format!("Failed to clone log file descriptor: {e}"))?;

    let mut cmd = Command::new(exe);
    cmd.args(["serve", "--host", host, "--port", &port.to_string()])
        .stdin(Stdio::null())
        .stdout(Stdio::from(log_file))
        .stderr(Stdio::from(log_err));

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
    let _ = fs::write(config_dir().join("daemon.pid"), format!("{pid}\n"));
    Ok(pid)
}

fn print_tail_diagnostics() {
    let log_path = config_dir().join("daemon.log");
    if let Ok(content) = fs::read_to_string(&log_path) {
        let lines: Vec<&str> = content.lines().collect();
        let start = lines.len().saturating_sub(20);
        for line in &lines[start..] {
            eprintln!("{line}");
        }
    }
    if !env_is_one("AGENTSTERM_NO_SYSTEMD") && cmd_exists("systemctl") {
        let _ = Command::new("systemctl")
            .args(["--user", "status", UNIT_NAME, "--no-pager", "-n", "20"])
            .stdout(Stdio::inherit())
            .stderr(Stdio::inherit())
            .status();
    }
}

pub fn run_install(host_arg: Option<String>, port: u16, force_restart: bool) -> i32 {
    let exe = self_exe();
    let ts_ip = host_arg
        .filter(|s| !s.trim().is_empty())
        .or_else(|| {
            env::var("AGENTSTERM_HOST")
                .ok()
                .filter(|s| !s.trim().is_empty())
        })
        .or_else(|| detect_tailscale_ip(false));
    let Some(host) = ts_ip else {
        return die(
            3,
            "Tailscale has no IPv4 address (or tailscale CLI not found); run 'sudo tailscale up' and retry, or pass --host.",
        );
    };

    if let Err(err) = ensure_config_dir() {
        return die(1, &format!("Could not create config dir: {err}"));
    }
    ensure_live_debugging_doc();
    ensure_app_cli_symlinks(&exe);

    let mut cfg = load_or_create_config();
    if cfg.host.as_deref() != Some(host.as_str()) || cfg.port != Some(port) {
        cfg.host = Some(host.clone());
        cfg.port = Some(port);
        let _ = save_config(&cfg);
    }

    let expected_commit = daemon_commit();
    let user = current_username();
    let (systemd_ready, linger_enabled) = systemd_user_ready(&user);

    let already_current = if !force_restart {
        probe_info(&host, port, &cfg.token)
            .ok()
            .map(|info| running_matches_target(&info, &expected_commit))
            .unwrap_or(false)
    } else {
        false
    };

    let mode: String;
    let reboot_status: String;

    if already_current {
        log_step(&format!(
            "Daemon v{SERVER_VERSION} ({}) already running on {host}:{port}; skipping restart to preserve live turns",
            &expected_commit[..expected_commit.len().min(12)]
        ));
        if systemd_ready {
            mode = format!("systemd --user ({UNIT_NAME}, unchanged)");
            reboot_status = if linger_enabled {
                "will survive reboots (systemd --user with linger enabled)".to_string()
            } else {
                format!(
                    "will NOT survive a reboot or logout (could not enable linger; run 'sudo loginctl enable-linger {user}')"
                )
            };
        } else if has_cron_reboot() {
            mode = "detached process (unchanged)".to_string();
            reboot_status = "will survive reboots (via @reboot crontab entry)".to_string();
        } else if install_cron_reboot(&exe, &host, port) {
            log_step("Installed @reboot crontab entry for existing detached daemon");
            mode = "detached process + @reboot crontab (unchanged)".to_string();
            reboot_status = "will survive reboots (via @reboot crontab entry)".to_string();
        } else {
            mode = "detached process (unchanged)".to_string();
            reboot_status = format!(
                "will NOT survive a reboot (systemd --user and crontab are unavailable; run 'sudo loginctl enable-linger {user}' and re-run install --force-restart)"
            );
        }
    } else {
        stop_legacy_daemons();
        if systemd_ready {
            let unit_dir = home_dir().join(".config/systemd/user");
            if let Err(err) = fs::create_dir_all(&unit_dir) {
                return die(
                    1,
                    &format!("Could not create {}: {err}", unit_dir.display()),
                );
            }
            let unit_path = unit_dir.join(UNIT_NAME);
            let unit_body = format!(
                "[Unit]\n\
Description=AgentsTerm VM daemon (Rust)\n\
After=network-online.target tailscaled.service\n\
Wants=network-online.target\n\
\n\
[Service]\n\
Type=simple\n\
ExecStart={} serve --host {} --port {}\n\
Restart=always\n\
RestartSec=3\n\
Environment=PATH=%h/.local/bin:%h/.npm-global/bin:%h/.cargo/bin:/usr/local/bin:/usr/bin:/bin\n\
\n\
[Install]\n\
WantedBy=default.target\n",
                exe.display(),
                host,
                port
            );
            if let Err(err) = fs::write(&unit_path, unit_body) {
                return die(
                    1,
                    &format!("Could not write {}: {err}", unit_path.display()),
                );
            }
            let _ = fs::set_permissions(&unit_path, fs::Permissions::from_mode(0o644));
            log_step(&format!("Installed {}", unit_path.display()));
            let _ = Command::new("systemctl")
                .args(["--user", "daemon-reload"])
                .status();
            let _ = Command::new("systemctl")
                .args(["--user", "enable", "-q", UNIT_NAME])
                .status();
            remove_cron_reboot();
            stop_detached();
            let restart_ok = Command::new("systemctl")
                .args(["--user", "restart", UNIT_NAME])
                .status()
                .map(|s| s.success())
                .unwrap_or(false);
            if !restart_ok {
                print_tail_diagnostics();
                return die(4, "systemctl --user restart failed.");
            }
            mode = format!("systemd --user ({UNIT_NAME})");
            reboot_status = if linger_enabled {
                "will survive reboots (systemd --user with linger enabled)".to_string()
            } else {
                log_step(&format!(
                    "note: could not enable linger; run 'sudo loginctl enable-linger {user}' so systemd --user starts at boot"
                ));
                format!(
                    "will NOT survive a reboot or logout (could not enable linger; run 'sudo loginctl enable-linger {user}')"
                )
            };
        } else {
            log_step("systemd --user is unavailable; starting a detached process");
            stop_detached();
            let pid = match spawn_detached_daemon(&exe, &host, port) {
                Ok(p) => p,
                Err(err) => return die(4, &err),
            };
            let log_path = config_dir().join("daemon.log");
            if install_cron_reboot(&exe, &host, port) {
                log_step("Installed @reboot crontab fallback (agentsterm-daemon ensure-running)");
                mode = format!(
                    "detached process + @reboot crontab (pid {pid}, log {})",
                    log_path.display()
                );
                reboot_status = "will survive reboots (via @reboot crontab entry -> agentsterm-daemon ensure-running)".to_string();
            } else {
                mode = format!("detached process (pid {pid}, log {})", log_path.display());
                reboot_status = format!(
                    "will NOT survive a reboot (systemd --user and crontab are unavailable; run 'sudo loginctl enable-linger {user}' and re-run install --force-restart)"
                );
            }
        }
    }

    log_step(&format!(
        "Waiting for the daemon on http://{host}:{port} ..."
    ));
    let deadline = Instant::now() + Duration::from_secs(20);
    let mut last_err: String;
    loop {
        match probe_info(&host, port, &cfg.token) {
            Ok(info) => {
                if running_matches_target(&info, &expected_commit) {
                    let ver = info
                        .get("version")
                        .and_then(Value::as_str)
                        .unwrap_or(SERVER_VERSION);
                    let commit = info
                        .get("commit")
                        .and_then(Value::as_str)
                        .unwrap_or("unknown");
                    let host_name = info
                        .get("hostname")
                        .and_then(Value::as_str)
                        .unwrap_or("unknown");
                    log_step(&format!(
                        "Daemon v{ver} (commit {}) healthy on {host}:{port} (hostname {host_name})",
                        &commit[..commit.len().min(12)]
                    ));
                    break;
                }
                let got = info
                    .get("commit")
                    .and_then(Value::as_str)
                    .unwrap_or("unknown");
                last_err = format!(
                    "daemon reports commit {}, expected {}",
                    &got[..got.len().min(12)],
                    &expected_commit[..expected_commit.len().min(12)]
                );
            }
            Err(err) => {
                last_err = err;
            }
        }
        if Instant::now() >= deadline {
            eprintln!("ERROR: daemon is not healthy on the expected build: {last_err}");
            print_tail_diagnostics();
            return die(4, "the daemon failed its health check (see output above).");
        }
        thread::sleep(Duration::from_millis(500));
    }

    log_step(&format!("Running as {mode}"));
    log_step(&format!("Reboot persistence: {reboot_status}"));
    let uri = pairing_uri(&host, port, &cfg.token);
    println!("Host:  {host}");
    println!("Port:  {port}");
    println!("Token: {}", cfg.token);
    println!();
    println!("{uri}");
    0
}

pub fn run_ensure_running(host_arg: Option<String>, port: u16) -> i32 {
    let cfg = load_or_create_config();
    let host = host_arg
        .filter(|s| !s.trim().is_empty())
        .or_else(|| cfg.host.clone())
        .or_else(|| detect_tailscale_ip(false))
        .unwrap_or_else(|| "127.0.0.1".to_string());

    // Wait up to 60s for the bind interface (e.g. tailscale0 after reboot) or an existing daemon.
    let deadline = Instant::now() + Duration::from_secs(60);
    loop {
        if probe_info(&host, port, &cfg.token).is_ok() {
            return 0;
        }
        if TcpListener::bind(format!("{host}:{port}")).is_ok() {
            break;
        }
        if Instant::now() >= deadline {
            break;
        }
        thread::sleep(Duration::from_secs(1));
    }

    if probe_info(&host, port, &cfg.token).is_ok() {
        return 0;
    }

    let exe = self_exe();
    match spawn_detached_daemon(&exe, &host, port) {
        Ok(_) => 0,
        Err(err) => die(1, &err),
    }
}
