import unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2] / 'app/src/main/java/com/friendbook/social'

class MediaPublishRegression(unittest.TestCase):
    def test_reel_publish_finishes_on_error_and_reconciles_same_id(self):
        src=(ROOT/'reels/ReelUploadManager.java').read_text()
        self.assertNotIn('CloudflareStore.put("reels"',src)
        self.assertIn('ref.set(doc).addOnSuccessListener',src)
        self.assertIn('ref.get(com.friendbook.social.d1.documents.Source.SERVER)',src)
        self.assertIn('Publish could not be confirmed',src)
        self.assertIn('final String actor=Fb.uid()',src)
        self.assertIn('actor.equals(snapshot.getString("authorId"))',src)

    def test_story_publish_checks_account_and_confirmation(self):
        src=(ROOT/'story/AddStoryActivity.java').read_text()
        self.assertIn('publishStory(actor, m',src)
        self.assertIn('ref.set(data).addOnSuccessListener',src)
        self.assertIn('ref.get(com.friendbook.social.d1.documents.Source.SERVER)',src)
        self.assertIn('if (!actor.equals(Fb.uid())) { failAccountChanged(); return; }',src)
        self.assertNotIn('collection("stories").add(m)',src)

    def test_opportunistic_reel_list_does_not_replay_stale_acl_cache(self):
        src=(ROOT/'core/CloudflareStore.java').read_text()
        self.assertNotIn('LIST_CACHE',src)
        self.assertIn('if (actor.equals(Fb.uid())) callback.accept(docs)',src)

if __name__=='__main__': unittest.main()
