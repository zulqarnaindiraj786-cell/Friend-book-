package com.example.frienbook.chat;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.PopupMenu;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.frienbook.core.Fb;
import com.example.frienbook.core.Format;
import com.example.frienbook.core.Presence;
import com.example.frienbook.core.Prefs;
import com.example.frienbook.core.Ui;
import com.example.frienbook.models.Models;
import com.example.frienbook.profile.ProfileActivity;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.firestore.WriteBatch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 1:1 chat: realtime messages, typing, presence, seen/delivered, images, deletes. */
public class ChatActivity extends Activity implements MessagesAdapter.Actions {

    private static final int PICK_IMG = 51;

    private String peerId, peerName = "", chatId;
    private TextView presenceTv, nameTv;
    private ImageView barTick;
    private ImageView barAvatar;
    private EditText input;
    private MessagesAdapter adapter;
    private RecyclerView rv;
    private ListenerRegistration msgReg, userReg;
    private Presence.Handle presence, typingH;
    private boolean typingSent;
    private long lastTypingPush;
    private boolean busy;
    private ImageView mic;
    private android.media.MediaRecorder recorder;
    private String voicePath;
    private long voiceStartMs;
    private boolean recording;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);
        peerId = getIntent().getStringExtra("uid");
        peerName = getIntent().getStringExtra("name");
        if (peerId == null || Fb.uid() == null) { finish(); return; }
        if (peerName == null) peerName = "Chat";
        chatId = Fb.chatId(Fb.uid(), peerId);
        String prefill = getIntent().getStringExtra("prefill");

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));

        // custom bar with presence
        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(Ui.BLUE);
        bar.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
        TextView back = Ui.tv(this, "←", 22, Color.WHITE);
        back.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        barAvatar = new ImageView(this);
        int s = Ui.dp(this, 40);
        bar.addView(barAvatar, new LinearLayout.LayoutParams(s, s));
        LinearLayout nameCol = Ui.col(this);
        LinearLayout.LayoutParams ncp = new LinearLayout.LayoutParams(0, -2, 1);
        ncp.leftMargin = Ui.dp(this, 10);
        nameTv = Ui.tvBold(this, peerName, 16, Color.WHITE);
        barTick = new ImageView(this);
        int ts = Ui.dp(this, 17);
        LinearLayout.LayoutParams tickp = new LinearLayout.LayoutParams(ts, ts);
        tickp.leftMargin = Ui.dp(this, 5);
        tickp.gravity = Gravity.CENTER_VERTICAL;
        LinearLayout nameRow = new LinearLayout(this);
        nameRow.addView(nameTv);
        nameRow.addView(barTick, tickp);
        presenceTv = Ui.tv(this, "…", 12, 0xCCFFFFFF);
        nameCol.addView(nameRow);
        nameCol.addView(presenceTv);
        bar.addView(nameCol, ncp);
        TextView menu = Ui.tv(this, "⋮", 22, Color.WHITE);
        menu.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 8), 0);
        bar.addView(menu);
        root.addView(bar);

        View.OnClickListener prof = v -> {
            Intent i = new Intent(this, ProfileActivity.class);
            i.putExtra("uid", peerId);
            startActivity(i);
        };
        barAvatar.setOnClickListener(prof);
        nameTv.setOnClickListener(prof);
        menu.setOnClickListener(v -> {
            PopupMenu pm = new PopupMenu(this, v);
            pm.getMenu().add("👤 View profile");
            pm.getMenu().add("✔ Mark all as read");
            pm.getMenu().add("🎨 Chat theme");
            pm.getMenu().add("🚫 Block user");
            pm.setOnMenuItemClickListener(item -> {
                String t = item.getTitle().toString();
                if (t.contains("profile")) prof.onClick(v);
                else if (t.contains("read")) resetUnread();
                else if (t.contains("theme")) themePicker();
                else blockPeer();
                return true;
            });
            pm.show();
        });

        rv = new RecyclerView(this);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        rv.setLayoutManager(lm);
        adapter = new MessagesAdapter(this, this);
        rv.setAdapter(adapter);
        root.addView(rv, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(buildComposer());
        if (prefill != null && !prefill.isEmpty()) input.setText(prefill);
        setContentView(root);
    }

    private View buildComposer() {
        LinearLayout wrap = Ui.col(this);
        wrap.setBackgroundColor(Ui.card(this));
        wrap.addView(Ui.divider(this));
        LinearLayout row = Ui.row(this);
        Ui.pad(row, 8, 8, 10, 8);

        // Facebook-Lite outline icon buttons (2px stroke)
        ImageView photo = com.example.frienbook.core.Icons.icon(
                this, com.example.frienbook.core.Icons.IMAGE, 23,
                com.example.frienbook.core.Icons.GRAY);
        photo.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));
        photo.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            startActivityForResult(i, PICK_IMG);
        });
        row.addView(photo);

        ImageView sticker = com.example.frienbook.core.Icons.icon(
                this, com.example.frienbook.core.Icons.SMILE, 23,
                com.example.frienbook.core.Icons.GRAY);
        sticker.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6));
        sticker.setOnClickListener(v -> stickerSheet());
        row.addView(sticker);

        mic = com.example.frienbook.core.Icons.icon(
                this, com.example.frienbook.core.Icons.MIC, 23,
                com.example.frienbook.core.Icons.GRAY);
        mic.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6));
        mic.setOnClickListener(v -> toggleVoiceRecording());
        row.addView(mic);

        input = Ui.input(this, "Message…");
        input.setBackground(Ui.round(this, Ui.inputBg(this), 22));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, -2, 1);
        row.addView(input, ip);

        ImageView send = com.example.frienbook.core.Icons.icon(
                this, com.example.frienbook.core.Icons.SEND, 24,
                com.example.frienbook.core.Icons.BLUE);
        send.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 2), Ui.dp(this, 4));
        row.addView(send);
        wrap.addView(row);

        ImageView quick = com.example.frienbook.core.Icons.icon(
                this, com.example.frienbook.core.Icons.LIKE, 24,
                com.example.frienbook.core.Icons.BLUE);
        quick.setPadding(Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
        quick.setOnClickListener(v -> send(null, null, "👍"));
        row.addView(quick, new LinearLayout.LayoutParams(-2, -2));

        send.setOnClickListener(v -> send(input.getText().toString().trim(), null, null));
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                boolean has = s.length() > 0;
                long now = System.currentTimeMillis();
                if (has && (!typingSent || now - lastTypingPush > 2000)) {
                    Presence.setTyping(chatId, true);
                    typingSent = true;
                    lastTypingPush = now;
                } else if (!has && typingSent) {
                    Presence.setTyping(chatId, false);
                    typingSent = false;
                }
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        return wrap;
    }

    @Override
    protected void onStart() {
        super.onStart();

        // peer profile (live)
        userReg = Fb.db().collection("users").document(peerId)
                .addSnapshotListener((d, err) -> {
                    if (d == null || !d.exists()) return;
                    Models.User u = Models.User.fromDoc(d);
                    peerName = u.displayName;
                    nameTv.setText(peerName);
                    com.example.frienbook.core.Icons.setTickBadge(barTick, u.tickLevel, false);
                    Ui.avatar(this, barAvatar, u.photoUrl);
                    adapter.setPeerPhoto(u.photoUrl);
                });

        // presence + typing on Cloudflare (Worker D1 - no Realtime Database)
        presence = Presence.watch(peerId, (online, lastSeen) -> {
            if (typingOn) return;
            if (online) {
                presenceTv.setText("🟢 Active now");
            } else if (lastSeen > 0) {
                presenceTv.setText("Active " + Format.ago(lastSeen) + " ago");
            } else presenceTv.setText("");
        });
        typingH = Presence.watchTyping(chatId, Fb.uid(), t -> {
            typingOn = t;
            if (t) presenceTv.setText("typing…");
            else presenceTv.setText("");
        });

        // messages (realtime via onSnapshot)
        msgReg = Fb.db().collection("chats").document(chatId)
                .collection("messages")
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .limitToLast(200)
                .addSnapshotListener((snap, err) -> {
                    if (snap == null) return;
                    List<Models.Message> list = new ArrayList<>();
                    Set<String> hidden = hiddenMsgs();
                    List<DocumentSnapshot> toMark = new ArrayList<>();
                    String me = Fb.uid();
                    for (DocumentSnapshot d : snap) {
                        Models.Message m = Models.Message.fromDoc(d);
                        if (hidden.contains(m.id)) continue;
                        list.add(m);
                        if (m.receiverId.equals(me) && (!m.isSeen || !m.isDelivered) && !m.isDeleted) {
                            boolean needsSeen = !m.isSeen && Prefs.readReceipts(this);
                            boolean needsDeliv = !m.isDelivered;
                            if (needsSeen || needsDeliv) toMark.add(d);
                        }
                    }
                    adapter.setData(list);
                    if (rv.getAdapter() != null && rv.getAdapter().getItemCount() > 0)
                        rv.scrollToPosition(rv.getAdapter().getItemCount() - 1);
                    if (!toMark.isEmpty()) {
                        WriteBatch batch = Fb.db().batch();
                        for (DocumentSnapshot d : toMark) {
                            Map<String, Object> patch = new HashMap<>();
                            patch.put("isDelivered", true);
                            if (Prefs.readReceipts(this)) patch.put("isSeen", true);
                            batch.set(d.getReference(), patch, SetOptions.merge());
                        }
                        batch.commit();
                    }
                    resetUnread();
                });
    }

    private boolean typingOn = false;

    @Override
    protected void onStop() {
        super.onStop();
        if (msgReg != null) msgReg.remove();
        if (userReg != null) userReg.remove();
        if (presence != null) presence.close();
        if (typingH != null) typingH.close();
        if (typingSent) Presence.setTyping(chatId, false);
        MessagesAdapter.stopVoice();
        if (recording) { stopRecording(false); }
    }

    private Set<String> hiddenMsgs() {
        return Prefs.hiddenMsgs(this, chatId);
    }

    // ---------- voice notes ----------
    private void toggleVoiceRecording() {
        if (recording) { stopRecording(true); return; }
        if (androidx.core.content.ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.RECORD_AUDIO}, 99);
            Ui.toast(this, "Allow microphone, then tap 🎤 again");
            return;
        }
        try {
            voicePath = new java.io.File(getCacheDir(),
                    "voice_" + System.currentTimeMillis() + ".m4a").getAbsolutePath();
            recorder = new android.media.MediaRecorder();
            recorder.setAudioSource(android.media.MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(64000);
            recorder.setAudioSamplingRate(44100);
            recorder.setOutputFile(voicePath);
            recorder.prepare();
            recorder.start();
            voiceStartMs = System.currentTimeMillis();
            recording = true;
            com.example.frienbook.core.Icons.setTint(mic, 0xFFE41E3F); // red while recording
            mic.animate().scaleX(1.25f).scaleY(1.25f).setDuration(150).start();
            Ui.toast(this, "🎤 Recording… tap again to send");
        } catch (Exception e) {
            Ui.toast(this, "Couldn't start recording");
            recording = false;
        }
    }

    private void stopRecording(boolean send) {
        if (!recording) return;
        recording = false;
        com.example.frienbook.core.Icons.setTint(mic, com.example.frienbook.core.Icons.GRAY);
        mic.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
        int durSec = (int) Math.max(1, (System.currentTimeMillis() - voiceStartMs) / 1000);
        try { recorder.stop(); } catch (Throwable ignored) { }
        try { recorder.release(); } catch (Throwable ignored) { }
        recorder = null;
        if (!send || voicePath == null) return;
        Ui.toast(this, "Uploading voice note…");
        Uri uri = Uri.fromFile(new java.io.File(voicePath));
        com.example.frienbook.core.CloudflareMedia.uploadPhoto(this, uri, null,
                url -> sendVoice(url, durSec),
                err -> Ui.toast(this, "Voice upload failed"));
    }

    private void sendVoice(String url, int durSec) {
        String me = Fb.uid();
        Map<String, Object> msg = Fb.map(
                "senderId", me,
                "receiverId", peerId,
                "text", "",
                "type", "voice",
                "voiceUrl", url,
                "voiceDurationSec", durSec,
                "isSeen", false,
                "isDelivered", false,
                "isDeleted", false,
                "createdAt", FieldValue.serverTimestamp());
        Map<String, Object> unread = new HashMap<>();
        unread.put(peerId, FieldValue.increment(1));
        Map<String, Object> chatDoc = Fb.map(
                "participants", java.util.Arrays.asList(me, peerId),
                "lastMessage", "🎤 Voice note · " + Format.duration(durSec),
                "lastMessageTime", FieldValue.serverTimestamp(),
                "unreadCount", unread);
        WriteBatch batch = Fb.db().batch();
        com.google.firebase.firestore.DocumentReference msgRef =
                Fb.db().collection("chats").document(chatId).collection("messages").document();
        batch.set(msgRef, msg);
        batch.set(Fb.db().collection("chats").document(chatId), chatDoc, SetOptions.merge());
        // The Cloudflare copy travels with the Firestore write while old builds are in the wild:
        // messages are flat in D1, keyed chatId~msgId, and the chat gets a small mirror summary.
        Map<String, Object> msgMirror = new HashMap<>(msg);
        msgMirror.put("chatId", chatId);
        com.example.frienbook.core.CloudflareDocs.mirror("messages", chatId + "~" + msgRef.getId(), msgMirror);
        Map<String, Object> chatMirror = new HashMap<>();
        chatMirror.put("participants", java.util.Arrays.asList(me, peerId));
        chatMirror.put("lastMessage", String.valueOf(chatDoc.get("lastMessage")));
        chatMirror.put("lastMessageTime", System.currentTimeMillis());
        com.example.frienbook.core.CloudflareDocs.mirror("chats", chatId, chatMirror);
        batch.commit().addOnFailureListener(e -> Ui.toast(this, "Couldn't send voice note"));
    }

    // ---------- chat theme ----------
    private void themePicker() {
        String[] names = {"Facebook Blue", "Lover Red", "Kiwi Green", "Royal Purple",
                "Sunset Orange", "Bubblegum Pink", "Ocean Teal", "Midnight"};
        int[] colors = {0xFF1877F2, 0xFFE41E3F, 0xFF31A24C, 0xFF9360F7,
                0xFFF5650D, 0xFFFF66C4, 0xFF2ABBA7, 0xFF1B2440};
        Ui.choose(this, "🎨 Chat theme (applies on this device)", names, w -> {
            Prefs.chatTheme(this, colors[w], -1);
            adapter.notifyDataSetChanged();
            Ui.toast(this, "Theme: " + names[w]);
        });
    }

    private void resetUnread() {
        Map<String, Object> inner = new HashMap<>();
        inner.put(Fb.uid(), 0);
        Fb.db().collection("chats").document(chatId)
                .set(Fb.map("unreadCount", inner), SetOptions.merge());
    }

    /** ✨ Sticker pack — big emoji stickers, Facebook-Messenger style. */
    private void stickerSheet() {
        final String[] stickers = {
                "😂", "😍", "🥰", "😘", "🤗", "😎", "🥳", "😇",
                "👍", "👏", "🙌", "💪", "🤝", "✌️", "👌", "🫶",
                "❤️", "💔", "💯", "🔥", "✨", "🎉", "🎂", "🌹",
                "😢", "😡", "🤔", "😴", "🙏", "☕", "⚽", "❓"};
        android.app.AlertDialog d = new android.app.AlertDialog.Builder(this).create();
        android.widget.GridView g = new android.widget.GridView(this);
        g.setNumColumns(4);
        Ui.pad(g, 8, 8, 8, 8);
        g.setAdapter(new android.widget.BaseAdapter() {
            @Override public int getCount() { return stickers.length; }
            @Override public Object getItem(int p) { return stickers[p]; }
            @Override public long getItemId(int p) { return p; }
            @Override public android.view.View getView(int p, android.view.View v, android.view.ViewGroup par) {
                android.widget.TextView t = new android.widget.TextView(ChatActivity.this);
                t.setText(stickers[p]);
                t.setTextSize(34);
                t.setGravity(android.view.Gravity.CENTER);
                t.setPadding(0, Ui.dp(ChatActivity.this, 8), 0, Ui.dp(ChatActivity.this, 8));
                return t;
            }
        });
        android.widget.LinearLayout box = Ui.col(this);
        android.widget.TextView head = Ui.tvBold(this, "✨ Stickers", 16, Ui.txt(this));
        Ui.pad(head, 16, 12, 16, 4);
        box.addView(head);
        box.addView(g);
        d.setView(box);
        g.setOnItemClickListener((par, v, p, id) -> { d.dismiss(); sendSticker(stickers[p]); });
        d.show();
    }

    private void sendSticker(String emoji) {
        if (busy) return;
        busy = true;
        String me = Fb.uid();
        Map<String, Object> msg = Fb.map(
                "senderId", me,
                "receiverId", peerId,
                "text", emoji,
                "imageUrl", null,
                "type", "sticker",
                "isSeen", false,
                "isDelivered", false,
                "isDeleted", false,
                "createdAt", FieldValue.serverTimestamp());
        Map<String, Object> unread = new HashMap<>();
        unread.put(peerId, FieldValue.increment(1));
        Map<String, Object> typingOff = new HashMap<>();
        typingOff.put(me, false);
        Map<String, Object> chatDoc = Fb.map(
                "participants", java.util.Arrays.asList(me, peerId),
                "lastMessage", "✨ " + emoji + " sticker",
                "lastMessageTime", FieldValue.serverTimestamp(),
                "typing", typingOff,
                "unreadCount", unread);
        WriteBatch batch = Fb.db().batch();
        com.google.firebase.firestore.DocumentReference msgRef =
                Fb.db().collection("chats").document(chatId).collection("messages").document();
        batch.set(msgRef, msg);
        batch.set(Fb.db().collection("chats").document(chatId), chatDoc, SetOptions.merge());
        // The Cloudflare copy travels with the Firestore write while old builds are in the wild:
        // messages are flat in D1, keyed chatId~msgId, and the chat gets a small mirror summary.
        Map<String, Object> msgMirror = new HashMap<>(msg);
        msgMirror.put("chatId", chatId);
        com.example.frienbook.core.CloudflareDocs.mirror("messages", chatId + "~" + msgRef.getId(), msgMirror);
        Map<String, Object> chatMirror = new HashMap<>();
        chatMirror.put("participants", java.util.Arrays.asList(me, peerId));
        chatMirror.put("lastMessage", String.valueOf(chatDoc.get("lastMessage")));
        chatMirror.put("lastMessageTime", System.currentTimeMillis());
        com.example.frienbook.core.CloudflareDocs.mirror("chats", chatId, chatMirror);
        batch.commit()
                .addOnSuccessListener(x -> busy = false)
                .addOnFailureListener(e -> { busy = false; Ui.toast(this, "Couldn't send — " + e.getMessage()); });
    }

    private void send(String text, String imageUrl, String rawOverride) {
        final String finalText = rawOverride != null ? rawOverride
                : (text != null && !text.isEmpty()) ? text : null;
        if (busy) return;
        if (finalText == null && imageUrl == null) return;
        busy = true;
        Ui.hideKeyboard(this);
        String me = Fb.uid();
        String type = imageUrl != null ? "image" : "text";

        Map<String, Object> msg = Fb.map(
                "senderId", me,
                "receiverId", peerId,
                "text", finalText != null ? finalText : "",
                "imageUrl", imageUrl,
                "type", type,
                "isSeen", false,
                "isDelivered", false,
                "isDeleted", false,
                "createdAt", FieldValue.serverTimestamp());

        Map<String, Object> unread = new HashMap<>();
        unread.put(peerId, FieldValue.increment(1));
        Map<String, Object> typingOff = new HashMap<>();
        typingOff.put(me, false);
        Map<String, Object> chatDoc = Fb.map(
                "participants", java.util.Arrays.asList(me, peerId),
                "lastMessage", "image".equals(type) ? "📷 Photo" : finalText,
                "lastMessageTime", FieldValue.serverTimestamp(),
                "typing", typingOff,
                "unreadCount", unread);

        WriteBatch batch = Fb.db().batch();
        com.google.firebase.firestore.DocumentReference msgRef =
                Fb.db().collection("chats").document(chatId).collection("messages").document();
        batch.set(msgRef, msg);
        batch.set(Fb.db().collection("chats").document(chatId), chatDoc, SetOptions.merge());
        // The Cloudflare copy travels with the Firestore write while old builds are in the wild:
        // messages are flat in D1, keyed chatId~msgId, and the chat gets a small mirror summary.
        Map<String, Object> msgMirror = new HashMap<>(msg);
        msgMirror.put("chatId", chatId);
        com.example.frienbook.core.CloudflareDocs.mirror("messages", chatId + "~" + msgRef.getId(), msgMirror);
        Map<String, Object> chatMirror = new HashMap<>();
        chatMirror.put("participants", java.util.Arrays.asList(me, peerId));
        chatMirror.put("lastMessage", String.valueOf(chatDoc.get("lastMessage")));
        chatMirror.put("lastMessageTime", System.currentTimeMillis());
        com.example.frienbook.core.CloudflareDocs.mirror("chats", chatId, chatMirror);
        batch.commit()
                .addOnSuccessListener(x -> {
                    busy = false;
                    input.setText("");
                    typingSent = false;
                })
                .addOnFailureListener(e -> {
                    busy = false;
                    Ui.toast(this, "Couldn't send — " + e.getMessage());
                });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == PICK_IMG && res == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            Ui.toast(this, "Uploading photo…");
            com.example.frienbook.core.CloudflareMedia.uploadPhoto(this, uri, null,
                    url -> send(null, url, null),
                    err -> Ui.toast(this, "Upload failed"));
        }
    }

    // ---------- message long-press ----------
    @Override
    public void longPress(Models.Message m) {
        boolean mine = m.senderId.equals(Fb.uid());
        List<String> opts = new ArrayList<>();
        opts.add("😍 React to message");
        opts.add("📋 Copy text");
        if (mine) {
            opts.add("🗑 Delete for everyone");
            opts.add("👤 Delete for me");
        } else {
            opts.add("👤 Delete for me");
            opts.add("🚩 Report");
        }
        String[] arr = opts.toArray(new String[0]);
        Ui.choose(this, "Message", arr, w -> {
            String sel = arr[w];
            if (sel.contains("React")) { reactPicker(m); return; }
            if (sel.contains("Copy")) {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null && m.text != null)
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("msg", m.text));
                Ui.toast(this, "Copied");
            } else if (sel.contains("everyone")) {
                Ui.confirm(this, "Delete for everyone?", "Everyone will see that this message was removed.",
                        "Delete", () -> Fb.db().collection("chats").document(chatId)
                                .collection("messages").document(m.id)
                                .update("isDeleted", true, "text", "", "imageUrl", null));
            } else if (sel.contains("for me")) {
                Set<String> hidden = new HashSet<>(hiddenMsgs());
                hidden.add(m.id);
                getSharedPreferences("fb_prefs", MODE_PRIVATE).edit()
                        .putStringSet("hiddenMsgs_" + chatId, hidden).apply();
                // trigger local refresh
                List<Models.Message> current = new ArrayList<>();
                for (Models.Message x : adapter.all()) if (!hidden.contains(x.id)) current.add(x);
                adapter.setData(current);
            } else if (sel.contains("Report")) {
                Fb.db().collection("reports").add(Fb.map(
                        "messageId", m.id, "chatId", chatId, "reportedBy", Fb.uid(),
                        "createdAt", FieldValue.serverTimestamp()));
                Ui.toast(this, "Reported");
            }
        });
    }

    private void reactPicker(Models.Message m) {
        String[] emoj = {"👍 Like", "❤️ Love", "😂 Haha", "😮 Wow", "😢 Sad", "🙏 Thanks", "🚫 Remove reaction"};
        Ui.choose(this, "React to this message", emoj, w -> {
            String me = Fb.uid();
            if (me == null) return;
            java.util.Map<String, Object> upd = new HashMap<>();
            if (w == 6) {
                upd.put("reactions." + me, FieldValue.delete());
                if (m.reactions != null) m.reactions.remove(me);
            } else {
                String e = emoj[w].split(" ")[0];
                upd.put("reactions." + me, e);
                if (m.reactions == null) m.reactions = new HashMap<>();
                m.reactions.put(me, e);
            }
            Fb.db().collection("chats").document(chatId)
                    .collection("messages").document(m.id)
                    .update(upd)
                    .addOnFailureListener(err -> Ui.toast(this, "Reaction failed"));
        });
    }

    private void blockPeer() {
        Ui.confirm(this, "Block " + peerName + "?", "They won't be able to message or find you.",
                "Block", () -> {
                    Fb.db().collection("users").document(Fb.uid())
                            .set(Fb.map("blockedUsers", FieldValue.arrayUnion(peerId)), SetOptions.merge());
                    Ui.toast(this, "Blocked");
                    finish();
                });
    }
}
