package com.friendbook.social.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Stable identity only for notifications explicitly deduplicated by actor/recipient/type/subject. */
public final class NotificationEventKey {
    private NotificationEventKey() { }
    public static String forAction(String actor,String recipient,String type,String subject) {
        if (actor==null || actor.isEmpty() || recipient==null || recipient.isEmpty() || type==null) return null;
        boolean reaction=("like".equals(type) || "reel_like".equals(type));
        if (!reaction && !"friend_request".equals(type)) return null;
        if (reaction && (subject==null || subject.isEmpty())) return null;
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            for(String field:new String[]{actor,recipient,type,subject==null?"":subject}) {
                byte[] value=field.getBytes(StandardCharsets.UTF_8);
                digest.update(new byte[]{(byte)(value.length>>>24),(byte)(value.length>>>16),(byte)(value.length>>>8),(byte)value.length});
                digest.update(value);
            }
            StringBuilder result=new StringBuilder("action_");
            for(byte b:digest.digest()) result.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
