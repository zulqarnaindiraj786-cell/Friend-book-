from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class D1QuotaAuditCostGuard(unittest.TestCase):
    def test_full_mirror_scan_requires_two_independent_opt_ins(self):
        source = (ROOT / '.github/workflows/d1-quota-audit.yml').read_text()
        self.assertIn("description: 'Expensive: compare all mirror/snapshot rows", source)
        self.assertIn('recent runs cost about 58k–127k D1 reads', source)
        self.assertIn('authorize_expensive_snapshot_scan:', source)
        self.assertIn('default: false', source)
        self.assertIn('inputs.compare_mirror == true && inputs.authorize_expensive_snapshot_scan == true', source)
        self.assertIn('inputs.compare_mirror == true && inputs.authorize_expensive_snapshot_scan != true', source)
        self.assertIn('needs both explicit confirmations', source)

    def test_routine_usage_audit_skips_unrelated_schema_scan(self):
        source = (ROOT / '.github/workflows/d1-quota-audit.yml').read_text()
        job = source.split('  canonical-mirror-state:', 1)[1].split('    runs-on:', 1)[0]
        self.assertIn("inputs.compare_mirror == true", job)
        self.assertIn('sqlite_master', source)

    def test_normal_quota_report_stays_analytics_only(self):
        source = (ROOT / '.github/workflows/d1-quota-audit.yml').read_text()
        self.assertIn('d1QueriesAdaptiveGroups', source)
        self.assertNotIn('wrangler d1 execute', source)
        self.assertNotIn('wrangler d1 migrations apply', source)


if __name__ == '__main__':
    unittest.main()
