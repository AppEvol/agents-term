use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::collections::HashSet;
use std::fs::{self, File};
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::thread;
use std::time::{Duration, Instant};

fn real_bin_path() -> PathBuf {
    PathBuf::from(env!("CARGO_BIN_EXE_agentsterm-daemon"))
}

fn asset_name() -> &'static str {
    match std::env::consts::ARCH {
        "x86_64" => "agentsterm-daemon-linux-x86_64",
        "aarch64" => "agentsterm-daemon-linux-aarch64",
        other => panic!("unsupported test arch: {other}"),
    }
}

fn used_ports() -> &'static Mutex<HashSet<u16>> {
    static PORTS: OnceLock<Mutex<HashSet<u16>>> = OnceLock::new();
    PORTS.get_or_init(|| Mutex::new(HashSet::new()))
}

fn spawn_lock() -> &'static Mutex<()> {
    static LOCK: OnceLock<Mutex<()>> = OnceLock::new();
    LOCK.get_or_init(|| Mutex::new(()))
}

fn bind_unique_listener() -> (TcpListener, u16) {
    let mut used = used_ports().lock().unwrap();
    let mut held = Vec::new();
    loop {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        if used.insert(port) {
            drop(held);
            return (listener, port);
        }
        held.push(listener);
    }
}

fn free_port() -> u16 {
    let (listener, port) = bind_unique_listener();
    drop(listener);
    port
}

