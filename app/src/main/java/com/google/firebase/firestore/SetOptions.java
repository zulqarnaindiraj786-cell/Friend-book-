package com.google.firebase.firestore;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class SetOptions {
    private static final SetOptions MERGE = new SetOptions(true, Collections.emptyList());

    private final boolean merge;
    private final List<String> fields;

    private SetOptions(boolean merge, List<String> fields) {
        this.merge = merge;
        this.fields = fields;
    }

    public static SetOptions merge() {
        return MERGE;
    }

    public static SetOptions mergeFields(String... fields) {
        return new SetOptions(true, fields == null ? Collections.emptyList() : Arrays.asList(fields));
    }

    public static SetOptions mergeFields(List<String> fields) {
        return new SetOptions(true, fields == null ? Collections.emptyList() : fields);
    }

    public boolean isMerge() {
        return merge;
    }

    public List<String> getFields() {
        return fields;
    }
}
