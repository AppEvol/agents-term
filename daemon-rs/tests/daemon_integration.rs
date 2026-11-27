use serde_json::{json, Value};
use std::fs;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::os::unix::fs::{MetadataExt, PermissionsExt};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::thread;
use std::time::{Duration, Instant};

fn bin_path() -> PathBuf {
    PathBuf::from(env!("CARGO_BIN_EXE_agentsterm-daemon"))
}

fn free_port() -> u16 {
    TcpListener::bind("127.0.0.1:0")
        .unwrap()
        .local_addr()
        .unwrap()
        .port()
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

fn write_exec(path: &Path, body: &str) {
    fs::write(path, body).unwrap();
    fs::set_permissions(path, fs::Permissions::from_mode(0o755)).unwrap();
}

fn seed_split_workspace(
    app_home: &Path,
    active_tab_id: Option<&str>,
    open_tabs: &[Value],
    closed_tabs: &[Value],
    messages_by_tab: &serde_json::Map<String, Value>,
) {
    let tabs_root = app_home.join("agents_tabs");
    fs::create_dir_all(&tabs_root).unwrap();
    fs::write(
        tabs_root.join("workspace.json"),
        json!({ "schemaVersion": 1, "activeTabId": active_tab_id }).to_string(),
    )
    .unwrap();
    let mut lines = String::new();
    for tab in open_tabs {
        let mut t = tab.clone();
        if let Some(obj) = t.as_object_mut() {
            obj.insert("state".into(), json!("open"));
        }
        lines.push_str(&t.to_string());
        lines.push('\n');
    }
    for tab in closed_tabs {
        let mut t = tab.clone();
        if let Some(obj) = t.as_object_mut() {
            obj.insert("state".into(), json!("closed"));
        }
        lines.push_str(&t.to_string());
        lines.push('\n');
    }
    fs::write(tabs_root.join("tabs.jsonl"), lines).unwrap();
    for (tab_id, msgs) in messages_by_tab {
        let tdir = tabs_root.join(tab_id);
        fs::create_dir_all(&tdir).unwrap();
        fs::write(tdir.join("messages.json"), msgs.to_string()).unwrap();
    }
}

struct TestDaemon {
    child: Child,
    port: u16,
    token: String,
}

impl TestDaemon {
    fn start(home: &Path, extra_bin: Option<&Path>) -> Self {
        Self::start_with_app_home(home, None, extra_bin)
    }

    fn start_with_app_home(
        home: &Path,
        app_home_override: Option<&Path>,
        extra_bin: Option<&Path>,
    ) -> Self {
        let port = free_port();
        let mut cmd = Command::new(bin_path());
        cmd.args(["serve", "--host", "127.0.0.1", "--port", &port.to_string()])
            .env("HOME", home)
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());
        if let Some(app_home) = app_home_override {
            cmd.env("AGENTSTERM_HOME", app_home);
        } else {
            cmd.env_remove("AGENTSTERM_HOME");
        }
        if let Some(bin) = extra_bin {
            let cur_path = std::env::var("PATH").unwrap_or_default();
            cmd.env("PATH", format!("{}:{cur_path}", bin.display()));
        }
        let mut child = cmd.spawn().expect("spawn daemon");
        let stdout = child.stdout.take().unwrap();
        let mut reader = BufReader::new(stdout);
        let mut line = String::new();
        reader.read_line(&mut line).unwrap();
        assert!(
            line.contains("listening on http://127.0.0.1"),
            "Unexpected startup line: {line}"
        );
        // ISO-8601 UTC timestamp prefix (#61)
        assert!(
            line.contains("T") && line.contains("Z [INFO]"),
            "Missing timestamp: {line}"
        );

        let state_root = app_home_override
            .map(Path::to_path_buf)
            .unwrap_or_else(|| home.join(".agents-term"));
        let cfg: Value =
            serde_json::from_str(&fs::read_to_string(state_root.join("config.json")).unwrap())
                .unwrap();
        let token = cfg["token"].as_str().unwrap().to_string();

        // Drain stdout in background so pipe never blocks.
        thread::spawn(move || {
            let mut sink = String::new();
            let _ = reader.read_to_string(&mut sink);
        });

        Self { child, port, token }
    }

    fn api(&self, method: &str, path: &str, body: Option<Value>) -> (u16, Value) {
        let mut stream = TcpStream::connect(format!("127.0.0.1:{}", self.port)).unwrap();
        stream
            .set_read_timeout(Some(Duration::from_secs(15)))
            .unwrap();
        let payload = body.map(|b| b.to_string());
        let mut req = format!(
            "{method} {path} HTTP/1.1\r\nHost: 127.0.0.1:{}\r\nAuthorization: Bearer {}\r\nConnection: close\r\n",
            self.port, self.token
        );
        if let Some(ref p) = payload {
            req.push_str("Content-Type: application/json\r\n");
            req.push_str(&format!("Content-Length: {}\r\n", p.len()));
        }
        req.push_str("\r\n");
        stream.write_all(req.as_bytes()).unwrap();
        if let Some(ref p) = payload {
            stream.write_all(p.as_bytes()).unwrap();
        }
        let mut raw = String::new();
        stream.read_to_string(&mut raw).unwrap();
        let (head, body_str) = raw.split_once("\r\n\r\n").unwrap();
        let status: u16 = head
            .lines()
            .next()
            .unwrap()
            .split_whitespace()
            .nth(1)
            .unwrap()
            .parse()
            .unwrap();
        let parsed = serde_json::from_str(body_str).unwrap_or(Value::Null);
        (status, parsed)
    }

    /// Sends a raw request body and returns the raw response body, for image up/downloads.
    fn api_raw(
        &self,
        method: &str,
        path: &str,
        token: Option<&str>,
        body: Option<&[u8]>,
    ) -> (u16, String, Vec<u8>) {
        let mut stream = TcpStream::connect(format!("127.0.0.1:{}", self.port)).unwrap();
        stream
            .set_read_timeout(Some(Duration::from_secs(15)))
            .unwrap();
        let mut req = format!(
            "{method} {path} HTTP/1.1\r\nHost: 127.0.0.1:{}\r\nConnection: close\r\n",
            self.port
        );
        if let Some(token) = token {
            req.push_str(&format!("Authorization: Bearer {token}\r\n"));
        }
        if let Some(bytes) = body {
            req.push_str("Content-Type: application/octet-stream\r\n");
            req.push_str(&format!("Content-Length: {}\r\n", bytes.len()));
        }
        req.push_str("\r\n");
        stream.write_all(req.as_bytes()).unwrap();
        if let Some(bytes) = body {
            // The daemon may answer and close before reading an oversized body to the end.
            let _ = stream.write_all(bytes);
        }
        let mut raw = Vec::new();
        let _ = stream.read_to_end(&mut raw);
        let split = raw
            .windows(4)
            .position(|w| w == b"\r\n\r\n")
            .expect("HTTP response head");
        let head = String::from_utf8_lossy(&raw[..split]).to_string();
        let status: u16 = head
            .lines()
            .next()
            .unwrap()
            .split_whitespace()
            .nth(1)
            .unwrap()
            .parse()
            .unwrap();
        (status, head.to_ascii_lowercase(), raw[split + 4..].to_vec())
    }

    fn upload_image(&self, tab_id: &str, bytes: &[u8]) -> (u16, Value) {
        let (status, _, body) = self.api_raw(
            "POST",
            &format!("/api/tabs/{tab_id}/attachments"),
            Some(&self.token),
            Some(bytes),
        );
        (status, serde_json::from_slice(&body).unwrap_or(Value::Null))
    }

    fn create_tab(&self, agent: &str, cwd: &Path) -> String {
        let (st, created) = self.api(
            "POST",
            "/api/tabs",
            Some(json!({ "agent": agent, "cwd": cwd.to_string_lossy() })),
        );
        assert_eq!(st, 200, "{created}");
        created["tab"]["id"].as_str().unwrap().to_string()
    }

    fn wait_settled(&self, tab_id: &str, expected_msg_count: usize) -> Value {
        let deadline = Instant::now() + Duration::from_secs(10);
        loop {
            let (_, resp) = self.api("GET", &format!("/api/tabs/{tab_id}/messages"), None);
            let count = resp["messages"].as_array().map(Vec::len).unwrap_or(0);
            let status = resp["tab"]["status"].as_str().unwrap_or("").to_string();
            if count >= expected_msg_count && status != "working" {
                return resp;
            }
            assert!(
                Instant::now() < deadline,
                "Tab did not settle: status={status} msgs={count}"
            );
            thread::sleep(Duration::from_millis(50));
        }
    }

    fn kill_sigkill(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

impl Drop for TestDaemon {
    fn drop(&mut self) {
        unsafe {
            let _ = libc::kill(self.child.id() as libc::pid_t, libc::SIGTERM);
        }
        let deadline = Instant::now() + Duration::from_secs(3);
        while Instant::now() < deadline {
            if let Ok(Some(_)) = self.child.try_wait() {
                return;
            }
            thread::sleep(Duration::from_millis(50));
        }
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

#[test]
fn open_tab_limit_preserves_existing_tabs_and_allows_one_create_in_the_last_slot() {
    let home = make_temp_dir("agentsterm-tab-limit");
    let app_home = home.join(".agents-term");
    let tabs: Vec<_> = (1..=11)
        .map(|id| json!({ "id": format!("old_{id}"), "agent": "claude", "status": "done" }))
        .collect();
    let history = json!([{ "id": "msg_1", "role": "user", "text": "Saved conversation" }]);
    let mut messages_by_tab = serde_json::Map::new();
    messages_by_tab.insert("old_11".to_string(), history.clone());
    seed_split_workspace(&app_home, Some("old_11"), &tabs, &[], &messages_by_tab);

    let daemon = TestDaemon::start(&home, None);
    let create = || daemon.api("POST", "/api/tabs", Some(json!({ "agent": "shell" })));
    for (count, close_id) in [(11, "old_1"), (10, "old_2")] {
        let (_, listing) = daemon.api("GET", "/api/tabs", None);
        assert_eq!(listing["tabs"].as_array().unwrap().len(), count);
        assert_eq!(listing["activeTabId"], "old_11");
        let (status, body) = create();
        assert_eq!(status, 409);
        assert!(body["error"].as_str().unwrap().contains("10 open tabs"));
        assert_eq!(daemon.api("GET", "/api/tabs", None).1, listing);
        assert_eq!(
            daemon
                .api("DELETE", &format!("/api/tabs/{close_id}"), None)
                .0,
            200
        );
    }

    let mut statuses = thread::scope(|scope| {
        let other = scope.spawn(create);
        [create().0, other.join().unwrap().0]
    });
    statuses.sort();
    assert_eq!(statuses, [200, 409]);
    let (_, listing) = daemon.api("GET", "/api/tabs", None);
    assert_eq!(listing["tabs"].as_array().unwrap().len(), 10);
    let (_, transcript) = daemon.api("GET", "/api/tabs/old_11/messages", None);
    assert_eq!(transcript["messages"], history);
    drop(daemon);
    fs::remove_dir_all(home).unwrap();
}

/// Regression test for Issue #52: interrupting a Shell command kills its child process group
/// and never allows `finished` to be created after the tab reports interrupted/idle.
#[test]
fn issue_52_interrupt_kills_shell_child_process_group() {
    let home = make_temp_dir("agentsterm-rs-52");
    let work = home.join("scratch");
    fs::create_dir_all(&work).unwrap();
    let daemon = TestDaemon::start(&home, None);

    let (st, created) = daemon.api(
        "POST",
        "/api/tabs",
        Some(json!({ "agent": "shell", "cwd": work.to_string_lossy() })),
    );
    assert_eq!(st, 200);
    let tab_id = created["tab"]["id"].as_str().unwrap().to_string();

    let (st, _) = daemon.api(
        "POST",
        &format!("/api/tabs/{tab_id}/messages"),
        Some(json!({ "text": "sh -c 'touch started && sleep 3 && touch finished'" })),
    );
    assert_eq!(st, 200);

    let started = work.join("started");
    let deadline = Instant::now() + Duration::from_secs(5);
    while !started.exists() && Instant::now() < deadline {
        thread::sleep(Duration::from_millis(50));
    }
    assert!(started.exists(), "started marker never appeared");

    let (st, _) = daemon.api(
        "POST",
        &format!("/api/tabs/{tab_id}/interrupt"),
        Some(json!({})),
    );
    assert_eq!(st, 200);

    // Wait until tab returns to idle (which must happen only after process exits).
    let idle_deadline = Instant::now() + Duration::from_secs(6);
    loop {
        let (_, resp) = daemon.api("GET", &format!("/api/tabs/{tab_id}/messages"), None);
        let tab_status = resp["tab"]["status"].as_str().unwrap_or("");
        let msg_status = resp["messages"]
            .as_array()
            .and_then(|a| a.last())
            .and_then(|m| m["status"].as_str())
            .unwrap_or("");
        if tab_status == "idle" && msg_status == "interrupted" {
            break;
        }
        assert!(
            Instant::now() < idle_deadline,
            "Tab did not settle to idle/interrupted: tab={tab_status} msg={msg_status}"
        );
        thread::sleep(Duration::from_millis(50));
    }

    // Sleep past the original 3-second child delay and confirm `finished` was never created.
    thread::sleep(Duration::from_millis(3200));
    assert!(
        !work.join("finished").exists(),
        "Orphaned child process created 'finished' after interrupt (#52 regression)"
    );
    let _ = fs::remove_dir_all(&home);
}

/// Regression tests for Issues #61 and #62:
/// - #62: `sessionRef` is persisted immediately mid-turn on first discovery so a mid-turn kill
///   does not lose conversation context.
/// - #61: On restart, any recovered `streaming` message is marked `interrupted` with an explicit
///   user-visible `error` reason.
#[test]
fn issues_61_and_62_mid_turn_session_ref_and_restart_interruption_reason() {
    let home = make_temp_dir("agentsterm-rs-61-62");
    let fake_bin = home.join("fake-bin");
    fs::create_dir_all(&fake_bin).unwrap();
    write_exec(
        &fake_bin.join("claude"),
        r#"#!/usr/bin/env sh
printf '{"type":"system","subtype":"init","session_id":"sess-midturn-62"}\n'
printf '{"type":"stream_event","session_id":"sess-midturn-62","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Working..."}}}\n'
sleep 10
"#,
    );

    let mut daemon1 = TestDaemon::start(&home, Some(&fake_bin));
    let (st, created) = daemon1.api(
        "POST",
        "/api/tabs",
        Some(json!({ "agent": "claude", "cwd": home.to_string_lossy() })),
    );
    assert_eq!(st, 200);
    let tab_id = created["tab"]["id"].as_str().unwrap().to_string();

    let (st, _) = daemon1.api(
        "POST",
        &format!("/api/tabs/{tab_id}/messages"),
        Some(json!({ "text": "Start long task" })),
    );
    assert_eq!(st, 200);

    // Wait until sessionRef appears on disk in tabs.jsonl WHILE the turn is still streaming (#62).
    let tabs_index = home.join(".agents-term/agents_tabs/tabs.jsonl");
    let deadline = Instant::now() + Duration::from_secs(5);
    let mut found_on_disk = false;
    while Instant::now() < deadline {
        if let Ok(raw) = fs::read_to_string(&tabs_index) {
            for line in raw.lines() {
                if let Ok(parsed) = serde_json::from_str::<Value>(line) {
                    if parsed["id"].as_str() == Some(tab_id.as_str())
                        && parsed["state"].as_str() == Some("open")
                        && parsed["sessionRef"]["value"].as_str() == Some("sess-midturn-62")
                    {
                        found_on_disk = true;
                        break;
                    }
                }
            }
            if found_on_disk {
                break;
            }
        }
        thread::sleep(Duration::from_millis(50));
    }
    assert!(
        found_on_disk,
        "sessionRef was not persisted to agents_tabs/tabs.jsonl mid-turn (#62)"
    );

    // Hard-kill daemon1 mid-turn and start daemon2 on the same HOME.
    daemon1.kill_sigkill();
    drop(daemon1);

    let daemon2 = TestDaemon::start(&home, Some(&fake_bin));
    let (st, resp) = daemon2.api("GET", &format!("/api/tabs/{tab_id}/messages"), None);
    assert_eq!(st, 200);
    assert_eq!(resp["tab"]["status"].as_str(), Some("idle"));
    assert_eq!(
        resp["tab"]["sessionRef"]["value"].as_str(),
        Some("sess-midturn-62"),
        "sessionRef should survive mid-turn crash (#62)"
    );
    let last_msg = resp["messages"].as_array().unwrap().last().unwrap();
    assert_eq!(last_msg["status"].as_str(), Some("interrupted"));
    let err_reason = last_msg["error"].as_str().unwrap_or("");
    assert!(
        err_reason.contains("daemon restarted"),
        "Expected explicit restart interruption reason (#61), got: {err_reason:?}"
    );

    let _ = fs::remove_dir_all(&home);
}

#[test]
fn recently_closed_tab_can_be_revived_across_restart_with_history_and_session_ref() {
    let home = make_temp_dir("agentsterm-rs-revive");
    let app_home = home.join(".agents-term");
    let history = json!([
        { "id": "m1", "tabId": "tab_save", "role": "user", "text": "Fix login bug", "status": "complete" },
        { "id": "m2", "tabId": "tab_save", "role": "assistant", "text": "Fixed login bug.", "status": "complete" }
    ]);
    let open_tabs = vec![json!({
        "id": "tab_save",
        "title": "Claude · repo",
        "agent": "claude",
        "cwd": home.to_string_lossy(),
        "permissionMode": "full-access",
        "model": "sonnet",
        "systemPrompt": "Keep it short",
        "status": "idle",
        "sessionRef": { "kind": "claude", "value": "sess-revive-1" },
        "taskSummary": "Fix login bug",
        "lastMessagePreview": "Fixed login bug.",
        "queuedMessages": [],
        "createdAt": 1000,
        "updatedAt": 2000
    })];
    let mut messages_by_tab = serde_json::Map::new();
    messages_by_tab.insert("tab_save".to_string(), history.clone());
    seed_split_workspace(
        &app_home,
        Some("tab_save"),
        &open_tabs,
        &[],
        &messages_by_tab,
    );

    let daemon1 = TestDaemon::start(&home, None);
    let (st, del_body) = daemon1.api("DELETE", "/api/tabs/tab_save", None);
    assert_eq!(st, 200);
    assert_eq!(del_body["closedTab"]["id"].as_str(), Some("tab_save"));
    assert!(del_body["closedTab"]["closedAt"].as_i64().unwrap_or(0) > 0);
    assert_eq!(del_body["closedTabs"].as_array().unwrap().len(), 1);

    let (st, listing) = daemon1.api("GET", "/api/tabs", None);
    assert_eq!(st, 200);
    assert!(listing["tabs"].as_array().unwrap().is_empty());
    assert_eq!(listing["closedTabs"].as_array().unwrap().len(), 1);
    drop(daemon1);

    // Restart daemon and confirm the recently closed tab + history survived on disk.
    let daemon2 = TestDaemon::start(&home, None);
    let (st, closed_resp) = daemon2.api("GET", "/api/tabs/closed", None);
    assert_eq!(st, 200);
    assert_eq!(closed_resp["closedTabs"].as_array().unwrap().len(), 1);
    assert_eq!(
        closed_resp["closedTabs"][0]["id"].as_str(),
        Some("tab_save")
    );

    let (st, revived) = daemon2.api("POST", "/api/tabs/closed/tab_save/revive", Some(json!({})));
    assert_eq!(st, 200);
    assert_eq!(revived["tab"]["id"].as_str(), Some("tab_save"));
    assert!(revived["tab"].get("closedAt").is_none());
    assert_eq!(
        revived["tab"]["sessionRef"]["value"].as_str(),
        Some("sess-revive-1")
    );
    assert_eq!(revived["tab"]["model"].as_str(), Some("sonnet"));
    assert_eq!(revived["messages"], history);
    assert!(revived["closedTabs"].as_array().unwrap().is_empty());

    let (st, transcript) = daemon2.api("GET", "/api/tabs/tab_save/messages", None);
    assert_eq!(st, 200);
    assert_eq!(transcript["messages"], history);

    drop(daemon2);
    let _ = fs::remove_dir_all(&home);
}

#[test]
fn closed_tab_retention_discards_after_one_day_and_beyond_last_50() {
    let home = make_temp_dir("agentsterm-rs-retention");
    let app_home = home.join(".agents-term");

    let now_ms = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_millis() as i64;
    let one_day_ms: i64 = 24 * 60 * 60 * 1000;

    let mut closed_tabs = Vec::new();
    let mut messages_by_tab = serde_json::Map::new();

    // Two expired tabs: 25 hours old and exactly 24 hours old.
    for (id, age_ms) in [
        ("expired_25h", one_day_ms + 3_600_000),
        ("expired_24h", one_day_ms),
    ] {
        closed_tabs.push(json!({
            "id": id,
            "title": id,
            "agent": "claude",
            "cwd": home.to_string_lossy(),
            "status": "idle",
            "closedAt": now_ms - age_ms,
        }));
        messages_by_tab.insert(
            id.to_string(),
            json!([{ "id": "m", "tabId": id, "role": "user", "text": "old" }]),
        );
    }

    // 55 recent closed tabs (closed 1..=55 minutes ago): only the 50 most recent survive.
    for i in 1..=55i64 {
        let id = format!("recent_{i:02}");
        closed_tabs.push(json!({
            "id": id,
            "title": format!("Recent {i}"),
            "agent": "agy",
            "cwd": home.to_string_lossy(),
            "status": "idle",
            "closedAt": now_ms - i * 60_000,
        }));
        messages_by_tab.insert(
            id.clone(),
            json!([{ "id": "m", "tabId": id, "role": "user", "text": "hi" }]),
        );
    }

    let open_tabs = vec![json!({
        "id": "open_1",
        "title": "Open 1",
        "agent": "shell",
        "cwd": home.to_string_lossy(),
        "status": "idle"
    })];
    seed_split_workspace(
        &app_home,
        Some("open_1"),
        &open_tabs,
        &closed_tabs,
        &messages_by_tab,
    );

    let daemon = TestDaemon::start(&home, None);
    let (st, closed_resp) = daemon.api("GET", "/api/tabs/closed", None);
    assert_eq!(st, 200);
    let arr = closed_resp["closedTabs"].as_array().unwrap();
    assert_eq!(arr.len(), 50);
    assert_eq!(arr[0]["id"].as_str(), Some("recent_01"));
    assert_eq!(arr[49]["id"].as_str(), Some("recent_50"));

    // Expired and >50 evicted tabs cannot be revived and had their per-tab folders purged from disk.
    let tabs_root = app_home.join("agents_tabs");
    for discarded in ["expired_25h", "expired_24h", "recent_51", "recent_55"] {
        let (rev_st, _) = daemon.api(
            "POST",
            &format!("/api/tabs/closed/{discarded}/revive"),
            Some(json!({})),
        );
        assert_eq!(rev_st, 404, "expected {discarded} to be discarded forever");
        assert!(
            !tabs_root.join(discarded).exists(),
            "expected per-tab folder for {discarded} to be deleted"
        );
    }
    assert!(tabs_root.join("recent_50/messages.json").is_file());

    // Closing one more open tab pushes recent_50 out beyond the last 50.
    let (st, del) = daemon.api("DELETE", "/api/tabs/open_1", None);
    assert_eq!(st, 200);
    let updated_closed = del["closedTabs"].as_array().unwrap();
    assert_eq!(updated_closed.len(), 50);
    assert_eq!(updated_closed[0]["id"].as_str(), Some("open_1"));
    assert_eq!(updated_closed[49]["id"].as_str(), Some("recent_49"));

    let (rev_st, _) = daemon.api("POST", "/api/tabs/closed/recent_50/revive", Some(json!({})));
    assert_eq!(rev_st, 404);

    // Wait for debounced save and confirm recent_50's per-tab directory was removed.
    let deadline = Instant::now() + Duration::from_secs(3);
    while tabs_root.join("recent_50").exists() && Instant::now() < deadline {
        thread::sleep(Duration::from_millis(50));
    }
    assert!(!tabs_root.join("recent_50").exists());

    drop(daemon);
    let _ = fs::remove_dir_all(&home);
}

#[test]
fn revive_closed_tab_respects_open_tab_limit_without_losing_closed_tab() {
    let home = make_temp_dir("agentsterm-rs-revive-limit");
    let app_home = home.join(".agents-term");

    let now_ms = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_millis() as i64;
    let open_tabs: Vec<_> = (1..=10)
        .map(|i| {
            json!({
                "id": format!("open_{i}"),
                "title": format!("Open {i}"),
                "agent": "shell",
                "cwd": home.to_string_lossy(),
                "status": "idle"
            })
        })
        .collect();
    let closed_tabs = vec![json!({
        "id": "closed_target",
        "title": "Accidental Close",
        "agent": "claude",
        "cwd": home.to_string_lossy(),
        "status": "idle",
        "closedAt": now_ms - 5_000
    })];
    let mut messages_by_tab = serde_json::Map::new();
    messages_by_tab.insert(
        "closed_target".to_string(),
        json!([{ "id": "m1", "tabId": "closed_target", "role": "user", "text": "Keep me" }]),
    );
    seed_split_workspace(
        &app_home,
        Some("open_1"),
        &open_tabs,
        &closed_tabs,
        &messages_by_tab,
    );

    let daemon = TestDaemon::start(&home, None);
    let (st, err_body) = daemon.api(
        "POST",
        "/api/tabs/closed/closed_target/revive",
        Some(json!({})),
    );
    assert_eq!(st, 409);
    assert!(err_body["error"].as_str().unwrap().contains("10 open tabs"));

    // Closed tab must still be preserved after a 409 rejection.
    let (st, closed_resp) = daemon.api("GET", "/api/tabs/closed", None);
    assert_eq!(st, 200);
    assert_eq!(closed_resp["closedTabs"].as_array().unwrap().len(), 1);
    assert_eq!(
        closed_resp["closedTabs"][0]["id"].as_str(),
        Some("closed_target")
    );

    // Close one open tab to make room, then revive succeeds.
    assert_eq!(daemon.api("DELETE", "/api/tabs/open_1", None).0, 200);
    let (st, revived) = daemon.api(
        "POST",
        "/api/tabs/closed/closed_target/revive",
        Some(json!({})),
    );
    assert_eq!(st, 200);
    assert_eq!(revived["tab"]["id"].as_str(), Some("closed_target"));
    assert_eq!(revived["messages"].as_array().unwrap().len(), 1);

    drop(daemon);
    let _ = fs::remove_dir_all(&home);
}

#[test]
fn work_done_padding_instruction_is_non_modifiable_and_token_marks_tab_done() {
    let home = make_temp_dir("agentsterm-rs-work-done");
    let fake_bin = home.join("fake-bin");
    let args_log = home.join("claude-args.log");
    fs::create_dir_all(&fake_bin).unwrap();
    write_exec(
        &fake_bin.join("claude"),
        &format!(
            r#"#!/usr/bin/env sh
printf '%s\n---\n' "$*" >> "{}"
case "$*" in
  *"TURN_PROSE"*)
    printf '{{"type":"stream_event","session_id":"sess-wd","event":{{"type":"content_block_delta","index":0,"delta":{{"type":"text_delta","text":"All done. You can close this tab now."}}}}}}\n'
    ;;
  *"TURN_TOKEN"*)
    printf '{{"type":"stream_event","session_id":"sess-wd","event":{{"type":"content_block_delta","index":0,"delta":{{"type":"text_delta","text":"Shipped cleanly!\\n\\n<this-work-is-"}}}}}}\n'
    printf '{{"type":"stream_event","session_id":"sess-wd","event":{{"type":"content_block_delta","index":0,"delta":{{"type":"text_delta","text":"done-172728>\\n"}}}}}}\n'
    ;;
  *"TURN_CODE"*)
    printf '{{"type":"stream_event","session_id":"sess-wd","event":{{"type":"content_block_delta","index":0,"delta":{{"type":"text_delta","text":"Example:\\n```rust\\nconst T = \\"<this-work-is-done-172728>\\";\\n```\\nStill working."}}}}}}\n'
    ;;
esac
"#,
            args_log.display()
        ),
    );

    let daemon = TestDaemon::start(&home, Some(&fake_bin));
    let (st, created) = daemon.api(
        "POST",
        "/api/tabs",
        Some(json!({ "agent": "claude", "cwd": home.to_string_lossy(), "systemPrompt": "" })),
    );
    assert_eq!(st, 200);
    let tab_id = created["tab"]["id"].as_str().unwrap().to_string();
    assert!(created["tab"]["systemPrompt"].is_null());

    let wait_settled = |expected_msg_count: usize| -> Value {
        let deadline = Instant::now() + Duration::from_secs(6);
        loop {
            let (_, resp) = daemon.api("GET", &format!("/api/tabs/{tab_id}/messages"), None);
            let msgs = resp["messages"].as_array().unwrap();
            let status = resp["tab"]["status"].as_str().unwrap_or("");
            if msgs.len() >= expected_msg_count && status != "working" {
                return resp;
            }
            assert!(
                Instant::now() < deadline,
                "Tab did not settle: status={status} msgs={}",
                msgs.len()
            );
            thread::sleep(Duration::from_millis(50));
        }
    };

    // 1) Empty user systemPrompt still injects padding instruction; prose without token stays idle.
    let (st, _) = daemon.api(
        "POST",
        &format!("/api/tabs/{tab_id}/messages"),
        Some(json!({ "text": "TURN_PROSE" })),
    );
    assert_eq!(st, 200);
    let resp1 = wait_settled(2);
    assert_eq!(resp1["tab"]["status"].as_str(), Some("idle"));
    assert!(resp1["tab"]["systemPrompt"].is_null());
    let log1 = fs::read_to_string(&args_log).unwrap();
    assert!(
        log1.contains("<this-work-is-done-172728>"),
        "Expected non-modifiable padding instruction even when user systemPrompt is empty: {log1}"
    );

    // 2) Custom systemPrompt preserves user prompt + padding instruction; split token marks done & strips cleanly.
    let (st, patched) = daemon.api(
        "PATCH",
        &format!("/api/tabs/{tab_id}"),
        Some(json!({ "systemPrompt": "Be concise." })),
    );
    assert_eq!(st, 200);
    assert_eq!(patched["tab"]["systemPrompt"].as_str(), Some("Be concise."));

    let (st, _) = daemon.api(
        "POST",
        &format!("/api/tabs/{tab_id}/messages"),
        Some(json!({ "text": "TURN_TOKEN" })),
    );
    assert_eq!(st, 200);
    let resp2 = wait_settled(4);
    assert_eq!(resp2["tab"]["status"].as_str(), Some("done"));
    assert_eq!(resp2["tab"]["statusDetail"].as_str(), Some("Task finished"));
    assert_eq!(
        resp2["tab"]["lastMessagePreview"].as_str(),
        Some("Shipped cleanly!")
    );
    let msg2 = resp2["messages"].as_array().unwrap().last().unwrap();
    assert_eq!(msg2["text"].as_str(), Some("Shipped cleanly!"));
    let log2 = fs::read_to_string(&args_log).unwrap();
    assert!(log2.contains("Be concise."));
    assert!(log2.contains("<this-work-is-done-172728>"));
    assert!(log2.contains("docs/agents_debugging_tips.md"));

    // 3) Token inside fenced code block is preserved verbatim and does not mark tab done.
    let (st, _) = daemon.api(
        "POST",
        &format!("/api/tabs/{tab_id}/messages"),
        Some(json!({ "text": "TURN_CODE" })),
    );
    assert_eq!(st, 200);
    let resp3 = wait_settled(6);
    assert_eq!(resp3["tab"]["status"].as_str(), Some("idle"));
    let msg3 = resp3["messages"].as_array().unwrap().last().unwrap();
    let msg3_text = msg3["text"].as_str().unwrap_or("");
    assert!(
        msg3_text.contains("<this-work-is-done-172728>"),
        "Expected token inside code block to be preserved: {msg3_text}"
    );

    let _ = fs::remove_dir_all(&home);
}

#[test]
fn agent_usage_endpoint_returns_parsed_usage_and_errors() {
    let home = make_temp_dir("agentsterm-usage");
    // enriched_path() searches $HOME/.local/bin before /usr/bin, so place the mock curl there.
    let fake_bin = home.join(".local/bin");
    fs::create_dir_all(&fake_bin).unwrap();

    // Write fake credentials for agy, claude, and codex.
    let agy_dir = home.join(".gemini/antigravity-cli");
    fs::create_dir_all(&agy_dir).unwrap();
    fs::write(
        agy_dir.join("antigravity-oauth-token"),
        r#"{"token":{"access_token":"fake-agy-token"}}"#,
    )
    .unwrap();

    let claude_dir = home.join(".claude");
    fs::create_dir_all(&claude_dir).unwrap();
    fs::write(
        claude_dir.join(".credentials.json"),
        r#"{"claudeAiOauth":{"accessToken":"fake-claude-token","subscriptionType":"max"}}"#,
    )
    .unwrap();

    let codex_dir = home.join(".codex");
    fs::create_dir_all(&codex_dir).unwrap();
    fs::write(
        codex_dir.join("auth.json"),
        r#"{"tokens":{"access_token":"fake-codex-token","account_id":"acct-1"}}"#,
    )
    .unwrap();

    // Fake curl that inspects the URL argument and returns mock JSON + HTTP 200.
    let fake_curl = fake_bin.join("curl");
    write_exec(
        &fake_curl,
        r#"#!/bin/sh
cat >/dev/null
URL=""
for arg in "$@"; do
  case "$arg" in
    https://*) URL="$arg" ;;
  esac
done
case "$URL" in
  *fetchAvailableModels*)
    printf '%s\n200' '{"models":{"claude-opus-5-5-high":{"displayName":"Claude Opus 5.5 (High)","quotaInfo":{"remainingFraction":0.85,"resetTime":"2026-11-26T05:59:04Z"}},"gemini-3-flash":{"displayName":"Gemini 3 Flash","quotaInfo":{"remainingFraction":0.7,"resetTime":"2026-11-26T03:34:04Z"}}}}'
    ;;
  *api.anthropic.com/api/oauth/usage*)
    printf '%s\n200' '{"limits":[{"kind":"session","percent":15,"resets_at":"2026-11-26T05:00:00Z","scope":null},{"kind":"weekly_all","percent":22,"resets_at":"2026-11-30T01:59:59Z","scope":null}]}'
    ;;
  *chatgpt.com/backend-api/wham/usage*)
    printf '%s\n200' '{"plan_type":"pro","rate_limit":{"primary_window":{"used_percent":4,"limit_window_seconds":604800,"reset_at":1796238718}}}'
    ;;
  *)
    printf '%s\n404' '{"error":"unknown url"}'
    ;;
