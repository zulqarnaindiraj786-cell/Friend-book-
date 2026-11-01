package com.friendbook.social.chat;

import com.friendbook.social.models.Models;
import java.util.*;

/** Immutable render keys include grouping neighbours and the two moving status markers. */
final class MessageRenderState {
    private static boolean grouped(Models.Message a, Models.Message b) {
        return a != null && b != null && !"system".equals(a.type) && !"system".equals(b.type)
                && !"sticker".equals(a.type) && !"sticker".equals(b.type)
                && a.senderId != null && a.senderId.equals(b.senderId)
                && Math.abs(a.createdAt - b.createdAt) <= 180_000L;
    }
    static List<List<Object>> keys(List<Models.Message> rows, String uid) {
        int lastMine = -1, lastSeen = -1;
        for (int i = 0; i < rows.size(); i++) {
            Models.Message m = rows.get(i);
            if (uid != null && uid.equals(m.senderId) && !"system".equals(m.type)) {
                lastMine = i; if (m.isSeen) lastSeen = i;
            }
        }
        List<List<Object>> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Models.Message m = rows.get(i);
            out.add(Arrays.asList(m.senderId, m.senderName, m.senderPhoto, m.receiverId,
                    m.text, m.imageUrl, m.type, m.voiceUrl, m.replyToText, m.replyToSender,
                    m.callType, m.callStatus, m.callDurationSec, m.forwarded, m.voiceDurationSec,
                    m.isSeen, m.isDelivered, m.isDeleted, m.createdAt,
                    m.reactions == null ? Collections.emptyMap() : new HashMap<>(m.reactions),
                    i > 0 && grouped(rows.get(i - 1), m),
                    i + 1 < rows.size() && grouped(m, rows.get(i + 1)),
                    i == lastMine, i == lastSeen));
        }
        return out;
    }
}
