import pathlib
import unittest

JAVA = pathlib.Path(__file__).resolve().parents[2] / 'app/src/main/java/com/friendbook/social'


class InlinePhotoBandwidthTest(unittest.TestCase):
    def test_chat_preview_keeps_fullscreen_url_and_clears_recycled_request(self):
        source = (JAVA / 'chat/MessagesAdapter.java').read_text()
        self.assertIn('Ui.imgPreview(c, h.img, m.imageUrl)', source)
        self.assertIn('hasVideo ? m.videoUrl : m.imageUrl', source)
        self.assertIn('void onViewRecycled(@NonNull VH holder) {\n        clearMessagePhoto(holder);', source)

    def test_profile_photo_preview_keeps_fullscreen_url(self):
        source = (JAVA / 'profile/ProfileActivity.java').read_text()
        self.assertIn('Ui.imgPreview(ProfileActivity.this, h.iv, url);\n            h.iv.setOnClickListener(v -> openFullscreenImage(url));', source)
        ui = (JAVA / 'core/Ui.java').read_text()
        self.assertIn('w_320,c_limit,f_webp,q_auto:eco/', ui)
        self.assertIn(': feedThumbnailUrl(url);', ui)
        self.assertIn('img(c, iv, preview);', ui)

    def test_profile_avatar_cover_and_feed_can_recover_from_derivative_failure(self):
        ui = (JAVA / 'core/Ui.java').read_text()
        self.assertIn('if (!optimized.equals(original)) request.error(request.clone().load(original));', ui)
        for method in ('avatarLarge', 'img'):
            body = ui.split('public static void ' + method + '(', 1)[1].split('\n    }', 1)[0]
            self.assertIn('photoWithOriginalFallback(', body)

    def test_feed_photo_shows_retry_after_final_failure(self):
        ui = (JAVA / 'core/Ui.java').read_text()
        self.assertIn('request.clone().load(original).listener(failure)', ui)
        feed = (JAVA / 'post/FeedAdapter.java').read_text()
        self.assertIn('Photo couldn’t load · Tap to retry', feed)
        self.assertIn('holder.boundPhotoPost != post', feed)
