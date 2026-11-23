from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[2]

class PushReceiveBurst(unittest.TestCase):
    def test_gate_covers_refresh_not_display_and_never_uses_event_id(self):
        source=(ROOT/'app/src/main/java/com/friendbook/social/push/FriendMessagingService.java').read_text()
        self.assertIn('REFRESH_BURSTS.allow(actor,message.getMessageId()',source)
        self.assertIn('"incoming_call".equals(data.get("type"))',source)
        self.assertIn('if (refreshAllowed && "message".equals',source)
        self.assertIn('if (refreshAllowed && actor.equals',source)
        self.assertIn('PushManager.show(this, data);',source)
        self.assertNotIn('if (!refreshAllowed) return',source)
        self.assertNotIn('REFRESH_BURSTS.allow(actor,data.get("eventId")',source)