fn make_temp_dir(prefix: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!(
        "{prefix}-{}-{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    fs::create_dir_all(&dir).unwrap();
    dir
}

fn sha256_hex(bytes: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(bytes);
    hasher
        .finalize()
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}

fn make_wrapper_bytes(real_bin: &Path, hash: &str) -> Vec<u8> {
    format!(
        "#!/bin/sh\n\
         if [ \"$1\" = \"version\" ] && [ \"$2\" = \"--hash-only\" ]; then\n\
           echo \"{hash}\"\n\
           exit 0\n\
         fi\n\
         if [ \"$1\" = \"version\" ]; then\n\
           echo \"0.3.0 {hash}\"\n\
           exit 0\n\
         fi\n\
         export AGENTSTERM_DAEMON_COMMIT=\"{hash}\"\n\
         exec \"{}\" \"$@\"\n",
        real_bin.display()
    )
    .into_bytes()
}

fn make_broken_serve_bytes(hash: &str) -> Vec<u8> {
    format!(
        "#!/bin/sh\n\
         if [ \"$1\" = \"version\" ] && [ \"$2\" = \"--hash-only\" ]; then\n\
           echo \"{hash}\"\n\
           exit 0\n\
         fi\n\
         if [ \"$1\" = \"version\" ]; then\n\
           echo \"0.3.0 {hash}\"\n\
           exit 0\n\
         fi\n\
         echo \"simulated broken daemon startup\" >&2\n\
         exit 42\n"
    )
    .into_bytes()
}

fn spawn_retry_etxtbsy(cmd: &mut Command) -> Child {
    let _guard = spawn_lock().lock().unwrap();
    for attempt in 0..8 {
        match cmd.spawn() {
            Ok(child) => return child,
            Err(e) if e.raw_os_error() == Some(libc::ETXTBSY) && attempt < 7 => {
                thread::sleep(Duration::from_millis(25 * (attempt as u64 + 1)));
            }
            Err(e) => panic!("spawn failed: {e}"),
        }
    }
    cmd.spawn().expect("spawn failed")
}

fn output_retry_etxtbsy(cmd: &mut Command) -> std::process::Output {
    for attempt in 0..8 {
        let res = {
            let _guard = spawn_lock().lock().unwrap();
            cmd.stdin(Stdio::null())
                .stdout(Stdio::piped())
                .stderr(Stdio::piped())
                .spawn()
        };
        match res {
            Ok(child) => return child.wait_with_output().expect("wait_with_output failed"),
            Err(e) if e.raw_os_error() == Some(libc::ETXTBSY) && attempt < 7 => {
                thread::sleep(Duration::from_millis(25 * (attempt as u64 + 1)));
            }
            Err(e) => panic!("command output failed: {e}"),
        }
    }
    cmd.output().expect("command output failed")
}

fn write_exec_bytes(path: &Path, bytes: &[u8]) {
    let _guard = spawn_lock().lock().unwrap();
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).unwrap();
    }
    let tmp = path.with_file_name(format!(
        ".{}.tmp.{}",
        path.file_name().and_then(|n| n.to_str()).unwrap_or("bin"),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    fs::write(&tmp, bytes).unwrap();
    fs::set_permissions(&tmp, fs::Permissions::from_mode(0o755)).unwrap();
    fs::rename(&tmp, path).unwrap();
}

fn read_pid_from_file(path: &Path) -> Option<i32> {
    fs::read_to_string(path)
        .ok()?
        .lines()
        .next()?
        .trim()
        .parse::<i32>()
        .ok()
        .filter(|&p| p > 1)
}

struct IsolatedEnv {
    root: PathBuf,
    app_home: PathBuf,
    child: Option<Child>,
}

impl IsolatedEnv {
    fn new(prefix: &str) -> Self {
        let root = make_temp_dir(prefix);
        let app_home = root.join("agents-term-home");
        fs::create_dir_all(app_home.join("bin")).unwrap();
        Self {
            root,
            app_home,
            child: None,
        }
    }

    fn installed_bin(&self) -> PathBuf {
        self.app_home.join("bin/agentsterm-daemon")
    }

    fn prev_bin(&self) -> PathBuf {
        self.app_home.join("bin/agentsterm-daemon.prev")
    }

    fn staged_bin(&self) -> PathBuf {
        self.app_home.join("bin/.agentsterm-daemon.staged")
    }

    fn token(&self) -> String {
        let raw = fs::read_to_string(self.app_home.join("config.json")).unwrap();
        let cfg: Value = serde_json::from_str(&raw).unwrap();
        cfg["token"].as_str().unwrap().to_string()
    }

    fn lock_pid(&self) -> Option<i32> {
        read_pid_from_file(&self.app_home.join("daemon.lock"))
    }
}

impl Drop for IsolatedEnv {
    fn drop(&mut self) {
        if let Some(ref mut child) = self.child {
            let _ = child.kill();
            let _ = child.wait();
        }
        let my_pid = std::process::id() as i32;
        for pid_file in [
            self.app_home.join("daemon.lock"),
            self.app_home.join("daemon.pid"),
        ] {
            if let Some(pid) = read_pid_from_file(&pid_file) {
                if pid != my_pid {
                    unsafe {
                        let _ = libc::kill(pid, libc::SIGKILL);
                    }
                }
            }
        }
        let _ = fs::remove_dir_all(&self.root);
    }
}

struct MockArtifact {
    binary_bytes: Vec<u8>,
    sha256_body: String,
}

struct MockReleaseServer {
    port: u16,
    artifact: Arc<Mutex<MockArtifact>>,
    stop: Arc<AtomicBool>,
    handle: Option<thread::JoinHandle<()>>,
}

impl MockReleaseServer {
    fn start(binary_bytes: Vec<u8>) -> Self {
        let sha = sha256_hex(&binary_bytes);
        let asset = asset_name();
        let sha256_body = format!("{sha}  {asset}\n");
        let artifact = Arc::new(Mutex::new(MockArtifact {
            binary_bytes,
            sha256_body,
        }));
        let (listener, port) = bind_unique_listener();
        listener.set_nonblocking(true).unwrap();
        let stop = Arc::new(AtomicBool::new(false));

        let stop_bg = Arc::clone(&stop);
        let art_bg = Arc::clone(&artifact);
        let handle = thread::spawn(move || {
            let bin_path = format!("/{asset}");
            let sha_path = format!("/{asset}.sha256");
            while !stop_bg.load(Ordering::Relaxed) {
                match listener.accept() {
                    Ok((mut stream, _)) => {
                        let _ = stream.set_read_timeout(Some(Duration::from_secs(5)));
                        let _ = stream.set_write_timeout(Some(Duration::from_secs(5)));
                        let mut reader = BufReader::new(stream.try_clone().unwrap());
                        let mut req_line = String::new();
                        if reader.read_line(&mut req_line).is_ok() {
                            let path = req_line.split_whitespace().nth(1).unwrap_or("");
                            // Drain headers.
                            loop {
                                let mut h = String::new();
                                if reader.read_line(&mut h).unwrap_or(0) == 0
                                    || h == "\r\n"
                                    || h == "\n"
                                {
                                    break;
                                }
                            }
                            let (status, content_type, body) = {
                                let guard = art_bg.lock().unwrap();
                                if path == bin_path {
                                    (
                                        "200 OK",
                                        "application/octet-stream",
                                        guard.binary_bytes.clone(),
                                    )
                                } else if path == sha_path {
                                    (
                                        "200 OK",
                                        "text/plain",
                                        guard.sha256_body.as_bytes().to_vec(),
                                    )
                                } else {
                                    ("404 Not Found", "text/plain", b"not found".to_vec())
                                }
                            };
                            let resp_head = format!(
                                "HTTP/1.1 {status}\r\nContent-Type: {content_type}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                                body.len()
                            );
                            let _ = stream.write_all(resp_head.as_bytes());
                            let _ = stream.write_all(&body);
                            let _ = stream.flush();
                        }
                    }
                    Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                        thread::sleep(Duration::from_millis(20));
                    }
                    Err(_) => break,
                }
            }
        });

        Self {
            port,
            artifact,
            stop,
            handle: Some(handle),
        }
    }

    fn base_url(&self) -> String {
        format!("http://127.0.0.1:{}", self.port)
    }

    fn set_binary(&self, binary_bytes: Vec<u8>) {
        let sha = sha256_hex(&binary_bytes);
        let asset = asset_name();
        let mut guard = self.artifact.lock().unwrap();
        guard.binary_bytes = binary_bytes;
        guard.sha256_body = format!("{sha}  {asset}\n");
    }

    fn set_sha256_raw(&self, sha256_body: String) {
        self.artifact.lock().unwrap().sha256_body = sha256_body;
    }
}

