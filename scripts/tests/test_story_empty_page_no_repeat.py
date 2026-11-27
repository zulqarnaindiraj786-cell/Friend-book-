from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class StoryEmptyPageNoRepeat(unittest.TestCase):
    def test_successful_empty_page_survives_tab_return_without_refetch(self):
        source = (ROOT / 'app/src/main/java/com/friendbook/social/main/HomeFragment.java').read_text()
        self.assertIn('private boolean storyPageCompleted;', source)
        self.assertIn('&& !storyPageCompleted)storyQuery=null;', source)
        self.assertIn('loadingStories=false;storyPageCompleted=true;', source)

    def test_new_query_window_resets_completion(self):
        source = (ROOT / 'app/src/main/java/com/friendbook/social/main/HomeFragment.java').read_text()
        self.assertIn('storyCursor=null; storyPageCompleted=false;', source)
        self.assertIn('storiesActor=null;moreStories=true;storyPageCompleted=false;', source)


if __name__ == '__main__':
    unittest.main()
