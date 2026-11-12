package com.example.frienbook.pay;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.frienbook.core.Admin;
import com.example.frienbook.core.Fb;
import com.example.frienbook.core.Icons;
import com.example.frienbook.core.Ui;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.SetOptions;

import java.util.HashMap;
import java.util.Map;

/**
 * Unified payment screen — every paid feature goes through here:
 *   type = tick_blue | tick_gold | tick_diamond | boost_profile | boost_post | subscription
 * Shows admin-set JazzCash/Easypaisa numbers (Firestore config/payments), the user uploads
 * the payment screenshot, and a pending record is written:
 *   - payments {type, amount, currency, screenshotUrl, status:"pending", date, createdMonth, ...}
 *   - boosts / subscriptions side docs created in "pending" state (activated on admin approve)
 */
public class PayRequestActivity extends Activity {

    private static final int RC_PICK = 42;

    private String type, targetId;
    private int amount;             // PRICE in Rs (or USD for subscriptions)
    private String currency = "PKR";
    private String title;

    private TextView statusT, jazzcashT, easypaisaT;
    private ImageView preview;
    private Uri picked;
    private ListenerRegistration cfgReg;
    private boolean busy;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);

        Intent i = getIntent();
        type = i.getStringExtra("type");
        targetId = i.getStringExtra("target_id"); // postId (boost_post) or creatorId (subscription)
        amount = i.getIntExtra("amount", 0);
        currency = i.getStringExtra("currency");
        title = i.getStringExtra("title");
        if (currency == null) currency = "PKR";
        if (type == null) { finish(); return; }

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        root.addView(Ui.pageBar(this, title == null ? "Payment" : title));
        root.addView(Ui.divider(this));

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        LinearLayout body = Ui.col(this);
        Ui.pad(body, 14, 14, 14, 20);
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        // amount hero
        LinearLayout hero = Ui.col(this);
        hero.setGravity(Gravity.CENTER);
        hero.setBackground(Ui.round(this, Ui.card(this), 14));
        Ui.pad(hero, 10, 18, 10, 18);
        TextView amt = Ui.tvBold(this, money(amount, currency), 30, Icons.BLUE);
        hero.addView(amt);
        hero.addView(Ui.tv(this, subtitle(), 13, Ui.sub(this)));
        body.addView(hero, Ui.lpw(-2));
        body.addView(Ui.divider(this));

        // payment numbers (live from config/payments)
        LinearLayout card = Ui.col(this);
        card.setBackground(Ui.round(this, Ui.card(this), 14));
        Ui.pad(card, 14, 12, 14, 12);
        LinearLayout h = rowWithIcon(Icons.BANKNOTE, "Pay on any of these accounts");
        card.addView(h);
        jazzcashT = numberRow(card, "JazzCash", "0301-2345678");
        easypaisaT = numberRow(card, "Easypaisa", "0345-6789012");
        TextView note = Ui.tv(this,
                "1) Send the exact amount\n2) Take a screenshot of the receipt\n3) Upload it below — admin approves within a few hours",
                12, Ui.sub(this));
        note.setPadding(0, Ui.dp(this, 8), 0, 0);
        card.addView(note);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.setMargins(0, Ui.dp(this, 12), 0, 0);
        body.addView(card, cp);

        // screenshot picker
        preview = new ImageView(this);
        preview.setBackground(Ui.roundStroke(this, Ui.inputBg(this), 12, Ui.div(this), 1));
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 160));
        pp.setMargins(0, Ui.dp(this, 14), 0, 0);
        preview.setOnClickListener(v -> pick());
        body.addView(preview, pp);

        TextView pickB = Ui.btnOutline(this, "📷 Upload payment screenshot", Icons.BLUE);
        LinearLayout.LayoutParams pb = new LinearLayout.LayoutParams(-1, -2);
        pb.setMargins(0, Ui.dp(this, 10), 0, 0);
        pickB.setOnClickListener(v -> pick());
        body.addView(pickB, pb);

        TextView submit = Ui.btn(this, "Submit for approval");
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.setMargins(0, Ui.dp(this, 10), 0, 0);
        submit.setOnClickListener(v -> submit());
        body.addView(submit, sp);

        statusT = Ui.tv(this, "", 13, Ui.sub(this));
        statusT.setGravity(Gravity.CENTER);
        statusT.setPadding(0, Ui.dp(this, 10), 0, 0);
        body.addView(statusT);

        setContentView(root);
        watchConfig();
    }

    private String money(int amt, String cur) {
        return "USD".equals(cur) ? "$" + amt : "Rs " + amt;
    }

    private String subtitle() {
        if (type != null && type.startsWith("tick_"))
            return Admin.tickLabel(type.substring(5)) + " — one-time";
        if ("boost_profile".equals(type)) return "Boost your profile for 24 hours";
        if ("boost_post".equals(type)) return "Boost this post — more reach";
        return "Fan subscription — monthly";
    }

    private LinearLayout rowWithIcon(int icon, String text) {
        LinearLayout r = Ui.row(this);
        r.addView(Icons.icon(this, icon, 20, Icons.BLUE));
        TextView t = Ui.tvBold(this, text, 14, Ui.txt(this));
        t.setPadding(Ui.dp(this, 8), 0, 0, 0);
        r.addView(t);
        return r;
    }

    private TextView numberRow(LinearLayout card, String label, String number) {
        LinearLayout r = Ui.row(this);
        r.setPadding(0, Ui.dp(this, 8), 0, 0);
        TextView l = Ui.tv(this, label, 14, Ui.sub(this));
        r.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
        TextView n = Ui.tvBold(this, number, 15, Ui.txt(this));
        r.addView(n);
        n.setOnClickListener(v -> copy(number));
        card.addView(r);
        return n;
    }

    private void copy(String s) {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("number", s));
        Ui.toast(this, "Copied: " + s);
    }

    private void watchConfig() {
        cfgReg = Fb.db().collection("config").document("payments")
                .addSnapshotListener((d, err) -> {
                    if (d == null || !d.exists()) return;
                    String j = d.getString("jazzcash");
                    String e = d.getString("easypaisa");
                    if (j != null && !j.isEmpty()) jazzcashT.setText(j);
                    if (e != null && !e.isEmpty()) easypaisaT.setText(e);
                });
    }

    private void pick() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        startActivityForResult(i, RC_PICK);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == RC_PICK && res == RESULT_OK && data != null && data.getData() != null) {
            picked = data.getData();
            preview.setImageURI(picked);
        }
    }

    private void submit() {
        if (busy) return;
        if (picked == null) { Ui.toast(this, "Upload your payment screenshot first"); return; }
        busy = true;
        statusT.setText("Uploading screenshot…");
        com.example.frienbook.core.CloudflareMedia.uploadPhoto(this, picked, null,
                url -> writeDocs(url),
                err -> {
                    busy = false;
                    statusT.setText("Upload failed: " + err);
                });
    }

    private void writeDocs(String screenshotUrl) {
        FirebaseFirestore db = Fb.db();
        String uid = Fb.uid();
        String email = Fb.me != null ? Fb.me.email : "";
        String month = Admin.monthKey();

        Map<String, Object> pay = new HashMap<>();
        pay.put("userId", uid);
        pay.put("userEmail", email == null ? "" : email);
        pay.put("type", type);
        pay.put("amount", amount);
        pay.put("currency", currency);
        pay.put("screenshotUrl", screenshotUrl);
        pay.put("status", "pending");
        pay.put("date", FieldValue.serverTimestamp());
        pay.put("createdMonth", month);

        // side collection doc for boosts / subscriptions so they can be activated on approval
        if ("boost_profile".equals(type) || "boost_post".equals(type)) {
            DocumentReference boost = db.collection("boosts").document();
            Map<String, Object> bo = new HashMap<>();
            bo.put("userId", uid);
            bo.put("postId", "boost_post".equals(type) ? targetId : "");
            bo.put("type", "boost_post".equals(type) ? "post" : "profile");
            bo.put("status", "pending");
            bo.put("amount", amount);
            bo.put("screenshotUrl", screenshotUrl);
            bo.put("createdAt", FieldValue.serverTimestamp());
            bo.put("createdMonth", month);
            pay.put("boostId", boost.getId());
            boost.set(bo).addOnSuccessListener(x ->
                    db.collection("payments").add(pay).addOnSuccessListener(p -> done()))
                    .addOnFailureListener(e -> fail(e));
        } else if ("subscription".equals(type)) {
            DocumentReference sub = db.collection("subscriptions").document();
            Map<String, Object> su = new HashMap<>();
            su.put("creatorId", targetId);
            su.put("subscriberId", uid);
            su.put("price", amount);
            su.put("status", "pending");
            su.put("screenshotUrl", screenshotUrl);
            su.put("createdAt", FieldValue.serverTimestamp());
            su.put("createdMonth", month);
            pay.put("subscriptionId", sub.getId());
            sub.set(su).addOnSuccessListener(x ->
                    db.collection("payments").add(pay).addOnSuccessListener(p -> done()))
                    .addOnFailureListener(e -> fail(e));
        } else {
            // tick_* payments carry the requested level
            pay.put("tickLevel", type.substring(5));
            db.collection("payments").add(pay).addOnSuccessListener(p -> done())
                    .addOnFailureListener(e -> fail(e));
        }
    }

    private void done() {
        statusT.setText("✅ Payment submitted! Admin will approve shortly.");
        Ui.toast(this, "Submitted for approval ✓");
        statusT.postDelayed(this::finish, 1200);
    }

    private void fail(Exception e) {
        busy = false;
        statusT.setText("Error: " + e.getMessage());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cfgReg != null) cfgReg.remove();
    }
}
