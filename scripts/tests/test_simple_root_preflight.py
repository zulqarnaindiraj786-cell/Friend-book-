import unittest
from scripts.loadtest.simple_root_preflight import summarize

class SimpleRootPreflightTest(unittest.TestCase):
 def test_caps_and_unusual_counts(self):
  payload={'success':True,'result':[{'success':True,'results':[{'kind':'pages','sampled':257,'unusual':2},{'kind':'events','sampled':3,'unusual':0}],'meta':{'rows_read':260}}]}
  result=summarize(payload)
  self.assertTrue(result['collections']['pages']['truncated'])
  self.assertFalse(result['collections']['events']['truncated'])
  self.assertEqual(result['d1_reported_rows_read'],260)
  payload['result'][0]['results'][1]['unusual']=4
  with self.assertRaises(ValueError):summarize(payload)
 def test_missing_and_duplicate_rejected(self):
  with self.assertRaises(ValueError):summarize({'success':True,'result':[{'success':True,'results':[{'kind':'events','sampled':0,'unusual':0}]}]})
