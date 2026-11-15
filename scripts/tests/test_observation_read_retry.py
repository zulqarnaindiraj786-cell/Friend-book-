import sys
from pathlib import Path
import unittest
from unittest.mock import Mock
sys.path.insert(0, str(Path(__file__).parents[1] / 'admin'))
from observe_interaction_changes import ReadOnlySourceRetry, BackupError, FIRESTORE, PREFIX


class ReadRetryTests(unittest.TestCase):
    def test_retry_uses_identical_snapshot_read_time_and_is_bounded(self):
        source, sleep = Mock(), Mock()
        source.post.side_effect = [TimeoutError(), {'ok': True}]
        reader = ReadOnlySourceRetry(source, (TimeoutError,), sleep)
        url = FIRESTORE + PREFIX + ':listCollectionIds'
        args = {'json': {'readTime': 'sealed-time', 'pageToken': 'unchanged'}, 'timeout': 90}
        self.assertEqual(reader.post(url, **args), {'ok': True})
        self.assertEqual(source.post.call_count, 2)
        self.assertEqual(source.post.call_args_list[0], source.post.call_args_list[1])
        sleep.assert_called_once_with(1)

    def test_exhausted_source_read_stops_after_three_attempts(self):
        source, sleep = Mock(), Mock()
        source.get.side_effect = TimeoutError()
        reader = ReadOnlySourceRetry(source, (TimeoutError,), sleep)
        with self.assertRaises(TimeoutError): reader.get(FIRESTORE + PREFIX + '/posts', timeout=90)
        self.assertEqual(source.get.call_count, 3)
        self.assertEqual([call.args[0] for call in sleep.call_args_list], [1, 2])

    def test_never_retries_or_sends_mutations_or_foreign_destinations(self):
        source = Mock()
        reader = ReadOnlySourceRetry(source, (TimeoutError,), Mock())
        for url in [FIRESTORE + PREFIX + ':commit', 'https://example.invalid/private']:
            with self.assertRaises(BackupError): reader.post(url, json={})
        self.assertEqual(source.mock_calls, [])
