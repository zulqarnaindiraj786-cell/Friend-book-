package com.friendbook.social.chat;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
    private DocumentSnapshot historyCursor,historyEnd,lastServerMessage;
    private Set<String> maskedHistoryIds=new HashSet<>();
    private final java.util.Deque<HistoryResume> historyRanges=new java.util.ArrayDeque<>();
    private static final class HistoryResume {
        final DocumentSnapshot cursor,end;final boolean more;final Set<String> masked;
        HistoryResume(DocumentSnapshot c,DocumentSnapshot e,boolean m,Set<String> ids){cursor=c;end=e;more=m;masked=new HashSet<>(ids);}
    }
    private boolean historyLoading, historyMore=true,historyFailed;
    private int historyGeneration;
    private TextView historyButton;
    private android.widget.ProgressBar historySpinner;
    private boolean chatVisible;
    private String messageActor;
    private final Set<String> receiptInFlight=new HashSet<>(),receiptFailed=new HashSet<>();
    private final Map<String,Models.Message> historyMessages=new HashMap<>();

    // ============ MESSAGE CACHE ============
    private static final long MESSAGES_CACHE_TTL_MS = 30_000; // 30 seconds
    private boolean messagesCacheLoaded = false;
    private long lastKnownMessageTime = 0L;

    private String peerId, peerName = "", peerPhoto = "", chatId;
    private boolean isGroupChat = false;
    private boolean isTeamChat = false;
    private Models.ChatRoom groupRoom;
    private TextView presenceTv, nameTv;
    private View peerOnlineDot;
    private View composerRoot;
    private TextView readOnlyBanner;
    private ImageView barTick;
    private ImageView barAvatar;
    private ImageView voiceCallBtn, videoCallBtn, settingsBtn;
    private EditText input;
    private MessagesAdapter adapter;
    private RecyclerView rv;
    private ListenerRegistration msgReg, userReg, roomReg;
    private Presence.Handle presence, typingH;
    private boolean peerOnline = false;
    private long peerLastSeen = 0L;
    private boolean typingSent;
    private long lastTypingPush;
    private Handler typingHandler = new Handler(Looper.getMainLooper());
    private Runnable typingClearRunnable;
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
        Ui.statusBarLight(this);
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
        root.setBackgroundColor(Ui.isDark(this) ? Ui.bg(this) : Color.WHITE);

        // Clean Messenger-style white header: <  [Avatar+GreenDot] Name / Active status   [Call] [Video] [Gear]
        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(Ui.card(this));
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 4), Ui.dp(this, 7), Ui.dp(this, 6), Ui.dp(this, 7));

        int headerIconTint = Ui.isDark(this) ? Color.WHITE : 0xFF1C1E21;
        ImageView back = Ui.backArrow(this, headerIconTint, v -> finish());
        bar.addView(back);

        android.widget.FrameLayout avatarWrap = new android.widget.FrameLayout(this);
        int avPx = Ui.dp(this, 40);
        barAvatar = new ImageView(this);
        Ui.avatar(this, barAvatar, isTeamChat ? Ui.TEAM_AVATAR_TOKEN : peerPhoto);
        avatarWrap.addView(barAvatar, new android.widget.FrameLayout.LayoutParams(avPx, avPx, Gravity.CENTER));

        peerOnlineDot = new View(this);
        android.graphics.drawable.GradientDrawable dotBg = new android.graphics.drawable.GradientDrawable();
        dotBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dotBg.setColor(0xFF31A24C);
        dotBg.setStroke(Ui.dp(this, 2), Ui.card(this));
        peerOnlineDot.setBackground(dotBg);
        peerOnlineDot.setVisibility(View.GONE);
        int dotPx = Ui.dp(this, 12);
        android.widget.FrameLayout.LayoutParams dotLp =
                new android.widget.FrameLayout.LayoutParams(dotPx, dotPx, Gravity.BOTTOM | Gravity.END);
        avatarWrap.addView(peerOnlineDot, dotLp);

        LinearLayout.LayoutParams avWrapLp = new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42));
        avWrapLp.leftMargin = Ui.dp(this, 2);
        bar.addView(avatarWrap, avWrapLp);

        LinearLayout nameCol = Ui.col(this);
        LinearLayout.LayoutParams ncp = new LinearLayout.LayoutParams(0, -2, 1);
        ncp.leftMargin = Ui.dp(this, 10);
        ncp.rightMargin = Ui.dp(this, 4);
        nameTv = Ui.tvBold(this, peerName, 16.5f, Ui.txt(this));
        nameTv.setSingleLine(true);
        nameTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        barTick = new ImageView(this);
        int ts = Ui.dp(this, 16);
        LinearLayout.LayoutParams tickp = new LinearLayout.LayoutParams(ts, ts);
        tickp.leftMargin = Ui.dp(this, 4);
        tickp.gravity = Gravity.CENTER_VERTICAL;
        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        nameRow.addView(nameTv, new LinearLayout.LayoutParams(-2, -2));
        nameRow.addView(barTick, tickp);

        presenceTv = Ui.tv(this, "", 12.5f, Ui.sub(this));
        presenceTv.setSingleLine(true);
        presenceTv.setVisibility(View.GONE);
        nameCol.addView(nameRow);
        nameCol.addView(presenceTv);
        bar.addView(nameCol, ncp);

        // Top-right Voice Call, Video Call & Settings Gear icons (exact match to screenshot!)
        voiceCallBtn = buildHeaderActionIcon(com.friendbook.social.core.Icons.CALL_FILLED, headerIconTint, 38, 8);
        voiceCallBtn.setOnClickListener(v -> startChatCall(false));
        bar.addView(voiceCallBtn);

        videoCallBtn = buildHeaderActionIcon(com.friendbook.social.core.Icons.VIDEOCAM_FILLED, headerIconTint, 40, 8);
        videoCallBtn.setOnClickListener(v -> startChatCall(true));
        bar.addView(videoCallBtn);

        settingsBtn = buildHeaderActionIcon(com.friendbook.social.core.Icons.SETTINGS_GEAR_FILLED, headerIconTint, 38, 8);
        bar.addView(settingsBtn);

        if (isTeamChat) {
            voiceCallBtn.setVisibility(View.GONE);
            videoCallBtn.setVisibility(View.GONE);
        }

        root.addView(bar);
        root.addView(Ui.divider(this));

        View.OnClickListener prof = v -> {
            if (isGroupChat) {
                startActivity(new Intent(this, GroupChatInfoActivity.class).putExtra("chat_id", chatId));
            } else if (!isTeamChat) {
                Intent i = new Intent(this, ProfileActivity.class);
                i.putExtra("uid", peerId);
                startActivity(i);
            }
        };
        avatarWrap.setOnClickListener(prof);
        nameCol.setOnClickListener(prof);
        settingsBtn.setOnClickListener(v -> {
            PopupMenu pm = new PopupMenu(this, v);
            if (!isTeamChat) {
                pm.getMenu().add("📞 Audio call");
                pm.getMenu().add("📹 Video call");
            }
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
                if (t.contains("Audio call")) startChatCall(false);
                else if (t.contains("Video call")) startChatCall(true);
                else if (t.contains("Group Info")) {
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
        rv.setBackgroundColor(Ui.isDark(this) ? Ui.bg(this) : Color.WHITE);
        rv.setClipToPadding(false);
        rv.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        rv.setLayoutManager(lm);
        rv.setItemAnimator(null);
        rv.setItemViewCacheSize(20);
        adapter = new MessagesAdapter(this, this);
        rv.setAdapter(adapter);
        rv.addOnLayoutChangeListener((v, l, t, r, bo, ol, ot, or2, obo) -> {
            if (bo < obo && adapter.getItemCount() > 0 && (forceScrollBottom || ((LinearLayoutManager)rv.getLayoutManager()).findLastVisibleItemPosition()>=adapter.getItemCount()-3)) {
                rv.post(() -> rv.scrollToPosition(adapter.getItemCount() - 1));
            }
        });
        historyButton=Ui.tvBold(this,"View older messages",13,Ui.BLUE);
        historyButton.setPadding(Ui.dp(this,12),Ui.dp(this,8),Ui.dp(this,12),Ui.dp(this,8));
        historyButton.setOnClickListener(v -> {historyFailed=false;loadOlderMessages();});
        historyButton.setVisibility(View.GONE);root.addView(historyButton);
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView list,int dx,int dy) {
                LinearLayoutManager manager=(LinearLayoutManager)list.getLayoutManager();
                if(dy<0 && !historyFailed && manager!=null && manager.findFirstVisibleItemPosition()<=2) loadOlderMessages();
            }
        });
        android.widget.FrameLayout messageWrap=new android.widget.FrameLayout(this);messageWrap.addView(rv,new android.widget.FrameLayout.LayoutParams(-1,-1));
        historySpinner=new android.widget.ProgressBar(this);historySpinner.setVisibility(View.GONE);historySpinner.setContentDescription("Loading older messages");
        messageWrap.addView(historySpinner,new android.widget.FrameLayout.LayoutParams(Ui.dp(this,26),Ui.dp(this,26),Gravity.TOP|Gravity.CENTER_HORIZONTAL));
        root.addView(messageWrap, new LinearLayout.LayoutParams(-1, 0, 1));

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

    private ImageView buildHeaderActionIcon(int drawableRes, int tintColor, int boxDp, int padDp) {
        ImageView iv = new ImageView(this);
        int px = Ui.dp(this, boxDp);
        int pad = Ui.dp(this, padDp);
        iv.setLayoutParams(new LinearLayout.LayoutParams(px, px));
        iv.setPadding(pad, pad, pad, pad);
        iv.setImageResource(drawableRes);
        iv.setColorFilter(tintColor);
        return iv;
    }

    private void startChatCall(boolean isVideo) {
        if (isTeamChat) return;
        String display = nameTv != null && nameTv.getText() != null
                ? nameTv.getText().toString() : peerName;
        CallActivity.startOutgoing(
                this,
                chatId,
                isGroupChat ? "" : peerId,
                display,
                peerPhoto,
                isGroupChat,
                isVideo);
    }

    @Override
    public void onCallClick(Models.Message m) {
        boolean vid = m != null && ("video".equalsIgnoreCase(m.callType)
                || (m.text != null && m.text.toLowerCase().contains("video")));
        startChatCall(vid);
    }

    @Override
    public void onReactionSummaryClick(Models.Message m) {
        if (m == null) return;
        com.friendbook.social.post.Reactions.showMessageReactors(
                this, m, isTeamChat, () -> adapter.notifyDataSetChanged());
    }

    private View buildComposer() {
        final int composerBlue = Prefs.chatBg(this);
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

        // Clean Messenger-style Bottom Bar: [Gallery] [Mic] [ Message ... 😊 ] [👍 / ➤]
        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Ui.pad(row, 6, 7, 6, 8);

        ImageView photo = buildHeaderActionIcon(
                com.friendbook.social.core.Icons.GALLERY_FILLED, composerBlue, 38, 7);
        photo.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            startActivityForResult(i, PICK_IMG);
        });
        row.addView(photo);

        mic = buildHeaderActionIcon(
                com.friendbook.social.core.Icons.MIC_FILLED, composerBlue, 38, 7);
        mic.setOnClickListener(v -> toggleVoiceRecording());
        row.addView(mic);

        // Rounded #F0F2F5 Message pill with right-aligned blue Sticker/Smile icon inside!
        LinearLayout pill = Ui.row(this);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        int pillBg = Ui.isDark(this) ? 0xFF2C2D30 : 0xFFF0F2F5;
        pill.setBackground(Ui.round(this, pillBg, 22));
        pill.setMinimumHeight(Ui.dp(this, 40));

        input = new EditText(this);
        input.setHint("Message");
        input.setHintTextColor(Ui.sub(this));
        input.setTextColor(Ui.txt(this));
        input.setTextSize(15.5f);
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setMaxLines(4);
        input.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8));
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                String txt = input.getText().toString().trim();
                if (!txt.isEmpty()) send(txt, null, null);
                return true;
            }
            return false;
        });
        pill.addView(input, new LinearLayout.LayoutParams(0, -2, 1f));

        ImageView sticker = buildHeaderActionIcon(
                com.friendbook.social.core.Icons.STICKER_SMILE_FILLED, composerBlue, 36, 7);
        sticker.setOnClickListener(v -> stickerSheet());
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36));
        stLp.rightMargin = Ui.dp(this, 3);
        pill.addView(sticker, stLp);

        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(0, -2, 1f);
        pillLp.leftMargin = Ui.dp(this, 4);
        pillLp.rightMargin = Ui.dp(this, 4);
        row.addView(pill, pillLp);

        final ImageView send = buildHeaderActionIcon(
                com.friendbook.social.core.Icons.SEND_FILLED, composerBlue, 38, 7);
        send.setVisibility(View.GONE);
        row.addView(send);

        final ImageView quick = buildHeaderActionIcon(
                com.friendbook.social.core.Icons.LIKE_FILLED, composerBlue, 38, 7);
        quick.setOnClickListener(v -> send(null, null, "👍"));
        row.addView(quick);

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
                    // 2s debounce - only send typing after user stops typing for 2s
                    Presence.setTyping(chatId, true);
                    typingSent = true;
                    lastTypingPush = now;
                    
                    // 3s auto-stop typing indicator
                    if (typingClearRunnable != null) {
                        typingHandler.removeCallbacks(typingClearRunnable);
                    }
                    typingClearRunnable = () -> {
                        Presence.setTyping(chatId, false);
                        typingSent = false;
                    };
                    typingHandler.postDelayed(typingClearRunnable, 3000);
                } else if (!has && typingSent) {
                    Presence.setTyping(chatId, false);
                    typingSent = false;
                    if (typingClearRunnable != null) {
                        typingHandler.removeCallbacks(typingClearRunnable);
                    }
                }
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        return wrap;
    }

    private void refreshPresenceText() {
        if (presenceTv == null || isGroupChat || isTeamChat) return;
        if (peerOnlineDot != null) {
            peerOnlineDot.setVisibility(peerOnline ? View.VISIBLE : View.GONE);
        }
        if (typingOn) {
            presenceTv.setText("typing…");
            presenceTv.setVisibility(View.VISIBLE);
            return;
        }
        String label = Presence.formatActiveStatus(peerOnline, peerLastSeen);
        if (label.isEmpty()) {
            presenceTv.setText("");
            presenceTv.setVisibility(View.GONE);
        } else {
            presenceTv.setText(label);
            presenceTv.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if(Fb.uid()==null || (messageActor!=null && !java.util.Objects.equals(messageActor,Fb.uid()))){finish();return;}

        if (isGroupChat) {
            roomReg = Fb.db().collection("chats").document(chatId)
                    .addSnapshotListener((d, err) -> {
                        if (d == null || !d.exists()) { finish(); return; }
                        CallActivity.maybeHandleIncomingFromRoomDoc(ChatActivity.this, d);
                        groupRoom = Models.ChatRoom.fromDoc(d);
                        peerName = (groupRoom.groupName == null || groupRoom.groupName.isEmpty())
                                ? "Group Chat" : groupRoom.groupName;
                        peerPhoto = groupRoom.groupPhoto == null ? "" : groupRoom.groupPhoto;
                        nameTv.setText(peerName);
                        barTick.setVisibility(View.GONE);
                        presenceTv.setText(groupRoom.participants.size() + " members");
                        presenceTv.setVisibility(View.VISIBLE);
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
            presenceTv.setVisibility(View.VISIBLE);
        } else {
            // Listen for incoming Voice/Video Calls on this 1:1 chat doc too!
            roomReg = Fb.db().collection("chats").document(chatId)
                    .addSnapshotListener((d, err) -> {
                        if (d != null && d.exists()) {
                            CallActivity.maybeHandleIncomingFromRoomDoc(ChatActivity.this, d);
                        }
                    });

            // Peer profile (live)
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

            // Live Presence (Firestore + RTDB merged + 30s live age refresh, never wiped by typing!)
            presence = Presence.watch(peerId, (online, lastSeen) -> {
                peerOnline = online;
                peerLastSeen = lastSeen;
                refreshPresenceText();
            });
            typingH = Presence.watchTyping(chatId, Fb.uid(), t -> {
                typingOn = t;
                refreshPresenceText();
            });
        }

        // ============ MESSAGE CACHE: Show cached messages instantly ============
        if (chatId != null && !messagesCacheLoaded) {
            List<Models.Message> cachedMessages = com.friendbook.social.core.FastCache.getMessages(chatId);
            if (cachedMessages != null && !cachedMessages.isEmpty()) {
                // Update last known message time for incremental fetch
                for (Models.Message m : cachedMessages) {
                    if (m.createdAt > lastKnownMessageTime) {
                        lastKnownMessageTime = m.createdAt;
                    }
                    messageSnapshots.put(m.id, m);
                }
                List<Models.Message> list = new ArrayList<>(messageSnapshots.values());
                list.sort(java.util.Comparator.comparingLong((Models.Message m) -> m.createdAt).thenComparing(m -> m.id));
                adapter.setData(list);
                initialScrollDone = true;
                rv.scrollToPosition(list.size() - 1);
            }
            messagesCacheLoaded = true;
        }

        // Parse only changed documents, not 200 message bodies on every receipt/typing update.
        if(!java.util.Objects.equals(messageActor,Fb.uid())){
            messageSnapshots.clear();historyMessages.clear();historyRanges.clear();maskedHistoryIds.clear();historyEnd=null;lastServerMessage=null;historyCursor=null;historyMore=true;initialScrollDone=false;messageActor=Fb.uid();
        }
        historyLoading=false;historySpinner.setVisibility(View.GONE);historyGeneration++;
        final int chatGeneration=historyGeneration; final String chatActor=Fb.uid();
        final Set<String> knownAtAttach=new HashSet<>(historyMessages.keySet());knownAtAttach.addAll(messageSnapshots.keySet());
        final boolean[] firstServer={true};
        final com.friendbook.social.core.RealtimeSoundGate soundGate = new com.friendbook.social.core.RealtimeSoundGate(Presence.now());
        msgReg = Fb.db().collection("chats").document(chatId)
                .collection("messages")
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .limitToLast(20)
                .addSnapshotListener(com.google.firebase.firestore.MetadataChanges.INCLUDE, (snap, err) -> {
                    if (snap == null || chatGeneration!=historyGeneration || !java.util.Objects.equals(chatActor,Fb.uid()) || isFinishing()) return;
                    boolean bridged=false;
                    if(!snap.getMetadata().isFromCache() && !snap.getMetadata().hasPendingWrites() && !snap.isEmpty()){
                        Set<String> known=firstServer[0]?knownAtAttach:new HashSet<>(historyMessages.keySet());
                        if(!firstServer[0])known.addAll(messageSnapshots.keySet());
                        Set<String> headIds=new HashSet<>();for(DocumentSnapshot doc:snap)headIds.add(doc.getId());
                        if(lastServerMessage!=null && historyCursor!=null && !known.isEmpty() && java.util.Collections.disjoint(known,headIds)){
                            historyRanges.push(new HistoryResume(historyCursor,historyEnd,historyMore,maskedHistoryIds));
                            historyEnd=lastServerMessage;historyCursor=snap.getDocuments().get(0);historyMore=true;maskedHistoryIds=new HashSet<>(known);bridged=true;
                        }
                        if(historyCursor==null){historyCursor=snap.getDocuments().get(0);historyMore=snap.size()==20;}
                        lastServerMessage=snap.getDocuments().get(snap.size()-1);firstServer[0]=false;
                    }
                    if(snap.getDocumentChanges(com.google.firebase.firestore.MetadataChanges.EXCLUDE).isEmpty() && !bridged){
                        boolean cached=snap.getMetadata().isFromCache() || snap.getMetadata().hasPendingWrites(),playSound=false;
                        boolean eligible=soundGate.begin(cached);Set<String> hidden=hiddenMsgs();
                        for(DocumentSnapshot doc:snap){Models.Message m=messageSnapshots.get(doc.getId());
                            if(m!=null && soundGate.observe(m.id,m.createdAt,Presence.now(),eligible) && !hidden.contains(m.id)
                                    && !java.util.Objects.equals(Fb.uid(),m.senderId) && !m.isDeleted && !"system".equals(m.type) && !"call".equals(m.type))playSound=true;}
                        soundGate.end(cached);if(playSound)com.friendbook.social.core.SoundManager.playNewMessage(this);
                        markVisibleReceipts();return;
                    }
                    Set<String> liveIds=new HashSet<>();for(DocumentSnapshot doc:snap)liveIds.add(doc.getId());
                    for(String id:new ArrayList<>(messageSnapshots.keySet()))if(!liveIds.contains(id))historyMessages.put(id,messageSnapshots.remove(id));
                    LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                    int prevCount = adapter.getItemCount();
                    int lastVis = lm != null ? lm.findLastVisibleItemPosition() : prevCount - 1;
                    boolean nearBottom = !initialScrollDone || forceScrollBottom || (prevCount - lastVis <= 4);

                    boolean soundEligible = soundGate.begin(snap.getMetadata().isFromCache() || snap.getMetadata().hasPendingWrites());
                    boolean incomingSound = false;
                    List<Models.Message> list = new ArrayList<>();
                    Set<String> hidden = hiddenMsgs();
                    String me = Fb.uid();
                    for (com.google.firebase.firestore.DocumentChange change : snap.getDocumentChanges()) {
                        String id = change.getDocument().getId();
                        if (change.getType() == com.google.firebase.firestore.DocumentChange.Type.REMOVED) {
                            Models.Message evicted=messageSnapshots.remove(id);
                            if(evicted!=null) historyMessages.put(id,evicted);
                        } else {
                            messageSnapshots.put(id, Models.Message.fromDoc(change.getDocument()));
                        }
                    }
                    for (DocumentSnapshot d : snap) {
                        Models.Message m = messageSnapshots.get(d.getId());
                        if (m == null) { m = Models.Message.fromDoc(d); messageSnapshots.put(d.getId(), m); }
                        boolean freshSound = soundGate.observe(m.id, m.createdAt, Presence.now(), soundEligible);
                        if (hidden.contains(m.id)) continue;
                        list.add(m);
                        if (freshSound && me != null && !me.equals(m.senderId) && !m.isDeleted
                                && !"system".equals(m.type) && !"call".equals(m.type)) incomingSound = true;
                    }
                    markVisibleReceipts();
                    soundGate.end(snap.getMetadata().isFromCache() || snap.getMetadata().hasPendingWrites());
                    if (incomingSound) com.friendbook.social.core.SoundManager.playNewMessage(this);
                    Map<String,Models.Message> combined=new HashMap<>(historyMessages);
                    for(Models.Message message:list) combined.put(message.id,message);
                    list=new ArrayList<>(combined.values()); list.removeIf(message -> hidden.contains(message.id) || maskedHistoryIds.contains(message.id));
                    list.sort(java.util.Comparator.comparingLong((Models.Message m)->m.createdAt).thenComparing(m->m.id));
                    adapter.setData(list);
                    
                    // ⚡ Update message cache with fresh data (30 seconds TTL)
                    if (chatId != null && list != null && !list.isEmpty()) {
                        com.friendbook.social.core.FastCache.saveMessages(chatId, list);
                    }
                    
                    hydrateTeamSharedReactions(list);
                    historyButton.setVisibility(View.GONE);
                    if (nearBottom && !list.isEmpty() && (!initialScrollDone || forceScrollBottom || list.size() > prevCount)) {
                        initialScrollDone = true;
                        forceScrollBottom = false;
                        rv.scrollToPosition(list.size() - 1);
                    }

                });
    }

    private void loadOlderMessages() {
        if(historyLoading || !historyMore || historyCursor==null) return;
        historyLoading=true; final int generation=historyGeneration; final String actor=Fb.uid();
        historyButton.setVisibility(View.GONE);historySpinner.setVisibility(View.VISIBLE);
        Query older=Fb.db().collection("chats").document(chatId).collection("messages")
                .orderBy("createdAt",Query.Direction.DESCENDING).startAfter(historyCursor);
        if(historyEnd!=null)older=older.endBefore(historyEnd);
        older.limit(20).get(com.google.firebase.firestore.Source.SERVER)
                .addOnSuccessListener(page -> {
                    if(generation!=historyGeneration || !java.util.Objects.equals(actor,Fb.uid()) || isFinishing()) return;
                    historyLoading=false;historyFailed=false;historySpinner.setVisibility(View.GONE); historyMore=page.size()==20;
                    if(!page.isEmpty()) historyCursor=page.getDocuments().get(page.size()-1);
                    if(historyEnd!=null && page.size()<20){HistoryResume saved=historyRanges.pop();historyCursor=saved.cursor;historyEnd=saved.end;historyMore=saved.more;maskedHistoryIds=saved.masked;}
                    LinearLayoutManager manager=(LinearLayoutManager)rv.getLayoutManager();
                    int first=manager.findFirstVisibleItemPosition(); View anchor=manager.findViewByPosition(first);
                    int top=anchor==null?0:anchor.getTop();
                    String anchorId=adapter.idAt(first);
                    for(DocumentSnapshot doc:page) { Models.Message message=Models.Message.fromDoc(doc); historyMessages.put(message.id,message); }
                    Map<String,Models.Message> combined=new HashMap<>(historyMessages); combined.putAll(messageSnapshots);
                    Set<String> hidden=hiddenMsgs(); List<Models.Message> rows=new ArrayList<>(combined.values());
                    rows.removeIf(message -> hidden.contains(message.id) || maskedHistoryIds.contains(message.id)); rows.sort(java.util.Comparator.comparingLong((Models.Message m)->m.createdAt).thenComparing(m->m.id));
                    adapter.setData(rows);
                    
                    // ⚡ Update message cache with older messages (30 seconds TTL)
                    if (chatId != null && rows != null && !rows.isEmpty()) {
                        com.friendbook.social.core.FastCache.saveMessages(chatId, rows);
                    }
                    
                    int restored=-1;
                    for(int i=0;i<rows.size();i++)if(java.util.Objects.equals(anchorId,rows.get(i).id)){restored=i;break;}
                    if(restored>=0)manager.scrollToPositionWithOffset(restored,top);
                    historyButton.setVisibility(View.GONE);
                    // History does not trigger sounds or bulk seen/delivered writes.
                }).addOnFailureListener(error -> {
                    if(generation!=historyGeneration) return;
                    historyLoading=false;historyFailed=true;historySpinner.setVisibility(View.GONE);historyButton.setText("Retry older messages");historyButton.setVisibility(View.VISIBLE);
                });
    }

    /** Dispatch receipts only for messages VISIBLE on screen in the foreground open conversation. */
    private void markVisibleReceipts(){
        String me=Fb.uid();if(!chatVisible || me==null || !me.equals(messageActor) || messageSnapshots.isEmpty())return;
        
        // Get visible message positions from RecyclerView
        LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
        if (lm == null) return;
        int firstVisible = lm.findFirstVisibleItemPosition();
        int lastVisible = lm.findLastVisibleItemPosition();
        if (firstVisible == -1 || lastVisible == -1) return;
        
        // Get adapter data to map positions to messages
        List<Models.Message> adapterData = adapter.getData(); // Need to add this method to adapter
        if (adapterData == null || adapterData.isEmpty()) return;
        
        WriteBatch batch=Fb.db().batch();List<String> claimed=new ArrayList<>();Set<String> hidden=hiddenMsgs();
        
        // Only mark messages that are VISIBLE on screen
        for (int pos = firstVisible; pos <= lastVisible && pos < adapterData.size(); pos++) {
            Models.Message m = adapterData.get(pos);
            if (m == null) continue;
            if(!me.equals(m.receiverId) || m.isDeleted || hidden.contains(m.id) || receiptInFlight.contains(m.id) || receiptFailed.contains(m.id))continue;
            if(m.isDelivered && (m.isSeen || !Prefs.readReceipts(this)))continue;
            Map<String,Object> patch=Fb.map("isDelivered",true);if(Prefs.readReceipts(this))patch.put("isSeen", true);
            com.friendbook.social.core.DataRouter.mirrorPut("chats", chatId, patch);
            batch.update(Fb.db().collection("chats").document(chatId).collection("messages").document(m.id),patch);
            receiptInFlight.add(m.id);claimed.add(m.id);
        }
        
        if(claimed.isEmpty() && unreadResetOnce)return;
        unreadResetOnce=true;
        com.friendbook.social.core.DataRouter.mirrorPut("chats", chatId, com.google.firebase.firestore.FieldPath.of("unreadCount");
        batch.update(Fb.db().collection("chats").document(chatId),com.google.firebase.firestore.FieldPath.of("unreadCount",me),0);
        batch.commit().addOnCompleteListener(done->{receiptInFlight.removeAll(claimed);if(!done.isSuccessful())receiptFailed.addAll(claimed);});
    }

    private boolean typingOn = false;

    @Override protected void onResume() {
        super.onResume();chatVisible=true;receiptFailed.clear();unreadResetOnce=false;markVisibleReceipts(); com.friendbook.social.core.SoundEffects.visibleChat(chatId);
    }
    @Override protected void onPause() {
        chatVisible=false;com.friendbook.social.core.SoundEffects.clearVisibleChat(chatId); super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        historyGeneration++; historyLoading=false;
        if (msgReg != null) { msgReg.remove(); msgReg = null; }
        if (userReg != null) { userReg.remove(); userReg = null; }
        if (roomReg != null) { roomReg.remove(); roomReg = null; }
        if (presence != null) { presence.close(); presence = null; }
        if (typingH != null) { typingH.close(); typingH = null; }
        if (typingSent) { Presence.setTyping(chatId, false); typingSent = false; }
        if (typingClearRunnable != null) { typingHandler.removeCallbacks(typingClearRunnable); typingClearRunnable = null; }
        MessagesAdapter.stopVoice();
        if (recording) { stopRecording(false); }
        
        // Clear message cache for this chat when leaving (cache will be refreshed on next open)
        if (chatId != null) {
            com.friendbook.social.core.FastCache.clearMessagesCache(chatId);
        }
        messagesCacheLoaded = false;
        lastKnownMessageTime = 0L;
    }

    private final Map<String, Models.Message> messageSnapshots = new HashMap<>();

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
            mic.setColorFilter(0xFFE41E3F); // red while recording
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
        mic.setColorFilter(Prefs.chatBg(this));
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
        Models.Message optimistic = new Models.Message();
        optimistic.id = msgRef.getId(); optimistic.senderId = me; optimistic.receiverId = peerId;
        optimistic.type = "voice"; optimistic.voiceUrl = url; optimistic.voiceDurationSec = durSec;
        optimistic.createdAt = now; optimistic.text = "";
        adapter.appendOptimistic(optimistic);
        rv.scrollToPosition(adapter.getItemCount() - 1);
        Map<String, Object> chatDoc = buildChatRoomDoc(me, "🎤 Voice note · " + Format.duration(durSec));
        forceScrollBottom = true;
        WriteBatch batch = Fb.db().batch();
        batch.set(msgRef, msg);
        com.friendbook.social.core.DataRouter.mirrorPut("chats", chatId, chatDoc);
        batch.set(Fb.db().collection("chats").document(chatId), chatDoc, SetOptions.merge());
        com.friendbook.social.core.SoundManager.action(this, "sent", me);
        batch.commit().addOnSuccessListener(x -> {
            com.friendbook.social.push.FCMPush.message(chatId, msgRef.getId());
        })
                .addOnFailureListener(e -> Ui.toast(this, "Couldn't send voice note"));
    }

    // ---------- chat theme ----------
    private void themePicker() {
        String[] names = {"Classic Blue", "friendbook Blue", "Lover Red", "Kiwi Green",
                "Royal Purple", "Sunset Orange", "Bubblegum Pink", "Ocean Teal", "Midnight"};
        int[] colors = {0xFF0084FF, 0xFF1877F2, 0xFFE41E3F, 0xFF31A24C,
                0xFF9360F7, 0xFFF5650D, 0xFFFF66C4, 0xFF2ABBA7, 0xFF1B2440};
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
        com.friendbook.social.core.DataRouter.mirrorPut("chats", chatId, "unreadCount." + me, 0);
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
        String me = Fb.uid();
        if (me == null) return;
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
        com.friendbook.social.core.DataRouter.mirrorPut("chats", chatId, chatDoc);
        batch.set(Fb.db().collection("chats").document(chatId), chatDoc, SetOptions.merge());
        com.friendbook.social.core.SoundManager.action(this, "sent", me);
        batch.commit()
                .addOnSuccessListener(x -> {
                    com.friendbook.social.push.FCMPush.message(chatId, msgRef.getId());
                })
                .addOnFailureListener(e -> { Ui.toast(this, "Queued offline — will sync automatically"); });
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

        // ⚡ Update message cache immediately for instant UI
        if (chatId != null) {
            com.friendbook.social.core.FastCache.prependMessage(chatId, opt);
        }

        Map<String, Object> chatDoc = buildChatRoomDoc(me, "image".equals(type) ? "📷 Photo" : finalText);
        com.friendbook.social.core.CloudflareStore.put("chats", chatId, chatDoc);
        com.friendbook.social.core.CloudflareStore.put("messages", chatId + "_" + msgRef.getId(), msg);

        WriteBatch batch = Fb.db().batch();
        batch.set(msgRef, msg);
        com.friendbook.social.core.DataRouter.mirrorPut("chats", chatId, chatDoc);
        batch.set(Fb.db().collection("chats").document(chatId), chatDoc, SetOptions.merge());
        com.friendbook.social.core.SoundManager.action(this, "sent", me);
        batch.commit().addOnSuccessListener(x -> {
            com.friendbook.social.push.FCMPush.message(chatId, msgRef.getId());
        }).addOnFailureListener(e ->
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
        if (m.reactions != null && !m.reactions.isEmpty()) {
            opts.add("👥 View reactions (" + m.reactions.size() + ")");
        }
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
            if (sel.contains("View reactions")) {
                onReactionSummaryClick(m);
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

    private final Set<String> hydratedTeamMsgIds = new HashSet<>();

    private void hydrateTeamSharedReactions(List<Models.Message> messages) {
        if (!isTeamChat || messages == null || messages.isEmpty()) return;
        for (final Models.Message m : messages) {
            if (m == null || m.id == null || !hydratedTeamMsgIds.add(m.id)) continue;
            final String sharedDocId = m.sharedTeamReactionDocId();
            Fb.db().collection("chats").document("team_shared_reactions")
                    .collection("messages").document(sharedDocId)
                    .get()
                    .addOnSuccessListener(doc -> {
                        if (isFinishing() || doc == null || !doc.exists()) return;
                        Models.Message shared = Models.Message.fromDoc(doc);
                        if (shared.reactions == null || shared.reactions.isEmpty()) return;
                        if (m.reactions == null) m.reactions = new HashMap<>();
                        if (m.reactorNames == null) m.reactorNames = new HashMap<>();
                        if (m.reactorPhotos == null) m.reactorPhotos = new HashMap<>();
                        m.reactions.putAll(shared.reactions);
                        if (shared.reactorNames != null) m.reactorNames.putAll(shared.reactorNames);
                        if (shared.reactorPhotos != null) m.reactorPhotos.putAll(shared.reactorPhotos);
                        adapter.notifyDataSetChanged();
                    });
        }
    }

    private void reactPicker(Models.Message m) {
        String[] emoj = {"👍 Like", "❤️ Love", "😂 Haha", "😮 Wow", "😢 Sad", "🙏 Thanks", "😡 Angry", "🚫 Remove reaction"};
        Ui.choose(this, "React to this message", emoj, w -> {
            String me = Fb.uid();
            if (me == null) return;
            String myName = Fb.myName();
            String myPhoto = Fb.myPhoto();
            java.util.Map<String, Object> upd = new HashMap<>();
            if (w == emoj.length - 1) {
                upd.put("reactions." + me, FieldValue.delete());
                upd.put("reactorNames." + me, FieldValue.delete());
                upd.put("reactorPhotos." + me, FieldValue.delete());
                if (m.reactions != null) m.reactions.remove(me);
                if (m.reactorNames != null) m.reactorNames.remove(me);
                if (m.reactorPhotos != null) m.reactorPhotos.remove(me);
            } else {
                String e = emoj[w].split(" ")[0];
                upd.put("reactions." + me, e);
                if (myName != null && !myName.isEmpty()) upd.put("reactorNames." + me, myName);
                if (myPhoto != null && !myPhoto.isEmpty()) upd.put("reactorPhotos." + me, myPhoto);
                if (m.reactions == null) m.reactions = new HashMap<>();
                if (m.reactorNames == null) m.reactorNames = new HashMap<>();
                if (m.reactorPhotos == null) m.reactorPhotos = new HashMap<>();
                m.reactions.put(me, e);
                if (myName != null && !myName.isEmpty()) m.reactorNames.put(me, myName);
                if (myPhoto != null && !myPhoto.isEmpty()) m.reactorPhotos.put(me, myPhoto);
            }
            adapter.notifyDataSetChanged();
            Fb.db().collection("chats").document(chatId)
                    .collection("messages").document(m.id)
                    .update(upd)
                    .addOnFailureListener(err -> Ui.toast(this, "Reaction failed"));
            if (isTeamChat || Ui.TEAM_USER_ID.equals(m.senderId)) {
                String sharedDocId = m.sharedTeamReactionDocId();
                com.google.firebase.firestore.DocumentReference sharedRef =
                        Fb.db().collection("chats").document("team_shared_reactions")
                                .collection("messages").document(sharedDocId);
                if (w == emoj.length - 1) {
                    sharedRef.update(upd);
                } else {
                    String e = emoj[w].split(" ")[0];
                    Map<String, Object> rx = java.util.Collections.singletonMap(me, e);
                    Map<String, Object> mergeDoc = new HashMap<>();
                    mergeDoc.put("reactions", rx);
                    if (myName != null && !myName.isEmpty()) {
                        mergeDoc.put("reactorNames", java.util.Collections.singletonMap(me, myName));
                    }
                    if (myPhoto != null && !myPhoto.isEmpty()) {
                        mergeDoc.put("reactorPhotos", java.util.Collections.singletonMap(me, myPhoto));
                    }
                    sharedRef.set(mergeDoc, SetOptions.merge());
                }
            }
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
