from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[2]

class CommentAuthorLookupBudget(unittest.TestCase):
    def test_comment_bind_uses_gate_disk_cache_and_targeted_repaint(self):
        source=(ROOT/'app/src/main/java/com/friendbook/social/post/CommentsAdapter.java').read_text()
        lookup=source.split('if (liveUser == null && c.authorId != null',1)[1].split('Ui.avatar(activity, h.av',1)[0]
        self.assertIn('authorLookups.begin(actor, authorId',lookup)
        self.assertIn('Source.CACHE',lookup)
        self.assertIn('return author.get();',lookup)
        self.assertIn('authorLookups.finish(actor, authorId, ticket, true',lookup)
        self.assertIn('authorLookups.finish(actor, authorId, ticket, false',lookup)
        self.assertIn('notifyItemChanged(i + headerOffset())',lookup)
        self.assertNotIn('notifyDataSetChanged()',lookup)
        self.assertIn('java.util.Objects.equals(actor, Fb.uid())',lookup)
