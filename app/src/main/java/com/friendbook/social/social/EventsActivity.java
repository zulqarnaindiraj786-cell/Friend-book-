package com.friendbook.social.social;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Format;
import com.friendbook.social.core.Ui;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.ListenerRegistration;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 📅 EventsActivity — Full friendbook Events Hub:
 * Tabs (Upcoming · Going/Interested · Hosted by Me), RSVP, Add to Phone Calendar & Share.
 */
public class EventsActivity extends Activity {

    static class Event {
        String id, title, where, details, hostId, hostName;
        long startAt;
        List<String> interested = new ArrayList<>();
    }

    private final List<Event> allEvents = new ArrayList<>();
    private final List<Event> events = new ArrayList<>();
    private String tab = "upcoming";
    private final TextView[] tabs = new TextView[3];
    private Adapter adapter;
    private ListenerRegistration reg;
    private TextView empty;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));

        LinearLayout bar = Ui.pageBar(this, "Events");
        TextView add = Ui.roundChip(this, "＋ Create", Ui.inputBg(this), Ui.txt(this), 13);
        Ui.pad(add, 12, 6, 12, 6);
        add.setOnClickListener(v -> createDialog());
        bar.addView(add);
        root.addView(bar);

        String[] labels = {"Upcoming", "⭐ Going / Interested", "Hosted"};
        String[] keys = {"upcoming", "mine_rsvp", "hosted"};
        LinearLayout trow = Ui.row(this);
        trow.setBackgroundColor(Ui.card(this));
        Ui.pad(trow, 10, 8, 10, 8);
        for (int i = 0; i < 3; i++) {
            final String k = keys[i];
            TextView t = Ui.tvBold(this, labels[i], 12, Ui.sub(this));
            t.setGravity(Gravity.CENTER);
            Ui.pad(t, 8, 6, 8, 6);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) lp.leftMargin = Ui.dp(this, 6);
            trow.addView(t, lp);
            tabs[i] = t;
            t.setOnClickListener(v -> { tab = k; updateTabs(); applyFilter(); });
        }
        root.addView(trow);
        root.addView(Ui.divider(this));
        updateTabs();

        empty = Ui.tv(this, "No events here yet.\nTap ＋ Create to host a meetup, livestream or party!", 14, Ui.sub(this));
        empty.setGravity(Gravity.CENTER);
        Ui.pad(empty, 24, 60, 24, 0);
        root.addView(empty);

        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter();
        rv.setAdapter(adapter);
        root.addView(rv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private void updateTabs() {
        String[] keys = {"upcoming", "mine_rsvp", "hosted"};
        for (int i = 0; i < 3; i++) {
            boolean on = keys[i].equals(tab);
            tabs[i].setBackground(Ui.round(this, on ? Ui.BLUE : Ui.inputBg(this), 16));
            tabs[i].setTextColor(on ? Color.WHITE : Ui.sub(this));
        }
    }

    private void createDialog() {
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 18, 12, 18, 4);
        EditText title = Ui.input(this, "Event name");
        EditText where = Ui.input(this, "Location or Online link");
        EditText details = Ui.input(this, "Details");
        details.setMinLines(2);
        final Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, 1);
        final TextView dateBtn = Ui.btnOutline(this, fmt(cal.getTimeInMillis()), Ui.BLUE);
        dateBtn.setOnClickListener(v -> new DatePickerDialog(this, (dp, y, m, d) -> {
            cal.set(y, m, d, 19, 0);
            dateBtn.setText(fmt(cal.getTimeInMillis()));
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show());

        for (View v : new View[]{title, where, details, dateBtn}) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
            p.bottomMargin = Ui.dp(this, 8);
            box.addView(v, p);
        }
        new AlertDialog.Builder(this)
                .setTitle("Create Event")
                .setView(box)
                .setPositiveButton("Create", (dlg, w) -> {
                    String t = title.getText().toString().trim();
                    if (t.isEmpty()) { Ui.toast(this, "Enter an event name"); return; }
                    Map<String, Object> m = new HashMap<>();
                    m.put("title", t);
                    m.put("where", where.getText().toString().trim());
                    m.put("details", details.getText().toString().trim());
                    m.put("startAt", cal.getTimeInMillis());
                    m.put("hostId", Fb.uid());
                    m.put("hostName", Fb.myName());
                    m.put("interested", Collections.singletonList(Fb.uid()));
                    com.friendbook.social.core.DataRouter.add("events", m, null)
                            .addOnSuccessListener(x -> Ui.toast(this, "Event created 📅"));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String fmt(long ms) {
        return new SimpleDateFormat("EEE, dd MMM yyyy · 7:00 PM", Locale.getDefault()).format(new Date(ms));
    }

    @Override
    protected void onStart() {
        super.onStart();
        com.friendbook.social.core.D1Snap.read(com.friendbook.social.core.DocQuery.of("events").limit(100).build(),snap->{
                    allEvents.clear();
                    long cutoff = System.currentTimeMillis() - 86400_000L;
                    if (snap != null) for (com.friendbook.social.core.Doc d : snap) {
                        Event e = new Event();
                        e.id = d.getId();
                        e.title = d.getString("title");
                        e.where = d.getString("where");
                        e.details = d.getString("details");
                        e.hostId = d.getString("hostId");
                        e.hostName = d.getString("hostName");
                        Object s = d.get("startAt");
                        e.startAt = s instanceof Number ? ((Number) s).longValue() : 0;
                        e.interested = Format.listOf(d.getData(), "interested");
                        if (e.startAt >= cutoff) allEvents.add(e);
                    }
                    Collections.sort(allEvents, (a, b2) -> Long.compare(a.startAt, b2.startAt));
                    applyFilter();
                });
    }

    private void applyFilter() {
        events.clear();
        String me = Fb.uid();
        for (Event e : allEvents) {
            if ("mine_rsvp".equals(tab) && (me == null || !e.interested.contains(me))) continue;
            if ("hosted".equals(tab) && (me == null || !me.equals(e.hostId))) continue;
            events.add(e);
        }
        adapter.notifyDataSetChanged();
        empty.setVisibility(events.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (reg != null) reg.remove();
    }

    class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        @Override public int getItemCount() { return events.size(); }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int vt) {
            LinearLayout card = Ui.col(EventsActivity.this);
            RecyclerView.LayoutParams p = new RecyclerView.LayoutParams(-1, -2);
            p.bottomMargin = Ui.dp(EventsActivity.this, 8);
            card.setLayoutParams(p);
            card.setBackgroundColor(Ui.card(EventsActivity.this));
            Ui.pad(card, 14, 12, 14, 12);

            TextView when = Ui.tvBold(EventsActivity.this, "", 12, Ui.RED);
            TextView title = Ui.tvBold(EventsActivity.this, "", 17, Ui.txt(EventsActivity.this));
            TextView where = Ui.tv(EventsActivity.this, "", 13, Ui.sub(EventsActivity.this));
            TextView det = Ui.tv(EventsActivity.this, "", 13, Ui.txt(EventsActivity.this));
            det.setPadding(0, Ui.dp(EventsActivity.this, 4), 0, Ui.dp(EventsActivity.this, 8));

            LinearLayout bar = Ui.row(EventsActivity.this);
            TextView count = Ui.tv(EventsActivity.this, "", 12, Ui.sub(EventsActivity.this));
            bar.addView(count, new LinearLayout.LayoutParams(0, -2, 1));
            TextView calBtn = Ui.tvBold(EventsActivity.this, "📅 Calendar", 12, Ui.BLUE);
            Ui.pad(calBtn, 10, 7, 10, 7);
            calBtn.setBackground(Ui.round(EventsActivity.this, Ui.inputBg(EventsActivity.this), 8));
            bar.addView(calBtn);

            TextView shareBtn = Ui.tvBold(EventsActivity.this, "↗", 13, Ui.txt(EventsActivity.this));
            Ui.pad(shareBtn, 10, 7, 10, 7);
            shareBtn.setBackground(Ui.round(EventsActivity.this, Ui.inputBg(EventsActivity.this), 8));
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
            sp.leftMargin = Ui.dp(EventsActivity.this, 6);
            bar.addView(shareBtn, sp);

            TextView btn = Ui.tvBold(EventsActivity.this, "", 13, Color.WHITE);
            Ui.pad(btn, 12, 7, 12, 7);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
            bp.leftMargin = Ui.dp(EventsActivity.this, 6);
            bar.addView(btn, bp);

            card.addView(when);
            card.addView(title);
            card.addView(where);
            card.addView(det);
            card.addView(bar);
            return new VH(card, when, title, where, det, count, calBtn, shareBtn, btn);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int i) {
            Event e = events.get(i);
            h.when.setText("📅 " + fmt(e.startAt));
            h.title.setText(e.title);
            h.where.setText((e.where == null || e.where.isEmpty() ? "Online" : "📍 " + e.where)
                    + " · Hosted by " + e.hostName);
            h.det.setText(e.details == null ? "" : e.details);
            h.det.setVisibility(e.details == null || e.details.isEmpty() ? View.GONE : View.VISIBLE);
            h.count.setText(e.interested.size() + " going / interested");

            boolean going = Fb.uid() != null && e.interested.contains(Fb.uid());
            h.btn.setText(going ? "✓ Going" : "⭐ Interested");
            h.btn.setTextColor(going ? Ui.BLUE : Color.WHITE);
            h.btn.setBackground(Ui.round(EventsActivity.this, going ? Ui.inputBg(EventsActivity.this) : Ui.BLUE, 8));
            h.btn.setOnClickListener(v -> {
                String me = Fb.uid();
                if (me == null) return;
                com.friendbook.social.core.DataRouter.mirrorPut("events", e.id, "interested", going ? FieldValue.arrayRemove(me) : FieldValue.arrayUnion(me));
                Fb.db().collection("events").document(e.id).update("interested",
                        going ? FieldValue.arrayRemove(me) : FieldValue.arrayUnion(me));
            });

            h.calBtn.setOnClickListener(v -> {
                try {
                    Intent cal = new Intent(Intent.ACTION_INSERT)
                            .setData(CalendarContract.Events.CONTENT_URI)
                            .putExtra(CalendarContract.Events.TITLE, e.title)
                            .putExtra(CalendarContract.Events.EVENT_LOCATION, e.where)
                            .putExtra(CalendarContract.Events.DESCRIPTION, e.details)
                            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.startAt);
                    startActivity(cal);
                } catch (Exception ex) {
                    Ui.toast(EventsActivity.this, "No Calendar app found");
                }
            });

            h.shareBtn.setOnClickListener(v -> {
                Intent s = new Intent(Intent.ACTION_SEND);
                s.setType("text/plain");
                // ALWAYS share Play Store link
                s.putExtra(Intent.EXTRA_TEXT, "Check out friendbook!\n" + Ui.PLAY_STORE_LINK);
                startActivity(Intent.createChooser(s, "Share friendbook"));
            });
        }

        class VH extends RecyclerView.ViewHolder {
            TextView when, title, where, det, count, calBtn, shareBtn, btn;
            VH(View v, TextView w, TextView t, TextView wh, TextView d, TextView c,
               TextView cal, TextView sh, TextView b) {
                super(v);
                when = w; title = t; where = wh; det = d; count = c;
                calBtn = cal; shareBtn = sh; btn = b;
            }
        }
    }
}
