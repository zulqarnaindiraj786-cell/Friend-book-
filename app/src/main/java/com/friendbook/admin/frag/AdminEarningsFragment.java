package com.friendbook.admin.frag;

import android.app.AlertDialog;
import android.app.Fragment;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.friendbook.core.Admin;
import com.friendbook.core.Fb;
import com.friendbook.core.Format;
import com.friendbook.core.Ui;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Admin Earnings tab — the money picture:
 *approved payments split by type (blue/gold/diamond ticks, boosts, subscriptions 70/30),
 live-gift income (⭐ × Rs 10), monthly totals and a simple bar chart.
 */
public class AdminEarningsFragment extends Fragment {

    private TextView tickIncomeT, subsIncomeT, liveIncomeT, totalT, splitT, monthT;
    private BarChart chart;
    private ListenerRegistration payReg, giftReg;

    // running totals
    private long tickBlue, tickGold, tickDiamond, subs, liveStars;
    private final Map<String, Long> monthly = new LinkedHashMap<>();

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inf, ViewGroup container, Bundle b) {
        android.app.Activity a = getActivity();
        ScrollView sv = new ScrollView(a);
        LinearLayout body = Ui.col(a);
        body.setBackgroundColor(Ui.bg(a));
        Ui.pad(body, 12, 10, 12, 24);
        sv.addView(body);

        totalT = bigCard(body, "📊 Community Milestones Approved", "…", Ui.BLUE);
        tickIncomeT = row(body, "✓ Verified Badges Granted", "blue / gold / diamond reviews", Ui.BLUE);
        subsIncomeT = row(body, "⭐ VIP Fan Club Memberships", "free creator fan club", Ui.GREEN);
        liveIncomeT = row(body, "🔴 Live Community Stars", "total stars sent on live streams", Ui.RED);

        splitT = Ui.tv(a, "", 12, Ui.sub(a));
        Ui.pad(splitT, 4, 10, 4, 0);
        body.addView(splitT);

        // monthly chart
        TextView ch = Ui.tvBold(a, "📊 Monthly approvals (last 6 months)", 14, Ui.txt(a));
        Ui.pad(ch, 4, 16, 4, 6);
        body.addView(ch);
        LinearLayout chartCard = Ui.col(a);
        chartCard.setBackground(Ui.round(a, Ui.card(a), 14));
        Ui.pad(chartCard, 12, 10, 12, 10);
        chart = new BarChart(a);
        chartCard.addView(chart, new LinearLayout.LayoutParams(-1, Ui.dp(a, 160)));
        body.addView(chartCard, Ui.lpw(-2));
        monthT = Ui.tv(a, "", 11, Ui.sub(a));
        monthT.setGravity(Gravity.CENTER);
        Ui.pad(monthT, 0, 6, 0, 0);
        body.addView(monthT);

        // tools (moved from the old Stats tab)
        TextView tools = Ui.tvBold(a, "🛠 Tools", 13, Ui.txt(a));
        Ui.pad(tools, 4, 18, 4, 4);
        body.addView(tools);
        toolsRow(body, "📢 AdMob Native Advanced Settings",
                () -> a.startActivity(new android.content.Intent(a, com.friendbook.admin.AdMobSettingsActivity.class)));
        toolsRow(body, "📣 Broadcast to all users", () -> {
            android.app.Activity ac = getActivity();
            if (ac instanceof com.friendbook.admin.AdminPanelActivity)
                ((com.friendbook.admin.AdminPanelActivity) ac).showBroadcastDialog();
        });
        toolsRow(body, "🚩 Reports queue",
                () -> a.startActivity(new android.content.Intent(a, com.friendbook.admin.AdminReportsActivity.class)));
        toolsRow(body, "🗂 Content moderation",
                () -> a.startActivity(new android.content.Intent(a, com.friendbook.admin.AdminContentActivity.class)));

        return sv;
    }

    // ============================================================ UI helpers
    private TextView bigCard(LinearLayout parent, String label, String value, int color) {
        android.app.Activity a = getActivity();
        LinearLayout card = Ui.col(a);
        card.setBackground(Ui.round(a, Ui.card(a), 14));
        Ui.pad(card, 14, 14, 14, 14);
        card.addView(Ui.tv(a, label, 12, Ui.sub(a)));
        TextView v = Ui.tvBold(a, value, 26, color);
        card.addView(v);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, Ui.dp(a, 2), 0, Ui.dp(a, 10));
        parent.addView(card, p);
        return v;
    }

    private TextView row(LinearLayout parent, String title, String sub, int color) {
        android.app.Activity a = getActivity();
        LinearLayout card = Ui.row(a);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(Ui.round(a, Ui.card(a), 14));
        Ui.pad(card, 14, 10, 14, 10);
        LinearLayout txts = Ui.col(a);
        txts.addView(Ui.tvBold(a, title, 13, Ui.txt(a)));
        txts.addView(Ui.tv(a, sub, 11, Ui.sub(a)));
        card.addView(txts, new LinearLayout.LayoutParams(0, -2, 1));
        TextView v = Ui.tvBold(a, "…", 16, color);
        card.addView(v);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 0, 0, Ui.dp(a, 8));
        parent.addView(card, p);
        return v;
    }

    private void toolsRow(LinearLayout parent, String label, Runnable go) {
        android.app.Activity a = getActivity();
        TextView t = Ui.tvBold(a, label, 13, Ui.BLUE);
        t.setBackground(Ui.roundStroke(a, Ui.card(a), 10, Ui.div(a), 1));
        Ui.pad(t, 14, 10, 14, 10);
        t.setOnClickListener(v -> go.run());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 0, 0, Ui.dp(a, 6));
        parent.addView(t, p);
    }

    // ============================================================ DATA
    @Override
    public void onStart() {
        super.onStart();
        payReg = Fb.db().collection("payments")
                .whereEqualTo("status", "approved").limit(1000)
                .addSnapshotListener((snap, err) -> {
                    tickBlue = tickGold = tickDiamond = subs = 0;
                    monthly.clear();
                    seedMonths();
                    if (snap != null) {
                        for (DocumentSnapshot d : snap) {
                            Map<String, Object> m = d.getData();
                            String type = Format.str(m, "type");
                            long amount = Format.intOf(m, "amount");
                            boolean usd = "USD".equals(Format.str(m, "currency"));
                            String mk = Format.str(m, "createdMonth");
                            if (type == null) continue;
                            switch (type) {
                                case "tick_blue": tickBlue += amount; break;
                                case "tick_gold": tickGold += amount; break;
                                case "tick_diamond": tickDiamond += amount; break;
                                case "subscription": subs += amount; break; // USD
                            }
                            if (!mk.isEmpty() && monthly.containsKey(mk) && !usd)
                                monthly.put(mk, monthly.get(mk) + amount);
                        }
                    }
                    render();
                });
        giftReg = Fb.db().collection("gifts").limit(1000)
                .addSnapshotListener((snap, err) -> {
                    liveStars = 0;
                    if (snap != null) for (DocumentSnapshot d : snap)
                        liveStars += Format.intOf(d.getData(), "stars");
                    render();
                });
    }

    @Override
    public void onStop() {
        super.onStop();
        if (payReg != null) payReg.remove();
        if (giftReg != null) giftReg.remove();
    }

    private void seedMonths() {
        Calendar cal = Calendar.getInstance();
        for (int i = 5; i >= 0; i--) {
            Calendar c = (Calendar) cal.clone();
            c.add(Calendar.MONTH, -i);
            monthly.put(String.format(Locale.US, "%04d-%02d",
                    c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1), 0L);
        }
    }

    private void render() {
        if (totalT == null) return;
        long tickTotal = tickBlue + tickGold + tickDiamond;
        long totalMilestones = tickTotal + subs + liveStars;

        totalT.setText(Format.count(totalMilestones) + " milestones");
        tickIncomeT.setText(Format.count(tickTotal) + " ✓");
        subsIncomeT.setText(Format.count(subs) + " ⭐");
        liveIncomeT.setText("⭐ " + Format.count(liveStars));
        splitT.setText("Badges — Blue: " + Format.count(tickBlue)
                + " · Gold: " + Format.count(tickGold)
                + " · Diamond: " + Format.count(tickDiamond)
                + "\nLive stars sent: ⭐ " + Format.count(liveStars));
        long current = 0;
        String lastKey = null;
        for (Map.Entry<String, Long> e : monthly.entrySet()) { lastKey = e.getKey(); }
        if (lastKey != null) current = monthly.get(lastKey);
        monthT.setText("This month activity: " + Format.count(current));
        chart.setData(monthly);
    }

    /** Simple 6-month bar chart (no libraries). */
    static class BarChart extends View {
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint lbl = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Map<String, Long> data = new LinkedHashMap<>();

        BarChart(android.content.Context c) {
            super(c);
            bar.setColor(0xFF1877F2);
            lbl.setColor(Ui.isDark(c) ? 0xFFB0B3B8 : 0xFF65676B);
            lbl.setTextSize(Ui.dp(c, 10));
            lbl.setTextAlign(Paint.Align.CENTER);
        }

        void setData(Map<String, Long> d) {
            data = new LinkedHashMap<>(d);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (data.isEmpty()) return;
            long max = 1;
            for (Long v : data.values()) if (v != null && v > max) max = v;
            int w = getWidth(), h = getHeight();
            int n = data.size();
            float slot = w / (float) n;
            float bw = slot * 0.55f;
            int i = 0;
            for (Map.Entry<String, Long> e : data.entrySet()) {
                long v = e.getValue() == null ? 0 : e.getValue();
                float bh = Math.max(h * 0.04f, (h - Ui.dp(getContext(), 22)) * (v / (float) max));
                float left = i * slot + (slot - bw) / 2f;
                RectF r = new RectF(left, h - Ui.dp(getContext(), 18) - bh, left + bw, h - Ui.dp(getContext(), 18));
                canvas.drawRoundRect(r, 10, 10, bar);
                canvas.drawText(e.getKey().substring(5), left + bw / 2f, h - Ui.dp(getContext(), 4), lbl);
                i++;
            }
        }
    }
}
