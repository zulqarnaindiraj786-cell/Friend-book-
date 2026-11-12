"""Source contract guards: Reel stickers are drawn 2026 vector glyphs, never words or emojis."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'app/src/main/java/com/friendbook/social'


def absent(test, token, source, where):
    test.assertFalse(token in source, '%s must not contain %s' % (where, token))


class ReelStickerVectorGuards(unittest.TestCase):
    def tiles(self):
        source = (JAVA / 'reels/AddReelActivity.java').read_text()
        block = source.split('STICKER_TILES = {', 1)[1].split('};', 1)[0]
        return re.findall(r'\{"([a-z_0-9]+)",\s*"([^"]+)"\}', block)

    def test_picker_stores_vector_keys_not_words(self):
        tiles = self.tiles()
        self.assertGreaterEqual(len(tiles), 12)
        icons = (JAVA / 'core/StudioVectorIcons.java').read_text()
        switch = icons.split('public static boolean isVectorToken', 1)[1]
        for key, label in tiles:
            self.assertIn('case "%s"' % key, switch, '%s is not a real vector glyph' % key)
            self.assertFalse(label.isupper(), 'sticker labels are words, not shouted keys: %s' % label)

    def test_old_word_sticker_list_is_gone(self):
        source = (JAVA / 'reels/AddReelActivity.java').read_text()
        for word in ['"FIRE", "LOVE", "HAHA"', '"SPARK"', '"BLOOM"', '"GEM"', 'setItems(stickers']:
            absent(self, word, source, 'AddReelActivity')

    def test_editor_and_player_draw_the_sticker_as_a_vector(self):
        reel = (JAVA / 'reels/AddReelActivity.java').read_text()
        self.assertIn('stageStickerTv = com.friendbook.social.core.StudioVectorIcons.stickerView(', reel)
        player = (JAVA / 'reels/ReelsFragment.java').read_text()
        self.assertIn('overlayStickerTv = com.friendbook.social.core.StudioVectorIcons.stickerView(', player)
        icons = (JAVA / 'core/StudioVectorIcons.java').read_text()
        self.assertIn('public static TextView stickerView(', icons)
        self.assertIn('public static Drawable drawablePx(', icons)
        # A vector key clears the text so the literal word can never be drawn.
        sticker = icons.split('public static TextView stickerView(', 1)[1]
        sticker = sticker.split('private static String normalizeToken', 1)[0]
        self.assertIn('if (isVectorToken(next))', sticker)
        self.assertIn('super.setText("", type)', sticker)
        self.assertIn('drawablePx(token, sz, color)', sticker)

    def test_poster_bake_uses_the_same_vector_engine(self):
        upload = (JAVA / 'reels/ReelUploadManager.java').read_text()
        bake = upload.split('// 2. Sticker overlay', 1)[1].split('// 3. Text overlay pill', 1)[0]
        self.assertIn('StudioVectorIcons.isVectorToken(sticker)', bake)
        self.assertIn('StudioVectorIcons.drawablePx(', bake)
        self.assertIn('icon.draw(canvas)', bake)
        # Legacy text stickers keep the old drawText path so old Reels look unchanged.
        self.assertIn('canvas.drawText(sticker, sx, sy, p)', bake)

    def test_no_keyboard_emoji_stickers_were_introduced(self):
        source = (JAVA / 'reels/AddReelActivity.java').read_text()
        block = source.split('STICKER_TILES = {', 1)[1].split('};', 1)[0]
        self.assertTrue(all(ord(ch) < 0x2100 for ch in block), 'sticker tiles must be vector keys')


if __name__ == '__main__':
    unittest.main()
