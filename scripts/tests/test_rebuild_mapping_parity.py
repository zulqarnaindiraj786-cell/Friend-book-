"""The Worker module and the D1-API runner must map a row to the same document.

There are two implementations for one contract: cloudflare/document-rebuild.js runs inside
the Worker (admin route), scripts/admin/rebuild_documents_d1.py runs through the D1 REST API
(the path that has a repository credential today). A divergence would silently produce a
different app document depending on which one ran, so this asserts they agree byte for byte
on a fixture that covers JSON defaults, malformed JSON, 0/1 booleans, missing columns, the
users uid mirror and the chats timestamp column.
"""
from pathlib import Path
import json
import shutil
import subprocess
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1] / 'admin'))
import rebuild_documents_d1 as runner

ROOT = Path(__file__).parents[2]
WORKER_MODULE = ROOT / 'cloudflare' / 'document-rebuild.js'

FIXTURES = [
    {
        'collection': 'posts',
        'row': {
            'id': 'post_1', 'author_id': 'uid_1', 'author_name': 'A', 'content': 'hi',
            'media_url': None, 'media_type': 'image', 'mentions': '["uid_2"]',
            'privacy': 'Public', 'archived': 0, 'trashed_at': 0, 'likes': 4, 'comments': 2,
            'shares': 0, 'views': 9, 'created_at': 1700000000000, 'saved_by': '[]',
            'media_urls': '["https://cdn.example/a.jpg"]', 'poll_votes': '{"a":1}',
            'reactions_map': 'not-json', 'author_verified': '1', 'author_tick_level': 'gold',
        },
    },
    {
        'collection': 'posts',
        'row': {'id': 'bad/id', 'author_id': 'uid_1', 'created_at': 1},
    },
    {
        'collection': 'users',
        'row': {
            'uid': 'uid_1', 'display_name': 'Ayesha', 'banned': 0, 'is_admin': 1,
            'is_moderator': 0, 'deactivated': 0, 'blocked': '["uid_2"]', 'hobbies': None,
            'followers': '[]', 'following': '[]', 'created_at': 0, 'stars': 12,
        },
    },
    {
        'collection': 'chats',
        'row': {
            'id': 'chat_1', 'last_message': 'hello', 'last_message_time': 1700000005000,
            'is_group': 1, 'group_name': 'Team', 'created_by': 'uid_1',
            'only_admins_can_send': 0, 'admin_ids': '["uid_1"]', 'participants': '["uid_1","uid_2"]',
            'typing': '{}', 'participant_names': '{"uid_1":"A"}', 'participant_photos': '{}',
        },
    },
    {
        'collection': 'notifications',
        'row': {
            'id': 'n1', 'to_user_id': 'uid_1', 'from_user_id': 'uid_2',
            'from_user_name': 'B', 'from_user_photo': None, 'type': 'like',
            'post_id': 'post_1', 'group_id': None, 'text': 'liked', 'is_read': 0,
            'created_at': 1700000009000,
        },
    },
]


@unittest.skipUnless(shutil.which('node'), 'node is required to load the Worker module')
class MappingParityTests(unittest.TestCase):
    def workerResults(self):
        program = (
            "import { readFileSync } from 'node:fs';\n"
            f"const mod = await import({json.dumps(WORKER_MODULE.as_uri())});\n"
            "const input = JSON.parse(readFileSync(0, 'utf8'));\n"
            "const out = input.map(item => mod.mapRow(mod.SPEC_BY_COLLECTION.get(item.collection), item.row));\n"
            "process.stdout.write(JSON.stringify(out));\n"
        )
        completed = subprocess.run(
            ['node', '--input-type=module', '-e', program],
            input=json.dumps(FIXTURES), capture_output=True, text=True, timeout=120,
        )
        self.assertEqual(completed.returncode, 0, completed.stderr[-800:])
        return json.loads(completed.stdout)

    def test_both_mappers_produce_identical_documents(self):
        worker = self.workerResults()
        self.assertEqual(len(worker), len(FIXTURES))
        for fixture, result in zip(FIXTURES, worker):
            spec = runner.SPEC_BY_COLLECTION[fixture['collection']]
            mine = runner.map_row(spec, fixture['row'])
            label = f"{fixture['collection']}/{fixture['row'].get(spec['pk'])}"
            self.assertEqual(mine['error'], result['error'], label)
            if result['error']:
                self.assertIsNone(mine['doc'], label)
                continue
            self.assertEqual(json.dumps(mine['doc'], sort_keys=True),
                             json.dumps(result['doc'], sort_keys=True), label)
            self.assertEqual(mine['warnings'], result['warnings'], label)
            # Spot-check the contract itself, not only agreement.
            if fixture['collection'] == 'posts' and label.endswith('post_1'):
                self.assertIs(mine['doc']['archived'], False)
                self.assertIs(mine['doc']['authorVerified'], True)
                self.assertEqual(mine['doc']['mediaUrls'], ['https://cdn.example/a.jpg'])
                self.assertEqual(mine['doc']['reactionsMap'], {})
                self.assertEqual(mine['doc']['updatedAt'], 1700000000000)
                self.assertEqual(mine['warnings'], [{'column': 'reactions_map', 'reason': 'invalid-json'}])
            if fixture['collection'] == 'users' and label == 'users/uid_1':
                self.assertEqual(mine['doc']['id'], 'uid_1')
                self.assertEqual(mine['doc']['uid'], 'uid_1')
                self.assertIs(mine['doc']['isAdmin'], True)
                self.assertIs(mine['doc']['banned'], False)
                self.assertEqual(mine['doc']['hobbies'], [])
            if fixture['collection'] == 'chats':
                self.assertEqual(mine['doc']['updatedAt'], 1700000005000)
                self.assertIs(mine['doc']['isGroup'], True)


