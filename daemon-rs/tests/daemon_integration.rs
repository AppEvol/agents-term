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

    // Verify /api/info system prompt references custom AGENTSTERM_HOME and read-only rule.
    let (st, info) = daemon.api("GET", "/api/info", None);
    assert_eq!(st, 200);
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
    let tab_a_ino_before = fs::metadata(&tab_a_msgs).unwrap().ino();
    let tab_b_ino_before = fs::metadata(&tab_b_msgs).unwrap().ino();

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
