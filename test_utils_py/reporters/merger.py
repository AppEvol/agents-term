"""Merge Allure result shards, fold isolated retry attempts, and emit run metadata."""

from __future__ import annotations

import json
import shutil
import uuid
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Tuple

from test_utils_py.reporters.allure_model import label_value
from test_utils_py.retry import MAX_RETRIES, recovered


def fold_retries(directory: Path) -> int:
    """Fold only explicitly numbered attempts, retaining their evidence as Allure steps."""
    originals: Dict[Tuple[str, str], List[Path]] = {}
    retries: Dict[Tuple[str, str], Dict[int, Path]] = {}
    count = 0
    for path in directory.glob("*-result.json"):
        data = json.loads(path.read_text(encoding="utf-8"))
        key = (label_value(data, "parentSuite"), data.get("historyId", ""))
        number = label_value(data, "retryAttempt")
        if not number:
            originals.setdefault(key, []).append(path)
            count += 1
            continue
        index = int(number)
        attempts = retries.setdefault(key, {})
        if not key[1] or index in attempts or not 1 <= index <= MAX_RETRIES:
            raise ValueError(f"Invalid or duplicate retry attempt {number} for {key}")
        attempts[index] = path

    for key, attempts in retries.items():
        if len(originals.get(key, [])) != 1 or sorted(attempts) != list(range(1, len(attempts) + 1)):
            raise ValueError(f"Missing or ambiguous suite result or retry sequence for {key}")
        path = originals[key][0]
        original = json.loads(path.read_text(encoding="utf-8"))
        if original["status"] not in ("failed", "broken"):
            raise ValueError(f"Unexpected isolated retry for {original['status']} test {key}")
        ordered = [original] + [json.loads(attempts[i].read_text(encoding="utf-8")) for i in sorted(attempts)]
        steps = []
        for index, attempt in enumerate(ordered):
            step = {
                k: attempt[k]
                for k in ("status", "statusDetails", "start", "stop", "steps", "attachments")
                if k in attempt
            }
            step["name"] = f"Isolated retry {index}" if index else "Initial suite run"
            steps.append(step)
        original["steps"] = steps
        original.pop("attachments", None)
        original["labels"].extend(
            [
                {"name": "initialStatus", "value": original["status"]},
                {"name": "retryCount", "value": str(len(attempts))},
            ]
        )
        if recovered([step["status"] for step in steps[1:]]):
            original["status"] = "passed"
            original["statusDetails"] = {"flaky": True}
            original["labels"].append({"name": "tag", "value": "recovered on retry"})
        original["stop"] = max(step.get("stop", 0) for step in steps)
        path.write_text(json.dumps(original, indent=2, sort_keys=True), encoding="utf-8")
        for attempt_path in attempts.values():
            attempt_path.unlink()
    return count


def merge_results(
    *,
    input_dirs: Sequence[Path],
    output_dir: Path,
    env_pairs: Sequence[Tuple[str, str]] = (),
    executor: Optional[Dict[str, Any]] = None,
) -> int:
    """Merge Allure result files and attachments from one or more directories."""
    if output_dir.exists():
        shutil.rmtree(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    for input_dir in input_dirs:
        if not input_dir.exists():
            continue
        files = [input_dir] if input_dir.is_file() else sorted(p for p in input_dir.rglob("*") if p.is_file())
        for src in files:
            name = src.name
            is_result = name.endswith("-result.json")
            is_keepable = (
                is_result
                or name.endswith(("-container.json", ".xml"))
                or "-attachment" in name
                or name == "categories.json"
            )
            if not is_keepable:
                continue
            dest = output_dir / name
            if dest.exists():
                dest = output_dir / f"{uuid.uuid4().hex[:8]}-{name}"
            shutil.copy2(src, dest)

    if env_pairs:
        lines = [f"{k}={v}" for k, v in env_pairs if v]
        if lines:
            (output_dir / "environment.properties").write_text("\n".join(lines) + "\n", encoding="utf-8")

    if executor:
        cleaned_executor = {k: v for k, v in executor.items() if v is not None and v != ""}
        if cleaned_executor:
            (output_dir / "executor.json").write_text(
                json.dumps(cleaned_executor, indent=2, sort_keys=True),
                encoding="utf-8",
            )

    return fold_retries(output_dir)
