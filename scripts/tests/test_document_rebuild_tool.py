from argparse import Namespace
from contextlib import redirect_stdout, redirect_stderr
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import io
import json
import os
import sys
import threading
import unittest
from unittest.mock import patch

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

    def test_base_url_accepts_the_worker_root_or_the_migration_api_prefix(self):
        self.assertEqual(tool.normalise_base('https://w.example.workers.dev/'),
                         'https://w.example.workers.dev')
        self.assertEqual(tool.normalise_base('https://w.example.workers.dev/v1/admin/migrate'),
                         'https://w.example.workers.dev')
        self.assertEqual(tool.normalise_base('https://w.example.workers.dev/v1/admin/migrate/'),
                         'https://w.example.workers.dev')
        self.assertEqual(tool.normalise_base('https://w.example.workers.dev/other'),
                         'https://w.example.workers.dev/other')

    def test_only_posts_is_the_declared_test_collection(self):
        self.assertEqual(tool.TEST_COLLECTION, 'posts')
        self.assertEqual(len(tool.COLLECTIONS), 10)
        self.assertEqual(tool.COLLECTIONS[0], 'posts')


class _FakeWorker(BaseHTTPRequestHandler):
    """Minimal stand-in for the deployed Worker, so the real HTTP path is exercised."""

    token = 'x' * 48
    calls = []

    def log_message(self, *args):
        pass

    def _send(self, status, body):
        payload = json.dumps(body).encode('utf8')
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def _record(self, body=None):
        type(self).calls.append({
            'path': self.path,
            'method': self.command,
            'authorization': self.headers.get('Authorization'),
            'body': body,
        })

    def do_GET(self):
        self._record()
        if self.path == '/v1/admin/migrate/documents/plan':
            return self._send(200, {
                'ok': True, 'testCollection': 'posts',
                'gate': {'collection': 'posts', 'passed': False, 'sourceRows': 2, 'documentRows': 0},
                'collections': [{'collection': 'posts', 'sourceRows': 2, 'documentRows': 0, 'pending': 2}],
                'totals': {'sourceRows': 2, 'documentRows': 0, 'pending': 2},
            })
        if self.path.startswith('/v1/admin/migrate/documents/verify'):
            return self._send(200, {'mismatchCount': 0, 'checked': 1})
        return self._send(404, {'error': 'Not found'})

    def do_POST(self):
        length = int(self.headers.get('Content-Length') or 0)
        body = json.loads(self.rfile.read(length) or b'{}')
        self._record(body)
        if self.path != '/v1/admin/migrate/documents/rebuild':
            return self._send(404, {'error': 'Not found'})
        if body.get('collection') != 'posts':
            return self._send(409, {'error': 'gate', 'gate': {'passed': False}})
        return self._send(200, {
            'collection': 'posts', 'dryRun': not body.get('apply'), 'scanned': 2,
            'converted': 2, 'written': 0, 'skippedExisting': 0, 'oversized': 0,
            'invalid': 0, 'nextCursor': None,
        })


class HttpSmokeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(('127.0.0.1', 0), _FakeWorker)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = f'http://127.0.0.1:{cls.server.server_port}'

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def run_cli(self, argv, base=None):
        out = io.StringIO()
        err = io.StringIO()
        environment = {'CF_WORKERS_API_BASE': base or self.base,
                       'MIGRATION_ADMIN_TOKEN': _FakeWorker.token}
        _FakeWorker.calls = []
        with patch.dict(os.environ, environment, clear=False):
            with redirect_stdout(out), redirect_stderr(err):
                code = tool.main(argv)
        return code, out.getvalue(), err.getvalue()

    def test_plan_reaches_the_migration_route_with_the_bearer_token(self):
        code, out, _ = self.run_cli(['plan'])
        self.assertEqual(code, 0)
        self.assertEqual(json.loads(out)['totals']['pending'], 2)
        call = _FakeWorker.calls[0]
        self.assertEqual(call['path'], '/v1/admin/migrate/documents/plan')
        self.assertEqual(call['authorization'], f'Bearer {_FakeWorker.token}')

    def test_posts_dry_run_sends_apply_false_and_writes_nothing(self):
        code, out, _ = self.run_cli(['run', 'posts'])
        self.assertEqual(code, 0)
        rebuild = [call for call in _FakeWorker.calls if call['method'] == 'POST']
        self.assertEqual(len(rebuild), 1)
        self.assertEqual(rebuild[0]['body']['collection'], 'posts')
        self.assertIs(rebuild[0]['body']['apply'], False)
        # One compact JSON line per page, then the indented final summary.
        lines = out.strip().splitlines()
        start = max(index for index, line in enumerate(lines) if line == '{')
        summary = json.loads('\n'.join(lines[start:]))
        self.assertEqual(summary['results'][0]['written'], 0)
        self.assertTrue(summary['results'][0]['dryRun'])

    def test_gate_block_exits_with_code_2(self):
        code, _, err = self.run_cli(['run', 'users', '--apply'])
        self.assertEqual(code, 2)
        self.assertIn('gate', err)

    def test_base_may_already_include_the_migration_prefix(self):
        code, _, _ = self.run_cli(['plan'], base=self.base + '/v1/admin/migrate')
        self.assertEqual(code, 0)
        self.assertEqual(_FakeWorker.calls[0]['path'], '/v1/admin/migrate/documents/plan')

    def test_short_token_is_refused_before_any_request(self):
        out = io.StringIO()
        err = io.StringIO()
        _FakeWorker.calls = []
        with patch.dict(os.environ, {'CF_WORKERS_API_BASE': self.base,
                                     'MIGRATION_ADMIN_TOKEN': 'short'}, clear=False):
            with redirect_stdout(out), redirect_stderr(err):
                code = tool.main(['plan'])
        self.assertEqual(code, 1)
        self.assertIn('32 characters', err.getvalue())
        self.assertEqual(_FakeWorker.calls, [])


if __name__ == '__main__':
    unittest.main()
