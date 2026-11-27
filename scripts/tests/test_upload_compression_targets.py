from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'app/src/main/java/com/friendbook/social'


class UploadCompressionTargets(unittest.TestCase):
    def test_photo_upload_targets_size_before_modest_downscale(self):
        source = (JAVA / 'core/Cloudinary.java').read_text()
        compressor = source.split('private static byte[] compressImageFor2G', 1)[1].split('public static Task<String> uploadJpegBytes', 1)[0]
        self.assertIn('compressed.length <= 150_000', compressor)
        self.assertIn('for (int q = quality; q >= 58; q -= 6)', compressor)
        self.assertIn('Math.max(bmp.getWidth(), bmp.getHeight()) > 960', compressor)

    def test_video_upload_is_transcoded_to_social_renditions(self):
        source = (JAVA / 'core/PhoneVideoEncoder.java').read_text()
        self.assertIn('Presentation.createForHeight(preview ? 360 : 720)', source)
        self.assertIn('setBitrate(preview ? 150_000 : 800_000)', source)


if __name__ == '__main__':
    unittest.main()