impl Drop for MockReleaseServer {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        let _ = TcpStream::connect(format!("127.0.0.1:{}", self.port));
        if let Some(h) = self.handle.take() {
            let _ = h.join();
        }
    }
}

fn http_call(
    port: u16,
    method: &str,
    path: &str,
    token: Option<&str>,
    body: Option<Value>,
) -> Result<(u16, Value), String> {
    let mut stream = TcpStream::connect_timeout(
        &format!("127.0.0.1:{port}").parse().unwrap(),
        Duration::from_secs(3),
    )
    .map_err(|e| e.to_string())?;
    let _ = stream.set_read_timeout(Some(Duration::from_secs(10)));
    let _ = stream.set_write_timeout(Some(Duration::from_secs(5)));
    let payload = body.map(|b| b.to_string());
    let mut req =
        format!("{method} {path} HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nConnection: close\r\n");
    if let Some(tok) = token {
        req.push_str(&format!("Authorization: Bearer {tok}\r\n"));
    }
    if let Some(ref p) = payload {
        req.push_str("Content-Type: application/json\r\n");
        req.push_str(&format!("Content-Length: {}\r\n", p.len()));
    }
    req.push_str("\r\n");
    stream
        .write_all(req.as_bytes())
        .map_err(|e| e.to_string())?;
    if let Some(ref p) = payload {
        stream.write_all(p.as_bytes()).map_err(|e| e.to_string())?;
    }
    let mut raw = String::new();
    stream.read_to_string(&mut raw).map_err(|e| e.to_string())?;
    let Some((head, body_str)) = raw.split_once("\r\n\r\n") else {
        return Err(format!("Malformed HTTP response: {raw}"));
    };
    let status: u16 = head
        .lines()
        .next()
        .and_then(|l| l.split_whitespace().nth(1))
        .and_then(|s| s.parse().ok())
        .ok_or_else(|| format!("Bad status line: {head}"))?;
    let parsed = serde_json::from_str(body_str).unwrap_or(Value::Null);
    Ok((status, parsed))
}

fn wait_for_info_matching<F>(port: u16, token: &str, timeout: Duration, pred: F) -> Value
where
    F: Fn(&Value) -> bool,
{
    let deadline = Instant::now() + timeout;
    let mut last = String::new();
    while Instant::now() < deadline {
        match http_call(port, "GET", "/api/info", Some(token), None) {
            Ok((200, info)) => {
                if pred(&info) {
                    return info;
                }
                last = format!("200 {info}");
            }
            Ok((code, body)) => {
                last = format!("{code} {body}");
            }
            Err(err) => {
                last = err;
            }
        }
        thread::sleep(Duration::from_millis(120));
    }
    panic!("Timed out waiting for /api/info match (last={last})");
}

fn spawn_installed_daemon(
    iso: &mut IsolatedEnv,
    port: u16,
    release_base_url: &str,
    extra_env: &[(&str, &str)],
) -> String {
    let stderr_path = iso.root.join("daemon-stderr.log");
    let stderr_file = File::create(&stderr_path).expect("create stderr log");
    let mut cmd = Command::new(iso.installed_bin());
    cmd.args(["serve", "--host", "127.0.0.1", "--port", &port.to_string()])
        .env("HOME", &iso.root)
        .env("AGENTSTERM_HOME", &iso.app_home)
        .env("AGENTSTERM_NO_SYSTEMD", "1")
        .env("AGENTSTERM_NO_CRON", "1")
        .env("AGENTSTERM_KEEP_LEGACY", "1")
        .env("AGENTSTERM_RELEASE_BASE_URL", release_base_url)
        .env_remove("INVOCATION_ID")
        .env_remove("AGENTSTERM_DAEMON_COMMIT")
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::from(stderr_file));
    for (k, v) in extra_env {
        cmd.env(k, v);
    }
    let child = spawn_retry_etxtbsy(&mut cmd);
    iso.child = Some(child);

    let deadline = Instant::now() + Duration::from_secs(20);
    let mut last_err = String::new();
    while Instant::now() < deadline {
        if let Some(ref mut ch) = iso.child {
            if let Ok(Some(status)) = ch.try_wait() {
                let stderr_log = fs::read_to_string(&stderr_path).unwrap_or_default();
                panic!("Daemon exited early with {status} on port {port}; stderr:\n{stderr_log}");
            }
        }
        if iso.app_home.join("config.json").is_file() {
            let token = iso.token();
            match http_call(port, "GET", "/api/info", Some(&token), None) {
                Ok((200, _)) => return token,
                Ok((code, body)) => last_err = format!("HTTP {code} {body}"),
                Err(err) => last_err = err,
            }
        }
        thread::sleep(Duration::from_millis(100));
    }
    let stderr_log = fs::read_to_string(&stderr_path).unwrap_or_default();
    panic!(
        "Daemon did not become healthy on port {port} (last_err={last_err}); stderr:\n{stderr_log}"
    );
}

