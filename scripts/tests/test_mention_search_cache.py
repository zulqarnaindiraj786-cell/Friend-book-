from pathlib import Path
import unittest
JAVA=Path(__file__).resolve().parents[2]/'app/src/main/java/com/friendbook/social'
class MentionSearchCache(unittest.TestCase):
    def test_search_reuses_short_lived_account_scoped_exact_queries(self):
        s=(JAVA/'core/Mentions.java').read_text()
        self.assertIn('SEARCH_CACHE_MS=5*60*1000L',s)
        self.assertIn('limit(10).getCachedOrFetch(false,SEARCH_CACHE_MS)',s)
        self.assertIn('query.toLowerCase(Locale.ROOT),query.toUpperCase(Locale.ROOT)',s)
        self.assertIn('postDelayed(pending,250)',s)
        self.assertIn('final String actor=Fb.uid()',s)
        self.assertIn('!actor.equals(Fb.uid())',s)
    def test_queued_search_requires_visible_focused_input(self):
        s=(JAVA/'core/Mentions.java').read_text()
        pending=s.split('pending=() -> {',1)[1].split('for(String term:terms)',1)[0]
        for token in ['!input.isAttachedToWindow()','!input.hasWindowFocus()','!input.hasFocus()']:
            self.assertIn(token,pending)
        self.assertIn('handler.removeCallbacksAndMessages(null); popup.dismiss()',s)
    def test_existing_list_reuse_stays_unlimited_but_search_window_is_explicit(self):
        s=(JAVA/'d1/documents/Query.java').read_text()
        self.assertIn('return getCachedOrFetch(force,Long.MAX_VALUE)',s)
        self.assertIn('!hasFreshLocalPage(maxAgeMs)',s)
