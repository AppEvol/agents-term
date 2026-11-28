#!/usr/bin/env python3
"""Thin CI entrypoint for Allure 3 report aggregation; implementation lives in test_utils_py."""

from __future__ import annotations

import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from test_utils_py.reporters.ci_report import main  # noqa: E402 - repo root is added to sys.path above

if __name__ == "__main__":
    raise SystemExit(main())
