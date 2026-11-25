import json
import sqlite3
import unittest
from scripts.loadtest.canonical_semantic_inventory import decode_value, inspect, plain_document


class CanonicalSemanticInventoryTest(unittest.TestCase):
    def test_firestore_wire_values_decode_without_exposing_content(self):
        self.assertEqual(decode_value({'stringValue':'x'}), 'x')
        self.assertEqual(decode_value({'integerValue':'12'}), 12)
        self.assertEqual(decode_value({'arrayValue':{'values':[{'booleanValue':True}]}}), [True])
        self.assertEqual(decode_value({'mapValue':{'fields':{'n':{'doubleValue':2.5}}}}), {'n':2.5})

    def test_plain_firestore_envelope_and_legacy_json_compare_semantically(self):
        wire=json.dumps({'fields':{'name':{'stringValue':'Alice'},'score':{'integerValue':'4'}},'createTime':'2024-01-01T00:00:00Z'})
        canonical=plain_document(wire,'uid1')
        legacy={'name':'Alice','score':4,'createdAt':1704067200000,'id':'uid1'}
        self.assertEqual(canonical,legacy)

    def test_inventory_reports_only_aggregate_counts(self):
        db=sqlite3.connect(':memory:');db.row_factory=sqlite3.Row
        self.addCleanup(db.close)
        db.executescript('''CREATE TABLE friendbook_cf_documents(path TEXT PRIMARY KEY,collection TEXT,doc_id TEXT,owner_uid TEXT,document_json TEXT);
        CREATE TABLE friendbook_cf_firestore_documents(path TEXT PRIMARY KEY,doc_id TEXT,owner_uid TEXT,document_json TEXT,create_time TEXT);''')
        legacy={'name':'Alice','id':'u','createdAt':1704067200000}
        wire={'fields':{'name':{'stringValue':'Alice'}},'createTime':'2024-01-01T00:00:00Z'}
        db.execute('INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?)',('users/u','users','u','u',json.dumps(legacy)))
        db.execute('INSERT INTO friendbook_cf_firestore_documents VALUES (?,?,?,?,?)',('users/u','u','u',json.dumps(wire),'2024-01-01T00:00:00Z'))
        def query(sql,params):
            return [dict(r) for r in db.execute(sql,params)], {'rows_read':1}
        result=inspect(query,page_size=1)
        user=result['collections']['users']
        self.assertEqual(user['semantic_match'],1)
        self.assertEqual(user['canonical_missing'],0)
        self.assertNotIn('Alice',str(result))

    def test_page_size_is_bounded(self):
        with self.assertRaises(ValueError): inspect(lambda *_: ([],{'rows_read':0}),0)
        with self.assertRaises(ValueError): inspect(lambda *_: ([],{'rows_read':0}),1001)


if __name__=='__main__': unittest.main()
