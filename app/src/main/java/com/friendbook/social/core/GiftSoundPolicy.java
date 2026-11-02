package com.friendbook.social.core;
import java.util.Locale;
public final class GiftSoundPolicy {
    private GiftSoundPolicy() {}
    public static String effect(String id,String emoji,String name) {
        String key=(String.valueOf(id)+" "+String.valueOf(name)).toLowerCase(Locale.ROOT);
        if(key.contains("lion") || "🦁".equals(emoji)) return "lion";
        if(key.contains("plane") || key.contains("jet") || key.contains("universe") || key.contains("rocket")
                || "✈️".equals(emoji) || "✈".equals(emoji) || "🛩️".equals(emoji) || "🚀".equals(emoji)) return "airplane";
        return "gift";
    }
}
