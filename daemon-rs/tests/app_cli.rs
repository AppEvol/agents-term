//! Comprehensive integration tests for the native Rust `agents_term_app_cli` / `agentsterm-daemon app`
//! CLI, porting and expanding all regression cases previously covered by `app_state_cli/test_app_state_cli.py`.

use base64::Engine;
use serde_json::{json, Value};
use std::fs;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::TcpListener;
use std::path::{Path, PathBuf};
use std::process::{Command, Output};
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc, Mutex,
};
use std::thread;
use std::time::Duration;

const TOKEN: &str = "tok_test";
const PNG_BYTES: &[u8] = b"\x89PNG\r\n\x1a\nfake";

const EXIT_USAGE: i32 = 1;
const EXIT_DAEMON: i32 = 2;
const EXIT_NOT_READY: i32 = 3;
const EXIT_APP: i32 = 4;

fn bin_path() -> PathBuf {
    PathBuf::from(env!("CARGO_BIN_EXE_agentsterm-daemon"))
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

#[derive(Default)]
struct StubState {
    ready: bool,
    control: bool,
    old_daemon: bool,
    non_json_ok: bool,
    ui_calls: usize,
    appears_after: usize,
    requests: Vec<Value>,
}

struct StubServer {
    port: u16,
    state: Arc<Mutex<StubState>>,
    stop: Arc<AtomicBool>,
}

impl StubServer {
    fn start() -> Self {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        listener.set_nonblocking(true).unwrap();

        let state = Arc::new(Mutex::new(StubState {
            ready: true,
            control: true,
            ..Default::default()
        }));
        let stop = Arc::new(AtomicBool::new(false));

        let st_clone = Arc::clone(&state);
        let stop_clone = Arc::clone(&stop);
        thread::spawn(move || {
            while !stop_clone.load(Ordering::Relaxed) {
                match listener.accept() {
                    Ok((mut stream, _)) => {
                        let _ = stream.set_nonblocking(false);
                        let _ = stream.set_read_timeout(Some(Duration::from_secs(5)));
                        handle_conn(&mut stream, &st_clone);
                    }
                    Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                        thread::sleep(Duration::from_millis(5));
                    }
                    Err(_) => break,
                }
            }
        });

        Self { port, state, stop }
    }

    fn reset(&self) {
        let mut s = self.state.lock().unwrap();
        s.ready = true;
        s.control = true;
        s.old_daemon = false;
        s.non_json_ok = false;
        s.ui_calls = 0;
        s.appears_after = 0;
        s.requests.clear();
    }
}

impl Drop for StubServer {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
    }
}

fn write_http(stream: &mut impl Write, status: u16, body: &[u8]) {
    let reason = match status {
        200 => "OK",
        401 => "Unauthorized",
        403 => "Forbidden",
        404 => "Not Found",
        502 => "Bad Gateway",
        503 => "Service Unavailable",
        504 => "Gateway Timeout",
        _ => "Error",
    };
    let head = format!(
        "HTTP/1.1 {status} {reason}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
        body.len()
    );
    let _ = stream.write_all(head.as_bytes());
    let _ = stream.write_all(body);
    let _ = stream.flush();
}

