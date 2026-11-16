"""Source architecture guards, not Android/Firestore emulator behavioral tests."""
import unittest
from pathlib import Path
JAVA=Path(__file__).resolve().parents[2]/'app/src/main/java/com/friendbook/social'
class RemainingPaging(unittest.TestCase):
    def test_remaining_screen_controls_and_cache_adapter(self):
        # Groups reads D1 now, so it keeps its own DocPager; the rest still page Firestore.
        for name in ['social/PageDetailActivity.java','social/PokesActivity.java','settings/ArchiveTrashActivity.java','chat/NewChatActivity.java','friends/BirthdaysActivity.java','settings/FeedPreferencesActivity.java']:
            source=(JAVA/name).read_text();self.assertIn('PagedLists.attach',source);self.assertIn('pageControls',source)
        groups=(JAVA/'groups/GroupsActivity.java').read_text()
        self.assertIn('new com.friendbook.social.core.DocPager(groupQuery.build(),20',groups)
        self.assertIn('groupPager.start()',groups)
        source=(JAVA/'core/PagedLists.java').read_text()
        for token in ['new CursorPager(query,size,key','current[0].next()','current[0].close()','controls.removeView(row)','Refresh ']:self.assertIn(token,source)
    def test_action_batches_do_not_truncate_or_use_offline_results(self):
        source=(JAVA/'core/QueryActions.java').read_text()
        for token in ['page.limit(50).get(Source.SERVER)','query.startAfter(cursor)','batch.commit()','Objects.equals(actor,Fb.uid())','done::trySetException','else deletePage(query,last,actor,done)','else updatePage(query,last,actor,patch,total+snap.size(),done)']:self.assertIn(token,source)
    def test_exact_counts_do_not_download_members(self):
        source=(JAVA/'core/CountReads.java').read_text();self.assertIn('query.count().get(AggregateSource.SERVER)',source)
        # Settings counts from D1 now, because a Firestore count with the network off read the
        # local cache and reported zero.
        for name in ['bluetick/BlueTickActivity.java','bluetick/MonetizationActivity.java','main/MainActivity.java']:
            self.assertIn('CountReads.once',(JAVA/name).read_text())
        settings=(JAVA/'settings/SettingsActivity.java').read_text()
        self.assertIn('CloudflareStore.query(', settings)
        self.assertIn('whereEqualTo("authorId", me)', settings)
    def test_tagging_preserves_selected_ids_outside_loaded_page(self):
        source=(JAVA/'core/FriendPicker.java').read_text()
        self.assertIn('new LinkedHashMap<>(initial)',source);self.assertIn('if(on)selected.put(uid,user.displayName);else selected.remove(uid)',source)
        self.assertIn('reg.remove()',source);self.assertIn('request!=generation[0]',source)
    def test_story_links_and_next_ten(self):
        source=(JAVA/'story/StoryViewerActivity.java').read_text()
        for token in ['document(specificStoryId).get()','10,"story-viewer:"','advanceAfterPage=true','storyPages.next()','storyPages.close()','storyPages.forget(s.id)']:self.assertIn(token,source)
    def test_no_migration_executed_by_application(self):
        # No runtime admin sweep or silent removal of old user data accompanies this rollout.
        for p in JAVA.rglob('*.java'):
            self.assertNotIn('migrate_comments_v2',p.read_text())
