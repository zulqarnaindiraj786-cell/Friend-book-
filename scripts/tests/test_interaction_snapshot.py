import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('snapshot', Path(__file__).parents[1] / 'admin/copy_interaction_snapshot.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class Response:
    status_code = 200
    def __init__(self, data): self.data = data
    def json(self): return self.data


class Source:
    def __init__(self):
        self.calls = []
        self.parent = m.PREFIX + '/posts/missing'
        self.document = {'name': self.parent + '/comments/comment', 'createTime': '2026-11-04T00:00:00Z',
                         'updateTime': '2026-11-04T00:00:00Z',
                         'fields': {'text': {'stringValue': 'hello'}, 'media': {'stringValue': 'https://res.cloudinary.com/example/video.mp4'}}}
    def post(self, url, json, timeout):
        self.calls.append((url, json))
        if url.endswith(':runAggregationQuery'):
            count = int(url.startswith(m.FIRESTORE + self.parent + ':'))
            return Response([{'readTime': '2026-11-04T00:00:00Z', 'result': {'aggregateFields': {'total': {'integerValue': str(count)}}}}])
        assert url.endswith(':listCollectionIds')
        return Response({'collectionIds': ['comments'] if url.startswith(m.FIRESTORE + self.parent + ':') else []})
    def get(self, url, params, timeout):
        self.calls.append((url, params))
        if url == m.FIRESTORE + m.PREFIX + '/posts':
            return Response({'documents': [{'name': self.parent}]})
        return Response({'documents': [self.document] if url.endswith('/posts/missing/comments') else []})


class Destination:
    def __init__(self): self.rows = {}; self.sql = []; self.corrupt = False
    def post(self, url, json, timeout):
        assert url == m.D1
        sql, params = json['sql'], json['params']
        self.sql.append(sql)
        rows = []
        if sql.startswith('INSERT'):
            batch, path, text, sha = params
            if (batch, path) in self.rows: raise RuntimeError('duplicate')
            self.rows[batch, path] = {'path': path, 'document_json': text, 'sha256': sha}
        if sql.startswith('SELECT'):
            rows = [dict(r) for (b, p), r in sorted(self.rows.items()) if b == params[0] and p > params[1]][:100]
            if self.corrupt and rows: rows[0]['sha256'] = 'wrong'
        return Response({'success': True, 'result': [{'success': True, 'results': rows}]})


class SnapshotTests(unittest.TestCase):
    def test_orphan_parent_nested_comment_and_media_preserved(self):
        source = Source(); snapshot = m.Snapshot(source); docs = snapshot.export()
        self.assertEqual(docs, {source.document['name']: m.canonical(source.document)})
        self.assertNotIn(source.parent, docs)
        for _, body in source.calls[1:]: self.assertEqual(body['readTime'], snapshot.time)
    def test_wrong_authoritative_count_fails(self):
        source = Source(); original = source.post
        def wrong(url, json, timeout):
            response = original(url, json, timeout)
            if url.endswith(':runAggregationQuery'): response.data[0]['result']['aggregateFields']['total']['integerValue'] = '99'
            return response
        source.post = wrong
        with self.assertRaises(RuntimeError): m.Snapshot(source).export()
    def test_copy_verifies_content_and_never_modifies_existing_batch(self):
        destination = Destination(); docs = {m.PREFIX + '/posts/p': '{"fields":{}}'}
        m.copy_and_verify(destination, 'batch', docs)
        m.copy_and_verify(destination, 'other', docs)
        self.assertEqual(len(destination.rows), 2)
        self.assertTrue(all(s.startswith(('CREATE TABLE IF NOT EXISTS', 'INSERT INTO', 'SELECT')) for s in destination.sql))
        with self.assertRaises(RuntimeError): m.copy_and_verify(destination, 'batch', docs)
        self.assertEqual(len(destination.rows), 2)
    def test_corrupt_hash_refuses_verification(self):
        destination = Destination(); destination.corrupt = True
        with self.assertRaises(RuntimeError): m.copy_and_verify(destination, 'batch', {m.PREFIX + '/posts/p': '{}'})
    def test_large_verification_pages(self):
        docs = {m.PREFIX + '/posts/' + str(i).zfill(4): '{}' for i in range(201)}
        destination = Destination(); m.copy_and_verify(destination, 'batch', docs)
        self.assertEqual(len(destination.rows), 201)
    def test_remote_error_body_not_exposed(self):
        response = Response({'private': 'secret'}); response.status_code = 403
        with self.assertRaises(RuntimeError) as error: m.checked(response)
        self.assertNotIn('secret', str(error.exception))

if __name__ == '__main__': unittest.main()
