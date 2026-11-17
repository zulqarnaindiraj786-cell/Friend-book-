package com.google.firebase.firestore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** A Firestore field path: documentId() or of("a", "b"). */
public class FieldPath {
    private final List<String> segments;

    private FieldPath(List<String> segments) {
        this.segments = segments;
    }

    public static FieldPath documentId() {
        List<String> s = new ArrayList<>();
        s.add("__name__");
        return new FieldPath(s);
    }

    public static FieldPath of(String... segments) {
        return new FieldPath(new ArrayList<>(Arrays.asList(segments)));
    }

    public List<String> getFieldPath() {
        return new ArrayList<>(segments);
    }

    public String getFieldPathString() {
        if (segments.size() == 1 && "__name__".equals(segments.get(0))) return "__name__";
        return String.join(".", segments);
    }

    boolean isDocumentId() {
        return segments.size() == 1 && "__name__".equals(segments.get(0));
    }
}
