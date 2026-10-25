package com.friendbook.live;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.friendbook.core.Admin;
import com.friendbook.core.Fb;
import com.friendbook.core.Format;
import com.friendbook.core.Icons;
import com.friendbook.core.Ui;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.WriteBatch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 🔴 LIVE — host goes live (Firestore room), viewers join, chat and send ⭐ Stars
 * (1 star = Rs 10). Top gifter badge computed live from `gifts`.
 *
 * Firestore:
 *   lives/{id}: hostId, hostName, hostPhotoUrl, title, viewers, stars, status(live|ended), startedAt, createdMonth
 *   lives/{id}/chat: senderId, senderName, senderPhoto, text, createdAt
 *   gifts: senderId, senderName, hostId, liveId, stars, createdAt, createdMonth
 */
public class LiveActivity extends Activity {

    private String liveId;
    private boolean amHost, joined;

    private TextView titleT, viewersT, starsT, topGifterT, liveChip;
    private ImageView hostAvatar;
    private TextView chatInput;
    private EditText chatEdit;

    private final List<Map<String, Object>> chat = new ArrayList<>();
    private ChatAdapter chatAdapter;

    private ListenerRegistration liveReg, chatReg, giftsReg;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);
        liveId = getIntent().getStringExtra("live_id");
        String title = getIntent().getStringExtra("title");
        if (liveId == null) { startHost(title == null ? "" : title); return; }
        buildUi();
    }

    // ============================================================ HOST START
    private void startHost(String title) {
        String uid = Fb.uid();
        if (uid == null) { finish(); return; }
        DocumentReference ref = Fb.db().collection("lives").document();
        Map<String, Object> m = new HashMap<>();
        m.put("hostId", uid);
        m.put("hostName", Fb.myName());
        m.put("hostPhotoUrl", Fb.myPhoto());
        m.put("title", title.isEmpty() ? Fb.myName() + " is live!" : title);
        m.put("viewers", 0);
        m.put("stars", 0);
        m.put("status", "live");
        m.put("startedAt", FieldValue.serverTimestamp());
        m.put("createdMonth", Admin.monthKey());
        ref.set(m).addOnSuccessListener(x -> {
            liveId = ref.getId();
            amHost = true;
            buildUi();
        }).addOnFailureListener(e -> { Ui.toast(this, "Failed: " + e.getMessage()); finish(); });
    }

    // ============================================================ UI
    private void buildUi() {
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(0xFF101114);

        // header
        LinearLayout head = Ui.row(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        hostAvatar = new ImageView(this);
        int av = Ui.dp(this, 38);
        head.addView(hostAvatar, new LinearLayout.LayoutParams(av, av));
        LinearLayout mid = Ui.col(this);
        mid.setPadding(Ui.dp(this, 10), 0, 0, 0);
        titleT = Ui.tvBold(this, "…", 15, 0xFFFFFFFF);
        mid.addView(titleT);
        LinearLayout chips = Ui.row(this);
        liveChip = Ui.roundChip(this, "🔴 LIVE", 0xFFE41E3F, 0xFFFFFFFF, 11);
        viewersT = Ui.roundChip(this, "👁 0", 0x33000000, 0xFFFFFFFF, 11);
        starsT = Ui.roundChip(this, "⭐ 0", 0x33F7B928, 0xFFF7B928, 11);
        chips.addView(liveChip);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-2, -2);
        vp.setMargins(Ui.dp(this, 6), 0, 0, 0);
        chips.addView(viewersT, vp);
        LinearLayout.LayoutParams sp2 = new LinearLayout.LayoutParams(-2, -2);
        sp2.setMargins(Ui.dp(this, 6), 0, 0, 0);
        chips.addView(starsT, sp2);
        mid.addView(chips);
        head.addView(mid, new LinearLayout.LayoutParams(0, -2, 1));
        TextView close = Ui.tvBold(this, amHost ? "End" : "✕", 16, 0xFFFFFFFF);
        close.setOnClickListener(v -> { if (amHost) endLive(); else finish(); });
        head.addView(close);
        root.addView(head);

        // stage (placeholder — real video needs a streaming server; room/chat/gifts are fully live)
        LinearLayout stage = Ui.col(this);
        stage.setGravity(Gravity.CENTER);
        stage.setBackground(Ui.gradient(0xFF1A1C22, 0xFF0B0D10, 0));
        TextView stageIcon = Ui.tv(this, "🔴", 44, 0xFFFFFFFF);
        Ui.pulse(stageIcon);
        stage.addView(stageIcon);
        TextView stageT = Ui.tv(this, amHost ? "You are LIVE — viewers are joining" : "Watching live",
                14, 0xFFB0B3B8);
        Ui.pad(stageT, 0, 6, 0, 0);
        stage.addView(stageT);
        topGifterT = Ui.tvBold(this, "", 13, 0xFFF7B928);
        Ui.pad(topGifterT, 0, 8, 0, 0);
        stage.addView(topGifterT);
        root.addView(stage, new LinearLayout.LayoutParams(-1, Ui.dp(this, 200)));

        // chat
        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        chatAdapter = new ChatAdapter();
        rv.setAdapter(chatAdapter);
        root.addView(rv, new LinearLayout.LayoutParams(-1, 0, 1));

        // gift bar (viewers only)
        if (!amHost) {
            LinearLayout gifts = Ui.row(this);
            gifts.setGravity(Gravity.CENTER);
            gifts.setPadding(Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 2));
            int[] amounts = {1, 5, 10, 50, 100};
            for (int g : amounts) giftButton(gifts, g);
            root.addView(gifts);
            TextView hint = Ui.tv(this, "⭐ 1 Star = Rs " + Admin.STAR_VALUE_PKR + " — support the host",
                    11, 0xFFB0B3B8);
            hint.setGravity(Gravity.CENTER);
            hint.setPadding(0, 0, 0, Ui.dp(this, 4));
            root.addView(hint);
        }

        // chat input
        LinearLayout composer = Ui.row(this);
        composer.setGravity(Gravity.CENTER_VERTICAL);
        composer.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 10));
        chatEdit = new EditText(this);
        chatEdit.setHint("Say something…");
        chatEdit.setTextColor(0xFFE4E6EB);
        chatEdit.setHintTextColor(0xFFB0B3B8);
        chatEdit.setBackground(Ui.roundStroke(this, 0xFF3A3B3C, 20, 0xFF3A3B3C, 1));
        chatEdit.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
        composer.addView(chatEdit, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView send = Icons.icon(this, Icons.SEND, 26, Icons.BLUE);
        send.setPadding(Ui.dp(this, 10), 0, 0, 0);
        send.setOnClickListener(v -> sendChat());
        composer.addView(send);
        root.addView(composer);

        setContentView(root);
        if (!amHost) joinAsViewer();
        listen();
    }

    private void giftButton(LinearLayout box, int stars) {
        TextView t = Ui.roundChip(this, "⭐ " + stars, 0xFF2D2F34, 0xFFF7B928, 14);
        t.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        t.setOnClickListener(v -> sendGift(stars));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        box.addView(t, p);
    }

    // ============================================================ VIEWER
    private void joinAsViewer() {
        joined = true;
        Fb.db().collection("lives").document(liveId)
                .update("viewers", FieldValue.increment(1));
        Map<String, Object> m = new HashMap<>();
        m.put("senderId", Fb.uid());
        m.put("senderName", Fb.myName());
        m.put("senderPhoto", Fb.myPhoto());
        m.put("text", "👋 joined the live");
        m.put("createdAt", FieldValue.serverTimestamp());
        Fb.db().collection("lives").document(liveId).collection("chat").add(m);
    }

    private void leaveViewer() {
        if (!joined || amHost) return;
        joined = false;
        Fb.db().collection("lives").document(liveId)
                .update("viewers", FieldValue.increment(-1));
    }

    // ============================================================ DATA
    private void listen() {
        FirebaseFirestore db = Fb.db();
        liveReg = db.collection("lives").document(liveId)
                .addSnapshotListener((d, err) -> {
                    if (d == null || !d.exists()) { finish(); return; }
                    Map<String, Object> m = d.getData();
                    titleT.setText(Format.strOr(m, "title", "Live"));
                    Ui.avatar(this, hostAvatar, Format.str(m, "hostPhotoUrl"));
                    viewersT.setText("👁 " + Format.count(Format.intOf(m, "viewers")));
                    starsT.setText("⭐ " + Format.count(Format.intOf(m, "stars")));
                    if ("ended".equals(Format.str(m, "status"))) {
                        liveChip.setText("ENDED");
                        Ui.toast(this, "Live has ended");
                        if (!isFinishing()) { topGifterT.postDelayed(this::finish, 1000); }
                    }
                });

        chatReg = db.collection("lives").document(liveId).collection("chat")
                .orderBy("createdAt", Query.Direction.DESCENDING).limit(60)
                .addSnapshotListener((snap, err) -> {
                    chat.clear();
                    if (snap != null) for (DocumentSnapshot d : snap.getDocuments()) chat.add(d.getData());
                    chatAdapter.notifyDataSetChanged();
                });

        giftsReg = db.collection("gifts").whereEqualTo("liveId", liveId)
                .limit(200)
                .addSnapshotListener((snap, err) -> {
                    if (snap == null) return;
                    Map<String, Long> totals = new HashMap<>();
                    Map<String, String> names = new HashMap<>();
                    for (DocumentSnapshot d : snap) {
                        Map<String, Object> m = d.getData();
                        String s = Format.str(m, "senderId");
                        totals.put(s, (totals.containsKey(s) ? totals.get(s) : 0L) + Format.intOf(m, "stars"));
                        names.put(s, Format.strOr(m, "senderName", "Someone"));
                    }
                    String topId = null; long topVal = 0;
                    for (Map.Entry<String, Long> e : totals.entrySet())
                        if (e.getValue() > topVal) { topVal = e.getValue(); topId = e.getKey(); }
                    topGifterT.setText(topId == null ? "" :
                            "👑 Top gifter: " + names.get(topId) + " (⭐ " + topVal + ")");
                });
    }

    private void sendChat() {
        String text = chatEdit.getText().toString().trim();
        if (text.isEmpty()) return;
        chatEdit.setText("");
        Map<String, Object> m = new HashMap<>();
        m.put("senderId", Fb.uid());
        m.put("senderName", Fb.myName());
        m.put("senderPhoto", Fb.myPhoto());
        m.put("text", text);
        m.put("createdAt", FieldValue.serverTimestamp());
        Fb.db().collection("lives").document(liveId).collection("chat").add(m);
    }

    private void sendGift(int stars) {
        String hostId = getIntent().getStringExtra("host_id");
        FirebaseFirestore db = Fb.db();
        WriteBatch batch = db.batch();
        Map<String, Object> gift = new HashMap<>();
        gift.put("senderId", Fb.uid());
        gift.put("senderName", Fb.myName());
        gift.put("hostId", hostId);
        gift.put("liveId", liveId);
        gift.put("stars", stars);
        gift.put("createdAt", FieldValue.serverTimestamp());
        gift.put("createdMonth", Admin.monthKey());
        batch.set(db.collection("gifts").document(), gift);
        batch.update(db.collection("lives").document(liveId), "stars", FieldValue.increment(stars));
        if (hostId != null)
            batch.update(db.collection("users").document(hostId), "stars", FieldValue.increment(stars));
        // also echo into chat
        Map<String, Object> m = new HashMap<>();
        m.put("senderId", Fb.uid());
        m.put("senderName", Fb.myName());
        m.put("senderPhoto", Fb.myPhoto());
        m.put("text", "⭐ sent " + stars + " stars!");
        m.put("createdAt", FieldValue.serverTimestamp());
        batch.set(db.collection("lives").document(liveId).collection("chat").document(), m);
        batch.commit().addOnSuccessListener(x -> Ui.toast(this, "⭐ " + stars + " stars sent!"))
                .addOnFailureListener(e -> Ui.toast(this, "Gift failed: " + e.getMessage()));
    }

    private void endLive() {
        new AlertDialog.Builder(this)
                .setTitle("End live?")
                .setMessage("Viewers will be notified that the stream ended.")
                .setPositiveButton("End live", (d, w) -> Fb.db().collection("lives").document(liveId)
                        .update("status", "ended", "endedAt", FieldValue.serverTimestamp())
                        .addOnSuccessListener(x -> finish()))
                .setNegativeButton("Keep going", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        leaveViewer();
        super.onDestroy();
        if (liveReg != null) liveReg.remove();
        if (chatReg != null) chatReg.remove();
        if (giftsReg != null) giftsReg.remove();
    }

    // ---- chat adapter (dark bubbles) ----
    private class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.VH> {
        class VH extends RecyclerView.ViewHolder {
            ImageView av; TextView name, text;
            VH(View v) {
                super(v);
                LinearLayout row = (LinearLayout) v;
                av = new ImageView(LiveActivity.this);
                int s = Ui.dp(LiveActivity.this, 28);
                row.addView(av, new LinearLayout.LayoutParams(s, s));
                LinearLayout bub = Ui.col(LiveActivity.this);
                bub.setBackground(Ui.round(LiveActivity.this, 0x66262830, 14));
                bub.setPadding(Ui.dp(LiveActivity.this, 10), Ui.dp(LiveActivity.this, 5),
                        Ui.dp(LiveActivity.this, 10), Ui.dp(LiveActivity.this, 5));
                name = Ui.tvBold(LiveActivity.this, "", 11, 0xFF6EB5FF);
                bub.addView(name);
                text = Ui.tv(LiveActivity.this, "", 13, 0xFFE4E6EB);
                bub.addView(text);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.setMargins(Ui.dp(LiveActivity.this, 8), 0, 0, 0);
                row.addView(bub, lp);
            }
        }

        @androidx.annotation.NonNull
        @Override
        public VH onCreateViewHolder(@androidx.annotation.NonNull android.view.ViewGroup p, int t) {
            LinearLayout row = Ui.row(LiveActivity.this);
            row.setPadding(Ui.dp(LiveActivity.this, 10), Ui.dp(LiveActivity.this, 4),
                    Ui.dp(LiveActivity.this, 10), Ui.dp(LiveActivity.this, 4));
            return new VH(row);
        }

        @Override
        public void onBindViewHolder(@androidx.annotation.NonNull VH h, int i) {
            Map<String, Object> m = chat.get(i);
            h.name.setText(Format.strOr(m, "senderName", "User"));
            h.text.setText(com.friendbook.core.Format.strOr(m, "text", ""));
            Ui.avatar(LiveActivity.this, h.av, Format.str(m, "senderPhoto"));
        }

        @Override public int getItemCount() { return chat.size(); }
    }
}
