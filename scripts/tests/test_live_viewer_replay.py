"""Wiring guard; behavioral snapshot-replay coverage is in LiveViewerEventsTest."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class LiveViewerReplay(unittest.TestCase):
    def test_join_leave_use_packet_gate_before_triggering_heartbeat(self):
        source = (ROOT / 'app/src/main/java/com/friendbook/social/live/LiveActivity.java').read_text()
        chat = source.split('private void onChatSnapshot(', 1)[1].split('private ', 1)[0]
        self.assertIn('boolean isNewPacket = processedPacketIds.add(id);', chat)
        self.assertIn('LiveViewerEvents.apply(activeViewerIds, uid, true, isNewPacket)', chat)
        self.assertIn('LiveViewerEvents.apply(activeViewerIds, uid, false, isNewPacket)', chat)
        self.assertEqual(chat.count('publishHostHeartbeat();'), 1)
        self.assertIn('if (!viewersBeforeSnapshot.equals(activeViewerIds)) publishHostHeartbeat();', chat)
        self.assertNotIn('activeViewerIds.add(uid)', chat)
        self.assertNotIn('activeViewerIds.remove(uid)', chat)
        self.assertIn('visibleMessages.add(new ChatItem(id, uid, name, photo, text, "join"))', chat)

    def test_gate_rejects_replayed_packets_before_mutating_membership(self):
        source = (ROOT / 'app/src/main/java/com/friendbook/social/live/LiveViewerEvents.java').read_text()
        self.assertLess(source.index('if (!newPacket'), source.index('viewers.add(uid)'))
