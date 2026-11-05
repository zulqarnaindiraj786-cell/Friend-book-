import json
from pathlib import Path
import sys
import unittest
sys.path.insert(0, str(Path(__file__).parents[1] / 'admin'))
import plan_interaction_import as m


def typed(value):
    if value is None: return {'nullValue': None}
    if isinstance(value, bool): return {'booleanValue': value}
    if isinstance(value, int): return {'integerValue': str(value)}
    if isinstance(value, str): return {'stringValue': value}
    if isinstance(value, list): return {'arrayValue': {'values': [typed(v) for v in value]}}
    return {'mapValue': {'fields': {k: typed(v) for k, v in value.items()}}}


def document(path, data):
    name = m.PREFIX + '/' + path
    return name, m.canonical({'name': name, 'fields': {k: typed(v) for k, v in data.items()}})


def fixture():
    comment = {'authorId': 'u', 'content': 'hello', 'postId': 'p', 'createdAt': 123,
               'imageUrl': 'https://res.cloudinary.com/demo/photo.jpg'}
    return dict([document('posts/p', {'likes': 1, 'comments': 1, 'reactionsMap': {'u': 'like'}}),
                 document('posts/p/reactions/u', {'type': 'like', 'createdAt': 123, 'userId': 'u'}),
                 document('posts/p/comments/c', comment), document('comments/c', comment)])


class PlanTests(unittest.TestCase):
    def test_mirrors_deduped_and_media_preserved(self):
        rows, report = m.plan(fixture())
        self.assertEqual(report['blockers'], {})
        self.assertEqual(len(rows['comments']), 1)
        self.assertEqual(rows['comments'][0]['media_url'], 'https://res.cloudinary.com/demo/photo.jpg')
        self.assertEqual(rows['reactions'][0]['updated_at'], 123)
        self.assertFalse(report['safe_to_cutover'])
        self.assertEqual(rows['resources'][0]['state'], 'staging')
    def test_conflicting_mirror_is_not_silently_preferred(self):
        docs = fixture(); key, value = document('comments/c', {'authorId': 'u', 'content': 'changed', 'postId': 'p', 'createdAt': 123})
        docs[key] = value
        _, report = m.plan(docs)
        self.assertEqual(report['blockers']['conflicting_comment_representations'], 1)
    def test_orphan_reply_blocks_import(self):
        docs = fixture()
        for path in ('posts/p/comments/c', 'comments/c'):
            key = m.PREFIX + '/' + path; data = json.loads(docs[key]); data['fields']['parentId'] = typed('missing'); docs[key] = m.canonical(data)
        _, report = m.plan(docs)
        self.assertEqual(report['blockers']['missing_reply_parent'], 1)
    def test_unknown_nested_data_not_dropped(self):
        docs = fixture(); docs.update([document('posts/p/something/unknown', {'value': 1})])
        _, report = m.plan(docs)
        self.assertEqual(report['blockers']['unmapped_snapshot_document'], 1)
    def test_counter_mismatch_is_visible(self):
        docs = fixture(); docs.update([document('posts/p', {'likes': 99, 'comments': 1})])
        _, report = m.plan(docs)
        self.assertEqual(report['blockers']['resource_reaction_counter_mismatch'], 1)
    def test_unknown_reaction_value_fails_closed(self):
        docs = fixture(); docs.update([document('posts/p', {'likes': 1, 'comments': 1, 'reactionsMap': {'u': {'type': 'like'}}})])
        _, report = m.plan(docs)
        self.assertIn('unsupported_reaction', report['blockers'])
    def test_missing_historical_timestamp_not_invented(self):
        docs = fixture(); docs.pop(m.PREFIX + '/posts/p/reactions/u')
        rows, report = m.plan(docs)
        self.assertEqual(report['blockers']['missing_historical_reaction_timestamp'], 1)
        self.assertIsNone(rows['reactions'][0]['updated_at'])
    def test_typed_integer_not_coerced_to_float(self):
        self.assertEqual(m.decode({'integerValue': '9007199254740993'}), 9007199254740993)
    def test_timestamp_rejects_missing_timezone(self):
        with self.assertRaises(ValueError): m.timestamp('2026-11-05T00:00:00')

if __name__ == '__main__': unittest.main()
