import importlib.util
from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('retirement',ROOT/'scripts/admin/retire_legacy_notification_index.py')
p=importlib.util.module_from_spec(spec);spec.loader.exec_module(p)
class Retirement(unittest.TestCase):
 def test_exact_indexes_and_absent_old(self):
  rows=[{'name':name,'sql':sql} for name,sql in p.expected().items()]
  self.assertTrue(p.validate(rows));self.assertFalse(p.validate([r for r in rows if r['name']==p.PAGE]))
  for bad in ([],[r for r in rows if r['name']==p.OLD],rows+rows,
              [{'name':r['name'],'sql':r['sql'].replace('touserid','other')} for r in rows]):
   with self.assertRaises(ValueError):p.validate(bad)
 def test_only_fixed_drop_and_capped_aggregate(self):
  source=(ROOT/'scripts/admin/retire_legacy_notification_index.py').read_text()
  self.assertEqual(source.count("query('DROP INDEX"),1);self.assertNotIn('DELETE FROM',source)
  self.assertIn('LIMIT 257',source);self.assertIn('if args.apply and present:',source)
