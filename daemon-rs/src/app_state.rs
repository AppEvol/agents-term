use regex::Regex;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;
use tokio::sync::{mpsc, oneshot, Notify};

use crate::util::{now_ms, rand_hex};

pub const DEFAULT_COMMAND_TIMEOUT_MS: u64 = 15_000;
pub const MAX_COMMAND_TIMEOUT_MS: u64 = 120_000;
/// How long after a socket connects it may still send its first `app_hello`.
/// The app sends it right after connecting; until this has passed, a socket
/// without one is still connecting rather than an app too old to report.
const HELLO_GRACE_MS: u64 = 2_000;

fn command_name_re() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| Regex::new(r"^[a-z][a-z0-9_.-]{0,63}$").unwrap())
}

#[derive(Clone, Debug)]
pub struct AppStateError {
    pub code: &'static str,
    pub message: String,
    pub http_status: u16,
}

impl AppStateError {
    pub fn new(code: &'static str, message: impl Into<String>) -> Self {
        let http_status = match code {
            "no_app_connected" | "app_disconnected" => 503,
            "unsupported_app" => 501,
            "inspection_disabled" | "control_disabled" | "recording_disabled" => 403,
            "timeout" => 504,
            "app_error" => 502,
            _ => 400,
        };
        Self {
            code,
            message: message.into(),
            http_status,
        }
    }
}

#[derive(Clone, Debug)]
struct HelloInfo {
    enabled: bool,
    app_version: Option<String>,
    device: Option<String>,
    commands: Vec<String>,
    control_enabled: bool,
    control_commands: Vec<String>,
    recording_enabled: bool,
}

fn str_or_none(v: Option<&Value>) -> Option<String> {
    v.and_then(Value::as_str)
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_string)
}

fn str_vec(v: Option<&Value>) -> Vec<String> {
    v.and_then(Value::as_array)
        .map(|arr| {
            arr.iter()
                .filter_map(Value::as_str)
                .map(str::to_string)
                .collect()
        })
        .unwrap_or_default()
}

fn parse_hello(raw: Option<&Value>) -> HelloInfo {
    let obj = raw.and_then(Value::as_object);
    HelloInfo {
        enabled: obj.and_then(|o| o.get("enabled")).and_then(Value::as_bool) == Some(true),
        app_version: str_or_none(obj.and_then(|o| o.get("appVersion"))),
        device: str_or_none(obj.and_then(|o| o.get("device"))),
        commands: str_vec(obj.and_then(|o| o.get("commands"))),
        control_enabled: obj
            .and_then(|o| o.get("controlEnabled"))
            .and_then(Value::as_bool)
            == Some(true),
        control_commands: str_vec(obj.and_then(|o| o.get("controlCommands"))),
        recording_enabled: obj
            .and_then(|o| o.get("recordingEnabled"))
            .and_then(Value::as_bool)
            == Some(true),
    }
}

struct ClientEntry {
    seq: u64,
    client_id: String,
    tx: mpsc::UnboundedSender<String>,
    connected_at: i64,
    hello: Option<HelloInfo>,
    hello_at: Option<i64>,
}

impl ClientEntry {
    fn summary(&self) -> Value {
        let hello = self.hello.as_ref();
        let enabled = hello.map(|h| h.enabled).unwrap_or(false);
        let control_enabled = enabled && hello.map(|h| h.control_enabled).unwrap_or(false);
        let supports_control = hello
            .map(|h| !h.control_commands.is_empty())
            .unwrap_or(false);
        json!({
            "clientId": self.client_id,
            "connectedAt": self.connected_at,
            "helloAt": self.hello_at,
            "enabled": enabled,
            "supportsReporter": self.hello.is_some(),
            "appVersion": hello.and_then(|h| h.app_version.clone()),
            "device": hello.and_then(|h| h.device.clone()),
            "commands": hello.map(|h| h.commands.clone()).unwrap_or_default(),
            "supportsControl": supports_control,
            "controlEnabled": control_enabled,
            "recordingEnabled": enabled && hello.map(|h| h.recording_enabled).unwrap_or(false),
        })
    }
}

struct PendingRequest {
    client_id: String,
    command: String,
    started_at: i64,
    tx: oneshot::Sender<Result<Value, AppStateError>>,
}

