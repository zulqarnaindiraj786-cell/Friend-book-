import unittest
from scripts.loadtest.retired_migration_gate import pending_safe


class MigrationGateTest(unittest.TestCase):
    def test_only_requested_migration(self):
        self.assertTrue(pending_safe('| 0086_remove_unused_status_tables.sql |'))

    def test_unrelated_migration_rejected(self):
        self.assertFalse(pending_safe('| 0085_other.sql |\n| 0086_remove_unused_status_tables.sql |'))
        self.assertFalse(pending_safe('No migrations to apply'))
