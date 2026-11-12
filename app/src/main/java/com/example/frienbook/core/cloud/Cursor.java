package com.example.frienbook.core.cloud;

import android.util.Base64;

import org.json.JSONObject;

/**
 * Where a page stopped.
 *
 * The Worker pages by keyset - the ordering value plus the row's path as a tie-breaker - and hands the
 * position back as an opaque token. Encoding the same shape on the phone is only needed for the rare
 * "start after this value" call; everywhere else the cursor simply travels back unchanged, so a feed
 * can page through ten thousand posts without ever counting them.
 */
final class Cursor {

    private Cursor() { }

    static String encode(Object orderValue, String path) {
        try {
            JSONObject body = new JSONObject();
            Long whole = Cloud.asLong(orderValue);
            if (whole != null && !(orderValue instanceof String)) {
                body.put("v", whole);
            } else {
                body.put("v", String.valueOf(orderValue));
            }
            body.put("p", path == null ? "" : path);
            return Base64.encodeToString(body.toString().getBytes("UTF-8"),
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        } catch (Exception failed) {
            return null;
        }
    }
}
