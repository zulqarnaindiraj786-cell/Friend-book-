package com.example.frienbook.core.cloud;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A marker, never a value.
 *
 * When one of these is inside a map you write, the map is split before it leaves the phone: plain
 * fields go to the Worker as data, markers go as operations it performs on the stored row itself.
 * That is what makes a like counter exact when two people tap at the same moment, and what stops a
 * double tap from putting the same uid into likedBy twice - neither of which a client can guarantee
 * by reading, adding one and writing back.
 */
public final class FieldValue {

    static final int SERVER_TIMESTAMP = 1;
    static final int INCREMENT = 2;
    static final int ARRAY_UNION = 3;
    static final int ARRAY_REMOVE = 4;
    static final int DELETE = 5;

    final int kind;
    final double number;
    final List<Object> values;

    private FieldValue(int kind, double number, List<Object> values) {
        this.kind = kind;
        this.number = number;
        this.values = values;
    }

    /** Stamped with the Worker's clock, so a wrong phone time cannot reorder a feed or a thread. */
    public static FieldValue serverTimestamp() {
        return new FieldValue(SERVER_TIMESTAMP, 0, null);
    }

    public static FieldValue increment(long by) {
        return new FieldValue(INCREMENT, by, null);
    }

    public static FieldValue increment(double by) {
        return new FieldValue(INCREMENT, by, null);
    }

    /** Adds the values that are not already in the array, and leaves the ones that are. */
    public static FieldValue arrayUnion(Object... toAdd) {
        return new FieldValue(ARRAY_UNION, 0, list(toAdd));
    }

    public static FieldValue arrayRemove(Object... toRemove) {
        return new FieldValue(ARRAY_REMOVE, 0, list(toRemove));
    }

    /** Drops one field from a document without touching the rest of it. */
    public static FieldValue delete() {
        return new FieldValue(DELETE, 0, null);
    }

    private static List<Object> list(Object[] values) {
        List<Object> out = new ArrayList<>();
        if (values != null) out.addAll(Arrays.asList(values));
        return out;
    }
}
