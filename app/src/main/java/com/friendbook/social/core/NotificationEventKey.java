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
        return hash("action_",new String[]{actor,recipient,type,subject==null?"":subject});
    }

    /** One event per saved comment, not one event per post or callback attempt. */
    public static String forComment(String actor,String recipient,String type,String resource,String commentId) {
        if(actor==null || actor.isEmpty() || recipient==null || recipient.isEmpty()
                || resource==null || resource.isEmpty() || commentId==null || commentId.isEmpty()
                || !("comment".equals(type) || "reel_comment".equals(type) || "reply".equals(type) || "reel_comment_reply".equals(type)))return null;
        String eventType=("reply".equals(type) || "reel_comment_reply".equals(type))?"reply":"comment";
        return hash("comment_",new String[]{actor,recipient,eventType,resource,commentId});
    }

    private static String hash(String prefix,String[] fields) {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            for(String field:fields) {
                byte[] value=field.getBytes(StandardCharsets.UTF_8);
                digest.update(new byte[]{(byte)(value.length>>>24),(byte)(value.length>>>16),(byte)(value.length>>>8),(byte)value.length});
                digest.update(value);
            }
            StringBuilder result=new StringBuilder(prefix);
            for(byte b:digest.digest()) result.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
