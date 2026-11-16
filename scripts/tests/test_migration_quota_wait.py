"""The migration probe must wait for the quota, not declare failure.

The Spark plan's daily read quota resets at midnight Pacific, which is 08:00 UTC. Earlier
runs waited for a 00:00 UTC boundary that never mattered, probed, got ResourceExhausted and
exited 6 - so the canary could only be run by hand at the right hour. These tests pin the
reset arithmetic and the refusal classification, because getting either wrong either hangs
CI for a day or hides a genuine credential failure as "quota".
"""

import importlib.util
import os
import sys
import unittest
from datetime import datetime, timezone
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "migrate_firestore_to_d1.py"


def load_module():
    spec = importlib.util.spec_from_file_location("migrate_mod_quota", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class QuotaResetWindowTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.m = load_module()

    def test_reset_is_midnight_pacific_not_midnight_utc(self):
        # 05:30 UTC is 21:30 the previous day in Pacific: the allowance returns in ~2.5h.
        seconds = self.m.seconds_until_quota_reset(datetime(2026, 11, 16, 5, 30, tzinfo=timezone.utc))
        self.assertGreater(seconds, 2 * 3600)
        self.assertLess(seconds, 3 * 3600)

    def test_just_before_the_reset_is_a_short_wait(self):
        seconds = self.m.seconds_until_quota_reset(datetime(2026, 11, 16, 7, 55, tzinfo=timezone.utc))
        self.assertLess(seconds, 10 * 60)

    def test_after_the_reset_rolls_to_the_next_day(self):
        seconds = self.m.seconds_until_quota_reset(datetime(2026, 11, 16, 9, 0, tzinfo=timezone.utc))
        self.assertGreater(seconds, 22 * 3600)

    def test_the_wait_always_includes_a_margin(self):
        # A wait that lands exactly on the boundary retries a fraction early and burns a cycle.
        seconds = self.m.seconds_until_quota_reset(datetime(2026, 11, 16, 8, 0, tzinfo=timezone.utc))
        self.assertGreater(seconds, 23 * 3600)


class RefusalClassificationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.m = load_module()

    def test_resource_exhausted_is_a_quota_refusal(self):
        class ResourceExhausted(Exception):
            """Mirrors the firebase_admin exception type by name."""

        self.assertTrue(self.m.is_quota_refusal(ResourceExhausted("429 Quota exceeded.")))

    def test_the_message_alone_is_enough(self):
        self.assertTrue(self.m.is_quota_refusal(RuntimeError("ResourceExhausted: 429 Quota exceeded")))

    def test_credentials_are_not_a_quota_refusal(self):
        # Waiting for midnight on a bad service account would hide a real failure for hours.
        self.assertFalse(self.m.is_quota_refusal(PermissionError("403 Missing or insufficient permissions")))
        self.assertFalse(self.m.is_quota_refusal(RuntimeError("invalid_grant: account not found")))
        self.assertFalse(self.m.is_quota_refusal(ConnectionError("network unreachable")))

    def test_no_wait_by_default(self):
        # A local run must never block for hours.
        self.assertEqual(self.m.QUOTA_WAIT_MINUTES,
                         int(os.environ.get("MIGRATION_QUOTA_WAIT_MINUTES", "0")))


if __name__ == "__main__":
    unittest.main()
