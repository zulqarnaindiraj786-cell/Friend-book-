"""Synthetic read-only API fixtures. No live export/production proof implied."""
from pathlib import Path
import json
import subprocess
import sys
import tempfile
import unittest
from scripts.admin.full_firestore_snapshot import FullFirestoreSnapshot, private_source_copy
from scripts.admin.copy_interaction_snapshot import PREFIX, FIRESTORE, BackupError, copy_and_verify
from scripts.tests.test_interaction_snapshot import Response, Destination

ROOT = Path(__file__).resolve().parents[2]
TIME = '2026-11-05T01:00:00Z'


class FullSource:
    def __init__(self):
        self.calls = []
        self.invalid = False
        self.docs = {
            PREFIX + '/users/u': {'createTime': TIME, 'fields': {
                'counter': {'integerValue': '9007199254740993'},
                'blocked': {'arrayValue': {'values': [{'stringValue': 'other'}]}}}},
            PREFIX + '/chats/missing': {},
            PREFIX + '/chats/missing/messages/old-id': {'createTime': TIME, 'fields': {
                'text': {'stringValue': 'keep exact message'},
                'readBy': {'mapValue': {'fields': {'u': {'booleanValue': True}}}}}},
            PREFIX + '/customLedger/record-id': {'createTime': TIME, 'fields': {
                'media': {'stringValue': 'https://res.cloudinary.com/example/old-original.jpg'},
                'paid': {'nullValue': None}}},
        }
        for name, doc in self.docs.items():
            doc['name'] = name

    def post(self, url, json, timeout):
        self.calls.append((url, json))
        parent, operation = url[len(FIRESTORE):].rsplit(':', 1)
        if operation == 'runAggregationQuery':
            collection = json['structuredAggregationQuery']['structuredQuery']['from'][0]['collectionId']
            prefix = parent + '/' + collection + '/'
            count = sum(name.startswith(prefix) and '/' not in name[len(prefix):] and 'createTime' in doc
                        for name, doc in self.docs.items())
            return Response([{'readTime': TIME, 'result': {'aggregateFields': {
                'total': {'integerValue': str(count)}}}}])
        assert operation == 'listCollectionIds'
        if parent == PREFIX:
            if self.invalid:
                return Response({'collectionIds': ['../other-project']})
            return Response({'collectionIds': ['customLedger']} if json.get('pageToken') else
                            {'collectionIds': ['users', 'chats'], 'nextPageToken': 'root-page2'})
        return Response({'collectionIds': ['messages'] if parent.endswith('/chats/missing') else []})

    def get(self, url, params, timeout):
        self.calls.append((url, params))
        prefix = url[len(FIRESTORE):] + '/'
        return Response({'documents': [doc for name, doc in self.docs.items()
                                       if name.startswith(prefix) and '/' not in name[len(prefix):]]})


class FullFirestoreSnapshotTest(unittest.TestCase):
    def test_all_roots_pagination_orphans_and_typed_history_preserved_at_one_read_time(self):
        source = FullSource()
        reader = FullFirestoreSnapshot(source)
        docs = reader.export()
        self.assertEqual(len(docs), 3)
        self.assertEqual(set(reader.roots), {'users', 'chats', 'customLedger'})
        self.assertNotIn(PREFIX + '/chats/missing', docs)
        self.assertIn('9007199254740993', docs[PREFIX + '/users/u'])
        self.assertEqual(json.loads(docs[PREFIX + '/customLedger/record-id']),
                         source.docs[PREFIX + '/customLedger/record-id'])
        for _, body in source.calls[1:]:
            self.assertEqual(body['readTime'], TIME)
        destination = Destination()
        copy_and_verify(destination, 'full-domain-fixture', docs)
        self.assertEqual(len(destination.rows), 3)
        self.assertTrue(all(sql.startswith(('CREATE TABLE IF NOT EXISTS', 'INSERT INTO', 'SELECT'))
                            for sql in destination.sql))
        with self.assertRaises(BackupError):
            reader.export()

    def test_limits_abort_without_truncating_or_claiming_success(self):
        for budgets in ({'max_documents': 1}, {'max_bytes': 1}):
            with self.assertRaises(BackupError):
                FullFirestoreSnapshot(FullSource(), **budgets).export()
        with self.assertRaises(ValueError):
            FullFirestoreSnapshot(FullSource(), max_documents=0)

    def test_invalid_collection_paths_fail_before_arbitrary_traversal(self):
        source = FullSource()
        source.invalid = True
        with self.assertRaises(BackupError):
            FullFirestoreSnapshot(source).export()
        self.assertEqual(len(source.calls), 2)

    def test_private_local_copy_permissions_and_no_backup_reuse(self):
        reader = FullFirestoreSnapshot(FullSource())
        reader.export()
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / 'private-batch'
            private_source_copy(reader, output)
            self.assertEqual(output.stat().st_mode & 0o777, 0o700)
            self.assertEqual((output / 'source.jsonl').stat().st_mode & 0o777, 0o600)
            self.assertEqual(len((output / 'source.jsonl').read_text().splitlines()), 3)
            manifest = json.loads((output / 'manifest.json').read_text())
            self.assertFalse(manifest['safe_to_cutover'])
            self.assertFalse(manifest['other_firebase_services_backed_up'])
            with self.assertRaises(FileExistsError):
                private_source_copy(reader, output)

    def test_cli_default_cannot_accidentally_contact_providers(self):
        run = subprocess.run([sys.executable, str(ROOT / 'scripts/admin/full_firestore_snapshot.py')],
                             capture_output=True, text=True)
        self.assertEqual(run.returncode, 2)
        self.assertIn('no provider contacted', run.stdout)
        self.assertNotIn('Traceback', run.stderr)

    def test_unexported_or_symlink_output_cannot_be_certified_as_full_private_copy(self):
        with tempfile.TemporaryDirectory() as folder:
            reader = FullFirestoreSnapshot(FullSource())
            with self.assertRaises(BackupError):
                private_source_copy(reader, Path(folder) / 'incomplete')
            reader.export()
            (Path(folder) / 'linked').symlink_to(Path(folder), target_is_directory=True)
            with self.assertRaises(BackupError):
                private_source_copy(reader, Path(folder) / 'linked' / 'batch')
