import importlib.util
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('retirement',ROOT/'scripts/admin/retire_notification_recipient_index.py')
p=importlib.util.module_from_spec(spec);spec.loader.exec_module(p)

class Retirement(unittest.TestCase):
    def test_exact_replacement_required_old_definition_must_match(self):
        rows=[{'name':name,'sql':sql} for name,sql in p.expected().items()]
        self.assertTrue(p.validate(rows))
        self.assertFalse(p.validate([row for row in rows if row['name']==p.PAGE]))
        for bad in ([],[row for row in rows if row['name']==p.OLD],
                    [{'name':row['name'],'sql':row['sql'].replace('touserid','other')} for row in rows],
                    [{'name':row['name'],'sql':row['sql'].replace("parent_path='notifications'","parent_path='users'")} for row in rows]):
            with self.assertRaises(ValueError):p.validate(bad)
    def test_only_fixed_index_drop_no_record_changes_or_data_scan(self):
        source=(ROOT/'scripts/admin/retire_notification_recipient_index.py').read_text()
        self.assertEqual(source.count("query('DROP INDEX"),1)
        self.assertNotIn('DELETE FROM',source)
        self.assertNotIn('SELECT *',source)
        self.assertNotIn('SELECT COUNT',source)
        self.assertIn('if args.apply and present:',source)
