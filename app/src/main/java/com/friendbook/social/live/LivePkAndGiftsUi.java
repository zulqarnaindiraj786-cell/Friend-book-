package com.friendbook.social.live;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.friendbook.social.core.FastCache;
import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Format;
import com.friendbook.social.core.Notify;
import com.friendbook.social.core.Ui;
import com.friendbook.social.models.Models;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.SetOptions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * TikTok-Style Live Overlays, Gift Drawer, PK Matchmaking Drawer, Invite Friends Sheet,
 * Beauty & Filter Studio Sheet, Mute/Block Manager, and Post-Live Full-Screen Analytics Screen.
 */
public final class LivePkAndGiftsUi {

    private LivePkAndGiftsUi() { }

    // ==================== TIKTOK GIFT CATALOG ====================

    public static final class GiftItem {
        public final String id;
        /** 2026 vector icon key rendered by StudioVectorIcons (never a keyboard emoji). */
        public final String icon;
        public final String name;
        public final int coins;

        public GiftItem(String id, String icon, String name, int coins) {
            this.id = id;
            this.icon = icon;
            this.name = name;
            this.coins = coins;
        }
    }

    public static final GiftItem[] GIFTS = new GiftItem[]{
            new GiftItem("rose",     "rose",     "Rose",         1),
            new GiftItem("heart",    "heart",    "Finger Heart", 5),
            new GiftItem("donut",    "donut",    "Doughnut",     30),
            new GiftItem("crown",    "crown",    "Gold Crown",   99),
            new GiftItem("confetti", "confetti", "Party Pop",    199),
            new GiftItem("airplane", "jet",      "Private Jet",  299),
            new GiftItem("lion",     "lion",     "Golden Lion",  500),
            new GiftItem("universe", "rocket",   "Universe",     1000)
    };

    public interface GiftSendCallback {
        void onSendGift(@NonNull GiftItem gift, boolean targetPkSideB);
    }

    public interface BeautyFilterCallback {
        void onBeautyChanged(int beautyLevel);
        void onColorFilterChanged(int filterIndex);
        void onToggleRingLight();
        void onToggleMirror();
    }

    public interface PkChallengeCallback {
        void onChallengeLiveHost(@NonNull String targetLiveId,
                                 @NonNull String targetHostUid,
                                 @NonNull String targetHostName,
                                 @NonNull String targetHostPhoto,
                                 int durationSec);
        void onChallengeViewerOrFriend(@NonNull String targetUid,
                                       @NonNull String targetName,
                                       @NonNull String targetPhoto,
                                       int durationSec);
        void onEndCurrentPk();
    }

    public interface ModerationActionCallback {
        void onUnmuteUser(@NonNull String uid);
        void onUnkickUser(@NonNull String uid);
    }

    // ==================== 1. TIKTOK DARK GIFT DRAWER ====================

    public static void showGiftDrawer(@NonNull Activity a,
                                      @NonNull String hostAName,
                                      boolean pkActive,
                                      @Nullable String hostBName,
                                      boolean defaultSideB,
                                      @NonNull GiftSendCallback cb) {
        final AlertDialog dialog = new AlertDialog.Builder(a).create();
        LinearLayout sheet = Ui.col(a);
        GradientDrawable sheetBg = new GradientDrawable();
        sheetBg.setColor(0xF214161E);
        sheetBg.setCornerRadii(new float[]{
                Ui.dp(a, 22), Ui.dp(a, 22), Ui.dp(a, 22), Ui.dp(a, 22), 0, 0, 0, 0});
        sheet.setBackground(sheetBg);
        Ui.pad(sheet, 16, 14, 16, 20);

        // Top Handle Bar
        View handle = new View(a);
        handle.setBackground(Ui.round(a, 0x44FFFFFF, 4));
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(Ui.dp(a, 40), Ui.dp(a, 4));
        hLp.gravity = Gravity.CENTER_HORIZONTAL;
        hLp.bottomMargin = Ui.dp(a, 12);
        sheet.addView(handle, hLp);

        // Header Row
        LinearLayout head = Ui.row(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.tvBold(a, "Send Live Gift", 16, Color.WHITE);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageView closeTv = com.friendbook.social.core.StudioVectorIcons.iconView(a, "close", 18, 0xAAFFFFFF);
        closeTv.setPadding(Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 8));
        closeTv.setOnClickListener(v -> dialog.dismiss());
        head.addView(closeTv, new LinearLayout.LayoutParams(Ui.dp(a, 34), Ui.dp(a, 34)));
        sheet.addView(head);

        // PK Battle Team Target Selector (Red Team A vs Blue Team B)
        final boolean[] targetSideB = {pkActive && defaultSideB};
        if (pkActive && hostBName != null && !hostBName.isEmpty()) {
            LinearLayout teamRow = Ui.row(a);
            teamRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams trLp = new LinearLayout.LayoutParams(-1, -2);
            trLp.topMargin = Ui.dp(a, 10);

            final TextView teamABtn = new TextView(a);
            final TextView teamBBtn = new TextView(a);
            Runnable refreshTeams = () -> {
                styleTeamBtn(a, teamABtn, "Boost " + shortName(hostAName), !targetSideB[0], 0xFFFE2C55);
                styleTeamBtn(a, teamBBtn, "Boost " + shortName(hostBName), targetSideB[0], 0xFF00B2FF);
            };
            teamABtn.setOnClickListener(v -> { targetSideB[0] = false; refreshTeams.run(); });
            teamBBtn.setOnClickListener(v -> { targetSideB[0] = true; refreshTeams.run(); });
            refreshTeams.run();

            LinearLayout.LayoutParams t1Lp = new LinearLayout.LayoutParams(0, -2, 1f);
            t1Lp.rightMargin = Ui.dp(a, 6);
            LinearLayout.LayoutParams t2Lp = new LinearLayout.LayoutParams(0, -2, 1f);
            t2Lp.leftMargin = Ui.dp(a, 6);
            teamRow.addView(teamABtn, t1Lp);
            teamRow.addView(teamBBtn, t2Lp);
            sheet.addView(teamRow, trLp);
        }

        // 2x4 Grid of Gifts
        final int[] selectedIdx = {0};
        final LinearLayout[] cells = new LinearLayout[GIFTS.length];
        final TextView sendCta = new TextView(a);

        Runnable refreshSelection = () -> {
            for (int i = 0; i < GIFTS.length; i++) {
                boolean sel = (i == selectedIdx[0]);
                GradientDrawable gd = new GradientDrawable();
                gd.setCornerRadius(Ui.dp(a, 14));
                gd.setColor(sel ? 0x33FE2C55 : 0x1AFFFFFF);
                gd.setStroke(Ui.dp(a, sel ? 2 : 1), sel ? 0xFFFE2C55 : 0x22FFFFFF);
                cells[i].setBackground(gd);
            }
            GiftItem g = GIFTS[selectedIdx[0]];
            String who = (pkActive && targetSideB[0] && hostBName != null && !hostBName.isEmpty())
                    ? shortName(hostBName) : shortName(hostAName);
            sendCta.setText("Send " + g.name + " (" + g.coins + " Stars) to " + who);
        };

        for (int rowIdx = 0; rowIdx < 2; rowIdx++) {
            LinearLayout gRow = Ui.row(a);
            LinearLayout.LayoutParams grLp = new LinearLayout.LayoutParams(-1, -2);
            grLp.topMargin = Ui.dp(a, 10);
            for (int colIdx = 0; colIdx < 4; colIdx++) {
                final int idx = rowIdx * 4 + colIdx;
                GiftItem g = GIFTS[idx];
                LinearLayout cell = Ui.col(a);
                cell.setGravity(Gravity.CENTER);
                Ui.pad(cell, 4, 10, 4, 10);

                ImageView emTv = com.friendbook.social.core.StudioVectorIcons.iconView(
                        a, g.icon, 30, 0xFFFFD54F);
                cell.addView(emTv, new LinearLayout.LayoutParams(Ui.dp(a, 30), Ui.dp(a, 30)));

                TextView nmTv = Ui.tvBold(a, g.name, 10.5f, Color.WHITE);
                nmTv.setSingleLine(true);
                nmTv.setEllipsize(TextUtils.TruncateAt.END);
                nmTv.setGravity(Gravity.CENTER);
                nmTv.setPadding(0, Ui.dp(a, 4), 0, Ui.dp(a, 2));
                cell.addView(nmTv);

                TextView coinTv = com.friendbook.social.core.StudioVectorIcons.iconChip(
                        a, "star", String.valueOf(g.coins), 10f, 0, 0xFFFFD54F, 0xFFFFD54F);
                coinTv.setGravity(Gravity.CENTER);
                cell.addView(coinTv);

                cell.setOnClickListener(v -> {
                    selectedIdx[0] = idx;
                    refreshSelection.run();
                });
                cells[idx] = cell;
                LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0, -2, 1f);
                if (colIdx > 0) cLp.leftMargin = Ui.dp(a, 8);
                gRow.addView(cell, cLp);
            }
            sheet.addView(gRow, grLp);
        }