esac
"#,
    );

    let daemon = TestDaemon::start(&home, Some(&fake_bin));

    // 1) agy usage
    let (st, agy) = daemon.api("GET", "/api/agents/agy/usage", None);
    assert_eq!(st, 200);
    assert_eq!(agy["agent"].as_str(), Some("agy"));
    assert!(agy["error"].is_null());
    let agy_items = agy["items"].as_array().unwrap();
    assert_eq!(agy_items.len(), 2);
    assert_eq!(agy_items[0]["label"].as_str(), Some("Gemini 3 Flash"));
    assert_eq!(agy_items[0]["remainingPercent"].as_f64(), Some(70.0));
    assert_eq!(agy_items[1]["label"].as_str(), Some("Claude Opus 5.5"));
    assert_eq!(agy_items[1]["remainingPercent"].as_f64(), Some(85.0));

    // 2) claude usage
    let (st, claude) = daemon.api("GET", "/api/agents/claude/usage", None);
    assert_eq!(st, 200);
    assert_eq!(claude["agent"].as_str(), Some("claude"));
    assert_eq!(claude["plan"].as_str(), Some("max"));
    let claude_items = claude["items"].as_array().unwrap();
    assert_eq!(claude_items.len(), 2);
    assert_eq!(claude_items[0]["label"].as_str(), Some("5-hour session"));
    assert_eq!(claude_items[0]["remainingPercent"].as_f64(), Some(85.0));
    assert_eq!(claude_items[1]["label"].as_str(), Some("Weekly limit"));
    assert_eq!(claude_items[1]["remainingPercent"].as_f64(), Some(78.0));

    // 3) codex usage
    let (st, codex) = daemon.api("GET", "/api/agents/codex/usage", None);
    assert_eq!(st, 200);
    assert_eq!(codex["agent"].as_str(), Some("codex"));
    assert_eq!(codex["plan"].as_str(), Some("pro"));
    let codex_items = codex["items"].as_array().unwrap();
    assert_eq!(codex_items.len(), 1);
    assert_eq!(codex_items[0]["label"].as_str(), Some("Weekly limit"));
    assert_eq!(codex_items[0]["remainingPercent"].as_f64(), Some(96.0));

    // 4) shell returns clear unsupported error
    let (st, shell) = daemon.api("GET", "/api/agents/shell/usage", None);
    assert_eq!(st, 200);
    assert!(shell["error"]
        .as_str()
        .unwrap_or("")
        .contains("not supported"));

    let _ = fs::remove_dir_all(&home);
}

