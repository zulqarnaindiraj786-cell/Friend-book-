package com.example.frienbook.core;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.ListenerRegistration;

/**
 * 📢 AdMob config — real production IDs, live-editable from the admin panel.
 * `config/ads` { bannerId, enabled } overrides defaults at runtime (admin edits apply instantly, no
 * app update needed). The emergency kill switch is Cloudflare's: Worker `/v1/config` answers
 * `adsEnabled` from a Worker variable, so flipping it needs no app update and no Firebase console.
 */
public class AdsConfig {

    public static final String APP_ID = "ca-app-pub-3387480177023669~5696528190";
    public static final String DEFAULT_BANNER_ID = "ca-app-pub-3387480177023669/2691090902";

    private static String bannerId = DEFAULT_BANNER_ID;
    private static boolean enabled = true;
    private static boolean remoteAllowed = true; // Cloudflare /v1/config adsEnabled
    private static ListenerRegistration reg;

    public static String bannerId() { return bannerId == null || bannerId.isEmpty() ? DEFAULT_BANNER_ID : bannerId; }
    public static boolean bannersEnabled() { return enabled && remoteAllowed; }

    public interface ChangeListener { void onChange(String bannerId, boolean enabled); }
    private static ChangeListener changeListener;
    public static void setChangeListener(ChangeListener l) { changeListener = l; }

    /** Live-listen Firestore config/ads so admin edits apply without an app update. */
    public static void startWatching() {
        if (reg != null) return;
        reg = Fb.db().collection("config").document("ads")
                .addSnapshotListener((d, err) -> {
                    if (d != null && d.exists()) {
                        String id = d.getString("bannerId");
                        Boolean en = d.getBoolean("enabled");
                        if (id != null && !id.isEmpty()) bannerId = id;
                        if (en != null) enabled = en;
                        notifyChange();
                    }
                });
    }

    /** Cloudflare kill switch (`/v1/config` → adsEnabled), refreshed by {@link CloudConfig}. */
    public static void setRemoteAllowed(boolean allowed) {
        remoteAllowed = allowed;
        notifyChange();
    }

    public static void notifyChange() {
        if (changeListener != null) changeListener.onChange(bannerId(), bannersEnabled());
    }

    /** Admin saves new banner ID / toggle. */
    public static void save(String newBannerId, boolean on, android.content.Context c) {
        Fb.db().collection("config").document("ads")
                .set(Fb.map("bannerId", newBannerId, "enabled", on,
                                "updatedAt", com.google.firebase.firestore.FieldValue.serverTimestamp()),
                        com.google.firebase.firestore.SetOptions.merge())
                .addOnSuccessListener(x -> Ui.toast(c, "AdMob settings saved ✓"))
                .addOnFailureListener(e -> Ui.toast(c, "Save failed: " + e.getMessage()));
    }

    /** Delete override → fall back to the built-in live ID. */
    public static void reset(android.content.Context c) {
        Fb.db().collection("config").document("ads").delete()
                .addOnSuccessListener(x -> {
                    bannerId = DEFAULT_BANNER_ID;
                    enabled = true;
                    Ui.toast(c, "Reset to default banner ✓");
                    notifyChange();
                });
    }
}