#[test]
fn cli_update_stage_only_full_update_validation_and_prev_rollback() {
    let iso = IsolatedEnv::new("agentsterm-su-cli");
    let port = free_port();
    let h1 = "1111111111111111111111111111111111111111";
    let h2 = "2222222222222222222222222222222222222222";
    let v1_bytes = make_wrapper_bytes(&real_bin_path(), h1);
    let v2_bytes = make_wrapper_bytes(&real_bin_path(), h2);
    write_exec_bytes(&iso.installed_bin(), &v1_bytes);

    let mock = MockReleaseServer::start(v2_bytes.clone());

    let run_cli = |bin: &Path, args: &[&str]| {
        let mut cmd = Command::new(bin);
        cmd.args(args)
            .env("HOME", &iso.root)
            .env("AGENTSTERM_HOME", &iso.app_home)
            .env("AGENTSTERM_NO_SYSTEMD", "1")
            .env("AGENTSTERM_NO_CRON", "1")
            .env("AGENTSTERM_KEEP_LEGACY", "1")
            .env("AGENTSTERM_RELEASE_BASE_URL", mock.base_url())
            .env_remove("INVOCATION_ID");
        output_retry_etxtbsy(&mut cmd)
    };

    // Invalid version tag rejected with exit 2.
    let bad_tag = run_cli(&iso.installed_bin(), &["update", "../evil"]);
    assert_eq!(bad_tag.status.code(), Some(2));

    // Tampered checksum rejected and installed binary untouched.
    mock.set_sha256_raw(
        "0000000000000000000000000000000000000000000000000000000000000000  bad\n".to_string(),
    );
    let bad_sha = run_cli(
        &iso.installed_bin(),
        &["update", "v0.3.2-2222222", "--stage-only"],
    );
    assert!(!bad_sha.status.success());
    assert!(String::from_utf8_lossy(&bad_sha.stderr).contains("SHA-256 mismatch"));
    assert!(!iso.staged_bin().exists());

    // Restore valid checksum; wrong expected-hash rejected.
    mock.set_binary(v2_bytes);
    let bad_hash = run_cli(
        &iso.installed_bin(),
        &[
            "update",
            "v0.3.2-2222222",
            "--expected-hash",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "--stage-only",
        ],
    );
    assert!(!bad_hash.status.success());
    assert!(String::from_utf8_lossy(&bad_hash.stderr).contains("does not match expected hash"));
    assert!(!iso.staged_bin().exists());

    // --stage-only verifies and stages without touching installed binary or .prev.
    let stage = run_cli(
        &iso.installed_bin(),
        &[
            "update",
            "v0.3.2-2222222",
            "--expected-hash",
            h2,
            "--stage-only",
        ],
    );
    assert!(
        stage.status.success(),
        "stage-only failed:\n{}\n{}",
        String::from_utf8_lossy(&stage.stdout),
        String::from_utf8_lossy(&stage.stderr)
    );
    assert!(iso.staged_bin().is_file());
    assert!(!iso.prev_bin().exists());

    // Full CLI update replaces installed binary, backs up v1 to .prev, and starts v2.
    let port_str = port.to_string();
    let full = run_cli(
        &iso.installed_bin(),
        &[
            "update",
            "v0.3.2-2222222",
            "--expected-hash",
            h2,
            "--host",
            "127.0.0.1",
            "--port",
            &port_str,
        ],
    );
    assert!(
        full.status.success(),
        "full CLI update failed:\n{}\n{}",
        String::from_utf8_lossy(&full.stdout),
        String::from_utf8_lossy(&full.stderr)
    );
    assert!(iso.prev_bin().is_file());

    let token = iso.token();
    let info_v2 = wait_for_info_matching(port, &token, Duration::from_secs(10), |info| {
        info["commit"].as_str() == Some(h2)
    });
    assert_eq!(info_v2["selfUpdate"]["supported"].as_bool(), Some(true));
    assert_eq!(
        info_v2["selfUpdate"]["rollbackAvailable"].as_bool(),
        Some(true)
    );
    assert_eq!(
        info_v2["selfUpdate"]["previousBuildHash"].as_str(),
        Some(h1)
    );

    // Breakglass rollback via `~/.agents-term/bin/agentsterm-daemon.prev rollback --port <port>`.
    let rb = run_cli(
        &iso.prev_bin(),
        &["rollback", "--host", "127.0.0.1", "--port", &port_str],
    );
    assert!(
        rb.status.success(),
        ".prev rollback failed:\n{}\n{}",
        String::from_utf8_lossy(&rb.stdout),
        String::from_utf8_lossy(&rb.stderr)
    );
    let info_v1 = wait_for_info_matching(port, &token, Duration::from_secs(10), |info| {
        info["commit"].as_str() == Some(h1)
    });
    assert_eq!(
        info_v1["selfUpdate"]["rollbackAvailable"].as_bool(),
        Some(false)
    );
}

