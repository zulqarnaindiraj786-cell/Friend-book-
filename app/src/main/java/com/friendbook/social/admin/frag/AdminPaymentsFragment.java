package com.friendbook.social.admin.frag;

import android.app.AlertDialog;
import android.app.Fragment;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.friendbook.social.core.Admin;
import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Format;
import com.friendbook.social.core.Icons;
import com.friendbook.social.core.Ui;
import com.friendbook.social.d1.documents.DocumentReference;
import com.friendbook.social.d1.documents.DocumentSnapshot;
import com.friendbook.social.d1.documents.FieldValue;
import com.friendbook.social.d1.documents.ListenerRegistration;
import com.friendbook.social.d1.documents.Query;
import com.friendbook.social.d1.documents.SetOptions;
import com.friendbook.social.d1.documents.WriteBatch;


/**
 * Admin Payments tab — ONE unified list of every payment type:
 *   tick_blue / tick_gold / tick_diamond  → grant that tick level
 *   boost_profile / boost_post            → activate boost for 24h
 *   subscription                          → activate subscription for 30 days
 * Screenshot thumbnail tappable, Approve / Reject buttons.
 */
public class AdminPaymentsFragment extends Fragment {

    private LinearLayout list;
    private TextView countT;
    private ListenerRegistration reg;
    private com.friendbook.social.core.CursorPager pages;
    private LinearLayout cards;
    private TextView more;
    private String actor;
    private boolean started;
    private final java.util.Set<String> pending=new java.util.HashSet<>();
    private final java.util.Map<String,String> confirmed=new java.util.HashMap<>();
    private final java.util.List<DocumentSnapshot> loaded=new java.util.ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inf, ViewGroup container, Bundle b) {
        android.app.Activity a = getActivity();
        ScrollView sv = new ScrollView(a);
        list = Ui.col(a);
        list.setBackgroundColor(Ui.bg(a));
        Ui.pad(list, 12, 10, 12, 24);
        sv.addView(list);

        countT = Ui.tv(a, "✓ Badge & Verification Requests", 12, Ui.sub(a));
        Ui.pad(countT, 2, 0, 2, 8);
        list.addView(countT);
        cards=Ui.col(a);list.addView(cards);
        LinearLayout controls=Ui.row(a);list.addView(controls);
        TextView refresh=Ui.tvBold(a,"Refresh requests",13,Ui.BLUE);Ui.pad(refresh,8,10,8,10);
        refresh.setOnClickListener(v->startPages());controls.addView(refresh);
        more=Ui.tvBold(a,"Load 10 more",13,Ui.BLUE);Ui.pad(more,8,10,8,10);
        more.setOnClickListener(v->{if(pages!=null)pages.next();});controls.addView(more);
        return sv;
    }

    @Override
    public void onStart() {
        super.onStart();
        android.app.Activity a=getActivity();
        if(a==null || !Admin.isAdmin() || Fb.uid()==null)return;
        if(actor!=null && !actor.equals(Fb.uid())){a.finish();return;}
        actor=Fb.uid();started=true;startPages();
    }

    private void startPages() {
        if(!started || !java.util.Objects.equals(actor,Fb.uid()) || getActivity()==null)return;
        if(reg!=null){reg.remove();reg=null;}
        if(pages!=null){pages.close();pages=null;}
        loaded.clear();renderRequests();
        Query query=Fb.db().collection("payments").orderBy("date",Query.Direction.DESCENDING);
        pages=new com.friendbook.social.core.CursorPager(query,10,"admin-payments",(docs,hasMore,busy,error)->{
            if(!started || !java.util.Objects.equals(actor,Fb.uid()) || getActivity()==null)return;
            more.setText(busy?"Loading…":!error.isEmpty()?"Retry page":hasMore?"Load 10 more":"All loaded");
            more.setEnabled(!busy && hasMore);
            if(!busy && (error.isEmpty() || !docs.isEmpty())){loaded.clear();loaded.addAll(docs);renderRequests();}
            if(!error.isEmpty())Ui.toast(getActivity(),error);
        });
        final com.friendbook.social.core.CursorPager observed=pages;
        observed.startObserved();
        reg=query.limit(10).addSnapshotListener((snap,err)->{
            // A removed observer can still have a queued callback after refresh.
            if(!started || !java.util.Objects.equals(actor,Fb.uid()) || pages!=observed)return;
            if(err!=null){observed.observedError();return;}
            if(snap!=null)observed.acceptObservedFirstPage(snap);
        });
    }

    private void renderRequests() {
        android.app.Activity a=getActivity();if(!started || a==null || cards==null)return;
        cards.removeAllViews();countT.setText(loaded.size()+" loaded request(s) — newest first within loaded pages");
        if(loaded.isEmpty())cards.addView(Ui.tv(a,"No requests in loaded pages.",13,Ui.sub(a)));
        for(DocumentSnapshot doc:loaded)cards.addView(row(a,doc));
    }

    @Override
    public void onStop() {
        super.onStop();started=false;
        if(reg!=null){reg.remove();reg=null;}
        if(pages!=null){pages.close();pages=null;}
    }

    private View row(android.app.Activity a, DocumentSnapshot d) {
        java.util.Map<String, Object> m = d.getData();
        String userId = Format.str(m, "userId");
        String email = Format.str(m, "userEmail");
        String type = Format.str(m, "type");
        String status = confirmed.containsKey(d.getId()) ? confirmed.get(d.getId()) : Format.strOr(m, "status", "pending");
        String shot = Format.str(m, "screenshotUrl");
        long amount = Format.intOf(m, "amount");
        String currency = Format.strOr(m, "currency", "PKR");
        long date = Format.ts(m == null ? null : m.get("date"));

        LinearLayout card = Ui.col(a);
        card.setBackground(Ui.round(a, Ui.card(a), 14));
        Ui.pad(card, 12, 10, 12, 10);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.setMargins(0, 0, 0, Ui.dp(a, 10));
        card.setLayoutParams(cp);

        LinearLayout top = Ui.row(a);
        ImageView thumb = new ImageView(a);
        int ts = Ui.dp(a, 64);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ts, ts);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackground(Ui.round(a, Ui.inputBg(a), 10));
        thumb.setClipToOutline(true);
        Ui.img(a, thumb, shot);
        thumb.setOnClickListener(v -> {
            Intent i = new Intent(a, com.friendbook.social.post.FullscreenMediaActivity.class);
            i.putExtra("url", shot);
            a.startActivity(i);
        });
        top.addView(thumb, tp);

        LinearLayout info = Ui.col(a);
        info.setPadding(Ui.dp(a, 12), 0, 0, 0);
        info.addView(Ui.tvBold(a, typeLabel(type) + "  ·  Free Community Review",
                14, Ui.txt(a)));
        info.addView(Ui.tv(a, email, 12, Ui.sub(a)));
        info.addView(Ui.tv(a, "uid: " + userId, 10, Ui.sub(a)));
        info.addView(Ui.tv(a, Format.ago(date), 11, Ui.sub(a)));
        top.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(top);

        TextView statusT = Ui.roundChip(a,
                "pending".equals(status) ? "⏳ PENDING" : "approved".equals(status) ? "✅ APPROVED" : "❌ REJECTED",
                "pending".equals(status) ? 0x22F7B928 : "approved".equals(status) ? 0x2231A24C : 0x22E41E3F,
                "pending".equals(status) ? Ui.ORANGE : "approved".equals(status) ? Ui.GREEN : Ui.RED, 11);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.setMargins(0, Ui.dp(a, 8), 0, 0);
        card.addView(statusT, sp);

        boolean freeReview = amount == 0 && "FREE".equals(currency)
                && ("tick_blue".equals(type) || "tick_gold".equals(type) || "tick_diamond".equals(type));
        boolean manualSubscription = "subscription".equals(type) && amount > 0
                && !Format.str(m, "subscriptionId").isEmpty() && currency.matches("[A-Z]{3}") && !"FREE".equals(currency);
        if ("pending".equals(status) && !freeReview) {
            TextView warning = Ui.tv(a, manualSubscription
                    ? "Verify the existing payment outside the app before approving. This does not charge the customer."
                    : "This historical request is not eligible for approval here.", 12, Ui.sub(a));
            Ui.pad(warning, 0, 8, 0, 0);
            card.addView(warning);
        }
        if ("pending".equals(status) && (freeReview || manualSubscription)) {
            LinearLayout btns = Ui.row(a);
            btns.setPadding(0, Ui.dp(a, 10), 0, 0);
            TextView approve = Ui.btn(a, "✓ Approve");
            approve.setEnabled(!pending.contains(d.getId()));
            approve.setOnClickListener(v -> confirm(a, "Approve " + typeLabel(type) + "?",
                    manualSubscription ? "Approve only if you confirmed the existing payment externally. Cloudflare will activate the linked subscription for 30 days."
                            : "The feature activates instantly for this user.", () -> approve(d, m)));
            TextView reject = Ui.btnGrey(a, "✕ Reject");
            reject.setEnabled(!pending.contains(d.getId()));
            reject.setOnClickListener(v -> confirm(a, "Reject?", manualSubscription ? "Reject this existing payment request without activating the subscription?" : "The applicant may submit another free review later.",
                    () -> reject(d)));
            LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, Ui.dp(a, 38), 1);
            LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(0, Ui.dp(a, 38), 1);
            b2.leftMargin = Ui.dp(a, 8);
            btns.addView(approve, b1);
            btns.addView(reject, b2);
            card.addView(btns);
        }
        return card;
    }

    private String typeLabel(String type) {
        if (type == null) return "Payment";
        switch (type) {
            case "tick_blue": return "✓ Blue Tick";
            case "tick_gold": return "✨ Gold Tick";
            case "tick_diamond": return "💎 Diamond Tick";
            case "subscription": return "⭐ Subscription";
            default: return type;
        }
    }

    private void confirm(android.app.Activity a, String t, String msg, Runnable go) {
        new AlertDialog.Builder(a).setTitle(t).setMessage(msg)
                .setPositiveButton("Yes", (d, w) -> go.run())
                .setNegativeButton("Cancel", null).show();
    }

    private void reject(DocumentSnapshot d) {
        final String owner=actor,id=d.getId();
        if(!started || !Admin.isAdmin() || !java.util.Objects.equals(owner,Fb.uid()) || confirmed.containsKey(id) || !pending.add(id))return;
        renderRequests();
        d.getReference().update("status","rejected","rejectedBy",owner,"rejectedAt",FieldValue.serverTimestamp())
                .addOnCompleteListener(task->{
                    pending.remove(id);if(!java.util.Objects.equals(owner,Fb.uid()))return;
                    if(task.isSuccessful())confirmed.put(id,"rejected");
                    if(started && getActivity()!=null){renderRequests();Ui.toast(getActivity(),task.isSuccessful()?"Request rejected":"Couldn't reject request. Please retry.");}
                });
    }

    /** Approve = write payment approved + activate the purchased thing, in one batch. */
    private void approve(DocumentSnapshot d, java.util.Map<String, Object> m) {
        final String owner=actor,id=d.getId();
        String type = Format.str(m, "type");
        String uid = Format.str(m, "userId");
        if(uid.isEmpty()){if(started && getActivity()!=null)Ui.toast(getActivity(),"Missing user for request");return;}
        if(!started || !Admin.isAdmin() || !java.util.Objects.equals(owner,Fb.uid()) || confirmed.containsKey(id) || !pending.add(id))return;
        renderRequests();
        WriteBatch batch = Fb.db().batch();
        batch.update(d.getReference(), "status", "approved",
                "approvedBy", owner, "approvedAt", FieldValue.serverTimestamp());

        if (type != null && type.startsWith("tick_")) {
            String level = type.substring(5);
            batch.set(Fb.db().collection("users").document(uid),
                    Fb.map("tickLevel", level, "isVerified", true), SetOptions.merge());
        } else if ("subscription".equals(type)) {
            String subId = Format.str(m, "subscriptionId");
            if (!subId.isEmpty()) {
                DocumentReference su = Fb.db().collection("subscriptions").document(subId);
                // The Worker verifies the linked subscriber and sets the expiry atomically.
                batch.update(su, "status", "active");
            }
        }
        batch.commit().addOnCompleteListener(task -> {
            pending.remove(id);if(!java.util.Objects.equals(owner,Fb.uid()))return;
            if(task.isSuccessful()) {
                confirmed.put(id,"approved");
                // Generic document writes cannot modify another author's content.
                // Do not launch four scans and rejected batches after their approval.
                if(type != null && type.startsWith("tick_") && owner.equals(uid))Admin.syncAuthorTickLevel(uid,type.substring(5));
                notifyUser(uid,type);
            }
            if(started && getActivity()!=null) {
                renderRequests();Ui.toast(getActivity(),task.isSuccessful()?"Approved ✓ — activated":"Approval failed. Please retry.");
            }
        });
    }

    private void notifyUser(String uid, String type) {
        String text;
        if (type != null && type.startsWith("tick_"))
            text = "✓ Approved! Your " + Admin.tickLabel(type.substring(5)) + " is now live 🎉";
        else text = "⭐ Your request was approved. Enjoy!";
        // Use the authenticated reviewer's identity; a synthetic team UID is not
        // authorized to write notifications and would silently fail.
        com.friendbook.social.core.Notify.send(uid, "payment", text);
    }
}