#[test]
fn daemon_lock_prevents_concurrent_instances_and_releases_on_exit() {
    let home = make_temp_dir("agentsterm-lock-home");
    let custom_app_home = home.join("custom-agents-term");

    let daemon1 = TestDaemon::start_with_app_home(&home, Some(&custom_app_home), None);
    assert!(
        !home.join(".agents-term").exists(),
        "AGENTSTERM_HOME override should not touch ~/.agents-term"
    );
    let lock_path = custom_app_home.join("daemon.lock");
    assert!(lock_path.is_file());
    assert_eq!(
        fs::metadata(&lock_path).unwrap().permissions().mode() & 0o777,
        0o600
    );

    // Starting a second daemon on the same AGENTSTERM_HOME must fail fast on daemon.lock.
    let second_port = free_port();
    let dup = Command::new(bin_path())
        .args([
            "serve",
            "--host",
            "127.0.0.1",
            "--port",
            &second_port.to_string(),
        ])
        .env("HOME", &home)
        .env("AGENTSTERM_HOME", &custom_app_home)
        .output()
        .unwrap();
    assert_eq!(dup.status.code(), Some(1));
    let stderr = String::from_utf8_lossy(&dup.stderr);
    assert!(
        stderr.contains("daemon.lock"),
        "Expected daemon.lock error message, got: {stderr}"
    );

    // Original daemon remains healthy.
    let (st, _) = daemon1.api("GET", "/api/info", None);
    assert_eq!(st, 200);

    // Once daemon1 exits, a fresh daemon acquires the lock cleanly.
    drop(daemon1);
    let daemon2 = TestDaemon::start_with_app_home(&home, Some(&custom_app_home), None);
    let (st, _) = daemon2.api("GET", "/api/info", None);
    assert_eq!(st, 200);

    drop(daemon2);
    let _ = fs::remove_dir_all(&home);
}

