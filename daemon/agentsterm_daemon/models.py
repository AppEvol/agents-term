"""Model catalog discovery for agy (CLI), claude (~/.claude) and codex (~/.codex)."""

from __future__ import annotations

import asyncio
import json
import os
import re
import time
from pathlib import Path
from typing import Any, Dict, List, Optional

from .config import enriched_path, home_dir, resolve_binary
from .util import is_number

ANSI_RE = re.compile(r"\x1b\[[0-9;?]*[ -/]*[@-~]")
AGY_MODEL_LINE_RE = re.compile(r"^([A-Za-z0-9][A-Za-z0-9._:/-]*)\s{2,}(.+)$")
FETCHING_RE = re.compile(r"fetching available models", re.I)
CODEX_MODEL_RE = re.compile(r'^\s*model\s*=\s*"([^"]+)"', re.M)

AGY_CACHE_TTL_MS = 5 * 60_000
DISK_CACHE_TTL_MS = 60_000

_cache_by_agent: Dict[str, Dict[str, Any]] = {}
_agy_in_flight: Optional["asyncio.Future[Dict[str, Any]]"] = None


def _now_ms() -> int:
    return int(time.time() * 1000)


def parse_agy_models_output(raw: str) -> List[Dict[str, Any]]:
    cleaned = ANSI_RE.sub("", raw)
    seen = set()
    models = []
    for raw_line in cleaned.split("\n"):
        # Handle carriage-return spinner prefixes like "\r⠋ Fetching...\rgemini-4  Gemini 4"
        line = raw_line.split("\r")[-1].strip()
        if not line or FETCHING_RE.search(line):
            continue
        m = AGY_MODEL_LINE_RE.match(line)
        if not m:
            continue
        model_id, name = m.group(1).strip(), m.group(2).strip()
        if not model_id or not name or model_id in seen:
            continue
        seen.add(model_id)
        models.append({"id": model_id, "name": name})
    return models


def _read_json_safe(path: Path) -> Optional[Dict[str, Any]]:
    try:
        if not path.exists():
            return None
        parsed = json.loads(path.read_text(encoding="utf-8"))
        return parsed if isinstance(parsed, dict) else None
    except (OSError, ValueError):
        return None


def _nonblank(value: Any) -> Optional[str]:
    return value.strip() if isinstance(value, str) and value.strip() else None


def read_claude_models_from_disk(home: Optional[Path] = None) -> Dict[str, Any]:
    claude_dir = Path(home or home_dir()) / ".claude"
    settings = _read_json_safe(claude_dir / "settings.json")
    default_model = _nonblank(settings.get("model")) if settings else None

    catalog_dir = claude_dir / "cache" / "model-catalog"
    models: List[Dict[str, Any]] = []
    seen = set()
    try:
        if catalog_dir.exists():
            files = sorted(
                (p for p in catalog_dir.iterdir() if p.name.endswith(".json")),
                key=lambda p: p.stat().st_mtime,
                reverse=True,
            )
            for f in files:
                parsed = _read_json_safe(f)
                catalog = parsed.get("catalog") if parsed else None
                config = catalog.get("config") if isinstance(catalog, dict) else None
                raw_models = config.get("models") if isinstance(config, dict) else None
                if not isinstance(raw_models, list) or not raw_models:
                    continue
                for item in raw_models:
                    if not isinstance(item, dict):
                        continue
                    model_id = item["id"].strip() if isinstance(item.get("id"), str) else ""
                    if not model_id or model_id in seen:
                        continue
                    seen.add(model_id)
                    models.append(
                        {
                            "id": model_id,
                            "name": _nonblank(item.get("name")) or model_id,
                            "description": _nonblank(item.get("description")),
                            "isDefault": model_id == default_model if default_model else False,
                        }
                    )
                if models:
                    break
    except OSError:
        pass  # Fall back to aliases below

    if not models:
        models = [
            {"id": "opus", "name": "Opus", "description": "Most capable Claude model alias"},
            {"id": "sonnet", "name": "Sonnet", "description": "Balanced Claude model alias"},
            {"id": "haiku", "name": "Haiku", "description": "Fastest Claude model alias"},
        ]
    return {"defaultModel": default_model, "models": models}


def read_codex_models_from_disk(home: Optional[Path] = None) -> Dict[str, Any]:
    codex_dir = Path(home or home_dir()) / ".codex"
    default_model = None
    try:
        config_path = codex_dir / "config.toml"
        if config_path.exists():
            m = CODEX_MODEL_RE.search(config_path.read_text(encoding="utf-8"))
            if m and m.group(1).strip():
                default_model = m.group(1).strip()
    except OSError:
        pass

    seen = set()
    cache = _read_json_safe(codex_dir / "models_cache.json")
    raw_models = cache.get("models") if cache else None
    sortable = []
    for item in raw_models if isinstance(raw_models, list) else []:
        if not isinstance(item, dict):
            continue
        if item.get("visibility") == "hide" or item.get("supported_in_api") is False:
            continue
        model_id = item["slug"].strip() if isinstance(item.get("slug"), str) else ""
        if not model_id or model_id in seen:
            continue
        seen.add(model_id)
        priority = item["priority"] if is_number(item.get("priority")) else 999
        sortable.append(
            (
                priority,
                {
                    "id": model_id,
                    "name": _nonblank(item.get("display_name")) or model_id,
                    "description": _nonblank(item.get("description")),
                    "isDefault": model_id == default_model if default_model else False,
                },
            )
        )
    sortable.sort(key=lambda e: e[0])  # stable, like Array.prototype.sort
    models = [option for _, option in sortable]
    if default_model and default_model not in seen:
        models.insert(0, {"id": default_model, "name": default_model, "isDefault": True})
    return {"defaultModel": default_model, "models": models}


