package com.friendbook.social.admin.frag;

import android.app.Fragment;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.friendbook.social.core.Admin;
import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Icons;
import com.friendbook.social.core.Ui;
import com.friendbook.social.models.Models;
import com.friendbook.social.profile.ProfileActivity;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Admin Moderators tab — Appoint or remove Global App Moderators across all of friendbook.
 * Global Moderators ONLY get permission to:
 *   1. Delete violating user posts
 *   2. Ban / Unban non-admin users
 * They NEVER gain access to the full Admin Panel!
 */
public class AdminModeratorsFragment extends Fragment {

    private LinearLayout activeList, searchList;
    private TextView activeCountT;
    private final List<Models.User> allUsers = new ArrayList<>();
    private String query = "";
    private ListenerRegistration reg;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inf, ViewGroup container, Bundle b) {
        android.app.Activity a = getActivity();
        ScrollView sv = new ScrollView(a);
        LinearLayout root = Ui.col(a);
        root.setBackgroundColor(Ui.bg(a));
        Ui.pad(root, 12, 10, 12, 24);
        sv.addView(root);

        // Info card explaining strict Global Moderator scope
        LinearLayout info = Ui.col(a);
        info.setBackground(Ui.roundStroke(a, Ui.card(a), 12, Ui.BLUE, 1));
        Ui.pad(info, 14, 12, 14, 12);
        info.addView(Ui.tvBold(a, "🛡 Global friendbook Moderators", 15, Ui.BLUE));
        TextView desc = Ui.tv(a,
                "Appoint trusted community members as Moderators of friendbook.\n"
                        + "• ✅ Can delete rule-breaking user posts\n"
                        + "• ✅ Can ban / unban abusive user accounts\n"
                        + "• 🔒 Cannot open Admin Panel, give ticks, broadcast, or touch Admins",
                12, Ui.sub(a));
        desc.setPadding(0, Ui.dp(a, 4), 0, 0);
        info.addView(desc);
        root.addView(info);

        activeCountT = Ui.tvBold(a, "Active Moderators (0)", 14, Ui.txt(a));
        Ui.pad(activeCountT, 4, 14, 4, 6);
        root.addView(activeCountT);

        activeList = Ui.col(a);
        root.addView(activeList);

        TextView addHead = Ui.tvBold(a, "Appoint a New Moderator", 14, Ui.txt(a));
        Ui.pad(addHead, 4, 16, 4, 6);
        root.addView(addHead);

        EditText search = Ui.input(a, "🔍 Search any user by name or email…");
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) {}
            @Override public void onTextChanged(CharSequence s, int st, int bf, int c) {
                query = s.toString().trim().toLowerCase(Locale.US);
                render();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.bottomMargin = Ui.dp(a, 8);
        root.addView(search, sp);

        searchList = Ui.col(a);
        root.addView(searchList);

        return sv;
    }

    @Override
    public void onStart() {
        super.onStart();
        reg = Fb.db().collection("users").limit(400)
                .addSnapshotListener((snap, err) -> {
                    allUsers.clear();
                    if (snap != null) {
                        for (DocumentSnapshot d : snap.getDocuments()) {
                            allUsers.add(Models.User.fromDoc(d));
                        }
                    }
                    render();
                });
    }

    @Override
    public void onStop() {
        super.onStop();
        if (reg != null) reg.remove();
    }

    private void render() {
        android.app.Activity a = getActivity();
        if (a == null || activeList == null) return;
        activeList.removeAllViews();
        searchList.removeAllViews();

        int modCount = 0;
        for (Models.User u : allUsers) {
            if (u.isModerator && !u.isAdmin && !Admin.isOwnerEmail(u.email)) {
                modCount++;
                activeList.addView(userRow(a, u, true));
            }
        }
        activeCountT.setText("Active Moderators (" + modCount + ")");
        if (modCount == 0) {
            TextView empty = Ui.tv(a, "No global moderators appointed yet. Search below to add one.", 12, Ui.sub(a));
            Ui.pad(empty, 8, 6, 8, 6);
            activeList.addView(empty);
        }

        int shown = 0;
        for (Models.User u : allUsers) {
            if (u.isAdmin || Admin.isOwnerEmail(u.email) || u.isModerator) continue;
            if (!query.isEmpty()) {
                boolean match = u.displayName.toLowerCase(Locale.US).contains(query)
                        || u.email.toLowerCase(Locale.US).contains(query);
                if (!match) continue;
            }
            searchList.addView(userRow(a, u, false));
            if (++shown >= 40) break;
        }
    }

    private View userRow(android.app.Activity a, Models.User u, boolean activeMod) {
        LinearLayout row = Ui.row(a);
        row.setBackground(Ui.round(a, Ui.card(a), 10));
        row.setGravity(Gravity.CENTER_VERTICAL);
        Ui.pad(row, 12, 10, 12, 10);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.bottomMargin = Ui.dp(a, 6);
        row.setLayoutParams(rp);

        ImageView av = new ImageView(a);
        Ui.avatar(a, av, u.photoUrl);
        int s = Ui.dp(a, 42);
        row.addView(av, new LinearLayout.LayoutParams(s, s));

        LinearLayout mid = Ui.col(a);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -2, 1);
        mp.leftMargin = Ui.dp(a, 10);
        LinearLayout nr = Ui.row(a);
        nr.setGravity(Gravity.CENTER_VERTICAL);
        nr.addView(Ui.tvBold(a, u.displayName, 14, Ui.txt(a)));
        ImageView tk = new ImageView(a);
        Icons.setTickBadge(tk, u.tickLevel, false);
        int ts = Ui.dp(a, 14);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ts, ts);
        tlp.leftMargin = Ui.dp(a, 4);
        nr.addView(tk, tlp);
        if (activeMod) {
            TextView chip = Ui.roundChip(a, "🛡 MOD", Ui.BLUE, 0xFFFFFFFF, 9);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
            clp.leftMargin = Ui.dp(a, 6);
            nr.addView(chip, clp);
        }
        mid.addView(nr);
        mid.addView(Ui.tv(a, activeMod ? "Can delete posts & ban users" : "Regular member", 11, Ui.sub(a)));
        row.addView(mid, mp);

        TextView btn = activeMod
                ? Ui.btnOutline(a, "Remove", Ui.RED)
                : Ui.btn(a, "🛡 Make Mod");
        Ui.pad(btn, 12, 6, 12, 6);
        btn.setOnClickListener(v -> Admin.setGlobalModerator(a, u.uid, u.displayName, !activeMod));
        row.addView(btn);

        row.setOnClickListener(v ->
                startActivity(new Intent(a, ProfileActivity.class).putExtra("uid", u.uid)));
        return row;
    }
}
