"""Wiring and bundled PCM validation. Actual speaker/mixing quality needs a real device."""
import unittest, wave, struct
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
JAVA=ROOT/'app/src/main/java/com/friendbook/social'
class SoundEffectsTest(unittest.TestCase):
    def test_assets_are_small_non_silent_pcm(self):
        for name in ['reaction','comment','message_sent','message_received','gift','lion_roar','airplane']:
            with wave.open(str(ROOT/f'app/src/main/res/raw/sfx_{name}.wav')) as w:
                self.assertEqual(w.getnchannels(),1); self.assertEqual(w.getsampwidth(),2)
                self.assertEqual(w.getframerate(),22050)
                frames=w.readframes(w.getnframes()); self.assertLess(len(frames),1_000_000)
                samples=struct.unpack('<'+'h'*(len(frames)//2),frames)
                self.assertGreater(max(abs(x) for x in samples),1000)
                self.assertLess(max(abs(x) for x in samples),30000)
    def test_success_hooks_cover_posts_reels_comments_and_all_send_types(self):
        for file in ['post/Reactions.java','reels/ReelsFragment.java','post/CommentsEngine.java']:
            self.assertIn('SoundEffects.success', (JAVA/file).read_text(),file)
        chat=(JAVA/'chat/ChatActivity.java').read_text()
        self.assertEqual(chat.count('SoundEffects.success(this, "sent", soundStarted, me)'),3)
        self.assertIn('SoundEffects.incoming(this)',chat)
        self.assertIn('MetadataChanges.INCLUDE',chat)
        self.assertIn('snap.getMetadata().hasPendingWrites()',chat)
    def test_silence_background_and_cooldown_guards_are_present(self):
        manager=(JAVA/'core/SoundEffects.java').read_text()
        for token in ['Prefs.quiet','Prefs.inAppSounds','App.isForeground','RINGER_MODE_NORMAL','INTERRUPTION_FILTER_ALL','ticket()-started>10_000L','ticket()-requested>500L','Objects.equals(actor,Fb.uid())']:
            self.assertIn(token,manager)
        self.assertNotIn('requestAudioFocus',manager)
        self.assertIn('SoundEffects.stopAll()', (JAVA/'App.java').read_text())
    def test_gifts_are_realtime_gated_and_visible_chat_does_not_double_ding(self):
        live=(JAVA/'live/LiveActivity.java').read_text()
        self.assertIn('giftSoundGate.observe',live)
        self.assertIn('freshGiftSound && stars > 0',live)
        self.assertIn('chatGift.put("giftId", gift.id)',live)
        self.assertIn('chat_already_visible',(JAVA/'push/PushManager.java').read_text())
        adapter=(JAVA/'chat/MessagesAdapter.java').read_text()
        self.assertNotIn('SoundEffects',adapter) # Never trigger effects from binding/history rendering.
if __name__=='__main__':unittest.main()
