package com.friendbook.social.live;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Format;
import com.friendbook.social.core.Ui;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dedicated Live Discovery Hub:
 * - Instant 1-tap "Go Live Studio" launcher
 * - Category filter chips (All, Chatting, Gaming, Music, Q&A, Sports, Lifestyle, Cooking, Tech)
 * - Real-time active live broadcast cards with live video snapshot preview, viewer count & stars
 */
public class LiveHubActivity extends Activity {

    private static final String[] CATEGORIES = {
            "🔥 All", "💬 Chatting", "🎮 Gaming", "🎵 Music",
            "📚 Q&A", "🏏 Sports", "✨ Lifestyle", "🍳 Cooking", "💻 Tech"
    };

    private LinearLayout cardsContainer;
    private LinearLayout chipRow;
    private TextView activeCountTv;
    private String selectedCategory = "🔥 All";
    private final List<Map<String, Object>> allActiveLives = new ArrayList<>();
    private ListenerRegistration reg;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));

        // Crisp white top header bar with vector <- back arrow + quick Go Live CTA
        LinearLayout topBar = Ui.pageBar(this, "🔴 Live Broadcasts");
        TextView topGoBtn = Ui.roundChip(this, "🔴 Go Live", 0xFFE41E3F, 0xFFFFFFFF, 12);
        Ui.pad(topGoBtn, 14, 7, 14, 7);
        topGoBtn.setOnClickListener(v -> launchHostStudio());
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(-2, -2);
        btnLp.setMargins(0, 0, Ui.dp(this, 12), 0);
        topBar.addView(topGoBtn, btnLp);
        root.addView(topBar);
        root.addView(Ui.divider(this));

        ScrollView sv = new ScrollView(this);
        LinearLayout body = Ui.col(this);
        Ui.pad(body, 12, 12, 12, 28);
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Hero Go-Live Studio Card
        LinearLayout hero = Ui.row(this);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setBackground(Ui.round(this, 0xFF18191A, 16));
        Ui.pad(hero, 16, 16, 16, 16);

        LinearLayout heroText = Ui.col(this);
        heroText.addView(Ui.tvBold(this, "🎥 Start a Real Live Broadcast", 16, 0xFFFFFFFF));
        TextView sub = Ui.tv(this,
                "Stream live camera & voice, invite a Co-Host, run Polls & Q&A, and earn ⭐ Stars!",
                12, 0xCCFFFFFF);
        sub.setPadding(0, Ui.dp(this, 4), 0, 0);
        heroText.addView(sub);
        hero.addView(heroText, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView startChip = Ui.roundChip(this, "Start Live", 0xFFE41E3F, 0xFFFFFFFF, 13);
        Ui.pad(startChip, 16, 10, 16, 10);
        startChip.setOnClickListener(v -> launchHostStudio());
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(-2, -2);
        scLp.setMargins(Ui.dp(this, 10), 0, 0, 0);
        hero.addView(startChip, scLp);
        hero.setOnClickListener(v -> launchHostStudio());
        body.addView(hero);

        // Category Filter Rail
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout.LayoutParams hsvLp = new LinearLayout.LayoutParams(-1, -2);
        hsvLp.setMargins(0, Ui.dp(this, 12), 0, Ui.dp(this, 8));
        chipRow = Ui.row(this);
        hsv.addView(chipRow);
        body.addView(hsv, hsvLp);
        rebuildCategoryChips();

        activeCountTv = Ui.tvBold(this, "🔴 Checking active live rooms...", 13, Ui.sub(this));
        Ui.pad(activeCountTv, 4, 4, 4, 8);
        body.addView(activeCountTv);

        cardsContainer = Ui.col(this);
        body.addView(cardsContainer);

        setContentView(root);
    }

    private void launchHostStudio() {
        Intent i = new Intent(this, LiveActivity.class);
        i.putExtra("host", true);
        startActivity(i);
    }

    private void rebuildCategoryChips() {
        chipRow.removeAllViews();
        for (final String cat : CATEGORIES) {
            boolean sel = cat.equals(selectedCategory);
            TextView c = Ui.roundChip(this, cat,
                    sel ? 0xFFE41E3F : Ui.card(this),
                    sel ? 0xFFFFFFFF : Ui.txt(this), 12);
            Ui.pad(c, 14, 7, 14, 7);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(0, 0, Ui.dp(this, 8), 0);
            c.setOnClickListener(v -> {
                selectedCategory = cat;
                rebuildCategoryChips();
                renderCards();
            });
            chipRow.addView(c, lp);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        reg = Fb.db().collection("lives")
                .whereEqualTo("status", "live")
                .limit(40)
                .addSnapshotListener((snap, err) -> {
                    allActiveLives.clear();
                    long now = System.currentTimeMillis();
                    if (snap != null) {
                        for (DocumentSnapshot d : snap.getDocuments()) {
                            Map<String, Object> raw = d.getData();
                            if (raw == null) continue;
                            long hb = Format.longOf(raw, "lastHeartbeatMs");
                            // Skip crashed/abandoned live rooms with stale heartbeats (> 2.5 min)
                            if (hb > 0 && (now - hb) > 150_000L) continue;
                            Map<String, Object> m = new HashMap<>(raw);
                            m.put("_id", d.getId());
                            allActiveLives.add(m);
                        }
                    }
                    Collections.sort(allActiveLives, (a, b) -> {
                        int va = Math.max(Format.intOf(a, "viewers"), Format.intOf(a, "viewerCount"));
                        int vb = Math.max(Format.intOf(b, "viewers"), Format.intOf(b, "viewerCount"));
                        if (vb != va) return Integer.compare(vb, va);
                        return Long.compare(Format.longOf(b, "startedAtMs"), Format.longOf(a, "startedAtMs"));
                    });
                    renderCards();
                });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (reg != null) {
            reg.remove();
            reg = null;
        }
    }

    private void renderCards() {
        if (isFinishing() || cardsContainer == null) return;
        cardsContainer.removeAllViews();

        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> m : allActiveLives) {
            if ("🔥 All".equals(selectedCategory)
                    || selectedCategory.equalsIgnoreCase(Format.str(m, "category"))) {
                filtered.add(m);
            }
        }

        activeCountTv.setText(filtered.isEmpty()
                ? "No active broadcasts in this category right now"
                : "🔴 " + filtered.size() + " Live broadcast" + (filtered.size() == 1 ? "" : "s") + " on air now");

        if (filtered.isEmpty()) {
            LinearLayout empty = Ui.col(this);
            empty.setGravity(Gravity.CENTER);
            empty.setBackground(Ui.roundStroke(this, Ui.card(this), 16, Ui.div(this), 1));
            Ui.pad(empty, 24, 32, 24, 32);
            empty.addView(Ui.tvBold(this, "📡 Nobody is streaming here right now", 15, Ui.txt(this)));
            TextView sub = Ui.tv(this,
                    "Be the first to go live! Your followers and friends will see your stream immediately on Home.",
                    13, Ui.sub(this));
            sub.setGravity(Gravity.CENTER);
            sub.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 14));
            empty.addView(sub);
            TextView btn = Ui.roundChip(this, "🔴 Go Live Now", 0xFFE41E3F, 0xFFFFFFFF, 13);
            Ui.pad(btn, 20, 10, 20, 10);
            btn.setOnClickListener(v -> launchHostStudio());
            empty.addView(btn);
            cardsContainer.addView(empty);
            return;
        }

        for (final Map<String, Object> m : filtered) {
            cardsContainer.addView(buildLiveCard(m));
        }
    }

    private View buildLiveCard(final Map<String, Object> m) {
        final String lid = Format.str(m, "_id");
        final String hostId = Format.str(m, "hostId");
        String hostName = Format.strOr(m, "hostName", "Creator");
        String hostPhoto = Format.str(m, "hostPhotoUrl");
        if (hostPhoto.isEmpty()) hostPhoto = Format.str(m, "hostPhoto");
        String title = Format.strOr(m, "title", hostName + " is Live 🔴");
        String cat = Format.strOr(m, "category", "💬 Chatting");
        int viewers = Math.max(1, Math.max(Format.intOf(m, "viewers"), Format.intOf(m, "viewerCount")));
        int stars = Math.max(Format.intOf(m, "stars"), Format.intOf(m, "totalStars"));
        String coHostName = Format.str(m, "coHostName");
        String frameB64 = Format.str(m, "liveFrameB64");

        LinearLayout card = Ui.col(this);
        card.setBackground(Ui.roundStroke(this, Ui.card(this), 16, Ui.div(this), 1));
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(-1, -2);
        cLp.setMargins(0, 0, 0, Ui.dp(this, 12));
        card.setLayoutParams(cLp);

        // Live Video Stage Preview Box (200dp tall)
        FrameLayout previewBox = new FrameLayout(this);
        previewBox.setBackground(Ui.round(this, 0xFF121316, 14));
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 200));
        pbLp.setMargins(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), 0);

        ImageView previewImg = new ImageView(this);
        previewImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        boolean decodedPreview = false;
        if (!frameB64.isEmpty()) {
            try {
                byte[] bytes = Base64.decode(frameB64, Base64.DEFAULT);
                Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bmp != null) {
                    previewImg.setImageBitmap(bmp);
                    decodedPreview = true;
                }
            } catch (Throwable ignored) { }
        }
        previewBox.addView(previewImg, new FrameLayout.LayoutParams(-1, -1));

        if (!decodedPreview) {
            ImageView centerAv = new ImageView(this);
            Ui.avatar(this, centerAv, hostPhoto);
            FrameLayout.LayoutParams avLp = new FrameLayout.LayoutParams(Ui.dp(this, 76), Ui.dp(this, 76), Gravity.CENTER);
            previewBox.addView(centerAv, avLp);
        }

        // Top badges inside preview
        LinearLayout badges = Ui.row(this);
        badges.setGravity(Gravity.CENTER_VERTICAL);
        Ui.pad(badges, 10, 10, 10, 10);
        TextView livePill = Ui.roundChip(this, "🔴 LIVE", 0xFFE41E3F, 0xFFFFFFFF, 11);
        Ui.pad(livePill, 10, 4, 10, 4);
        badges.addView(livePill);

        TextView viewPill = Ui.roundChip(this, "👁 " + Format.count(viewers), 0xAA000000, 0xFFFFFFFF, 11);
        Ui.pad(viewPill, 10, 4, 10, 4);
        LinearLayout.LayoutParams vpLp = new LinearLayout.LayoutParams(-2, -2);
        vpLp.setMargins(Ui.dp(this, 6), 0, 0, 0);
        badges.addView(viewPill, vpLp);

        if (!coHostName.isEmpty()) {
            TextView coPill = Ui.roundChip(this, "🤝 + " + coHostName, 0xCC1877F2, 0xFFFFFFFF, 10);
            Ui.pad(coPill, 8, 4, 8, 4);
            LinearLayout.LayoutParams cpLp = new LinearLayout.LayoutParams(-2, -2);
            cpLp.setMargins(Ui.dp(this, 6), 0, 0, 0);
            badges.addView(coPill, cpLp);
        }
        previewBox.addView(badges, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));

        TextView catPill = Ui.roundChip(this, cat, 0x99000000, 0xFFFFFFFF, 10);
        Ui.pad(catPill, 10, 4, 10, 4);
        FrameLayout.LayoutParams catLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        catLp.setMargins(Ui.dp(this, 10), 0, 0, Ui.dp(this, 10));
        previewBox.addView(catPill, catLp);

        if (stars > 0) {
            TextView starPill = Ui.roundChip(this, "⭐ " + Format.count(stars), 0xCC222222, 0xFFFFD54F, 10);
            Ui.pad(starPill, 10, 4, 10, 4);
            FrameLayout.LayoutParams stLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
            stLp.setMargins(0, 0, Ui.dp(this, 10), Ui.dp(this, 10));
            previewBox.addView(starPill, stLp);
        }

        card.addView(previewBox, pbLp);

        // Host row + Watch Live CTA
        LinearLayout infoRow = Ui.row(this);
        infoRow.setGravity(Gravity.CENTER_VERTICAL);
        Ui.pad(infoRow, 12, 10, 12, 12);

        ImageView av = new ImageView(this);
        Ui.avatar(this, av, hostPhoto);
        infoRow.addView(av, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));

        LinearLayout meta = Ui.col(this);
        meta.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 8), 0);
        meta.addView(Ui.tvBold(this, title, 14, Ui.txt(this)));
        meta.addView(Ui.tv(this, hostName + "  ·  Tap to watch with live sound 🔊", 12, Ui.sub(this)));
        infoRow.addView(meta, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView watchBtn = Ui.roundChip(this, "Watch", Ui.BLUE, 0xFFFFFFFF, 12);
        Ui.pad(watchBtn, 14, 7, 14, 7);
        infoRow.addView(watchBtn);

        card.addView(infoRow);
        card.setOnClickListener(v -> {
            Intent i = new Intent(this, LiveActivity.class);
            i.putExtra("live_id", lid);
            i.putExtra("host_id", hostId);
            startActivity(i);
        });
        return card;
    }
}
