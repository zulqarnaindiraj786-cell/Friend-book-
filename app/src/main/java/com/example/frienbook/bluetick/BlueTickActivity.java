package com.example.frienbook.bluetick;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.example.frienbook.core.Admin;
import com.example.frienbook.core.Fb;
import com.example.frienbook.core.Icons;
import com.example.frienbook.core.Ui;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;

import java.util.HashMap;
import java.util.Map;

/**
 * 🔵 Blue Tick — two real ways (Facebook-style):
 *  A) FREE: invite 5 friends → automatic blue tick (referrals collection, live progress)
 *  B) Rs 100: JazzCash/Easypaisa transfer → upload payment screenshot → admin approves.
 */
public class BlueTickActivity extends Activity {

    private static final int PICK_SCREENSHOT = 71;

    private TextView statusT, progressT, countedT, payStatusT, numbersT;
    private ProgressBar bar;
    private ListenerRegistration refReg, payReg, userReg;
    private boolean busy;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        root.addView(Ui.pageBar(this, "Blue tick ✓"));

        ScrollView sv = new ScrollView(this);
        LinearLayout body = Ui.col(this);
        Ui.pad(body, 12, 10, 12, 24);

        // ---------- hero ----------
        LinearLayout hero = Ui.col(this);
        hero.setGravity(Gravity.CENTER);
        hero.setBackground(Ui.round(this, Ui.card(this), 14));
        Ui.pad(hero, 16, 20, 16, 18);
        ImageView badge = new ImageView(this);
        badge.setImageResource(Icons.VERIFIED); // keep native two-tone colors (blue badge, white check)
        int bs = Ui.dp(this, 72);
        hero.addView(badge, new LinearLayout.LayoutParams(bs, bs));
        hero.addView(badge);
        statusT = Ui.tvBold(this, "Loading…", 18, Ui.txt(this));
        statusT.setGravity(Gravity.CENTER);
        statusT.setPadding(0, Ui.dp(this, 10), 0, 0);
        hero.addView(statusT);
        TextView sub = Ui.tv(this, "The blue tick shows your account is verified on friendbook.", 13, Ui.sub(this));
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), 0);
        hero.addView(sub);
        body.addView(hero);
        body.addView(sp(12));

        // ---------- OPTION A: 5 referrals ----------
        LinearLayout cardA = Ui.col(this);
        cardA.setBackground(Ui.round(this, Ui.card(this), 14));
        Ui.pad(cardA, 14, 14, 14, 14);
        LinearLayout headA = Ui.row(this);
        headA.addView(Icons.chipIcon(this, Icons.USER_PLUS, Icons.BLUE));
        TextView tA = Ui.tvBold(this, "Option A — FREE blue tick", 16, Ui.txt(this));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1);
        tp.leftMargin = Ui.dp(this, 12);
        headA.addView(tA, tp);
        cardA.addView(headA);

        TextView dA = Ui.tv(this,
                "Invite 5 friends to friendbook. When all 5 create an account from your invite link, the blue tick turns on AUTOMATICALLY.",
                13, Ui.sub(this));
        dA.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 10));
        cardA.addView(dA);

        progressT = Ui.tvBold(this, "Progress: 0/5 friends", 14, Ui.txt(this));
        cardA.addView(progressT);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(Admin.REFERRALS_FOR_TICK);
        bar.setProgress(0);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 10));
        bp.topMargin = Ui.dp(this, 6);
        cardA.addView(bar, bp);

        countedT = Ui.tv(this, "", 12, Ui.sub(this));
        countedT.setPadding(0, Ui.dp(this, 6), 0, 0);
        cardA.addView(countedT);

        LinearLayout btnRow = Ui.row(this);
        btnRow.setPadding(0, Ui.dp(this, 12), 0, 0);
        TextView share = Ui.btn(this, "📤 Share invite link");
        TextView copy = Ui.btnGrey(this, "Copy link");
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1);
        btnRow.addView(share, b1);
        LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1);
        b2.leftMargin = Ui.dp(this, 10);
        btnRow.addView(copy, b2);
        cardA.addView(btnRow);

        share.setOnClickListener(v -> shareInvite());
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("invite", Admin.inviteLink(Fb.uid())));
            Ui.toast(this, "Invite link copied ✓");
        });
        body.addView(cardA);
        body.addView(sp(12));

        // ---------- OPTION B: Rs 100 payment ----------
        LinearLayout cardB = Ui.col(this);
        cardB.setBackground(Ui.round(this, Ui.card(this), 14));
        Ui.pad(cardB, 14, 14, 14, 14);
        LinearLayout headB = Ui.row(this);
        headB.addView(Icons.chipIcon(this, Icons.BANKNOTE, Ui.GREEN));
        TextView tB = Ui.tvBold(this, "Option B — Rs 100 = blue tick", 16, Ui.txt(this));
        LinearLayout.LayoutParams tp2 = new LinearLayout.LayoutParams(0, -2, 1);
        tp2.leftMargin = Ui.dp(this, 12);
        headB.addView(tB, tp2);
        cardB.addView(headB);

        numbersT = Ui.tv(this, "Loading payment numbers…", 14, Ui.txt(this));
        numbersT.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        numbersT.setTextIsSelectable(true);
        cardB.addView(numbersT);

        TextView steps = Ui.tv(this,
                "1️⃣ Send Rs 100 via JazzCash/Easypaisa\n"
                        + "2️⃣ Take a screenshot of the success receipt\n"
                        + "3️⃣ Upload the screenshot below\n"
                        + "4️⃣ Admin approves → blue tick turns on ✓",
                13, Ui.sub(this));
        steps.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 10));
        cardB.addView(steps);

        TextView upload = Ui.btn(this, "📸 Upload payment screenshot");
        cardB.addView(upload, new LinearLayout.LayoutParams(-1, Ui.dp(this, 44)));
        upload.setOnClickListener(v -> pickScreenshot());

        payStatusT = Ui.tvBold(this, "", 13, Ui.sub(this));
        payStatusT.setPadding(0, Ui.dp(this, 10), 0, 0);
        cardB.addView(payStatusT);
        body.addView(cardB);

        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        loadPaymentNumbers();
    }

    private View sp(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, Ui.dp(this, h)));
        return v;
    }

    /** Payment numbers live in Firestore: config/payments {jazzcash, easypaisa}. */
    private void loadPaymentNumbers() {
        Fb.db().collection("config").document("payments").get().addOnSuccessListener(d -> {
            String jazz = "0301-2345678";
            String easypaisa = "0345-6789012";
            if (d.exists()) {
                String j = d.getString("jazzcash");
                String e = d.getString("easypaisa");
                if (j != null && !j.isEmpty()) jazz = j;
                if (e != null && !e.isEmpty()) easypaisa = e;
            }
            numbersT.setText("💳 JazzCash:  " + jazz + "\n💳 Easypaisa:  " + easypaisa
                    + "\n(Account title: friendbook)");
        }).addOnFailureListener(e ->
                numbersT.setText("💳 JazzCash: 0301-2345678\n💳 Easypaisa: 0345-6789012"));
    }

    @Override
    protected void onStart() {
        super.onStart();
        String me = Fb.uid();
        if (me == null) { finish(); return; }

        // my verification state
        userReg = Fb.db().collection("users").document(me)
                .addSnapshotListener((d, err) -> {
                    if (d == null || !d.exists()) return;
                    boolean verified = Boolean.TRUE.equals(d.getBoolean("isVerified"));
                    statusT.setText(verified
                            ? "✓ You have the blue tick!"
                            : "No blue tick yet — earn it below");
                    statusT.setTextColor(verified ? Ui.BLUE : Ui.txt(this));
                });

        // live referral progress (auto-grant also happens globally in MainActivity)
        refReg = Fb.db().collection("referrals").whereEqualTo("refBy", me)
                .addSnapshotListener((snap, err) -> {
                    int n = snap == null ? 0 : snap.size();
                    int capped = Math.min(n, Admin.REFERRALS_FOR_TICK);
                    bar.setProgress(capped);
                    progressT.setText("Progress: " + capped + "/"
                            + Admin.REFERRALS_FOR_TICK + " friends" + (n > 5 ? "  (" + n + " total invites!)" : ""));
                    progressT.setTextColor(capped >= Admin.REFERRALS_FOR_TICK ? Ui.GREEN : Ui.txt(this));
                    countedT.setText(n == 0
                            ? "Your invite link: " + Admin.inviteLink(me)
                            : capped >= Admin.REFERRALS_FOR_TICK ? "🎉 Complete! Your blue tick is on."
                            : "Keep sharing — " + (Admin.REFERRALS_FOR_TICK - capped) + " more to go!");
                });

        // latest payment request status
        payReg = Fb.db().collection("blueTickPayments")
                .whereEqualTo("userId", me)
                .orderBy("date", Query.Direction.DESCENDING)
                .limit(1)
                .addSnapshotListener((snap, err) -> {
                    if (snap == null || snap.isEmpty()) { payStatusT.setText(""); return; }
                    DocumentSnapshot d = snap.getDocuments().get(0);
                    String s = d.getString("status");
                    if ("pending".equals(s)) {
                        payStatusT.setText("⏳ Payment under review — screenshot submitted");
                        payStatusT.setTextColor(Ui.ORANGE);
                    } else if ("approved".equals(s)) {
                        payStatusT.setText("✓ Payment approved — blue tick active!");
                        payStatusT.setTextColor(Ui.GREEN);
                    } else {
                        payStatusT.setText("❌ Last payment was rejected — you can upload again");
                        payStatusT.setTextColor(Ui.RED);
                    }
                });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (refReg != null) refReg.remove();
        if (payReg != null) payReg.remove();
        if (userReg != null) userReg.remove();
    }

    private void shareInvite() {
        String link = Admin.inviteLink(Fb.uid());
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT,
                "Join me on friendbook — the fast lite social app! 🚀\n\nSign up with my invite link and I'll earn towards my free blue tick ✓\n\n" + link);
        startActivity(Intent.createChooser(i, "Share your invite link"));
    }

    private void pickScreenshot() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        startActivityForResult(Intent.createChooser(i, "Pick payment screenshot"), PICK_SCREENSHOT);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (busy || req != PICK_SCREENSHOT || res != RESULT_OK || data == null || data.getData() == null) return;
        busy = true;
        Uri uri = data.getData();
        payStatusT.setText("⏳ Uploading screenshot…");
        String path = "payments/" + Fb.uid() + "/" + System.currentTimeMillis() + ".jpg";
        Fb.upload(path, uri)
                .addOnSuccessListener(url -> {
                    Map<String, Object> pay = new HashMap<>();
                    pay.put("userId", Fb.uid());
                    pay.put("userEmail", Fb.auth().getCurrentUser() != null
                            ? Fb.auth().getCurrentUser().getEmail() : "");
                    pay.put("screenshotUrl", url.toString());
                    pay.put("status", "pending");
                    pay.put("date", FieldValue.serverTimestamp());
                    Fb.db().collection("blueTickPayments").add(pay)
                            .addOnSuccessListener(x -> {
                                busy = false;
                                Ui.toast(this, "Screenshot sent for review ✓");
                            })
                            .addOnFailureListener(e -> {
                                busy = false;
                                payStatusT.setText("Couldn't submit — try again");
                            });
                })
                .addOnFailureListener(e -> {
                    busy = false;
                    payStatusT.setText("Upload failed: " + e.getMessage());
                });
    }
}