struct BrokerInner {
    next_seq: u64,
    clients: HashMap<u64, ClientEntry>,
    pending: HashMap<String, PendingRequest>,
}

#[derive(Clone)]
pub struct AppStateBroker {
    inner: Arc<Mutex<BrokerInner>>,
    /// Signalled when a client says hello or disconnects.
    clients_changed: Arc<Notify>,
    hello_grace_ms: u64,
}

impl AppStateBroker {
    pub fn new() -> Self {
        Self::with_hello_grace(HELLO_GRACE_MS)
    }

    fn with_hello_grace(hello_grace_ms: u64) -> Self {
        Self {
            clients_changed: Arc::new(Notify::new()),
            hello_grace_ms,
            inner: Arc::new(Mutex::new(BrokerInner {
                next_seq: 1,
                clients: HashMap::new(),
                pending: HashMap::new(),
            })),
        }
    }

    pub fn add_socket(&self, tx: mpsc::UnboundedSender<String>) -> u64 {
        let mut inner = self.inner.lock().unwrap();
        let seq = inner.next_seq;
        inner.next_seq += 1;
        inner.clients.insert(
            seq,
            ClientEntry {
                seq,
                client_id: format!("c{seq}"),
                tx,
                connected_at: now_ms(),
                hello: None,
                hello_at: None,
            },
        );
        seq
    }

    pub fn remove_socket(&self, id: u64) {
        let mut to_fail = Vec::new();
        {
            let mut inner = self.inner.lock().unwrap();
            let Some(client) = inner.clients.remove(&id) else {
                return;
            };
            self.clients_changed.notify_waiters();
            let keys: Vec<String> = inner
                .pending
                .iter()
                .filter(|(_, p)| p.client_id == client.client_id)
                .map(|(k, _)| k.clone())
                .collect();
            for k in keys {
                if let Some(req) = inner.pending.remove(&k) {
                    to_fail.push(req);
                }
            }
        }
        for req in to_fail {
            let _ = req.tx.send(Err(AppStateError::new(
                "app_disconnected",
                format!("App disconnected before answering '{}'", req.command),
            )));
        }
    }

