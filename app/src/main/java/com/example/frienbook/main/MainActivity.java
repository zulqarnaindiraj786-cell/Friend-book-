package com.example.frienbook.main;

import android.Manifest;
import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.frienbook.App;
import com.example.frienbook.core.Fb;
import com.example.frienbook.core.Prefs;
import com.example.frienbook.core.Presence;
import com.example.frienbook.core.Ui;
import com.example.frienbook.chat.ConversationsActivity;
import com.example.frienbook.friends.SearchActivity;
import com.example.frienbook.post.CreatePostActivity;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.HashMap;
import java.util.Map;

/** Home shell: FB-Lite top bar, bottom nav with 5 tabs, realtime badges. */
public class MainActivity extends Activity {

    public static final int TAB_HOME = 0, TAB_FRIENDS = 1, TAB_REELS = 2, TAB_NOTIFS = 3, TAB_MENU = 4;
    private FrameLayout container;
    private LinearLayout navRow;
    private final TextView[] navIcons = new TextView[5];
    private final View[] navIndicators = new View[5];
    private TextView msgBadge, notifBadge, friendsBadge;
    private int active = -1;
    private ListenerRegistration notifReg, chatReg, reqReg;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Fb.signedIn()) { finish(); return; }
        Ui.statusBarBlue(this);
        Fb.ensureMeFresh();
        Presence.init();

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));

        // ---- Top bar ----
        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(Ui.card(this));
        bar.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        bar.setElevation(Ui.dp(this, 2));

        TextView logo = Ui.tvBold(this, "friendbook", 24, Ui.BLUE);
        bar.addView(logo, new LinearLayout.LayoutParams(0, -2, 1));

        bar.addView(topIcon("➕", v -> startActivity(new Intent(this, CreatePostActivity.class))));
        bar.addView(topIcon("🔍", v -> startActivity(new Intent(this, SearchActivity.class))));
        bar.addView(messengerIcon());
        root.addView(bar);
        root.addView(Ui.divider(this));

        // ---- Fragment container ----
        container = new FrameLayout(this);
        container.setId(View.generateViewId());
        root.addView(container, new LinearLayout.LayoutParams(-1, 0, 1));

        // ---- Bottom nav ----
        navRow = Ui.row(this);
        navRow.setBackgroundColor(Ui.card(this));
        navRow.setElevation(Ui.dp(this, 8));
        navRow.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        String[] icons = {"🏠", "👥", "🎞️", "🔔", "☰"};
        for (int i = 0; i < 5; i++) {
            final int tab = i;
            LinearLayout cell = Ui.col(this);
            cell.setGravity(Gravity.CENTER);
            View ind = new View(this);
            ind.setBackgroundColor(Ui.BLUE);
            ind.setVisibility(View.GONE);
            cell.addView(ind, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 3)));
            navIndicators[i] = ind;

            FrameLayout iconWrap = new FrameLayout(this);
            TextView ic = Ui.tv(this, icons[i], 21, Ui.sub(this));
            ic.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
            iconWrap.addView(ic);
            if (i == TAB_NOTIFS) {
                notifBadge = Ui.badge(this);
                FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
                iconWrap.addView(notifBadge, fp);
            }
            if (i == TAB_FRIENDS) {
                friendsBadge = Ui.badge(this);
                FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
                iconWrap.addView(friendsBadge, fp);
            }
            cell.addView(iconWrap);
            navIcons[i] = ic;
            cell.setOnClickListener(v -> select(tab));
            navRow.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
        }
        root.addView(Ui.divider(this));
        root.addView(navRow);

        setContentView(root);
        select(TAB_HOME);
        startBadgeListeners();
        askNotifPermission();

        // Secret: shake to report a problem.
        App.instance.setShakeListener(this::feedbackDialog);
    }

    private View topIcon(String emoji, View.OnClickListener l) {
        TextView t = Ui.tv(this, emoji, 20, Ui.sub(this));
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.circle(Ui.inputBg(this)));
        int s = Ui.dp(this, 38);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(s, s);
        p.leftMargin = Ui.dp(this, 8);
        t.setLayoutParams(p);
        t.setOnClickListener(l);
        return t;
    }

    private View messengerIcon() {
        FrameLayout wrap = new FrameLayout(this);
        TextView t = Ui.tv(this, "💬", 20, Ui.sub(this));
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.circle(Ui.inputBg(this)));
        int s = Ui.dp(this, 38);
        FrameLayout.LayoutParams inner = new FrameLayout.LayoutParams(s, s);
        wrap.addView(t, inner);
        msgBadge = Ui.badge(this);
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        wrap.addView(msgBadge, fp);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.leftMargin = Ui.dp(this, 8);
        wrap.setLayoutParams(p);
        wrap.setOnClickListener(v -> startActivity(new Intent(this, ConversationsActivity.class)));
        return wrap;
    }

    public void select(int tab) {
        if (tab == active) return;
        active = tab;
        Fragment f;
        switch (tab) {
            case TAB_FRIENDS: f = new com.example.frienbook.friends.FriendsFragment(); break;
            case TAB_REELS: f = new com.example.frienbook.reels.ReelsFragment(); break;
            case TAB_NOTIFS: f = new NotificationsFragment(); break;
            case TAB_MENU: f = new MenuFragment(); break;
            default: f = new HomeFragment(); break;
        }
        FragmentTransaction ft = getFragmentManager().beginTransaction();
        ft.replace(container.getId(), f);
        ft.commitAllowingStateLoss();
        for (int i = 0; i < 5; i++) {
            boolean on = i == tab;
            navIndicators[i].setVisibility(on ? View.VISIBLE : View.GONE);
            navIcons[i].setAlpha(on ? 1f : 0.55f);
        }
    }

    private void startBadgeListeners() {
        String me = Fb.uid();
        if (me == null) return;
        if (notifReg != null) notifReg.remove();
        notifReg = Fb.db().collection("notifications")
                .whereEqualTo("toUserId", me).whereEqualTo("isRead", false)
                .addSnapshotListener((snap, err) -> {
                    Ui.setBadge(notifBadge, snap == null ? 0 : snap.size());
                });
        if (chatReg != null) chatReg.remove();
        chatReg = Fb.db().collection("chats")
                .whereArrayContains("participants", me)
                .addSnapshotListener((snap, err) -> {
                    int total = 0;
                    if (snap != null) {
                        for (com.google.firebase.firestore.DocumentSnapshot d : snap) {
                            Object u = d.get("unreadCount");
                            if (u instanceof Map) {
                                Object n = ((Map<?, ?>) u).get(me);
                                if (n instanceof Number) total += ((Number) n).intValue();
                            }
                        }
                    }
                    Ui.setBadge(msgBadge, total);
                });
        if (reqReg != null) reqReg.remove();
        reqReg = Fb.db().collection("friend_requests")
                .whereEqualTo("toUid", me).whereEqualTo("status", "pending")
                .addSnapshotListener((snap, err) -> {
                    Ui.setBadge(friendsBadge, snap == null ? 0 : snap.size());
                });
    }

    private void askNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 44);
        }
    }

    private void feedbackDialog() {
        if (active != TAB_HOME) return; // avoid interrupting videos/stories
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 20, 10, 20, 0);
        TextView t = Ui.tv(this, "Shook your phone? Tell us what went wrong.", 14, Ui.sub(this));
        box.addView(t);
        EditText e = Ui.multi(this, "Describe the problem…", 3);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = Ui.dp(this, 10);
        box.addView(e, p);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Report a problem")
                .setView(box)
                .setPositiveButton("Send", (d, w) -> {
                    String txt = e.getText().toString().trim();
                    if (txt.isEmpty()) return;
                    Map<String, Object> m = new HashMap<>();
                    m.put("uid", Fb.uid());
                    m.put("name", Fb.myName());
                    m.put("text", txt);
                    m.put("app", "friendbook-android");
                    m.put("createdAt", FieldValue.serverTimestamp());
                    Fb.db().collection("feedback").add(m);
                    Ui.toast(this, "Thanks for your feedback!");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onBackPressed() {
        if (active != TAB_HOME) select(TAB_HOME);
        else super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Fb.ensureMeFresh();
        Presence.init();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (notifReg != null) notifReg.remove();
        if (chatReg != null) chatReg.remove();
        if (reqReg != null) reqReg.remove();
        App.instance.setShakeListener(null);
    }

}
