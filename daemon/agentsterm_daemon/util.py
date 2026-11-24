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
from typing import Any, Optional

_WS_RE = re.compile(r"\s+")


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
    return int(math.floor(x + 0.5))


def json_compact(value: Any) -> str:
    """JSON.stringify(value)"""
    return json.dumps(value, separators=(",", ":"), ensure_ascii=False)


def json_pretty(value: Any) -> str:
    """JSON.stringify(value, null, 2)"""
    return json.dumps(value, indent=2, ensure_ascii=False)


def truncate(s: str, max_len: int = 180) -> str:
    single = _WS_RE.sub(" ", s).strip()
    return single[: max_len - 1] + "…" if len(single) > max_len else single


def summarize_tool_input(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, str):
        return truncate(value, 120)
    if isinstance(value, (dict, list)):
        if isinstance(value, dict):
            for key in ("command", "CommandLine", "path", "Path", "file_path", "pattern", "query", "url", "toolSummary"):
                v = value.get(key)
                if isinstance(v, str) and v:
                    return truncate(v, 120)
        try:
            return truncate(json_compact(value), 120)
        except (TypeError, ValueError):
            return ""
    return js_str(value)


def str_or_none(value: Any) -> Optional[str]:
    return value.strip() if isinstance(value, str) and value.strip() else None
