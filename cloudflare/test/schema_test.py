import sqlite3
import tomllib
import unittest
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
class AdditiveSchema(unittest.TestCase):
    def test_schema_is_repeatable_and_preserves_existing_rows(self):
        db = sqlite3.connect(':memory:')
        db.execute('CREATE TABLE existing_data (value TEXT)')
        db.execute("INSERT INTO existing_data VALUES ('keep')")
        sql = (ROOT / 'cloudflare/migrations/0001_additive_profiles.sql').read_text()
        db.executescript(sql)
        db.execute("INSERT INTO friendbook_cf_profiles(uid,display_name) VALUES ('u','Keep profile')")
        db.executescript(sql)
        self.assertEqual(db.execute('SELECT value FROM existing_data').fetchall(), [('keep',)])
        self.assertEqual(db.execute('SELECT display_name FROM friendbook_cf_profiles').fetchall(), [('Keep profile',)])
    def test_binding_targets_only_supplied_database(self):
        config = tomllib.loads((ROOT / 'wrangler.toml').read_text())
        self.assertEqual(config['name'], 'rough-firefly-dd7b')
        self.assertEqual(config['main'], 'worker.js')
        binding = config['d1_databases'][0]
        self.assertEqual(binding['binding'], 'DB')
        self.assertEqual(binding['database_name'], 'friendbook-db')
        self.assertEqual(binding['database_id'], 'e1c605ca-a50b-46c6-9deb-56dd1f70d163')
if __name__ == '__main__': unittest.main()