    pub fn handle_message(&self, id: u64, raw: &str) -> bool {
        let Ok(Value::Object(msg)) = serde_json::from_str::<Value>(raw) else {
            return false;
        };
        let msg_type = msg.get("type").and_then(Value::as_str).unwrap_or("");
        if msg_type == "app_hello" {
            let mut inner = self.inner.lock().unwrap();
            if let Some(client) = inner.clients.get_mut(&id) {
                client.hello = Some(parse_hello(msg.get("reporter")));
                client.hello_at = Some(now_ms());
                self.clients_changed.notify_waiters();
                return true;
            }
            return false;
        }
        if msg_type == "app_state_response" {
            let request_id = msg.get("requestId").and_then(Value::as_str).unwrap_or("");
            let pending = {
                let mut inner = self.inner.lock().unwrap();
                let client_id = inner.clients.get(&id).map(|c| c.client_id.clone());
                let Some(cid) = client_id else {
                    return false;
                };
                if inner
                    .pending
                    .get(request_id)
                    .map(|p| p.client_id == cid)
                    .unwrap_or(false)
                {
                    inner.pending.remove(request_id)
                } else {
                    return true;
                }
            };
            if let Some(req) = pending {
                let ok = msg.get("ok").and_then(Value::as_bool) == Some(true);
                let elapsed_ms = (now_ms() - req.started_at).max(0);
                if ok {
                    let result = msg.get("result").cloned().unwrap_or(Value::Null);
                    let _ = req.tx.send(Ok(json!({
                        "clientId": req.client_id,
                        "command": req.command,
                        "elapsedMs": elapsed_ms,
                        "result": result,
                    })));
                } else {
                    let err_msg = str_or_none(msg.get("error"))
                        .unwrap_or_else(|| format!("App rejected '{}'", req.command));
                    let code_str = msg
                        .get("code")
                        .and_then(Value::as_str)
                        .unwrap_or("app_error");
                    let code: &'static str = match code_str {
                        "inspection_disabled" => "inspection_disabled",
                        "control_disabled" => "control_disabled",
                        "recording_disabled" => "recording_disabled",
                        "bad_request" => "bad_request",
                        "timeout" => "timeout",
                        _ => "app_error",
                    };
                    let _ = req.tx.send(Err(AppStateError::new(code, err_msg)));
                }
            }
            return true;
        }
        false
    }

    /// Waits while the only connected clients are ones that connected moments
    /// ago and have not said hello yet, so that an app that is still
    /// connecting is not reported as `unsupported_app`. Returns at once in
    /// every other case, and after `hello_grace_ms` at the latest.
    pub async fn wait_for_hello(&self) {
        loop {
            let changed = self.clients_changed.notified();
            tokio::pin!(changed);
            // Register before looking, so a hello that lands in between is not missed.
            changed.as_mut().enable();
            let remaining_ms = {
                let inner = self.inner.lock().unwrap();
                if inner.clients.values().any(|c| c.hello.is_some()) {
                    return;
                }
                let now = now_ms();
                let grace = self.hello_grace_ms as i64;
                inner
                    .clients
                    .values()
                    .map(|c| c.connected_at + grace - now)
                    .max()
                    .unwrap_or(0)
            };
            if remaining_ms <= 0 {
                return;
            }
            let _ = tokio::time::timeout(Duration::from_millis(remaining_ms as u64), changed).await;
        }
    }

    pub fn status(&self) -> Value {
        let inner = self.inner.lock().unwrap();
        let mut clients: Vec<&ClientEntry> = inner.clients.values().collect();
        clients.sort_by_key(|c| c.seq);

        let summaries: Vec<Value> = clients.iter().map(|c| c.summary()).collect();
        let ready_client = clients
            .iter()
            .rev()
            .find(|c| c.hello.as_ref().map(|h| h.enabled).unwrap_or(false))
            .copied();
        let control_client = clients
            .iter()
            .rev()
            .find(|c| {
                c.hello
                    .as_ref()
                    .map(|h| h.enabled && h.control_enabled)
                    .unwrap_or(false)
            })
            .copied();
        let reason = if ready_client.is_some() {
            None
        } else if clients.is_empty() {
            Some("no_app_connected")
        } else if clients.iter().any(|c| c.hello.is_some()) {
            Some("inspection_disabled")
        } else {
            Some("unsupported_app")
        };
        let control_reason = if control_client.is_some() {
            None
        } else if let Some(r) = reason {
            Some(r)
        } else if clients.iter().any(|c| {
            c.hello
                .as_ref()
                .map(|h| !h.control_commands.is_empty())
                .unwrap_or(false)
        }) {
            Some("control_disabled")
        } else {
            Some("unsupported_app")
        };

        json!({
            "connectedClients": clients.len(),
            "ready": ready_client.is_some(),
            "reason": reason,
            "activeClient": ready_client.map(|c| c.summary()),
            "controlReady": control_client.is_some(),
            "controlReason": control_reason,
            "clients": summaries,
        })
    }

    pub async fn request(
        &self,
        command: &str,
        args: Value,
        timeout_ms: u64,
    ) -> Result<Value, AppStateError> {
        if !command_name_re().is_match(command) {
            return Err(AppStateError::new(
                "bad_request",
                "command must be a lowercase identifier (e.g. 'state', 'logs', 'screenshot')",
            ));
        }
        let clamped_ms = timeout_ms.clamp(1_000, MAX_COMMAND_TIMEOUT_MS);
        let request_id = format!("req_{}", rand_hex(8));
        self.wait_for_hello().await;

        let (rx, tx_ws) = {
            let mut inner = self.inner.lock().unwrap();
            let mut clients: Vec<&ClientEntry> = inner.clients.values().collect();
            clients.sort_by_key(|c| c.seq);

            if clients.is_empty() {
                return Err(AppStateError::new(
                    "no_app_connected",
                    "No AgentsTerm app is connected to this daemon",
                ));
            }
            let with_hello: Vec<&ClientEntry> = clients
                .iter()
                .copied()
                .filter(|c| c.hello.is_some())
                .collect();
            if with_hello.is_empty() {
                return Err(AppStateError::new(
                    "unsupported_app",
                    "Connected app build does not support remote state reporting; update the app",
                ));
            }
            let enabled: Vec<&ClientEntry> = with_hello
                .iter()
                .copied()
                .filter(|c| c.hello.as_ref().map(|h| h.enabled).unwrap_or(false))
                .collect();
            if enabled.is_empty() {
                return Err(AppStateError::new(
                    "inspection_disabled",
                    "Remote inspection is turned off in the app (Settings > Debug sharing > Allow VM agents to inspect the app)",
                ));
            }

            let target = *enabled.last().unwrap();
            let hello = target.hello.as_ref().unwrap();
            let is_control_cmd = hello.control_commands.iter().any(|c| c == command);
            if is_control_cmd && !hello.control_enabled {
                return Err(AppStateError::new(
                    "control_disabled",
                    "Remote UI control is turned off in the app (Settings > Debug sharing > Allow VM agents to control the app)",
                ));
            }

            let client_id = target.client_id.clone();
            let ws_tx = target.tx.clone();
            let (otx, orx) = oneshot::channel();
            inner.pending.insert(
                request_id.clone(),
                PendingRequest {
                    client_id,
                    command: command.to_string(),
                    started_at: now_ms(),
                    tx: otx,
                },
            );
            (orx, ws_tx)
        };

        let frame = json!({
            "type": "app_state_request",
            "requestId": request_id,
            "command": command,
            "args": args,
        });
        if tx_ws.send(frame.to_string()).is_err() {
            let mut inner = self.inner.lock().unwrap();
            inner.pending.remove(&request_id);
            return Err(AppStateError::new(
                "app_disconnected",
                format!("App disconnected before receiving '{command}'"),
            ));
        }

        match tokio::time::timeout(Duration::from_millis(clamped_ms), rx).await {
            Ok(Ok(res)) => res,
            Ok(Err(_)) => Err(AppStateError::new(
                "app_disconnected",
                format!("App disconnected before answering '{command}'"),
            )),
            Err(_) => {
                let mut inner = self.inner.lock().unwrap();
                inner.pending.remove(&request_id);
                Err(AppStateError::new(
                    "timeout",
                    format!(
                        "Timed out after {clamped_ms}ms waiting for app response to '{command}'"
                    ),
                ))
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Instant;

    const HELLO: &str = r#"{"type":"app_hello","reporter":{"enabled":true,"commands":["state"]}}"#;

    /// Answers the next request the daemon sends down `rx`, as the app would.
    async fn answer_next(
        broker: &AppStateBroker,
        id: u64,
        rx: &mut mpsc::UnboundedReceiver<String>,
    ) {
        let frame: Value = serde_json::from_str(&rx.recv().await.unwrap()).unwrap();
        let reply = json!({
            "type": "app_state_response",
            "requestId": frame["requestId"],
            "ok": true,
            "result": {"screen": "chat"},
        });
        assert!(broker.handle_message(id, &reply.to_string()));
    }

    #[tokio::test]
    async fn request_waits_for_the_hello_of_a_client_that_just_connected() {
        let broker = AppStateBroker::with_hello_grace(5_000);
        let (tx, mut rx) = mpsc::unbounded_channel();
        let id = broker.add_socket(tx);

        let app = broker.clone();
        let app_task = tokio::spawn(async move {
            tokio::time::sleep(Duration::from_millis(50)).await;
            assert!(app.handle_message(id, HELLO));
            answer_next(&app, id, &mut rx).await;
        });

        let started = Instant::now();
        let res = broker.request("state", json!({}), 5_000).await.unwrap();
        assert_eq!(res["result"]["screen"], json!("chat"));
        // It went on as soon as the hello arrived, not at the end of the grace period.
        assert!(started.elapsed() < Duration::from_millis(4_000));
        app_task.await.unwrap();
    }

    #[tokio::test]
    async fn the_app_can_refuse_a_command_because_recording_is_off() {
        let broker = AppStateBroker::with_hello_grace(5_000);
        let (tx, mut rx) = mpsc::unbounded_channel();
        let id = broker.add_socket(tx);
        assert!(broker.handle_message(id, HELLO));
        assert_eq!(
            broker.status()["activeClient"]["recordingEnabled"],
            json!(false)
        );

        let app = broker.clone();
        let app_task = tokio::spawn(async move {
            let frame: Value = serde_json::from_str(&rx.recv().await.unwrap()).unwrap();
            let reply = json!({
                "type": "app_state_response",
                "requestId": frame["requestId"],
                "ok": false,
                "code": "recording_disabled",
                "error": "Screen recording is disabled in the app",
            });
            assert!(app.handle_message(id, &reply.to_string()));
        });
        let err = broker
            .request("record_start", json!({}), 5_000)
            .await
            .unwrap_err();
        assert_eq!(err.code, "recording_disabled");
        assert_eq!(err.http_status, 403);
        app_task.await.unwrap();

        let hello = r#"{"type":"app_hello","reporter":{"enabled":true,"recordingEnabled":true}}"#;
        assert!(broker.handle_message(id, hello));
        assert_eq!(
            broker.status()["activeClient"]["recordingEnabled"],
            json!(true)
        );
    }

    #[tokio::test]
    async fn status_is_ready_after_waiting_for_the_hello() {
        let broker = AppStateBroker::with_hello_grace(5_000);
        let (tx, _rx) = mpsc::unbounded_channel();
        let id = broker.add_socket(tx);
        // Before the hello the client is indistinguishable from an old app.
        assert_eq!(broker.status()["reason"], json!("unsupported_app"));

        let app = broker.clone();
        tokio::spawn(async move {
            tokio::time::sleep(Duration::from_millis(50)).await;
            app.handle_message(id, HELLO);
        });
        broker.wait_for_hello().await;
        let status = broker.status();
        assert_eq!(status["ready"], json!(true));
        assert_eq!(status["reason"], Value::Null);
    }

    #[tokio::test]
    async fn a_client_that_never_says_hello_is_unsupported_once_the_grace_period_ends() {
        let broker = AppStateBroker::with_hello_grace(150);
        let (tx, _rx) = mpsc::unbounded_channel();
        broker.add_socket(tx);

        let started = Instant::now();
        let err = broker.request("state", json!({}), 5_000).await.unwrap_err();
        assert_eq!(err.code, "unsupported_app");
        assert!(started.elapsed() >= Duration::from_millis(100));

        // The grace period is over: later calls do not wait again.
        let started = Instant::now();
        broker.wait_for_hello().await;
        let err = broker.request("state", json!({}), 5_000).await.unwrap_err();
        assert_eq!(err.code, "unsupported_app");
        assert!(started.elapsed() < Duration::from_millis(100));
        assert_eq!(broker.status()["reason"], json!("unsupported_app"));
    }

    #[tokio::test]
    async fn waiting_ends_when_the_connecting_client_disconnects() {
        let broker = AppStateBroker::with_hello_grace(5_000);
        let (tx, _rx) = mpsc::unbounded_channel();
        let id = broker.add_socket(tx);

        let app = broker.clone();
        tokio::spawn(async move {
            tokio::time::sleep(Duration::from_millis(50)).await;
            app.remove_socket(id);
        });
        let started = Instant::now();
        let err = broker.request("state", json!({}), 5_000).await.unwrap_err();
        assert_eq!(err.code, "no_app_connected");
        assert!(started.elapsed() < Duration::from_millis(4_000));
    }

    #[tokio::test]
    async fn nothing_waits_without_clients_or_once_a_client_has_said_hello() {
        let broker = AppStateBroker::with_hello_grace(5_000);
        let started = Instant::now();
        let err = broker.request("state", json!({}), 5_000).await.unwrap_err();
        assert_eq!(err.code, "no_app_connected");

        // A client with inspection off has said hello; a second one still connecting
        // does not hold the answer back.
        let (tx, _rx) = mpsc::unbounded_channel();
        let id = broker.add_socket(tx);
        broker.handle_message(id, r#"{"type":"app_hello","reporter":{"enabled":false}}"#);
        let (tx2, _rx2) = mpsc::unbounded_channel();
        broker.add_socket(tx2);
        let err = broker.request("state", json!({}), 5_000).await.unwrap_err();
        assert_eq!(err.code, "inspection_disabled");
        assert!(started.elapsed() < Duration::from_millis(4_000));
    }
}
