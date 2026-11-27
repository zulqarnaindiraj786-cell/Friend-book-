import json
import unittest
from unittest.mock import Mock

from scripts.loadtest.seed_views_kv import load_namespace, seed, validate_rows


class SeedViewsKvTests(unittest.TestCase):
    def test_loads_only_the_vIEWS_namespace(self):
        config = '[[kv_namespaces]]\nbinding = "VIEWS"\nid = "kv123"\n'
        self.assertEqual(load_namespace(config), 'kv123')
        with self.assertRaises(RuntimeError):
            load_namespace('[[kv_namespaces]]\nbinding = "OTHER"\nid = "kv123"')

    def test_rows_are_strictly_validated_and_bounded(self):
        self.assertEqual(validate_rows([{'resource': 'reels/id', 'views': 3.8}]), {'reels/id': 3})
        for rows in (
            [{'resource': 'users/id', 'views': 1}],
            [{'resource': 'reels/a', 'views': True}],
            [{'resource': 'reels/a', 'views': -1}],
            [{'resource': 'reels/a', 'views': 1}, {'resource': 'reels/a', 'views': 2}],
        ):
            with self.subTest(rows=rows), self.assertRaises(RuntimeError):
                validate_rows(rows)

    def test_existing_immutable_seed_is_never_overwritten_or_d1_read(self):
        request = Mock(return_value=(200, b'{"reels/old":1}'))
        self.assertEqual(seed('token', 'namespace', request), 'preserved')
        request.assert_called_once()

    def test_copies_bounded_aggregate_rows_to_separate_immutable_key(self):
        snapshot = {'reels/a': 7, 'posts/b': 4}
        responses = [
            (404, b''),
            (200, json.dumps({'success': True, 'result': [{'success': True, 'results': [
                {'resource': 'reels/a', 'views': 7}, {'resource': 'posts/b', 'views': 4}
            ], 'meta': {'rows_read': 2}}]}).encode()),
            (200, b'{"success":true}'),
            (200, json.dumps(snapshot).encode()),
        ]
        calls = []
        def request(url, token, method='GET', data=None):
            calls.append((url, method, data))
            return responses.pop(0)
        self.assertEqual(seed('token', 'ns1', request), 'seeded')
        self.assertIn('views%3Alegacy-seed%3Av1', calls[0][0])
        self.assertEqual(calls[1][1], 'POST')
        query = json.loads(calls[1][2])
        self.assertIn('LIMIT 1001', query['sql'])
        self.assertEqual(calls[2][1], 'PUT')
        self.assertEqual(json.loads(calls[2][2]), snapshot)
        self.assertEqual(calls[3][1], 'GET', 'seed is verified before deploy proceeds')

    def test_refuses_if_seed_capacity_would_truncate_history(self):
        too_many = [{'resource': f'reels/{i}', 'views': i} for i in range(1001)]
        responses = [(404, b''), (200, json.dumps({'success': True, 'result': [{'success': True, 'results': too_many}]}).encode())]
        calls = []
        def request(url, token, method='GET', data=None):
            calls.append(method)
            return responses.pop(0)
        with self.assertRaises(RuntimeError):
            seed('token', 'ns1', request)
        self.assertEqual(calls, ['GET', 'POST'], 'refuses before writing a truncated snapshot')


if __name__ == '__main__':
    unittest.main()
