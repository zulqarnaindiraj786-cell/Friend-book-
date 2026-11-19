import pathlib
import unittest

JAVA = pathlib.Path(__file__).resolve().parents[2] / 'app/src/main/java/com/friendbook/social'


class CloudflareFailureSignalsTest(unittest.TestCase):
    def test_transport_does_not_convert_bad_query_or_count_to_zero(self):
        s = (JAVA / 'd1/documents/D1DocumentTransport.java').read_text()
        self.assertIn('Invalid query response', s)
        self.assertIn('Invalid count response', s)
        self.assertIn('Invalid document response', s)
        self.assertNotIn('return data.optInt("count", 0)', s)

    def test_follow_result_can_be_observed(self):
        s = (JAVA / 'core/Notify.java').read_text()
        self.assertIn('Task<Void> follow(', s)
        self.assertIn('return batch.commit().addOnSuccessListener', s)
        for path in ['profile/ProfileActivity.java', 'post/FeedAdapter.java', 'reels/ReelsFragment.java', 'live/LiveActivity.java']:
            self.assertIn("Couldn't", (JAVA / path).read_text())

    def test_admin_report_is_not_dismissed_when_content_action_fails(self):
        s = (JAVA / 'admin/AdminReportsActivity.java').read_text()
        self.assertIn('Action failed; report kept in queue', s)
        self.assertIn('Ban failed; report kept in queue', s)
        self.assertIn("Couldn't load reports", s)


if __name__ == '__main__':
    unittest.main()
