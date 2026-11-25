//! Native Rust implementation of `app_state_cli` (`agentsterm-daemon app ...` or symlinked `app_state_cli`).
//!
//! Preserves the exact command set, flags (including global flags before or after the subcommand),
//! JSON output shapes, and exit codes (0 ok, 1 usage, 2 daemon, 3 app not ready, 4 app error/timeout).

use base64::Engine;
use chrono::Local;
use serde_json::{json, Map, Value};
use std::collections::HashSet;
use std::env;
use std::fs;
use std::path::PathBuf;
use std::thread;
use std::time::{Duration, Instant};

use crate::config::load_or_create_config;
use crate::http_client::http_request;

const EXIT_USAGE: i32 = 1;
const EXIT_DAEMON: i32 = 2;
const EXIT_NOT_READY: i32 = 3;
const EXIT_APP: i32 = 4;

const NOT_READY_CODES: &[&str] = &[
    "no_app_connected",
    "unsupported_app",
    "inspection_disabled",
    "control_disabled",
    "app_disconnected",
];

struct CliErr {
    message: String,
    exit_code: i32,
    code: Option<String>,
}

impl CliErr {
    fn new(message: impl Into<String>, exit_code: i32, code: Option<&str>) -> Self {
        Self {
            message: message.into(),
            exit_code,
            code: code.map(str::to_string),
        }
    }
}

struct Client {
    host: String,
    port: u16,
    token: String,
    timeout: Duration,
}

impl Client {
    fn request(
        &self,
        method: &str,
        path: &str,
        body: Option<&Value>,
        timeout: Option<Duration>,
    ) -> Result<Value, CliErr> {
        let body_bytes = body.map(|v| v.to_string().into_bytes());
        let resp = http_request(
            method,
            &self.host,
            self.port,
            path,
            Some(&self.token),
            body_bytes.as_deref(),
            timeout.unwrap_or(self.timeout),
        )
        .map_err(|e| {
            CliErr::new(
                format!(
                    "Cannot reach daemon at http://{}:{}: {e}",
                    self.host, self.port
                ),
                EXIT_DAEMON,
                Some("daemon_unreachable"),
            )
        })?;

        let payload = resp.json().unwrap_or_else(|_| json!({}));
        if resp.status >= 200 && resp.status < 300 {
            return Ok(payload);
        }

        let message = payload
            .get("error")
            .and_then(Value::as_str)
            .map(str::to_string)
            .unwrap_or_else(|| format!("HTTP {} from daemon", resp.status));
        let code = payload.get("code").and_then(Value::as_str);

        if resp.status == 401 {
            return Err(CliErr::new(
                format!("{message} (check the token in ~/.config/herdr-mobile/config.json)"),
                EXIT_DAEMON,
                Some("unauthorized"),
            ));
        }
        if let Some(c) = code {
            if NOT_READY_CODES.contains(&c) {
                return Err(CliErr::new(message, EXIT_NOT_READY, Some(c)));
            }
            return Err(CliErr::new(message, EXIT_APP, Some(c)));
        }
        if resp.status == 404 {
            return Err(CliErr::new(
                format!("{message}. The daemon is too old for app state reporting; update and restart it."),
                EXIT_DAEMON,
                Some("daemon_unsupported"),
            ));
        }
        Err(CliErr::new(message, EXIT_DAEMON, Some("daemon_error")))
    }

    fn status(&self) -> Result<Value, CliErr> {
        self.request("GET", "/api/app/status", None, None)
    }

    fn command(&self, cmd: &str, args: Value, timeout_s: f64) -> Result<Value, CliErr> {
        let body = json!({
            "command": cmd,
            "args": args,
            "timeoutMs": (timeout_s * 1000.0) as u64,
        });
        let http_timeout = Duration::from_secs_f64((timeout_s + 10.0).max(5.0));
        self.request("POST", "/api/app/command", Some(&body), Some(http_timeout))
    }
}

fn emit(val: &Value, pretty: bool) {
    if pretty {
        println!("{}", serde_json::to_string_pretty(val).unwrap_or_default());
    } else {
        println!("{}", serde_json::to_string(val).unwrap_or_default());
    }
}