#[test]
fn split_per_tab_storage_rewrites_only_dirty_tab_and_cleans_orphans() {
    let home = make_temp_dir("agentsterm-split-store");
    let custom_app_home = home.join("state-root");
    let orphan_dir = custom_app_home.join("agents_tabs/orphan_stale_tab");
    fs::create_dir_all(&orphan_dir).unwrap();
    fs::write(orphan_dir.join("messages.json"), "[]").unwrap();
    seed_split_workspace(&custom_app_home, None, &[], &[], &serde_json::Map::new());

    let daemon = TestDaemon::start_with_app_home(&home, Some(&custom_app_home), None);

    // Orphaned per-tab directory should be cleaned up on startup.
    assert!(
        !orphan_dir.exists(),
        "Orphaned tab directory should be pruned during startup recovery"
    );

    assert_eq!(
        fs::read(custom_app_home.join("docs/agents_debugging_tips.md")).unwrap(),
        include_bytes!("../../docs/agents_debugging_tips.md").as_slice()
    );

    // Verify /api/info exposes protocolVersion/commit/buildHash/runtime and custom system prompt.
    let (st, info) = daemon.api("GET", "/api/info", None);
    assert_eq!(st, 200);
    assert_eq!(info["protocolVersion"].as_u64(), Some(1));
    assert_eq!(info["runtime"].as_str(), Some("rust"));
    assert!(!info["commit"].as_str().unwrap_or("").is_empty());
    assert!(!info["buildHash"].as_str().unwrap_or("").is_empty());
    let sp = info["defaultSystemPrompt"].as_str().unwrap_or("");
    assert!(sp.contains(
        &custom_app_home
            .join("live-debugging.md")
            .display()
            .to_string()
    ));
    assert!(sp.contains(
        &custom_app_home
            .join("debug-dumps/latest")
            .display()
            .to_string()
    ));
    assert!(sp.contains("read-only"));

    // Create two tabs and run a shell turn in each so both have messages.json on disk.
    let (st, t1) = daemon.api(
        "POST",
        "/api/tabs",
        Some(json!({ "agent": "shell", "title": "Tab A" })),
    );
    assert_eq!(st, 200);
    let tab_a = t1["tab"]["id"].as_str().unwrap().to_string();
    let (st, t2) = daemon.api(
        "POST",
        "/api/tabs",
        Some(json!({ "agent": "shell", "title": "Tab B" })),
    );
    assert_eq!(st, 200);
    let tab_b = t2["tab"]["id"].as_str().unwrap().to_string();

    for (tid, marker) in [(&tab_a, "ALPHA"), (&tab_b, "BETA")] {
        assert_eq!(
            daemon
                .api(
                    "POST",
                    &format!("/api/tabs/{tid}/messages"),
                    Some(json!({ "text": format!("echo {marker}") }))
                )
                .0,
            200
        );
        let deadline = Instant::now() + Duration::from_secs(5);
        loop {
            let (_, resp) = daemon.api("GET", &format!("/api/tabs/{tid}/messages"), None);
            if resp["tab"]["status"].as_str() == Some("idle")
                && resp["messages"].as_array().map(|a| a.len()).unwrap_or(0) >= 2
            {
                break;
            }
            assert!(Instant::now() < deadline, "Tab {tid} did not settle");
            thread::sleep(Duration::from_millis(50));
        }
    }

    let tabs_root = custom_app_home.join("agents_tabs");
    let tab_a_msgs = tabs_root.join(&tab_a).join("messages.json");
    let tab_b_msgs = tabs_root.join(&tab_b).join("messages.json");
    let ws_file = tabs_root.join("workspace.json");
    let index_file = tabs_root.join("tabs.jsonl");

    // Store writes are debounced asynchronously, so wait for the persisted files to settle on disk.
    let wait_for_persisted_messages = |path: &Path, min_count: usize, expected_text: &str| {
        let deadline = Instant::now() + Duration::from_secs(5);
        loop {
            if let Ok(raw) = fs::read_to_string(path) {
                if let Ok(Value::Array(msgs)) = serde_json::from_str::<Value>(&raw) {
                    let last_complete = msgs
                        .last()
                        .and_then(|m| m.get("status"))
                        .and_then(Value::as_str)
                        == Some("complete");
                    if msgs.len() >= min_count && last_complete && raw.contains(expected_text) {
                        return;
                    }
                }
            }
            assert!(
                Instant::now() < deadline,
                "Timed out waiting for {} to flush at least {} messages containing {}",
                path.display(),
                min_count,
                expected_text
            );
            thread::sleep(Duration::from_millis(25));
        }
    };
    wait_for_persisted_messages(&tab_a_msgs, 2, "ALPHA");
    wait_for_persisted_messages(&tab_b_msgs, 2, "BETA");
    let index_deadline = Instant::now() + Duration::from_secs(5);
    while !ws_file.is_file() || !index_file.is_file() {
        assert!(
            Instant::now() < index_deadline,
            "workspace.json or tabs.jsonl did not flush"
        );
        thread::sleep(Duration::from_millis(25));
    }
    assert!(ws_file.is_file());
    assert!(index_file.is_file());
    assert!(tab_a_msgs.is_file());
    assert!(tab_b_msgs.is_file());
    assert!(!custom_app_home.join("state.json").exists());

    // Check permissions: 0700 on agents_tabs & <tab_id>, 0600 on workspace.json, tabs.jsonl, messages.json.
    assert_eq!(
        fs::metadata(&tabs_root).unwrap().permissions().mode() & 0o777,
        0o700
    );
    assert_eq!(
        fs::metadata(tabs_root.join(&tab_a))
            .unwrap()
            .permissions()
            .mode()
            & 0o777,
        0o700
    );
    assert_eq!(
        fs::metadata(&ws_file).unwrap().permissions().mode() & 0o777,
        0o600
    );
    assert_eq!(
        fs::metadata(&index_file).unwrap().permissions().mode() & 0o777,
        0o600
    );
    assert_eq!(
        fs::metadata(&tab_a_msgs).unwrap().permissions().mode() & 0o777,
        0o600
    );

    let ws_json: Value = serde_json::from_str(&fs::read_to_string(&ws_file).unwrap()).unwrap();
    assert_eq!(ws_json["schemaVersion"].as_u64(), Some(1));
    assert_eq!(ws_json["activeTabId"].as_str(), Some(tab_b.as_str()));

    // Updating Tab B must NOT rewrite Tab A's messages.json (atomic rename would change inode).
    // Keep both pre-update files open across the rename so the filesystem cannot reuse freed inodes.
    let tab_a_file_before = fs::File::open(&tab_a_msgs).unwrap();
    let tab_b_file_before = fs::File::open(&tab_b_msgs).unwrap();
    let tab_a_ino_before = tab_a_file_before.metadata().unwrap().ino();
    let tab_b_ino_before = tab_b_file_before.metadata().unwrap().ino();

    assert_eq!(
        daemon
            .api(
                "POST",
                &format!("/api/tabs/{tab_b}/messages"),
                Some(json!({ "text": "echo BETA_2" }))
            )
            .0,
        200
    );
    let deadline = Instant::now() + Duration::from_secs(5);
    loop {
        let (_, resp) = daemon.api("GET", &format!("/api/tabs/{tab_b}/messages"), None);
        if resp["tab"]["status"].as_str() == Some("idle")
            && resp["messages"].as_array().map(|a| a.len()).unwrap_or(0) >= 4
        {
            break;
        }
        assert!(
            Instant::now() < deadline,
            "Tab B second turn did not settle"
        );
        thread::sleep(Duration::from_millis(50));
    }
    wait_for_persisted_messages(&tab_b_msgs, 4, "BETA_2");

    assert_eq!(
        fs::metadata(&tab_a_msgs).unwrap().ino(),
        tab_a_ino_before,
        "Streaming updates in Tab B must not rewrite Tab A's messages.json"
    );
    assert_ne!(
        fs::metadata(&tab_b_msgs).unwrap().ino(),
        tab_b_ino_before,
        "Tab B's messages.json should have been atomically replaced"
    );
    drop(tab_a_file_before);
    drop(tab_b_file_before);

    // Closing Tab A marks it state=closed in tabs.jsonl while keeping its messages.json for revive.
    assert_eq!(
        daemon.api("DELETE", &format!("/api/tabs/{tab_a}"), None).0,
        200
    );
    drop(daemon);

    let lines: Vec<Value> = fs::read_to_string(&index_file)
        .unwrap()
        .lines()
        .filter(|l| !l.trim().is_empty())
        .map(|l| serde_json::from_str(l).unwrap())
        .collect();
    let rec_a = lines
        .iter()
        .find(|v| v["id"].as_str() == Some(&tab_a))
        .unwrap();
    let rec_b = lines
        .iter()
        .find(|v| v["id"].as_str() == Some(&tab_b))
        .unwrap();
    assert_eq!(rec_a["state"].as_str(), Some("closed"));
    assert!(rec_a["closedAt"].as_i64().unwrap_or(0) > 0);
    assert_eq!(rec_b["state"].as_str(), Some("open"));
    assert!(rec_b.get("closedAt").is_none());
    assert!(tab_a_msgs.is_file());

    let _ = fs::remove_dir_all(&home);
}

/// A minimal valid-looking PNG: the signature followed by filler bytes.
fn fake_png(fill: u8, len: usize) -> Vec<u8> {
    let mut bytes = b"\x89PNG\r\n\x1a\n".to_vec();
    bytes.resize(len.max(bytes.len()), fill);
    bytes
}

/// A fake `claude` that records its arguments and answers with one line of text. A prompt
/// containing `SLOW_TURN` keeps the turn running long enough to queue messages behind it.
fn write_fake_claude(fake_bin: &Path, args_log: &Path) {
    fs::create_dir_all(fake_bin).unwrap();
    write_exec(
        &fake_bin.join("claude"),
        &format!(
            r#"#!/usr/bin/env sh
printf '%s\n---\n' "$*" >> "{}"
case "$*" in
  *"SLOW_TURN"*) sleep 2 ;;
esac
printf '{{"type":"stream_event","session_id":"sess-att","event":{{"type":"content_block_delta","index":0,"delta":{{"type":"text_delta","text":"I see it."}}}}}}\n'
"#,
            args_log.display()
        ),
    );
}

#[test]
fn image_attachments_are_validated_and_stored_privately_in_the_tab_folder() {
    let home = make_temp_dir("agentsterm-rs-att-upload");
    let daemon = TestDaemon::start(&home, None);

    // The app reads the limits from /api/info, and hides the attach button without them.
    let (st, info) = daemon.api("GET", "/api/info", None);
    assert_eq!(st, 200);
    assert_eq!(
        info["attachments"]["maxBytes"].as_u64(),
        Some(8 * 1024 * 1024)
    );
    assert_eq!(info["attachments"]["maxPerMessage"].as_u64(), Some(8));
    assert!(info["attachments"]["mimeTypes"]
        .as_array()
        .unwrap()
        .contains(&json!("image/png")));

    let tab_id = daemon.create_tab("claude", &home);
    let png = fake_png(7, 4096);
    let (st, uploaded) = daemon.upload_image(&tab_id, &png);
    assert_eq!(st, 200, "{uploaded}");
    let att = &uploaded["attachment"];
    let att_id = att["id"].as_str().unwrap();
    assert!(att_id.starts_with("att_"));
    assert_eq!(att["mimeType"].as_str(), Some("image/png"));
    assert_eq!(att["bytes"].as_u64(), Some(4096));

    // Stored under ~/.agents-term/agents_tabs/<tabId>/attachments/, readable by the owner only.
    let dir = home
        .join(".agents-term")
        .join("agents_tabs")
        .join(&tab_id)
        .join("attachments");
    let stored = dir.join(format!("{att_id}.png"));
    assert_eq!(
        att["path"].as_str(),
        Some(stored.to_string_lossy().as_ref())
    );
    assert_eq!(fs::read(&stored).unwrap(), png);
    assert_eq!(fs::metadata(&stored).unwrap().mode() & 0o777, 0o600);
    assert_eq!(fs::metadata(&dir).unwrap().mode() & 0o777, 0o700);

    // The transcript downloads it back with the right type.
    let path = format!("/api/tabs/{tab_id}/attachments/{att_id}");
    let (st, head, body) = daemon.api_raw("GET", &path, Some(&daemon.token), None);
    assert_eq!(st, 200);
    assert!(head.contains("content-type: image/png"), "{head}");
    assert_eq!(body, png);
    let (st, _, _) = daemon.api_raw("GET", &path, None, None);
    assert_eq!(st, 401, "downloads need the bearer token");
    let (st, _, _) = daemon.api_raw("GET", &path, Some("wrong"), None);
    assert_eq!(st, 401);
    for missing in ["att_00", "att_..%2F..%2Fconfig", "config.json"] {
        let (st, _, _) = daemon.api_raw(
            "GET",
            &format!("/api/tabs/{tab_id}/attachments/{missing}"),
            Some(&daemon.token),
            None,
        );
        assert_eq!(st, 404, "{missing}");
    }

    // A JPEG keeps its own type; the type is read from the bytes, not claimed by the client.
    let (st, jpeg) = daemon.upload_image(&tab_id, b"\xff\xd8\xff\xe0JFIF-ish");
    assert_eq!(st, 200);
    assert_eq!(jpeg["attachment"]["mimeType"].as_str(), Some("image/jpeg"));
    assert!(jpeg["attachment"]["name"]
        .as_str()
        .unwrap()
        .ends_with(".jpg"));

    // Rejections.
    let (st, err) = daemon.upload_image(&tab_id, b"#!/bin/sh\necho not an image\n");
    assert_eq!(st, 415, "{err}");
    assert!(err["error"]
        .as_str()
        .unwrap()
        .contains("Unsupported image format"));
    let (st, _) = daemon.upload_image(&tab_id, b"");
    assert_eq!(st, 400);
    let (st, err) = daemon.upload_image(&tab_id, &fake_png(1, 8 * 1024 * 1024 + 1));
    assert_eq!(st, 413, "{err}");
    assert!(err["error"].as_str().unwrap().contains("8 MB"));
    let (st, _) = daemon.upload_image(&tab_id, &fake_png(1, 8 * 1024 * 1024));
    assert_eq!(st, 200, "an image of exactly the limit is accepted");
    let (st, _) = daemon.upload_image("tab_missing", &png);
    assert_eq!(st, 404);
    let (st, _, _) = daemon.api_raw(
        "POST",
        &format!("/api/tabs/{tab_id}/attachments"),
        None,
        Some(&png),
    );
    assert_eq!(st, 401);

    // Shell tabs run commands, so they take no images.
    let shell_id = daemon.create_tab("shell", &home);
    let (st, err) = daemon.upload_image(&shell_id, &png);
    assert_eq!(st, 400, "{err}");
    let (st, err) = daemon.api(
        "POST",
        &format!("/api/tabs/{shell_id}/messages"),
        Some(json!({ "text": "echo hi", "attachments": [att_id] })),
    );
    assert_eq!(st, 400, "an attachment of another tab is not found: {err}");

    drop(daemon);
    let _ = fs::remove_dir_all(&home);
}

