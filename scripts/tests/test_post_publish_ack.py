import pathlib
import unittest


class PostPublishAcknowledgementTest(unittest.TestCase):
    def test_publish_and_edit_wait_for_d1_acknowledgement(self):
        source = (pathlib.Path(__file__).resolve().parents[2] / 'app/src/main/java/com/friendbook/social/post/CreatePostActivity.java').read_text()
        save = source[source.index('    private void save('):source.index('    private void fail(')]
        self.assertNotIn('CloudflareStore.put("posts"', save)
        self.assertNotIn('prependFeedPost', save)
        self.assertIn('document(editId).update(m)', save)
        self.assertIn('docRef.set(m).addOnSuccessListener', save)
        self.assertIn('addOnFailureListener(e -> fail(', save)
        self.assertGreater(save.index('Prefs.setPostDraft(this, "")'), save.index('docRef.set(m).addOnSuccessListener'))


if __name__ == '__main__':
    unittest.main()
