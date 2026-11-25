use serde_json::{json, Value};
use std::fs;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::os::unix::fs::PermissionsExt;
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

struct TestDaemon {
    child: Child,
    port: u16,
    token: String,
}

impl TestDaemon {
    fn start(home: &Path, extra_bin: Option<&Path>) -> Self {
        let port = free_port();
        let mut cmd = Command::new(bin_path());
        cmd.args(["serve", "--host", "127.0.0.1", "--port", &port.to_string()])
            .env("HOME", home)
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());
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

        let cfg: Value = serde_json::from_str(
            &fs::read_to_string(home.join(".config/herdr-mobile/config.json")).unwrap(),
        )
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
    let config = home.join(".config/herdr-mobile");
    fs::create_dir_all(&config).unwrap();
    let tabs: Vec<_> = (1..=11)
        .map(|id| json!({ "id": format!("old_{id}"), "agent": "claude", "status": "done" }))
        .collect();
    let history = json!([{ "id": "msg_1", "role": "user", "text": "Saved conversation" }]);
    let saved =
        json!({ "tabs": tabs, "activeTabId": "old_11", "messagesByTab": { "old_11": history } });
    fs::write(config.join("state.json"), saved.to_string()).unwrap();

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

    // Wait until sessionRef appears on disk WHILE the turn is still streaming (#62).
    let state_file = home.join(".config/herdr-mobile/state.json");
    let deadline = Instant::now() + Duration::from_secs(5);
    let mut found_on_disk = false;
    while Instant::now() < deadline {
        if let Ok(raw) = fs::read_to_string(&state_file) {
            if let Ok(parsed) = serde_json::from_str::<Value>(&raw) {
                let sref = parsed["tabs"]
                    .as_array()
                    .and_then(|a| a.first())
                    .and_then(|t| t["sessionRef"]["value"].as_str());
                if sref == Some("sess-midturn-62") {
                    found_on_disk = true;
                    break;
                }
            }
        }
        thread::sleep(Duration::from_millis(50));
    }
    assert!(
        found_on_disk,
        "sessionRef was not persisted to state.json mid-turn (#62)"
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
    let config = home.join(".config/herdr-mobile");
    fs::create_dir_all(&config).unwrap();
    let history = json!([
        { "id": "m1", "tabId": "tab_save", "role": "user", "text": "Fix login bug", "status": "complete" },
        { "id": "m2", "tabId": "tab_save", "role": "assistant", "text": "Fixed login bug.", "status": "complete" }
    ]);
    let saved = json!({
        "tabs": [{
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
        }],
        "activeTabId": "tab_save",
        "messagesByTab": { "tab_save": history }
    });
    fs::write(config.join("state.json"), saved.to_string()).unwrap();

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
    let config = home.join(".config/herdr-mobile");
    fs::create_dir_all(&config).unwrap();

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

    let saved = json!({
        "tabs": [{
            "id": "open_1",
            "title": "Open 1",
            "agent": "shell",
            "cwd": home.to_string_lossy(),
            "status": "idle"
        }],
        "activeTabId": "open_1",
        "closedTabs": closed_tabs,
        "messagesByTab": messages_by_tab,
    });
    fs::write(config.join("state.json"), saved.to_string()).unwrap();

    let daemon = TestDaemon::start(&home, None);
    let (st, closed_resp) = daemon.api("GET", "/api/tabs/closed", None);
    assert_eq!(st, 200);
    let arr = closed_resp["closedTabs"].as_array().unwrap();
    assert_eq!(arr.len(), 50);
    assert_eq!(arr[0]["id"].as_str(), Some("recent_01"));
    assert_eq!(arr[49]["id"].as_str(), Some("recent_50"));

    // Expired and >50 evicted tabs cannot be revived and had their messages purged from disk.
    for discarded in ["expired_25h", "expired_24h", "recent_51", "recent_55"] {
        let (rev_st, _) = daemon.api(
            "POST",
            &format!("/api/tabs/closed/{discarded}/revive"),
            Some(json!({})),
        );
        assert_eq!(rev_st, 404, "expected {discarded} to be discarded forever");
    }

    let persisted: Value =
        serde_json::from_str(&fs::read_to_string(config.join("state.json")).unwrap()).unwrap();
    let msgs_map = persisted["messagesByTab"].as_object().unwrap();
    assert!(!msgs_map.contains_key("expired_25h"));
    assert!(!msgs_map.contains_key("expired_24h"));
    assert!(!msgs_map.contains_key("recent_51"));
    assert!(msgs_map.contains_key("recent_50"));

    // Closing one more open tab pushes recent_50 out beyond the last 50.
    let (st, del) = daemon.api("DELETE", "/api/tabs/open_1", None);
    assert_eq!(st, 200);
    let updated_closed = del["closedTabs"].as_array().unwrap();
    assert_eq!(updated_closed.len(), 50);
    assert_eq!(updated_closed[0]["id"].as_str(), Some("open_1"));
    assert_eq!(updated_closed[49]["id"].as_str(), Some("recent_49"));

    let (rev_st, _) = daemon.api("POST", "/api/tabs/closed/recent_50/revive", Some(json!({})));
    assert_eq!(rev_st, 404);

    drop(daemon);
    let _ = fs::remove_dir_all(&home);
}

#[test]
fn revive_closed_tab_respects_open_tab_limit_without_losing_closed_tab() {
    let home = make_temp_dir("agentsterm-rs-revive-limit");
    let config = home.join(".config/herdr-mobile");
    fs::create_dir_all(&config).unwrap();

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
    let saved = json!({
        "tabs": open_tabs,
        "activeTabId": "open_1",
        "closedTabs": [{
            "id": "closed_target",
            "title": "Accidental Close",
            "agent": "claude",
            "cwd": home.to_string_lossy(),
            "status": "idle",
            "closedAt": now_ms - 5_000
        }],
        "messagesByTab": {
            "closed_target": [{ "id": "m1", "tabId": "closed_target", "role": "user", "text": "Keep me" }]
        }
    });
    fs::write(config.join("state.json"), saved.to_string()).unwrap();

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
