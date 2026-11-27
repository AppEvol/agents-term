"""Regression tests for release decisions on isolated retries."""

import sys
import unittest
from pathlib import Path
from unittest.mock import Mock, call

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from test_retries import retry_case


class RetryPolicyTest(unittest.TestCase):
    def test_recovers_only_after_adjacent_passes(self):
        attempt = Mock(side_effect=["passed", "failed", "broken", "passed", "passed"])
        self.assertTrue(retry_case("case", attempt))
        self.assertEqual(attempt.call_args_list, [call(i) for i in range(1, 6)])

    def test_lone_final_pass_cannot_recover(self):
        attempt = Mock(side_effect=["failed", "passed", "failed", "failed", "passed"])
        self.assertFalse(retry_case("case", attempt))
        self.assertEqual(attempt.call_count, 5)

    def test_incomplete_or_skipped_retry_blocks(self):
        for status in (None, "skipped"):
            with self.subTest(status=status):
                attempt = Mock(side_effect=["passed", status])
                self.assertFalse(retry_case("case", attempt))
                self.assertEqual(attempt.call_count, 2)