#[test]
fn message_with_images_hands_the_file_paths_to_the_agent_and_survives_restart() {
    let home = make_temp_dir("agentsterm-rs-att-send");
    let fake_bin = home.join("fake-bin");
    let args_log = home.join("claude-args.log");
    write_fake_claude(&fake_bin, &args_log);

    let daemon = TestDaemon::start(&home, Some(&fake_bin));
    let tab_id = daemon.create_tab("claude", &home);
    let messages_path = format!("/api/tabs/{tab_id}/messages");

    // Old app builds send text only: no attachments field appears and the prompt is untouched.
    let (st, sent) = daemon.api(
        "POST",
        &messages_path,
        Some(json!({ "text": "PLAIN_TURN" })),
    );
    assert_eq!(st, 200);
    assert!(sent["userMessage"].get("attachments").is_none());
    daemon.wait_settled(&tab_id, 2);
    let log = fs::read_to_string(&args_log).unwrap();
    assert!(!log.contains("--add-dir"), "{log}");
    assert!(!log.contains("attached"), "{log}");

    let (_, first) = daemon.upload_image(&tab_id, &fake_png(1, 512));
    let (_, second) = daemon.upload_image(&tab_id, &fake_png(2, 1024));
    let first_id = first["attachment"]["id"].as_str().unwrap().to_string();
    let second_id = second["attachment"]["id"].as_str().unwrap().to_string();
    let first_path = first["attachment"]["path"].as_str().unwrap().to_string();
    let second_path = second["attachment"]["path"].as_str().unwrap().to_string();

    // Unknown ids are refused rather than silently dropped, and nothing is sent.
    let (st, err) = daemon.api(
        "POST",
        &messages_path,
        Some(json!({ "text": "look", "attachments": [first_id, "att_ffff"] })),
    );
    assert_eq!(st, 400, "{err}");
    assert!(err["error"].as_str().unwrap().contains("att_ffff"));
    let too_many: Vec<String> = (0..9)
        .map(|i| {
            let (_, up) = daemon.upload_image(&tab_id, &fake_png(i, 64));
            up["attachment"]["id"].as_str().unwrap().to_string()
        })
        .collect();
    let (st, err) = daemon.api(
        "POST",
        &messages_path,
        Some(json!({ "text": "look", "attachments": too_many })),
    );
    assert_eq!(st, 400, "{err}");
    assert!(err["error"].as_str().unwrap().contains("at most 8"));
    let (_, unchanged) = daemon.api("GET", &messages_path, None);
    assert_eq!(unchanged["messages"].as_array().unwrap().len(), 2);

    // Text plus two images.
    let (st, sent) = daemon.api(
        "POST",
        &messages_path,
        Some(json!({ "text": "What is wrong in these?", "attachments": [first_id, second_id] })),
    );
    assert_eq!(st, 200, "{sent}");
    let user_atts = sent["userMessage"]["attachments"].as_array().unwrap();
    assert_eq!(user_atts.len(), 2);
    assert_eq!(user_atts[0]["id"].as_str(), Some(first_id.as_str()));
    assert_eq!(user_atts[1]["mimeType"].as_str(), Some("image/png"));
    assert_eq!(
        sent["userMessage"]["text"].as_str(),
        Some("What is wrong in these?"),
        "the transcript keeps the text the user typed, without the file paths"
    );
    daemon.wait_settled(&tab_id, 4);
    let log = fs::read_to_string(&args_log).unwrap();
    let turn = log.split("\n---\n").nth(1).unwrap();
    assert!(turn.contains("What is wrong in these?"), "{turn}");
    assert!(turn.contains(&format!("- {first_path}")), "{turn}");
    assert!(turn.contains(&format!("- {second_path}")), "{turn}");
    let att_dir = Path::new(&first_path).parent().unwrap();
    assert!(
        turn.contains(&format!("--add-dir={}", att_dir.display())),
        "the agent is given read access to the attachment folder: {turn}"
    );

    // Images only, no text.
    let (_, third) = daemon.upload_image(&tab_id, &fake_png(3, 256));
    let third_id = third["attachment"]["id"].as_str().unwrap().to_string();
    let third_path = third["attachment"]["path"].as_str().unwrap().to_string();
    let (st, sent) = daemon.api(
        "POST",
        &messages_path,
        Some(json!({ "text": "", "attachments": [third_id] })),
    );
    assert_eq!(st, 200, "{sent}");
    assert_eq!(sent["userMessage"]["text"].as_str(), Some(""));
    daemon.wait_settled(&tab_id, 6);
    let log = fs::read_to_string(&args_log).unwrap();
    let turn = log.split("\n---\n").nth(2).unwrap();
    assert!(turn.contains("Look at the attached image."), "{turn}");
    assert!(turn.contains(&format!("- {third_path}")), "{turn}");

    // A message with neither text nor images is still refused.
    let (st, _) = daemon.api(
        "POST",
        &messages_path,
        Some(json!({ "text": "  ", "attachments": [] })),
    );
    assert_eq!(st, 400);

    // After a daemon restart the transcript still lists the images and can download them.
    drop(daemon);
    let daemon = TestDaemon::start(&home, Some(&fake_bin));
    let (st, reloaded) = daemon.api("GET", &messages_path, None);
    assert_eq!(st, 200);
    let msgs = reloaded["messages"].as_array().unwrap();
    assert_eq!(msgs.len(), 6);
    assert_eq!(msgs[2]["attachments"].as_array().unwrap().len(), 2);
    assert_eq!(
        msgs[4]["attachments"][0]["id"].as_str(),
        Some(third_id.as_str())
    );
    let (st, _, body) = daemon.api_raw(
        "GET",
        &format!("/api/tabs/{tab_id}/attachments/{second_id}"),
        Some(&daemon.token),
        None,
    );
    assert_eq!(st, 200);
    assert_eq!(body, fake_png(2, 1024));

    // Closing the tab keeps its images (it can be revived); they go when the tab is purged.
    let (st, _) = daemon.api("DELETE", &format!("/api/tabs/{tab_id}"), None);
    assert_eq!(st, 200);
    assert!(Path::new(&first_path).exists());
    let (st, _) = daemon.api("POST", &format!("/api/tabs/closed/{tab_id}/revive"), None);
    assert_eq!(st, 200);
    let (st, _, _) = daemon.api_raw(
        "GET",
        &format!("/api/tabs/{tab_id}/attachments/{first_id}"),
        Some(&daemon.token),
        None,
    );
    assert_eq!(st, 200);

    drop(daemon);
    let _ = fs::remove_dir_all(&home);
}

#[test]
fn queued_messages_keep_their_images_and_removed_ones_delete_them() {
    let home = make_temp_dir("agentsterm-rs-att-queue");
    let fake_bin = home.join("fake-bin");
    let args_log = home.join("claude-args.log");
    write_fake_claude(&fake_bin, &args_log);

    let daemon = TestDaemon::start(&home, Some(&fake_bin));
    let tab_id = daemon.create_tab("claude", &home);
    let messages_path = format!("/api/tabs/{tab_id}/messages");

    let (st, _) = daemon.api("POST", &messages_path, Some(json!({ "text": "SLOW_TURN" })));
    assert_eq!(st, 200);

    let upload = |fill: u8| -> (String, String) {
        let (st, up) = daemon.upload_image(&tab_id, &fake_png(fill, 128));
        assert_eq!(st, 200);
        (
            up["attachment"]["id"].as_str().unwrap().to_string(),
            up["attachment"]["path"].as_str().unwrap().to_string(),
        )
    };
    let (dropped_id, dropped_path) = upload(1);
    let (kept_id, kept_path) = upload(2);
    let (image_only_id, image_only_path) = upload(3);

    // While the agent is busy, messages with images are queued with their images.
    let (st, queued) = daemon.api(
        "POST",
        &messages_path,
        Some(json!({ "text": "never mind this one", "attachments": [dropped_id] })),
    );
    assert_eq!(st, 200, "{queued}");
    assert_eq!(queued["queued"].as_bool(), Some(true));
    let dropped_queue_id = queued["queuedMessage"]["id"].as_str().unwrap().to_string();
    assert_eq!(
        queued["queuedMessage"]["attachments"][0]["id"].as_str(),
        Some(dropped_id.as_str())
    );
    let (st, _) = daemon.api(
        "POST",
        &messages_path,
        Some(json!({ "text": "QUEUED_WITH_IMAGE", "attachments": [kept_id] })),
    );
    assert_eq!(st, 200);
    let (st, queued) = daemon.api(
        "POST",
        &messages_path,
        Some(json!({ "text": "", "attachments": [image_only_id] })),
    );
    assert_eq!(st, 200, "{queued}");
    assert_eq!(queued["tab"]["queuedMessages"].as_array().unwrap().len(), 3);

    // Removing a queued message deletes the images that will now never be sent.
    assert!(Path::new(&dropped_path).exists());
    let (st, removed) = daemon.api(
        "DELETE",
        &format!("/api/tabs/{tab_id}/queue/{dropped_queue_id}"),
        None,
    );
    assert_eq!(st, 200);
    assert_eq!(
        removed["tab"]["queuedMessages"].as_array().unwrap().len(),
        2
    );
    assert!(!Path::new(&dropped_path).exists());
    assert!(Path::new(&kept_path).exists());

    // When the running turn ends, the queue runs as one turn carrying every image.
    let settled = daemon.wait_settled(&tab_id, 4);
    assert!(settled["tab"]["queuedMessages"]
        .as_array()
        .unwrap()
        .is_empty());
    let msgs = settled["messages"].as_array().unwrap();
    assert_eq!(msgs[2]["role"].as_str(), Some("user"));
    assert_eq!(msgs[2]["text"].as_str(), Some("QUEUED_WITH_IMAGE"));
    let ids: Vec<&str> = msgs[2]["attachments"]
        .as_array()
        .unwrap()
        .iter()
        .filter_map(|a| a["id"].as_str())
        .collect();
    assert_eq!(ids, vec![kept_id.as_str(), image_only_id.as_str()]);
    let log = fs::read_to_string(&args_log).unwrap();
    let turn = log.split("\n---\n").nth(1).unwrap();
    assert!(turn.contains("QUEUED_WITH_IMAGE"), "{turn}");
    assert!(turn.contains(&format!("- {kept_path}")), "{turn}");
    assert!(turn.contains(&format!("- {image_only_path}")), "{turn}");
    assert!(!turn.contains(&dropped_path), "{turn}");

    drop(daemon);
    let _ = fs::remove_dir_all(&home);
}

