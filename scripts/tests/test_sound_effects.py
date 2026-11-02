"""Wiring and bundled PCM validation. Actual speaker/mixing quality needs a real device."""
import unittest, wave, struct
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
JAVA=ROOT/'app/src/main/java/com/friendbook/social'
class SoundEffectsTest(unittest.TestCase):
    def test_assets_are_small_non_silent_pcm(self):
        for name in ['like_pop','comment_pop','message_pop','sfx_message_sent','sfx_gift','sfx_lion_roar','sfx_airplane']:
            with wave.open(str(ROOT/f'app/src/main/res/raw/{name}.wav')) as w:
                self.assertEqual(w.getnchannels(),1); self.assertEqual(w.getsampwidth(),2)
                self.assertEqual(w.getframerate(),22050)
                frames=w.readframes(w.getnframes()); self.assertLess(len(frames),1_000_000)
                samples=struct.unpack('<'+'h'*(len(frames)//2),frames)
                self.assertGreater(max(abs(x) for x in samples),1000)
                self.assertLess(max(abs(x) for x in samples),30000)
    def test_immediate_hooks_cover_posts_reels_comments_and_all_send_types(self):
        for file in ['post/Reactions.java','reels/ReelsFragment.java','post/CommentsEngine.java']:
            self.assertIn('SoundManager.action', (JAVA/file).read_text(),file)
        chat=(JAVA/'chat/ChatActivity.java').read_text()
        self.assertEqual(chat.count('SoundManager.action(this, "sent", me)'),3)
        self.assertIn('SoundManager.playNewMessage(this)',chat)
        self.assertIn('MetadataChanges.INCLUDE',chat)
        self.assertIn('snap.getMetadata().hasPendingWrites()',chat)
    def test_replaceable_resources_and_kotlin_facade(self):
        source=(JAVA/'core/SoundEffects.java').read_text()
        for name in ['like_pop','comment_pop','message_pop']:
            self.assertIn('R.raw.'+name,source)
        facade=(JAVA/'core/SoundManager.kt').read_text()
        for method in ['playLike','playComment','playMessageSent','playNewMessage']:
            self.assertIn('@JvmStatic fun '+method,facade)
        self.assertNotIn('SoundPool.Builder',facade) # no second audio pool / duplicate playback

    def test_interactions_have_fast_attack_and_short_duration(self):
        for name in ['like_pop','comment_pop','sfx_message_sent','message_pop']:
            with wave.open(str(ROOT/f'app/src/main/res/raw/{name}.wav')) as w:
                self.assertLessEqual(w.getnframes()/w.getframerate(),.25)
                data=struct.unpack('<'+'h'*w.getnframes(),w.readframes(w.getnframes()))
                self.assertGreater(max(abs(x) for x in data[:int(.005*w.getframerate())]),10000)
        manager=(JAVA/'core/SoundEffects.java').read_text()
        self.assertIn('.85f',manager)
        chat=(JAVA/'chat/ChatActivity.java').read_text()
        self.assertNotIn('SoundEffects.success',chat)
        self.assertEqual(chat.count('SoundManager.action(this, "sent", me);\n        batch.commit()'),3)

    def test_silence_background_and_cooldown_guards_are_present(self):
        manager=(JAVA/'core/SoundEffects.java').read_text()
        for token in ['Prefs.quiet','Prefs.inAppSounds','App.isForeground','RINGER_MODE_NORMAL','INTERRUPTION_FILTER_ALL','ticket()-requested>120L','Objects.equals(actor,Fb.uid())']:
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
