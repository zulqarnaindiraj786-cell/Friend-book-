import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
JAVA = ROOT / 'app/src/main/java/com/friendbook/social'


class ProfileFeedConsistencyTest(unittest.TestCase):
    def test_profile_patch_uses_one_document_write_and_returns_task(self):
        source = (JAVA / 'core/Fb.java').read_text()
        patch = source[source.index('    public static Task<Void> patchMe('):source.index('    public static Map<String, Object> map(')]
        self.assertNotIn('CloudflareStore.put', patch)
        self.assertIn('return db().collection("users").document(uid).set(fields, SetOptions.merge())', patch)

    def test_photo_updates_wait_for_profile_ack(self):
        for path in ('profile/ProfileActivity.java', 'profile/EditProfileActivity.java'):
            source = (JAVA / path).read_text()
            self.assertIn('Fb.patchMe(', source)
            self.assertIn("Couldn't save photo. Please try again.", source)

    def test_profile_pages_and_bootstrap_use_same_newest_first_query(self):
        profile = (JAVA / 'profile/ProfileActivity.java').read_text()
        posts = profile[profile.index('    private void startPostsWatcher()'):profile.index('    private void applyPostsFilter()')]
        self.assertIn('.orderBy("createdAt", com.friendbook.social.d1.documents.Query.Direction.DESCENDING)', posts)
        bootstrap = (JAVA / 'd1/documents/D1DocumentStore.java').read_text()
        self.assertIn('.orderBy("createdAt", Query.Direction.DESCENDING).limit(7)', bootstrap)

    def test_profile_count_starts_without_waiting_for_page_callback(self):
        profile = (JAVA / 'profile/ProfileActivity.java').read_text()
        self.assertIn('postPager.startCachedFor(120_000L);\n        refreshPostCount(generation);', profile)
        self.assertIn('postPager.forget(id)', profile)

    def test_staff_delete_uses_moderation_api_not_owner_only_delete(self):
        adapter = (JAVA / 'post/FeedAdapter.java').read_text()
        for role in ('admin', 'moderator'):
            self.assertIn(f'"Delete post ({role})")) deletePost(p, true)', adapter)
        self.assertIn('moderation ? Fb.db().moderate("delete_content", "posts", p.id)', adapter)
        self.assertIn(': Fb.db().collection("posts").document(p.id).delete()', adapter)

    def test_missing_feed_author_lookup_is_account_scoped_and_deduped(self):
        source = (JAVA / 'post/FeedAdapter.java').read_text()
        self.assertIn('requestedAuthors.add(String.valueOf(actor) + ":" + p.authorId)', source)
        self.assertIn('java.util.Objects.equals(actor, Fb.uid())', source)
        self.assertIn('FieldPath.documentId(),chunk)', source)
        self.assertNotIn('Fb.getUserFresh(p.authorId)', source)
        self.assertIn('String dpUrl = photo;', source)


if __name__ == '__main__':
    unittest.main()