#[test]
fn full_access_permission_denials_do_not_mark_tab_blocked() {
    let home = make_temp_dir("agentsterm-rs-full-access-denials");
    let fake_bin = home.join("fake-bin");
    fs::create_dir_all(&fake_bin).unwrap();
    write_exec(
        &fake_bin.join("claude"),
        r#"#!/usr/bin/env sh
printf '{"type":"result","session_id":"sess-deny","subtype":"success","is_error":false,"result":"Tool call was denied by settings.","permission_denials":[{"tool_name":"Bash","tool_input":{"description":"Download rustup","command":"curl https://sh.rustup.rs"}}]}\n'
"#,
    );

    let daemon = TestDaemon::start(&home, Some(&fake_bin));

    // 1) Restricted ("default") tab: denial marks the tab blocked waiting for approval.
    let (st, created_default) = daemon.api(
        "POST",
        "/api/tabs",
        Some(json!({
            "agent": "claude",
            "cwd": home.to_string_lossy(),
            "permissionMode": "default",
        })),
    );
    assert_eq!(st, 200, "{created_default}");
    let default_tab = created_default["tab"]["id"].as_str().unwrap().to_string();
    let (st, _) = daemon.api(
        "POST",
        &format!("/api/tabs/{default_tab}/messages"),
        Some(json!({ "text": "install rust" })),
    );
    assert_eq!(st, 200);
    let settled = daemon.wait_settled(&default_tab, 2);
    assert_eq!(settled["tab"]["status"].as_str(), Some("blocked"));
    assert_eq!(
        settled["tab"]["statusDetail"].as_str(),
        Some("Waiting for approval")
    );
    let default_msg = &settled["messages"].as_array().unwrap()[1];
    assert_eq!(default_msg["permissionMode"].as_str(), Some("default"));
    assert_eq!(default_msg["deniedActions"].as_array().unwrap().len(), 1);

    // 2) Single-turn overridePermissionMode="full-access": records permissionMode="full-access"
    //    and leaves the tab idle instead of "blocked" even when the CLI's own deny rules reject it.
    let (st, _) = daemon.api(
        "POST",
        &format!("/api/tabs/{default_tab}/messages"),
        Some(json!({ "text": "install rust", "overridePermissionMode": "full-access" })),
    );
    assert_eq!(st, 200);
    let settled = daemon.wait_settled(&default_tab, 4);
    assert_eq!(settled["tab"]["status"].as_str(), Some("idle"));
    assert!(settled["tab"]["statusDetail"].is_null());
    let override_msg = &settled["messages"].as_array().unwrap()[3];
    assert_eq!(override_msg["permissionMode"].as_str(), Some("full-access"));
    assert_eq!(override_msg["deniedActions"].as_array().unwrap().len(), 1);

    // 3) Tab configured with full-access: preserves deniedActions without marking the tab blocked.
    let (st, created_full) = daemon.api(
        "POST",
        "/api/tabs",
        Some(json!({
            "agent": "claude",
            "cwd": home.to_string_lossy(),
            "permissionMode": "full-access",
        })),
    );
    assert_eq!(st, 200, "{created_full}");
    let full_tab = created_full["tab"]["id"].as_str().unwrap().to_string();
    let (st, _) = daemon.api(
        "POST",
        &format!("/api/tabs/{full_tab}/messages"),
        Some(json!({ "text": "install rust" })),
    );
    assert_eq!(st, 200);
    let settled = daemon.wait_settled(&full_tab, 2);
    assert_eq!(settled["tab"]["status"].as_str(), Some("idle"));
    assert!(settled["tab"]["statusDetail"].is_null());
    let full_msg = &settled["messages"].as_array().unwrap()[1];
    assert_eq!(full_msg["permissionMode"].as_str(), Some("full-access"));
    assert_eq!(full_msg["deniedActions"].as_array().unwrap().len(), 1);

    drop(daemon);
    let _ = fs::remove_dir_all(&home);
}

#[test]
fn issue_49_shell_cwd_tilde_expansion_validation_and_no_home_fallback() {
    let home = make_temp_dir("agentsterm-rs-49");
    let project = home.join("project");
    fs::create_dir_all(&project).unwrap();
    let plain_file = home.join("not-a-dir.txt");
    fs::write(&plain_file, "hello").unwrap();

    let daemon = TestDaemon::start(&home, None);

    // Missing absolute path fails POST /api/tabs with 400.
    let missing_abs = home.join("does-not-exist-49");
    let (st, err) = daemon.api(
        "POST",
        "/api/tabs",
        Some(json!({ "agent": "shell", "cwd": missing_abs.to_string_lossy() })),
    );
    assert_eq!(st, 400, "{err}");
    assert_eq!(
        err["error"].as_str(),
        Some(
            format!(
                "Working directory does not exist: {}",
                missing_abs.display()
            )
            .as_str()
        )
    );

    // Missing ~/path expands ~ and fails POST /api/tabs with 400.
    let (st, err) = daemon.api(
        "POST",
        "/api/tabs",
        Some(json!({ "agent": "shell", "cwd": "~/missing-sub" })),
    );
    assert_eq!(st, 400, "{err}");
    assert_eq!(
        err["error"].as_str(),
        Some(
            format!(
                "Working directory does not exist: {}",
                home.join("missing-sub").display()
            )
            .as_str()
        )
    );

    // Existing file (not a directory) fails POST /api/tabs with 400.
    let (st, err) = daemon.api(
        "POST",
        "/api/tabs",
        Some(json!({ "agent": "shell", "cwd": plain_file.to_string_lossy() })),
    );
    assert_eq!(st, 400, "{err}");
    assert_eq!(
        err["error"].as_str(),
        Some(
            format!(
                "Working directory is not a directory: {}",
                plain_file.display()
            )
            .as_str()
        )
    );

    // Existing ~/project expands against HOME and executes inside <home>/project.
    let (st, created) = daemon.api(
        "POST",
        "/api/tabs",
        Some(json!({ "agent": "shell", "cwd": "~/project" })),
    );
    assert_eq!(st, 200, "{created}");
    assert_eq!(
        created["tab"]["cwd"].as_str(),
        Some(project.to_string_lossy().as_ref())
    );
    let tab_id = created["tab"]["id"].as_str().unwrap().to_string();

    let (st, _) = daemon.api(
        "POST",
        &format!("/api/tabs/{tab_id}/messages"),
        Some(json!({ "text": "pwd" })),
    );
    assert_eq!(st, 200);
    let settled = daemon.wait_settled(&tab_id, 2);
    assert_eq!(settled["tab"]["status"].as_str(), Some("idle"));
    let msg1 = &settled["messages"].as_array().unwrap()[1];
    assert_eq!(msg1["exitCode"].as_i64(), Some(0));
    assert!(
        msg1["text"]
            .as_str()
            .unwrap_or("")
            .contains(project.to_string_lossy().as_ref()),
        "Expected pwd output to contain {}, got: {:?}",
        project.display(),
        msg1["text"]
    );

    // Removing the directory after tab creation fails the turn with an actionable error
    // naming the path and never silently falls back to or overwrites cwd with $HOME.
    fs::remove_dir_all(&project).unwrap();
    let (st, _) = daemon.api(
        "POST",
        &format!("/api/tabs/{tab_id}/messages"),
        Some(json!({ "text": "pwd" })),
    );
    assert_eq!(st, 200);
    let settled = daemon.wait_settled(&tab_id, 4);
    assert_eq!(settled["tab"]["status"].as_str(), Some("error"));
    assert_eq!(
        settled["tab"]["cwd"].as_str(),
        Some(project.to_string_lossy().as_ref()),
        "tab.cwd must not be overwritten with $HOME on missing cwd"
    );
    let msg2 = &settled["messages"].as_array().unwrap()[3];
    assert_eq!(msg2["status"].as_str(), Some("error"));
    assert_eq!(
        msg2["error"].as_str(),
        Some(format!("Working directory does not exist: {}", project.display()).as_str())
    );

    drop(daemon);
    let _ = fs::remove_dir_all(&home);
}

#[test]
fn issue_50_shell_exit_codes_and_no_empty_code_blocks() {
    let home = make_temp_dir("agentsterm-rs-50");
    let daemon = TestDaemon::start(&home, None);
    let tab_id = daemon.create_tab("shell", &home);
    let msgs_path = format!("/api/tabs/{tab_id}/messages");

    // 1) Silent exit-0 command (`true`): exitCode 0, status complete, error null, empty text.
    assert_eq!(
        daemon
            .api("POST", &msgs_path, Some(json!({ "text": "true" })))
            .0,
        200
    );
    let settled = daemon.wait_settled(&tab_id, 2);
    assert_eq!(settled["tab"]["status"].as_str(), Some("idle"));
    let m1 = &settled["messages"].as_array().unwrap()[1];
    assert_eq!(m1["exitCode"].as_i64(), Some(0));
    assert_eq!(m1["status"].as_str(), Some("complete"));
    assert!(m1["error"].is_null(), "expected null error: {m1}");
    assert_eq!(m1["text"].as_str(), Some(""));
    assert!(m1["segments"].as_array().unwrap().is_empty());

    // 2) Silent non-zero command (`false`): exitCode 1, status error, error "Exited with code 1", empty text, tab stays idle.
    assert_eq!(
        daemon
            .api("POST", &msgs_path, Some(json!({ "text": "false" })))
            .0,
        200
    );
    let settled = daemon.wait_settled(&tab_id, 4);
    assert_eq!(settled["tab"]["status"].as_str(), Some("idle"));
    let m2 = &settled["messages"].as_array().unwrap()[3];
    assert_eq!(m2["exitCode"].as_i64(), Some(1));
    assert_eq!(m2["status"].as_str(), Some("error"));
    assert_eq!(m2["error"].as_str(), Some("Exited with code 1"));
    assert_eq!(m2["text"].as_str(), Some(""));
    assert!(m2["segments"].as_array().unwrap().is_empty());

    // 3) Failing command with stderr output still records exitCode, status error, and output.
    assert_eq!(
        daemon
            .api(
                "POST",
                &msgs_path,
                Some(json!({ "text": "echo boom >&2; (exit 3)" }))
            )
            .0,
        200
    );
    let settled = daemon.wait_settled(&tab_id, 6);
    assert_eq!(settled["tab"]["status"].as_str(), Some("idle"));
    let m3 = &settled["messages"].as_array().unwrap()[5];
    assert_eq!(m3["exitCode"].as_i64(), Some(3));
    assert_eq!(m3["status"].as_str(), Some("error"));
    assert_eq!(m3["error"].as_str(), Some("Exited with code 3"));
    assert!(m3["text"].as_str().unwrap_or("").contains("boom"));

    drop(daemon);
    let _ = fs::remove_dir_all(&home);
}

#[test]
fn issue_51_persistent_bash_session_stdin_and_cleanup() {
    let home = make_temp_dir("agentsterm-rs-51");
    let project = home.join("project");
    fs::create_dir_all(&project).unwrap();
    let daemon = TestDaemon::start(&home, None);

    // Accurate shell description in /api/info.
    let (st, info) = daemon.api("GET", "/api/info", None);
    assert_eq!(st, 200);
    let shell_spec = info["agents"]
        .as_array()
        .unwrap()
        .iter()
        .find(|a| a["type"].as_str() == Some("shell"))
        .unwrap();
    assert_eq!(
        shell_spec["description"].as_str(),
        Some("Persistent bash (keeps directory, env vars, and stdin)")
    );

    let tab_id = daemon.create_tab("shell", &home);
    let msgs_path = format!("/api/tabs/{tab_id}/messages");
    let stdin_path = format!("/api/tabs/{tab_id}/stdin");

    // Stdin API error handling when idle / bad request / missing tab.
    let (st, err) = daemon.api(
        "POST",
        "/api/tabs/tab_missing/stdin",
        Some(json!({ "data": "x\n" })),
    );
    assert_eq!(st, 404, "{err}");
    let (st, err) = daemon.api("POST", &stdin_path, Some(json!({})));
    assert_eq!(st, 400, "{err}");
    let (st, err) = daemon.api("POST", &stdin_path, Some(json!({ "data": "x\n" })));
    assert_eq!(st, 409, "{err}");
    assert_eq!(
        err["error"].as_str(),
        Some("No running shell command accepts stdin")
    );

    // 1) cd + export + local variable + shell function persist across turns.
    assert_eq!(
        daemon
            .api(
                "POST",
                &msgs_path,
                Some(json!({ "text": "cd project && export SHELL_AUDIT_COLOR=blue && LOCAL_VAL=99 && myfn() { echo \"fn:$1\"; }" }))
            )
            .0,
        200
    );
    let settled = daemon.wait_settled(&tab_id, 2);
    assert_eq!(settled["tab"]["status"].as_str(), Some("idle"));
    assert_eq!(
        settled["tab"]["cwd"].as_str(),
        Some(project.to_string_lossy().as_ref())
    );
    let m1 = &settled["messages"].as_array().unwrap()[1];
    assert_eq!(m1["exitCode"].as_i64(), Some(0));
    assert_eq!(m1["text"].as_str(), Some(""));

    assert_eq!(
        daemon
            .api(
                "POST",
                &msgs_path,
                Some(json!({ "text": "pwd && printenv SHELL_AUDIT_COLOR && echo \"local=$LOCAL_VAL\" && myfn ok" }))
            )
            .0,
        200
    );
    let settled = daemon.wait_settled(&tab_id, 4);
    let m2 = &settled["messages"].as_array().unwrap()[3];
    let m2_text = m2["text"].as_str().unwrap_or("");
    assert_eq!(m2["exitCode"].as_i64(), Some(0));
    assert!(
        m2_text.contains(project.to_string_lossy().as_ref()),
        "{m2_text}"
    );
    assert!(m2_text.contains("blue"), "{m2_text}");
    assert!(m2_text.contains("local=99"), "{m2_text}");
    assert!(m2_text.contains("fn:ok"), "{m2_text}");

    // 2) Interactive stdin: read -r answer blocks until POST /stdin sends "hello\n".
    assert_eq!(
        daemon
            .api(
                "POST",
                &msgs_path,
                Some(json!({ "text": "read -r answer && echo \"got:$answer\"" }))
            )
            .0,
        200
    );
    thread::sleep(Duration::from_millis(150));
    let (_, mid) = daemon.api("GET", &msgs_path, None);
    assert_eq!(
        mid["tab"]["status"].as_str(),
        Some("working"),
        "read -r should stay blocked waiting for stdin"
    );
    let (st, stdin_res) = daemon.api("POST", &stdin_path, Some(json!({ "data": "hello\n" })));
    assert_eq!(st, 200, "{stdin_res}");
    assert_eq!(stdin_res["ok"].as_bool(), Some(true));
    let settled = daemon.wait_settled(&tab_id, 6);
    let m3 = &settled["messages"].as_array().unwrap()[5];
    assert_eq!(m3["exitCode"].as_i64(), Some(0));
    assert!(
        m3["text"].as_str().unwrap_or("").contains("got:hello"),
        "{m3}"
    );

    // 3) Interactive stdin EOF unblocks read.
    assert_eq!(
        daemon
            .api(
                "POST",
                &msgs_path,
                Some(json!({ "text": "read -r answer || echo stdin-closed" }))
            )
            .0,
        200
    );
    thread::sleep(Duration::from_millis(150));
    let (st, eof_res) = daemon.api("POST", &stdin_path, Some(json!({ "eof": true })));
    assert_eq!(st, 200, "{eof_res}");
    assert_eq!(eof_res["ok"].as_bool(), Some(true));
    let settled = daemon.wait_settled(&tab_id, 8);
    let m4 = &settled["messages"].as_array().unwrap()[7];
    assert_eq!(m4["exitCode"].as_i64(), Some(0));
    assert!(
        m4["text"].as_str().unwrap_or("").contains("stdin-closed"),
        "{m4}"
    );

    // 4) `exit 7` reports exact exit code 7 and automatically spawns a fresh bash on next turn.
    assert_eq!(
        daemon
            .api("POST", &msgs_path, Some(json!({ "text": "exit 7" })))
            .0,
        200
    );
    let settled = daemon.wait_settled(&tab_id, 10);
    assert_eq!(settled["tab"]["status"].as_str(), Some("idle"));
    let m5 = &settled["messages"].as_array().unwrap()[9];
    assert_eq!(m5["exitCode"].as_i64(), Some(7));
    assert_eq!(m5["status"].as_str(), Some("error"));
    assert_eq!(m5["error"].as_str(), Some("Exited with code 7"));

    assert_eq!(
        daemon
            .api(
                "POST",
                &msgs_path,
                Some(json!({ "text": "pwd && echo respawned" }))
            )
            .0,
        200
    );
    let settled = daemon.wait_settled(&tab_id, 12);
    let m6 = &settled["messages"].as_array().unwrap()[11];
    assert_eq!(m6["exitCode"].as_i64(), Some(0));
    assert!(
        m6["text"].as_str().unwrap_or("").contains("respawned"),
        "{m6}"
    );
    assert!(
        m6["text"]
            .as_str()
            .unwrap_or("")
            .contains(project.to_string_lossy().as_ref()),
        "respawned shell should start in tab.cwd: {m6}"
    );

    // 5) Closing the tab cleans up the session temp directory.
    let tmp_root = home.join(".agents-term/tmp");
    let has_shell_dir = || {
        fs::read_dir(&tmp_root)
            .ok()
            .map(|it| {
                it.flatten().any(|e| {
                    e.file_name()
                        .to_string_lossy()
                        .starts_with(&format!("shell-{tab_id}-"))
                })
            })
            .unwrap_or(false)
    };
    assert!(has_shell_dir(), "expected active persistent shell temp dir");
    assert_eq!(
        daemon.api("DELETE", &format!("/api/tabs/{tab_id}"), None).0,
        200
    );
    assert!(
        !has_shell_dir(),
        "closing shell tab must clean up persistent shell temp dir"
    );

    drop(daemon);
    let _ = fs::remove_dir_all(&home);
}