fn handle_conn(stream: &mut std::net::TcpStream, state: &Arc<Mutex<StubState>>) {
    let mut reader = BufReader::new(stream.try_clone().unwrap());
    let mut req_line = String::new();
    if reader.read_line(&mut req_line).unwrap_or(0) == 0 {
        return;
    }
    let parts: Vec<&str> = req_line.split_whitespace().collect();
    if parts.len() < 2 {
        return;
    }
    let method = parts[0];
    let path = parts[1];

    let mut content_len: usize = 0;
    let mut auth = String::new();
    loop {
        let mut line = String::new();
        if reader.read_line(&mut line).unwrap_or(0) == 0 || line == "\r\n" || line == "\n" {
            break;
        }
        if let Some((k, v)) = line.split_once(':') {
            let kl = k.trim().to_ascii_lowercase();
            let val = v.trim();
            if kl == "content-length" {
                content_len = val.parse().unwrap_or(0);
            } else if kl == "authorization" {
                auth = val.to_string();
            }
        }
    }

    let mut body_bytes = vec![0u8; content_len];
    if content_len > 0 {
        let _ = reader.read_exact(&mut body_bytes);
    }

    if auth != format!("Bearer {TOKEN}") {
        write_http(
            stream,
            401,
            json!({ "error": "Invalid bearer token" })
                .to_string()
                .as_bytes(),
        );
        return;
    }

    let mut st = state.lock().unwrap();
    if st.old_daemon {
        write_http(
            stream,
            404,
            json!({ "error": format!("Unknown endpoint {method} {path}") })
                .to_string()
                .as_bytes(),
        );
        return;
    }
    if st.non_json_ok {
        write_http(stream, 200, b"not-json");
        return;
    }

    if method == "GET" && path == "/api/app/status" {
        let resp = json!({
            "ready": st.ready,
            "controlReady": st.ready && st.control,
            "reason": if st.ready { Value::Null } else { json!("inspection_disabled") },
            "target": Value::Null,
            "clients": []
        });
        write_http(stream, 200, resp.to_string().as_bytes());
        return;
    }

    if method != "POST" || path != "/api/app/command" {
        write_http(
            stream,
            404,
            json!({ "error": "nope" }).to_string().as_bytes(),
        );
        return;
    }

    let body: Value = serde_json::from_slice(&body_bytes).unwrap_or_else(|_| json!({}));
    st.requests.push(body.clone());

    if !st.ready {
        write_http(
            stream,
            403,
            json!({
                "ok": false,
                "code": "inspection_disabled",
                "error": "remote inspection is off"
            })
            .to_string()
            .as_bytes(),
        );
        return;
    }

    let command = body["command"].as_str().unwrap_or("");
    let args = body.get("args").cloned().unwrap_or_else(|| json!({}));

    let result = match command {
        "screenshot" => {
            let b64 = base64::engine::general_purpose::STANDARD.encode(PNG_BYTES);
            json!({ "capturedAt": 1, "pngBase64": b64 })
        }
        "ui" => {
            st.ui_calls += 1;
            let all_nodes = vec![
                json!({
                    "id": 4,
                    "window": 0,
                    "bounds": [900, 2100, 1060, 2300],
                    "center": [980, 2200],
                    "role": "Button",
                    "description": "Send",
                    "tag": "send_button",
                    "clickable": true,
                }),
                json!({
                    "id": 3,
                    "window": 1,
                    "bounds": [0, 2100, 880, 2300],
                    "center": [440, 2200],
                    "text": "Message",
                    "value": "dra\"ft",
                    "tag": "composer_input",
                    "editable": true,
                    "focused": true,
                    "enabled": false,
                    "toggle": "on",
                    "selected": false,
                }),
            ];
            let wanted = args.get("tag").and_then(Value::as_str);
            let nodes: Vec<Value> = if let Some(w) = wanted {
                all_nodes
                    .into_iter()
                    .filter(|n| n["tag"].as_str() == Some(w) && st.ui_calls >= st.appears_after)
                    .collect()
            } else {
                all_nodes
            };
            json!({
                "width": 1080,
                "height": 2400,
                "nodeCount": nodes.len(),
                "nodes": nodes,
                "windows": [
                    { "index": 0, "type": "activity" },
                    { "index": 1, "type": "popup" }
                ]
            })
        }
        "tap" | "long_press" | "swipe" | "scroll" | "type" | "key" if !st.control => {
            write_http(
                stream,
                403,
                json!({
                    "ok": false,
                    "code": "control_disabled",
                    "error": "remote control is off"
                })
                .to_string()
                .as_bytes(),
            );
            return;
        }
        "fail" => {
            write_http(
                stream,
                502,
                json!({ "ok": false, "code": "app_error", "error": "boom" })
                    .to_string()
                    .as_bytes(),
            );
            return;
        }
        "disconnected" => {
            write_http(
                stream,
                503,
                json!({ "ok": false, "code": "app_disconnected", "error": "gone" })
                    .to_string()
                    .as_bytes(),
            );
            return;
        }
        "timedout" => {
            write_http(
                stream,
                504,
                json!({ "ok": false, "code": "timeout", "error": "slow" })
                    .to_string()
                    .as_bytes(),
            );
            return;
        }
        _ => json!({ "echo": args }),
    };

    let resp = json!({
        "ok": true,
        "clientId": "c1",
        "command": command,
        "elapsedMs": 3,
        "result": result,
    });
    write_http(stream, 200, resp.to_string().as_bytes());
}

