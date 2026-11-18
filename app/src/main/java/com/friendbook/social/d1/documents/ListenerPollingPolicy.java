package com.friendbook.social.d1.documents;

/** Chat messages already have their own faster schedule; never also poll them on the general tick. */
final class ListenerPollingPolicy {
    private ListenerPollingPolicy() { }
    static boolean isChatMessages(String path) {
        if(path==null) return false;
        String[] parts=path.split("/",-1);
        return parts.length==3 && "chats".equals(parts[0]) && !parts[1].isEmpty() && "messages".equals(parts[2]);
    }
}
