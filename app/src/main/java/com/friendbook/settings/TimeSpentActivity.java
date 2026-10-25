package com.friendbook.settings;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.friendbook.core.Icons;
import com.friendbook.core.Prefs;
import com.friendbook.core.Ui;

/**
 * ⏱ Your time on friendbook — Facebook-Lite Digital Wellbeing dashboard:
 * 7-day usage bar chart, daily average, daily time reminder & Quiet Mode controls.
 */
public class TimeSpentActivity extends Activity {

    private TextView avgTv, todayTv, limitSubTv;
    private LinearLayout chartBox;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        root.addView(Ui.pageBar(this, "Your time on friendbook"));

        ScrollView sv = new ScrollView(this);
        LinearLayout body = Ui.col(this);
        Ui.pad(body, 12, 12, 12, 24);

        // Hero stats + 7-day bar chart card
        LinearLayout hero = Ui.col(this);
        hero.setBackground(Ui.round(this, Ui.card(this), 14));
        Ui.pad(hero, 16, 16, 16, 16);

        TextView label = Ui.tv(this, "Daily average (last 7 days)", 12, Ui.sub(this));
        hero.addView(label);
        avgTv = Ui.tvBold(this, "0m", 32, Ui.BLUE);
        hero.addView(avgTv);
        todayTv = Ui.tv(this, "", 13, Ui.sub(this));
        todayTv.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 14));
        hero.addView(todayTv);

        chartBox = Ui.row(this);
        chartBox.setGravity(Gravity.BOTTOM);
        hero.addView(chartBox, new LinearLayout.LayoutParams(-1, Ui.dp(this, 150)));
        body.addView(hero);

        // Manage your time tools
        TextView hTools = Ui.tvBold(this, "MANAGE YOUR TIME", 12, Ui.sub(this));
        hTools.setPadding(Ui.dp(this, 4), Ui.dp(this, 16), 0, Ui.dp(this, 6));
        body.addView(hTools);

        LinearLayout toolsCard = Ui.col(this);
        toolsCard.setBackground(Ui.round(this, Ui.card(this), 14));

        // Daily time reminder
        LinearLayout remRow = Ui.row(this);
        Ui.pad(remRow, 14, 14, 14, 14);
        remRow.addView(Icons.icon(this, Icons.SNOOZE, 22, Icons.BLUE));
        LinearLayout remMid = Ui.col(this);
        LinearLayout.LayoutParams rmp = new LinearLayout.LayoutParams(0, -2, 1);
        rmp.leftMargin = Ui.dp(this, 14);
        remMid.addView(Ui.tvBold(this, "Daily time reminder", 15, Ui.txt(this)));
        limitSubTv = Ui.tv(this, "", 12, Ui.sub(this));
        remMid.addView(limitSubTv);
        remRow.addView(remMid, rmp);
        remRow.addView(Ui.tv(this, "›", 18, Ui.sub(this)));
        remRow.setOnClickListener(v -> pickReminder());
        toolsCard.addView(remRow);
        toolsCard.addView(Ui.divider(this));

        // Quiet mode switch
        LinearLayout qRow = Ui.row(this);
        Ui.pad(qRow, 14, 10, 14, 10);
        qRow.addView(Icons.icon(this, Icons.QUIET, 22, Icons.GRAY));
        LinearLayout qMid = Ui.col(this);
        LinearLayout.LayoutParams qmp = new LinearLayout.LayoutParams(0, -2, 1);
        qmp.leftMargin = Ui.dp(this, 14);
        qMid.addView(Ui.tvBold(this, "Quiet mode", 15, Ui.txt(this)));
        qMid.addView(Ui.tv(this, "Mutes notification badges & haptic alerts", 12, Ui.sub(this)));
        qRow.addView(qMid, qmp);
        Switch qSw = new Switch(this);
        qSw.setChecked(Prefs.quiet(this));
        qSw.setOnCheckedChangeListener((btn, on) -> {
            Prefs.setQuiet(this, on);
            Ui.toast(this, on ? "Quiet mode ON 🔕" : "Quiet mode OFF");
        });
        qRow.addView(qSw);
        toolsCard.addView(qRow);
        toolsCard.addView(Ui.divider(this));

        // Feed preferences shortcut
        LinearLayout fpRow = Ui.row(this);
        Ui.pad(fpRow, 14, 14, 14, 14);
        fpRow.addView(Icons.icon(this, Icons.STAR, 22, Icons.GRAY));
        LinearLayout fpMid = Ui.col(this);
        LinearLayout.LayoutParams fpmp = new LinearLayout.LayoutParams(0, -2, 1);
        fpmp.leftMargin = Ui.dp(this, 14);
        fpMid.addView(Ui.tvBold(this, "Control your Feed", 15, Ui.txt(this)));
        fpMid.addView(Ui.tv(this, "Manage Favorites & Snoozed people for meaningful time", 12, Ui.sub(this)));
        fpRow.addView(fpMid, fpmp);
        fpRow.addView(Ui.tv(this, "›", 18, Ui.sub(this)));
        fpRow.setOnClickListener(v -> startActivity(new Intent(this, FeedPreferencesActivity.class)));
        toolsCard.addView(fpRow);

        body.addView(toolsCard);

        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        int[] mins = Prefs.last7DaysMinutes(this);
        String[] labels = Prefs.last7DayLabels();
        int sum = 0, max = 10;
        for (int m : mins) {
            sum += m;
            if (m > max) max = m;
        }
        int avg = Math.round(sum / 7f);
        avgTv.setText(fmtMin(avg));
        todayTv.setText("Today: " + fmtMin(mins[6]) + " · 7-day total: " + fmtMin(sum));

        int lim = Prefs.dailyLimitMinutes(this);
        limitSubTv.setText(lim <= 0
                ? "Off — get a reminder after a set amount of time"
                : "On — remind me after " + fmtMin(lim) + " per day");

        chartBox.removeAllViews();
        for (int i = 0; i < 7; i++) {
            LinearLayout col = Ui.col(this);
            col.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            TextView val = Ui.tv(this, mins[i] > 0 ? mins[i] + "m" : "", 10, Ui.sub(this));
            val.setGravity(Gravity.CENTER);
            col.addView(val);

            View bar = new View(this);
            int barH = Math.max(Ui.dp(this, 6), Ui.dp(this, 96f * mins[i] / max));
            boolean isToday = i == 6;
            bar.setBackground(Ui.round(this, isToday ? Ui.BLUE : 0xFF8AB4F8, 6));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(Ui.dp(this, 24), barH);
            bp.topMargin = Ui.dp(this, 4);
            bp.bottomMargin = Ui.dp(this, 6);
            col.addView(bar, bp);

            TextView day = Ui.tvBold(this, labels[i], 11, isToday ? Ui.BLUE : Ui.sub(this));
            day.setGravity(Gravity.CENTER);
            col.addView(day);

            chartBox.addView(col, new LinearLayout.LayoutParams(0, -1, 1));
        }
    }

    private String fmtMin(int m) {
        if (m < 60) return m + "m";
        return (m / 60) + "h " + (m % 60) + "m";
    }

    private void pickReminder() {
        String[] opts = {"Off", "15 minutes", "30 minutes", "45 minutes", "1 hour", "2 hours"};
        int[] vals = {0, 15, 30, 45, 60, 120};
        Ui.choose(this, "Set daily time reminder", opts, w -> {
            Prefs.setDailyLimitMinutes(this, vals[w]);
            refresh();
            Ui.toast(this, vals[w] == 0 ? "Daily reminder turned off"
                    : "We'll remind you after " + opts[w] + " each day ⏰");
        });
    }
}