def _read_agy_default_model(home: Optional[Path] = None) -> Optional[str]:
    settings = _read_json_safe(Path(home or home_dir()) / ".gemini" / "antigravity-cli" / "settings.json")
    return _nonblank(settings.get("model")) if settings else None


async def _run_agy_models(binary: str) -> tuple:
    env = {**os.environ, "PATH": enriched_path(), "NO_COLOR": "1", "FORCE_COLOR": "0"}
    err = False
    stdout = stderr = b""
    try:
        proc = await asyncio.create_subprocess_exec(
            binary, "models",
            stdin=asyncio.subprocess.DEVNULL,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            env=env,
        )
        try:
            stdout, stderr = await asyncio.wait_for(proc.communicate(), timeout=10)
        except asyncio.TimeoutError:
            err = True
            proc.kill()
            stdout, stderr = await proc.communicate()
        if proc.returncode != 0:
            err = True
    except OSError:
        err = True
    combined = "\n".join(s for s in (stdout.decode("utf-8", "replace"), stderr.decode("utf-8", "replace")) if s)
    return combined, err


async def discover_agy_models(force: bool = False, home: Optional[Path] = None) -> Dict[str, Any]:
    global _agy_in_flight
    cached = _cache_by_agent.get("agy")
    if not force and cached and _now_ms() - cached["fetchedAt"] < AGY_CACHE_TTL_MS and cached["models"]:
        return cached
    if _agy_in_flight is not None:
        return await asyncio.shield(_agy_in_flight)

    default_model = _read_agy_default_model(home)
    binary = resolve_binary("agy")
    if not binary:
        empty = {"fetchedAt": _now_ms(), "defaultModel": default_model, "models": []}
        _cache_by_agent["agy"] = empty
        return empty

    loop = asyncio.get_running_loop()
    future: "asyncio.Future[Dict[str, Any]]" = loop.create_future()
    _agy_in_flight = future
    try:
        combined, err = await _run_agy_models(binary)
        parsed = [
            {**m, "isDefault": m["id"] == default_model if default_model else False}
            for m in parse_agy_models_output(combined)
        ]
        if parsed:
            result = {"fetchedAt": _now_ms(), "defaultModel": default_model, "models": parsed}
        elif cached and cached["models"] and err:
            result = cached
        else:
            result = {"fetchedAt": _now_ms(), "defaultModel": default_model, "models": parsed}
        _cache_by_agent["agy"] = result
        future.set_result(result)
        return result
    except BaseException as e:
        if not future.done():
            future.set_exception(e)
            future.exception()  # mark retrieved
        raise
    finally:
        _agy_in_flight = None


def _kick_agy_discovery() -> None:
    try:
        loop = asyncio.get_running_loop()
    except RuntimeError:
        return  # No event loop (e.g. sync tests); the next request will refresh.
    task = loop.create_task(discover_agy_models())
    task.add_done_callback(lambda t: t.cancelled() or t.exception())


def get_cached_agent_models(agent: str) -> Dict[str, Any]:
    if agent == "shell":
        return {"defaultModel": None, "models": []}
    now = _now_ms()
    if agent == "agy":
        cached = _cache_by_agent.get("agy")
        if not cached or now - cached["fetchedAt"] >= AGY_CACHE_TTL_MS:
            if _agy_in_flight is None:
                _kick_agy_discovery()
        return {
            "defaultModel": cached["defaultModel"] if cached else _read_agy_default_model(),
            "models": cached["models"] if cached else [],
        }
    if agent in ("claude", "codex"):
        cached = _cache_by_agent.get(agent)
        if cached and now - cached["fetchedAt"] < DISK_CACHE_TTL_MS:
            return {"defaultModel": cached["defaultModel"], "models": cached["models"]}
        fresh = read_claude_models_from_disk() if agent == "claude" else read_codex_models_from_disk()
        _cache_by_agent[agent] = {"fetchedAt": now, **fresh}
        return fresh
    return {"defaultModel": None, "models": []}


async def get_agent_models(agent: str, force_refresh: bool = False) -> Dict[str, Any]:
    if agent == "agy":
        res = await discover_agy_models(force=force_refresh)
        return {"agent": agent, "defaultModel": res["defaultModel"], "models": res["models"]}
    if agent in ("claude", "codex"):
        res = read_claude_models_from_disk() if agent == "claude" else read_codex_models_from_disk()
        _cache_by_agent[agent] = {"fetchedAt": _now_ms(), **res}
        return {"agent": agent, "defaultModel": res["defaultModel"], "models": res["models"]}
    return {"agent": agent, "defaultModel": None, "models": []}


def warm_agent_models_cache() -> None:
    _kick_agy_discovery()
    get_cached_agent_models("claude")
    get_cached_agent_models("codex")


def clear_models_cache_for_tests() -> None:
    global _agy_in_flight
    _cache_by_agent.clear()
    _agy_in_flight = None