fn save_screenshot(client: &Client, timeout_s: f64, output: Option<&str>) -> Result<Value, CliErr> {
    let mut resp = client.command("screenshot", json!({}), timeout_s)?;
    let b64 = resp
        .get_mut("result")
        .and_then(Value::as_object_mut)
        .and_then(|r| r.remove("pngBase64"))
        .and_then(|v| v.as_str().map(str::to_string))
        .filter(|s| !s.is_empty())
        .ok_or_else(|| {
            CliErr::new(
                "App returned no screenshot data",
                EXIT_APP,
                Some("app_error"),
            )
        })?;

    let bytes = base64::engine::general_purpose::STANDARD
        .decode(b64.trim())
        .map_err(|e| {
            CliErr::new(
                format!("Invalid screenshot base64: {e}"),
                EXIT_APP,
                Some("app_error"),
            )
        })?;

    let out_path = match output {
        Some(p) => PathBuf::from(p),
        None => PathBuf::from(format!(
            "/tmp/agentsterm-screenshot-{}.png",
            Local::now().format("%Y%m%d-%H%M%S")
        )),
    };
    if let Some(parent) = out_path.parent() {
        let _ = fs::create_dir_all(parent);
    }
    fs::write(&out_path, &bytes).map_err(|e| {
        CliErr::new(
            format!("Failed to write {}: {e}", out_path.display()),
            EXIT_USAGE,
            None,
        )
    })?;
    let abs = fs::canonicalize(&out_path).unwrap_or(out_path);
    if let Some(r) = resp.get_mut("result").and_then(Value::as_object_mut) {
        r.insert("path".into(), json!(abs.to_string_lossy()));
        r.insert("bytes".into(), json!(bytes.len()));
    }
    Ok(resp)
}

fn maybe_attach_screenshot(
    client: &Client,
    timeout_s: f64,
    mut resp: Value,
    screenshot_out: Option<&str>,
) -> Result<Value, CliErr> {
    if let Some(out) = screenshot_out {
        thread::sleep(Duration::from_millis(250));
        let shot = save_screenshot(client, timeout_s, Some(out))?;
        if let Some(obj) = resp.as_object_mut() {
            obj.insert(
                "screenshot".into(),
                shot.get("result").cloned().unwrap_or(Value::Null),
            );
        }
    }
    Ok(resp)
}

fn format_ui_brief(result: &Value) -> String {
    let mut lines = Vec::new();
    let w = result
        .get("windowWidth")
        .and_then(Value::as_i64)
        .unwrap_or(0);
    let h = result
        .get("windowHeight")
        .and_then(Value::as_i64)
        .unwrap_or(0);
    lines.push(format!("window: {w}x{h}"));
    if let Some(nodes) = result.get("nodes").and_then(Value::as_array) {
        for n in nodes {
            let tag = n.get("tag").and_then(Value::as_str).unwrap_or("");
            let text = n.get("text").and_then(Value::as_str).unwrap_or("");
            let desc = n
                .get("contentDescription")
                .and_then(Value::as_str)
                .unwrap_or("");
            let role = n.get("role").and_then(Value::as_str).unwrap_or("");
            let b = n.get("bounds").and_then(Value::as_object);
            let bounds_str = b
                .map(|o| {
                    format!(
                        "[{},{} {},{}]",
                        o.get("left").and_then(Value::as_i64).unwrap_or(0),
                        o.get("top").and_then(Value::as_i64).unwrap_or(0),
                        o.get("right").and_then(Value::as_i64).unwrap_or(0),
                        o.get("bottom").and_then(Value::as_i64).unwrap_or(0),
                    )
                })
                .unwrap_or_default();
            let mut flags = Vec::new();
            if n.get("clickable").and_then(Value::as_bool) == Some(true) {
                flags.push("click");
            }
            if n.get("editable").and_then(Value::as_bool) == Some(true) {
                flags.push("edit");
            }
            if n.get("scrollable").and_then(Value::as_bool) == Some(true) {
                flags.push("scroll");
            }
            let label = if !text.is_empty() {
                text
            } else if !desc.is_empty() {
                desc
            } else {
                ""
            };
            let mut parts = vec![bounds_str];
            if !tag.is_empty() {
                parts.push(format!("#{tag}"));
            }
            if !role.is_empty() {
                parts.push(format!("({role})"));
            }
            if !flags.is_empty() {
                parts.push(format!("[{}]", flags.join(",")));
            }
            if !label.is_empty() {
                parts.push(format!("{label:?}"));
            }
            lines.push(parts.join(" "));
        }
    }
    lines.join("\n")
}

