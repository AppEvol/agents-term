"""Small helpers shared across the daemon.

Several helpers mirror JavaScript semantics on purpose (truthiness, String(), Math.round) so this
port parses agent CLI output exactly like the original TypeScript daemon did.
"""

from __future__ import annotations

import json
import math
import re
import secrets
import time
from typing import Any, Dict, List, Optional

_WS_RE = re.compile(r"\s+")
_CODE_FENCE_RE = re.compile(r"```[^\n]*\n?(.*?)```", re.S)
_MARKDOWN_LINK_RE = re.compile(r"\[([^\]]+)\]\([^)]+\)")
_MARKDOWN_LINE_PREFIX_RE = re.compile(r"^\s*(?:#{1,6}\s+|>\s+|[-*+]\s+)")
_TASK_SUMMARY_WRAPPER_RE = re.compile(r"</?tab_task_summary>", re.I)
_MODEL_SLASH_RE = re.compile(r"^/model(?:\s|$)", re.I)
_LEADING_CD_RE = re.compile(
    r"""^\s*cd(?:\s+(?:-[LP]|--))?\s+(?:"[^"]*"|'[^']*'|(?:\\.|[^\s;&|\n])+)\s*(?:&&|;|\r?\n)\s*"""
)
_SHELL_WRAPPER_RE = re.compile(
    r"""^\s*(?:/(?:usr/)?bin/)?(?:ba|z)?sh\s+-[a-z]*c\s+(["'])(.*)\1\s*$""",
    re.S,
)


def now_ms() -> int:
    return int(time.time() * 1000)


def rand_hex(n_bytes: int) -> str:
    return secrets.token_hex(n_bytes)


def is_number(value: Any) -> bool:
    """typeof value === "number" (bool is not a number in JSON terms)."""
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def is_finite_number(value: Any) -> bool:
    return is_number(value) and math.isfinite(value)


def js_truthy(value: Any) -> bool:
    """Boolean(value) in JavaScript: empty arrays/objects are truthy, NaN is falsy."""
    if value is None or value is False:
        return False
    if isinstance(value, bool):
        return True
    if is_number(value):
        return value != 0 and not (isinstance(value, float) and math.isnan(value))
    if isinstance(value, str):
        return value != ""
    return True


def js_str(value: Any) -> str:
    """String(value) in JavaScript for JSON-decoded values."""
    if value is None:
        return "null"
    if value is True:
        return "true"
    if value is False:
        return "false"
    if isinstance(value, float):
        if math.isnan(value):
            return "NaN"
        if math.isinf(value):
            return "Infinity" if value > 0 else "-Infinity"
        if value == int(value) and abs(value) < 1e21:
            return str(int(value))
        return repr(value)
    if isinstance(value, (dict,)):
        return "[object Object]"
    if isinstance(value, list):
        return ",".join("" if v is None else js_str(v) for v in value)
    return str(value)


def first_not_none(*values: Any) -> Any:
    """a ?? b ?? c"""
    for v in values:
        if v is not None:
            return v
    return None


def js_round(x: float) -> int:
    """Math.round: rounds half up (toward +infinity)."""
    return math.floor(x + 0.5)


def json_compact(value: Any) -> str:
    """JSON.stringify(value)"""
    return json.dumps(value, separators=(",", ":"), ensure_ascii=False)


def json_pretty(value: Any) -> str:
    """JSON.stringify(value, null, 2)"""
    return json.dumps(value, indent=2, ensure_ascii=False)


def truncate(s: str, max_len: int = 180) -> str:
    single = _WS_RE.sub(" ", s).strip()
    return single[: max_len - 1] + "…" if len(single) > max_len else single


def summarize_task_prompt(raw: str, max_len: int = 160) -> Optional[str]:
    """Normalize a task summary or first user prompt into a clean single-line summary."""
    if not isinstance(raw, str):
        return None
    cleaned = _TASK_SUMMARY_WRAPPER_RE.sub("", raw).strip()
    if not cleaned:
        return None
    without_fences = _CODE_FENCE_RE.sub(" ", cleaned).strip()
    source = without_fences if without_fences else _CODE_FENCE_RE.sub(r"\1", cleaned)
    source = _MARKDOWN_LINK_RE.sub(r"\1", source).replace("`", "")
    lines = [_MARKDOWN_LINE_PREFIX_RE.sub("", line).strip() for line in source.splitlines()]
    single = _WS_RE.sub(" ", " ".join(line for line in lines if line)).strip()
    if not single:
        return None
    if len(single) <= max_len:
        return single
    cut = single[: max_len - 1].rstrip()
    space_idx = cut.rfind(" ")
    if space_idx >= int((max_len - 1) * 0.65):
        cut = cut[:space_idx].rstrip(" ,;:-")
    return cut + "…"


def derive_task_summary_from_messages(messages: List[Dict[str, Any]], max_len: int = 160) -> Optional[str]:
    """Derive a fallback task summary from the first substantive user message."""
    for msg in messages:
        if not isinstance(msg, dict) or msg.get("role") != "user":
            continue
        text = msg.get("text")
        if not isinstance(text, str) or not text.strip():
            continue
        if _MODEL_SLASH_RE.match(text.strip()):
            continue
        summary = summarize_task_prompt(text, max_len=max_len)
        if summary:
            return summary
    return None


def summarize_command(command: str) -> str:
    """Return a concise summary of a shell command, stripping leading `cd <dir> &&` prefixes."""
    s = command.strip()
    if not s:
        return ""
    m = _SHELL_WRAPPER_RE.match(s)
    if m:
        quote, inner = m.group(1), m.group(2).strip()
        if inner and quote not in inner:
            s = inner
    while True:
        match = _LEADING_CD_RE.match(s)
        if not match:
            break
        rest = s[match.end() :].strip()
        if not rest:
            break
        s = rest
    return truncate(s, 120)


def summarize_tool_input(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, str):
        return summarize_command(value)
    if isinstance(value, (dict, list)):
        if isinstance(value, dict):
            for key in ("description", "toolSummary", "summary", "title"):
                v = value.get(key)
                if isinstance(v, str) and v.strip():
                    return truncate(v, 120)
            for key in ("command", "CommandLine"):
                v = value.get(key)
                if isinstance(v, str) and v.strip():
                    return summarize_command(v)
            for key in ("file_path", "path", "Path", "pattern", "query", "url"):
                v = value.get(key)
                if isinstance(v, str) and v.strip():
                    return truncate(v, 120)
        try:
            return truncate(json_compact(value), 120)
        except (TypeError, ValueError):
            return ""
    return js_str(value)
