package com.google.firebase.firestore;

import java.util.ArrayList;
import java.util.List;

/** Options for DocumentReference.set(). */
public class SetOptions {
    private final boolean merge;
    private final List<String> mergeFields;

    private SetOptions(boolean merge, List<String> mergeFields) {
        this.merge = merge;
        this.mergeFields = mergeFields;
    }

    public static SetOptions merge() {
        return new SetOptions(true, null);
    }

    public static SetOptions merge(FieldPath... fields) {
        List<String> list = new ArrayList<>();
        if (fields != null) for (FieldPath f : fields) list.add(f.getFieldPathString());
        return new SetOptions(true, list);
    }

    public static SetOptions mergeFields(String... fields) {
        List<String> list = new ArrayList<>();
        if (fields != null) list.addAll(java.util.Arrays.asList(fields));
        return new SetOptions(true, list);
    }

    public static SetOptions mergeFields(FieldPath... fields) {
        return merge(fields);
    }

    boolean isMerge() { return merge; }

    List<String> getMergeFields() { return mergeFields; }
}
