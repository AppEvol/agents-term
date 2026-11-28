"""Allow invoking the unified test utilities CLI via `python3 -m test_utils_py`."""

from __future__ import annotations

from test_utils_py.cli import main

if __name__ == "__main__":
    raise SystemExit(main())
