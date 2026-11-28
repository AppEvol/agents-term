"""Allure 3 JSON result schema builders, identity hashing, attachment helpers, and file I/O."""

from __future__ import annotations

import hashlib
import json
import shutil
import time
import traceback
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Tuple


def now_ms() -> int:
    return int(time.time() * 1000)


def md5_hex(value: str) -> str:
    return hashlib.md5(value.encode("utf-8")).hexdigest()


def parse_key_values(items: Sequence[str], flag_name: str) -> List[Tuple[str, str]]:
    parsed: List[Tuple[str, str]] = []
    for item in items:
        if "=" not in item:
            raise ValueError(f"{flag_name} expects KEY=VALUE, got: {item!r}")
        key, value = item.split("=", 1)
        parsed.append((key, value))
    return parsed


def compute_identity(full_name: str, parameters: Sequence[Dict[str, str]]) -> Tuple[str, str]:
    param_part = "|".join(f"{p['name']}={p['value']}" for p in sorted(parameters, key=lambda item: item["name"]))
    digest = md5_hex(f"{full_name}||{param_part}")
    return digest, digest


def label_value(result: Dict[str, Any], name: str, default: str = "") -> str:
    for label in result.get("labels", []):
        if label.get("name") == name and label.get("value"):
            return str(label["value"])
    return default


def write_text_attachment(output_dir: Path, name: str, content: str, mime_type: str = "text/plain") -> Dict[str, str]:
    source = f"{uuid.uuid4()}-attachment.txt"
    (output_dir / source).write_text(content, encoding="utf-8")
    return {"name": name, "source": source, "type": mime_type}


def copy_file_attachment(output_dir: Path, src_path: Path, name: str, mime_type: str) -> Dict[str, str]:
    suffix = src_path.suffix or ".bin"
    source = f"{uuid.uuid4()}-attachment{suffix}"
    shutil.copy2(src_path, output_dir / source)
    return {"name": name, "source": source, "type": mime_type}


def write_result(output_dir: Path, payload: Dict[str, Any]) -> Path:
    output_dir.mkdir(parents=True, exist_ok=True)
    result_uuid = payload.setdefault("uuid", str(uuid.uuid4()))
    out_path = output_dir / f"{result_uuid}-result.json"
    out_path.write_text(json.dumps(payload, indent=2, sort_keys=True), encoding="utf-8")
    return out_path


def read_results(folder: Path) -> List[Dict[str, Any]]:
    if not folder.is_dir():
        return []
    return [json.loads(path.read_text(encoding="utf-8")) for path in sorted(folder.glob("*-result.json"))]


def build_result(
    *,
    name: str,
    full_name: str,
    status: str,
    start_ms: int,
    stop_ms: int,
    labels: List[Dict[str, str]],
    parameters: Optional[List[Dict[str, str]]] = None,
    status_details: Optional[Dict[str, str]] = None,
    attachments: Optional[List[Dict[str, str]]] = None,
    steps: Optional[List[Dict[str, Any]]] = None,
) -> Dict[str, Any]:
    params = list(parameters or [])
    history_id, test_case_id = compute_identity(full_name, params)
    payload: Dict[str, Any] = {
        "uuid": str(uuid.uuid4()),
        "name": name,
        "fullName": full_name,
        "historyId": history_id,
        "testCaseId": test_case_id,
        "status": status,
        "stage": "finished",
        "start": start_ms,
        "stop": max(start_ms, stop_ms),
        "labels": labels,
    }
    if params:
        payload["parameters"] = params
    if status_details:
        cleaned = {k: v for k, v in status_details.items() if v}
        if cleaned:
            payload["statusDetails"] = cleaned
    if attachments:
        payload["attachments"] = attachments
    if steps:
        payload["steps"] = steps
    return payload


def parse_iso_timestamp_ms(raw: Optional[str]) -> Optional[int]:
    if not raw:
        return None
    text = raw.strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    try:
        dt = datetime.fromisoformat(text)
    except ValueError:
        return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return int(dt.timestamp() * 1000)


def format_exc_info(err: Tuple[Any, Any, Any]) -> Tuple[str, str]:
    exc_type, exc_value, _tb = err
    message = f"{exc_type.__name__}: {exc_value}" if exc_value else exc_type.__name__
    trace = "".join(traceback.format_exception(*err))
    return message, trace
