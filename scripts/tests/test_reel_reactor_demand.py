import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
JAVA = ROOT / "app/src/main/java/com/friendbook/social/reels/ReelsFragment.java"


class ReelReactorDemandTest(unittest.TestCase):
    def test_legacy_reactor_hydration_is_only_requested_when_sheet_is_opened(self):
        source = JAVA.read_text()
        self.assertEqual(source.count("hydrateLegacyPostReelReactionsIfNeeded("), 2)
        bind = source.split("void bind(Models.Reel r, boolean autoPlayNow)", 1)[1].split(
            "private void", 1
        )[0]
        self.assertNotIn("hydrateLegacyPostReelReactionsIfNeeded(", bind)
        click = source.split("likeCount.setOnClickListener", 1)[1].split("View.OnClickListener", 1)[0]
        self.assertIn("hydrateLegacyPostReelReactionsIfNeeded(target", click)
        helper = source.split("private void hydrateLegacyPostReelReactionsIfNeeded", 1)[1].split(
            "// Only explicit bind/resume/authoritative metadata refresh requests", 1
        )[0]
        self.assertIn('.collection("reactions")', helper)
        self.assertIn(".limit(200)", helper)
        self.assertIn("LegacyReactionReadGate.begin", helper)
        self.assertIn("readActor.equals(Fb.uid())", helper)
        self.assertIn("target.reactions != null && !target.reactions.isEmpty()", helper)


if __name__ == "__main__":
    unittest.main()
