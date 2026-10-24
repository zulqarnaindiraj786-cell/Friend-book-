package com.example.frienbook.admin.frag;

import android.app.Fragment;
import android.content.Intent;
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

import com.example.frienbook.core.Admin;
import com.example.frienbook.core.Fb;
import com.example.frienbook.core.Format;
import com.example.frienbook.core.Ui;
import com.example.frienbook.post.FullscreenMediaActivity;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.WriteBatch;

import java.util.ArrayList;
import java.util.List;

/**
 * Admin Tab 3: Rs-100 blue-tick payment requests — screenshot preview + Approve/Reject.
 * Approve ⇒ users/{uid}.isVerified = true instantly.
 */
public class AdminPaymentsFragment extends Fragment {

    private final List<DocumentSnapshot> items = new ArrayList<>();
    private Adapter adapter;
    private ListenerRegistration reg;
    private TextView empty;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inf, ViewGroup container, Bundle b) {
        android.app.Activity a = getActivity();
        LinearLayout root = Ui.col(a);
        root.setBackgroundColor(Ui.bg(a));

        empty = Ui.tv(a, "", 14, Ui.sub(a));
        empty.setGravity(Gravity.CENTER);
        Ui.pad(empty, 24, 50, 24, 0);
        root.addView(empty);

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
        reg = Fb.db().collection("blueTickPayments")
                .orderBy("date", Query.Direction.DESCENDING).limit(100)
                .addSnapshotListener((snap, err) -> {
                    items.clear();
                    if (snap != null) items.addAll(snap.getDocuments());
                    adapter.notifyDataSetChanged();
                    empty.setText("No payment requests yet.\nUsers pay Rs 100 via JazzCash/Easypaisa and upload a screenshot.");
                    empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                });
    }

    @Override
    public void onStop() {
        super.onStop();
        if (reg != null) reg.remove();
    }

    private void approve(DocumentSnapshot d) {
        android.app.Activity a = getActivity();
        if (a == null) return;
        String uid = d.getString("userId");
        if (uid == null) return;
        Ui.confirm(a, "Approve Rs 100 payment?",
                "The blue tick will turn on for this user instantly.", "Approve ✓", () -> {
                    WriteBatch batch = Fb.db().batch();
                    batch.update(d.getReference(), Fb.map(
                            "status", "approved",
                            "approvedBy", Fb.uid(),
                            "approvedAt", FieldValue.serverTimestamp()));
                    batch.set(Fb.db().collection("users").document(uid),
                            Fb.map("isVerified", true),
                            com.google.firebase.firestore.SetOptions.merge());
                    batch.commit().addOnSuccessListener(x -> {
                        Admin.syncAuthorVerifiedPublic(uid, true);
                        Ui.toast(a, "✓ Approved — blue tick ON");
                    }).addOnFailureListener(e -> Ui.toast(a, "Failed: " + e.getMessage()));
                });
    }

    private void reject(DocumentSnapshot d) {
        android.app.Activity a = getActivity();
        if (a == null) return;
        Ui.confirm(a, "Reject this payment?", "The user can re-submit a new screenshot.",
                "Reject", () -> {
                    d.getReference().update(Fb.map(
                            "status", "rejected",
                            "rejectedBy", Fb.uid(),
                            "rejectedAt", FieldValue.serverTimestamp()));
                    Ui.toast(a, "Rejected");
                });
    }

    class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        @Override public int getItemCount() { return items.size(); }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            android.app.Activity a = getActivity();
            LinearLayout col = Ui.col(a);
            col.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            col.setBackgroundColor(Ui.card(a));
            Ui.pad(col, 12, 10, 12, 10);

            LinearLayout top = Ui.row(a);
            ImageView shot = new ImageView(a);
            shot.setScaleType(ImageView.ScaleType.CENTER_CROP);
            int w = Ui.dp(a, 84);
            top.addView(shot, new LinearLayout.LayoutParams(w, w));
            LinearLayout mid = Ui.col(a);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -2, 1);
            mp.leftMargin = Ui.dp(a, 12);
            TextView email = Ui.tvBold(a, "", 14, Ui.txt(a));
            TextView meta = Ui.tv(a, "", 12, Ui.sub(a));
            TextView status = Ui.tvBold(a, "", 13, Ui.ORANGE);
            status.setPadding(0, Ui.dp(a, 4), 0, 0);
            mid.addView(email);
            mid.addView(meta);
            mid.addView(status);
            top.addView(mid, mp);
            col.addView(top);

            LinearLayout btns = Ui.row(a);
            btns.setPadding(0, Ui.dp(a, 10), 0, 0);
            TextView approve = Ui.btn(a, "✓ Approve tick");
            approve.setBackground(Ui.round(a, Ui.GREEN, 8));
            TextView reject = Ui.btnGrey(a, "✕ Reject");
            reject.setBackground(Ui.round(a, Ui.RED, 8));
            reject.setTextColor(0xFFFFFFFF);
            LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, Ui.dp(a, 38), 1);
            LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, Ui.dp(a, 38), 1);
            lp2.leftMargin = Ui.dp(a, 10);
            btns.addView(approve, lp1);
            btns.addView(reject, lp2);
            col.addView(btns);
            col.addView(Ui.divider(a));

            return new VH(col, shot, email, meta, status, approve, reject);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            android.app.Activity a = getActivity();
            DocumentSnapshot d = items.get(position);
            String emailS = Format.str(d.getData(), "userEmail");
            String uid = Format.str(d.getData(), "userId");
            String shot = Format.str(d.getData(), "screenshotUrl");
            String stat = Format.strOr(d.getData(), "status", "pending");
            h.email.setText(emailS.isEmpty() ? uid : emailS);
            h.meta.setText("uid " + (uid.length() > 10 ? uid.substring(0, 10) + "…" : uid)
                    + " · " + Format.ago(Format.ts(d.getData() == null ? null : d.get("date"))));
            h.status.setText("pending".equals(stat) ? "⏳ PENDING — needs your decision"
                    : "approved".equals(stat) ? "✓ Approved" : "✕ Rejected");
            h.status.setTextColor("pending".equals(stat) ? Ui.ORANGE
                    : "approved".equals(stat) ? Ui.GREEN : Ui.RED);
            if (!shot.isEmpty()) {
                Ui.img(a, h.shot, shot);
                h.shot.setOnClickListener(v -> {
                    Intent i = new Intent(a, FullscreenMediaActivity.class);
                    i.putExtra("type", "image");
                    i.putExtra("url", shot);
                    a.startActivity(i);
                });
            }
            boolean pending = "pending".equals(stat);
            h.approve.setEnabled(pending);
            h.reject.setEnabled(pending);
            h.approve.setAlpha(pending ? 1f : 0.4f);
            h.reject.setAlpha(pending ? 1f : 0.4f);
            h.approve.setOnClickListener(v -> { if (pending) approve(d); });
            h.reject.setOnClickListener(v -> { if (pending) reject(d); });
        }

        class VH extends RecyclerView.ViewHolder {
            ImageView shot;
            TextView email, meta, status, approve, reject;
            VH(View v, ImageView s, TextView e, TextView m, TextView st, TextView ap, TextView rj) {
                super(v);
                shot = s; email = e; meta = m; status = st; approve = ap; reject = rj;
            }
        }
    }
}
