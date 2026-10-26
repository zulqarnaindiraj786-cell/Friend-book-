package com.friendbook.admin;

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

import com.friendbook.core.Admin;
import com.friendbook.core.AdsConfig;
import com.friendbook.core.ConsentManager;
import com.friendbook.core.Fb;
import com.friendbook.core.Ui;
import com.google.firebase.firestore.ListenerRegistration;

/**
 * 📢 Real Production AdMob Native Advanced & Play Store Approval Dashboard:
 * - App ID:                    ca-app-pub-3387480177023669~5696528190
 * - Native Advanced Unit ID:   ca-app-pub-3387480177023669/5657602381 (friendbook native)
 * - Placement:                 After every 5 posts in RecyclerView (`native_ad_layout.xml`)
 * - app-ads.txt:               friendbook.com/app-ads.txt -> google.com, pub-3387480177023669, DIRECT, f08c47fec0942fa0
 * - Live Telemetry:            Requests, Impressions, Match Rate %, Error Code 3 count & Est. eCPM ($0.17+)
 */
public class AdMobSettingsActivity extends Activity {

    private EditText nativeEdit;
    private EditText interstitialEdit;
    private Switch enabledSwitch;
    private TextView previewT, statsT;
    private ListenerRegistration reg;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Admin.isAdmin()) { Ui.toast(this, "Admins only"); finish(); return; }
        Ui.statusBarBlue(this);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        root.addView(Ui.pageBar(this, "📢 Real AdMob Native Advanced"));
        root.addView(Ui.divider(this));

        ScrollView sv = new ScrollView(this);
        LinearLayout body = Ui.col(this);
        Ui.pad(body, 14, 14, 14, 24);
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        // 1. Live Earnings, Match Rate & eCPM ($0.17+ Growth) Card
        LinearLayout statsCard = Ui.col(this);
        statsCard.setBackground(Ui.roundStroke(this, Ui.card(this), 14, Ui.BLUE, 1));
        Ui.pad(statsCard, 14, 12, 14, 12);
        statsCard.addView(Ui.tvBold(this, "💰 Real-Time Match Rate & eCPM Monitor ($0.17+ Target)", 13, Ui.BLUE));
        statsT = Ui.tvBold(this, AdsConfig.statsSummary(this), 12.5f, Ui.txt(this));
        statsT.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 4));
        statsCard.addView(statsT);
        statsCard.addView(Ui.tv(this,
                "Once published on Play Store & linked in AdMob Console, 'Requires review' clears and match rate climbs to 90%+.",
                11, Ui.sub(this)));
        body.addView(statsCard, Ui.lpw(-2));

        // 2. App ID & Play Store Link Card
        LinearLayout card1 = Ui.col(this);
        card1.setBackground(Ui.round(this, Ui.card(this), 14));
        Ui.pad(card1, 14, 12, 14, 12);
        card1.addView(Ui.tvBold(this, "Production AdMob App ID (AndroidManifest.xml)", 13, Ui.sub(this)));
        TextView appId = Ui.tvBold(this, AdsConfig.APP_ID, 14, Ui.txt(this));
        appId.setPadding(0, Ui.dp(this, 4), 0, 0);
        card1.addView(appId);
        TextView storeLink = Ui.tv(this, "Play Store URL: " + AdsConfig.PLAY_STORE_URL, 11.5f, Ui.GREEN);
        storeLink.setPadding(0, Ui.dp(this, 4), 0, 0);
        card1.addView(storeLink);
        LinearLayout.LayoutParams cp1 = new LinearLayout.LayoutParams(-1, -2);
        cp1.topMargin = Ui.dp(this, 10);
        body.addView(card1, cp1);

        // 3. Verified app-ads.txt Card (Blogger / Blogspot Hosting Ready)
        LinearLayout adsTxtCard = Ui.col(this);
        adsTxtCard.setBackground(Ui.round(this, Ui.card(this), 14));
        Ui.pad(adsTxtCard, 14, 12, 14, 12);
        adsTxtCard.addView(Ui.tvBold(this, "📄 Blogger / Blogspot app-ads.txt Setup", 13, Ui.sub(this)));
        final String bundledLine = AdsConfig.readBundledAppAdsTxt(this);
        TextView adsTxtLine = Ui.tvBold(this, bundledLine, 12, Ui.txt(this));
        adsTxtLine.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        adsTxtCard.addView(adsTxtLine);
        TextView copyAdsTxt = Ui.btnGrey(this, "📋 Copy app-ads.txt Line for Blogger");
        copyAdsTxt.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("app-ads.txt", bundledLine));
            Ui.toast(this, "Copied app-ads.txt content ✓");
        });
        adsTxtCard.addView(copyAdsTxt);
        LinearLayout.LayoutParams cpa = new LinearLayout.LayoutParams(-1, -2);
        cpa.topMargin = Ui.dp(this, 10);
        body.addView(adsTxtCard, cpa);

        // 4. Native Advanced Unit ("friendbook native") — Every 5 Posts
        LinearLayout card2 = Ui.col(this);
        card2.setBackground(Ui.round(this, Ui.card(this), 14));
        Ui.pad(card2, 14, 12, 14, 14);
        card2.addView(Ui.tvBold(this,
                "Native Advanced Ad Unit (" + AdsConfig.NATIVE_UNIT_NAME + " · After Every 5 Posts)",
                13, Ui.sub(this)));
        nativeEdit = Ui.input(this, AdsConfig.DEFAULT_NATIVE_ID);
        nativeEdit.setText(AdsConfig.nativeId());
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1, Ui.dp(this, 46));
        ep.setMargins(0, Ui.dp(this, 6), 0, Ui.dp(this, 12));
        card2.addView(nativeEdit, ep);

        card2.addView(Ui.tvBold(this, "Optional Production Interstitial Ad Unit ID", 13, Ui.sub(this)));
        interstitialEdit = Ui.input(this, "Optional: ca-app-pub-3387480177023669/...");
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
        cp2.setMargins(0, Ui.dp(this, 10), 0, 0);
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
                Ui.toast(this, "Enter a real production Native Ad Unit ID starting with " + AdsConfig.PUBLISHER_PREFIX);
                return;
            }
            if (!iId.isEmpty() && !AdsConfig.isRealProductionUnit(iId)) {
                Ui.toast(this, "Interstitial ID must start with " + AdsConfig.PUBLISHER_PREFIX + " (or leave blank)");
                return;
            }
            AdsConfig.save(nId, iId, enabledSwitch.isChecked(), this);
        });
        body.addView(save, sp);

        TextView gdprBtn = Ui.btnGrey(this, "🛡️ Open UMP GDPR Consent Form");
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-1, -2);
        gp.setMargins(0, Ui.dp(this, 8), 0, 0);
        gdprBtn.setOnClickListener(v -> ConsentManager.showPrivacyOptionsForm(this));
        body.addView(gdprBtn, gp);

        TextView reset = Ui.btnGrey(this, "↩ Reset to Default friendbook native ID");
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.setMargins(0, Ui.dp(this, 8), 0, 0);
        reset.setOnClickListener(v -> {
            AdsConfig.reset(this);
            nativeEdit.setText(AdsConfig.DEFAULT_NATIVE_ID);
            interstitialEdit.setText("");
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
                        String id = d.getString("native_unit_v2");
                        String iId = d.getString("interstitialId");
                        Boolean en = d.getBoolean("nativeEnabled");
                        String activeNative = AdsConfig.isRealProductionUnit(id) ? id : AdsConfig.DEFAULT_NATIVE_ID;
                        nativeEdit.setText(activeNative);
                        if (iId != null && AdsConfig.isRealProductionUnit(iId)) interstitialEdit.setText(iId);
                        if (en != null) enabledSwitch.setChecked(en);
                        previewT.setText("Live Native Advanced (every 5 posts): "
                                + (en == null || en ? "ON" : "OFF") + " — " + activeNative);
                    } else {
                        previewT.setText("Live Native Advanced (every 5 posts): ON — " + AdsConfig.DEFAULT_NATIVE_ID);
                    }
                });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (reg != null) reg.remove();
    }
}