#[test]
fn issue_53_shell_queue_preserves_submission_boundaries() {
    let home = make_temp_dir("agentsterm-rs-53");
    let work = home.join("work");
    fs::create_dir_all(&work).unwrap();
    let daemon = TestDaemon::start(&home, None);
    let tab_id = daemon.create_tab("shell", &work);
    let msgs_path = format!("/api/tabs/{tab_id}/messages");

    // Start a command that signals readiness then sleeps briefly.
    let (st, _) = daemon.api(
        "POST",
        &msgs_path,
        Some(json!({ "text": "touch started && sleep 0.6 && echo first-done" })),
    );
    assert_eq!(st, 200);

    let started = work.join("started");
    let deadline = Instant::now() + Duration::from_secs(5);
    while !started.exists() && Instant::now() < deadline {
        thread::sleep(Duration::from_millis(25));
    }
    assert!(started.exists(), "first shell command never started");

    // Queue two separate commands while the first is running.
    let (st, q1) = daemon.api("POST", &msgs_path, Some(json!({ "text": "set -e; false" })));
    assert_eq!(st, 200);
    assert_eq!(q1["queued"].as_bool(), Some(true));
    assert_eq!(q1["tab"]["queuedMessages"].as_array().unwrap().len(), 1);

    let (st, q2) = daemon.api(
        "POST",
        &msgs_path,
        Some(json!({ "text": "echo second-ran" })),
    );
    assert_eq!(st, 200);
    assert_eq!(q2["queued"].as_bool(), Some(true));
    assert_eq!(q2["tab"]["queuedMessages"].as_array().unwrap().len(), 2);

    // Wait until all 3 turns (6 messages) finish and queue is completely drained.
    let settled = daemon.wait_settled(&tab_id, 6);
    assert_eq!(settled["tab"]["status"].as_str(), Some("idle"));
    assert!(settled["tab"]["queuedMessages"]
        .as_array()
        .unwrap()
        .is_empty());

    let msgs = settled["messages"].as_array().unwrap();
    assert_eq!(msgs.len(), 6, "expected 3 separate turns: {msgs:?}");

    // Turn 1
    assert_eq!(msgs[0]["role"].as_str(), Some("user"));
    assert_eq!(msgs[1]["exitCode"].as_i64(), Some(0));
    assert_eq!(msgs[1]["status"].as_str(), Some("complete"));
    assert!(msgs[1]["text"]
        .as_str()
        .unwrap_or("")
        .contains("first-done"));

    // Turn 2: `set -e; false` ran as its own turn, exited 1, and did NOT swallow Turn 3.
    assert_eq!(msgs[2]["role"].as_str(), Some("user"));
    assert_eq!(msgs[2]["text"].as_str(), Some("set -e; false"));
    assert_eq!(msgs[3]["exitCode"].as_i64(), Some(1));
    assert_eq!(msgs[3]["status"].as_str(), Some("error"));
    assert_eq!(msgs[3]["error"].as_str(), Some("Exited with code 1"));
    assert_eq!(msgs[3]["text"].as_str(), Some(""));

    // Turn 3: `echo second-ran` ran cleanly in its own turn after the failing turn.
    assert_eq!(msgs[4]["role"].as_str(), Some("user"));
    assert_eq!(msgs[4]["text"].as_str(), Some("echo second-ran"));
    assert_eq!(msgs[5]["exitCode"].as_i64(), Some(0));
    assert_eq!(msgs[5]["status"].as_str(), Some("complete"));
    assert!(msgs[5]["error"].is_null());
    assert!(msgs[5]["text"]
        .as_str()
        .unwrap_or("")
        .contains("second-ran"));

    drop(daemon);
    let _ = fs::remove_dir_all(&home);
}

struct TestWsClient {
    stream: TcpStream,
}

enum TestWsFrame {
    Text(String),
    Binary(Vec<u8>),
    Close,
}

impl TestWsClient {
    fn connect(port: u16, path_and_query: &str, token: Option<&str>) -> Result<Self, u16> {
        let mut stream = TcpStream::connect(("127.0.0.1", port)).expect("connect ws");
        stream
            .set_read_timeout(Some(Duration::from_secs(5)))
            .unwrap();
        let mut req = format!(
            "GET {path_and_query} HTTP/1.1\r\n\
             Host: 127.0.0.1:{port}\r\n\
             Upgrade: websocket\r\n\
             Connection: Upgrade\r\n\
             Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\
             Sec-WebSocket-Version: 13\r\n"
        );
        if let Some(tok) = token {
            req.push_str(&format!("Authorization: Bearer {tok}\r\n"));
        }
        req.push_str("\r\n");
        stream.write_all(req.as_bytes()).unwrap();
        stream.flush().unwrap();

        let mut header_buf = Vec::new();
        let mut b = [0u8; 1];
        while !header_buf.ends_with(b"\r\n\r\n") {
            stream.read_exact(&mut b).unwrap();
            header_buf.push(b[0]);
        }
        let header_str = String::from_utf8_lossy(&header_buf);
        let status: u16 = header_str
            .lines()
            .next()
            .unwrap_or("")
            .split_whitespace()
            .nth(1)
            .and_then(|s| s.parse().ok())
            .unwrap_or(0);
        if status != 101 {
            return Err(status);
        }
        Ok(Self { stream })
    }

    fn send_frame(&mut self, opcode: u8, payload: &[u8]) {
        let mask = [0x12u8, 0x34, 0x56, 0x78];
        let mut out = Vec::with_capacity(14 + payload.len());
        out.push(0x80 | opcode);
        let len = payload.len();
        if len < 126 {
            out.push(0x80 | (len as u8));
        } else if len <= 65535 {
            out.push(0x80 | 126);
            out.extend_from_slice(&(len as u16).to_be_bytes());
        } else {
            out.push(0x80 | 127);
            out.extend_from_slice(&(len as u64).to_be_bytes());
        }
        out.extend_from_slice(&mask);
        for (i, byte) in payload.iter().enumerate() {
            out.push(byte ^ mask[i % 4]);
        }
        self.stream.write_all(&out).unwrap();
        self.stream.flush().unwrap();
    }

    fn send_text(&mut self, text: &str) {
        self.send_frame(0x1, text.as_bytes());
    }

    fn send_binary(&mut self, data: &[u8]) {
        self.send_frame(0x2, data);
    }

    fn read_frame(&mut self) -> Option<TestWsFrame> {
        loop {
            let mut hdr = [0u8; 2];
            if self.stream.read_exact(&mut hdr).is_err() {
                return None;
            }
            let opcode = hdr[0] & 0x0F;
            let masked = (hdr[1] & 0x80) != 0;
            let mut len = (hdr[1] & 0x7F) as u64;
            if len == 126 {
                let mut ext = [0u8; 2];
                self.stream.read_exact(&mut ext).ok()?;
                len = u16::from_be_bytes(ext) as u64;
            } else if len == 127 {
                let mut ext = [0u8; 8];
                self.stream.read_exact(&mut ext).ok()?;
                len = u64::from_be_bytes(ext);
            }
            let mask_key = if masked {
                let mut m = [0u8; 4];
                self.stream.read_exact(&mut m).ok()?;
                Some(m)
            } else {
                None
            };
            let mut payload = vec![0u8; len as usize];
            if len > 0 {
                self.stream.read_exact(&mut payload).ok()?;
            }
            if let Some(m) = mask_key {
                for (i, byte) in payload.iter_mut().enumerate() {
                    *byte ^= m[i % 4];
                }
            }
            match opcode {
                0x1 => {
                    return Some(TestWsFrame::Text(
                        String::from_utf8_lossy(&payload).into_owned(),
                    ))
                }
                0x2 => return Some(TestWsFrame::Binary(payload)),
                0x8 => return Some(TestWsFrame::Close),
                0x9 => self.send_frame(0xA, &payload),
                _ => continue,
            }
        }
    }
}

fn read_pty_until_contains(
    ws: &mut TestWsClient,
    last_seq: &mut u64,
    needle: &str,
    timeout: Duration,
) -> String {
    let deadline = Instant::now() + timeout;
    let mut text = String::new();
    while Instant::now() < deadline {
        ws.stream
            .set_read_timeout(Some(Duration::from_millis(250)))
            .unwrap();
        if let Some(TestWsFrame::Binary(frame)) = ws.read_frame() {
            assert!(frame.len() >= 8);
            let seq = u64::from_be_bytes(frame[..8].try_into().unwrap());
            let chunk = &frame[8..];
            *last_seq = (*last_seq).max(seq + chunk.len() as u64);
            text.push_str(&String::from_utf8_lossy(chunk));
            if text.contains(needle) {
                return text;
            }
        }
    }
    panic!("Timed out waiting for PTY output containing {needle:?}; got: {text:?}");
}

