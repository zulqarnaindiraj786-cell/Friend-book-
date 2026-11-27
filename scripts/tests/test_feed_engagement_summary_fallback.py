from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class FeedEngagementSummaryFallback(unittest.TestCase):
    def test_embedded_comment_preview_supplies_count_when_denormalized_count_missing(self):
        source = (ROOT / 'app/src/main/java/com/friendbook/social/post/FeedAdapter.java').read_text()
        render = source.split('private void renderReactionBar(', 1)[1].split('// 3. Three Rounded Pill Capsule Buttons', 1)[0]
        self.assertIn('Math.max(p.comments, p.inlineComments == null ? 0 : p.inlineComments.size())', render)
        self.assertIn('visibleCommentCount > 0', render)
        self.assertIn('int embeddedReactionCount = p.reactionsMap == null ? 0 : p.reactionsMap.size()', render)
        self.assertIn('Math.max(Math.max(0, total), embeddedReactionCount)', render)


if __name__ == '__main__':
    unittest.main()