#[test]
fn http_idle_self_update_execv_preserves_pid_token_tabs_and_rollback() {
    let mut iso = IsolatedEnv::new("agentsterm-su-execv");
    let port = free_port();
    let h1 = "1111111111111111111111111111111111111111";
    let h2 = "2222222222222222222222222222222222222222";
    write_exec_bytes(
        &iso.installed_bin(),
        &make_wrapper_bytes(&real_bin_path(), h1),
    );

    let mock = MockReleaseServer::start(make_wrapper_bytes(&real_bin_path(), h2));
    let token = spawn_installed_daemon(&mut iso, port, &mock.base_url(), &[]);

    // Auth enforcement on new endpoints.
    assert_eq!(
        http_call(port, "GET", "/api/daemon/update", None, None)
            .unwrap()
            .0,
        401
    );
    assert_eq!(
        http_call(
            port,
            "POST",
            "/api/daemon/update",
            Some("wrong"),
            Some(json!({}))
        )
        .unwrap()
        .0,
        401
    );
    assert_eq!(
        http_call(port, "DELETE", "/api/daemon/update", None, None)
            .unwrap()
            .0,
        401
    );
    assert_eq!(
        http_call(port, "POST", "/api/daemon/rollback", None, Some(json!({})))
            .unwrap()
            .0,
        401
    );

    // HTTP body must NEVER accept a custom release URL.
    let (bad_url_code, bad_url_body) = http_call(
        port,
        "POST",
        "/api/daemon/update",
        Some(&token),
        Some(json!({ "version": "latest", "releaseBaseUrl": "https://evil.example" })),
    )
    .unwrap();
    assert_eq!(bad_url_code, 400);
    assert_eq!(
        bad_url_body["code"].as_str(),
        Some("custom_url_not_allowed")
    );

    // No .prev yet -> rollback returns 409 (not 403 or "Unknown endpoint").
    let (no_rb_code, no_rb_body) = http_call(
        port,
        "POST",
        "/api/daemon/rollback",
        Some(&token),
        Some(json!({})),
    )
    .unwrap();
    assert_eq!(no_rb_code, 409);
    assert_eq!(no_rb_body["code"].as_str(), Some("no_rollback_available"));

    // Create a shell tab and run a command to verify persistence across in-place execv().
    let (ct_code, created) = http_call(
        port,
        "POST",
        "/api/tabs",
        Some(&token),
        Some(json!({ "agent": "shell", "title": "Update Persist", "cwd": iso.root.to_string_lossy() })),
    )
    .unwrap();
    assert_eq!(ct_code, 200);
    let tab_id = created["tab"]["id"].as_str().unwrap().to_string();
    assert_eq!(
        http_call(
            port,
            "POST",
            &format!("/api/tabs/{tab_id}/messages"),
            Some(&token),
            Some(json!({ "text": "echo SURVIVED_EXECV" })),
        )
        .unwrap()
        .0,
        200
    );

    // Wait for shell turn to finish.
    let deadline = Instant::now() + Duration::from_secs(10);
    while Instant::now() < deadline {
        let (st, resp) = http_call(
            port,
            "GET",
            &format!("/api/tabs/{tab_id}/messages"),
            Some(&token),
            None,
        )
        .unwrap();
        if st == 200
            && resp["tab"]["status"].as_str() == Some("idle")
            && resp["messages"].as_array().map(|a| a.len()).unwrap_or(0) >= 2
        {
            break;
        }
        thread::sleep(Duration::from_millis(100));
    }

    let pid_before = iso.lock_pid().expect("daemon.lock pid before update");

    // Trigger in-place self-update while idle.
    let (upd_code, upd_body) = http_call(
        port,
        "POST",
        "/api/daemon/update",
        Some(&token),
        Some(json!({ "version": "v0.3.2-2222222", "expectedHash": h2 })),
    )
    .unwrap();
    assert_eq!(upd_code, 202);
    assert_eq!(upd_body["state"].as_str(), Some("checking"));

    // Wait for v2 to come up via in-place execv().
    let info_v2 = wait_for_info_matching(port, &token, Duration::from_secs(15), |info| {
        info["commit"].as_str() == Some(h2)
    });
    assert_eq!(
        info_v2["selfUpdate"]["rollbackAvailable"].as_bool(),
        Some(true)
    );
    assert_eq!(
        info_v2["selfUpdate"]["previousBuildHash"].as_str(),
        Some(h1)
    );
    assert!(info_v2["selfUpdate"]["lastError"].is_null());
    assert!(
        !iso.app_home.join("update-pending.json").exists(),
        "update-pending.json should be cleared once v2 is healthy"
    );

    let pid_after_update = iso.lock_pid().expect("daemon.lock pid after update");
    assert_eq!(
        pid_before, pid_after_update,
        "in-place execv() must preserve daemon PID"
    );

    // Verify tab + message survived.
    let (m_code, msgs) = http_call(
        port,
        "GET",
        &format!("/api/tabs/{tab_id}/messages"),
        Some(&token),
        None,
    )
    .unwrap();
    assert_eq!(m_code, 200);
    assert!(msgs["messages"]
        .as_array()
        .unwrap()
        .iter()
        .any(|m| m["text"].as_str().unwrap_or("").contains("SURVIVED_EXECV")));

    // Trigger HTTP rollback back to v1.
    let (rb_code, rb_body) = http_call(
        port,
        "POST",
        "/api/daemon/rollback",
        Some(&token),
        Some(json!({})),
    )
    .unwrap();
    assert_eq!(rb_code, 202);
    assert_eq!(rb_body["state"].as_str(), Some("restarting"));

    let info_v1 = wait_for_info_matching(port, &token, Duration::from_secs(15), |info| {
        info["commit"].as_str() == Some(h1)
    });
    assert_eq!(
        info_v1["selfUpdate"]["rollbackAvailable"].as_bool(),
        Some(false)
    );
    assert_eq!(
        pid_before,
        iso.lock_pid().expect("daemon.lock pid after rollback"),
        "rollback execv() must also preserve daemon PID"
    );
}

