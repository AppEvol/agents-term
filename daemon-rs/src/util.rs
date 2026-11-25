use rand::RngCore;
use regex::Regex;
use serde_json::Value;
use std::sync::OnceLock;
use std::time::{SystemTime, UNIX_EPOCH};

pub const TASK_SUMMARY_OPEN_TAG: &str = "<tab_task_summary>";
pub const TASK_SUMMARY_CLOSE_TAG: &str = "</tab_task_summary>";
pub const TASK_SUMMARY_PROMPT_ADDENDUM: &str =
    "Before your response, output a single line wrapped in \
<tab_task_summary>...</tab_task_summary> containing a concise one-sentence summary \
(max 120 characters) of what this tab's task is about. \
Do not mention these tags in your response.";

pub fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

pub fn rand_hex(n_bytes: usize) -> String {
    let mut buf = vec![0u8; n_bytes];
    rand::thread_rng().fill_bytes(&mut buf);
    buf.iter().map(|b| format!("{b:02x}")).collect()
}

pub fn is_number(v: &Value) -> bool {
    v.is_number()
}

pub fn is_finite_number(v: &Value) -> Option<f64> {
    v.as_f64().filter(|n| n.is_finite())
}

pub fn js_truthy(v: &Value) -> bool {
    match v {
        Value::Null => false,
        Value::Bool(b) => *b,
        Value::Number(n) => n.as_f64().map(|f| f != 0.0 && !f.is_nan()).unwrap_or(false),
        Value::String(s) => !s.is_empty(),
        Value::Array(_) | Value::Object(_) => true,
    }
}

pub fn js_str(v: &Value) -> String {
    match v {
        Value::Null => "null".to_string(),
        Value::Bool(true) => "true".to_string(),
        Value::Bool(false) => "false".to_string(),
        Value::Number(n) => {
            if let Some(i) = n.as_i64() {
                i.to_string()
            } else if let Some(u) = n.as_u64() {
                u.to_string()
            } else if let Some(f) = n.as_f64() {
                if f.fract() == 0.0 && f.abs() < 1e21 {
                    format!("{}", f as i64)
                } else {
                    f.to_string()
                }
            } else {
                n.to_string()
            }
        }
        Value::String(s) => s.clone(),
        Value::Object(_) => "[object Object]".to_string(),
        Value::Array(arr) => arr
            .iter()
            .map(|item| {
                if item.is_null() {
                    String::new()
                } else {
                    js_str(item)
                }
            })
            .collect::<Vec<_>>()
            .join(","),
    }
}

pub fn js_round(x: f64) -> i64 {
    (x + 0.5).floor() as i64
}

pub fn json_compact(v: &Value) -> String {
    serde_json::to_string(v).unwrap_or_default()
}

pub fn json_pretty(v: &Value) -> String {
    serde_json::to_string_pretty(v).unwrap_or_default()
}

fn ws_re() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| Regex::new(r"\s+").unwrap())
}

fn code_fence_re() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| Regex::new(r"(?s)```[^\n]*\n?(.*?)```").unwrap())
}

fn markdown_link_re() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| Regex::new(r"\[([^\]]+)\]\([^)]+\)").unwrap())
}

fn markdown_line_prefix_re() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| Regex::new(r"^\s*(?:#{1,6}\s+|>\s+|[-*+]\s+)").unwrap())
}

fn task_summary_wrapper_re() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| Regex::new(r"(?i)</?tab_task_summary>").unwrap())
}

fn task_summary_tag_re() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| Regex::new(r"(?s)<tab_task_summary>.*?</tab_task_summary>\s*").unwrap())
}

fn model_slash_re() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| Regex::new(r"(?i)^/model(?:\s|$)").unwrap())
}

fn leading_cd_re() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| {
        Regex::new(r#"^\s*cd(?:\s+(?:-[LP]|--))?\s+(?:"[^"]*"|'[^']*'|(?:\\.|[^\s;&|\n])+)\s*(?:&&|;|\r?\n)\s*"#)
            .unwrap()
    })
}

fn shell_wrapper_re() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| {
        Regex::new(
            r#"(?s)^\s*(?:/(?:usr/)?bin/)?(?:ba|z)?sh\s+-[a-z]*c\s+(?:"([^"]*)"|'([^']*)')\s*$"#,
        )
        .unwrap()
    })
}

fn fenced_code_re() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| Regex::new(r"(?s)```.*?```").unwrap())
}

