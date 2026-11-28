"""Regression tests for release decisions on isolated retries."""

from __future__ import annotations

import unittest
from unittest.mock import Mock, call

from test_utils_py.retry import retry_case


class RetryPolicyTest(unittest.TestCase):
    def test_recovers_only_after_adjacent_passes(self) -> None:
        attempt = Mock(side_effect=["passed", "failed", "broken", "passed", "passed"])
        self.assertTrue(retry_case("case", attempt))
        self.assertEqual(attempt.call_args_list, [call(i) for i in range(1, 6)])

    def test_lone_final_pass_cannot_recover(self) -> None:
        attempt = Mock(side_effect=["failed", "passed", "failed", "failed", "passed"])
        self.assertFalse(retry_case("case", attempt))
        self.assertEqual(attempt.call_count, 5)

    def test_incomplete_or_skipped_retry_blocks(self) -> None:
        for status in (None, "skipped"):
            with self.subTest(status=status):
                attempt = Mock(side_effect=["passed", status])
                self.assertFalse(retry_case("case", attempt))
                self.assertEqual(attempt.call_count, 2)


if __name__ == "__main__":
    unittest.main()
