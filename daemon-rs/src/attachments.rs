//! Image attachments for chat messages.
//!
//! The app uploads each image to a private per-tab folder before sending the message that
//! references it. The agent is then handed the file path in its prompt and opens the file
//! itself, which works the same way for every agent CLI.

use serde_json::{json, Value};
use std::collections::HashSet;
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::os::unix::fs::OpenOptionsExt;
use std::path::{Path, PathBuf};
use std::time::{Duration, SystemTime};

use crate::config::{ensure_dir_0700, tab_attachments_dir};
use crate::util::rand_hex;

pub const MAX_ATTACHMENT_BYTES: usize = 8 * 1024 * 1024;
pub const MAX_ATTACHMENTS_PER_MESSAGE: usize = 8;
/// Uploads that no message references are removed after this long.
const STALE_UPLOAD_AGE: Duration = Duration::from_secs(24 * 60 * 60);

/// Supported image types as `(file extension, MIME type)`.
const IMAGE_TYPES: [(&str, &str); 5] = [
    ("png", "image/png"),
    ("jpg", "image/jpeg"),
    ("gif", "image/gif"),
    ("webp", "image/webp"),
    ("bmp", "image/bmp"),
];

#[derive(Debug, PartialEq, Eq)]
pub enum StageError {
    Empty,
    TooLarge,
    UnsupportedType,
    Io(String),
}

pub fn supported_mime_types() -> Vec<&'static str> {
    IMAGE_TYPES.iter().map(|(_, mime)| *mime).collect()
}

/// Identifies the image type from the file's leading bytes. The type the client claims is
/// never trusted.
pub fn sniff_image(bytes: &[u8]) -> Option<(&'static str, &'static str)> {
    let ext = if bytes.starts_with(b"\x89PNG\r\n\x1a\n") {
        "png"
    } else if bytes.starts_with(b"\xff\xd8\xff") {
        "jpg"
    } else if bytes.starts_with(b"GIF87a") || bytes.starts_with(b"GIF89a") {
        "gif"
    } else if bytes.len() >= 12 && &bytes[0..4] == b"RIFF" && &bytes[8..12] == b"WEBP" {
        "webp"
    } else if bytes.starts_with(b"BM") && bytes.len() >= 26 {
        "bmp"
    } else {
        return None;
    };
    IMAGE_TYPES.iter().find(|(e, _)| *e == ext).copied()
}

pub fn is_safe_attachment_id(id: &str) -> bool {
    id.strip_prefix("att_")
        .map(|hex| !hex.is_empty() && hex.len() <= 64 && hex.bytes().all(|b| b.is_ascii_hexdigit()))
        .unwrap_or(false)
}

fn describe(id: &str, ext: &str, mime: &str, bytes: u64, path: &Path) -> Value {
    json!({
        "id": id,
        "name": format!("{id}.{ext}"),
        "mimeType": mime,
        "bytes": bytes,
        "path": path.to_string_lossy(),
    })
}

/// Writes an uploaded image into the tab's attachment folder and returns its description.
/// The file name is chosen here, never by the client, and an existing file is never replaced.
pub fn stage_attachment(tab_id: &str, bytes: &[u8]) -> Result<Value, StageError> {
    if bytes.is_empty() {
        return Err(StageError::Empty);
    }
    if bytes.len() > MAX_ATTACHMENT_BYTES {
        return Err(StageError::TooLarge);
    }
    let (ext, mime) = sniff_image(bytes).ok_or(StageError::UnsupportedType)?;
    let dir = tab_attachments_dir(tab_id).ok_or_else(|| StageError::Io("Invalid tab".into()))?;
    ensure_dir_0700(&dir).map_err(|e| StageError::Io(e.to_string()))?;

    let id = format!("att_{}", rand_hex(8));
    let path = dir.join(format!("{id}.{ext}"));
    let written = OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .open(&path)
        .and_then(|mut file| file.write_all(bytes));
    if let Err(err) = written {
        let _ = fs::remove_file(&path);
        return Err(StageError::Io(err.to_string()));
    }
    Ok(describe(&id, ext, mime, bytes.len() as u64, &path))
}

