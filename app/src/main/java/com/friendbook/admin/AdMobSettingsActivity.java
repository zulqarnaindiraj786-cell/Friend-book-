package com.friendbook.admin;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import com.friendbook.core.Admin;
import com.friendbook.core.AdsConfig;
import com.friendbook.core.Fb;
import com.friendbook.core.Ui;
import com.google.firebase.firestore.ListenerRegistration;

/**
 * 📢 AdMob Settings — ONLY zulqarnaindiraj786@gmail.com (or promoted admins).
 * Shows live App ID / Banner unit, lets admin change the Banner ID and toggle
 * banners on/off. Saved to Firestore `config/ads` (applies live to every install)
 * and mirrored into Firebase Remote Config's runtime view; console key
 * `ads_enabled` remains an extra kill switch.
 */
public class AdMobSettingsActivity extends Activity {

    private EditText bannerEdit;
    private Switch enabledSwitch;
    private TextView previewT;
    private ListenerRegistration reg;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Admin.isAdmin()) { Ui.toast(this, "Admins only"); finish(); return; }
        Ui.statusBarBlue(this);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        root.addView(Ui.pageBar(this, "📢 AdMob Settings"));
        root.addView(Ui.divider(this));

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        LinearLayout body = Ui.col(this);
        Ui.pad(body, 14, 14, 14, 24);
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        // App ID card
        LinearLayout card1 = Ui.col(this);
        card1.setBackground(Ui.round(this, Ui.card(this), 14));
        Ui.pad(card1, 14, 12, 14, 12);
        card1.addView(Ui.tvBold(this, "AdMob App ID", 13, Ui.sub(this)));
        TextView appId = Ui.tvBold(this, AdsConfig.APP_ID, 13, Ui.txt(this));
        appId.setPadding(0, Ui.dp(this, 4), 0, 0);
        card1.addView(appId);
        TextView hint1 = Ui.tv(this, "Set in AndroidManifest.xml (com.google.android.gms.ads.APPLICATION_ID)", 11, Ui.sub(this));
        hint1.setPadding(0, Ui.dp(this, 4), 0, 0);
        card1.addView(hint1);
        body.addView(card1, Ui.lpw(-2));

        // Banner unit editor
        LinearLayout card2 = Ui.col(this);
        card2.setBackground(Ui.round(this, Ui.card(this), 14));
        Ui.pad(card2, 14, 12, 14, 14);
        card2.addView(Ui.tvBold(this, "Banner Ad Unit ID", 13, Ui.sub(this)));
        bannerEdit = Ui.input(this, "ca-app-pub-…/…");
        bannerEdit.setText(AdsConfig.bannerId());
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1, Ui.dp(this, 46));
        ep.setMargins(0, Ui.dp(this, 8), 0, 0);
        card2.addView(bannerEdit, ep);

        LinearLayout switchRow = Ui.row(this);
        switchRow.setPadding(0, Ui.dp(this, 10), 0, 0);
        TextView onT = Ui.tvBold(this, "Show banner ads (every 3 posts in Home)", 13, Ui.txt(this));
        switchRow.addView(onT, new LinearLayout.LayoutParams(0, -2, 1));
        enabledSwitch = new Switch(this);
        enabledSwitch.setChecked(AdsConfig.bannersEnabled());
        switchRow.addView(enabledSwitch);
        card2.addView(switchRow);

        LinearLayout.LayoutParams cp2 = new LinearLayout.LayoutParams(-1, -2);
        cp2.setMargins(0, Ui.dp(this, 12), 0, 0);
        body.addView(card2, cp2);

        previewT = Ui.tv(this, "", 12, Ui.sub(this));
        previewT.setGravity(Gravity.CENTER);
        previewT.setPadding(0, Ui.dp(this, 10), 0, 0);
        body.addView(previewT);

        TextView save = Ui.btn(this, "💾 Save (applies to all users instantly)");
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.setMargins(0, Ui.dp(this, 10), 0, 0);
        save.setOnClickListener(v -> {
            String id = bannerEdit.getText().toString().trim();
            if (!id.startsWith("ca-app-pub-")) { Ui.toast(this, "Enter a valid ca-app-pub-…/… ID"); return; }
            AdsConfig.save(id, enabledSwitch.isChecked(), this);
        });
        body.addView(save, sp);

        TextView reset = Ui.btnGrey(this, "↩ Reset to default live banner");
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.setMargins(0, Ui.dp(this, 8), 0, 0);
        reset.setOnClickListener(v -> {
            AdsConfig.reset(this);
            bannerEdit.setText(AdsConfig.DEFAULT_BANNER_ID);
            enabledSwitch.setChecked(true);
        });
        body.addView(reset, rp);

        TextView note = Ui.tv(this,
                "ℹ️ Firebase Remote Config key `ads_enabled` (console) acts as an emergency kill switch — it always overrides this toggle. Saving here updates every device live via Firestore `config/ads`.",
                11, Ui.sub(this));
        note.setPadding(0, Ui.dp(this, 12), 0, 0);
        body.addView(note);

        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        reg = Fb.db().collection("config").document("ads")
                .addSnapshotListener((d, err) -> {
                    if (d != null && d.exists()) {
                        String id = d.getString("bannerId");
                        Boolean en = d.getBoolean("enabled");
                        if (id != null) bannerEdit.setText(id);
                        if (en != null) enabledSwitch.setChecked(en);
                        previewT.setText("Live now: " + (en != null && en ? "ON" : "OFF")
                                + " — " + (id == null ? AdsConfig.DEFAULT_BANNER_ID : id));
                    } else {
                        previewT.setText("Live now: ON (default) — " + AdsConfig.DEFAULT_BANNER_ID);
                    }
                });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (reg != null) reg.remove();
    }
}