const APP_USAGE: &str = "usage: agentsterm-daemon app [global flags] <command> [args...]\n\
\n\
Global flags (before or after command):\n\
  --host HOST        daemon host (default: config.json / AGENTS_TERM_HOST)\n\
  --port PORT        daemon port (default: config.json / AGENTS_TERM_PORT / 7501)\n\
  --token TOKEN      bearer token (default: config.json / AGENTS_TERM_TOKEN)\n\
  --timeout SEC      command timeout in seconds (default: 15)\n\
  --pretty           pretty-print JSON output\n\
\n\
Commands:\n\
  status             check app connection & inspection/control readiness\n\
  state              current screen, workspace, tabs, messages, settings\n\
  logs               in-app debug events (--limit N --category C --since MS --grep S -f --interval S)\n\
  logcat             Android logcat (--lines N --raw)\n\
  crash              last uncaught crash trace (--raw)\n\
  screenshot         capture PNG (-o PATH)\n\
  dump               upload debug dump (--note TEXT)\n\
  ui                 UI semantics tree (--brief --interactive --max-nodes N)\n\
  tap                tap by --tag / --text / --xy X Y (--exact --index N --screenshot PATH)\n\
  long-press         long-press by --tag / --text / --xy X Y (--duration MS --screenshot PATH)\n\
  swipe              swipe X1 Y1 X2 Y2 (--duration MS --screenshot PATH)\n\
  scroll             scroll (--direction up|down|left|right --tag / --text --screenshot PATH)\n\
  type               type text (--text-value / positional TEXT --tag / --text --clear --submit --screenshot PATH)\n\
  key                press key (back|home|enter|...) (--screenshot PATH)\n\
  wait               wait for element (--tag / --text --exact --gone --timeout SEC --interval SEC)\n\
  raw                send raw command NAME [JSON_ARGS]\n";

pub fn run_app_cli(raw_args: &[String]) -> i32 {
    match run_app_cli_inner(raw_args) {
        Ok(code) => code,
        Err(err) => {
            let mut obj = Map::new();
            obj.insert("error".into(), json!(err.message));
            if let Some(c) = err.code {
                obj.insert("code".into(), json!(c));
            }
            eprintln!("{}", Value::Object(obj));
            err.exit_code
        }
    }
}

fn next_val(args: &[String], i: &mut usize, flag: &str) -> Result<String, CliErr> {
    if *i + 1 >= args.len() {
        return Err(CliErr::new(
            format!("Missing value for {flag}"),
            EXIT_USAGE,
            None,
        ));
    }
    *i += 1;
    Ok(args[*i].clone())
}

