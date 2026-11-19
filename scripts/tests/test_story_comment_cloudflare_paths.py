import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

class CloudflareScreenRegression(unittest.TestCase):
    def test_story_interactions_use_dedicated_acknowledged_endpoint(self):
        story = (ROOT / 'app/src/main/java/com/friendbook/social/story/StoryViewerActivity.java').read_text()
        self.assertIn('storyEngagement(s.id, "view", null)', story)
        self.assertIn('storyEngagement(s.id, "react", emoji).addOnSuccessListener', story)
        self.assertIn("Couldn't send reaction. Try again.", story)
        self.assertNotIn('.update("reactions." + Fb.uid(), emoji)', story)
        self.assertIn('addOnCompleteListener(task ->', story)

    def test_comment_refresh_preserves_pending_and_rejects_stale_pages(self):
        source = (ROOT / 'app/src/main/java/com/friendbook/social/post/CommentsEngine.java').read_text()
        self.assertIn('requestedAtRevision!=revision', source)
        self.assertIn('!pendingD1.contains(entry.getKey())', source)
        self.assertIn('h.pendingD1.add(local.id)', source)
        self.assertIn('h.pendingD1.remove(removed)', source)
        self.assertIn('h.pendingD1.remove(c.id)', source)

if __name__ == '__main__':
    unittest.main()
