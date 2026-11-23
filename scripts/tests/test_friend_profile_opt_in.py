import contextlib
import importlib.util
import io
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('friend_profile',ROOT/'scripts/loadtest/profile_friend_guard.py')
p=importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)


class ProfileOptIn(unittest.TestCase):
    def test_no_scan_without_explicit_cli_flag(self):
        with patch.object(sys,'argv',['profile_friend_guard.py']), patch.object(p.subprocess,'run') as run, patch.object(p.urllib.request,'urlopen') as urlopen, contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as error: p.main()
            self.assertEqual(error.exception.code,2)
            run.assert_not_called()
            urlopen.assert_not_called()

    def test_original_full_guard_is_excluded_unless_separately_enabled(self):
        probes=[{'name':'indexed-duplicate-guard'},{'name':'original-duplicate-guard'}]
        self.assertEqual(p.select_probes(probes),probes[:1])
        self.assertEqual(p.select_probes(probes,True),probes)

    def test_workflow_requires_both_markers_and_omits_baseline(self):
        workflow=(ROOT/'.github/workflows/friend-guard-preflight.yml').read_text()
        gate=next(line for line in workflow.splitlines() if '    if:' in line)
        self.assertIn('[friend-index-preflight]',gate)
        self.assertIn('&&',gate)
        self.assertIn('[allow-production-scans]',gate)
        self.assertNotIn('--include-full-scan-baseline',workflow)
