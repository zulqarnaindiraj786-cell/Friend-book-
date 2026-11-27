"""Prevent UI repaint events from becoming repeated engagement network reads."""
from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[2]
JAVA=ROOT/'app/src/main/java/com/friendbook/social'

class ReelRenderNoFetch(unittest.TestCase):
    def test_reel_creator_badges_use_page_batch_not_per_holder_reads(self):
        source=(JAVA/'reels/ReelsFragment.java').read_text()
        adapter=source.split('static class ReelAdapter',1)[1].split('private static boolean isPrefixMatch',1)[0]
        holder=source.split('private void renderCreatorAndCaption()',1)[1].split('boolean hasCap',1)[0]
        self.assertIn('UserBatchLookup.load(new ArrayList<>(ids)',adapter)
        self.assertIn('TickCache.attachCached(tick, reel.authorId)',holder)
        self.assertNotIn('TickCache.attach(tick, reel.authorId)',source)

    def test_counter_render_only_hydrates_cache(self):
        source=(JAVA/'reels/ReelsFragment.java').read_text()
        render=source.split('private void renderCounts() {',1)[1].split('private void ',1)[0]
        self.assertIn('D1Reactions.hydrateReel(reel)',render)
        self.assertNotIn('D1Reactions.reel(',render)
        self.assertNotIn('refreshEngagement(',render)
        self.assertNotIn('D1Client.request',render)

    def test_explicit_bind_and_resume_metadata_refresh_still_load_engagement(self):
        source=(JAVA/'reels/ReelsFragment.java').read_text()
        for start,end in [('void updateMetaInPlace(', 'private void renderCreatorAndCaption'),
                          ('void bind(Models.Reel r,', 'if (sameVideo)')]:
            section=source.split(start,1)[1].split(end,1)[0]
            self.assertIn('refreshEngagement();',section)
        refresh=source.split('private void refreshEngagement() {',1)[1].split('private void renderCounts()',1)[0]
        self.assertIn('local_uploading_',refresh)
        self.assertIn('if (reel == current) renderCounts();',refresh)
        self.assertIn('D1Reactions.reel(',refresh)

    def test_cache_only_hydration_cannot_issue_requests(self):
        source=(JAVA/'post/D1Reactions.java').read_text()
        hydrate=source.split('public static void hydrateReel(',1)[1].split('public static void reel(',1)[0]
        self.assertNotIn('fetch(',hydrate)
        self.assertNotIn('D1Client.request',hydrate)
        request=source.split('public static void reel(',1)[1].split('public static void apply',1)[0]
        self.assertIn('hydrateReel(reel)',request)
        self.assertIn('fetch(',request)


    def test_only_visible_selected_reel_can_request_engagement(self):
        source=(JAVA/'reels/ReelsFragment.java').read_text()
        refresh=source.split('private void refreshEngagement() {',1)[1].split('private void renderCounts()',1)[0]
        self.assertIn('!visible.getAsBoolean()',refresh)
        self.assertIn('getBindingAdapterPosition() != playingPos',refresh)
        self.assertIn('playingPos < 0',refresh)
        self.assertLess(refresh.index('playingPos < 0'),refresh.index('D1Reactions.reel('))
        play=source.split('void playOnly(ViewPager2 pager, int pos) {',1)[1].split('@NonNull',1)[0]
        self.assertIn('((VH)selected).refreshEngagement();',play)
        attach=source.split('void onViewAttachedToWindow(',1)[1].split('void onViewDetachedFromWindow(',1)[0]
        self.assertIn('h.getBindingAdapterPosition()==playingPos',attach)
        self.assertIn('((VH)h).refreshEngagement();',attach)