struct CliHarness {
    tmp: PathBuf,
    cli_link: PathBuf,
    config_path: PathBuf,
    server: StubServer,
}

impl CliHarness {
    fn new() -> Self {
        let server = StubServer::start();
        let tmp = make_temp_dir("agentsterm-app-cli-suite");
        let bin_dir = tmp.join("bin");
        fs::create_dir_all(&bin_dir).unwrap();
        let cli_link = bin_dir.join("agents_term_app_cli");
        std::os::unix::fs::symlink(bin_path(), &cli_link).unwrap();

        let config_path = tmp.join("config.json");
        fs::write(
            &config_path,
            json!({
                "token": TOKEN,
                "host": "127.0.0.1",
                "port": server.port,
            })
            .to_string(),
        )
        .unwrap();

        Self {
            tmp,
            cli_link,
            config_path,
            server,
        }
    }

    fn run(&self, argv: &[&str]) -> Output {
        self.run_with_env(argv, &[])
    }

    fn run_with_env(&self, argv: &[&str], extra_env: &[(&str, &str)]) -> Output {
        let mut cmd = Command::new(&self.cli_link);
        cmd.arg("--config")
            .arg(&self.config_path)
            .args(argv)
            .env("HOME", &self.tmp)
            .env_remove("AGENTS_TERM_HOST")
            .env_remove("AGENTS_TERM_PORT")
            .env_remove("AGENTS_TERM_TOKEN");
        for (k, v) in extra_env {
            cmd.env(k, v);
        }
        cmd.output().unwrap()
    }

    fn sent(&self, argv: &[&str]) -> Vec<(String, Value)> {
        self.server.state.lock().unwrap().requests.clear();
        let out = self.run(argv);
        assert_eq!(
            out.status.code(),
            Some(0),
            "command {argv:?} failed: stderr={}",
            String::from_utf8_lossy(&out.stderr)
        );
        self.server
            .state
            .lock()
            .unwrap()
            .requests
            .iter()
            .map(|r| {
                (
                    r["command"].as_str().unwrap().to_string(),
                    r["args"].clone(),
                )
            })
            .collect()
    }
}

impl Drop for CliHarness {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.tmp);
    }
}

fn parse_json(bytes: &[u8]) -> Value {
    serde_json::from_slice(bytes).unwrap_or_else(|e| {
        panic!(
            "expected JSON, got {:?}: {e}",
            String::from_utf8_lossy(bytes)
        )
    })
}

#[test]
fn status_ready_and_not_ready_exit_codes() {
    let h = CliHarness::new();

    let out = h.run(&["status"]);
    assert_eq!(out.status.code(), Some(0));
    assert_eq!(parse_json(&out.stdout)["ready"], json!(true));

    h.server.state.lock().unwrap().ready = false;
    let out = h.run(&["status"]);
    assert_eq!(out.status.code(), Some(EXIT_NOT_READY));
    assert_eq!(parse_json(&out.stdout)["ready"], json!(false));
}

#[test]
fn global_flags_work_before_and_after_subcommand() {
    let h = CliHarness::new();

    let before = h.run(&["--pretty", "--timeout", "7", "ping"]);
    let after = h.run(&["ping", "--pretty", "--timeout", "7"]);
    assert_eq!(before.status.code(), Some(0));
    assert_eq!(after.status.code(), Some(0));
    let s_before = String::from_utf8_lossy(&before.stdout);
    let s_after = String::from_utf8_lossy(&after.stdout);
    assert!(s_before.contains("\n  "));
    assert_eq!(s_before, s_after);

    let st = h.server.state.lock().unwrap();
    let len = st.requests.len();
    assert_eq!(st.requests[len - 1]["timeoutMs"], json!(7000));
    assert_eq!(st.requests[len - 2]["timeoutMs"], json!(7000));
}

#[test]
fn logs_builds_filter_args() {
    let h = CliHarness::new();
    let out = h.run(&[
        "logs",
        "--limit",
        "5",
        "--category",
        "http",
        "--category",
        "ws",
        "--since",
        "123",
        "--grep",
        "fail",
    ]);
    assert_eq!(out.status.code(), Some(0));
    assert_eq!(
        parse_json(&out.stdout)["result"]["echo"],
        json!({
            "limit": 5,
            "categories": ["http", "ws"],
            "sinceTs": 123,
            "contains": "fail",
        })
    );
}

