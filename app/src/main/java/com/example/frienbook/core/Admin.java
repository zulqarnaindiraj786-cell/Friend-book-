package com.example.frienbook.core;

import com.example.frienbook.models.Models;

/** Admin gating: the owner email (and anyone flagged isAdmin in Firestore). */
public class Admin {

    public static final String OWNER_EMAIL = "zulqarnaindiraj786@gmail.com";

    /** True when the signed-in user is the app owner or a promoted admin. */
    public static boolean isAdmin() {
        Models.User me = Fb.me;
        if (me == null || Fb.auth().getCurrentUser() == null) return false;
        String email = me.email != null ? me.email : "";
        if (email.equalsIgnoreCase(OWNER_EMAIL)) return true;
        return me.isAdmin;
    }

    /** Firestore rules-side check uses this email too (see firebase/firestore.rules). */
    public static boolean isOwnerEmail(String email) {
        return email != null && email.equalsIgnoreCase(OWNER_EMAIL);
    }
}
