package com.friendbook.social.admin;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.friendbook.social.core.Admin;
import com.friendbook.social.core.AdsConfig;
import com.friendbook.social.core.ConsentManager;
import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Ui;
import com.friendbook.social.d1.documents.ListenerRegistration;

/**
 * 📢 Real Production AdMob Native Ads Dashboard (`com.friendbook.social`):
 * - App ID:        ca-app-pub-3387480177023669~1513026594
 * - Feed Native:   ca-app-pub-3387480177023669/4957447582
 * - Reels Native:  ca-app-pub-3387480177023669/6468085788
 * - app-ads.txt:   google.com, pub-3387480177023669, DIRECT, f08c47fec0942fa0
 */
public class AdMobSettingsActivity extends Activity {

    private EditText feedNativeEdit;
    private EditText reelsNativeEdit;
    private Switch enabledSwitch;
    private TextView previewT, statsT;
    private ListenerRegistration reg;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Admin.isAdmin()) { Ui.toast(this, "Admins only"); finish(); return; }
        Ui.statusBarLight(this);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        root.addView(Ui.pageBar(this, "📢 Real Production AdMob Ads"));
        root.addView(Ui.divider(this));

        ScrollView sv = new ScrollView(this);
        LinearLayout body = Ui.col(this);
        Ui.pad(body, 14, 14, 14, 24);
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        // 1. Live Production Ad Telemetry Card
        LinearLayout statsCard = Ui.col(this);
        statsCard.setBackground(Ui.roundStroke(this, Ui.card(this), 14, Ui.BLUE, 1));
        Ui.pad(statsCard, 14, 12, 14, 12);
        statsCard.addView(Ui.tvBold(this, "💰 Production AdMob Telemetry (Logcat tag: FriendBookAds)", 13, Ui.BLUE));
        statsT = Ui.tvBold(this, AdsConfig.statsSummary(this), 12.5f, Ui.txt(this));
        statsT.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 4));
        statsCard.addView(statsT);
        statsCard.addView(Ui.tv(this,
                "Pure production mode: zero test IDs, fresh AdLoader.Builder request on every Feed & Reels slot, Logcat error-code logging enabled.",
                11, Ui.sub(this)));
        // Real fill check: fires one genuine Feed + one genuine Reels request (zero test ads) and
        // then reports exactly what AdMob answered, so "no ads showing" can be diagnosed on device.
        final TextView fillCheck = Ui.btn(this, "Run Real Ad Fill Check");
        fillCheck.setOnClickListener(v -> {
            fillCheck.setEnabled(false);
            fillCheck.setText("Checking Google consent…");
            com.friendbook.social.core.ConsentManager.gatherConsentAndInitAds(this, () -> {
                if (isFinishing() || isDestroyed()) return;
                if (com.friendbook.social.core.ConsentManager.canRequestAds(this)) {
                    AdsConfig.preloadFeedBatchNow(getApplicationContext(), 1);
                    AdsConfig.preloadReelsBatchNow(getApplicationContext(), 1);
                }
                fillCheck.setText(com.friendbook.social.core.ConsentManager.canRequestAds(this)
                        ? "Requesting real ads…" : "Consent does not allow requests");
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    fillCheck.setEnabled(true);
                    fillCheck.setText("Run Real Ad Fill Check");
                    if (statsT != null) statsT.setText(AdsConfig.statsSummary(this));
                    new android.app.AlertDialog.Builder(this)
                            .setTitle("Real Ad Fill Check")
                            .setMessage(AdsConfig.statsSummary(this) + "\n"
                                    + com.friendbook.social.core.ConsentManager.consentDiagnostic() + "\n\n"
                                    + "How to read this:\n"
                                    + "• Loaded > 0 and Pool > 0 = real ads are arriving; the Feed slot shows one after every 3 posts.\n"
                                    + "• Code 3 = AdMob has no ad to serve for this app/unit right now (new unit, app still in review, or no advertiser demand). The app keeps asking every 30s while open.\n"
                                    + "• Code 0 with a message = the request itself was rejected; the message tells us why.\n"
                                    + "• Requests 0 = the SDK never asked, which points at consent/initialization, not at AdMob fill.")
                            .setPositiveButton("OK", null)
                            .show();
                }, com.friendbook.social.core.ConsentManager.canRequestAds(this) ? 8000L : 0L);
            });
        });
        body.addView(fillCheck, Ui.lpw(-2));
        body.addView(statsCard, Ui.lpw(-2));

        // 2. App ID & Official Website URL Card
        LinearLayout card1 = Ui.col(this);
        card1.setBackground(Ui.round(this, Ui.card(this), 14));
        Ui.pad(card1, 14, 12, 14, 12);
        card1.addView(Ui.tvBold(this, "Production AdMob App ID (AndroidManifest.xml)", 13, Ui.sub(this)));
        TextView appId = Ui.tvBold(this, AdsConfig.APP_ID, 14, Ui.txt(this));
        appId.setPadding(0, Ui.dp(this, 4), 0, 0);
        card1.addView(appId);
        TextView websiteLabel = Ui.tvBold(this, "Google Play listing", 12, Ui.sub(this));
        websiteLabel.setPadding(0, Ui.dp(this, 8), 0, 0);
        card1.addView(websiteLabel);
        TextView websiteLink = Ui.tvBold(this, AdsConfig.PLAY_STORE_URL, 12.5f, Ui.GREEN);
        websiteLink.setPadding(0, Ui.dp(this, 2), 0, 0);
        websiteLink.setOnClickListener(v -> Ui.openUrl(this, AdsConfig.PLAY_STORE_URL));
        card1.addView(websiteLink);
        LinearLayout.LayoutParams cp1 = new LinearLayout.LayoutParams(-1, -2);
        cp1.topMargin = Ui.dp(this, 10);
        body.addView(card1, cp1);

        // 3. Verified app-ads.txt Card
        LinearLayout adsTxtCard = Ui.col(this);
        adsTxtCard.setBackground(Ui.round(this, Ui.card(this), 14));
        Ui.pad(adsTxtCard, 14, 12, 14, 12);
        adsTxtCard.addView(Ui.tvBold(this, "📄 Official app-ads.txt (" + AdsConfig.APP_ADS_TXT_URL + ")", 13, Ui.sub(this)));
        final String bundledLine = AdsConfig.readBundledAppAdsTxt(this);
        TextView adsTxtLine = Ui.tvBold(this, bundledLine, 12, Ui.txt(this));
        adsTxtLine.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        adsTxtCard.addView(adsTxtLine);
        TextView copyAdsTxt = Ui.btnGrey(this, "📋 Copy app-ads.txt Line");
        copyAdsTxt.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("app-ads.txt", bundledLine));
            Ui.toast(this, "Copied app-ads.txt content ✓");
        });
        adsTxtCard.addView(copyAdsTxt);
        LinearLayout.LayoutParams cpa = new LinearLayout.LayoutParams(-1, -2);
        cpa.topMargin = Ui.dp(this, 10);
        body.addView(adsTxtCard, cpa);

        // 4. Feed Native & Reels Native Production Ad Units
        LinearLayout card2 = Ui.col(this);
        card2.setBackground(Ui.round(this, Ui.card(this), 14));
        Ui.pad(card2, 14, 12, 14, 14);
        card2.addView(Ui.tvBold(this,
                "Feed Native Ad Unit (" + AdsConfig.NATIVE_UNIT_NAME + ")",
                13, Ui.sub(this)));
        feedNativeEdit = Ui.input(this, AdsConfig.FEED_NATIVE_ID);
        feedNativeEdit.setText(AdsConfig.feedNativeId());
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1, Ui.dp(this, 46));
        ep.setMargins(0, Ui.dp(this, 6), 0, Ui.dp(this, 12));
        card2.addView(feedNativeEdit, ep);

        card2.addView(Ui.tvBold(this,
                "Reels Native Ad Unit (" + AdsConfig.REELS_UNIT_NAME + ")",
                13, Ui.sub(this)));
        reelsNativeEdit = Ui.input(this, AdsConfig.REELS_NATIVE_ID);
        reelsNativeEdit.setText(AdsConfig.reelsNativeId());
        LinearLayout.LayoutParams iep = new LinearLayout.LayoutParams(-1, Ui.dp(this, 46));
        iep.setMargins(0, Ui.dp(this, 6), 0, 0);
        card2.addView(reelsNativeEdit, iep);

        LinearLayout switchRow = Ui.row(this);
        switchRow.setGravity(Gravity.CENTER_VERTICAL);
        switchRow.setPadding(0, Ui.dp(this, 12), 0, 0);
        TextView onT = Ui.tvBold(this, "Enable Real Production Feed & Reels Native Ads", 13, Ui.txt(this));
        switchRow.addView(onT, new LinearLayout.LayoutParams(0, -2, 1));
        enabledSwitch = new Switch(this);
        enabledSwitch.setChecked(AdsConfig.isMasterSwitchEnabled());
        switchRow.addView(enabledSwitch);
        card2.addView(switchRow);

        LinearLayout.LayoutParams cp2 = new LinearLayout.LayoutParams(-1, -2);
        cp2.setMargins(0, Ui.dp(this, 10), 0, 0);
        body.addView(card2, cp2);

        previewT = Ui.tv(this, "", 12, Ui.sub(this));
        previewT.setGravity(Gravity.CENTER);
        previewT.setPadding(0, Ui.dp(this, 10), 0, 0);
        body.addView(previewT);

        TextView save = Ui.btn(this, "💾 Save Production Native Ad IDs");
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.setMargins(0, Ui.dp(this, 10), 0, 0);
        save.setOnClickListener(v -> {
            String fId = feedNativeEdit.getText().toString().trim();
            String rId = reelsNativeEdit.getText().toString().trim();
            if (!AdsConfig.isRealProductionUnit(fId)) {
                Ui.toast(this, "Enter a real production Feed Native ID starting with " + AdsConfig.PUBLISHER_PREFIX);
                return;
            }
            if (!AdsConfig.isRealProductionUnit(rId)) {
                Ui.toast(this, "Enter a real production Reels Native ID starting with " + AdsConfig.PUBLISHER_PREFIX);
                return;
            }
            AdsConfig.save(fId, rId, enabledSwitch.isChecked(), this);
        });
        body.addView(save, sp);

        TextView gdprBtn = Ui.btnGrey(this, "🛡️ Open UMP GDPR Consent Form");
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-1, -2);
        gp.setMargins(0, Ui.dp(this, 8), 0, 0);
        gdprBtn.setOnClickListener(v -> ConsentManager.showPrivacyOptionsForm(this));
        body.addView(gdprBtn, gp);

        TextView reset = Ui.btnGrey(this, "↩ Reset to Default Production IDs");
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.setMargins(0, Ui.dp(this, 8), 0, 0);
        reset.setOnClickListener(v -> {
            AdsConfig.reset(this);
            feedNativeEdit.setText(AdsConfig.FEED_NATIVE_ID);
            reelsNativeEdit.setText(AdsConfig.REELS_NATIVE_ID);
            enabledSwitch.setChecked(true);
        });
        body.addView(reset, rp);

        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (statsT != null) statsT.setText(AdsConfig.statsSummary(this));
        reg = Fb.db().collection("config").document("ads")
                .addSnapshotListener((d, err) -> {
                    if (d != null && d.exists()) {
                        String fId = d.getString("feed_native_unit_v3");
                        String rId = d.getString("reels_native_unit_v3");
                        Boolean en = d.getBoolean("ads_enabled_v3");
                        String activeFeed = AdsConfig.isRealProductionUnit(fId) ? fId : AdsConfig.FEED_NATIVE_ID;
                        String activeReels = AdsConfig.isRealProductionUnit(rId) ? rId : AdsConfig.REELS_NATIVE_ID;
                        feedNativeEdit.setText(activeFeed);
                        reelsNativeEdit.setText(activeReels);
                        if (en != null) enabledSwitch.setChecked(en);
                        previewT.setText("Production Ads: " + (en == null || en ? "ON" : "OFF")
                                + "\nFeed: " + activeFeed + "\nReels: " + activeReels);
                    } else {
                        previewT.setText("Production Ads: ON\nFeed: " + AdsConfig.FEED_NATIVE_ID
                                + "\nReels: " + AdsConfig.REELS_NATIVE_ID);
                    }
                });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (reg != null) reg.remove();
    }
}
