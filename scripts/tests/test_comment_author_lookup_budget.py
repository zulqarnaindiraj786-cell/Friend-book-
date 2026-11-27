from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[2]

class CommentAuthorLookupBudget(unittest.TestCase):
    def test_comment_authors_hydrate_in_bounded_batches_not_per_row(self):
        source=(ROOT/'app/src/main/java/com/friendbook/social/post/CommentsAdapter.java').read_text()
        lookup=source.split('private void hydrateCommentAuthors()',1)[1].split('public void setAll(',1)[0]
        self.assertIn('UserBatchLookup.load(new ArrayList<>(requested)',lookup)
        self.assertIn('TickCache.put(entry.getKey(),user.tickLevel)',lookup)
        self.assertIn('notifyItemChanged(i+headerOffset())',lookup)
        self.assertIn('Objects.equals(actor,Fb.uid())',lookup)
        self.assertIn('TickCache.attachCached(h.tick, c.authorId)',source)
        self.assertNotIn('author.get(',source)
        self.assertNotIn('TickCache.attach(h.tick, c.authorId)',source)
