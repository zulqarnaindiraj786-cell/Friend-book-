package com.friendbook.social.chat;

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

import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Format;
import com.friendbook.social.core.Presence;
import com.friendbook.social.core.Prefs;
import com.friendbook.social.core.Ui;
import com.friendbook.social.models.Models;
import com.friendbook.social.profile.ProfileActivity;
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

    private String peerId, peerName = "", peerPhoto = "", chatId;
    private boolean isGroupChat = false;
    private boolean isTeamChat = false;
    private Models.ChatRoom groupRoom;
    private TextView presenceTv, nameTv;
    private View composerRoot;
    private TextView readOnlyBanner;
    private ImageView barTick;
    private ImageView barAvatar;
    private EditText input;
    private MessagesAdapter adapter;
    private RecyclerView rv;
    private ListenerRegistration msgReg, userReg, roomReg;
    private Presence.Handle presence, typingH;
    private boolean typingSent;
    private long lastTypingPush;
    private boolean busy;
    private boolean initialScrollDone = false;
    private boolean forceScrollBottom = false;
    private boolean unreadResetOnce = false;
    private ImageView mic;
    private android.media.MediaRecorder recorder;
    private String voicePath;
    private long voiceStartMs;
    private boolean recording;
    private Models.Message replyingMsg;
    private LinearLayout replyBar;
    private TextView replyLabel;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);
        peerId = getIntent().getStringExtra("uid");
        peerName = getIntent().getStringExtra("name");
        String passedChatId = getIntent().getStringExtra("chat_id");
        isGroupChat = getIntent().getBooleanExtra("is_group", false);
        if (Fb.uid() == null) { finish(); return; }
        if (isGroupChat) {
            if (passedChatId == null || passedChatId.isEmpty()) { finish(); return; }
            chatId = passedChatId;
            if (peerName == null) peerName = "Group Chat";
        } else {
            if (peerId == null) { finish(); return; }
            isTeamChat = Ui.TEAM_USER_ID.equals(peerId);
            if (isTeamChat) {
                peerName = "friendbook Team";
                peerPhoto = Ui.TEAM_AVATAR_TOKEN;
                chatId = "team_" + Fb.uid();
            } else {
                if (peerName == null) peerName = "Chat";
                chatId = Fb.chatId(Fb.uid(), peerId);
            }
        }
        String prefill = getIntent().getStringExtra("prefill");

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));

        // custom crisp white bar with presence & real vector back arrow
        LinearLayout bar = Ui.row(this);
        bar.setBackground(Ui.barBg(this));
        bar.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
        ImageView back = Ui.backArrow(this, Ui.txt(this), v -> finish());
        bar.addView(back);
        barAvatar = new ImageView(this);
        int s = Ui.dp(this, 40);
        LinearLayout.LayoutParams avp = new LinearLayout.LayoutParams(s, s);
        avp.leftMargin = Ui.dp(this, 2);
        bar.addView(barAvatar, avp);
        LinearLayout nameCol = Ui.col(this);
        LinearLayout.LayoutParams ncp = new LinearLayout.LayoutParams(0, -2, 1);
        ncp.leftMargin = Ui.dp(this, 10);
        nameTv = Ui.tvBold(this, peerName, 16, Ui.txt(this));
        barTick = new ImageView(this);
        int ts = Ui.dp(this, 17);
        LinearLayout.LayoutParams tickp = new LinearLayout.LayoutParams(ts, ts);
        tickp.leftMargin = Ui.dp(this, 5);
        tickp.gravity = Gravity.CENTER_VERTICAL;
        LinearLayout nameRow = new LinearLayout(this);
        nameRow.addView(nameTv);
        nameRow.addView(barTick, tickp);
        presenceTv = Ui.tv(this, "…", 12, Ui.sub(this));
        nameCol.addView(nameRow);
        nameCol.addView(presenceTv);
        bar.addView(nameCol, ncp);
        TextView menu = Ui.tv(this, "⋮", 22, Ui.txt(this));
        menu.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 8), 0);
        bar.addView(menu);
        root.addView(bar);

        View.OnClickListener prof = v -> {
            if (isGroupChat) {
                startActivity(new Intent(this, GroupChatInfoActivity.class).putExtra("chat_id", chatId));
            } else if (!isTeamChat) {
                Intent i = new Intent(this, ProfileActivity.class);
                i.putExtra("uid", peerId);
                startActivity(i);
            }
        };
        barAvatar.setOnClickListener(prof);
        nameTv.setOnClickListener(prof);
        menu.setOnClickListener(v -> {
            PopupMenu pm = new PopupMenu(this, v);
            if (isGroupChat) {
                pm.getMenu().add("👥 Group Info & Admin Controls");
            } else if (!isTeamChat) {
                pm.getMenu().add("👤 View profile");
                pm.getMenu().add("✏️ Set nickname");
            }
            pm.getMenu().add("🎨 Chat theme");
            boolean muted = Prefs.isChatMuted(this, chatId);
            pm.getMenu().add(muted ? "🔔 Unmute notifications" : "🔕 Mute notifications");
            boolean archived = Prefs.isChatArchived(this, chatId);
            pm.getMenu().add(archived ? "📥 Unarchive chat" : "📦 Archive chat");
            pm.getMenu().add("✔ Mark all as read");
            if (!isGroupChat && !isTeamChat) pm.getMenu().add("🚫 Block user");
            pm.setOnMenuItemClickListener(item -> {
                String t = item.getTitle().toString();
                if (t.contains("Group Info")) {
                    startActivity(new Intent(this, GroupChatInfoActivity.class).putExtra("chat_id", chatId));
                } else if (t.contains("profile")) prof.onClick(v);
                else if (t.contains("nickname")) nicknameDialog();
                else if (t.contains("theme")) themePicker();
                else if (t.contains("Mute") || t.contains("Unmute")) {
                    Prefs.muteChat(this, chatId, !muted);
                    Ui.toast(this, !muted ? "Chat muted 🔕" : "Chat unmuted 🔔");
                } else if (t.contains("Archive") || t.contains("Unarchive")) {
                    Prefs.archiveChat(this, chatId, !archived);
                    Ui.toast(this, !archived ? "Chat archived 📦" : "Moved to Inbox");
                } else if (t.contains("read")) resetUnread();
                else if (t.contains("Block")) blockPeer();
                return true;
            });
            pm.show();
        });

        rv = new RecyclerView(this);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        rv.setLayoutManager(lm);
        rv.setItemAnimator(null);
        adapter = new MessagesAdapter(this, this);
        rv.setAdapter(adapter);
        rv.addOnLayoutChangeListener((v, l, t, r, bo, ol, ot, or2, obo) -> {
            if (bo < obo && adapter.getItemCount() > 0) {
                rv.post(() -> rv.scrollToPosition(adapter.getItemCount() - 1));
            }
        });
        root.addView(rv, new LinearLayout.LayoutParams(-1, 0, 1));

        readOnlyBanner = Ui.tvBold(this, "🛡 Official updates from friendbook Team", 13, Ui.sub(this));
        readOnlyBanner.setGravity(Gravity.CENTER);
        readOnlyBanner.setBackgroundColor(Ui.card(this));
        Ui.pad(readOnlyBanner, 14, 12, 14, 12);
        readOnlyBanner.setVisibility(View.GONE);
        root.addView(readOnlyBanner);

        composerRoot = buildComposer();
        root.addView(composerRoot);
        if (isTeamChat && !com.friendbook.social.core.Admin.isAdmin()) {
            composerRoot.setVisibility(View.GONE);
            readOnlyBanner.setVisibility(View.VISIBLE);
        }
        if (prefill != null && !prefill.isEmpty()) input.setText(prefill);
        setContentView(root);
    }

    private View buildComposer() {
        LinearLayout wrap = Ui.col(this);
        wrap.setBackgroundColor(Ui.card(this));
        wrap.addView(Ui.divider(this));

        replyBar = Ui.row(this);
        replyBar.setBackgroundColor(Ui.inputBg(this));
        Ui.pad(replyBar, 12, 6, 12, 6);
        replyBar.setVisibility(View.GONE);
        replyLabel = Ui.tv(this, "", 12, Ui.txt(this));
        replyLabel.setSingleLine(true);
        replyBar.addView(replyLabel, new LinearLayout.LayoutParams(0, -2, 1));
        TextView closeReply = Ui.tvBold(this, "✕", 14, Ui.sub(this));
        closeReply.setPadding(Ui.dp(this, 8), 0, 0, 0);
        closeReply.setOnClickListener(v -> {
            replyingMsg = null;
            replyBar.setVisibility(View.GONE);
        });
        replyBar.addView(closeReply);
        wrap.addView(replyBar);

        // ⚡ friendbook 1-Tap Quick Emoji Bar
        LinearLayout quickEmojiRow = Ui.row(this);
        Ui.pad(quickEmojiRow, 8, 4, 8, 2);
        String[] quickEmojis = {"❤️", "😂", "👍", "🔥", "😍", "😢", "👏", "🙏"};
        for (final String em : quickEmojis) {
            TextView et = Ui.tv(this, em, 18, Ui.txt(this));
            et.setGravity(Gravity.CENTER);
            et.setOnClickListener(v -> {
                Ui.haptic(v);
                int pos = Math.max(0, input.getSelectionStart());
                input.getText().insert(pos, em);
            });
            quickEmojiRow.addView(et, new LinearLayout.LayoutParams(0, -2, 1));
        }
        wrap.addView(quickEmojiRow);

        LinearLayout row = Ui.row(this);
        Ui.pad(row, 8, 8, 10, 8);

        // friendbook outline icon buttons (2px stroke)
        ImageView photo = com.friendbook.social.core.Icons.icon(
                this, com.friendbook.social.core.Icons.IMAGE, 23,
                com.friendbook.social.core.Icons.GRAY);
        photo.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));
        photo.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            startActivityForResult(i, PICK_IMG);
        });
        row.addView(photo);

        ImageView sticker = com.friendbook.social.core.Icons.icon(
                this, com.friendbook.social.core.Icons.SMILE, 23,
                com.friendbook.social.core.Icons.GRAY);
        sticker.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6));
        sticker.setOnClickListener(v -> stickerSheet());
        row.addView(sticker);

        mic = com.friendbook.social.core.Icons.icon(
                this, com.friendbook.social.core.Icons.MIC, 23,
                com.friendbook.social.core.Icons.GRAY);
        mic.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6));
        mic.setOnClickListener(v -> toggleVoiceRecording());
        row.addView(mic);

        input = Ui.input(this, "Message…");
        input.setBackground(Ui.round(this, Ui.inputBg(this), 22));
        input.setMinHeight(Ui.dp(this, 40));
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                String txt = input.getText().toString().trim();
                if (!txt.isEmpty()) send(txt, null, null);
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, -2, 1);
        ip.leftMargin = Ui.dp(this, 4);
        ip.rightMargin = Ui.dp(this, 4);
        row.addView(input, ip);

        final ImageView send = com.friendbook.social.core.Icons.icon(
                this, com.friendbook.social.core.Icons.SEND, 25,
                com.friendbook.social.core.Icons.BLUE);
        send.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));
        send.setVisibility(View.GONE);
        row.addView(send);

        final ImageView quick = com.friendbook.social.core.Icons.icon(
                this, com.friendbook.social.core.Icons.LIKE, 25,
                com.friendbook.social.core.Icons.BLUE);
        quick.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));
        quick.setOnClickListener(v -> send(null, null, "👍"));
        row.addView(quick, new LinearLayout.LayoutParams(-2, -2));

        wrap.addView(row);

        send.setOnClickListener(v -> send(input.getText().toString().trim(), null, null));
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                boolean has = s.toString().trim().length() > 0;
                send.setVisibility(has ? View.VISIBLE : View.GONE);
                quick.setVisibility(has ? View.GONE : View.VISIBLE);
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

        if (isGroupChat) {
            roomReg = Fb.db().collection("chats").document(chatId)
                    .addSnapshotListener((d, err) -> {
                        if (d == null || !d.exists()) { finish(); return; }
                        groupRoom = Models.ChatRoom.fromDoc(d);
                        peerName = (groupRoom.groupName == null || groupRoom.groupName.isEmpty())
                                ? "Group Chat" : groupRoom.groupName;
                        peerPhoto = groupRoom.groupPhoto == null ? "" : groupRoom.groupPhoto;
                        nameTv.setText(peerName);
                        barTick.setVisibility(View.GONE);
                        presenceTv.setText("👥 " + groupRoom.participants.size() + " members · Tap for Group Info");
                        if (!peerPhoto.isEmpty()) {
                            barAvatar.setPadding(0, 0, 0, 0);
                            Ui.avatar(this, barAvatar, peerPhoto);
                        } else {
                            barAvatar.setBackground(Ui.circle(Ui.BLUE));
                            int p = Ui.dp(this, 8);
                            barAvatar.setPadding(p, p, p, p);
                            barAvatar.setImageDrawable(com.friendbook.social.core.Icons.tinted(
                                    this, com.friendbook.social.core.Icons.GROUPS, Color.WHITE));
                        }
                        adapter.setGroupMeta(true, groupRoom.adminIds,
                                groupRoom.participantNames, groupRoom.participantPhotos);

                        String me = Fb.uid();
                        boolean inGroup = me != null && groupRoom.participants.contains(me);
                        boolean canSend = inGroup && (!groupRoom.onlyAdminsCanSend || groupRoom.isGroupAdmin(me));
                        if (!inGroup) {
                            composerRoot.setVisibility(View.GONE);
                            readOnlyBanner.setText("🚫 You are no longer a member of this group");
                            readOnlyBanner.setVisibility(View.VISIBLE);
                        } else if (!canSend) {
                            composerRoot.setVisibility(View.GONE);
                            readOnlyBanner.setText("🔒 Only group admins can send messages");
                            readOnlyBanner.setVisibility(View.VISIBLE);
                        } else {
                            composerRoot.setVisibility(View.VISIBLE);
                            readOnlyBanner.setVisibility(View.GONE);
                        }
                    });
        } else if (isTeamChat) {
            nameTv.setText("friendbook Team");
            com.friendbook.social.core.Icons.setTickBadge(barTick, "gold", false);
            Ui.avatar(this, barAvatar, Ui.TEAM_AVATAR_TOKEN);
            adapter.setPeerPhoto(Ui.TEAM_AVATAR_TOKEN);
            presenceTv.setText("Official friendbook Team");
        } else {
            // peer profile (live)
            userReg = Fb.db().collection("users").document(peerId)
                    .addSnapshotListener((d, err) -> {
                        if (d == null || !d.exists()) return;
                        Models.User u = Models.User.fromDoc(d);
                        com.friendbook.social.core.FastCache.putUser(u);
                        if (u.displayName != null && !u.displayName.isEmpty()) peerName = u.displayName;
                        peerPhoto = u.photoUrl == null ? "" : u.photoUrl;
                        String nick = Prefs.chatNickname(this, chatId);
                        nameTv.setText(!nick.isEmpty() ? nick : peerName);
                        com.friendbook.social.core.Icons.setTickBadge(barTick, u.tickLevel, false);
                        Ui.avatar(this, barAvatar, peerPhoto);
                        adapter.setPeerPhoto(peerPhoto);
                    });

            // presence (RTDB + Firestore fallback)
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
        }

        // messages (realtime via onSnapshot)
        msgReg = Fb.db().collection("chats").document(chatId)
                .collection("messages")
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .limitToLast(200)
                .addSnapshotListener((snap, err) -> {
                    if (snap == null) return;
                    LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                    int prevCount = adapter.getItemCount();
                    int lastVis = lm != null ? lm.findLastVisibleItemPosition() : prevCount - 1;
                    boolean nearBottom = !initialScrollDone || forceScrollBottom || (prevCount - lastVis <= 4);

                    List<Models.Message> list = new ArrayList<>();
                    Set<String> hidden = hiddenMsgs();
                    List<DocumentSnapshot> toMark = new ArrayList<>();
                    String me = Fb.uid();
                    for (DocumentSnapshot d : snap) {
                        Models.Message m = Models.Message.fromDoc(d);
                        if (hidden.contains(m.id)) continue;
                        list.add(m);
                        if (me != null && me.equals(m.receiverId) && (!m.isSeen || !m.isDelivered) && !m.isDeleted) {
                            boolean needsSeen = !m.isSeen && Prefs.readReceipts(this);
                            boolean needsDeliv = !m.isDelivered;
                            if (needsSeen || needsDeliv) toMark.add(d);
                        }
                    }
                    list.sort((a2, b2) -> Long.compare(a2.createdAt, b2.createdAt));
                    adapter.setData(list);
                    if (nearBottom && !list.isEmpty()) {
                        initialScrollDone = true;
                        forceScrollBottom = false;
                        rv.scrollToPosition(list.size() - 1);
                    }
                    if (!toMark.isEmpty()) {
                        WriteBatch batch = Fb.db().batch();
                        for (DocumentSnapshot d : toMark) {
                            Map<String, Object> patch = new HashMap<>();
                            patch.put("isDelivered", true);
                            if (Prefs.readReceipts(this)) patch.put("isSeen", true);
                            batch.set(d.getReference(), patch, SetOptions.merge());
                        }
                        batch.commit();
                        resetUnread();
                    } else if (!unreadResetOnce && !list.isEmpty()) {
                        unreadResetOnce = true;
                        resetUnread();
                    }
                });
    }

    private boolean typingOn = false;

    @Override
    protected void onStop() {
        super.onStop();
        if (msgReg != null) msgReg.remove();
        if (userReg != null) userReg.remove();
        if (roomReg != null) roomReg.remove();
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
            recorder.setAudioEncodingBitRate(24000); // 2G ultra-light wideband voice (~3 KB/sec!)
            recorder.setAudioSamplingRate(16000);
            recorder.setOutputFile(voicePath);
            recorder.prepare();
            recorder.start();
            voiceStartMs = System.currentTimeMillis();
            recording = true;
            com.friendbook.social.core.Icons.setTint(mic, 0xFFE41E3F); // red while recording
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
        com.friendbook.social.core.Icons.setTint(mic, com.friendbook.social.core.Icons.GRAY);
        mic.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
        int durSec = (int) Math.max(1, (System.currentTimeMillis() - voiceStartMs) / 1000);
        try { recorder.stop(); } catch (Throwable ignored) { }
        try { recorder.release(); } catch (Throwable ignored) { }
        recorder = null;
        if (!send || voicePath == null) return;
        Ui.toast(this, "Uploading voice note…");
        Uri uri = Uri.fromFile(new java.io.File(voicePath));
        String path = "chat/" + chatId + "/voice_" + System.currentTimeMillis() + ".m4a";
        com.friendbook.social.core.Cloudinary.uploadOrFirebase(this, path, uri)
                .addOnSuccessListener(url -> sendVoice(url.toString(), durSec))
                .addOnFailureListener(e -> Ui.toast(this, "Voice upload failed"));
    }

    private Map<String, Object> buildChatRoomDoc(String me, String lastMsg) {
        if (isGroupChat && groupRoom != null) {
            Map<String, Object> unread = new HashMap<>();
            unread.put(me, 0);
            for (String p : groupRoom.participants) {
                if (!p.equals(me)) unread.put(p, FieldValue.increment(1));
            }
            Map<String, String> names = new HashMap<>(groupRoom.participantNames);
            names.put(me, Fb.myName());
            Map<String, String> photos = new HashMap<>(groupRoom.participantPhotos);
            photos.put(me, Fb.myPhoto());
            String summary = Fb.myName() + ": " + (lastMsg == null ? "" : lastMsg);
            return Fb.map(
                    "participantNames", names,
                    "participantPhotos", photos,
                    "lastMessage", summary,
                    "lastMessageTime", FieldValue.serverTimestamp(),
                    "lastMessageTimeMs", System.currentTimeMillis(),
                    "unreadCount", unread);
        }
        Map<String, Object> unread = new HashMap<>();
        unread.put(peerId, FieldValue.increment(1));
        unread.put(me, 0);
        Map<String, Object> typingOff = new HashMap<>();
        typingOff.put(me, false);
        Map<String, String> names = new HashMap<>();
        names.put(me, Fb.myName());
        if (peerName != null && !peerName.isEmpty()) names.put(peerId, peerName);
        Map<String, String> photos = new HashMap<>();
        photos.put(me, Fb.myPhoto());
        if (peerPhoto != null && !peerPhoto.isEmpty()) photos.put(peerId, peerPhoto);
        return Fb.map(
                "participants", java.util.Arrays.asList(me, peerId),
                "participantNames", names,
                "participantPhotos", photos,
                "lastMessage", lastMsg == null ? "" : lastMsg,
                "lastMessageTime", FieldValue.serverTimestamp(),
                "lastMessageTimeMs", System.currentTimeMillis(),
                "typing", typingOff,
                "unreadCount", unread);
    }

    private void sendVoice(String url, int durSec) {
        String me = Fb.uid();
        if (me == null) return;
        long now = System.currentTimeMillis();
        com.google.firebase.firestore.DocumentReference msgRef =
                Fb.db().collection("chats").document(chatId).collection("messages").document();
        Map<String, Object> msg = Fb.map(
                "senderId", me,
                "senderName", Fb.myName(),
                "senderPhoto", Fb.myPhoto(),
                "receiverId", isGroupChat ? "" : peerId,
                "text", "",
                "type", "voice",
                "voiceUrl", url,
                "voiceDurationSec", durSec,
                "isSeen", false,
                "isDelivered", false,
                "isDeleted", false,
                "createdAtMs", now,
                "createdAt", FieldValue.serverTimestamp());
        Map<String, Object> chatDoc = buildChatRoomDoc(me, "🎤 Voice note · " + Format.duration(durSec));
        forceScrollBottom = true;
        WriteBatch batch = Fb.db().batch();
        batch.set(msgRef, msg);
        batch.set(Fb.db().collection("chats").document(chatId), chatDoc, SetOptions.merge());
        batch.commit().addOnFailureListener(e -> Ui.toast(this, "Couldn't send voice note"));
    }

    // ---------- chat theme ----------
    private void themePicker() {
        String[] names = {"friendbook Blue", "Lover Red", "Kiwi Green", "Royal Purple",
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
        String me = Fb.uid();
        if (me == null || chatId == null) return;
        // Use update() rather than set() so opening an empty chat never creates an incomplete room doc
        Fb.db().collection("chats").document(chatId).update("unreadCount." + me, 0);
    }

    /** ✨ Sticker pack — big emoji stickers, friendbook Messenger style. */
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
        String me = Fb.uid();
        if (me == null) return;
        busy = true;
        long now = System.currentTimeMillis();
        com.google.firebase.firestore.DocumentReference msgRef =
                Fb.db().collection("chats").document(chatId).collection("messages").document();
        Models.Message opt = new Models.Message();
        opt.id = msgRef.getId();
        opt.senderId = me;
        opt.receiverId = peerId;
        opt.text = emoji;
        opt.type = "sticker";
        opt.createdAt = now;
        forceScrollBottom = true;
        adapter.appendOptimistic(opt);
        rv.scrollToPosition(adapter.getItemCount() - 1);

        Map<String, Object> msg = Fb.map(
                "senderId", me,
                "senderName", Fb.myName(),
                "senderPhoto", Fb.myPhoto(),
                "receiverId", isGroupChat ? "" : peerId,
                "text", emoji,
                "imageUrl", null,
                "type", "sticker",
                "isSeen", false,
                "isDelivered", false,
                "isDeleted", false,
                "createdAtMs", now,
                "createdAt", FieldValue.serverTimestamp());
        Map<String, Object> chatDoc = buildChatRoomDoc(me, "✨ " + emoji + " sticker");
        WriteBatch batch = Fb.db().batch();
        batch.set(msgRef, msg);
        batch.set(Fb.db().collection("chats").document(chatId), chatDoc, SetOptions.merge());
        batch.commit()
                .addOnSuccessListener(x -> busy = false)
                .addOnFailureListener(e -> { busy = false; Ui.toast(this, "Queued offline — will sync automatically"); });
    }

    private void send(String text, String imageUrl, String rawOverride) {
        final String finalText = rawOverride != null ? rawOverride
                : (text != null && !text.isEmpty()) ? text : null;
        if (finalText == null && imageUrl == null) return;
        String me = Fb.uid();
        if (me == null) return;
        if (finalText != null) {
            com.friendbook.social.core.ContentSafetyEngine.Verdict tv =
                    com.friendbook.social.core.ContentSafetyEngine.checkText(finalText);
            if (tv.unsafe) {
                Ui.info(this, "🛡 Message Blocked", tv.message);
                return;
            }
        }
        String type = imageUrl != null ? "image" : "text";
        long now = System.currentTimeMillis();

        // ⚡ 0.0ms Optimistic UI: clear input & render bubble immediately!
        if (rawOverride == null && imageUrl == null && input != null) {
            input.setText("");
        }
        typingSent = false;

        com.google.firebase.firestore.DocumentReference msgRef =
                Fb.db().collection("chats").document(chatId).collection("messages").document();
        Models.Message opt = new Models.Message();
        opt.id = msgRef.getId();
        opt.senderId = me;
        opt.receiverId = peerId;
        opt.text = finalText != null ? finalText : "";
        opt.imageUrl = imageUrl == null ? "" : imageUrl;
        opt.type = type;
        opt.createdAt = now;

        Map<String, Object> msg = Fb.map(
                "senderId", me,
                "senderName", Fb.myName(),
                "senderPhoto", Fb.myPhoto(),
                "receiverId", isGroupChat ? "" : peerId,
                "text", finalText != null ? finalText : "",
                "imageUrl", imageUrl,
                "type", type,
                "isSeen", false,
                "isDelivered", false,
                "isDeleted", false,
                "createdAtMs", now,
                "createdAt", FieldValue.serverTimestamp());
        if (replyingMsg != null) {
            String rText = replyingMsg.text != null && !replyingMsg.text.isEmpty()
                    ? replyingMsg.text : ("voice".equals(replyingMsg.type) ? "🎤 Voice note" : "📷 Photo");
            String rSender = me.equals(replyingMsg.senderId) ? Fb.myName() : peerName;
            msg.put("replyToId", replyingMsg.id);
            msg.put("replyToText", rText);
            msg.put("replyToSender", rSender);
            opt.replyToText = rText;
            opt.replyToSender = rSender;
            replyingMsg = null;
            if (replyBar != null) replyBar.setVisibility(View.GONE);
        }

        forceScrollBottom = true;
        adapter.appendOptimistic(opt);
        rv.scrollToPosition(adapter.getItemCount() - 1);

        Map<String, Object> chatDoc = buildChatRoomDoc(me, "image".equals(type) ? "📷 Photo" : finalText);

        WriteBatch batch = Fb.db().batch();
        batch.set(msgRef, msg);
        batch.set(Fb.db().collection("chats").document(chatId), chatDoc, SetOptions.merge());
        batch.commit().addOnFailureListener(e ->
                Ui.toast(this, "Queued offline — will sync automatically"));
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == PICK_IMG && res == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            java.util.List<Uri> single = new ArrayList<>();
            single.add(uri);
            com.friendbook.social.core.ContentSafetyEngine.scanMediaAsync(this, single, false, "", v -> {
                if (isFinishing()) return;
                if (v.unsafe) {
                    Ui.info(this, "🛡 Unsafe Photo Blocked", v.message);
                    return;
                }
                Ui.toast(this, "Uploading photo…");
                String path = "chat/" + chatId + "/" + System.currentTimeMillis() + ".jpg";
                com.friendbook.social.core.Cloudinary.uploadOrFirebase(this, path, uri)
                        .addOnSuccessListener(url -> send(null, url.toString(), null))
                        .addOnFailureListener(e -> Ui.toast(this, "Upload failed"));
            });
        }
    }

    // ---------- message long-press ----------
    @Override
    public void longPress(Models.Message m) {
        boolean mine = Fb.uid() != null && Fb.uid().equals(m.senderId);
        boolean groupAdmin = isGroupChat && groupRoom != null && groupRoom.isGroupAdmin(Fb.uid());
        List<String> opts = new ArrayList<>();
        opts.add("↩ Reply to message");
        opts.add("😍 React to message");
        opts.add("↪ Forward message");
        opts.add("📋 Copy text");
        if (mine || groupAdmin) {
            opts.add("🗑 Delete for everyone");
            opts.add("👤 Delete for me");
        } else {
            opts.add("👤 Delete for me");
            opts.add("🚩 Report");
        }
        String[] arr = opts.toArray(new String[0]);
        Ui.choose(this, "Message", arr, w -> {
            String sel = arr[w];
            if (sel.contains("Reply")) {
                replyingMsg = m;
                String snippet = m.text != null && !m.text.isEmpty() ? m.text
                        : ("voice".equals(m.type) ? "🎤 Voice note" : "📷 Photo");
                replyLabel.setText("↩ Replying to " + (mine ? "yourself" : peerName) + ": " + snippet);
                replyBar.setVisibility(View.VISIBLE);
                input.requestFocus();
                return;
            }
            if (sel.contains("Forward")) {
                Intent fw = new Intent(this, NewChatActivity.class);
                fw.putExtra("forward_text", m.text == null ? "" : m.text);
                startActivity(fw);
                return;
            }
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

    private void nicknameDialog() {
        EditText in = Ui.input(this, "Nickname for " + peerName + " (leave empty to reset)");
        in.setText(Prefs.chatNickname(this, chatId));
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 18, 10, 18, 0);
        box.addView(in, new LinearLayout.LayoutParams(-1, Ui.dp(this, 44)));
        new android.app.AlertDialog.Builder(this)
                .setTitle("✏️ Set nickname")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String n = in.getText().toString().trim();
                    Prefs.setChatNickname(this, chatId, n);
                    nameTv.setText(n.isEmpty() ? peerName : n);
                    Ui.toast(this, n.isEmpty() ? "Nickname cleared" : "Nickname saved ✓");
                })
                .setNegativeButton("Cancel", null)
                .show();
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