        // Send CTA Button
        sendCta.setTextColor(Color.WHITE);
        sendCta.setTextSize(14.5f);
        sendCta.setTypeface(Typeface.DEFAULT_BOLD);
        sendCta.setGravity(Gravity.CENTER);
        sendCta.setBackground(Ui.round(a, 0xFFFE2C55, 24));
        Ui.pad(sendCta, 16, 13, 16, 13);
        LinearLayout.LayoutParams ctaLp = new LinearLayout.LayoutParams(-1, -2);
        ctaLp.topMargin = Ui.dp(a, 16);
        sendCta.setOnClickListener(v -> {
            GiftItem chosen = GIFTS[selectedIdx[0]];
            dialog.dismiss();
            cb.onSendGift(chosen, targetSideB[0]);
        });
        sheet.addView(sendCta, ctaLp);

        refreshSelection.run();
        presentBottomDialog(dialog, sheet);
    }

    private static void styleTeamBtn(Activity a, TextView tv, String text, boolean active, int accent) {
        tv.setText(text);
        tv.setTextSize(12.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(Color.WHITE);
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(Ui.dp(a, 18));
        gd.setColor(active ? accent : 0x22FFFFFF);
        gd.setStroke(Ui.dp(a, 1.5f), active ? Color.WHITE : 0x33FFFFFF);
        tv.setBackground(gd);
        Ui.pad(tv, 10, 9, 10, 9);
    }

    // ==================== 2. FLYING GIFT COMBO BANNER & STAGE BURST ====================

    public static void playGiftBannerAnimation(@NonNull Activity a,
                                               @NonNull FrameLayout hostLayer,
                                               @Nullable String senderPhoto,
                                               @NonNull String senderName,
                                               @NonNull String giftEmoji,
                                               @NonNull String giftName,
                                               int coins) {
        if (a.isFinishing()) return;

        // Play TikTok-style Lion Roar ("darhay") or Jet/Airplane Flyby ("jahaz ki voice sound") SFX!
        playGiftSoundEffect(a, giftEmoji, giftName, coins);

        // 1. Left-Edge TikTok Sliding Gift Combo Pill
        final LinearLayout pill = Ui.row(a);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable pillBg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                coins >= 100
                        ? new int[]{0xEEFE2C55, 0xCCFF8F00}
                        : new int[]{0xDD1A1D28, 0xAA2C3144});
        pillBg.setCornerRadius(Ui.dp(a, 26));
        pillBg.setStroke(Ui.dp(a, 1.5f), coins >= 100 ? 0xFFFFD54F : 0x55FFFFFF);
        pill.setBackground(pillBg);
        Ui.pad(pill, 6, 5, 14, 5);

        ImageView av = new ImageView(a);
        Ui.avatar(a, av, senderPhoto);
        pill.addView(av, new LinearLayout.LayoutParams(Ui.dp(a, 34), Ui.dp(a, 34)));

        LinearLayout txtCol = Ui.col(a);
        txtCol.setPadding(Ui.dp(a, 8), 0, Ui.dp(a, 10), 0);
        TextView snTv = Ui.tvBold(a, shortName(senderName), 12f, Color.WHITE);
        TextView gnTv = Ui.tv(a, "sent " + giftName, 10.5f, 0xFFFFECB3);
        txtCol.addView(snTv);
        txtCol.addView(gnTv);
        pill.addView(txtCol);

        ImageView bigEm = com.friendbook.social.core.StudioVectorIcons.iconView(a, giftEmoji, 28, 0xFFFFD54F);
        pill.addView(bigEm, new LinearLayout.LayoutParams(Ui.dp(a, 28), Ui.dp(a, 28)));

        TextView combo = Ui.tvBold(a, " x1", 15f, 0xFFFFD54F);
        pill.addView(combo);

        FrameLayout.LayoutParams pLp = new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL);
        pLp.leftMargin = Ui.dp(a, 12);
        pLp.bottomMargin = Ui.dp(a, 45);
        hostLayer.addView(pill, pLp);

        pill.setTranslationX(-Ui.dp(a, 280));
        ObjectAnimator slideIn = ObjectAnimator.ofFloat(pill, "translationX", -Ui.dp(a, 280), 0f);
        slideIn.setDuration(380L);
        slideIn.setInterpolator(new OvershootInterpolator(1.1f));

        ObjectAnimator fadeOut = ObjectAnimator.ofFloat(pill, "alpha", 1f, 0f);
        fadeOut.setStartDelay(2400L);
        fadeOut.setDuration(420L);

        AnimatorSet set = new AnimatorSet();
        set.playSequentially(slideIn, fadeOut);
        set.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                hostLayer.removeView(pill);
            }
        });
        set.start();

        // 2. Big Center Stage Burst for Premium Gifts (>= 99 coins)
        if (coins >= 99) {
            final int burstDp = coins >= 500 ? 104 : 78;
            final ImageView burst = com.friendbook.social.core.StudioVectorIcons.iconView(
                    a, giftEmoji, burstDp, 0xFFFFD54F);
            FrameLayout.LayoutParams bLp = new FrameLayout.LayoutParams(
                    Ui.dp(a, burstDp), Ui.dp(a, burstDp), Gravity.CENTER);
            hostLayer.addView(burst, bLp);

            burst.setScaleX(0.2f);
            burst.setScaleY(0.2f);
            ObjectAnimator sx = ObjectAnimator.ofFloat(burst, "scaleX", 0.2f, 1.35f, 1.0f);
            ObjectAnimator sy = ObjectAnimator.ofFloat(burst, "scaleY", 0.2f, 1.35f, 1.0f);
            sx.setDuration(600L);
            sy.setDuration(600L);

            ObjectAnimator bFade = ObjectAnimator.ofFloat(burst, "alpha", 1f, 0f);
            bFade.setStartDelay(1500L);
            bFade.setDuration(450L);

            AnimatorSet bSet = new AnimatorSet();
            bSet.play(sx).with(sy);
            if (isJetGift(giftEmoji, giftName)) {
                ObjectAnimator flyX = ObjectAnimator.ofFloat(burst, "translationX", -Ui.dp(a, 150), Ui.dp(a, 150));
                ObjectAnimator flyY = ObjectAnimator.ofFloat(burst, "translationY", Ui.dp(a, 110), -Ui.dp(a, 130));
                flyX.setDuration(1550L);
                flyY.setDuration(1550L);
                bSet.play(flyX).with(flyY);
            } else if (isLionGift(giftEmoji, giftName)) {
                ObjectAnimator roarShake = ObjectAnimator.ofFloat(
                        burst, "rotation", 0f, -10f, 10f, -8f, 8f, -5f, 5f, 0f);
                roarShake.setDuration(1100L);
                bSet.play(roarShake).with(sx);
            }
            bSet.play(bFade).after(sx);
            bSet.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    hostLayer.removeView(burst);
                }
            });
            bSet.start();
        }
    }

    private static boolean isLionGift(String icon, String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.US);
        String k = icon == null ? "" : icon.toLowerCase(Locale.US);
        return k.contains("lion") || n.contains("lion");
    }

    private static boolean isJetGift(String icon, String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.US);
        String k = icon == null ? "" : icon.toLowerCase(Locale.US);
        return k.contains("jet") || k.contains("rocket") || k.contains("plane")
                || n.contains("jet") || n.contains("airplane") || n.contains("universe") || n.contains("car");
    }

    /**
     * Synthesizes and plays TikTok-style Live Gift sound effects on a background audio thread:
     * - Golden Lion (🦁): Deep, chesty two-stage Lion Roar ("darhay") with laryngeal growl & formants.
     * - Private Jet / Universe (✈️ / 🚀): Doppler Jet Turbine whine + Afterburner flyby ("jahaz ki voice sound").
     */
    private static void playGiftSoundEffect(final Activity a,
                                            final String giftEmoji,
                                            final String giftName,
                                            final int coins) {
        final boolean lion = isLionGift(giftEmoji, giftName);
        final boolean jet = !lion && isJetGift(giftEmoji, giftName);
        if (!lion && !jet && coins < 99) return;
        if (com.friendbook.social.core.Prefs.quiet(a)) return;

        Thread sfxThread = new Thread(() -> {
            android.media.AudioTrack track = null;
            try {
                final int sampleRate = 22050;
                final short[] pcm = lion
                        ? synthesizeLionRoarPcm(sampleRate)
                        : (jet ? synthesizeJetFlybyPcm(sampleRate) : synthesizeCelebrationChimePcm(sampleRate));
                int byteLen = pcm.length * 2;
                int minBuf = android.media.AudioTrack.getMinBufferSize(
                        sampleRate,
                        android.media.AudioFormat.CHANNEL_OUT_MONO,
                        android.media.AudioFormat.ENCODING_PCM_16BIT);
                int bufSize = Math.max(byteLen, Math.max(minBuf, 4096));
                track = new android.media.AudioTrack(
                        android.media.AudioManager.STREAM_MUSIC,
                        sampleRate,
                        android.media.AudioFormat.CHANNEL_OUT_MONO,
                        android.media.AudioFormat.ENCODING_PCM_16BIT,
                        bufSize,
                        android.media.AudioTrack.MODE_STATIC);
                track.write(pcm, 0, pcm.length);
                track.play();
                long durMs = (pcm.length * 1000L) / sampleRate + 120L;
                Thread.sleep(durMs);
            } catch (Throwable ignored) {
            } finally {
                if (track != null) {
                    try { track.stop(); } catch (Throwable ignored) {}
                    try { track.release(); } catch (Throwable ignored) {}
                }
            }
        }, "live-gift-sfx");
        sfxThread.setDaemon(true);
        sfxThread.start();
    }

    /** 1.55s TikTok-style Mighty Lion Roar ("Darhay"): main roar + secondary throat grunt. */
    private static short[] synthesizeLionRoarPcm(int sr) {
        int totalSamples = (int) (sr * 1.55);
        short[] out = new short[totalSamples];
        double phase = 0.0;
        double subPhase = 0.0;
        double lpNoise1 = 0.0;
        double lpNoise2 = 0.0;
        long seed = 0x5DEECE66DL;

        for (int i = 0; i < totalSamples; i++) {
            double t = (double) i / sr;
            double env;
            double f0;
            if (t < 0.98) {
                // Stage 1: Fierce main roar ("ROAAAR / DARHAY")
                double p = t / 0.98;
                if (p < 0.09) env = p / 0.09;
                else if (p < 0.72) env = 1.0 - 0.12 * ((p - 0.09) / 0.63);
                else env = 0.88 * (1.0 - (p - 0.72) / 0.28);

                double baseF = (p < 0.18)
                        ? (92.0 + 54.0 * (p / 0.18))
                        : (146.0 - 70.0 * ((p - 0.18) / 0.82));
                double growlVib = 11.5 * Math.sin(2.0 * Math.PI * 28.5 * t)
                        + 5.5 * Math.sin(2.0 * Math.PI * 15.0 * t);
                f0 = Math.max(58.0, baseF + growlVib);
            } else {
                // Stage 2: Deep chesty follow-up grunt ("GRRR-AAH")
                double p = (t - 0.98) / 0.57;
                if (p < 0.08) env = 0.25 + 0.70 * (p / 0.08);
                else env = 0.95 * Math.pow(1.0 - (p - 0.08) / 0.92, 1.35);
                f0 = 88.0 - 28.0 * p + 8.5 * Math.sin(2.0 * Math.PI * 25.0 * t);
            }

            phase += f0 / sr;
            subPhase += (f0 * 0.5) / sr;

            // Rich glottal waveform + vocal tract formant harmonics (F1 ~260Hz, F2 ~580Hz)
            double glottal = 0.50 * Math.sin(2.0 * Math.PI * phase)
                    + 0.34 * Math.sin(2.0 * Math.PI * subPhase)
                    + 0.42 * Math.sin(4.0 * Math.PI * phase)
                    + 0.32 * Math.sin(6.0 * Math.PI * phase)
                    + 0.22 * Math.sin(8.0 * Math.PI * phase)
                    + 0.14 * Math.sin(10.0 * Math.PI * phase);

            // Laryngeal amplitude flutter (the signature lion "darhay" rattle)
            double flutter = 0.72 + 0.28 * Math.sin(2.0 * Math.PI * 31.0 * t);

            // Turbulent breathy roar resonance
            seed = (seed * 25214903917L + 11L) & ((1L << 48) - 1);
            double white = ((double) ((int) (seed >>> 16)) / Integer.MAX_VALUE);
            lpNoise1 += 0.14 * (white - lpNoise1);
            lpNoise2 += 0.05 * (lpNoise1 - lpNoise2);
            double roarNoise = (lpNoise1 - lpNoise2) * 1.45;

            double sample = Math.tanh((glottal * flutter + roarNoise * 0.65) * 1.45) * env;
            int pcmVal = (int) Math.max(-31000, Math.min(31000, sample * 29500));
            out[i] = (short) pcmVal;
        }
        return out;
    }

    /** 1.60s TikTok-style Jet / Airplane Flyby ("Jahaz ki voice sound"): Doppler turbine + jet roar. */
    private static short[] synthesizeJetFlybyPcm(int sr) {
        int totalSamples = (int) (sr * 1.60);
        short[] out = new short[totalSamples];
        double p1 = 0.0, p2 = 0.0, pSub = 0.0;
        double lpFast = 0.0, lpSlow = 0.0;
        long seed = 0x9E3779B97F4A7C15L;

        for (int i = 0; i < totalSamples; i++) {
            double t = (double) i / sr;
            double norm = t / 1.60;

            // Smooth approach -> loud overhead pass -> Doppler tail fade
            double env;
            if (norm < 0.42) {
                env = Math.pow(norm / 0.42, 1.25);
            } else if (norm < 0.56) {
                env = 1.0;
            } else {
                env = Math.pow(1.0 - (norm - 0.56) / 0.44, 1.35);
            }

            // Doppler shift: turbine pitch rises on approach (680Hz -> 1820Hz), drops on pass-by (1820Hz -> 490Hz)
            double turbineFreq;
            if (norm < 0.46) {
                turbineFreq = 680.0 + 1140.0 * Math.pow(norm / 0.46, 1.2);
            } else {
                turbineFreq = 1820.0 - 1330.0 * Math.pow((norm - 0.46) / 0.54, 0.78);
            }

            p1 += turbineFreq / sr;
            p2 += (turbineFreq * 1.98) / sr;
            pSub += (turbineFreq * 0.25) / sr;

            double turbineWhine = 0.28 * Math.sin(2.0 * Math.PI * p1)
                    + 0.14 * Math.sin(2.0 * Math.PI * p2)
                    + 0.24 * Math.sin(2.0 * Math.PI * pSub);

            // Sweeping aerodynamic jet afterburner whoosh
            seed = (seed * 25214903917L + 11L) & ((1L << 48) - 1);
            double white = ((double) ((int) (seed >>> 16)) / Integer.MAX_VALUE);
            double alphaFast = Math.min(0.48, Math.max(0.06, turbineFreq / 4200.0));
            lpFast += alphaFast * (white - lpFast);
            lpSlow += (alphaFast * 0.22) * (lpFast - lpSlow);
            double jetWhoosh = (lpFast - lpSlow) * 1.95;

            double sample = Math.tanh((jetWhoosh + turbineWhine) * 1.35) * env;
            int pcmVal = (int) Math.max(-31000, Math.min(31000, sample * 28500));
            out[i] = (short) pcmVal;
        }
        return out;
    }

    /** Crisp celebratory chime for other 99+ coin gifts (Crown / Confetti). */
    private static short[] synthesizeCelebrationChimePcm(int sr) {
        int totalSamples = (int) (sr * 0.55);
        short[] out = new short[totalSamples];
        double p1 = 0.0, p2 = 0.0;
        for (int i = 0; i < totalSamples; i++) {
            double t = (double) i / sr;
            double f = t < 0.18 ? 587.33 : (t < 0.34 ? 783.99 : 1046.50);
            p1 += f / sr;
            p2 += (f * 2.0) / sr;
            double env = Math.exp(-3.8 * t);
            double s = (0.65 * Math.sin(2.0 * Math.PI * p1) + 0.25 * Math.sin(2.0 * Math.PI * p2)) * env;
            out[i] = (short) Math.max(-26000, Math.min(26000, (int) (s * 24000)));
        }
        return out;
    }

    // ==================== 3. TIKTOK BEAUTY & COLOR FILTER DRAWER ====================

    public static void showBeautyAndFilterDrawer(@NonNull Activity a,
                                                 int currentBeautyLevel,
                                                 int currentFilterIndex,
                                                 @NonNull String[] filterNames,
                                                 boolean ringLightOn,
                                                 boolean mirrorOn,
                                                 @NonNull BeautyFilterCallback cb) {
        final AlertDialog dialog = new AlertDialog.Builder(a).create();
        LinearLayout sheet = Ui.col(a);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF214161E);
        bg.setCornerRadii(new float[]{
                Ui.dp(a, 22), Ui.dp(a, 22), Ui.dp(a, 22), Ui.dp(a, 22), 0, 0, 0, 0});
        sheet.setBackground(bg);
        Ui.pad(sheet, 16, 16, 16, 22);

        LinearLayout head = Ui.row(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.tvBold(a, "4K UHD Beauty & Camera Filters", 16, Color.WHITE),
                new LinearLayout.LayoutParams(0, -2, 1f));
        TextView done = Ui.tvBold(a, "Done", 14, 0xFFFE2C55);
        done.setOnClickListener(v -> dialog.dismiss());
        head.addView(done);
        sheet.addView(head);

        // Section 1: Beauty Smoothness & Acne Removal Levels
        TextView bLbl = Ui.tvBold(a, "4K UHD FACE SMOOTHING & BLEMISH ERASER", 11, 0x99FFFFFF);
        bLbl.setPadding(0, Ui.dp(a, 14), 0, Ui.dp(a, 8));
        sheet.addView(bLbl);

        final String[] beautyLabels = {"Off", "Soft Glow", "Acne Clear", "Rosy 4K", "4K UHD Smooth"};
        final int[] selBeauty = {Math.max(0, Math.min(4, currentBeautyLevel))};
        final TextView[] bChips = new TextView[5];
        LinearLayout bRow = Ui.row(a);
        Runnable refreshBeauty = () -> {
            for (int i = 0; i < 5; i++) {
                boolean on = (i == selBeauty[0]);
                bChips[i].setBackground(Ui.round(a, on ? 0xFFFE2C55 : 0x22FFFFFF, 16));
                bChips[i].setTextColor(Color.WHITE);
            }
        };
        for (int i = 0; i < 5; i++) {
            final int lvl = i;
            TextView tv = Ui.tvBold(a, beautyLabels[i], 10.5f, Color.WHITE);
            tv.setGravity(Gravity.CENTER);
            Ui.pad(tv, 5, 9, 5, 9);
            tv.setOnClickListener(v -> {
                selBeauty[0] = lvl;
                refreshBeauty.run();
                cb.onBeautyChanged(lvl);
            });
            bChips[i] = tv;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i > 0) lp.leftMargin = Ui.dp(a, 4);
            bRow.addView(tv, lp);
        }
        refreshBeauty.run();
        sheet.addView(bRow);

        // Section 2: Color Filters Horizontal Rail
        TextView fLbl = Ui.tvBold(a, "LIVE COLOR FILTERS", 11, 0x99FFFFFF);
        fLbl.setPadding(0, Ui.dp(a, 16), 0, Ui.dp(a, 8));
        sheet.addView(fLbl);

        HorizontalScrollView hsv = new HorizontalScrollView(a);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout fRow = Ui.row(a);
        final int[] selFilter = {currentFilterIndex};
        final TextView[] fChips = new TextView[filterNames.length];
        Runnable refreshFilters = () -> {
            for (int i = 0; i < filterNames.length; i++) {
                boolean on = (i == selFilter[0]);
                fChips[i].setBackground(Ui.round(a, on ? 0xFF00B2FF : 0x22FFFFFF, 16));
            }
        };
        for (int i = 0; i < filterNames.length; i++) {
            final int fi = i;
            TextView tv = Ui.tvBold(a, filterNames[i], 12f, Color.WHITE);
            Ui.pad(tv, 14, 9, 14, 9);
            tv.setOnClickListener(v -> {
                selFilter[0] = fi;
                refreshFilters.run();
                cb.onColorFilterChanged(fi);
            });
            fChips[i] = tv;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = Ui.dp(a, 8);
            fRow.addView(tv, lp);
        }
        refreshFilters.run();
        hsv.addView(fRow);
        sheet.addView(hsv);

        // Section 3: Quick Camera Studio Toggles (Ring Light + Front Mirror)
        LinearLayout toolsRow = Ui.row(a);
        LinearLayout.LayoutParams trLp = new LinearLayout.LayoutParams(-1, -2);
        trLp.topMargin = Ui.dp(a, 16);

        final boolean[] rlState = {ringLightOn};
        final TextView rlBtn = com.friendbook.social.core.StudioVectorIcons.iconChip(
                a, "light", rlState[0] ? "Ring Light: ON" : "Ring Light: OFF", 12f, 0, Color.WHITE, Color.WHITE);
        rlBtn.setGravity(Gravity.CENTER);
        rlBtn.setBackground(Ui.round(a, rlState[0] ? 0xFFFF9800 : 0x22FFFFFF, 16));
        Ui.pad(rlBtn, 10, 10, 10, 10);
        rlBtn.setOnClickListener(v -> {
            rlState[0] = !rlState[0];
            rlBtn.setText(rlState[0] ? "Ring Light: ON" : "Ring Light: OFF");
            rlBtn.setBackground(Ui.round(a, rlState[0] ? 0xFFFF9800 : 0x22FFFFFF, 16));
            cb.onToggleRingLight();
        });

        final boolean[] mirState = {mirrorOn};
        final TextView mirBtn = com.friendbook.social.core.StudioVectorIcons.iconChip(
                a, "mirror", mirState[0] ? "Mirror: ON" : "Mirror: OFF", 12f, 0, Color.WHITE, Color.WHITE);
        mirBtn.setGravity(Gravity.CENTER);
        mirBtn.setBackground(Ui.round(a, mirState[0] ? 0xFF1877F2 : 0x22FFFFFF, 16));
        Ui.pad(mirBtn, 10, 10, 10, 10);
        mirBtn.setOnClickListener(v -> {
            mirState[0] = !mirState[0];
            mirBtn.setText(mirState[0] ? "Mirror: ON" : "Mirror: OFF");
            mirBtn.setBackground(Ui.round(a, mirState[0] ? 0xFF1877F2 : 0x22FFFFFF, 16));
            cb.onToggleMirror();
        });

        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, -2, 1f);
        b1.rightMargin = Ui.dp(a, 6);
        LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(0, -2, 1f);
        b2.leftMargin = Ui.dp(a, 6);
        toolsRow.addView(rlBtn, b1);
        toolsRow.addView(mirBtn, b2);
        sheet.addView(toolsRow, trLp);

        presentBottomDialog(dialog, sheet);
    }

    // ==================== 4. INVITE FRIENDS TO LIVE DRAWER ====================

    public static void showInviteFriendsDrawer(@NonNull Activity a,
                                               @NonNull String liveId,
                                               @NonNull String hostName,
                                               @NonNull String liveTitle) {
        String me = Fb.uid() == null ? "" : Fb.uid();
        String photo = Fb.myPhoto() == null ? "" : Fb.myPhoto();
        showInviteFriendsDrawer(a, liveId, me, hostName, photo, liveTitle);
    }

    public static void showInviteFriendsDrawer(@NonNull Activity a,
                                               @NonNull String liveId,
                                               @NonNull String hostUid,
                                               @NonNull String hostName,
                                               @NonNull String hostPhoto,
                                               @NonNull String liveTitle) {
        final String me = Fb.uid();
        if (me == null) return;
        final String resolvedHostUid = hostUid.isEmpty() ? me : hostUid;
        final String resolvedHostPhoto = !hostPhoto.isEmpty() ? hostPhoto : (Fb.myPhoto() == null ? "" : Fb.myPhoto());

        final AlertDialog dialog = new AlertDialog.Builder(a).create();
        LinearLayout sheet = Ui.col(a);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF214161E);
        bg.setCornerRadii(new float[]{
                Ui.dp(a, 22), Ui.dp(a, 22), Ui.dp(a, 22), Ui.dp(a, 22), 0, 0, 0, 0});
        sheet.setBackground(bg);
        Ui.pad(sheet, 16, 16, 16, 20);

        LinearLayout head = Ui.row(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(com.friendbook.social.core.StudioVectorIcons.iconChip(
                        a, "users", "Invite Friends to Live", 16f, 0, Color.WHITE, Color.WHITE),
                new LinearLayout.LayoutParams(0, -2, 1f));
        ImageView close = com.friendbook.social.core.StudioVectorIcons.iconView(a, "close", 18, 0xAAFFFFFF);
        close.setOnClickListener(v -> dialog.dismiss());
        head.addView(close, new LinearLayout.LayoutParams(Ui.dp(a, 30), Ui.dp(a, 30)));
        sheet.addView(head);

        final TextView inviteAllBtn = com.friendbook.social.core.StudioVectorIcons.iconChip(
                a, "bell", "Send Live Video Invite to All Friends", 13f, 0, Color.WHITE, Color.WHITE);
        inviteAllBtn.setGravity(Gravity.CENTER);
        inviteAllBtn.setBackground(Ui.round(a, 0xFFFE2C55, 20));
        Ui.pad(inviteAllBtn, 14, 10, 14, 10);
        LinearLayout.LayoutParams iaLp = new LinearLayout.LayoutParams(-1, -2);
        iaLp.topMargin = Ui.dp(a, 12);
        iaLp.bottomMargin = Ui.dp(a, 10);
        sheet.addView(inviteAllBtn, iaLp);

        ScrollView sv = new ScrollView(a);
        final LinearLayout listCol = Ui.col(a);
        TextView loadingTv = Ui.tv(a, "Loading friends...", 13, 0xAAFFFFFF);
        Ui.pad(loadingTv, 8, 16, 8, 16);
        listCol.addView(loadingTv);
        sv.addView(listCol);
        sheet.addView(sv, new LinearLayout.LayoutParams(-1, Ui.dp(a, 270)));

        final List<Models.User> friendUsers = new ArrayList<>();
        com.friendbook.social.core.CloudflareStore.list("users", 50, found -> {
            if (a.isFinishing() || found == null) return;
            listCol.removeAllViews();
            for (Map<String, Object> d : found) {
                Models.User u = Models.User.fromMap(Format.str(d, "id"), d);
                if (u.uid == null || u.uid.equals(me) || u.deactivated) continue;
                if (Fb.me != null && Fb.me.blocked != null && Fb.me.blocked.contains(u.uid)) continue;
                FastCache.putUser(u);
                friendUsers.add(u);
            }
            friendUsers.sort((u1, u2) -> Boolean.compare(u2.isOnline, u1.isOnline));

            if (friendUsers.isEmpty()) {
                listCol.addView(Ui.tv(a, "No users available to invite right now.", 13, 0xAAFFFFFF));
                return;
            }

            for (final Models.User u : friendUsers) {
                LinearLayout row = Ui.row(a);
                row.setGravity(Gravity.CENTER_VERTICAL);
                Ui.pad(row, 4, 8, 4, 8);

                FrameLayout avWrap = new FrameLayout(a);
                ImageView av = new ImageView(a);
                Ui.avatar(a, av, u.photoUrl);
                avWrap.addView(av, new FrameLayout.LayoutParams(Ui.dp(a, 40), Ui.dp(a, 40)));
                if (u.isOnline) {
                    View dot = new View(a);
                    GradientDrawable gd = new GradientDrawable();
                    gd.setShape(GradientDrawable.OVAL);
                    gd.setColor(0xFF31A24C);
                    gd.setStroke(Ui.dp(a, 1.5f), 0xFF14161E);
                    dot.setBackground(gd);
                    avWrap.addView(dot, new FrameLayout.LayoutParams(
                            Ui.dp(a, 11), Ui.dp(a, 11), Gravity.BOTTOM | Gravity.END));
                }
                row.addView(avWrap);

                LinearLayout info = Ui.col(a);
                info.setPadding(Ui.dp(a, 10), 0, Ui.dp(a, 8), 0);
                info.addView(Ui.tvBold(a, u.displayName, 13.5f, Color.WHITE));
                info.addView(Ui.tv(a, u.isOnline ? "Active now · Sends Live Video to Inbox" : "Tap Invite · Sends Live Video to Inbox", 11f,
                        u.isOnline ? 0xFF69F0AE : 0x99FFFFFF));
                row.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));

                final TextView invBtn = Ui.roundChip(a, "Invite", 0xFFFE2C55, Color.WHITE, 12);
                Ui.pad(invBtn, 14, 7, 14, 7);
                invBtn.setOnClickListener(v -> {
                    invBtn.setText("Sent to Inbox");
                    invBtn.setEnabled(false);
                    invBtn.setBackground(Ui.round(a, 0x33FFFFFF, 16));
                    sendDirectLiveInviteToUser(me, u.uid, resolvedHostUid, hostName, resolvedHostPhoto, liveTitle, liveId);
                    Ui.toast(a, "Live video invite sent to " + u.displayName + "'s inbox");
                });
                row.addView(invBtn);
                listCol.addView(row);
            }
        });

        inviteAllBtn.setOnClickListener(v -> {
            int sent = 0;
            for (Models.User u : friendUsers) {
                sendDirectLiveInviteToUser(me, u.uid, resolvedHostUid, hostName, resolvedHostPhoto, liveTitle, liveId);
                if (++sent >= 25) break;
            }
            inviteAllBtn.setText("Sent Live Video to " + sent + " Inboxes");
            inviteAllBtn.setEnabled(false);
            inviteAllBtn.setBackground(Ui.round(a, 0xFF2E7D32, 20));
            Ui.toast(a, "Live video invite sent to " + sent + " friends' inboxes");
        });

        presentBottomDialog(dialog, sheet);
    }

    public static void sendDirectLiveInviteToUser(@NonNull String me,
                                                  @NonNull String targetUid,
                                                  @NonNull String hostName,
                                                  @NonNull String liveTitle,
                                                  @NonNull String liveId) {
        sendDirectLiveInviteToUser(me, targetUid, me, hostName,
                Fb.myPhoto() == null ? "" : Fb.myPhoto(), liveTitle, liveId);
    }

    public static void sendDirectLiveInviteToUser(@NonNull String me,
                                                  @NonNull String targetUid,
                                                  @NonNull String hostUid,
                                                  @NonNull String hostName,
                                                  @NonNull String hostPhoto,
                                                  @NonNull String liveTitle,
                                                  @NonNull String liveId) {
        if (me.isEmpty() || targetUid.isEmpty() || me.equals(targetUid)) return;
        String cleanHostUid = hostUid.isEmpty() ? me : hostUid;
        String cleanHostName = hostName.isEmpty() ? Fb.myName() : hostName;
        String cleanHostPhoto = !hostPhoto.isEmpty() ? hostPhoto : (Fb.myPhoto() == null ? "" : Fb.myPhoto());
        String cleanTitle = liveTitle.isEmpty() ? (cleanHostName + " is Live!") : liveTitle;

        Notify.send(targetUid, "live_invite",
                "invited you to join LIVE: " + cleanTitle, liveId);

        // Deliver a TikTok-style clickable Live Video card directly into their 1:1 Inbox!
        String cid = Fb.chatId(me, targetUid);
        String inviteText = "LIVE Video: " + cleanHostName + " invited you to join \"" + cleanTitle + "\" — Tap video to join Live!";
        long now = System.currentTimeMillis();
        DocumentReference chatRef = Fb.db().collection("chats").document(cid);
        Map<String, Object> msg = new HashMap<>();
        msg.put("senderId", me);
        msg.put("senderName", Fb.myName());
        msg.put("senderPhoto", Fb.myPhoto() == null ? "" : Fb.myPhoto());
        msg.put("receiverId", targetUid);
        msg.put("text", inviteText);
        msg.put("type", "live_invite");
        msg.put("liveId", liveId);
        msg.put("liveHostId", cleanHostUid);
        msg.put("liveHostName", cleanHostName);
        msg.put("liveHostPhoto", cleanHostPhoto);
        msg.put("liveTitle", cleanTitle);
        msg.put("imageUrl", cleanHostPhoto);
        msg.put("isDelivered", false);
        msg.put("isSeen", false);
        msg.put("createdAt", FieldValue.serverTimestamp());
        msg.put("createdAtMs", now);
        chatRef.collection("messages").add(msg);

        Map<String, Object> room = new HashMap<>();
        room.put("participants", Arrays.asList(me, targetUid));
        room.put("lastMessage", "LIVE Video · Tap to join " + cleanHostName + "'s Live");
        room.put("lastMessageTime", FieldValue.serverTimestamp());
        room.put("lastMessageTimeMs", now);
        Map<String, Object> pNames = new HashMap<>();
        pNames.put(me, Fb.myName());
        Models.User cachedPeer = FastCache.getUser(targetUid);
        if (cachedPeer != null && cachedPeer.displayName != null && !cachedPeer.displayName.isEmpty()) {
            pNames.put(targetUid, cachedPeer.displayName);
        }
        room.put("participantNames", pNames);
        Map<String, Object> pPhotos = new HashMap<>();
        pPhotos.put(me, Fb.myPhoto() == null ? "" : Fb.myPhoto());
        if (cachedPeer != null && cachedPeer.photoUrl != null && !cachedPeer.photoUrl.isEmpty()) {
            pPhotos.put(targetUid, cachedPeer.photoUrl);
        }
        room.put("participantPhotos", pPhotos);
        Map<String, Object> unread = new HashMap<>();
        unread.put(targetUid, FieldValue.increment(1));
        unread.put(me, 0);
        room.put("unreadCount", unread);
        chatRef.set(room, SetOptions.merge());
    }

    // ==================== 5. TIKTOK PK BATTLE MATCHMAKING DRAWER ====================

    public static void showPkChallengeDrawer(@NonNull Activity a,
                                             @NonNull String myLiveId,
                                             boolean pkCurrentlyActive,
                                             @NonNull Map<String, String> roomViewerNames,
                                             @NonNull Map<String, String> roomViewerPhotos,
                                             @NonNull PkChallengeCallback cb) {
        final String me = Fb.uid();
        if (me == null) return;

        final AlertDialog dialog = new AlertDialog.Builder(a).create();
        LinearLayout sheet = Ui.col(a);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF214161E);
        bg.setCornerRadii(new float[]{
                Ui.dp(a, 22), Ui.dp(a, 22), Ui.dp(a, 22), Ui.dp(a, 22), 0, 0, 0, 0});
        sheet.setBackground(bg);
        Ui.pad(sheet, 16, 16, 16, 22);

        LinearLayout head = Ui.row(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.tvBold(a, "Live Match / PK Battle", 16, Color.WHITE),
                new LinearLayout.LayoutParams(0, -2, 1f));
        ImageView close = com.friendbook.social.core.StudioVectorIcons.iconView(a, "close", 18, 0xAAFFFFFF);
        close.setOnClickListener(v -> dialog.dismiss());
        head.addView(close, new LinearLayout.LayoutParams(Ui.dp(a, 30), Ui.dp(a, 30)));
        sheet.addView(head);

        TextView desc = Ui.tv(a,
                "Battle another creator live side-by-side! Viewers send Gifts to boost your Red vs Blue PK bar before the timer runs out.",
                12f, 0xBBFFFFFF);
        desc.setPadding(0, Ui.dp(a, 4), 0, Ui.dp(a, 10));
        sheet.addView(desc);

        if (pkCurrentlyActive) {
            TextView endPkBtn = Ui.tvBold(a, "Leave Current Live Match (Return to Solo Live)", 13.5f, Color.WHITE);
            endPkBtn.setGravity(Gravity.CENTER);
            endPkBtn.setBackground(Ui.round(a, 0xFFE53935, 20));
            Ui.pad(endPkBtn, 14, 11, 14, 11);
            endPkBtn.setOnClickListener(v -> {
                dialog.dismiss();
                cb.onEndCurrentPk();
            });
            LinearLayout.LayoutParams epLp = new LinearLayout.LayoutParams(-1, -2);
            epLp.bottomMargin = Ui.dp(a, 12);
            sheet.addView(endPkBtn, epLp);
        }

        // Duration Selector: 1m / 3m / 5m
        final int[] durationsSec = {60, 180, 300};
        final String[] durLabels = {"1 Minute Blitz", "3 Minute Classic", "5 Minute Pro"};
        final int[] selDurIdx = {1}; // Default 3 minutes
        final TextView[] durBtns = new TextView[3];

        LinearLayout durRow = Ui.row(a);
        Runnable refreshDur = () -> {
            for (int i = 0; i < 3; i++) {
                boolean on = (i == selDurIdx[0]);
                durBtns[i].setBackground(Ui.round(a, on ? 0xFFFE2C55 : 0x22FFFFFF, 16));
            }
        };
        for (int i = 0; i < 3; i++) {
            final int di = i;
            TextView b = Ui.tvBold(a, durLabels[i], 12f, Color.WHITE);
            b.setGravity(Gravity.CENTER);
            Ui.pad(b, 8, 9, 8, 9);
            b.setOnClickListener(v -> {
                selDurIdx[0] = di;
                refreshDur.run();
            });
            durBtns[i] = b;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i > 0) lp.leftMargin = Ui.dp(a, 6);
            durRow.addView(b, lp);
        }
        refreshDur.run();
        sheet.addView(durRow);

        // Quick Random Match Button
        final List<Map<String, Object>> otherLiveRooms = new ArrayList<>();
        final TextView quickMatchBtn = com.friendbook.social.core.StudioVectorIcons.iconChip(
                a, "dice", "Random Quick Match (Auto-Pair)", 13.5f, 0, Color.WHITE, Color.WHITE);
        quickMatchBtn.setGravity(Gravity.CENTER);
        GradientDrawable qmBg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFFFE2C55, 0xFF00B2FF});
        qmBg.setCornerRadius(Ui.dp(a, 20));
        quickMatchBtn.setBackground(qmBg);
        Ui.pad(quickMatchBtn, 14, 11, 14, 11);
        LinearLayout.LayoutParams qmLp = new LinearLayout.LayoutParams(-1, -2);
        qmLp.topMargin = Ui.dp(a, 12);
        qmLp.bottomMargin = Ui.dp(a, 10);
        sheet.addView(quickMatchBtn, qmLp);

        ScrollView sv = new ScrollView(a);
        final LinearLayout listCol = Ui.col(a);
        sv.addView(listCol);
        sheet.addView(sv, new LinearLayout.LayoutParams(-1, Ui.dp(a, 240)));

        TextView loading = Ui.tv(a, "Scanning active Live creators & room viewers...", 12.5f, 0xAAFFFFFF);
        Ui.pad(loading, 4, 10, 4, 10);
        listCol.addView(loading);

        com.friendbook.social.core.CloudflareStore.query(
                com.friendbook.social.core.DocQuery.of("lives")
                        .whereEqualTo("status", "live").limit(25).build(),
                page -> {
                    if (a.isFinishing()) return;
                    listCol.removeAllViews();
                    otherLiveRooms.clear();
                    long now = System.currentTimeMillis();

                    {
                        for (Map<String, Object> m : page.documents) {
                            if (myLiveId.equals(Format.str(m, "id"))) continue;
                            if (m == null) continue;
                            String hUid = Format.str(m, "hostId");
                            if (hUid.isEmpty() || hUid.equals(me)) continue;
                            long hb = Format.longOf(m, "lastHeartbeatMs");
                            if (hb > 0 && (now - hb) > 150_000L) continue;
                            Map<String, Object> copy = new HashMap<>(m);
                            copy.put("_id", Format.str(m, "id"));
                            otherLiveRooms.add(copy);
                        }
                    }

                    // 1. Active Live Streamers Section
                    TextView sec1 = Ui.tvBold(a, "CREATORS LIVE RIGHT NOW (" + otherLiveRooms.size() + ")", 11f, 0xFFFFD54F);
                    sec1.setPadding(0, Ui.dp(a, 4), 0, Ui.dp(a, 6));
                    listCol.addView(sec1);

                    if (otherLiveRooms.isEmpty()) {
                        TextView none = Ui.tv(a,
                                "No other solo hosts live right now — invite any Viewer below to battle side-by-side!",
                                12f, 0x99FFFFFF);
                        none.setPadding(0, 0, 0, Ui.dp(a, 10));
                        listCol.addView(none);
                    } else {
                        for (final Map<String, Object> rm : otherLiveRooms) {
                            final String tLiveId = Format.str(rm, "_id");
                            final String tHostUid = Format.str(rm, "hostId");
                            final String tName = Format.strOr(rm, "hostName", "Creator");
                            final String tPhoto = Format.strOr(rm, "hostPhotoUrl", Format.str(rm, "hostPhoto"));
                            int vCount = Math.max(1, Format.intOf(rm, "viewers"));

                            LinearLayout row = buildOpponentRow(a, tPhoto, tName,
                                    "Live now · " + vCount + " watching", "PK Battle",
                                    v -> {
                                        dialog.dismiss();
                                        cb.onChallengeLiveHost(tLiveId, tHostUid, tName, tPhoto,
                                                durationsSec[selDurIdx[0]]);
                                    });
                            listCol.addView(row);
                        }
                    }

                    // 2. Viewers in Current Room (can join 2-User Split-Screen PK Battle!)
                    Map<String, String> candidates = new LinkedHashMap<>();
                    for (Map.Entry<String, String> e : roomViewerNames.entrySet()) {
                        if (!e.getKey().equals(me)) {
                            candidates.put(e.getKey(), e.getValue());
                        }
                    }
                    TextView sec2 = Ui.tvBold(a, "CHALLENGE VIEWER IN YOUR ROOM (" + candidates.size() + ")", 11f, 0xFF00B2FF);
                    sec2.setPadding(0, Ui.dp(a, 10), 0, Ui.dp(a, 6));
                    listCol.addView(sec2);

                    if (candidates.isEmpty()) {
                        listCol.addView(Ui.tv(a, "Invite friends below to send a Live Video to their Inbox & battle!", 12f, 0x88FFFFFF));
                    } else {
                        for (Map.Entry<String, String> e : candidates.entrySet()) {
                            final String vUid = e.getKey();
                            final String vName = e.getValue();
                            final String vPhoto = roomViewerPhotos.getOrDefault(vUid, "");
                            LinearLayout row = buildOpponentRow(a, vPhoto, vName,
                                    "Watching your Live · Ready for Split-Screen PK", "Invite PK",
                                    v -> {
                                        dialog.dismiss();
                                        cb.onChallengeViewerOrFriend(vUid, vName, vPhoto,
                                                durationsSec[selDurIdx[0]]);
                                    });
                            listCol.addView(row);
                        }
                    }

                    // 3. Invite Any Friend via Inbox Live Video Card!
                    final LinearLayout friendsSecCol = Ui.col(a);
                    listCol.addView(friendsSecCol);
                    com.friendbook.social.core.CloudflareStore.list("users", 25, uSnap -> {
                        if (a.isFinishing() || uSnap == null) return;
                        List<Models.User> fList = new ArrayList<>();
                        for (Map<String, Object> ud : uSnap) {
                            Models.User u = Models.User.fromMap(Format.str(ud, "id"), ud);
                            if (u.uid == null || u.uid.equals(me) || u.deactivated) continue;
                            if (candidates.containsKey(u.uid)) continue;
                            if (Fb.me != null && Fb.me.blocked != null && Fb.me.blocked.contains(u.uid)) continue;
                            FastCache.putUser(u);
                            fList.add(u);
                        }
                        if (fList.isEmpty()) return;
                        fList.sort((u1, u2) -> Boolean.compare(u2.isOnline, u1.isOnline));
                        TextView sec3 = Ui.tvBold(a, "INVITE FRIENDS VIA INBOX LIVE VIDEO (" + fList.size() + ")", 11f, 0xFF69F0AE);
                        sec3.setPadding(0, Ui.dp(a, 12), 0, Ui.dp(a, 6));
                        friendsSecCol.addView(sec3);
                        for (final Models.User fu : fList) {
                            LinearLayout row = buildOpponentRow(a, fu.photoUrl, fu.displayName,
                                    (fu.isOnline ? "Online · " : "") + "Sends clickable Live Video to Inbox",
                                    "Invite Match",
                                    v -> {
                                        dialog.dismiss();
                                        cb.onChallengeViewerOrFriend(
                                                fu.uid,
                                                fu.displayName == null ? "Friend" : fu.displayName,
                                                fu.photoUrl == null ? "" : fu.photoUrl,
                                                durationsSec[selDurIdx[0]]);
                                    });
                            friendsSecCol.addView(row);
                        }
                    });
                });

        quickMatchBtn.setOnClickListener(v -> {
            if (!otherLiveRooms.isEmpty()) {
                Map<String, Object> rm = otherLiveRooms.get(0);
                dialog.dismiss();
                cb.onChallengeLiveHost(
                        Format.str(rm, "_id"),
                        Format.str(rm, "hostId"),
                        Format.strOr(rm, "hostName", "Creator"),
                        Format.strOr(rm, "hostPhotoUrl", Format.str(rm, "hostPhoto")),
                        durationsSec[selDurIdx[0]]);
            } else {
                for (Map.Entry<String, String> e : roomViewerNames.entrySet()) {
                    if (!e.getKey().equals(me)) {
                        dialog.dismiss();
                        cb.onChallengeViewerOrFriend(
                                e.getKey(), e.getValue(),
                                roomViewerPhotos.getOrDefault(e.getKey(), ""),
                                durationsSec[selDurIdx[0]]);
                        return;
                    }
                }
                Ui.toast(a, "No other creator or viewer online right now — tap Invite first");
            }
        });

        presentBottomDialog(dialog, sheet);
    }

    private static LinearLayout buildOpponentRow(Activity a, String photo, String name,
                                                 String subtitle, String ctaText,
                                                 View.OnClickListener click) {
        LinearLayout row = Ui.row(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Ui.pad(row, 4, 7, 4, 7);

        ImageView av = new ImageView(a);
        Ui.avatar(a, av, photo);
        row.addView(av, new LinearLayout.LayoutParams(Ui.dp(a, 40), Ui.dp(a, 40)));

        LinearLayout col = Ui.col(a);
        col.setPadding(Ui.dp(a, 10), 0, Ui.dp(a, 8), 0);
        col.addView(Ui.tvBold(a, name, 13.5f, Color.WHITE));
        col.addView(Ui.tv(a, subtitle, 11f, 0xAAFFFFFF));
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView btn = Ui.roundChip(a, ctaText, 0xFFFE2C55, Color.WHITE, 11.5f);
        Ui.pad(btn, 12, 7, 12, 7);
        btn.setOnClickListener(click);
        row.addView(btn);
        return row;
    }

    // ==================== 6. MUTE & BLOCK MODERATION MANAGER ====================

    public static void blockUserPermanently(@NonNull Activity a,
                                            @NonNull String targetUid,
                                            @NonNull String targetName,
                                            @Nullable Runnable afterBlocked) {
        String me = Fb.uid();
        if (me == null || targetUid.isEmpty() || targetUid.equals(me)) return;
        Ui.confirm(a, "Block " + targetName + "?",
                targetName + " will be removed from your Live immediately and blocked across friendbook.",
                "Block", () -> {
                    Fb.db().collection("users").document(me)
                            .set(Fb.map("blockedUsers", FieldValue.arrayUnion(targetUid)), SetOptions.merge());
                    if (Fb.me != null && Fb.me.blocked != null && !Fb.me.blocked.contains(targetUid)) {
                        Fb.me.blocked.add(targetUid);
                    }
                    if (afterBlocked != null) afterBlocked.run();
                    Ui.toast(a, "Blocked " + targetName);
                });
    }

    public static void showModerationManagerDialog(@NonNull Activity a,
                                                   @NonNull Set<String> mutedIds,
                                                   @NonNull Set<String> kickedIds,
                                                   @NonNull Map<String, String> namesMap,
                                                   @NonNull ModerationActionCallback cb) {
        List<String> items = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();

        for (final String uid : mutedIds) {
            String nm = namesMap.getOrDefault(uid, uid);
            items.add("Unmute " + nm);
            actions.add(() -> cb.onUnmuteUser(uid));
        }
        for (final String uid : kickedIds) {
            String nm = namesMap.getOrDefault(uid, uid);
            items.add("Allow " + nm + " back into Live");
            actions.add(() -> cb.onUnkickUser(uid));
        }

        if (items.isEmpty()) {
            Ui.info(a, "Mute & Block List",
                    "No viewers are currently muted or removed in this Live.\n\nTip: Tap any viewer's comment in Live Chat to Mute, Kick, or Permanently Block them.");
            return;
        }
        Ui.choose(a, "Muted & Removed Viewers", items.toArray(new String[0]), w -> actions.get(w).run());
    }

    // ==================== 7. TIKTOK FULL-SCREEN POST-LIVE ANALYTICS SCREEN ====================

    public static View buildPostLiveSummaryOverlay(@NonNull Activity a,
                                                   @Nullable String hostPhoto,
                                                   @NonNull String hostName,
                                                   @NonNull String liveTitle,
                                                   long durationSec,
                                                   int totalViewers,
                                                   int peakViewers,
                                                   int totalGiftsCount,
                                                   int totalGiftCoins,
                                                   int totalLikes,
                                                   int totalComments,
                                                   int pkWins,
                                                   int pkPlayed,
                                                   @NonNull Map<String, Integer> supporterStars,
                                                   @NonNull Map<String, String> supporterNames,
                                                   boolean isHost,
                                                   @Nullable Runnable onShareRecap,
                                                   @NonNull Runnable onClose) {
        FrameLayout overlay = new FrameLayout(a);
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFA0E1018, 0xFC141824, 0xFF0B0D13});
        overlay.setBackground(bg);
        overlay.setClickable(true);

        ScrollView sv = new ScrollView(a);
        LinearLayout col = Ui.col(a);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        Ui.pad(col, 20, 36, 20, 32);
        sv.addView(col);
        overlay.addView(sv, new FrameLayout.LayoutParams(-1, -1));

        // Top Title
        TextView endBadge = Ui.tvBold(a, "LIVE ENDED", 22f, Color.WHITE);
        endBadge.setGravity(Gravity.CENTER);
        col.addView(endBadge);

        TextView subTitle = Ui.tv(a, liveTitle, 13f, 0xAAFFFFFF);
        subTitle.setGravity(Gravity.CENTER);
        subTitle.setPadding(0, Ui.dp(a, 4), 0, Ui.dp(a, 18));
        col.addView(subTitle);

        // Glowing Host Avatar
        FrameLayout ring = new FrameLayout(a);
        GradientDrawable rBg = new GradientDrawable();
        rBg.setShape(GradientDrawable.OVAL);
        rBg.setColor(0x22FE2C55);
        rBg.setStroke(Ui.dp(a, 2.5f), 0xFFFE2C55);
        ring.setBackground(rBg);
        int ringPx = Ui.dp(a, 92);
        ImageView av = new ImageView(a);
        Ui.avatar(a, av, hostPhoto);
        ring.addView(av, new FrameLayout.LayoutParams(Ui.dp(a, 80), Ui.dp(a, 80), Gravity.CENTER));
        col.addView(ring, new LinearLayout.LayoutParams(ringPx, ringPx));

        TextView nameTv = Ui.tvBold(a, hostName, 17f, Color.WHITE);
        nameTv.setPadding(0, Ui.dp(a, 10), 0, Ui.dp(a, 18));
        col.addView(nameTv);

        // 2x3 Analytics Grid Card (Duration, Total Viewers, Gifts, Likes, Comments, PK Wins)
        LinearLayout gridCard = Ui.col(a);
        GradientDrawable gcBg = new GradientDrawable();
        gcBg.setColor(0x18FFFFFF);
        gcBg.setCornerRadius(Ui.dp(a, 18));
        gcBg.setStroke(Ui.dp(a, 1), 0x26FFFFFF);
        gridCard.setBackground(gcBg);
        Ui.pad(gridCard, 14, 16, 14, 16);

        String durFormatted = durationSec >= 3600
                ? String.format(Locale.US, "%d:%02d:%02d", durationSec / 3600, (durationSec % 3600) / 60, durationSec % 60)
                : String.format(Locale.US, "%02d:%02d", durationSec / 60, durationSec % 60);

        LinearLayout row1 = Ui.row(a);
        row1.addView(statBox(a, Format.count(totalViewers), "Total Viewers\n(Peak " + peakViewers + ")"),
                new LinearLayout.LayoutParams(0, -2, 1f));
        row1.addView(statBox(a, Format.count(totalGiftCoins), "Gift Stars Earned\n(" + totalGiftsCount + " gifts)"),
                new LinearLayout.LayoutParams(0, -2, 1f));
        row1.addView(statBox(a, durFormatted, "LIVE\nDuration"),
                new LinearLayout.LayoutParams(0, -2, 1f));
        gridCard.addView(row1);

        View div = new View(a);
        div.setBackgroundColor(0x22FFFFFF);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(-1, Ui.dp(a, 1));
        dLp.topMargin = Ui.dp(a, 14);
        dLp.bottomMargin = Ui.dp(a, 14);
        gridCard.addView(div, dLp);

        LinearLayout row2 = Ui.row(a);
        row2.addView(statBox(a, Format.count(totalLikes), "Hearts &\nLikes"),
                new LinearLayout.LayoutParams(0, -2, 1f));
        row2.addView(statBox(a, Format.count(totalComments), "Live\nComments"),
                new LinearLayout.LayoutParams(0, -2, 1f));
        row2.addView(statBox(a, pkWins + "/" + pkPlayed, "PK Battles\nWon"),
                new LinearLayout.LayoutParams(0, -2, 1f));
        gridCard.addView(row2);

        col.addView(gridCard, new LinearLayout.LayoutParams(-1, -2));

        // Top Gift Supporters Podium (if any gifts sent!)
        if (!supporterStars.isEmpty()) {
            LinearLayout podium = Ui.col(a);
            podium.setBackground(Ui.round(a, 0x16FFD54F, 16));
            Ui.pad(podium, 14, 12, 14, 12);
            LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(-1, -2);
            pLp.topMargin = Ui.dp(a, 14);

            podium.addView(com.friendbook.social.core.StudioVectorIcons.iconChip(
                    a, "trophy", "Top Gift Supporters", 13.5f, 0, 0xFFFFD54F, 0xFFFFD54F));
            List<Map.Entry<String, Integer>> sorted = new ArrayList<>(supporterStars.entrySet());
            Collections.sort(sorted, (x, y) -> Integer.compare(y.getValue(), x.getValue()));
            String[] medals = {"1. ", "2. ", "3. "};
            for (int i = 0; i < Math.min(3, sorted.size()); i++) {
                Map.Entry<String, Integer> e = sorted.get(i);
                String sName = supporterNames.getOrDefault(e.getKey(), "Supporter");
                TextView line = Ui.tv(a, medals[i] + sName + "  —  " + e.getValue() + " Stars", 12.5f, Color.WHITE);
                line.setPadding(0, Ui.dp(a, 4), 0, 0);
                podium.addView(line);
            }
            col.addView(podium, pLp);
        }

        // Action Buttons
        if (isHost && onShareRecap != null) {
            TextView shareBtn = com.friendbook.social.core.StudioVectorIcons.iconChip(
                a, "send", "Share LIVE Summary to Feed", 14.5f, 0, Color.WHITE, Color.WHITE);
            shareBtn.setGravity(Gravity.CENTER);
            shareBtn.setBackground(Ui.round(a, 0xFFFE2C55, 24));
            Ui.pad(shareBtn, 18, 13, 18, 13);
            LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(-1, -2);
            sLp.topMargin = Ui.dp(a, 22);
            shareBtn.setOnClickListener(v -> onShareRecap.run());
            col.addView(shareBtn, sLp);
        }

        TextView doneBtn = Ui.tvBold(a, "Done", 14.5f, Color.WHITE);
        doneBtn.setGravity(Gravity.CENTER);
        doneBtn.setBackground(Ui.round(a, 0x26FFFFFF, 24));
        Ui.pad(doneBtn, 18, 13, 18, 13);
        LinearLayout.LayoutParams dnLp = new LinearLayout.LayoutParams(-1, -2);
        dnLp.topMargin = Ui.dp(a, 10);
        doneBtn.setOnClickListener(v -> onClose.run());
        col.addView(doneBtn, dnLp);

        return overlay;
    }

    private static LinearLayout statBox(Activity a, String value, String label) {
        LinearLayout box = Ui.col(a);
        box.setGravity(Gravity.CENTER);
        Ui.pad(box, 4, 2, 4, 2);
        TextView v = Ui.tvBold(a, value, 17f, Color.WHITE);
        v.setGravity(Gravity.CENTER);
        TextView l = Ui.tv(a, label, 11f, 0xAAFFFFFF);
        l.setGravity(Gravity.CENTER);
        l.setPadding(0, Ui.dp(a, 3), 0, 0);
        box.addView(v);
        box.addView(l);
        return box;
    }

    // ==================== DIALOG PRESENTATION HELPER ====================

    private static void presentBottomDialog(AlertDialog dialog, View content) {
        dialog.setView(content);
        dialog.show();
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
    }

    private static String shortName(@Nullable String s) {
        if (s == null || s.trim().isEmpty()) return "Creator";
        String t = s.trim();
        return t.length() > 14 ? t.substring(0, 14) + "…" : t;
    }
}