#[test]
fn http_active_turn_defer_cancel_and_auto_restart_on_idle() {
    let mut iso = IsolatedEnv::new("agentsterm-su-defer");
    let port = free_port();
    let h1 = "1111111111111111111111111111111111111111";
    let h2 = "2222222222222222222222222222222222222222";
    write_exec_bytes(
        &iso.installed_bin(),
        &make_wrapper_bytes(&real_bin_path(), h1),
    );

    let mock = MockReleaseServer::start(make_wrapper_bytes(&real_bin_path(), h2));
    let token = spawn_installed_daemon(&mut iso, port, &mock.base_url(), &[]);

    let (ct_code, created) = http_call(
        port,
        "POST",
        "/api/tabs",
        Some(&token),
        Some(json!({ "agent": "shell", "title": "Busy Shell", "cwd": iso.root.to_string_lossy() })),
    )
    .unwrap();
    assert_eq!(ct_code, 200);
    let tab_id = created["tab"]["id"].as_str().unwrap().to_string();

    // Start a long-running shell command that signals when started and waits for a gate file.
    let started_file = iso.root.join("turn1.started");
    let gate_file = iso.root.join("turn1.release");
    let cmd1 = format!(
        "touch '{}'; while [ ! -f '{}' ]; do sleep 0.1; done; echo DEFERRED_TURN_DONE",
        started_file.display(),
        gate_file.display()
    );
    assert_eq!(
        http_call(
            port,
            "POST",
            &format!("/api/tabs/{tab_id}/messages"),
            Some(&token),
            Some(json!({ "text": cmd1 })),
        )
        .unwrap()
        .0,
        200
    );

    let deadline = Instant::now() + Duration::from_secs(10);
    while Instant::now() < deadline && !started_file.exists() {
        thread::sleep(Duration::from_millis(50));
    }
    assert!(started_file.exists(), "shell turn did not start");

    // 1. whenBusy: "reject" must return 409 busy.
    let (rej_code, rej_body) = http_call(
        port,
        "POST",
        "/api/daemon/update",
        Some(&token),
        Some(json!({ "version": "v0.3.2-2222222", "whenBusy": "reject" })),
    )
    .unwrap();
    assert_eq!(rej_code, 409);
    assert_eq!(rej_body["code"].as_str(), Some("busy"));
    assert_eq!(rej_body["activeTurnsCount"].as_u64(), Some(1));

    // 2. whenBusy: "defer" stages binary and enters pending_restart without restarting yet.
    let (def_code, _) = http_call(
        port,
        "POST",
        "/api/daemon/update",
        Some(&token),
        Some(json!({ "version": "v0.3.2-2222222", "expectedHash": h2, "whenBusy": "defer" })),
    )
    .unwrap();
    assert_eq!(def_code, 202);

    let wait_pending = |tok: &str| {
        let dl = Instant::now() + Duration::from_secs(10);
        while Instant::now() < dl {
            let (code, st) = http_call(port, "GET", "/api/daemon/update", Some(tok), None).unwrap();
            assert_eq!(code, 200);
            if st["state"].as_str() == Some("pending_restart") {
                return st;
            }
            thread::sleep(Duration::from_millis(80));
        }
        panic!("Timed out waiting for pending_restart");
    };

    let pending_st = wait_pending(&token);
    assert_eq!(
        pending_st["activeTabIds"].as_array().unwrap(),
        &vec![json!(tab_id)]
    );
    assert!(iso.staged_bin().is_file());

    // 3. DELETE /api/daemon/update cancels pending_restart and removes staged file.
    let (del_code, del_body) =
        http_call(port, "DELETE", "/api/daemon/update", Some(&token), None).unwrap();
    assert_eq!(del_code, 200);
    assert_eq!(del_body["state"].as_str(), Some("idle"));
    assert!(!iso.staged_bin().exists());

    // 4. Re-request deferred update while turn is still active, then release the turn so
    //    `on_runner_idle` automatically applies the staged binary!
    let (def2_code, _) = http_call(
        port,
        "POST",
        "/api/daemon/update",
        Some(&token),
        Some(json!({ "version": "v0.3.2-2222222", "expectedHash": h2, "whenBusy": "defer" })),
    )
    .unwrap();
    assert_eq!(def2_code, 202);
    let _ = wait_pending(&token);

    // Release the turn!
    fs::write(&gate_file, b"go").unwrap();

    // Daemon must automatically swap & execv to v2 once the turn completes.
    let info_v2 = wait_for_info_matching(port, &token, Duration::from_secs(15), |info| {
        info["commit"].as_str() == Some(h2)
    });
    assert_eq!(info_v2["commit"].as_str(), Some(h2));

    let (m_code, msgs) = http_call(
        port,
        "GET",
        &format!("/api/tabs/{tab_id}/messages"),
        Some(&token),
        None,
    )
    .unwrap();
    assert_eq!(m_code, 200);
    assert!(msgs["messages"]
        .as_array()
        .unwrap()
        .iter()
        .any(|m| m["text"]
            .as_str()
            .unwrap_or("")
            .contains("DEFERRED_TURN_DONE")));
}

