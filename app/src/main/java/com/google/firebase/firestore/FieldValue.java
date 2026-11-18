package com.google.firebase.firestore;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class FieldValue {
    public static final String KIND_SERVER_TIMESTAMP = "serverTimestamp";
    public static final String KIND_DELETE = "delete";
    public static final String KIND_INCREMENT = "increment";
    public static final String KIND_ARRAY_UNION = "arrayUnion";
    public static final String KIND_ARRAY_REMOVE = "arrayRemove";

    private static final FieldValue SERVER_TIMESTAMP = new FieldValue(KIND_SERVER_TIMESTAMP, 0.0, Collections.emptyList());
    private static final FieldValue DELETE = new FieldValue(KIND_DELETE, 0.0, Collections.emptyList());

    private final String kind;
    private final double numberOperand;
    private final List<Object> elements;

    private FieldValue(String kind, double numberOperand, List<Object> elements) {
        this.kind = kind;
        this.numberOperand = numberOperand;
        this.elements = elements;
    }

    public static FieldValue serverTimestamp() {
        return SERVER_TIMESTAMP;
    }

    public static FieldValue delete() {
        return DELETE;
    }

    public static FieldValue increment(long l) {
        return new FieldValue(KIND_INCREMENT, (double) l, Collections.emptyList());
    }

    public static FieldValue increment(double d) {
        return new FieldValue(KIND_INCREMENT, d, Collections.emptyList());
    }

    public static FieldValue arrayUnion(Object... elements) {
        return new FieldValue(KIND_ARRAY_UNION, 0.0, elements == null ? Collections.emptyList() : Arrays.asList(elements));
    }

    public static FieldValue arrayRemove(Object... elements) {
        return new FieldValue(KIND_ARRAY_REMOVE, 0.0, elements == null ? Collections.emptyList() : Arrays.asList(elements));
    }

    public String getKind() {
        return kind;
    }

    public double getNumberOperand() {
        return numberOperand;
    }

    public List<Object> getElements() {
        return elements;
    }
}
