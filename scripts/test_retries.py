"""Isolated CI retry policy shared by the runners and release reporting."""

from typing import Any, Callable, Dict, Optional, Sequence

MAX_RETRIES = 5


def recovered(statuses: Sequence[str]) -> bool:
    """Only consecutive passes of the same isolated test can clear its initial failure."""
    return len(statuses) >= 2 and statuses[-1] == statuses[-2] == "passed"


def record_attempt(payload: Dict[str, Any], attempt: int) -> None:
    """Number attempts without changing the test's identity across CI runs."""
    payload.setdefault("labels", []).append({"name": "retryAttempt", "value": str(attempt)})


def retry_case(name: str, run_attempt: Callable[[int], Optional[str]]) -> bool:
    statuses = []
    for attempt in range(1, MAX_RETRIES + 1):
        print(f"Isolated retry {attempt}/{MAX_RETRIES} for {name}", flush=True)
        status = run_attempt(attempt)
        if status not in ("passed", "failed", "broken"):
            print(f"No conclusive result for {name} on isolated retry {attempt}", flush=True)
            return False
        statuses.append(status)
        if recovered(statuses):
            print(f"Recovered {name} after {attempt} isolated retries", flush=True)
            return True
    return False
