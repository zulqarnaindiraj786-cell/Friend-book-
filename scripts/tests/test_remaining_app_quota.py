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
    def test_birthdays_ignore_old_generations_and_finish_failures(self):
        code=(JAVA/'friends/BirthdaysActivity.java').read_text()
        for token in ['loadGeneration++','generation!=loadGeneration','addOnCompleteListener','!java.util.Objects.equals(actor,Fb.uid())','new java.util.LinkedHashSet<>(friendIds)']:
            self.assertIn(token,code)
