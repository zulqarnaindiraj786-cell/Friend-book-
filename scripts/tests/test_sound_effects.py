"""Chat-only audio guards. Physical listening/latency still needs a real device."""
import unittest, wave, struct
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
JAVA=ROOT/'app/src/main/java/com/friendbook/social'
class SoundEffectsTest(unittest.TestCase):
    def test_assets_are_short_valid_pcm(self):
        for name in ['message_pop','sfx_message_sent']:
            with wave.open(str(ROOT/f'app/src/main/res/raw/{name}.wav')) as w:
                self.assertEqual((w.getnchannels(),w.getsampwidth(),w.getframerate()),(1,2,22050))
                self.assertLess(w.getnframes()/w.getframerate(),.15)
                data=struct.unpack('<'+'h'*w.getnframes(),w.readframes(w.getnframes()))
                self.assertGreater(max(abs(x) for x in data[:110]),10000)
                self.assertLess(max(abs(x) for x in data),30000)
    def test_only_chat_actions_can_sound(self):
        for file in ['post/Reactions.java','post/CommentsEngine.java','reels/ReelsFragment.java','live/LiveActivity.java']:
            source=(JAVA/file).read_text()
            self.assertNotIn('SoundManager.action',source)
            self.assertNotIn('SoundEffects.gift(',source)
        manager=(JAVA/'core/SoundEffects.java').read_text()
        self.assertIn('!SENT.equals(effect)',manager)
        self.assertEqual(manager.count('current.load('),2)
        for name in ['like_pop','comment_pop','sfx_gift','sfx_airplane','sfx_lion_roar']:
            self.assertFalse((ROOT/f'app/src/main/res/raw/{name}.wav').exists())
    def test_all_chat_sends_remain_immediate(self):
        chat=(JAVA/'chat/ChatActivity.java').read_text()
        # The sent sound still plays before the network work starts, on every send path: the write
        # itself is now a D1 write (room row + message), dispatched after the sound.
        self.assertEqual(chat.count('SoundManager.action(this, "sent", me);'),3)
        self.assertEqual(chat.count('writeRoomThenMessage('),4)  # 3 callers + the helper itself
        self.assertIn('SoundManager.playNewMessage(this)',chat)
        self.assertIn('MetadataChanges.INCLUDE',chat)
        self.assertIn('snap.getMetadata().hasPendingWrites()',chat)
        self.assertIn('chat_already_visible',(JAVA/'push/PushManager.java').read_text())
    def test_mute_lifecycle_and_no_bind_playback(self):
        manager=(JAVA/'core/SoundEffects.java').read_text()
        for token in ['Prefs.quiet','Prefs.inAppSounds','App.isForeground','RINGER_MODE_NORMAL','INTERRUPTION_FILTER_ALL','ticket()-requested>120L','Objects.equals(actor,Fb.uid())']:
            self.assertIn(token,manager)
        self.assertNotIn('requestAudioFocus',manager)
        self.assertIn('SoundEffects.stopAll()', (JAVA/'App.java').read_text())
        self.assertNotIn('SoundManager',(JAVA/'chat/MessagesAdapter.java').read_text())
if __name__=='__main__':unittest.main()