#[test]
fn pty_shell_tab_autocomplete_ctrl_c_no_echo_resize_reconnect_and_restart() {
    let home = make_temp_dir("herdr-pty-home");
    let work = home.join("work");
    fs::create_dir_all(&work).unwrap();
    fs::write(work.join("my spaced file.txt"), "hello").unwrap();

    let daemon = TestDaemon::start(&home, None);

    // 1. /api/info advertises PTY capability.
    let (st, info) = daemon.api("GET", "/api/info", None);
    assert_eq!(st, 200);
    assert_eq!(info["pty"]["version"].as_u64(), Some(1));
    assert!(info["pty"]["bufferBytes"].as_u64().unwrap_or(0) >= 1024 * 1024);

    // 2. Default shell tab retains Command mode; invalid shellMode is rejected.
    let (st, cmd_tab) = daemon.api(
        "POST",
        "/api/tabs",
        Some(json!({ "agent": "shell", "cwd": work.to_string_lossy() })),
    );
    assert_eq!(st, 200);
    assert_eq!(cmd_tab["tab"]["shellMode"].as_str(), Some("command"));
    let cmd_tab_id = cmd_tab["tab"]["id"].as_str().unwrap().to_string();

    let (st, _) = daemon.api(
        "POST",
        "/api/tabs",
        Some(json!({ "agent": "shell", "shellMode": "bogus", "cwd": work.to_string_lossy() })),
    );
    assert_eq!(st, 400);

    // 3. Opt-in PTY shell tab persists shellMode="pty" and rejects Command message/stdin APIs.
    let (st, pty_tab) = daemon.api(
        "POST",
        "/api/tabs",
        Some(json!({
            "agent": "shell",
            "shellMode": "pty",
            "cwd": work.to_string_lossy(),
            "title": "PTY Test"
        })),
    );
    assert_eq!(st, 200);
    assert_eq!(pty_tab["tab"]["shellMode"].as_str(), Some("pty"));
    let pty_tab_id = pty_tab["tab"]["id"].as_str().unwrap().to_string();

    let (st, _) = daemon.api(
        "POST",
        &format!("/api/tabs/{pty_tab_id}/messages"),
        Some(json!({ "text": "echo nope" })),
    );
    assert_eq!(st, 400);
    let (st, _) = daemon.api(
        "POST",
        &format!("/api/tabs/{pty_tab_id}/stdin"),
        Some(json!({ "data": "nope\n" })),
    );
    assert_eq!(st, 400);
    let (st, _) = daemon.api(
        "POST",
        &format!("/api/tabs/{pty_tab_id}/interrupt"),
        Some(json!({ "text": "echo bypassed", "processQueue": true })),
    );
    assert_eq!(st, 400);
    let (_, msgs_resp) = daemon.api("GET", &format!("/api/tabs/{pty_tab_id}/messages"), None);
    assert!(
        msgs_resp["messages"].as_array().unwrap().is_empty(),
        "PTY tab must never persist messages from rejected interrupt: {msgs_resp}"
    );

    // 4. PTY WS enforces auth and rejects Command-mode tabs.
    assert_eq!(
        TestWsClient::connect(daemon.port, &format!("/api/tabs/{pty_tab_id}/pty"), None).err(),
        Some(401)
    );
    assert_eq!(
        TestWsClient::connect(
            daemon.port,
            &format!("/api/tabs/{cmd_tab_id}/pty"),
            Some(&daemon.token),
        )
        .err(),
        Some(400)
    );

    // 5. Connect to PTY WS, read ready + replay_end.
    let mut ws = TestWsClient::connect(
        daemon.port,
        &format!("/api/tabs/{pty_tab_id}/pty?afterSeq=0&cols=80&rows=24"),
        Some(&daemon.token),
    )
    .expect("connect pty ws");

    let ready_val: Value = match ws.read_frame().unwrap() {
        TestWsFrame::Text(s) => serde_json::from_str(&s).unwrap(),
        _ => panic!("expected ready text frame"),
    };
    assert_eq!(ready_val["type"].as_str(), Some("ready"));
    assert_eq!(ready_val["running"].as_bool(), Some(true));
    assert_eq!(ready_val["cols"].as_u64(), Some(80));
    assert_eq!(ready_val["rows"].as_u64(), Some(24));
    let session_id = ready_val["sessionId"].as_str().unwrap().to_string();

    let mut last_seq = ready_val["replayFrom"].as_u64().unwrap_or(0);
    loop {
        match ws.read_frame().unwrap() {
            TestWsFrame::Binary(frame) => {
                let seq = u64::from_be_bytes(frame[..8].try_into().unwrap());
                last_seq = last_seq.max(seq + (frame.len() - 8) as u64);
            }
            TestWsFrame::Text(s) => {
                let v: Value = serde_json::from_str(&s).unwrap();
                if v["type"].as_str() == Some("replay_end") {
                    break;
                }
            }
            TestWsFrame::Close => panic!("unexpected close before replay_end"),
        }
    }

    // 6. Tab-complete a filename containing spaces!
    ws.send_binary(b"ls my\t");
    let completed =
        read_pty_until_contains(&mut ws, &mut last_seq, "spaced", Duration::from_secs(5));
    assert!(completed.contains("file.txt") || completed.contains("spaced"));
    ws.send_binary(b"\n");
    let _ = read_pty_until_contains(
        &mut ws,
        &mut last_seq,
        "my spaced file.txt",
        Duration::from_secs(5),
    );

    // 7. Ctrl+C interrupts a running foreground sleep.
    ws.send_binary(b"sleep 30\n");
    thread::sleep(Duration::from_millis(200));
    ws.send_binary(&[0x03]);
    ws.send_binary(b"echo AFTER_SIGINT\n");
    let _ = read_pty_until_contains(
        &mut ws,
        &mut last_seq,
        "AFTER_SIGINT",
        Duration::from_secs(5),
    );

    // 8. No-echo secret input is NOT echoed back in PTY output.
    ws.send_binary(b"stty -echo; read -r s; stty echo; printf 'LEN=%d\\n' \"${#s}\"\n");
    thread::sleep(Duration::from_millis(200));
    ws.send_binary(b"disposable-secret-141\n");
    let no_echo_out =
        read_pty_until_contains(&mut ws, &mut last_seq, "LEN=21", Duration::from_secs(5));
    assert!(
        !no_echo_out.contains("disposable-secret-141"),
        "No-echo secret must not appear in PTY output: {no_echo_out:?}"
    );

    // 9. Resize updates PTY dimensions.
    ws.send_text(&json!({ "type": "resize", "cols": 110, "rows": 35 }).to_string());
    thread::sleep(Duration::from_millis(100));
    ws.send_binary(b"stty size\n");
    let _ = read_pty_until_contains(&mut ws, &mut last_seq, "35 110", Duration::from_secs(5));

    // 10. Disconnect and warm reconnect from last_seq with sessionId (no truncation!).
    drop(ws);
    let mut ws2 = TestWsClient::connect(
        daemon.port,
        &format!(
            "/api/tabs/{pty_tab_id}/pty?afterSeq={last_seq}&sessionId={session_id}&cols=110&rows=35"
        ),
        Some(&daemon.token),
    )
    .expect("warm reconnect pty ws");
    let ready2: Value = match ws2.read_frame().unwrap() {
        TestWsFrame::Text(s) => serde_json::from_str(&s).unwrap(),
        _ => panic!("expected ready2"),
    };
    assert_eq!(ready2["sessionId"].as_str(), Some(session_id.as_str()));
    assert_eq!(ready2["truncated"].as_bool(), Some(false));
    assert_eq!(ready2["replayFrom"].as_u64(), Some(last_seq));

    loop {
        if let TestWsFrame::Text(s) = ws2.read_frame().unwrap() {
            let v: Value = serde_json::from_str(&s).unwrap();
            if v["type"].as_str() == Some("replay_end") {
                break;
            }
        }
    }
    ws2.send_binary(b"exit 0\n");
    let exit_deadline = Instant::now() + Duration::from_secs(5);
    let mut saw_exit = false;
    while Instant::now() < exit_deadline {
        if let Some(TestWsFrame::Text(s)) = ws2.read_frame() {
            let v: Value = serde_json::from_str(&s).unwrap();
            if v["type"].as_str() == Some("exit") {
                assert_eq!(v["exitCode"].as_i64(), Some(0));
                saw_exit = true;
                break;
            }
        }
    }
    assert!(saw_exit, "expected exit frame after `exit 0`");

    ws2.send_text(&json!({ "type": "restart", "cols": 80, "rows": 24 }).to_string());
    let ready3: Value = match ws2.read_frame().unwrap() {
        TestWsFrame::Text(s) => serde_json::from_str(&s).unwrap(),
        _ => panic!("expected ready3 after restart"),
    };
    assert_eq!(ready3["type"].as_str(), Some("ready"));
    assert_eq!(ready3["running"].as_bool(), Some(true));
    assert_ne!(ready3["sessionId"].as_str(), Some(session_id.as_str()));

    drop(ws2);
    let (st, _) = daemon.api("DELETE", &format!("/api/tabs/{pty_tab_id}"), None);
    assert_eq!(st, 200);

    drop(daemon);
    let _ = fs::remove_dir_all(&home);
}

#[test]
fn pty_ws_query_restart_trailing_output_and_delete_kills_jobs() {
    let home = make_temp_dir("herdr-pty-audit-fixes");
    let work = home.join("work");
    fs::create_dir_all(&work).unwrap();
    let daemon = TestDaemon::start(&home, None);

    let (st, created) = daemon.api(
        "POST",
        "/api/tabs",
        Some(json!({
            "agent": "shell",
            "shellMode": "pty",
            "cwd": work.to_string_lossy(),
            "title": "PTY Lifecycle"
        })),
    );
    assert_eq!(st, 200);
    let tab_id = created["tab"]["id"].as_str().unwrap().to_string();

    // 1. Connect, emit trailing output right before exit 0, and verify output arrives BEFORE exit.
    let mut ws = TestWsClient::connect(
        daemon.port,
        &format!("/api/tabs/{tab_id}/pty?afterSeq=0&cols=80&rows=24"),
        Some(&daemon.token),
    )
    .expect("connect pty ws");
    let ready1: Value = match ws.read_frame().unwrap() {
        TestWsFrame::Text(s) => serde_json::from_str(&s).unwrap(),
        _ => panic!("expected ready1"),
    };
    let sess1 = ready1["sessionId"].as_str().unwrap().to_string();
    loop {
        if let TestWsFrame::Text(s) = ws.read_frame().unwrap() {
            let v: Value = serde_json::from_str(&s).unwrap();
            if v["type"].as_str() == Some("replay_end") {
                break;
            }
        }
    }

    ws.send_binary(b"echo BYE_BEFORE_EXIT_141; exit 0\n");
    let mut output_before_exit = String::new();
    let mut saw_exit = false;
    let deadline = Instant::now() + Duration::from_secs(5);
    while Instant::now() < deadline {
        match ws.read_frame() {
            Some(TestWsFrame::Binary(frame)) => {
                output_before_exit.push_str(&String::from_utf8_lossy(&frame[8..]));
            }
            Some(TestWsFrame::Text(s)) => {
                let v: Value = serde_json::from_str(&s).unwrap();
                if v["type"].as_str() == Some("exit") {
                    assert_eq!(v["exitCode"].as_i64(), Some(0));
                    saw_exit = true;
                    break;
                }
            }
            _ => break,
        }
    }
    assert!(saw_exit, "expected exit frame");
    assert!(
        output_before_exit.contains("BYE_BEFORE_EXIT_141"),
        "expected trailing output before exit frame, got: {output_before_exit:?}"
    );
    drop(ws);

    // 2. Reconnect to the exited session with `?restart=1`: must spawn a fresh running session.
    let mut ws2 = TestWsClient::connect(
        daemon.port,
        &format!("/api/tabs/{tab_id}/pty?restart=1&cols=90&rows=30"),
        Some(&daemon.token),
    )
    .expect("connect pty ws with restart=1");
    let ready2: Value = match ws2.read_frame().unwrap() {
        TestWsFrame::Text(s) => serde_json::from_str(&s).unwrap(),
        _ => panic!("expected ready2"),
    };
    assert_eq!(ready2["type"].as_str(), Some("ready"));
    assert_eq!(ready2["running"].as_bool(), Some(true));
    assert_eq!(ready2["cols"].as_u64(), Some(90));
    assert_eq!(ready2["rows"].as_u64(), Some(30));
    assert_ne!(ready2["sessionId"].as_str(), Some(sess1.as_str()));

    let mut last_seq = ready2["replayFrom"].as_u64().unwrap_or(0);
    loop {
        match ws2.read_frame().unwrap() {
            TestWsFrame::Binary(frame) => {
                let seq = u64::from_be_bytes(frame[..8].try_into().unwrap());
                last_seq = last_seq.max(seq + (frame.len() - 8) as u64);
            }
            TestWsFrame::Text(s) => {
                let v: Value = serde_json::from_str(&s).unwrap();
                if v["type"].as_str() == Some("replay_end") {
                    break;
                }
            }
            TestWsFrame::Close => panic!("unexpected close before replay_end"),
        }
    }

    // 3. Start a foreground `sleep 60` job and verify deleting the tab terminates it cleanly.
    let pid_file = work.join("sleep.pid");
    let cmd = format!("sleep 60 & echo $! > {}; fg || wait\n", pid_file.display());
    ws2.send_binary(cmd.as_bytes());
    let pid_deadline = Instant::now() + Duration::from_secs(5);
    let mut sleep_pid: Option<i32> = None;
    while Instant::now() < pid_deadline {
        if let Ok(raw) = fs::read_to_string(&pid_file) {
            if let Ok(p) = raw.trim().parse::<i32>() {
                sleep_pid = Some(p);
                break;
            }
        }
        thread::sleep(Duration::from_millis(50));
    }
    let sleep_pid = sleep_pid.expect("sleep.pid never written");
    assert_eq!(
        unsafe { libc::kill(sleep_pid as libc::pid_t, 0) },
        0,
        "sleep process should be alive before DELETE"
    );

    let (st, _) = daemon.api("DELETE", &format!("/api/tabs/{tab_id}"), None);
    assert_eq!(st, 200);

    // Confirm `sleep 60` dies promptly after tab deletion.
    let kill_deadline = Instant::now() + Duration::from_secs(3);
    loop {
        let alive = fs::read_to_string(format!("/proc/{sleep_pid}/stat"))
            .ok()
            .and_then(|raw| {
                raw.rfind(')')
                    .and_then(|i| raw.get(i + 1..))
                    .map(str::to_string)
            })
            .and_then(|rest| rest.split_ascii_whitespace().next().map(str::to_string))
            .is_some_and(|state| state != "Z" && state != "X");
        if !alive {
            break;
        }
        assert!(
            Instant::now() < kill_deadline,
            "sleep job (pid {sleep_pid}) survived DELETE /api/tabs/:id"
        );
        thread::sleep(Duration::from_millis(50));
    }

    // Suppress unused warning on last_seq if compiler checks.
    let _ = last_seq;
    drop(ws2);
    drop(daemon);
    let _ = fs::remove_dir_all(&home);
}