fn negated_close_tab_re() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| {
        Regex::new(r"(?i)\b(?:do\s+not|don't|cannot|can't|avoid|never|wait\s+before)\b[^\n.!?]{0,40}\bclose\s+(?:this|the)\s+tab\b").unwrap()
    })
}

fn close_tab_cue_re() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| {
        Regex::new(
            r"(?i)(?:\b(?:you\s+(?:can|may)|feel\s+free\s+to|(?:it(?:'s|\s+is)\s+)?safe\s+to|safely|good\s+to|ready\s+to)\s+(?:now\s+)?(?:safely\s+)?close\s+(?:this|the)\s+tab\b|\bclose\s+(?:this|the)\s+tab\s+(?:now|whenever|if\s+you(?:'d|\s+would)?\s+like)\b)",
        )
        .unwrap()
    })
}

fn blank_line_split_re() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| Regex::new(r"\n\s*\n").unwrap())
}

pub fn truncate(s: &str, max_len: usize) -> String {
    let single = ws_re().replace_all(s, " ");
    let trimmed = single.trim();
    let count = trimmed.chars().count();
    if count > max_len {
        let cut: String = trimmed.chars().take(max_len.saturating_sub(1)).collect();
        format!("{cut}…")
    } else {
        trimmed.to_string()
    }
}

pub fn summarize_task_prompt(raw: &str, max_len: usize) -> Option<String> {
    let cleaned = task_summary_wrapper_re().replace_all(raw, "");
    let cleaned = cleaned.trim();
    if cleaned.is_empty() {
        return None;
    }
    let without_fences = code_fence_re().replace_all(cleaned, " ");
    let source = if !without_fences.trim().is_empty() {
        without_fences.trim().to_string()
    } else {
        code_fence_re().replace_all(cleaned, "$1").to_string()
    };
    let source = markdown_link_re()
        .replace_all(&source, "$1")
        .replace('`', "");
    let lines: Vec<String> = source
        .lines()
        .map(|l| markdown_line_prefix_re().replace(l, "").trim().to_string())
        .filter(|l| !l.is_empty())
        .collect();
    let joined = lines.join(" ");
    let single = ws_re().replace_all(&joined, " ");
    let single = single.trim();
    if single.is_empty() {
        return None;
    }
    let chars: Vec<char> = single.chars().collect();
    if chars.len() <= max_len {
        return Some(single.to_string());
    }
    let limit = max_len.saturating_sub(1);
    let mut cut: String = chars.into_iter().take(limit).collect();
    cut = cut.trim_end().to_string();
    let threshold = ((limit as f64) * 0.65) as usize;
    if let Some(space_idx) = cut.rfind(' ') {
        let char_pos = cut[..space_idx].chars().count();
        if char_pos >= threshold {
            cut = cut[..space_idx]
                .trim_end_matches(|c: char| c.is_whitespace() || matches!(c, ',' | ';' | ':' | '-'))
                .to_string();
        }
    }
    Some(format!("{cut}…"))
}

pub fn derive_task_summary_from_messages(messages: &[Value], max_len: usize) -> Option<String> {
    for msg in messages {
        if msg.get("role").and_then(Value::as_str) != Some("user") {
            continue;
        }
        let Some(text) = msg.get("text").and_then(Value::as_str) else {
            continue;
        };
        let trimmed = text.trim();
        if trimmed.is_empty() || model_slash_re().is_match(trimmed) {
            continue;
        }
        if let Some(summary) = summarize_task_prompt(trimmed, max_len) {
            return Some(summary);
        }
    }
    None
}

pub fn summarize_command(command: &str) -> String {
    let mut s = command.trim().to_string();
    if s.is_empty() {
        return String::new();
    }
    if let Some(caps) = shell_wrapper_re().captures(&s) {
        let inner = caps
            .get(1)
            .or_else(|| caps.get(2))
            .map(|m| m.as_str().trim())
            .unwrap_or("");
        if !inner.is_empty() {
            s = inner.to_string();
        }
    }
    while let Some(m) = leading_cd_re().find(&s) {
        let rest = s[m.end()..].trim();
        if rest.is_empty() {
            break;
        }
        s = rest.to_string();
    }
    truncate(&s, 120)
}

