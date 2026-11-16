package com.friendbook.social.admin.frag;

import android.app.Fragment;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.friendbook.social.core.Admin;
import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Format;
import com.friendbook.social.core.Icons;
import com.friendbook.social.core.Ui;
import com.friendbook.social.models.Models;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin Tab 2: referrals overview — who invited how many, with live progress to the
 * free blue tick (5/5) and a detail pop-up of invitees.
 */
public class AdminReferralsFragment extends Fragment {

    static class Inviter {
        String uid, name = "…", email = "", photo = "";
        int count;
        boolean verified;
        long latest;
        List<String> invitees = new ArrayList<>();
    }

    private final List<Inviter> inviters = new ArrayList<>();
    private final Map<String, Inviter> byUid = new HashMap<>();
    private Adapter adapter;
    private ListenerRegistration reg;
    private TextView summary;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inf, ViewGroup container, Bundle b) {
        android.app.Activity a = getActivity();
        LinearLayout root = Ui.col(a);
        root.setBackgroundColor(Ui.bg(a));

        summary = Ui.tv(a, "", 13, Ui.sub(a));
        Ui.pad(summary, 14, 10, 14, 4);
        root.addView(summary);

        RecyclerView rv = new RecyclerView(a);
        rv.setLayoutManager(new LinearLayoutManager(a));
        adapter = new Adapter();
        rv.setAdapter(adapter);
        root.addView(rv, new LinearLayout.LayoutParams(-1, 0, 1));
        return root;
    }

    @Override
    public void onStart() {
        super.onStart();
        com.friendbook.social.core.D1Snap.read(com.friendbook.social.core.DocQuery.of("referrals").orderBy("date",com.friendbook.social.core.DocQuery.DESC).limit(400).build(),snap->{
                    if (snap == null) return;
                    Map<String, Integer> counts = new HashMap<>();
                    Map<String, Long> latest = new HashMap<>();
                    Map<String, List<String>> kids = new HashMap<>();
                    int total = snap.size();
                    for (com.friendbook.social.core.Doc d : snap) {
                        String by = Format.str(d.getData(), "refBy");
                        String to = Format.str(d.getData(), "refTo");
                        if (by.isEmpty()) continue;
                        Integer c = counts.get(by);
                        counts.put(by, (c == null ? 0 : c) + 1);
                        long t = Format.ts(d.getData() == null ? null : d.get("date"));
                        Long cur = latest.get(by);
                        if (cur == null || t > cur) latest.put(by, t);
                        if (!kids.containsKey(by)) kids.put(by, new ArrayList<>());
                        kids.get(by).add(to);
                    }
                    rebuild(counts, latest, kids, total);
                });
    }

    int pendingNames = 0;

    private void rebuild(Map<String, Integer> counts, Map<String, Long> latest,
                         Map<String, List<String>> kids, int total) {
        android.app.Activity a = getActivity();
        if (a == null) return;
        byUid.clear();
        inviters.clear();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            Inviter it = new Inviter();
            it.uid = e.getKey();
            it.count = e.getValue();
            it.latest = latest.get(e.getKey()) == null ? 0 : latest.get(e.getKey());
            it.invitees = kids.get(e.getKey());
            byUid.put(e.getKey(), it);
            inviters.add(it);
        }
        inviters.sort((x, y) -> Integer.compare(y.count, x.count));
        adapter.notifyDataSetChanged();
        summary.setText(total + " total referral signups · " + inviters.size() + " inviters");

        // resolve names (cached-ish lookups)
        pendingNames = inviters.size();
        for (Inviter it : inviters) {
            Fb.getUser(it.uid).addOnSuccessListener(u -> {
                if (u != null) {
                    it.name = u.displayName;
                    it.email = u.email;
                    it.photo = u.photoUrl;
                    it.verified = u.isVerified;
                }
                if (--pendingNames <= 0) {
                    int done = 0;
                    for (Inviter i2 : inviters) if (i2.count >= Admin.REFERRALS_BLUE) done++;
                    summary.setText(total + " referral signups · " + inviters.size() + " inviters · "
                            + done + " reached 5/5 ✓");
                }
                adapter.notifyDataSetChanged();
            });
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        if (reg != null) reg.remove();
    }

    private void detail(Inviter it) {
        android.app.Activity a = getActivity();
        if (a == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append(it.count).append("/").append(Admin.REFERRALS_BLUE).append(" referrals");
        if (it.count >= Admin.REFERRALS_BLUE) sb.append(" — free tick earned ✓");
        sb.append("\n\nInvitees:\n");
        java.util.Set<String> seen = new java.util.HashSet<>(it.invitees);
        for (String uid : seen) sb.append("• ").append(uid, 0, Math.min(10, uid.length())).append("…\n");
        Ui.info(a, it.name, sb.toString().trim());
    }

    class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        @Override public int getItemCount() { return inviters.size(); }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            android.app.Activity a = getActivity();
            LinearLayout row = Ui.row(a);
            row.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            row.setBackgroundColor(Ui.card(a));
            Ui.pad(row, 12, 10, 12, 10);

            ImageView av = new ImageView(a);
            int s = Ui.dp(a, 46);
            row.addView(av, new LinearLayout.LayoutParams(s, s));

            LinearLayout mid = Ui.col(a);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -2, 1);
            mp.leftMargin = Ui.dp(a, 12);
            LinearLayout nameRow = Ui.row(a);
            TextView name = Ui.tvBold(a, "", 15, Ui.txt(a));
            ImageView tick = new ImageView(a);
            tick.setImageResource(com.friendbook.social.R.drawable.ic_verified);
            tick.setVisibility(View.GONE);
            int ts = Ui.dp(a, 15);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ts, ts);
            tlp.leftMargin = Ui.dp(a, 5);
            nameRow.addView(name);
            nameRow.addView(tick, tlp);
            TextView sub = Ui.tv(a, "", 12, Ui.sub(a));
            mid.addView(nameRow);
            mid.addView(sub);
            row.addView(mid, mp);

            TextView prog = Ui.tvBold(a, "", 16, Ui.BLUE);
            prog.setGravity(Gravity.CENTER);
            row.addView(prog);
            return new VH(row, av, name, tick, sub, prog);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            android.app.Activity a = getActivity();
            Inviter it = inviters.get(position);
            Ui.avatar(a, h.av, it.photo);
            h.name.setText(it.name);
            h.tick.setVisibility(it.verified ? View.VISIBLE : View.GONE);
            h.sub.setText(it.email + " · last invite " + Format.ago(it.latest));
            h.prog.setText(it.count + "/" + Admin.REFERRALS_BLUE);
            h.prog.setTextColor(it.count >= Admin.REFERRALS_BLUE ? Ui.GREEN : Ui.BLUE);
            h.itemView.setOnClickListener(v -> detail(it));
        }

        class VH extends RecyclerView.ViewHolder {
            ImageView av, tick;
            TextView name, sub, prog;
            VH(View v, ImageView a, TextView n, ImageView t, TextView s, TextView p) {
                super(v);
                av = a; name = n; tick = t; sub = s; prog = p;
            }
        }
    }
}