#[test]
fn screenshot_writes_png_and_omits_base64() {
    let h = CliHarness::new();
    let target = h.tmp.join("shots").join("s.png");
    let target_str = target.to_string_lossy().to_string();

    let out = h.run(&["screenshot", "-o", &target_str]);
    assert_eq!(out.status.code(), Some(0));
    assert_eq!(fs::read(&target).unwrap(), PNG_BYTES);
    let result = &parse_json(&out.stdout)["result"];
    assert!(result.get("pngBase64").is_none());
    assert_eq!(
        result["path"].as_str(),
        Some(
            fs::canonicalize(&target)
                .unwrap()
                .to_string_lossy()
                .as_ref()
        )
    );
    assert_eq!(result["bytes"], json!(PNG_BYTES.len()));
}

#[test]
fn screenshot_without_output_flag_writes_under_agents_term_tmp() {
    let h = CliHarness::new();
    let custom_home = h.tmp.join("isolated-agents-term");
    let custom_home_str = custom_home.to_string_lossy().to_string();

    let out = h.run_with_env(&["screenshot"], &[("AGENTSTERM_HOME", &custom_home_str)]);
    assert_eq!(
        out.status.code(),
        Some(0),
        "stderr={}",
        String::from_utf8_lossy(&out.stderr)
    );
    let result = &parse_json(&out.stdout)["result"];
    let saved_path = PathBuf::from(result["path"].as_str().unwrap());
    let expected_tmp = fs::canonicalize(custom_home.join("tmp")).unwrap();
    assert!(
        saved_path.starts_with(&expected_tmp),
        "Expected default screenshot inside {}, got {}",
        expected_tmp.display(),
        saved_path.display()
    );
    assert_eq!(fs::read(&saved_path).unwrap(), PNG_BYTES);
    assert!(!custom_home.join("config.json").exists());
}

#[test]
fn not_ready_and_app_errors_map_to_exit_codes() {
    let h = CliHarness::new();

    let out = h.run(&["raw", "fail"]);
    assert_eq!(out.status.code(), Some(EXIT_APP));
    let err = parse_json(&out.stderr);
    assert_eq!(err["ok"], json!(false));
    assert_eq!(err["code"], json!("app_error"));

    let out = h.run(&["raw", "timedout"]);
    assert_eq!(out.status.code(), Some(EXIT_APP));
    assert_eq!(parse_json(&out.stderr)["code"], json!("timeout"));

    let out = h.run(&["raw", "disconnected"]);
    assert_eq!(out.status.code(), Some(EXIT_NOT_READY));
    assert_eq!(parse_json(&out.stderr)["code"], json!("app_disconnected"));

    h.server.state.lock().unwrap().ready = false;
    let out = h.run(&["state"]);
    assert_eq!(out.status.code(), Some(EXIT_NOT_READY));
    assert_eq!(
        parse_json(&out.stderr)["code"],
        json!("inspection_disabled")
    );
}

