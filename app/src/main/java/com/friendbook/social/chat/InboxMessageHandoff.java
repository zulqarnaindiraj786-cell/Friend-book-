package com.friendbook.social.chat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Heads observed in an open chat; shared only with the current account's inbox. No I/O. */
final class InboxMessageHandoff {
    static final class Head {
        final String chatId, preview, peerId;
        final long time;
        final boolean group;
        Head(String chatId,String preview,long time,String peerId,boolean group) {
            this.chatId=chatId;this.preview=preview;this.time=time;this.peerId=peerId;this.group=group;
        }
    }
    private static String actor;
    private static final Map<String,Head> heads=new LinkedHashMap<>();
    private static void scope(String uid) {
        if(!Objects.equals(actor,uid)){heads.clear();actor=uid;}
    }
    static void seen(String uid, Head head) {
        scope(uid);
        if(uid==null || head.time<=0)return;
        Head old=heads.get(head.chatId);
        if(old!=null && old.time>head.time)return;
        heads.put(head.chatId,head);
        if(heads.size()>32)heads.remove(heads.keySet().iterator().next());
    }
    static Map<String,Head> snapshot(String uid) {
        scope(uid);return new LinkedHashMap<>(heads);
    }
    static void confirmed(String uid,String chatId,long time) {
        scope(uid);
        Head head=heads.get(chatId);
        if(head!=null && time>=head.time)heads.remove(chatId);
    }
}
