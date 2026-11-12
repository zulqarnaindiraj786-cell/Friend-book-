package com.example.frienbook.social;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.example.frienbook.core.Fb;
import com.example.frienbook.core.Format;
import com.example.frienbook.core.Icons;
import com.example.frienbook.core.Ui;
import com.example.frienbook.core.cloud.DocumentSnapshot;
import com.example.frienbook.core.cloud.FieldValue;
import com.example.frienbook.core.cloud.ListenerRegistration;
import com.example.frienbook.core.cloud.Query;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 📅 Events — Facebook Lite style: create event (name/date/place/about), upcoming list,
 * "⭐ Interested" RSVP (realtime count). Firestore: events {name, dateMs, location,
 * about, hostId, hostName, interested[], createdAt}.
 */
public class EventsActivity extends Activity {

    private LinearLayout list;
    private TextView countT;
    private ListenerRegistration reg;
    private long pickedDateMs;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        root.addView(Ui.pageBar(this, "📅 Events"));

        ScrollView sv = new ScrollView(this);
        list = Ui.col(this);
        Ui.pad(list, 14, 10, 14, 24);
        list.setBackgroundColor(Ui.bg(this));
        sv.addView(list);

        TextView create = Ui.btn(this, "＋ Create an event");
        create.setOnClickListener(v -> createDialog());
        list.addView(create, new LinearLayout.LayoutParams(-1, Ui.dp(this, 44)));