#[test]
fn broken_binary_triggers_auto_rollback_watchdog_and_reports_last_error() {
    let mut iso = IsolatedEnv::new("agentsterm-su-watchdog");
    let port = free_port();
    let h1 = "1111111111111111111111111111111111111111";
    let h_bad = "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef";
    write_exec_bytes(
        &iso.installed_bin(),
        &make_wrapper_bytes(&real_bin_path(), h1),
    );

    let mock = MockReleaseServer::start(make_broken_serve_bytes(h_bad));
    let token = spawn_installed_daemon(
        &mut iso,
        port,
        &mock.base_url(),
        &[("AGENTSTERM_ROLLBACK_WATCH_TIMEOUT_SECS", "2")],
    );

    let (upd_code, _) = http_call(
        port,
        "POST",
        "/api/daemon/update",
        Some(&token),
        Some(json!({ "version": "v0.3.3-deadbee", "expectedHash": h_bad })),
    )
    .unwrap();
    assert_eq!(upd_code, 202);

    // The broken binary exits immediately on `serve`; the `.prev` watchdog detects the timeout
    // after 2s, restores v1, restarts it, and writes update-last-error.json.
    let recovered = wait_for_info_matching(port, &token, Duration::from_secs(15), |info| {
        info["commit"].as_str() == Some(h1) && info["selfUpdate"]["lastError"].is_string()
    });
    let last_err = recovered["selfUpdate"]["lastError"].as_str().unwrap();
    assert!(
        last_err.contains("automatically rolled back"),
        "unexpected lastError: {last_err}"
    );
    assert!(!iso.app_home.join("update-pending.json").exists());
}

#[test]
fn disabled_self_update_returns_409_never_403() {
    let mut iso = IsolatedEnv::new("agentsterm-su-disabled");
    let port = free_port();
    let h1 = "1111111111111111111111111111111111111111";
    write_exec_bytes(
        &iso.installed_bin(),
        &make_wrapper_bytes(&real_bin_path(), h1),
    );

    let mock = MockReleaseServer::start(make_wrapper_bytes(&real_bin_path(), h1));
    let token = spawn_installed_daemon(
        &mut iso,
        port,
        &mock.base_url(),
        &[("AGENTSTERM_DISABLE_SELF_UPDATE", "1")],
    );

    let (info_code, info) = http_call(port, "GET", "/api/info", Some(&token), None).unwrap();
    assert_eq!(info_code, 200);
    assert_eq!(info["selfUpdate"]["supported"].as_bool(), Some(false));

    let (upd_code, upd_body) = http_call(
        port,
        "POST",
        "/api/daemon/update",
        Some(&token),
        Some(json!({ "version": "latest" })),
    )
    .unwrap();
    assert_eq!(upd_code, 409);
    assert_eq!(upd_body["code"].as_str(), Some("self_update_disabled"));
}