class RunnerUnitTests(unittest.TestCase):
    def test_camel_matches_the_worker_field_names(self):
        self.assertEqual(runner.camel('author_photo'), 'authorPhoto')
        self.assertEqual(runner.camel('is_admin'), 'isAdmin')
        self.assertEqual(runner.camel('poll_votes'), 'pollVotes')

    def test_chunks_respect_row_and_byte_budgets(self):
        documents = [{'document_json': 'x' * 1000} for _ in range(40)]
        batches = list(runner.chunks(documents))
        self.assertEqual(sum(len(batch) for batch in batches), 40)
        self.assertTrue(all(len(batch) <= runner.WRITE_CHUNK_ROWS for batch in batches))
        big = [{'document_json': 'y' * 50_000} for _ in range(3)]
        self.assertEqual([len(batch) for batch in runner.chunks(big)], [1, 1, 1])

    def test_write_never_replaces_by_default_and_replaces_when_asked(self):
        captured = []

        def fake_d1(sql, params=(), retries=3):
            captured.append(sql)
            return [], {'changes': len(params) // 6}

        documents = [{'path': 'posts/p1', 'collection': 'posts', 'doc_id': 'p1',
                      'owner_uid': 'u', 'document_json': '{"id":"p1"}', 'updated_at': 1}]
        with patch.object(runner, 'd1', fake_d1):
            written, skipped = runner.write_documents(documents)
            self.assertEqual((written, skipped), (1, 0))
            runner.write_documents(documents, overwrite=True)
        self.assertIn('ON CONFLICT(path) DO NOTHING', captured[0])
        self.assertIn('DO UPDATE SET', captured[1])

    def test_dry_run_reads_pages_and_writes_nothing(self):
        writes = []

        def fake_d1(sql, params=(), retries=3):
            if sql.strip().startswith('SELECT'):
                if params and params[0] == '':
                    return ([{'id': 'p1', 'author_id': 'u', 'created_at': 5, 'archived': 0},
                             {'id': 'p2', 'author_id': 'u', 'created_at': 6, 'archived': 0}], {})
                return [], {}
            writes.append(sql)
            return [], {'changes': 0}

        spec = runner.SPEC_BY_COLLECTION['posts']
        with patch.object(runner, 'd1', fake_d1):
            result = runner.rebuild(spec, apply=False)
        self.assertEqual(result['written'], 0)
        self.assertEqual(writes, [])
        self.assertEqual(result['scanned'], 2)
        self.assertEqual(result['converted'], 2)
        self.assertTrue(result['dryRun'])

    def test_a_full_page_that_does_not_advance_stops_instead_of_looping(self):
        def fake_d1(sql, params=(), retries=3):
            if sql.strip().startswith('SELECT'):
                # A full page every time, always the same last id: would loop forever.
                return ([{'id': 'p1', 'author_id': 'u'}], {})
            return [], {'changes': 1}

        spec = runner.SPEC_BY_COLLECTION['posts']
        with patch.object(runner, 'd1', fake_d1):
            with self.assertRaises(runner.D1Error):
                runner.rebuild(spec, apply=False, page=1)

    def test_missing_token_fails_closed(self):
        with patch.object(runner, 'API_TOKEN', ''):
            with self.assertRaises(runner.D1Error):
                runner._request('SELECT 1', [])

    def test_gate_and_plan_ordering_comes_from_the_spec_list(self):
        self.assertEqual([spec['collection'] for spec in runner.SPECS][:2], ['posts', 'users'])
        self.assertEqual(runner.TEST_COLLECTION, 'posts')
        self.assertNotIn('posts', runner.REMAINING)
        self.assertEqual(len(runner.REMAINING), 9)


if __name__ == '__main__':
    unittest.main()