#[test]
fn control_commands_build_app_requests() {
    let h = CliHarness::new();

    assert_eq!(
        h.sent(&["tap", "540", "1200"]),
        vec![("tap".into(), json!({ "x": 540, "y": 1200 }))]
    );
    assert_eq!(
        h.sent(&["tap", "--xy", "540", "1200"]),
        vec![("tap".into(), json!({ "x": 540, "y": 1200 }))]
    );
    assert_eq!(
        h.sent(&["tap", "--tag", "send_button"]),
        vec![("tap".into(), json!({ "tag": "send_button" }))]
    );
    assert_eq!(
        h.sent(&["tap", "--text", "Send", "--exact", "--index", "2"]),
        vec![(
            "tap".into(),
            json!({ "text": "Send", "exact": true, "index": 2 })
        )]
    );
    assert_eq!(
        h.sent(&["long-press", "--tag", "msg", "--duration-ms", "1200"]),
        vec![(
            "long_press".into(),
            json!({ "tag": "msg", "durationMs": 1200 })
        )]
    );
    assert_eq!(
        h.sent(&["long-press", "540", "1200", "--duration", "900"]),
        vec![(
            "long_press".into(),
            json!({ "x": 540, "y": 1200, "durationMs": 900 })
        )]
    );
    assert_eq!(
        h.sent(&["swipe", "500", "1500", "500", "400", "--hold-ms", "100"]),
        vec![(
            "swipe".into(),
            json!({ "x1": 500, "y1": 1500, "x2": 500, "y2": 400, "durationMs": 300, "holdMs": 100 })
        )]
    );
    assert_eq!(
        h.sent(&["scroll"]),
        vec![("scroll".into(), json!({ "direction": "down" }))]
    );
    assert_eq!(
        h.sent(&["scroll", "up", "--amount", "0.3", "--tag", "messages"]),
        vec![(
            "scroll".into(),
            json!({ "tag": "messages", "direction": "up", "amount": 0.3 })
        )]
    );
    assert_eq!(
        h.sent(&["type", "hello world"]),
        vec![("type".into(), json!({ "text": "hello world" }))]
    );
    assert_eq!(
        h.sent(&[
            "type",
            "hi",
            "--tag",
            "composer_input",
            "--replace",
            "--submit"
        ]),
        vec![(
            "type".into(),
            json!({ "text": "hi", "tag": "composer_input", "replace": true, "submit": true })
        )]
    );
    assert_eq!(
        h.sent(&["type", "22", "--field", "Port"]),
        vec![("type".into(), json!({ "text": "22", "field": "Port" }))]
    );
    assert_eq!(
        h.sent(&["key", "back"]),
        vec![("key".into(), json!({ "key": "back" }))]
    );
    assert_eq!(
        h.sent(&["key", "enter"]),
        vec![("key".into(), json!({ "key": "enter" }))]
    );
    assert_eq!(
        h.sent(&["dump", "--note", "repro", "--no-screenshot"]),
        vec![(
            "dump".into(),
            json!({ "note": "repro", "includeScreenshot": false })
        )]
    );
    assert!(
        h.server.state.lock().unwrap().requests[0]["timeoutMs"]
            .as_u64()
            .unwrap()
            >= 60_000
    );
}

#[test]
fn tap_needs_exactly_one_kind_of_target() {
    let h = CliHarness::new();
    for argv in [
        &["tap"][..],
        &["tap", "5"][..],
        &["tap", "5", "6", "--tag", "x"][..],
        &["tap", "--xy", "5", "6", "--tag", "x"][..],
        &["tap", "5", "6", "--xy", "7", "8"][..],
    ] {
        let out = h.run(argv);
        assert_eq!(out.status.code(), Some(EXIT_USAGE), "argv={argv:?}");
        assert_eq!(
            parse_json(&out.stderr)["code"],
            json!("bad_args"),
            "argv={argv:?}"
        );
    }
    assert!(h.server.state.lock().unwrap().requests.is_empty());
}

#[test]
fn control_disabled_is_not_ready() {
    let h = CliHarness::new();
    h.server.state.lock().unwrap().control = false;

    for argv in [
        &["tap", "1", "2"][..],
        &["long-press", "--tag", "x"][..],
        &["swipe", "1", "2", "3", "4"][..],
        &["scroll", "down"][..],
        &["type", "hello"][..],
        &["key", "back"][..],
    ] {
        let out = h.run(argv);
        assert_eq!(out.status.code(), Some(EXIT_NOT_READY), "argv={argv:?}");
        assert_eq!(
            parse_json(&out.stderr)["code"],
            json!("control_disabled"),
            "argv={argv:?}"
        );
    }
}

#[test]
fn action_can_capture_a_screenshot_afterwards() {
    let h = CliHarness::new();
    let target = h.tmp.join("after-tap.png");
    let target_str = target.to_string_lossy().to_string();

    let out = h.run(&[
        "tap",
        "--tag",
        "send_button",
        "-s",
        &target_str,
        "--settle",
        "0",
    ]);
    assert_eq!(
        out.status.code(),
        Some(0),
        "{}",
        String::from_utf8_lossy(&out.stderr)
    );
    assert_eq!(fs::read(&target).unwrap(), PNG_BYTES);
    assert_eq!(
        parse_json(&out.stdout)["screenshot"].as_str(),
        Some(
            fs::canonicalize(&target)
                .unwrap()
                .to_string_lossy()
                .as_ref()
        )
    );
    let cmds: Vec<String> = h
        .server
        .state
        .lock()
        .unwrap()
        .requests
        .iter()
        .map(|r| r["command"].as_str().unwrap().to_string())
        .collect();
    assert_eq!(cmds, vec!["tap", "screenshot"]);
}

