from pathlib import Path
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[2]

class ChatProbeIndexTest(unittest.TestCase):
    def test_probe_uses_partial_index_without_losing_legacy_detection(self):
        db = sqlite3.connect(':memory:')
        db.execute('CREATE TABLE friendbook_cf_firestore_documents(parent_path TEXT, doc_id TEXT, document_json TEXT)')
        migration = (ROOT / 'cloudflare/migrations/0057_chat_compatibility_probe.sql').read_text()
        db.executescript(migration)
        source = (ROOT / 'cloudflare/firestore-documents.js').read_text()
        sql = next(part for part in source.split('`') if 'SELECT 1 AS hit FROM friendbook_cf_firestore_documents WHERE parent_path=? AND parent_path GLOB' in part)
        path = 'chats/sample/messages'
        db.executemany('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?)', [(path,str(i),'{"createdAt":123}') for i in range(1000)])
        self.assertIn('idx_cf_chat_invalid_order', str(db.execute('EXPLAIN QUERY PLAN '+sql,(path,)).fetchall()))
        self.assertIsNone(db.execute(sql,(path,)).fetchone())
        db.execute('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?)',(path,'old','{}'))
        self.assertEqual(db.execute(sql,(path,)).fetchone(),(1,))
