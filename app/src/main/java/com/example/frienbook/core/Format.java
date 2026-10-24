package com.example.frienbook.core;

import com.google.firebase.Timestamp;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/** Formatting helpers: timestamps, counts, safe readers from Firestore maps. */
public class Format {

    /** millis from various Firestore timestamp shapes. */
    public static long ts(Object o) {
        if (o == null) return 0L;
        if (o instanceof Timestamp) return ((Timestamp) o).toDate().getTime();
        if (o instanceof Date) return ((Date) o).getTime();
        if (o instanceof Long) return (Long) o;
        if (o instanceof Double) return ((Double) o).longValue();
        return 0L;
    }

    public static String ago(long t) {
        if (t <= 0) return "";
        long now = System.currentTimeMillis();
        long diff = Math.max(0, now - t);
        long m = diff / 60000L;
        if (m < 1) return "Just now";
        if (m < 60) return m + "m";
        long h = m / 60;
        if (h < 24) return h + "h";
        long d = h / 24;
        if (d < 7) return d + "d";
        if (d < 365) return new SimpleDateFormat("d MMM", Locale.getDefault()).format(new Date(t));
        return new SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(new Date(t));
    }

    public static String dateTime(long t) {
        if (t <= 0) return "";
        return new SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()).format(new Date(t));
    }

    public static String count(long n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 1_000_000) return String.format(Locale.US, "%.1fK", n / 1000f).replace(".0K", "K");
        return String.format(Locale.US, "%.1fM", n / 1_000_000f).replace(".0M", "M");
    }

    /** mm:ss for short durations (voice notes, videos). */
    public static String duration(int seconds) {
        if (seconds < 0) seconds = 0;
        int m = seconds / 60;
        int s = seconds % 60;
        return m + ":" + (s < 10 ? "0" + s : String.valueOf(s));
    }

    public static String cap(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ---- safe map readers ----
    public static String str(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v == null ? "" : String.valueOf(v);
    }

    public static String strOr(Map<String, Object> m, String k, String def) {
        String s = str(m, k);
        return s.isEmpty() ? def : s;
    }

    public static int intOf(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        if (v instanceof Number) return ((Number) v).intValue();
        return 0;
    }

    public static boolean boolOf(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v instanceof Boolean && (Boolean) v;
    }

    @SuppressWarnings("unchecked")
    public static java.util.List<String> listOf(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        if (v instanceof java.util.List) {
            java.util.List<String> out = new java.util.ArrayList<>();
            for (Object o : (java.util.List<Object>) v) if (o != null) out.add(String.valueOf(o));
            return out;
        }
        return new java.util.ArrayList<>();
    }
}
