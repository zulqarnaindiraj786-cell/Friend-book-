"""The migration probe must wait for the quota, not declare failure.

The Spark plan's daily read quota resets at midnight Pacific, which is 08:00 UTC. Earlier
runs waited for a 00:00 UTC boundary that never mattered, probed, got ResourceExhausted and
exited 6 - so the canary could only be run by hand at the right hour. These tests pin the
reset arithmetic and the refusal classification, because getting either wrong either hangs
CI for a day or hides a genuine credential failure as "quota".

The build job runs this suite on a bare runner where firebase_admin and requests are not
installed. Importing the migration script pulls both in, which made the whole build red for
tests that only need two pure functions, so the module is loaded from source instead.
"""

import ast
import os
import types
import unittest
from datetime import datetime, timezone
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "migrate_firestore_to_d1.py"

WANTED = {"QUOTA_RESET_HOUR_UTC", "QUOTA_WAIT_MINUTES",
          "seconds_until_quota_reset", "is_quota_refusal"}


def load_module():
    """Load the real definitions under test, without importing firebase_admin or requests."""
    source = SCRIPT.read_text()
    tree = ast.parse(source)
    pieces = []
    for node in tree.body:
        if isinstance(node, ast.FunctionDef) and node.name in WANTED:
            pieces.append(ast.get_source_segment(source, node))
        elif isinstance(node, ast.Assign):
            targets = [t.id for t in node.targets if isinstance(t, ast.Name)]
            if any(t in WANTED for t in targets):
                pieces.append(ast.get_source_segment(source, node))
    if not pieces:
        raise AssertionError("nothing to load: the functions under test have been renamed")

    module = types.ModuleType("migrate_quota_under_test")
    exec("from datetime import datetime, timedelta, timezone\nimport os",
         module.__dict__)
    exec("\n\n".join(pieces), module.__dict__)
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