pub fn summarize_tool_input(value: Option<&Value>) -> String {
    let Some(val) = value else {
        return String::new();
    };
    if val.is_null() {
        return String::new();
    }
    if let Some(s) = val.as_str() {
        return summarize_command(s);
    }
    if let Some(obj) = val.as_object() {
        for key in ["description", "toolSummary", "summary", "title"] {
            if let Some(s) = obj.get(key).and_then(Value::as_str) {
                if !s.trim().is_empty() {
                    return truncate(s, 120);
                }
            }
        }
        for key in ["command", "CommandLine"] {
            if let Some(s) = obj.get(key).and_then(Value::as_str) {
                if !s.trim().is_empty() {
                    return summarize_command(s);
                }
            }
        }
        for key in ["file_path", "path", "Path", "pattern", "query", "url"] {
            if let Some(s) = obj.get(key).and_then(Value::as_str) {
                if !s.trim().is_empty() {
                    return truncate(s, 120);
                }
            }
        }
        return truncate(&json_compact(val), 120);
    }
    if val.is_array() {
        return truncate(&json_compact(val), 120);
    }
    js_str(val)
}

pub fn is_task_finished_response(text: Option<&str>) -> bool {
    let Some(t) = text else {
        return false;
    };
    if t.trim().is_empty() {
        return false;
    }
    let without_code = fenced_code_re().replace_all(t, "");
    let without_code = without_code.trim();
    if without_code.is_empty() {
        return false;
    }
    let paragraphs: Vec<&str> = blank_line_split_re()
        .split(without_code)
        .map(str::trim)
        .filter(|p| !p.is_empty())
        .collect();
    let last = paragraphs.last().copied().unwrap_or(without_code);
    let chars: Vec<char> = last.chars().collect();
    let tail: String = if chars.len() > 320 {
        chars[chars.len() - 320..].iter().collect()
    } else {
        last.to_string()
    };
    if negated_close_tab_re().is_match(&tail) {
        return false;
    }
    close_tab_cue_re().is_match(&tail)
}

pub fn pad_first_prompt_for_task_summary(prompt: &str) -> String {
    format!("{prompt}\n\n<task_summary_request>\n{TASK_SUMMARY_PROMPT_ADDENDUM}\n</task_summary_request>")
}

pub fn block_separator(existing: Option<&str>) -> &'static str {
    let Some(s) = existing else {
        return "";
    };
    if s.is_empty() || s.ends_with("\n\n") {
        ""
    } else if s.ends_with('\n') {
        "\n"
    } else {
        "\n\n"
    }
}

#[derive(Default)]
pub struct BlockBreaks {
    text: bool,
    thinking: bool,
}

impl BlockBreaks {
    pub fn start(&mut self, block_type: Option<&str>) {
        match block_type {
            Some("text") => self.text = true,
            Some("thinking") => self.thinking = true,
            _ => {}
        }
    }

    pub fn take_text(&mut self, existing: Option<&str>) -> &'static str {
        let pending = std::mem::replace(&mut self.text, false);
        if pending {
            block_separator(existing)
        } else {
            ""
        }
    }

    pub fn take_thinking(&mut self, existing: Option<&str>) -> &'static str {
        let pending = std::mem::replace(&mut self.thinking, false);
        if pending {
            block_separator(existing)
        } else {
            ""
        }
    }
}

pub struct TaskSummaryStreamFilter {
    done: bool,
    in_tag: bool,
    buf: String,
    emitted_any: bool,
    strip_post_tag_ws: bool,
    pub summary: Option<String>,
}

impl TaskSummaryStreamFilter {
    const MAX_TAG_BODY_LEN: usize = 400;
    const MAX_LEADING_WS_HOLD: usize = 8;

    pub fn new() -> Self {
        Self {
            done: false,
            in_tag: false,
            buf: String::new(),
            emitted_any: false,
            strip_post_tag_ws: false,
            summary: None,
        }
    }

    fn open_tag_prefix_suffix_len(text: &str) -> usize {
        let open_bytes = TASK_SUMMARY_OPEN_TAG.as_bytes();
        let text_bytes = text.as_bytes();
        let max_k = text_bytes.len().min(open_bytes.len().saturating_sub(1));
        for k in (1..=max_k).rev() {
            if open_bytes.starts_with(&text_bytes[text_bytes.len() - k..]) {
                return k;
            }
        }
        0
    }

    fn consume_post_tag_ws(&mut self, text: &str) -> String {
        if !self.strip_post_tag_ws || text.is_empty() {
            return text.to_string();
        }
        let stripped = if !self.emitted_any {
            text.trim_start_matches([' ', '\t', '\r', '\n'])
        } else {
            text.trim_start_matches(['\r', '\n'])
        };
        if !stripped.is_empty() {
            self.strip_post_tag_ws = false;
        }
        stripped.to_string()
    }

