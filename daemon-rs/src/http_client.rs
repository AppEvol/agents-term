//! Minimal zero-dependency blocking HTTP/1.1 client for talking to the local/tailnet daemon
//! from `install`, `ensure-running`, and `app` (`agents_term_app_cli`) subcommands.

use serde_json::Value;
use std::collections::HashMap;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpStream, ToSocketAddrs};
use std::time::Duration;

#[derive(Debug)]
pub struct HttpResponse {
    pub status: u16,
    pub body: Vec<u8>,
}

impl HttpResponse {
    pub fn json(&self) -> Result<Value, String> {
        serde_json::from_slice(&self.body).map_err(|e| format!("Invalid JSON response: {e}"))
    }
}

pub fn http_request(
    method: &str,
    host: &str,
    port: u16,
    path: &str,
    token: Option<&str>,
    body: Option<&[u8]>,
    timeout: Duration,
) -> Result<HttpResponse, String> {
    let host_port = if host.contains(':') && !host.starts_with('[') {
        format!("[{host}]:{port}")
    } else {
        format!("{host}:{port}")
    };
    let addr = host_port
        .to_socket_addrs()
        .map_err(|e| format!("Failed to resolve {host_port}: {e}"))?
        .next()
        .ok_or_else(|| format!("No socket address for {host_port}"))?;

    let mut stream = TcpStream::connect_timeout(&addr, timeout)
        .map_err(|e| format!("Connection to {host_port} failed: {e}"))?;
    let _ = stream.set_read_timeout(Some(timeout));
    let _ = stream.set_write_timeout(Some(timeout));

    let mut req = format!("{method} {path} HTTP/1.1\r\nHost: {host_port}\r\nAccept: application/json\r\nConnection: close\r\n");
    if let Some(tok) = token.filter(|t| !t.is_empty()) {
        req.push_str(&format!("Authorization: Bearer {tok}\r\n"));
    }
    if let Some(b) = body {
        req.push_str("Content-Type: application/json\r\n");
        req.push_str(&format!("Content-Length: {}\r\n", b.len()));
    }
    req.push_str("\r\n");

    stream
        .write_all(req.as_bytes())
        .map_err(|e| format!("Write request headers failed: {e}"))?;
    if let Some(b) = body {
        stream
            .write_all(b)
            .map_err(|e| format!("Write request body failed: {e}"))?;
    }
    let _ = stream.flush();

    let mut reader = BufReader::new(stream);
    let mut status_line = String::new();
    let n = reader
        .read_line(&mut status_line)
        .map_err(|e| format!("Read status line failed: {e}"))?;
    if n == 0 {
        return Err("Empty HTTP response".to_string());
    }
    let status: u16 = status_line
        .split_whitespace()
        .nth(1)
        .and_then(|s| s.parse().ok())
        .ok_or_else(|| format!("Malformed HTTP status line: {}", status_line.trim()))?;

    let mut headers: HashMap<String, String> = HashMap::new();
    loop {
        let mut line = String::new();
        let read = reader
            .read_line(&mut line)
            .map_err(|e| format!("Read headers failed: {e}"))?;
        if read == 0 || line == "\r\n" || line == "\n" {
            break;
        }
        if let Some((k, v)) = line.split_once(':') {
            headers.insert(k.trim().to_ascii_lowercase(), v.trim().to_string());
        }
    }

    let mut out = Vec::new();
    let is_chunked = headers
        .get("transfer-encoding")
        .map(|v| v.to_ascii_lowercase().contains("chunked"))
        .unwrap_or(false);
    if is_chunked {
        loop {
            let mut size_line = String::new();
            reader
                .read_line(&mut size_line)
                .map_err(|e| format!("Read chunk size failed: {e}"))?;
            let hex = size_line.trim().split(';').next().unwrap_or("").trim();
            let chunk_len = usize::from_str_radix(hex, 16)
                .map_err(|e| format!("Invalid chunk length '{hex}': {e}"))?;
            if chunk_len == 0 {
                break;
            }
            let mut buf = vec![0u8; chunk_len];
            reader
                .read_exact(&mut buf)
                .map_err(|e| format!("Read chunk payload failed: {e}"))?;
            out.extend_from_slice(&buf);
            let mut crlf = [0u8; 2];
            let _ = reader.read_exact(&mut crlf);
        }
    } else if let Some(len) = headers
        .get("content-length")
        .and_then(|s| s.parse::<usize>().ok())
    {
        out.resize(len, 0);
        reader
            .read_exact(&mut out)
            .map_err(|e| format!("Read body failed: {e}"))?;
    } else {
        reader
            .read_to_end(&mut out)
            .map_err(|e| format!("Read body to EOF failed: {e}"))?;
    }

    Ok(HttpResponse { status, body: out })
}
