package com.example.frienbook.core.cloud;

/**
 * The one option the app ever passes to set(): merge(). With it, the fields you send are laid over
 * what is already stored; without it the document is replaced, so a field you stopped sending really
 * disappears. Both go to the Worker as an explicit instruction - the phone never decides by guessing.
 */
public final class SetOptions {

    private final boolean merge;

    private SetOptions(boolean merge) {
        this.merge = merge;
    }

    public static SetOptions merge() {
        return new SetOptions(true);
    }

    boolean isMerge() {
        return merge;
    }
}
