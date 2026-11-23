import importlib.util
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('d1_account_usage', ROOT / 'scripts/loadtest/d1_account_usage_analytics.py')
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)


class AccountUsageTests(unittest.TestCase):
    def test_account_wide_query_and_valid_response(self):
        query, variables = p.make_query('2026-01-01')
        self.assertNotIn('databaseId', query)
        self.assertEqual(variables['day'], '2026-01-01')
        payload = {'data': {'viewer': {'accounts': [{'d1AnalyticsAdaptiveGroups': [{'sum': {'rowsRead': 10, 'rowsWritten': 3}}]}]}}}
        self.assertEqual(p.parse(payload), {'rowsRead': 10, 'rowsWritten': 3})

    def test_preflight_budget_has_reserve(self):
        self.assertTrue(p.within_preflight_budget({'rowsRead': 3_000_000, 'rowsWritten': 50_000}))
        self.assertFalse(p.within_preflight_budget({'rowsRead': 3_000_001, 'rowsWritten': 10}))
        self.assertFalse(p.within_preflight_budget({'rowsRead': 10, 'rowsWritten': 50_001}))

    def test_metadata_only_fcm_rollout_has_separate_reserve(self):
        self.assertTrue(p.within_fcm_metadata_budget({'rowsRead': 3_000_000, 'rowsWritten': 75_000}))
        self.assertFalse(p.within_fcm_metadata_budget({'rowsRead': 3_000_001, 'rowsWritten': 0}))
        self.assertFalse(p.within_fcm_metadata_budget({'rowsRead': 0, 'rowsWritten': 75_001}))
        # This exception must not raise the existing index-build preflight budget.
        self.assertFalse(p.within_preflight_budget({'rowsRead': 0, 'rowsWritten': 50_001}))

    def test_two_user_backfill_has_a_separate_bounded_reserve(self):
        self.assertTrue(p.within_user_backfill_budget({'rowsRead': 3_000_000, 'rowsWritten': 60_000}))
        self.assertFalse(p.within_user_backfill_budget({'rowsRead': 3_000_001, 'rowsWritten': 0}))
        self.assertFalse(p.within_user_backfill_budget({'rowsRead': 0, 'rowsWritten': 60_001}))
        self.assertFalse(p.within_preflight_budget({'rowsRead': 0, 'rowsWritten': 50_001}))

    def test_retired_drop_has_a_separate_bounded_reserve(self):
        self.assertTrue(p.within_retired_drop_budget({'rowsRead': 3_000_000, 'rowsWritten': 60_000}))
        self.assertFalse(p.within_retired_drop_budget({'rowsRead': 3_000_001, 'rowsWritten': 0}))
        self.assertFalse(p.within_retired_drop_budget({'rowsRead': 0, 'rowsWritten': 60_001}))
        self.assertFalse(p.within_preflight_budget({'rowsRead': 0, 'rowsWritten': 50_001}))

    def test_notification_read_only_inspection_has_separate_reserve(self):
        self.assertTrue(p.within_notification_inspection_budget({'rowsRead': 3_000_000, 'rowsWritten': 75_000}))
        self.assertFalse(p.within_notification_inspection_budget({'rowsRead': 3_000_001, 'rowsWritten': 0}))
        self.assertFalse(p.within_notification_inspection_budget({'rowsRead': 0, 'rowsWritten': 75_001}))
        self.assertFalse(p.within_preflight_budget({'rowsRead': 0, 'rowsWritten': 50_001}))

    def test_missing_or_invalid_analytics_is_not_treated_as_zero(self):
        with self.assertRaises(ValueError): p.parse({'errors': [{'message': 'rejected'}]})
        for groups in ([], [{'sum': {'rowsRead': None, 'rowsWritten': 0}}], [{'sum': {'rowsRead': -1, 'rowsWritten': 0}}]):
            with self.assertRaises(ValueError): p.parse({'data': {'viewer': {'accounts': [{'d1AnalyticsAdaptiveGroups': groups}]}}})


if __name__ == '__main__': unittest.main()
