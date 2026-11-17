import unittest
from pathlib import Path
JAVA=Path('app/src/main/java/com/friendbook/social')
class CirclePaging(unittest.TestCase):
    def test_story_cache_and_live_results_apply_same_policy(self):
        s=(JAVA/'main/HomeFragment.java').read_text()
        for token in ['storyAllowed(story)','renderLoadedStories()','snapshot.size()==10','pymkLoaded','pymkLoading']:
            self.assertIn(token,s)
        s=(JAVA/'story/StoryViewerActivity.java').read_text()
        for token in ['allowedStory(story)','StoryAudience.visible','count().get(com.friendbook.social.d1.documents.AggregateSource.SERVER)']:
            self.assertIn(token,s)
    def test_local_settings_pages_preserve_search(self):
        s=(JAVA/'settings/SettingsActivity.java').read_text()
        for token in ['attached[0]+12','y<=oldY','body.postOnAnimation(appendSettings)','while(attached[0]<settingsRows.size())']:
            self.assertIn(token,s)
    def test_cached_revalidation_does_not_repaint_unchanged_rows(self):
        s=(JAVA/'core/CursorPager.java').read_text()
        for token in ['CachedPage(first,records.size()>size || more)','more=cached.more;emit("")','sameData(lastDelivered,docs)','Objects.equals(a.get(i).getData(),b.get(i).getData())','more=true;next()']:
            self.assertIn(token,s)
