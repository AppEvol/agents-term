use chrono::Utc;
use std::io::{self, Write};

pub fn utc_iso_now() -> String {
    Utc::now().format("%Y-%m-%dT%H:%M:%S%.3fZ").to_string()
}

pub fn log_info(msg: &str) {
    let line = format!("{} [INFO] {msg}\n", utc_iso_now());
    let _ = io::stdout().write_all(line.as_bytes());
    let _ = io::stdout().flush();
}

pub fn log_warn(msg: &str) {
    let line = format!("{} [WARN] {msg}\n", utc_iso_now());
    let _ = io::stderr().write_all(line.as_bytes());
    let _ = io::stderr().flush();
}

pub fn log_error(msg: &str) {
    let line = format!("{} [ERROR] {msg}\n", utc_iso_now());
    let _ = io::stderr().write_all(line.as_bytes());
    let _ = io::stderr().flush();
}