#[test]
fn ui_json_filters_and_brief_listing() {
    let h = CliHarness::new();
    assert_eq!(
        h.sent(&["ui", "--text", "send", "--interactive"]),
        vec![(
            "ui".into(),
            json!({ "text": "send", "interactiveOnly": true })
        )]
    );

    let out = h.run(&["ui"]);
    assert_eq!(out.status.code(), Some(0));
    assert_eq!(parse_json(&out.stdout)["result"]["nodeCount"], json!(2));

    let brief = h.run(&["ui", "--brief"]);
    assert_eq!(brief.status.code(), Some(0));
    let lines: Vec<String> = String::from_utf8_lossy(&brief.stdout)
        .lines()
        .map(str::to_string)
        .collect();
    assert_eq!(
        lines,
        vec![
            "# 1080x2400 screenshot pixels; windows 0:activity, 1:popup; [x,y] is each element's center",
            "[980,2200] Button description=\"Send\" tag=send_button clickable",
            "[440,2200] \"Message\" value=\"dra\\\"ft\" tag=composer_input toggle=on selected=false editable focused disabled window=1",
        ]
    );
}

#[test]
fn wait_polls_until_element_appears_or_times_out() {
    let h = CliHarness::new();
    h.server.state.lock().unwrap().appears_after = 3;

    let out = h.run(&["wait", "--tag", "send_button", "--interval", "0.05"]);
    assert_eq!(
        out.status.code(),
        Some(0),
        "{}",
        String::from_utf8_lossy(&out.stderr)
    );
    assert_eq!(
        parse_json(&out.stdout)["nodes"][0]["tag"],
        json!("send_button")
    );
    assert_eq!(h.server.state.lock().unwrap().ui_calls, 3);

    let gone = h.run(&["wait", "--tag", "missing", "--gone"]);
    assert_eq!(gone.status.code(), Some(0));
    assert_eq!(parse_json(&gone.stdout)["gone"], json!(true));

    let to1 = h.run(&[
        "wait",
        "--tag",
        "missing",
        "--wait-timeout",
        "0.2",
        "--interval",
        "0.05",
    ]);
    assert_eq!(to1.status.code(), Some(EXIT_APP));
    assert_eq!(parse_json(&to1.stderr)["code"], json!("wait_timeout"));

    let to2 = h.run(&[
        "wait",
        "--tag",
        "send_button",
        "--gone",
        "--wait-timeout",
        "0.1",
        "--interval",
        "0.05",
    ]);
    assert_eq!(to2.status.code(), Some(EXIT_APP));
    assert_eq!(parse_json(&to2.stderr)["code"], json!("wait_timeout"));

    let bad = h.run(&["wait"]);
    assert_eq!(bad.status.code(), Some(EXIT_USAGE));
    assert_eq!(parse_json(&bad.stderr)["code"], json!("bad_args"));
}