/// Finds a staged attachment of a tab by id.
pub fn find_attachment(tab_id: &str, id: &str) -> Option<Value> {
    if !is_safe_attachment_id(id) {
        return None;
    }
    let dir = tab_attachments_dir(tab_id)?;
    IMAGE_TYPES.iter().find_map(|(ext, mime)| {
        let path = dir.join(format!("{id}.{ext}"));
        let meta = fs::metadata(&path).ok()?;
        meta.is_file()
            .then(|| describe(id, ext, mime, meta.len(), &path))
    })
}

/// Resolves the attachment ids of a message into descriptions, in order and without
/// duplicates. Fails with a user-facing reason if an id is unknown or there are too many.
pub fn resolve_attachments(tab_id: &str, raw: Option<&Value>) -> Result<Vec<Value>, String> {
    let Some(items) = raw.and_then(Value::as_array) else {
        return Ok(Vec::new());
    };
    let mut seen = HashSet::new();
    let mut resolved = Vec::new();
    for item in items {
        let id = item
            .as_str()
            .or_else(|| item.get("id").and_then(Value::as_str))
            .map(str::trim)
            .unwrap_or("");
        if id.is_empty() || !seen.insert(id.to_string()) {
            continue;
        }
        let found = find_attachment(tab_id, id)
            .ok_or_else(|| format!("Attachment not found: {id}. Attach the image again."))?;
        resolved.push(found);
    }
    if resolved.len() > MAX_ATTACHMENTS_PER_MESSAGE {
        return Err(format!(
            "A message can have at most {MAX_ATTACHMENTS_PER_MESSAGE} images"
        ));
    }
    Ok(resolved)
}

/// Keeps only well-formed attachment descriptions from persisted state.
pub fn normalize_attachments(raw: Option<&Value>) -> Vec<Value> {
    raw.and_then(Value::as_array)
        .map(|items| {
            items
                .iter()
                .filter(|a| {
                    a.get("id")
                        .and_then(Value::as_str)
                        .map(is_safe_attachment_id)
                        .unwrap_or(false)
                })
                .cloned()
                .collect()
        })
        .unwrap_or_default()
}

fn attachment_ids(attachments: Option<&Value>, into: &mut HashSet<String>) {
    for att in attachments.and_then(Value::as_array).into_iter().flatten() {
        if let Some(id) = att.get("id").and_then(Value::as_str) {
            into.insert(id.to_string());
        }
    }
}

/// Ids of every attachment a tab's history or queue still refers to.
pub fn referenced_attachment_ids(tab: &Value, messages: &[Value]) -> HashSet<String> {
    let mut ids = HashSet::new();
    for msg in messages {
        attachment_ids(msg.get("attachments"), &mut ids);
    }
    for queued in tab
        .get("queuedMessages")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
    {
        attachment_ids(queued.get("attachments"), &mut ids);
    }
    ids
}

/// Deletes the files of attachments that will never be sent (e.g. a removed queued message).
pub fn remove_attachment_files(tab_id: &str, attachments: Option<&Value>) {
    let Some(dir) = tab_attachments_dir(tab_id) else {
        return;
    };
    let mut ids = HashSet::new();
    attachment_ids(attachments, &mut ids);
    for id in ids.iter().filter(|id| is_safe_attachment_id(id)) {
        for (ext, _) in IMAGE_TYPES {
            let _ = fs::remove_file(dir.join(format!("{id}.{ext}")));
        }
    }
}

/// Removes uploads older than a day that no message or queued message refers to: images
/// the user attached and then never sent.
pub fn sweep_stale_uploads(tab_id: &str, referenced: &HashSet<String>) {
    if let Some(dir) = tab_attachments_dir(tab_id) {
        sweep_dir_older_than(&dir, referenced, STALE_UPLOAD_AGE);
    }
}

fn sweep_dir_older_than(dir: &Path, referenced: &HashSet<String>, max_age: Duration) {
    let Ok(entries) = fs::read_dir(dir) else {
        return;
    };
    let now = SystemTime::now();
    for entry in entries.flatten() {
        let path: PathBuf = entry.path();
        let id = path.file_stem().and_then(|s| s.to_str()).unwrap_or("");
        if referenced.contains(id) {
            continue;
        }
        let age = entry
            .metadata()
            .and_then(|m| m.modified())
            .ok()
            .and_then(|modified| now.duration_since(modified).ok());
        if age.map(|a| a >= max_age).unwrap_or(false) {
            let _ = fs::remove_file(&path);
        }
    }
}

