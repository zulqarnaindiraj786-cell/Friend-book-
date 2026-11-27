import importlib.util
import math
from pathlib import Path
import unittest

path = Path(__file__).resolve().parents[1] / 'loadtest/d1_budget_forecast.py'
spec = importlib.util.spec_from_file_location('d1_budget_forecast', path)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class BudgetForecastTests(unittest.TestCase):
    def test_projection_and_headroom_are_not_a_guarantee(self):
        result = module.estimate(100_000, 20_000, 6, 5_000_000, 100_000, 0.20)
        self.assertEqual(result['reads']['projected_end_of_day_at_average_rate'], 400_000)
        self.assertEqual(result['reads']['status'], 'WITHIN_LINEAR_ESTIMATE_ONLY')
        self.assertEqual(result['writes']['projected_end_of_day_at_average_rate'], 80_000)
        self.assertEqual(result['writes']['status'], 'PROJECTED_OVER_HEADROOM')
        self.assertIn('No capacity or availability guarantee', result['warning'])

    def test_reached_and_exceeded_limits(self):
        result = module.estimate(5_100_000, 100_000, 24, 5_000_000, 100_000, 0.2)
        self.assertEqual(result['reads']['status'], 'LIMIT_REACHED')
        self.assertEqual(result['writes']['status'], 'LIMIT_REACHED')
        self.assertEqual(result['reads']['remaining_before_limit'], 0)

    def test_reject_bad_counters_or_configuration(self):
        for args in [(-1, 0, 1, 5, 5, 0.2), (0, 0, 0, 5, 5, 0.2),
                     (0, 0, 25, 5, 5, 0.2), (0, 0, math.nan, 5, 5, 0.2),
                     (0, 0, 1, 0, 5, 0.2), (0, 0, 1, 5, 5, 1),
                     (0, 0, 1, 5, 5, math.inf)]:
            with self.subTest(args=args), self.assertRaises(ValueError):
                module.estimate(*args)


if __name__ == '__main__':
    unittest.main()
