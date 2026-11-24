from pathlib import Path
import unittest
JAVA=Path(__file__).resolve().parents[2]/'app/src/main/java/com/friendbook/social'
class ReelSoundDemand(unittest.TestCase):
    def test_sound_sheet_uses_owned_paged_reads_and_disposes_preview(self):
        code=(JAVA/'reels/TikTokReelStudioHelper.java').read_text().split('public static void showSoundsBottomSheet',1)[1].split('private static void addReelToSoundMap',1)[0]
        for token in ['PagedLists.attach','10,"reel-sounds"','Search and favourites cover loaded sounds','!d.isShowing()','!actor.equals(Fb.uid())','onActivityStopped','soundPages[0].remove()','stopPreview.run()','unregisterActivityLifecycleCallbacks']:
            self.assertIn(token,code)
        self.assertNotIn('.limit(25)',code)
