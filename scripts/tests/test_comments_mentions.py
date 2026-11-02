import unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
JAVA=ROOT/'app/src/main/java/com/friendbook/social'
class CommentMentionGuards(unittest.TestCase):
    def test_composer_unlocks_after_local_enqueue_not_network_ack(self):
        for file in ['post/PostDetailActivity.java','reels/ReelCommentsActivity.java']:
            source=(JAVA/file).read_text()
            send=source[source.index('Models.Comment local = CommentsEngine.sendComment'):]
            callback=send[:send.index('});')]
            self.assertNotIn('busy = false',callback)
            self.assertIn('busy = false;',send[send.index('});'):send.index('if (local != null)')])
            self.assertNotIn('Ui.hideKeyboard(this)',source)
            self.assertIn('Mentions.capture(input)',source)
    def test_persistence_and_rendering_including_edits(self):
        engine=(JAVA/'post/CommentsEngine.java').read_text()
        self.assertIn('m.put("mentions", c.mentions)',engine)
        self.assertIn('dot.put("commentsMap." + c.id + ".mentions", c.mentions)',engine)
        for file in ['post/FeedAdapter.java','post/CommentsAdapter.java']:
            self.assertIn('Mentions.styled',(JAVA/file).read_text())
            self.assertIn('◆ Creator',(JAVA/file).read_text())
        create=(JAVA/'post/CreatePostActivity.java').read_text()
        self.assertIn('Mentions.restore',create)
        self.assertIn('m.put("mentions", submittingMentions)',create)
    def test_bounded_debounced_identity_selection_and_clicks(self):
        source=(JAVA/'core/Mentions.java').read_text()
        for token in ['postDelayed(pending,250)','limit(10)','request!=generation','isAttachedToWindow','SPAN_EXCLUSIVE_EXCLUSIVE','getSpanStart','ProfileActivity.class','putExtra("uid",uid)','BLUE=0xFF1A73E8','capture(EditText input)','Mentions']:
            self.assertIn(token,source)
        self.assertNotIn('addSnapshotListener',source)
if __name__=='__main__':unittest.main()
