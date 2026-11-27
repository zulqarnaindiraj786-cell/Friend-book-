"""Static wiring guards; behavioral pager coverage is in PagedListsDemandTest."""
from pathlib import Path
import unittest
JAVA=Path(__file__).resolve().parents[2]/'app/src/main/java/com/friendbook/social'

class RemainingAppQuota(unittest.TestCase):
    def test_discovery_screens_are_user_paged(self):
        for path in ['social/EventsActivity.java','social/PagesActivity.java','settings/MarketplaceActivity.java','post/MemoriesActivity.java','settings/ActivityLogActivity.java']:
            code=(JAVA/path).read_text()
            self.assertIn('PagedLists.attach',code,path)
            self.assertIn('loaded',code,path)
            self.assertIn('onStop()',code,path)
            for limit in ['.limit(100)', '.limit(200)', '.limit(300)', '.limit(80)', '.limit(50)']:
                self.assertNotIn(limit,code,path)
    def test_legacy_lists_cap_and_refresh_explicitly(self):
        code=(JAVA/'core/PagedLists.java').read_text()
        for token in ['Math.min(10,requestedSize)','startCachedFor(', 'refreshOnDemand()', 'row.addView(refresh)', 'Objects.equals(actor,Fb.uid())', 'current[0].close()']:
            self.assertIn(token,code)
    def test_filtered_story_pages_do_not_drain(self):
        code=(JAVA/'story/StoryViewerActivity.java').read_text()
        section=code.split('if(stories.isEmpty()){',1)[1].split('index=Math.min(index,stories.size()-1);show();',1)[0]
        self.assertNotIn('storyPages.next()',section)
        self.assertIn('Tap More stories',section)
        self.assertIn('stopTimer();status("No active stories on this page',code)
    def test_admin_content_initial_tab_does_not_start_two_listeners(self):
        code=(JAVA/'admin/AdminContentActivity.java').read_text()
        self.assertIn('if(started)listen();',code)
        self.assertIn('started=true;',code)
        self.assertIn('started=false;',code)
    def test_story_viewers_load_at_most_ten_and_stop_with_screen(self):
        code=(JAVA/'story/StoryViewerActivity.java').read_text()
        viewers=code.split('private void showViewers()',1)[1].split('private void sendReply()',1)[0]
        for token in ['Math.min(start+10,viewers.size())', 'setOnClickListener(v->next.run())', 'dialog.isShowing()', 'request!=storyRequest', 'Objects.equals(actor,Fb.uid())']:
            self.assertIn(token,viewers)
        self.assertIn('viewersDialog.dismiss()',code.split('protected void onStop()',1)[1])
    def test_creator_eligibility_reads_only_threshold_and_guards_screen(self):
        code=(JAVA/'bluetick/MonetizationActivity.java').read_text()
        for token in ['.limit(REELS_FOR_CREATOR)', 'getCachedOrFetch(false,120_000L)', 'generation!=screenGeneration', 'checkEligibility(followersCount, reelsCount)', 'screenGeneration++']:
            self.assertIn(token,code)
        self.assertNotIn('.limit(50)',code)
    def test_birthdays_ignore_old_generations_and_finish_failures(self):
        code=(JAVA/'friends/BirthdaysActivity.java').read_text()
        for token in ['loadGeneration++','generation!=loadGeneration','UserBatchLookup.load(','!java.util.Objects.equals(actor,Fb.uid())','new java.util.LinkedHashSet<>(friendIds)']:
            self.assertIn(token,code)