#[test]
fn bad_token_unreachable_old_daemon_and_env_override() {
    let h = CliHarness::new();

    let bad_tok = h.run(&["--token", "wrong", "ping"]);
    assert_eq!(bad_tok.status.code(), Some(EXIT_DAEMON));
    assert_eq!(parse_json(&bad_tok.stderr)["code"], json!("unauthorized"));

    let unreach = h.run(&["--port", "1", "ping"]);
    assert_eq!(unreach.status.code(), Some(EXIT_DAEMON));
    assert_eq!(
        parse_json(&unreach.stderr)["code"],
        json!("daemon_unreachable")
    );

    h.server.state.lock().unwrap().old_daemon = true;
    let old = h.run(&["status"]);
    assert_eq!(old.status.code(), Some(EXIT_DAEMON));
    assert_eq!(parse_json(&old.stderr)["code"], json!("daemon_unsupported"));
    h.server.reset();

    h.server.state.lock().unwrap().non_json_ok = true;
    let non_json = h.run(&["status"]);
    assert_eq!(non_json.status.code(), Some(EXIT_DAEMON));
    assert_eq!(parse_json(&non_json.stderr)["code"], json!("daemon_error"));
    h.server.reset();

    // Environment variables override config.json.
    let env_bad = h.run_with_env(&["ping"], &[("AGENTS_TERM_TOKEN", "wrong")]);
    assert_eq!(env_bad.status.code(), Some(EXIT_DAEMON));
    assert_eq!(parse_json(&env_bad.stderr)["code"], json!("unauthorized"));

    // 0.0.0.0 in config resolves to 127.0.0.1 for client connections.
    let zero_cfg = h.tmp.join("zero.json");
    fs::write(
        &zero_cfg,
        json!({ "token": TOKEN, "host": "0.0.0.0", "port": h.server.port }).to_string(),
    )
    .unwrap();
    let zero_res = Command::new(&h.cli_link)
        .args(["--config", &zero_cfg.to_string_lossy(), "ping"])
        .env("HOME", &h.tmp)
        .env_remove("AGENTS_TERM_HOST")
        .env_remove("AGENTS_TERM_PORT")
        .env_remove("AGENTS_TERM_TOKEN")
        .output()
        .unwrap();
    assert_eq!(zero_res.status.code(), Some(0));

    // Invalid config JSON exits 1 with bad_config.
    let bad_cfg = h.tmp.join("broken.json");
    fs::write(&bad_cfg, "{broken").unwrap();
    let bad_cfg_res = Command::new(&h.cli_link)
        .args(["--config", &bad_cfg.to_string_lossy(), "ping"])
        .env("HOME", &h.tmp)
        .output()
        .unwrap();
    assert_eq!(bad_cfg_res.status.code(), Some(EXIT_USAGE));
    assert_eq!(parse_json(&bad_cfg_res.stderr)["code"], json!("bad_config"));
}

#[test]
fn raw_accepts_flag_and_positional_and_rejects_non_object_args() {
    let h = CliHarness::new();

    assert_eq!(
        h.sent(&["raw", "ping", "--args", "{\"a\":1}"]),
        vec![("ping".into(), json!({ "a": 1 }))]
    );
    assert_eq!(
        h.sent(&["raw", "ping", "{\"b\":2}"]),
        vec![("ping".into(), json!({ "b": 2 }))]
    );

    for argv in [
        &["raw", "ping", "--args", "[1]"][..],
        &["raw", "ping", "[1]"][..],
        &["raw", "ping", "--args", "{bad"][..],
    ] {
        let out = h.run(argv);
        assert_eq!(out.status.code(), Some(EXIT_USAGE), "argv={argv:?}");
        assert_eq!(parse_json(&out.stderr)["code"], json!("bad_args"));
    }
}

#[test]
fn help_and_subcommand_help_work_without_daemon() {
    let h = CliHarness::new();

    for argv in [&[][..], &["--help"][..], &["-h"][..], &["help"][..]] {
        let out = Command::new(&h.cli_link)
            .args(argv)
            .env("HOME", Path::new(&h.tmp))
            .output()
            .unwrap();
        assert_eq!(out.status.code(), Some(0), "argv={argv:?}");
        let stdout = String::from_utf8_lossy(&out.stdout);
        assert!(stdout.contains("agents_term_app_cli"));
        assert!(stdout.contains("PREREQUISITES"));
        assert!(stdout.contains("RECOMMENDED AGENT WORKFLOW"));
        assert!(stdout.contains("EXIT CODES"));
    }

    for sub in [
        "status", "tap", "type", "ui", "wait", "dump", "swipe", "scroll",
    ] {
        let out = Command::new(&h.cli_link)
            .args([sub, "--help"])
            .env("HOME", Path::new(&h.tmp))
            .output()
            .unwrap();
        assert_eq!(out.status.code(), Some(0), "sub={sub}");
        let stdout = String::from_utf8_lossy(&out.stdout);
        assert!(
            stdout.contains(&format!("Usage: agents_term_app_cli {sub}")),
            "unexpected help for {sub}: {stdout}"
        );
    }

    let help_cmd = Command::new(&h.cli_link)
        .args(["help", "type"])
        .env("HOME", Path::new(&h.tmp))
        .output()
        .unwrap();
    assert_eq!(help_cmd.status.code(), Some(0));
    assert!(String::from_utf8_lossy(&help_cmd.stdout).contains("--replace"));
    assert!(String::from_utf8_lossy(&help_cmd.stdout).contains("--field"));
}
