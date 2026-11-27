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


    def test_related_diagnostics_and_legacy_promotion_require_confirmation(self):
        for name in ('coverage-proof','canonical-inventory','legacy-user-backfill'):
            with self.subTest(workflow=name):
                source=(ROOT/'.github/workflows'/f'{name}.yml').read_text()
                triggers=source.split('\non:\n',1)[1].split('\npermissions:',1)[0]
                self.assertIn('workflow_dispatch:',triggers)
                self.assertIn('confirm_production_scan:',triggers)
                self.assertIn('default: false',triggers)
                self.assertNotIn('  push:',triggers)
                self.assertNotIn('  schedule:',triggers)
                self.assertIn("if: github.event_name == 'workflow_dispatch' && inputs.confirm_production_scan == true",source)
                self.assertNotIn('github.event.head_commit.message',source)
        promotion=(ROOT/'.github/workflows/legacy-user-backfill.yml').read_text()
        for mode in ('legacy-user-preview','legacy-user-backfill','legacy-root-preview',
                     'legacy-request-preview','legacy-request-backfill','legacy-friend-preview','legacy-friend-backfill'):
            self.assertIn(f"inputs.mode == '{mode}'",promotion)
        self.assertIn('cancel-in-progress: false',promotion)
