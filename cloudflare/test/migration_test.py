import importlib.util
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('copy_tool', ROOT / 'scripts/admin/prepare_d1_copy.py')
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)
BATCH = '550e8400-e29b-41d4-a716-446655440000'

class CopyVerification(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.source = self.root / 'source.jsonl'
        self.documents = [
            {'name': tool.PREFIX + "users/u'1", 'fields': {
                'largeInteger': {'integerValue': '9223372036854775807'},
                'photo': {'stringValue': 'https://res.cloudinary.com/demo/image/upload/photo.jpg'},
                'name': {'stringValue': "علی's profile"},
                'nested': {'mapValue': {'fields': {'nil': {'nullValue': None}}}},
                'created': {'timestampValue': '2026-11-04T00:00:00.123456789Z'}}},
            {'name': tool.PREFIX + 'posts/p1', 'fields': {'author': {'referenceValue': tool.PREFIX + "users/u'1"}}},
            {'name': tool.PREFIX + 'chats/c1/messages/m1', 'fields': {'body': {'stringValue': 'keep'}}},
        ]
        self.source.write_text('\n'.join(json.dumps(d) for d in self.documents))

    def copied_rows(self):
        out = self.root / 'output'
        report = tool.prepare(self.source, out, BATCH)
        self.assertFalse(report['safe_to_cutover'])
        db = sqlite3.connect(':memory:')
        db.row_factory = sqlite3.Row
        db.execute('CREATE TABLE old_data(value TEXT)')
        db.execute("INSERT INTO old_data VALUES ('keep')")
        schema = (ROOT / 'cloudflare/migrations/0002_additive_copy_staging.sql').read_text()
        db.executescript(schema)
        db.executescript((out / 'copy.sql').read_text())
        db.executescript(schema)
        self.assertEqual(db.execute('SELECT value FROM old_data').fetchone()[0], 'keep')
        with self.assertRaises(sqlite3.IntegrityError):
            db.executescript((out / 'copy.sql').read_text())
        return [dict(row) for row in db.execute('SELECT * FROM friendbook_cf_copy_staging')]

    def check(self, rows):
        destination = self.root / 'destination.json'
        destination.write_text(json.dumps(rows))
        return tool.verify(self.source, destination, BATCH)

    def test_roundtrip_preserves_typed_data_and_never_certifies_cutover(self):
        rows = self.copied_rows()
        report = self.check(rows)
        self.assertTrue(report['provided_exports_match'])
        self.assertEqual(report['source_documents'], 3)
        self.assertEqual(report['collection_counts']['chats/c1/messages'], 1)
        self.assertFalse(report['production_copy_verified'])
        self.assertFalse(report['safe_to_cutover'])
        self.assertEqual(self.check([{'success': True, 'results': rows}]), report)
        with self.assertRaises(FileExistsError):
            tool.prepare(self.source, self.root / 'output', BATCH)

    def test_missing_extra_duplicate_corrupt_wrong_batch_or_identity_fail(self):
        rows = self.copied_rows()
        variants = [rows[:-1], rows + [rows[0]], [{'success': False, 'results': rows}]]
        extra_doc = {'name': tool.PREFIX + 'posts/extra', 'fields': {}}
        extra_text = tool.canonical(extra_doc)
        variants.append(rows + [{'batch_id': BATCH, 'path': 'posts/extra',
                                'document_json': extra_text, 'sha256': tool.digest(extra_text)}])
        for field, value in [('batch_id', 'wrong'), ('document_json', '{}'), ('sha256', 'wrong'), ('path', 'users/victim')]:
            changed = [dict(row) for row in rows]
            changed[0][field] = value
            variants.append(changed)
        for variant in variants:
            with self.subTest(variant_type=type(variant).__name__), self.assertRaises(ValueError):
                self.check(variant)
        changed = [dict(row) for row in rows]
        doc = json.loads(changed[0]['document_json'])
        doc['fields']['extra'] = {'stringValue': 'changed'}
        changed[0]['document_json'] = tool.canonical(doc)
        changed[0]['sha256'] = tool.digest(changed[0]['document_json'])
        with self.assertRaises(ValueError): self.check(changed)

    def test_empty_duplicate_wrong_project_and_oversized_exports_rejected(self):
        for text in ['', json.dumps(self.documents[0]) + '\n' + json.dumps(self.documents[0]),
                     '{"name":"wrong"}', '{"name":"a","name":"b"}']:
            self.source.write_text(text)
            with self.assertRaises(ValueError): tool.source_documents(self.source)
        doc = self.documents[0]
        doc['fields']['large'] = {'stringValue': 'x' * 100_000}
        self.source.write_text(json.dumps(doc))
        with self.assertRaises(ValueError): tool.prepare(self.source, self.root / 'output', BATCH)
        self.assertFalse((self.root / 'output').exists())

if __name__ == '__main__': unittest.main()
