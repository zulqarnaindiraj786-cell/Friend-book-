package com.friendbook.social.chat;

import com.friendbook.social.models.Models;
import java.util.LinkedHashMap;
import java.util.Map;

/** Keeps locally sent messages visible until a server snapshot contains them or send fails. */
final class PendingMessages {
    private final Map<String,Models.Message> rows = new LinkedHashMap<>();
    void add(Models.Message message) { if (message != null && message.id != null) rows.put(message.id, message); }
    void fail(String id) { rows.remove(id); }
    void clear() { rows.clear(); }
    void mergeInto(Map<String,Models.Message> server) {
        for (String id : server.keySet()) rows.remove(id);
        // Never replace an authoritative server row with an optimistic one.
        for (Map.Entry<String,Models.Message> row : rows.entrySet()) server.putIfAbsent(row.getKey(), row.getValue());
    }
}