    pub fn feed(&mut self, chunk: &str) -> (String, Option<String>) {
        if chunk.is_empty() {
            return (String::new(), None);
        }
        if self.done {
            let mut out = chunk.to_string();
            if !self.emitted_any {
                out = task_summary_tag_re().replace(&out, "").to_string();
            }
            out = self.consume_post_tag_ws(&out);
            if !out.is_empty() {
                self.emitted_any = true;
            }
            return (out, None);
        }

        let mut data = std::mem::take(&mut self.buf);
        data.push_str(chunk);
        let mut visible = String::new();
        let mut extracted: Option<String> = None;

        while !data.is_empty() {
            if !self.in_tag {
                if let Some(idx) = data.find(TASK_SUMMARY_OPEN_TAG) {
                    let before = &data[..idx];
                    if !before.is_empty() && (self.emitted_any || !before.trim().is_empty()) {
                        visible.push_str(before);
                        self.emitted_any = true;
                    }
                    data = data[idx + TASK_SUMMARY_OPEN_TAG.len()..].to_string();
                    self.in_tag = true;
                    continue;
                }

                if !self.emitted_any
                    && data.trim().is_empty()
                    && data.len() <= Self::MAX_LEADING_WS_HOLD
                {
                    self.buf = data;
                    break;
                }

                let suffix_len = Self::open_tag_prefix_suffix_len(&data);
                if suffix_len > 0 {
                    let split = data.len() - suffix_len;
                    let emit = &data[..split];
                    if !emit.is_empty()
                        && !self.emitted_any
                        && emit.trim().is_empty()
                        && emit.len() <= Self::MAX_LEADING_WS_HOLD
                    {
                        self.buf = data;
                    } else {
                        if !emit.is_empty() {
                            visible.push_str(emit);
                            self.emitted_any = true;
                        }
                        self.buf = data[split..].to_string();
                    }
                } else {
                    visible.push_str(&data);
                    self.emitted_any = true;
                }
                break;
            }

            if let Some(close_idx) = data.find(TASK_SUMMARY_CLOSE_TAG) {
                let raw_summary = &data[..close_idx];
                if let Some(cleaned) = summarize_task_prompt(raw_summary, 160) {
                    self.summary = Some(cleaned.clone());
                    extracted = Some(cleaned);
                }
                self.in_tag = false;
                self.done = true;
                self.strip_post_tag_ws = true;
                let rest =
                    self.consume_post_tag_ws(&data[close_idx + TASK_SUMMARY_CLOSE_TAG.len()..]);
                if !rest.is_empty() {
                    visible.push_str(&rest);
                    self.emitted_any = true;
                }
                break;
            }

            if data.chars().count() > Self::MAX_TAG_BODY_LEN {
                visible.push_str(TASK_SUMMARY_OPEN_TAG);
                visible.push_str(&data);
                self.emitted_any = true;
                self.in_tag = false;
                self.done = true;
                break;
            }

            self.buf = data;
            break;
        }

        (visible, extracted)
    }

    pub fn flush(&mut self) -> String {
        if self.in_tag {
            let mut out = String::from(TASK_SUMMARY_OPEN_TAG);
            out.push_str(&self.buf);
            self.in_tag = false;
            self.buf.clear();
            self.done = true;
            self.emitted_any = true;
            return out;
        }
        if !self.buf.is_empty() {
            let out = std::mem::take(&mut self.buf);
            if !out.is_empty() {
                self.emitted_any = true;
            }
            return out;
        }
        String::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn task_summary_filter_strips_tag_across_chunk_boundaries() {
        let mut f = TaskSummaryStreamFilter::new();
        let (v1, s1) = f.feed("<tab_task_");
        assert_eq!(v1, "");
        assert_eq!(s1, None);
        let (v2, s2) = f.feed("summary>Fix rust daemon</tab_task_summary>\n\nHello!");
        assert_eq!(s2.as_deref(), Some("Fix rust daemon"));
        assert_eq!(v2, "Hello!");
        assert_eq!(f.flush(), "");
    }

    #[test]
    fn task_finished_detection_ignores_negated_and_code_blocks() {
        assert!(is_task_finished_response(Some(
            "All done. You can close this tab now."
        )));
        assert!(!is_task_finished_response(Some(
            "Do not close this tab yet while tests run."
        )));
        assert!(!is_task_finished_response(Some(
            "```\nYou can close this tab.\n```\nStill working on step 2."
        )));
    }

    #[test]
    fn summarize_command_strips_leading_cd_and_shell_wrapper() {
        assert_eq!(
            summarize_command("bash -lc 'cd /tmp/work && cargo test'"),
            "cargo test"
        );
        assert_eq!(summarize_command("cd /home/user && ls -la"), "ls -la");
    }
}
