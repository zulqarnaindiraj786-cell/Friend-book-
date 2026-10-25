package com.friendbook.admin;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.friendbook.core.Admin;
import com.friendbook.core.AdsConfig;
import com.friendbook.core.Fb;
import com.friendbook.core.Ui;
import com.google.firebase.firestore.ListenerRegistration;

/**
 * 📢 Real Production AdMob Native Ads Settings (Admin Only):
 * Configures the live Production Native Ad Unit ID (in-feed after every 5 posts)
 * and optional Production Interstitial Ad Unit ID. Blocks test IDs automatically.
 */
public class AdMobSettingsActivity extends Activity {

    private EditText nativeEdit;
    private EditText interstitialEdit;
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
        root.addView(Ui.pageBar(this, "📢 Real AdMob Native Ads"));
        root.addView(Ui.divider(this));

        ScrollView sv = new ScrollView(this);
        LinearLayout body = Ui.col(this);
        Ui.pad(body, 14, 14, 14, 24);
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout card1 = Ui.col(this);
        card1.setBackground(Ui.round(this, Ui.card(this), 14));
        Ui.pad(card1, 14, 12, 14, 12);
        card1.addView(Ui.tvBold(this, "Production AdMob App ID (Active)", 13, Ui.sub(this)));
        TextView appId = Ui.tvBold(this, AdsConfig.APP_ID, 14, Ui.txt(this));
        appId.setPadding(0, Ui.dp(this, 4), 0, 0);
        card1.addView(appId);
        TextView hint1 = Ui.tv(this, "Configured in AndroidManifest.xml · Package: com.friendbook", 11, Ui.GREEN);
        hint1.setPadding(0, Ui.dp(this, 4), 0, 0);
        card1.addView(hint1);
        body.addView(card1, Ui.lpw(-2));

        LinearLayout card2 = Ui.col(this);
        card2.setBackground(Ui.round(this, Ui.card(this), 14));
        Ui.pad(card2, 14, 12, 14, 14);
        card2.addView(Ui.tvBold(this, "Production Native Ad Unit ID (After Every 5 Posts)", 13, Ui.sub(this)));
        nativeEdit = Ui.input(this, "ca-app-pub-XXXXXXXXXXXXXXXX/YYYYYYYYYY");
        nativeEdit.setText(AdsConfig.nativeId());
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1, Ui.dp(this, 46));
        ep.setMargins(0, Ui.dp(this, 6), 0, Ui.dp(this, 12));
        card2.addView(nativeEdit, ep);

        card2.addView(Ui.tvBold(this, "Optional Production Interstitial Ad Unit ID", 13, Ui.sub(this)));
        interstitialEdit = Ui.input(this, "Optional: ca-app-pub-XXXXXXXXXXXXXXXX/YYYYYYYYYY");
        interstitialEdit.setText(AdsConfig.interstitialId());
        LinearLayout.LayoutParams iep = new LinearLayout.LayoutParams(-1, Ui.dp(this, 46));
        iep.setMargins(0, Ui.dp(this, 6), 0, 0);
        card2.addView(interstitialEdit, iep);

        LinearLayout switchRow = Ui.row(this);
        switchRow.setGravity(Gravity.CENTER_VERTICAL);
        switchRow.setPadding(0, Ui.dp(this, 12), 0, 0);
        TextView onT = Ui.tvBold(this, "Enable In-Feed Native Ads (Every 5 Posts)", 13, Ui.txt(this));
        switchRow.addView(onT, new LinearLayout.LayoutParams(0, -2, 1));
        enabledSwitch = new Switch(this);
        enabledSwitch.setChecked(AdsConfig.isMasterSwitchEnabled());
        switchRow.addView(enabledSwitch);
        card2.addView(switchRow);

        LinearLayout.LayoutParams cp2 = new LinearLayout.LayoutParams(-1, -2);
        cp2.setMargins(0, Ui.dp(this, 12), 0, 0);
        body.addView(card2, cp2);

        previewT = Ui.tv(this, "", 12, Ui.sub(this));
        previewT.setGravity(Gravity.CENTER);
        previewT.setPadding(0, Ui.dp(this, 10), 0, 0);
        body.addView(previewT);

        TextView save = Ui.btn(this, "💾 Save Real Production Native ID");
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.setMargins(0, Ui.dp(this, 10), 0, 0);
        save.setOnClickListener(v -> {
            String nId = nativeEdit.getText().toString().trim();
            String iId = interstitialEdit.getText().toString().trim();
            if (!AdsConfig.isRealProductionUnit(nId)) {
                Ui.toast(this, "Enter a real production Native Ad Unit ID (ca-app-pub-...)");
                return;
            }
            if (!iId.isEmpty() && !AdsConfig.isRealProductionUnit(iId)) {
                Ui.toast(this, "Interstitial ID must be a real production unit (or leave blank)");
                return;
            }
            AdsConfig.save(nId, iId, enabledSwitch.isChecked(), this);
        });
        body.addView(save, sp);

        TextView reset = Ui.btnGrey(this, "↩ Reset to Built-In Native Ad ID");
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.setMargins(0, Ui.dp(this, 8), 0, 0);
        reset.setOnClickListener(v -> {
            AdsConfig.reset(this);
            nativeEdit.setText(AdsConfig.DEFAULT_NATIVE_ID);
            interstitialEdit.setText("");
            enabledSwitch.setChecked(true);
        });
        body.addView(reset, rp);

        TextView note = Ui.tv(this,
                "ℹ️ Banner ads are completely removed. Only real production AdMob Native Ads appear in the News Feed after every 5 posts.",
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
                        String id = d.getString("nativeId");
                        String iId = d.getString("interstitialId");
                        Boolean en = d.getBoolean("nativeEnabled");
                        String activeNative = AdsConfig.isRealProductionUnit(id) ? id : AdsConfig.DEFAULT_NATIVE_ID;
                        nativeEdit.setText(activeNative);
                        if (iId != null && AdsConfig.isRealProductionUnit(iId)) interstitialEdit.setText(iId);
                        if (en != null) enabledSwitch.setChecked(en);
                        previewT.setText("Live Native Ads (every 5 posts): "
                                + (en == null || en ? "ON" : "OFF")
                                + (activeNative.isEmpty() ? " — awaiting Native ID" : " — " + activeNative));
                    } else {
                        previewT.setText("Live Native Ads (every 5 posts): "
                                + (AdsConfig.DEFAULT_NATIVE_ID.isEmpty() ? "awaiting Native ID" : AdsConfig.DEFAULT_NATIVE_ID));
                    }
                });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (reg != null) reg.remove();
    }
}
