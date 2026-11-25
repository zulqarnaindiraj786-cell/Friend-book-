import importlib.util
from pathlib import Path
import unittest
spec=importlib.util.spec_from_file_location('calculator',Path(__file__).resolve().parents[1]/'quota_calculator.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class QuotaCalculatorTest(unittest.TestCase):
 def scenario(self):
  return dict(daily_users=1000,sessions_per_user=2,reads_per_session=50,writes_per_session=2,invocations_per_session=20,reads_daily_limit=100000,writes_daily_limit=5000,invocations_daily_limit=100000)
 def test_session_and_background_costs_are_separate(self):
  s=self.scenario();s['background_writes_per_day']=1001;r=m.calculate(s)
  self.assertEqual(r['daily_totals'],dict(reads=100000,writes=5001,invocations=40000));self.assertFalse(r['all_within_supplied_limits'])
 def test_boundary_is_inclusive_not_guarantee(self):
  r=m.calculate(self.scenario());self.assertTrue(r['all_within_supplied_limits']);self.assertIn('Not a free-plan guarantee',r['warning'])
 def test_invalid_missing_and_nonfinite_inputs_rejected(self):
  for value in [-1,True,'NaN','Infinity',None]:
   s=self.scenario();s['reads_per_session']=value
   with self.assertRaises(ValueError):m.calculate(s)
 def test_rows_not_http_counts_and_repeat_sessions_scale(self):
  s=self.scenario();s['reads_per_session']=250;r=m.calculate(s)
  self.assertEqual(500000,r['daily_totals']['reads']);self.assertFalse(r['within_supplied_limits']['reads'])
