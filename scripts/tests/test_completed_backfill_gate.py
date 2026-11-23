"""Prevent a completed production migration from restarting on source pushes."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class CompletedBackfillGate(unittest.TestCase):
    def test_only_explicit_manual_confirmation_can_start_heavy_backfill(self):
        source = (ROOT / '.github/workflows/legacy-roots-backfill.yml').read_text()
        triggers = source.split('\non:\n', 1)[1].split('\npermissions:', 1)[0]
        self.assertIn('  workflow_dispatch:', triggers)
        self.assertNotIn('  push:', triggers)
        self.assertNotIn('  schedule:', triggers)
        self.assertIn('confirm_production_scan:', triggers)
        self.assertIn('default: false', triggers)
        self.assertIn("if: github.event_name == 'workflow_dispatch' && inputs.confirm_production_scan == true", source)
        self.assertNotIn('contains(github.event.head_commit.message', source)
        self.assertIn("if: inputs.mode == 'diagnose'", source)
        self.assertIn("if: inputs.mode == 'promote'", source)
        self.assertIn("if: inputs.mode == 'finish'", source)
        self.assertIn('cancel-in-progress: false', source)