#[test]
fn pending_restart_with_force_promotes_staged_immediately() {
    let mut iso = IsolatedEnv::new("agentsterm-su-force-promote");
    let port = free_port();
    let h1 = "1111111111111111111111111111111111111111";
    let h2 = "2222222222222222222222222222222222222222";
    write_exec_bytes(
        &iso.installed_bin(),
        &make_wrapper_bytes(&real_bin_path(), h1),
    );

    let mock = MockReleaseServer::start(make_wrapper_bytes(&real_bin_path(), h2));
    let token = spawn_installed_daemon(&mut iso, port, &mock.base_url(), &[]);

    let (ct_code, created) = http_call(
        port,
        "POST",
        "/api/tabs",
        Some(&token),
        Some(json!({ "agent": "shell", "title": "Force Promote", "cwd": iso.root.to_string_lossy() })),
    )
    .unwrap();
    assert_eq!(ct_code, 200);
    let tab_id = created["tab"]["id"].as_str().unwrap().to_string();

    let started_file = iso.root.join("force.started");
    let cmd = format!("touch '{}'; sleep 30", started_file.display());
    assert_eq!(
        http_call(
            port,
            "POST",
            &format!("/api/tabs/{tab_id}/messages"),
            Some(&token),
            Some(json!({ "text": cmd })),
        )
        .unwrap()
        .0,
        200
    );
    let deadline = Instant::now() + Duration::from_secs(10);
    while Instant::now() < deadline && !started_file.exists() {
        thread::sleep(Duration::from_millis(50));
    }
    assert!(started_file.exists());

    // Defer update while busy.
    assert_eq!(
        http_call(
            port,
            "POST",
            "/api/daemon/update",
            Some(&token),
            Some(json!({ "version": "v0.3.2-2222222", "expectedHash": h2, "whenBusy": "defer" })),
        )
        .unwrap()
        .0,
        202
    );
    let dl = Instant::now() + Duration::from_secs(10);
    while Instant::now() < dl {
        let (_, st) = http_call(port, "GET", "/api/daemon/update", Some(&token), None).unwrap();
        if st["state"].as_str() == Some("pending_restart") {
            break;
        }
        thread::sleep(Duration::from_millis(80));
    }

    // Break the mock server checksum to prove `whenBusy: "force"` promotes the ALREADY-STAGED binary
    // without re-downloading!
    mock.set_sha256_raw("corrupt\n".to_string());

    let (force_code, force_body) = http_call(
        port,
        "POST",
        "/api/daemon/update",
        Some(&token),
        Some(json!({ "version": "v0.3.2-2222222", "whenBusy": "force" })),
    )
    .unwrap();
    assert_eq!(force_code, 202);
    assert_eq!(force_body["state"].as_str(), Some("restarting"));

    let info_v2 = wait_for_info_matching(port, &token, Duration::from_secs(15), |info| {
        info["commit"].as_str() == Some(h2)
    });
    assert_eq!(info_v2["commit"].as_str(), Some(h2));
}

#[test]
fn startup_crash_loop_guard_restores_prev_and_execs_it() {
    let mut iso = IsolatedEnv::new("agentsterm-su-crashloop");
    let port = free_port();
    let h1 = "1111111111111111111111111111111111111111";
    let h2 = "2222222222222222222222222222222222222222";
    write_exec_bytes(&iso.prev_bin(), &make_wrapper_bytes(&real_bin_path(), h1));
    write_exec_bytes(
        &iso.installed_bin(),
        &make_wrapper_bytes(&real_bin_path(), h2),
    );
    fs::write(
        iso.app_home.join("update-prev.json"),
        json!({ "buildHash": h1, "version": "0.3.0", "savedAt": 1 }).to_string(),
    )
    .unwrap();
    // Pre-seed update-pending.json with attempts=2 to simulate crash-looping v2.
    fs::write(
        iso.app_home.join("update-pending.json"),
        json!({ "targetHash": h2, "previousHash": h1, "startedAt": 1, "attempts": 2 }).to_string(),
    )
    .unwrap();

    let token = spawn_installed_daemon(&mut iso, port, "http://127.0.0.1:1", &[]);
    let info = wait_for_info_matching(port, &token, Duration::from_secs(10), |info| {
        info["commit"].as_str() == Some(h1)
    });
    assert_eq!(info["commit"].as_str(), Some(h1));
    assert!(
        info["selfUpdate"]["lastError"]
            .as_str()
            .unwrap_or("")
            .contains("crash-looped"),
        "expected crash-loop lastError, got: {}",
        info["selfUpdate"]
    );
    assert!(!iso.app_home.join("update-pending.json").exists());
}