/// The prompt handed to the agent CLI: the user's text followed by the image file paths.
pub fn prompt_with_attachments(text: &str, attachments: &[Value]) -> String {
    let paths: Vec<&str> = attachments
        .iter()
        .filter_map(|a| a.get("path").and_then(Value::as_str))
        .collect();
    if paths.is_empty() {
        return text.to_string();
    }
    let noun = if paths.len() == 1 { "image" } else { "images" };
    let mut out = String::new();
    let text = text.trim();
    if text.is_empty() {
        out.push_str(&format!("Look at the attached {noun}."));
    } else {
        out.push_str(text);
    }
    out.push_str(&format!(
        "\n\nThe user attached {} {noun} to this message. Open each file to view it:\n",
        paths.len()
    ));
    for path in paths {
        out.push_str(&format!("- {path}\n"));
    }
    out.trim_end().to_string()
}

/// Short transcript-free label for a message that has images, e.g. for the tab preview.
pub fn attachments_label(count: usize) -> String {
    if count == 1 {
        "[1 image]".to_string()
    } else {
        format!("[{count} images]")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sniffs_supported_image_types_and_rejects_others() {
        assert_eq!(
            sniff_image(b"\x89PNG\r\n\x1a\nrest"),
            Some(("png", "image/png"))
        );
        assert_eq!(
            sniff_image(b"\xff\xd8\xff\xe0rest"),
            Some(("jpg", "image/jpeg"))
        );
        assert_eq!(sniff_image(b"GIF89a..."), Some(("gif", "image/gif")));
        assert_eq!(
            sniff_image(b"RIFF\x00\x00\x00\x00WEBPVP8 "),
            Some(("webp", "image/webp"))
        );
        assert_eq!(sniff_image(b"#!/bin/sh\nrm -rf /"), None);
        assert_eq!(sniff_image(b"<svg xmlns='x'/>"), None);
        assert_eq!(sniff_image(b""), None);
    }

    #[test]
    fn attachment_ids_cannot_escape_the_tab_folder() {
        assert!(is_safe_attachment_id("att_0123abcdef"));
        assert!(!is_safe_attachment_id("att_"));
        assert!(!is_safe_attachment_id("att_../../etc/passwd"));
        assert!(!is_safe_attachment_id("../att_00"));
        assert!(!is_safe_attachment_id("att_00.png"));
    }

    #[test]
    fn prompt_lists_image_paths_after_the_text() {
        let atts = vec![
            json!({ "path": "/x/att_1.png" }),
            json!({ "path": "/x/att_2.jpg" }),
        ];
        let prompt = prompt_with_attachments("What is wrong here?", &atts);
        assert!(prompt.starts_with("What is wrong here?\n\n"));
        assert!(prompt.contains("attached 2 images"));
        assert!(prompt.ends_with("- /x/att_1.png\n- /x/att_2.jpg"));

        let image_only = prompt_with_attachments("  ", &atts[..1]);
        assert!(image_only.starts_with("Look at the attached image."));
        assert!(image_only.ends_with("- /x/att_1.png"));

        assert_eq!(prompt_with_attachments("plain", &[]), "plain");
    }

    #[test]
    fn sweep_removes_only_old_unreferenced_uploads() {
        let dir = std::env::temp_dir().join(format!("agentsterm-att-sweep-{}", rand_hex(6)));
        fs::create_dir_all(&dir).unwrap();
        fs::write(dir.join("att_aa.png"), b"kept").unwrap();
        fs::write(dir.join("att_bb.png"), b"dropped").unwrap();
        let referenced: HashSet<String> = ["att_aa".to_string()].into_iter().collect();

        sweep_dir_older_than(&dir, &referenced, STALE_UPLOAD_AGE);
        assert!(
            dir.join("att_bb.png").exists(),
            "a fresh upload must survive"
        );

        sweep_dir_older_than(&dir, &referenced, Duration::ZERO);
        assert!(dir.join("att_aa.png").exists());
        assert!(!dir.join("att_bb.png").exists());
        let _ = fs::remove_dir_all(&dir);
    }
}
