package com.friendbook.social.core;

/** Validate persisted UTF-16 ranges against exact text; edits must not link the wrong person. */
public final class MentionPolicy {
    private MentionPolicy() {}
    public static boolean valid(CharSequence text,int start,int end,String label,String uid) {
        return text!=null && label!=null && uid!=null && !uid.isEmpty() && uid.length()<=128
                && !uid.contains("/") && start>=0 && end>start && end<=text.length()
                && label.startsWith("@") && label.length()>1
                && text.subSequence(start,end).toString().equals(label);
    }
}