        countT = Ui.tv(this, "Loading upcoming events…", 12, Ui.sub(this));
        Ui.pad(countT, 2, 12, 2, 6);
        list.addView(countT);

        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        reg = Fb.db().collection("events")
                .orderBy("dateMs", Query.Direction.ASCENDING).limit(100)
                .addSnapshotListener((snap, err) -> {
                    while (list.getChildCount() > 2) list.removeViewAt(2);
                    List<DocumentSnapshot> upcoming = new ArrayList<>();
                    if (snap != null) {
                        long now = System.currentTimeMillis() - 6L * 3600 * 1000; // 6h grace
                        for (DocumentSnapshot d : snap) {
                            long when = Format.ts(d.getData() == null ? null : d.getData().get("dateMs"));
                            if (when >= now) upcoming.add(d);
                        }
                    }
                    countT.setText(upcoming.size() + " upcoming event(s)");
                    if (upcoming.isEmpty()) {
                        TextView e = Ui.tv(this, "Koi upcoming event nahi — pehla event aap banayein! 🎉", 13, Ui.sub(this));
                        Ui.pad(e, 4, 10, 4, 0);
                        list.addView(e);
                    }
                    for (DocumentSnapshot d : upcoming) list.addView(eventRow(d));
                });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (reg != null) reg.remove();
    }

    private View eventRow(DocumentSnapshot d) {
        Map<String, Object> m = d.getData();
        String name = Format.strOr(m, "name", "Event");
        String location = Format.str(m, "location");
        String about = Format.str(m, "about");
        String host = Format.strOr(m, "hostName", "friendbook user");
        long when = Format.ts(m == null ? null : m.get("dateMs"));
        List<String> interested = Format.listOf(m, "interested");
        boolean meIn = interested.contains(Fb.uid());

        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(when);

        LinearLayout card = Ui.row(this);
        card.setGravity(Gravity.TOP);
        card.setBackground(Ui.round(this, Ui.card(this), 14));
        Ui.pad(card, 12, 12, 12, 12);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.setMargins(0, 0, 0, Ui.dp(this, 10));
        card.setLayoutParams(cp);

        // date box (FB style)
        LinearLayout dateBox = Ui.col(this);
        dateBox.setGravity(Gravity.CENTER);
        dateBox.setBackground(Ui.round(this, 0xFF1877F2, 10));
        int ds = Ui.dp(this, 56);
        dateBox.setLayoutParams(new LinearLayout.LayoutParams(ds, ds));
        TextView day = Ui.tvBold(this, String.valueOf(c.get(Calendar.DAY_OF_MONTH)), 20, 0xFFFFFFFF);
        TextView mon = Ui.tvBold(this, new java.text.SimpleDateFormat("MMM", Locale.US).format(c.getTime()).toUpperCase(Locale.US), 11, 0xCCFFFFFF);
        dateBox.addView(day);
        dateBox.addView(mon);
        card.addView(dateBox);

        LinearLayout info = Ui.col(this);
        info.setPadding(Ui.dp(this, 12), 0, 0, 0);
        TextView title = Ui.tvBold(this, name, 16, Ui.txt(this));
        info.addView(title);
        TextView whenT = Ui.tv(this, new java.text.SimpleDateFormat("EEE, d MMM 'at' hh:mm a", Locale.US).format(c.getTime()), 12, Ui.sub(this));
        info.addView(whenT);
        if (!location.isEmpty()) {
            TextView loc = Ui.tv(this, "📍 " + location, 12, Ui.sub(this));
            info.addView(loc);
        }
        if (!about.isEmpty()) {
            TextView ab = Ui.tv(this, about, 12, Ui.txt(this));
            ab.setPadding(0, Ui.dp(this, 4), 0, 0);
            info.addView(ab);
        }
        TextView by = Ui.tv(this, "by " + host + " · ⭐ " + interested.size() + " interested", 11, Ui.sub(this));
        by.setPadding(0, Ui.dp(this, 4), 0, 0);
        info.addView(by);

        TextView rsvp = Ui.btnOutline(this, meIn ? "✓ Interested" : "⭐ Interested", meIn ? Ui.GREEN : Icons.BLUE);
        rsvp.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        rsvp.setOnClickListener(v -> d.getReference().update("interested",
                meIn ? FieldValue.arrayRemove(Fb.uid()) : FieldValue.arrayUnion(Fb.uid())));
        info.addView(rsvp, new LinearLayout.LayoutParams(-2, -2));

        card.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        return card;
    }

    private void createDialog() {
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 20, 10, 20, 0);
        EditText name = Ui.input(this, "Event name (e.g. Cricket match 🏏)");
        EditText loc = Ui.input(this, "Location");
        EditText about = Ui.multi(this, "About this event…", 2);
        box.addView(name, new LinearLayout.LayoutParams(-1, Ui.dp(this, 46)));
        box.addView(loc, new LinearLayout.LayoutParams(-1, Ui.dp(this, 46)));
        box.addView(about, new LinearLayout.LayoutParams(-1, -2));

        TextView dateBtn = Ui.btnGrey(this, "📆 Pick date & time");
        dateBtn.setGravity(Gravity.CENTER);
        box.addView(dateBtn, new LinearLayout.LayoutParams(-1, Ui.dp(this, 44)));
        pickedDateMs = 0;
        dateBtn.setOnClickListener(v -> {
            Calendar c = Calendar.getInstance();
            new DatePickerDialog(this, (dp, y, mth, dy) -> {
                new TimePickerDialog(this, (tp, hh, mm) -> {
                    Calendar x = Calendar.getInstance();
                    x.set(y, mth, dy, hh, mm);
                    pickedDateMs = x.getTimeInMillis();
                    dateBtn.setText("📆 " + new java.text.SimpleDateFormat("d MMM yyyy, hh:mm a", Locale.US).format(x.getTime()));
                }, 12, 0, false).show();
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
        });

        new AlertDialog.Builder(this)
                .setTitle("Create event")
                .setView(box)
                .setPositiveButton("Publish", (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (n.isEmpty()) { Ui.toast(this, "Name daalo"); return; }
                    long when = pickedDateMs == 0 ? System.currentTimeMillis() + 86400000 : pickedDateMs;
                    java.util.Map<String, Object> ev = Fb.map(
                            "name", n,
                            "location", loc.getText().toString().trim(),
                            "about", about.getText().toString().trim(),
                            "dateMs", when,
                            "hostId", Fb.uid(),
                            "hostName", Fb.myName(),
                            "interested", new ArrayList<String>(),
                            "createdAt", FieldValue.serverTimestamp());
                    Fb.db().collection("events").add(ev)
                            .addOnSuccessListener(x -> Ui.toast(this, "🎉 Event published!"))
                            .addOnFailureListener(err -> Ui.toast(this, "Failed: " + err.getMessage()));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