fn run_app_cli_inner(raw_args: &[String]) -> Result<i32, CliErr> {
    if raw_args.is_empty() || raw_args.iter().any(|a| a == "-h" || a == "--help") {
        print!("{APP_USAGE}");
        return Ok(0);
    }

    let mut host: Option<String> = None;
    let mut port: Option<u16> = None;
    let mut token: Option<String> = None;
    let mut timeout_s: f64 = 15.0;
    let mut pretty = false;
    let mut rest: Vec<String> = Vec::new();

    // Extract global flags from anywhere in argv so flags work before or after the subcommand.
    let mut i = 0;
    while i < raw_args.len() {
        let a = &raw_args[i];
        if a == "--host" {
            host = Some(next_val(raw_args, &mut i, "--host")?);
        } else if let Some(v) = a.strip_prefix("--host=") {
            host = Some(v.to_string());
        } else if a == "--port" {
            let v = next_val(raw_args, &mut i, "--port")?;
            port = Some(
                v.parse::<u16>()
                    .map_err(|_| CliErr::new(format!("Invalid --port: {v}"), EXIT_USAGE, None))?,
            );
        } else if let Some(v) = a.strip_prefix("--port=") {
            port = Some(
                v.parse::<u16>()
                    .map_err(|_| CliErr::new(format!("Invalid --port: {v}"), EXIT_USAGE, None))?,
            );
        } else if a == "--token" {
            token = Some(next_val(raw_args, &mut i, "--token")?);
        } else if let Some(v) = a.strip_prefix("--token=") {
            token = Some(v.to_string());
        } else if a == "--pretty" {
            pretty = true;
        } else if a == "--timeout" {
            let v = next_val(raw_args, &mut i, "--timeout")?;
            timeout_s = v
                .parse::<f64>()
                .map_err(|_| CliErr::new(format!("Invalid --timeout: {v}"), EXIT_USAGE, None))?;
        } else if let Some(v) = a.strip_prefix("--timeout=") {
            timeout_s = v
                .parse::<f64>()
                .map_err(|_| CliErr::new(format!("Invalid --timeout: {v}"), EXIT_USAGE, None))?;
        } else {
            rest.push(a.clone());
        }
        i += 1;
    }

    if rest.is_empty() {
        print!("{APP_USAGE}");
        return Ok(EXIT_USAGE);
    }

    let cfg = load_or_create_config();
    let resolved_host = host
        .or_else(|| env::var("AGENTS_TERM_HOST").ok().filter(|s| !s.is_empty()))
        .or(cfg.host)
        .unwrap_or_else(|| "127.0.0.1".to_string());
    let resolved_port = port
        .or_else(|| {
            env::var("AGENTS_TERM_PORT")
                .ok()
                .and_then(|s| s.parse::<u16>().ok())
        })
        .or(cfg.port)
        .unwrap_or(7501);
    let resolved_token = token
        .or_else(|| env::var("AGENTS_TERM_TOKEN").ok().filter(|s| !s.is_empty()))
        .unwrap_or(cfg.token);

    let client = Client {
        host: resolved_host,
        port: resolved_port,
        token: resolved_token,
        timeout: Duration::from_secs_f64((timeout_s + 5.0).max(5.0)),
    };

    let sub = rest[0].as_str();
    let sub_args = &rest[1..];

    match sub {
        "status" => {
            let data = client.status()?;
            emit(&data, pretty);
            if data.get("ready").and_then(Value::as_bool) == Some(true) {
                Ok(0)
            } else {
                Ok(EXIT_NOT_READY)
            }
        }
        "state" => {
            let resp = client.command("state", json!({}), timeout_s)?;
            emit(&resp, pretty);
            Ok(0)
        }
        "logs" => {
            let mut limit: i64 = 100;
            let mut categories: Vec<String> = Vec::new();
            let mut since: Option<i64> = None;
            let mut grep: Option<String> = None;
            let mut follow = false;
            let mut interval = 1.0f64;
            let mut j = 0;
            while j < sub_args.len() {
                match sub_args[j].as_str() {
                    "--limit" => {
                        limit = next_val(sub_args, &mut j, "--limit")?
                            .parse()
                            .map_err(|_| CliErr::new("Invalid --limit", EXIT_USAGE, None))?;
                    }
                    "--category" => categories.push(next_val(sub_args, &mut j, "--category")?),
                    "--since" => {
                        since = Some(
                            next_val(sub_args, &mut j, "--since")?
                                .parse()
                                .map_err(|_| CliErr::new("Invalid --since", EXIT_USAGE, None))?,
                        );
                    }
                    "--grep" => grep = Some(next_val(sub_args, &mut j, "--grep")?),
                    "-f" | "--follow" => follow = true,
                    "--interval" => {
                        interval = next_val(sub_args, &mut j, "--interval")?
                            .parse()
                            .map_err(|_| CliErr::new("Invalid --interval", EXIT_USAGE, None))?;
                    }
                    other => {
                        return Err(CliErr::new(
                            format!("Unknown logs option: {other}"),
                            EXIT_USAGE,
                            None,
                        ))
                    }
                }
                j += 1;
            }
            let build_args = |since_ts: Option<i64>| -> Value {
                let mut m = Map::new();
                m.insert("limit".into(), json!(limit));
                if !categories.is_empty() {
                    m.insert("categories".into(), json!(categories));
                }
                if let Some(s) = since_ts {
                    m.insert("sinceTs".into(), json!(s));
                }
                if let Some(ref g) = grep {
                    m.insert("contains".into(), json!(g));
                }
                Value::Object(m)
            };
            if !follow {
                let resp = client.command("logs", build_args(since), timeout_s)?;
                emit(&resp, pretty);
                return Ok(0);
            }
            let mut seen_ts = since;
            let mut seen_at_ts: HashSet<String> = HashSet::new();
            let mut first = true;
            loop {
                match client.command("logs", build_args(seen_ts), timeout_s) {
                    Ok(resp) => {
                        first = false;
                        if let Some(events) = resp
                            .get("result")
                            .and_then(|r| r.get("events"))
                            .and_then(Value::as_array)
                        {
                            for ev in events {
                                if let Some(ts) = ev.get("ts").and_then(Value::as_i64) {
                                    let id = serde_json::to_string(ev).unwrap_or_default();
                                    if let Some(st) = seen_ts {
                                        if ts < st || (ts == st && seen_at_ts.contains(&id)) {
                                            continue;
                                        }
                                        if ts > st {
                                            seen_ts = Some(ts);
                                            seen_at_ts.clear();
                                        }
                                    } else {
                                        seen_ts = Some(ts);
                                    }
                                    seen_at_ts.insert(id);
                                }
                                emit(ev, false);
                            }
                        }
                    }
                    Err(err) if !first => {
                        eprintln!("{}", json!({ "warning": err.message, "code": err.code }));
                    }
                    Err(err) => return Err(err),
                }
                thread::sleep(Duration::from_secs_f64(interval.max(0.1)));
            }
        }
        "logcat" => {
            let mut lines: i64 = 400;
            let mut raw = false;
            let mut j = 0;
            while j < sub_args.len() {
                match sub_args[j].as_str() {
                    "--lines" => {
                        lines = next_val(sub_args, &mut j, "--lines")?
                            .parse()
                            .map_err(|_| CliErr::new("Invalid --lines", EXIT_USAGE, None))?;
                    }
                    "--raw" => raw = true,
                    other => {
                        return Err(CliErr::new(
                            format!("Unknown logcat option: {other}"),
                            EXIT_USAGE,
                            None,
                        ))
                    }
                }
                j += 1;
            }
            let resp = client.command("logcat", json!({ "lines": lines }), timeout_s)?;
            if raw {
                println!(
                    "{}",
                    resp.get("result")
                        .and_then(|r| r.get("text"))
                        .and_then(Value::as_str)
                        .unwrap_or("")
                );
            } else {
                emit(&resp, pretty);
            }
            Ok(0)
        }
        "crash" => {
            let raw = sub_args.iter().any(|a| a == "--raw");
            let resp = client.command("crash", json!({}), timeout_s)?;
            if raw {
                println!(
                    "{}",
                    resp.get("result")
                        .and_then(|r| r.get("text"))
                        .and_then(Value::as_str)
                        .unwrap_or("")
                );
            } else {
                emit(&resp, pretty);
            }
            Ok(0)
        }
        "screenshot" => {
            let mut output: Option<String> = None;
            let mut j = 0;
            while j < sub_args.len() {
                match sub_args[j].as_str() {
                    "-o" | "--output" => output = Some(next_val(sub_args, &mut j, "--output")?),
                    other => {
                        return Err(CliErr::new(
                            format!("Unknown screenshot option: {other}"),
                            EXIT_USAGE,
                            None,
                        ))
                    }
                }
                j += 1;
            }
            let resp = save_screenshot(&client, timeout_s, output.as_deref())?;
            emit(&resp, pretty);
            Ok(0)
        }
        "dump" => {
            let mut note: Option<String> = None;
            let mut j = 0;
            while j < sub_args.len() {
                match sub_args[j].as_str() {
                    "--note" => note = Some(next_val(sub_args, &mut j, "--note")?),
                    other => {
                        return Err(CliErr::new(
                            format!("Unknown dump option: {other}"),
                            EXIT_USAGE,
                            None,
                        ))
                    }
                }
                j += 1;
            }
            let mut args = Map::new();
            if let Some(n) = note {
                args.insert("note".into(), json!(n));
            }
            let resp = client.command("dump", Value::Object(args), timeout_s)?;
            emit(&resp, pretty);
            Ok(0)
        }
        "ui" => {
            let mut brief = false;
            let mut interactive = false;
            let mut max_nodes: Option<i64> = None;
            let mut j = 0;
            while j < sub_args.len() {
                match sub_args[j].as_str() {
                    "--brief" => brief = true,
                    "--interactive" => interactive = true,
                    "--max-nodes" => {
                        max_nodes =
                            Some(next_val(sub_args, &mut j, "--max-nodes")?.parse().map_err(
                                |_| CliErr::new("Invalid --max-nodes", EXIT_USAGE, None),
                            )?);
                    }
                    other => {
                        return Err(CliErr::new(
                            format!("Unknown ui option: {other}"),
                            EXIT_USAGE,
                            None,
                        ))
                    }
                }
                j += 1;
            }
            let mut args = Map::new();
            if interactive {
                args.insert("interactiveOnly".into(), json!(true));
            }
            if let Some(mn) = max_nodes {
                args.insert("maxNodes".into(), json!(mn));
            }
            let resp = client.command("ui", Value::Object(args), timeout_s)?;
            if brief {
                if let Some(res) = resp.get("result") {
                    println!("{}", format_ui_brief(res));
                }
            } else {
                emit(&resp, pretty);
            }
            Ok(0)
        }
        "tap" | "long-press" => {
            let mut args = Map::new();
            let mut xy: Option<(f64, f64)> = None;
            let mut has_sel = false;
            let mut screenshot: Option<String> = None;
            let mut j = 0;
            while j < sub_args.len() {
                match sub_args[j].as_str() {
                    "--tag" => {
                        args.insert("tag".into(), json!(next_val(sub_args, &mut j, "--tag")?));
                        has_sel = true;
                    }
                    "--text" => {
                        args.insert("text".into(), json!(next_val(sub_args, &mut j, "--text")?));
                        has_sel = true;
                    }
                    "--exact" => {
                        args.insert("exact".into(), json!(true));
                    }
                    "--index" => {
                        let idx: i64 = next_val(sub_args, &mut j, "--index")?
                            .parse()
                            .map_err(|_| CliErr::new("Invalid --index", EXIT_USAGE, None))?;
                        args.insert("index".into(), json!(idx));
                    }
                    "--xy" => {
                        let x: f64 = next_val(sub_args, &mut j, "--xy X")?
                            .parse()
                            .map_err(|_| CliErr::new("Invalid --xy X", EXIT_USAGE, None))?;
                        let y: f64 = next_val(sub_args, &mut j, "--xy Y")?
                            .parse()
                            .map_err(|_| CliErr::new("Invalid --xy Y", EXIT_USAGE, None))?;
                        xy = Some((x, y));
                    }
                    "--duration" => {
                        let d: i64 = next_val(sub_args, &mut j, "--duration")?
                            .parse()
                            .map_err(|_| CliErr::new("Invalid --duration", EXIT_USAGE, None))?;
                        args.insert("durationMs".into(), json!(d));
                    }
                    "--screenshot" => {
                        screenshot = Some(next_val(sub_args, &mut j, "--screenshot")?)
                    }
                    other => {
                        return Err(CliErr::new(
                            format!("Unknown {sub} option: {other}"),
                            EXIT_USAGE,
                            None,
                        ))
                    }
                }
                j += 1;
            }
            if has_sel && xy.is_some() {
                return Err(CliErr::new(
                    "Specify either a selector (--tag/--text) or --xy, not both",
                    EXIT_USAGE,
                    None,
                ));
            }
            if !has_sel && xy.is_none() {
                return Err(CliErr::new(
                    "Specify a target (--tag, --text, or --xy X Y)",
                    EXIT_USAGE,
                    None,
                ));
            }
            if let Some((x, y)) = xy {
                args.insert("x".into(), json!(x));
                args.insert("y".into(), json!(y));
            }
            let cmd_name = if sub == "long-press" {
                "long_press"
            } else {
                "tap"
            };
            let resp = client.command(cmd_name, Value::Object(args), timeout_s)?;
            let resp = maybe_attach_screenshot(&client, timeout_s, resp, screenshot.as_deref())?;
            emit(&resp, pretty);
            Ok(0)
        }
        "swipe" => {
            let mut coords: Vec<f64> = Vec::new();
            let mut duration_ms: Option<i64> = None;
            let mut screenshot: Option<String> = None;
            let mut j = 0;
            while j < sub_args.len() {
                match sub_args[j].as_str() {
                    "--duration" => {
                        duration_ms =
                            Some(next_val(sub_args, &mut j, "--duration")?.parse().map_err(
                                |_| CliErr::new("Invalid --duration", EXIT_USAGE, None),
                            )?);
                    }
                    "--screenshot" => {
                        screenshot = Some(next_val(sub_args, &mut j, "--screenshot")?)
                    }
                    val => {
                        coords.push(val.parse::<f64>().map_err(|_| {
                            CliErr::new(format!("Invalid swipe coord: {val}"), EXIT_USAGE, None)
                        })?);
                    }
                }
                j += 1;
            }
            if coords.len() != 4 {
                return Err(CliErr::new("swipe requires X1 Y1 X2 Y2", EXIT_USAGE, None));
            }
            let mut args = json!({
                "x1": coords[0], "y1": coords[1], "x2": coords[2], "y2": coords[3]
            });
            if let Some(d) = duration_ms {
                args.as_object_mut()
                    .unwrap()
                    .insert("durationMs".into(), json!(d));
            }
            let resp = client.command("swipe", args, timeout_s)?;
            let resp = maybe_attach_screenshot(&client, timeout_s, resp, screenshot.as_deref())?;
            emit(&resp, pretty);
            Ok(0)
        }
        "scroll" => {
            let mut args = Map::new();
            let mut direction = "down".to_string();
            let mut screenshot: Option<String> = None;
            let mut j = 0;
            while j < sub_args.len() {
                match sub_args[j].as_str() {
                    "--direction" => direction = next_val(sub_args, &mut j, "--direction")?,
                    "--tag" => {
                        args.insert("tag".into(), json!(next_val(sub_args, &mut j, "--tag")?));
                    }
                    "--text" => {
                        args.insert("text".into(), json!(next_val(sub_args, &mut j, "--text")?));
                    }
                    "--screenshot" => {
                        screenshot = Some(next_val(sub_args, &mut j, "--screenshot")?)
                    }
                    val if !val.starts_with('-') => direction = val.to_string(),
                    other => {
                        return Err(CliErr::new(
                            format!("Unknown scroll option: {other}"),
                            EXIT_USAGE,
                            None,
                        ))
                    }
                }
                j += 1;
            }
            args.insert("direction".into(), json!(direction));
            let resp = client.command("scroll", Value::Object(args), timeout_s)?;
            let resp = maybe_attach_screenshot(&client, timeout_s, resp, screenshot.as_deref())?;
            emit(&resp, pretty);
            Ok(0)
        }
        "type" => {
            let mut args = Map::new();
            let mut text_val: Option<String> = None;
            let mut screenshot: Option<String> = None;
            let mut j = 0;
            while j < sub_args.len() {
                match sub_args[j].as_str() {
                    "--tag" => {
                        args.insert("tag".into(), json!(next_val(sub_args, &mut j, "--tag")?));
                    }
                    "--text" => {
                        args.insert("text".into(), json!(next_val(sub_args, &mut j, "--text")?));
                    }
                    "--clear" => {
                        args.insert("clear".into(), json!(true));
                    }
                    "--submit" => {
                        args.insert("submit".into(), json!(true));
                    }
                    "--screenshot" => {
                        screenshot = Some(next_val(sub_args, &mut j, "--screenshot")?)
                    }
                    val if !val.starts_with('-') && text_val.is_none() => {
                        text_val = Some(val.to_string())
                    }
                    other => {
                        return Err(CliErr::new(
                            format!("Unknown type option: {other}"),
                            EXIT_USAGE,
                            None,
                        ))
                    }
                }
                j += 1;
            }
            let input_text = text_val
                .ok_or_else(|| CliErr::new("type requires TEXT argument", EXIT_USAGE, None))?;
            args.insert("value".into(), json!(input_text));
            let resp = client.command("type", Value::Object(args), timeout_s)?;
            let resp = maybe_attach_screenshot(&client, timeout_s, resp, screenshot.as_deref())?;
            emit(&resp, pretty);
            Ok(0)
        }
        "key" => {
            let mut key_name: Option<String> = None;
            let mut screenshot: Option<String> = None;
            let mut j = 0;
            while j < sub_args.len() {
                match sub_args[j].as_str() {
                    "--screenshot" => {
                        screenshot = Some(next_val(sub_args, &mut j, "--screenshot")?)
                    }
                    val if !val.starts_with('-') && key_name.is_none() => {
                        key_name = Some(val.to_string())
                    }
                    other => {
                        return Err(CliErr::new(
                            format!("Unknown key option: {other}"),
                            EXIT_USAGE,
                            None,
                        ))
                    }
                }
                j += 1;
            }
            let key = key_name.ok_or_else(|| {
                CliErr::new("key requires KEY name (e.g. back)", EXIT_USAGE, None)
            })?;
            let resp = client.command("key", json!({ "key": key }), timeout_s)?;
            let resp = maybe_attach_screenshot(&client, timeout_s, resp, screenshot.as_deref())?;
            emit(&resp, pretty);
            Ok(0)
        }
        "wait" => {
            let mut tag: Option<String> = None;
            let mut text: Option<String> = None;
            let mut exact = false;
            let mut gone = false;
            let mut interval = 0.5f64;
            let mut j = 0;
            while j < sub_args.len() {
                match sub_args[j].as_str() {
                    "--tag" => tag = Some(next_val(sub_args, &mut j, "--tag")?),
                    "--text" => text = Some(next_val(sub_args, &mut j, "--text")?),
                    "--exact" => exact = true,
                    "--gone" => gone = true,
                    "--interval" => {
                        interval = next_val(sub_args, &mut j, "--interval")?
                            .parse()
                            .map_err(|_| CliErr::new("Invalid --interval", EXIT_USAGE, None))?;
                    }
                    other => {
                        return Err(CliErr::new(
                            format!("Unknown wait option: {other}"),
                            EXIT_USAGE,
                            None,
                        ))
                    }
                }
                j += 1;
            }
            if tag.is_none() && text.is_none() {
                return Err(CliErr::new(
                    "wait requires --tag or --text",
                    EXIT_USAGE,
                    None,
                ));
            }
            let deadline = Instant::now() + Duration::from_secs_f64(timeout_s.max(1.0));
            loop {
                let resp = client.command("ui", json!({}), timeout_s)?;
                let nodes = resp
                    .get("result")
                    .and_then(|r| r.get("nodes"))
                    .and_then(Value::as_array)
                    .cloned()
                    .unwrap_or_default();
                let matched = nodes.iter().find(|n| {
                    let tag_ok = match &tag {
                        Some(t) => n.get("tag").and_then(Value::as_str) == Some(t.as_str()),
                        None => true,
                    };
                    let text_ok = match &text {
                        Some(q) => {
                            let nt = n.get("text").and_then(Value::as_str).unwrap_or("");
                            let nd = n
                                .get("contentDescription")
                                .and_then(Value::as_str)
                                .unwrap_or("");
                            if exact {
                                nt == q || nd == q
                            } else {
                                nt.contains(q.as_str()) || nd.contains(q.as_str())
                            }
                        }
                        None => true,
                    };
                    tag_ok && text_ok
                });
                if gone && matched.is_none() {
                    emit(&json!({ "ok": true, "gone": true }), pretty);
                    return Ok(0);
                }
                if !gone {
                    if let Some(m) = matched {
                        emit(&json!({ "ok": true, "node": m }), pretty);
                        return Ok(0);
                    }
                }
                if Instant::now() >= deadline {
                    return Err(CliErr::new(
                        "Timed out waiting for UI condition",
                        EXIT_APP,
                        Some("timeout"),
                    ));
                }
                thread::sleep(Duration::from_secs_f64(interval.max(0.1)));
            }
        }
        "raw" => {
            if sub_args.is_empty() {
                return Err(CliErr::new(
                    "raw requires COMMAND [JSON_ARGS]",
                    EXIT_USAGE,
                    None,
                ));
            }
            let cmd = &sub_args[0];
            let args: Value = if sub_args.len() > 1 {
                let parsed: Value = serde_json::from_str(&sub_args[1]).map_err(|e| {
                    CliErr::new(format!("Invalid JSON args: {e}"), EXIT_USAGE, None)
                })?;
                if !parsed.is_object() {
                    return Err(CliErr::new(
                        "raw args must be a JSON object",
                        EXIT_USAGE,
                        None,
                    ));
                }
                parsed
            } else {
                json!({})
            };
            let resp = client.command(cmd, args, timeout_s)?;
            emit(&resp, pretty);
            Ok(0)
        }
        other => Err(CliErr::new(
            format!("Unknown app command: {other}"),
            EXIT_USAGE,
            None,
        )),
    }
}
