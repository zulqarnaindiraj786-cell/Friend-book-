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

    def test_missing_or_invalid_analytics_is_not_treated_as_zero(self):
        with self.assertRaises(ValueError): p.parse({'errors': [{'message': 'rejected'}]})
        for groups in ([], [{'sum': {'rowsRead': None, 'rowsWritten': 0}}], [{'sum': {'rowsRead': -1, 'rowsWritten': 0}}]):
            with self.assertRaises(ValueError): p.parse({'data': {'viewer': {'accounts': [{'d1AnalyticsAdaptiveGroups': groups}]}}})


if __name__ == '__main__': unittest.main()
