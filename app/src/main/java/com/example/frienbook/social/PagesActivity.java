package com.example.frienbook.social;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.example.frienbook.core.Fb;
import com.example.frienbook.core.Format;
import com.example.frienbook.core.Ui;
import com.example.frienbook.core.cloud.DocumentSnapshot;
import com.example.frienbook.core.cloud.FieldValue;
import com.example.frienbook.core.cloud.ListenerRegistration;
import com.example.frienbook.core.cloud.Query;

import java.util.List;
import java.util.Map;

/**
 * 📄 Pages — Facebook Lite style: create your page (name/category/about), discover pages,
 * 👍 follow / unfollow with live follower count.
 * Firestore: pages {name, category, about, photoUrl?, ownerId, ownerName, followers[], createdAt}.
 */
public class PagesActivity extends Activity {

    private LinearLayout list;
    private TextView countT;
    private ListenerRegistration reg;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        root.addView(Ui.pageBar(this, "📄 Pages"));

        ScrollView sv = new ScrollView(this);
        list = Ui.col(this);
        Ui.pad(list, 14, 10, 14, 24);
        list.setBackgroundColor(Ui.bg(this));
        sv.addView(list);

        TextView create = Ui.btn(this, "＋ Create a Page");
        create.setOnClickListener(v -> createDialog());
        list.addView(create, new LinearLayout.LayoutParams(-1, Ui.dp(this, 44)));

        countT = Ui.tv(this, "Loading pages…", 12, Ui.sub(this));
        Ui.pad(countT, 2, 12, 2, 6);
        list.addView(countT);

        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        reg = Fb.db().collection("pages")
                .orderBy("createdAt", Query.Direction.DESCENDING).limit(100)
                .addSnapshotListener((snap, err) -> {
                    while (list.getChildCount() > 2) list.removeViewAt(2);
                    int n = snap == null ? 0 : snap.size();
                    countT.setText(n + " page(s) — follow the ones you like");
                    if (n == 0) {
                        TextView e = Ui.tv(this, "Abhi koi page nahi — pehla page aap banayein! ✨", 13, Ui.sub(this));
                        Ui.pad(e, 4, 10, 4, 0);
                        list.addView(e);
                        return;
                    }
                    for (DocumentSnapshot d : snap.getDocuments()) list.addView(row(d));
                });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (reg != null) reg.remove();
    }

    private View row(DocumentSnapshot d) {
        Map<String, Object> m = d.getData();
        String name = Format.strOr(m, "name", "Page");
        String cat = Format.str(m, "category");
        String about = Format.str(m, "about");
        String photo = Format.str(m, "photoUrl");
        List<String> followers = Format.listOf(m, "followers");
        boolean meFollow = followers.contains(Fb.uid());
        boolean mine = Fb.uid() != null && Fb.uid().equals(Format.str(m, "ownerId"));

        LinearLayout card = Ui.row(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(Ui.round(this, Ui.card(this), 14));
        Ui.pad(card, 12, 12, 12, 12);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.setMargins(0, 0, 0, Ui.dp(this, 10));
        card.setLayoutParams(cp);

        ImageView av = new ImageView(this);
        Ui.avatar(this, av, photo);
        int avs = Ui.dp(this, 52);
        card.addView(av, new LinearLayout.LayoutParams(avs, avs));

        LinearLayout info = Ui.col(this);
        info.setPadding(Ui.dp(this, 12), 0, 0, 0);
        info.addView(Ui.tvBold(this, name, 15, Ui.txt(this)));
        if (!cat.isEmpty()) info.addView(Ui.tv(this, cat, 12, Ui.sub(this)));
        if (!about.isEmpty()) info.addView(Ui.tv(this, about, 11, Ui.sub(this)));
        info.addView(Ui.tv(this, Format.count(followers.size()) + " followers"
                + (mine ? " · 👑 your page" : ""), 11, Ui.sub(this)));
        card.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

        if (mine) {
            TextView del = Ui.tvBold(this, "🗑", 18, Ui.sub(this));
            del.setPadding(Ui.dp(this, 10), 0, 0, 0);
            del.setOnClickListener(v -> Ui.confirm(this, "Delete page?", "\"" + name + "\" hat jayegi.",
                    "Delete", () -> d.getReference().delete()));
            card.addView(del);
        } else {
            TextView follow = Ui.btn(this, meFollow ? "Following ✓" : "＋ Follow");
            follow.setTextSize(12);
            follow.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
            follow.setOnClickListener(v -> d.getReference().update("followers",
                    meFollow ? FieldValue.arrayRemove(Fb.uid()) : FieldValue.arrayUnion(Fb.uid())));
            card.addView(follow);
        }
        return card;
    }

    private void createDialog() {
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 20, 10, 20, 0);
        EditText name = Ui.input(this, "Page name (e.g. Chai Point ☕)");
        EditText cat = Ui.input(this, "Category (e.g. Food, Sports, Creator)");
        EditText about = Ui.multi(this, "About this page…", 2);
        box.addView(name, new LinearLayout.LayoutParams(-1, Ui.dp(this, 46)));
        box.addView(cat, new LinearLayout.LayoutParams(-1, Ui.dp(this, 46)));
        box.addView(about, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle("Create a Page")
                .setView(box)
                .setPositiveButton("Publish", (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (n.isEmpty()) { Ui.toast(this, "Page name daalo"); return; }
                    java.util.Map<String, Object> pg = Fb.map(
                            "name", n,
                            "category", cat.getText().toString().trim(),
                            "about", about.getText().toString().trim(),
                            "photoUrl", Fb.me != null && Fb.me.photoUrl != null ? Fb.me.photoUrl : "",
                            "ownerId", Fb.uid(),
                            "ownerName", Fb.myName(),
                            "followers", new java.util.ArrayList<String>(),
                            "createdAt", FieldValue.serverTimestamp());
                    Fb.db().collection("pages").add(pg)
                            .addOnSuccessListener(x -> Ui.toast(this, "📄 Page published!"))
                            .addOnFailureListener(err -> Ui.toast(this, "Failed: " + err.getMessage()));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
