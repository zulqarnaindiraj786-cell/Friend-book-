import importlib.util
import json
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('fcm_claim_gate', ROOT / 'scripts/loadtest/fcm_claim_migration_gate.py')
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)

class FcmMigrationGateTests(unittest.TestCase):
    def test_pending_migration_must_be_only_incremental_claims(self):
        self.assertTrue(gate.pending_safe('No migrations to apply!'))
        self.assertTrue(gate.pending_safe('Migrations to be applied:\n0085_fcm_claims_incremental.sql'))
        self.assertFalse(gate.pending_safe('failed to list'))
        self.assertFalse(gate.pending_safe('0085_fcm_claims_incremental.sql\n0086_other.sql'))
        self.assertFalse(gate.pending_safe('0084_friend_guard_exceptions.sql'))

    def test_bridge_schema_is_complete_and_exact(self):
        db=sqlite3.connect(':memory:')
        db.executescript((ROOT/'cloudflare/migrations/0056_fcm_device_claims.sql').read_text())
        db.executescript((ROOT/'cloudflare/migrations/0085_fcm_claims_incremental.sql').read_text())
        db.row_factory=sqlite3.Row
        def response():
            rows=db.execute('SELECT type,name,sql FROM sqlite_master WHERE name IN (?,?,?) LIMIT 3', (gate.OLD,gate.NEW,gate.BRIDGE)).fetchall()
            return [{'success':True,'results':[dict(row) for row in rows]}]
        self.assertTrue(gate.schema_safe(response()))
        bad=response();bad[0]['success']=False;self.assertFalse(gate.schema_safe(bad))
        bad=response();bad[0]['results'].pop();self.assertFalse(gate.schema_safe(bad))
        db.execute('DROP TRIGGER '+gate.BRIDGE)
        db.execute('CREATE TRIGGER '+gate.BRIDGE+' BEFORE INSERT ON '+gate.OLD+' BEGIN SELECT 1; END')
        self.assertFalse(gate.schema_safe(response()))
        db.close()

if __name__ == '__main__': unittest.main()
