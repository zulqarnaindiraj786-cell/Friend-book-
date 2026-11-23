package com.friendbook.social.push;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Identity of active durable work, not a delivery cache or authorization decision. */
final class PushWorkKey {
    private static boolean valid(String value) {return value!=null && value.matches("[A-Za-z0-9_-]{1,256}");}
    static String of(String actor,JSONObject body) {
        String action=body.optString("action");
        if ("register".equals(action)) return "register_"+actor;
        JSONObject data=body.optJSONObject("data");
        String target=body.optString("target_user_id");
        if ("send".equals(action) && valid(actor) && valid(target) && data!=null) {
            JSONArray identity=new JSONArray().put(actor).put(target);
            // Match gateway event precedence; previews are untrusted and the
            // gateway derives the actual payload from the persisted event.
            if ("message".equals(data.optString("type")) && valid(data.optString("chatId")) && valid(data.optString("messageId"))) {
                identity.put("message").put(data.optString("chatId")).put(data.optString("messageId"));
                return "event_v2_"+digest(identity.toString());
            }
            if (valid(data.optString("notificationId"))) {
                identity.put("notification").put(data.optString("notificationId"));
                return "event_v2_"+digest(identity.toString());
            }
        }
        // Unknown/self-test/bulk actions keep their original identity semantics.
        return actor+"_"+body.toString();
    }
    private static String digest(String input) {
        try {
            byte[] bytes=MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder out=new StringBuilder(64);
            for(byte b:bytes) out.append(Character.forDigit((b>>4)&15,16)).append(Character.forDigit(b&15,16));
            return out.toString();
        } catch(java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
}
