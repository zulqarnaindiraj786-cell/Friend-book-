from argparse import Namespace
from pathlib import Path
import json
import sys
import unittest

sys.path.insert(0, str(Path(__file__).parents[1] / 'admin'))
import rebuild_documents as tool


def page(**overrides):
    body = {'collection': 'posts', 'dryRun': True, 'scanned': 30, 'converted': 30,
            'written': 0, 'skippedExisting': 0, 'oversized': 0, 'invalid': 0,
            'nextCursor': None}
    body.update(overrides)
    return 200, body


class FakeCall:
    def __init__(self, responses):
        self.responses = list(responses)
        self.requests = []

    def __call__(self, path, method='GET', payload=None):
        self.requests.append((path, method, payload))
        if not self.responses:
            raise AssertionError('unexpected request')
        return self.responses.pop(0)


class RebuildToolTests(unittest.TestCase):
    def test_routes_stay_inside_the_existing_migration_namespace(self):
        args = Namespace(command='plan')
        call = FakeCall([(200, {'collections': []})])
        tool.run(args, call, log=lambda _: None)
        self.assertEqual(call.requests[0][0], '/v1/admin/migrate/documents/plan')

        verify = Namespace(command='verify', collection='posts', sample=40)
        call = FakeCall([(200, {'mismatchCount': 0})])
        tool.run(verify, call, log=lambda _: None)
        self.assertEqual(call.requests[0][0], '/v1/admin/migrate/documents/verify?collection=posts&sample=40')

        rebuild = FakeCall([page(nextCursor=None)])
        tool.rebuild_collection(rebuild, 'posts')
        self.assertEqual(rebuild.requests[0][0], '/v1/admin/migrate/documents/rebuild')
        self.assertEqual(rebuild.requests[0][1], 'POST')

    def test_dry_run_is_default_and_follows_the_cursor(self):
        call = FakeCall([
            page(nextCursor='p30'),
            page(scanned=5, converted=5, nextCursor=None),
        ])
        totals = tool.rebuild_collection(call, 'posts')
        self.assertTrue(totals['dryRun'])
        self.assertEqual(totals['pages'], 2)
        self.assertEqual(totals['scanned'], 35)
        self.assertTrue(all(request[2]['apply'] is False for request in call.requests))
        self.assertEqual([request[2]['after'] for request in call.requests], ['', 'p30'])

    def test_apply_and_overwrite_are_forwarded_only_when_requested(self):
        call = FakeCall([page(nextCursor=None)])
        tool.rebuild_collection(call, 'posts', apply=True, overwrite=True, limit=40)
        self.assertEqual(call.requests[0][2],
                         {'collection': 'posts', 'apply': True, 'overwrite': True,
                          'after': '', 'limit': 40})

    def test_non_advancing_cursor_stops_instead_of_looping(self):
        call = FakeCall([page(nextCursor='p30'), page(nextCursor='p30')])
        with self.assertRaises(tool.RebuildError):
            tool.rebuild_collection(call, 'posts')

    def test_gate_block_is_reported_and_run_returns_exit_code_2(self):
        call = FakeCall([(409, {'error': 'gate', 'gate': {'passed': False,
                                                          'sourceRows': 2, 'documentRows': 0}})])
        args = Namespace(command='run', collection='users', apply=True, overwrite=False,
                         limit=0, max_pages=10)
        self.assertEqual(tool.run(args, call, log=lambda _: None), 2)

    def test_run_all_stops_at_the_blocked_collection(self):
        call = FakeCall([
            page(nextCursor=None),                       # posts (already verified)
            (409, {'gate': {'passed': False}}),          # users blocked
        ])
        args = Namespace(command='run', collection='all', apply=True, overwrite=False,
                         limit=0, max_pages=10)
        self.assertEqual(tool.run(args, call, log=lambda _: None), 2)
        self.assertEqual(len(call.requests), 2, 'no collection runs after the gate blocks')

    def test_verify_exit_code_reflects_field_mismatches(self):
        args = Namespace(command='verify', collection='posts', sample=40)
        good = FakeCall([(200, {'mismatchCount': 0, 'checked': 40})])
        bad = FakeCall([(200, {'mismatchCount': 2, 'checked': 40})])
        self.assertEqual(tool.run(args, good, log=lambda _: None), 0)
        self.assertEqual(tool.run(args, bad, log=lambda _: None), 1)

    def test_plan_is_printed_as_json(self):
        args = Namespace(command='plan')
        call = FakeCall([(200, {'collections': [], 'totals': {'pending': 0}})])
        lines = []
        self.assertEqual(tool.run(args, call, log=lines.append), 0)
        self.assertEqual(json.loads(lines[0])['totals']['pending'], 0)

    def test_unknown_collection_is_refused_before_any_request(self):
        call = FakeCall([])
        with self.assertRaises(tool.RebuildError):
            tool.rebuild_collection(call, 'admins')
        self.assertEqual(call.requests, [])

    def test_only_posts_is_the_declared_test_collection(self):
        self.assertEqual(tool.TEST_COLLECTION, 'posts')
        self.assertEqual(len(tool.COLLECTIONS), 10)
        self.assertEqual(tool.COLLECTIONS[0], 'posts')


if __name__ == '__main__':
    unittest.main()
