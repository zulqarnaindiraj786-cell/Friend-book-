import sqlite3
import unittest
from scripts.admin import install_chat_activity_seek as p

class ChatActivitySeekTest(unittest.TestCase):
    def provider(self,extra=1):
        db=sqlite3.connect(':memory:')
        db.row_factory=sqlite3.Row
        def query(sql,params=()):
            indexed=bool(db.execute("SELECT 1 FROM sqlite_schema WHERE type='index' AND name LIKE '%_new'").fetchone())
            cursor=db.execute(sql,params)
            results=[dict(row) for row in cursor.fetchall()] if cursor.description else []
            changes=0
            if sql.startswith('UPDATE '):
                changes=1
                if indexed and params[0] in ('chats/direct','chats/group'):changes+=extra
            return {'results':results,'meta':{'rows_written':changes,'rows_read':0}}
        return db,query

    def test_probe_gates_constant_write_cost_and_cleans_own_objects(self):
        db,query=self.provider()
        try:
            result=p.isolated_probe(query,'a'*32)
            self.assertTrue(result['cleanup_verified'])
            self.assertEqual(result['additional_billed_rows_written']['group'],{'activity_update':1,'metadata_update':1})
            self.assertEqual(db.execute('SELECT count(*) FROM sqlite_schema').fetchone()[0],0)
        finally:db.close()

    def test_excess_cost_rejects_and_still_cleans_up(self):
        db,query=self.provider(3)
        try:
            with self.assertRaisesRegex(ValueError,'cost too high'):p.isolated_probe(query,'b'*32)
            self.assertEqual(db.execute('SELECT count(*) FROM sqlite_schema').fetchone()[0],0)
        finally:db.close()

    def test_identifier_cannot_escape_reserved_probe_namespace(self):
        for suffix in ('','x'*32,'a'*31,'a'*33,'a; DROP TABLE users'):
            with self.assertRaises(ValueError):p.isolated_probe(lambda *_:self.fail('must not query'),suffix)

    def test_reviewed_index_only_covers_root_chat_paths_and_has_no_temp_sort(self):
        db=sqlite3.connect(':memory:')
        try:
            db.execute('CREATE TABLE friendbook_cf_firestore_documents(path TEXT PRIMARY KEY,doc_id TEXT,document_json TEXT)')
            db.executescript(p.index_sql())
            expression=p.index_sql().split('ON friendbook_cf_firestore_documents(',1)[1].rsplit(',doc_id DESC)',1)[0]
            plan=db.execute(f"EXPLAIN QUERY PLAN SELECT path,doc_id FROM friendbook_cf_firestore_documents INDEXED BY {p.NAME} WHERE path='chats/'||doc_id ORDER BY {expression},doc_id DESC LIMIT ?",(64,)).fetchall()
            self.assertTrue(any(p.NAME in row[3] for row in plan))
            self.assertFalse(any('TEMP B-TREE' in row[3] for row in plan))
        finally:db.close()
