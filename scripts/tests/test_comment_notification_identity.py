from pathlib import Path
import unittest
JAVA=Path(__file__).resolve().parents[2]/'app/src/main/java/com/friendbook/social'

class CommentNotificationIdentity(unittest.TestCase):
    def test_saved_id_reaches_all_comment_producers(self):
        engine=(JAVA/'post/CommentsEngine.java').read_text()
        self.assertEqual(engine.count('cb.onSaved(newId)'),2)
        self.assertEqual(engine.count('Notify.sendForComment('),2)
        self.assertIn('default void onSaved(String commentId) { onComplete(true,null); }',engine)
        for file in ['post/PostDetailActivity.java','reels/ReelCommentsActivity.java']:
            source=(JAVA/file).read_text()
            self.assertIn('void onSaved(String commentId)',source)
            self.assertEqual(source.count('Notify.sendForComment('),2)

    def test_stable_events_keep_atomic_create_and_success_only_push(self):
        source=(JAVA/'core/Notify.java').read_text()
        self.assertIn('NotificationEventKey.forComment(Fb.uid(),toUid,type,collection+"/"+postId,commentId)',source)
        self.assertIn('ref.create(n).addOnSuccessListener',source)
        self.assertIn('D1DocumentStoreException.Code.ALREADY_EXISTS',source)
        self.assertIn('} else Fb.db().collection("notifications").add(n)',source)
