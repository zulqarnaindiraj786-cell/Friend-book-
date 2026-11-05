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
        docs = fixture(); docs.update([document('posts/p', {'likes': 1, 'comments': 1, 'reactionsMap': {'u': {'type': 'unknown'}}})])
        _, report = m.plan(docs)
        self.assertIn('unsupported_reaction', report['blockers'])
    def test_legacy_reaction_map_type_and_timestamp_preserved(self):
        docs = fixture(); docs.pop(m.PREFIX + '/posts/p/reactions/u')
        docs.update([document('posts/p', {'likes': 1, 'comments': 1,
                                        'reactionsMap': {'u': {'type': ' Like ', 'createdAt': 456}}})])
        rows, report = m.plan(docs)
        self.assertEqual(report['blockers'], {})
        self.assertEqual(rows['reactions'][0]['type'], 'like')
        self.assertEqual(rows['reactions'][0]['updated_at'], 456)

    def test_missing_historical_timestamp_not_invented(self):
        docs = fixture(); docs.pop(m.PREFIX + '/posts/p/reactions/u')
        rows, report = m.plan(docs)
        self.assertNotIn('missing_historical_reaction_timestamp', report['blockers'])
        self.assertEqual(report['warnings']['unknown_historical_reaction_timestamp_preserved'], 1)
        self.assertEqual(rows['reactions'][0]['updated_at'], 0)
        self.assertEqual(rows['reaction_history'][0]['timestamp_known'], 0)
    def test_legacy_likes_membership_does_not_downgrade_love(self):
        docs = fixture()
        docs.update([document('posts/p/reactions/u', {'type': 'love', 'createdAt': 123}),
                     document('posts/p', {'likes': 1, 'comments': 1}),
                     document('posts/p/likes/u', {'userId': 'u'})])
        rows, report = m.plan(docs)
        self.assertEqual(rows['reactions'][0]['type'], 'love')
        self.assertEqual(report['counts']['legacy_like_records_mapped'], 1)
        self.assertNotIn('unmapped_snapshot_document', report['blockers'])

    def test_reconciliation_is_independent_of_source_document_order(self):
        docs = fixture(); docs.update([document('posts/p/likes/u', {'userId': 'u'})])
        forward, first = m.plan(docs)
        reverse, second = m.plan(dict(reversed(list(docs.items()))))
        self.assertEqual(forward, reverse)
        self.assertEqual(first, second)

    def test_embedded_reaction_priority_matches_existing_android(self):
        docs = fixture(); docs.update([document('posts/p', {'likes': 1, 'comments': 1,
                     'reactionsMap': {'u': 'like'}, 'reactions': {'u': 'love'}})])
        rows, report = m.plan(docs)
        self.assertEqual(report['blockers'], {})
        self.assertEqual(rows['reactions'][0]['type'], 'like')
        self.assertEqual(report['warnings']['existing_android_reaction_precedence_preserved'], 1)

    def test_legacy_likes_actor_mismatch_blocks(self):
        docs = fixture(); docs.update([document('posts/p/likes/u', {'userId': 'different'})])
        _, report = m.plan(docs)
        self.assertEqual(report['blockers']['legacy_like_actor_mismatch'], 1)

    def test_comment_diagnostics_do_not_print_user_identity(self):
        docs = fixture(); docs.update([document('comments/bad', {'authorId': '', 'postId': 'p'})])
        _, report = m.plan(docs)
        self.assertEqual(report['invalid_comment_reasons']['author_identity'], 1)
        self.assertNotIn('bad', m.canonical(report))

    def test_missing_comment_date_is_preserved_as_unknown(self):
        docs = fixture()
        for key in (m.PREFIX + '/comments/c', m.PREFIX + '/posts/p/comments/c'):
            data = json.loads(docs[key]); del data['fields']['createdAt']; docs[key] = m.canonical(data)
        rows, report = m.plan(docs)
        self.assertEqual(report['blockers'], {})
        self.assertEqual(rows['comments'][0]['created_at'], 0)
        self.assertEqual(rows['comment_history'][0]['created_at_known'], 0)
        self.assertEqual(report['warnings']['unknown_comment_creation_time_preserved'], 1)

    def test_integral_double_timestamp_matches_android_without_guessing(self):
        self.assertEqual(m.timestamp(1234.0), 1234)
        with self.assertRaises(ValueError): m.timestamp(1234.5)

    def test_typed_integer_not_coerced_to_float(self):
        self.assertEqual(m.decode({'integerValue': '9007199254740993'}), 9007199254740993)
    def test_timestamp_rejects_missing_timezone(self):
        with self.assertRaises(ValueError): m.timestamp('2026-11-05T00:00:00')

if __name__ == '__main__': unittest.main()
