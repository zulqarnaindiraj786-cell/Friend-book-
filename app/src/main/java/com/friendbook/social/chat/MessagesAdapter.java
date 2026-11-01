package com.friendbook.social.chat;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.text.util.Linkify;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Format;
import com.friendbook.social.core.Icons;
import com.friendbook.social.core.Prefs;
import com.friendbook.social.core.Ui;
import com.friendbook.social.models.Models;
import com.friendbook.social.post.FullscreenMediaActivity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Clean Messenger-style Chat Bubbles:
 * - Consecutive bubble grouping (20dp outer corners, 5dp stacked inner corners, 2dp vertical seam)
 * - #F0F2F5 incoming bubbles with 28dp sender avatar on the bottom bubble of each run
 * - #0084FF outgoing bubbles with 16dp circular recipient seen mini-avatar on last seen message
 * - Interactive Voice/Video Call log cards, Voice Notes (1x/1.5x/2x), Images, Stickers & Reactions
 */
public class MessagesAdapter extends RecyclerView.Adapter<MessagesAdapter.VH> {

    public interface Actions {
        void longPress(Models.Message m);
        default void onCallClick(Models.Message m) { }
    }

    private static final long GROUP_WINDOW_MS = 180_000L; // 3 minutes

    private final Activity activity;
    private final Actions actions;
    private final List<Models.Message> data = new ArrayList<>();
    private List<List<Object>> renderKeys = new ArrayList<>();
    private String peerPhoto = "";
    private boolean isGroup = false;
    private final List<String> groupAdminIds = new ArrayList<>();
    private Map<String, String> groupNames = new HashMap<>();
    private Map<String, String> groupPhotos = new HashMap<>();

    private static MediaPlayer voicePlayer;
    private static String playingId;
    private static float voiceSpeed = 1.0f;

    public MessagesAdapter(Activity a, Actions actions) {
        this.activity = a;
        this.actions = actions;
    }

    public void setPeerPhoto(String url) {
        String clean = url == null ? "" : url;
        if (!clean.equals(peerPhoto)) {
            peerPhoto = clean;
            notifyDataSetChanged();
        }
    }

    public void setGroupMeta(boolean group, List<String> admins, Map<String, String> names, Map<String, String> photos) {
        if (isGroup == group && groupAdminIds.equals(admins == null ? java.util.Collections.emptyList() : admins)
                && groupNames.equals(names == null ? java.util.Collections.emptyMap() : names)
                && groupPhotos.equals(photos == null ? java.util.Collections.emptyMap() : photos)) return;
        this.isGroup = group;
        this.groupAdminIds.clear();
        if (admins != null) this.groupAdminIds.addAll(admins);
        this.groupNames = names != null ? new HashMap<>(names) : new HashMap<>();
        this.groupPhotos = photos != null ? new HashMap<>(photos) : new HashMap<>();
        notifyDataSetChanged();
    }

    public void setData(List<Models.Message> list) {
        final List<Models.Message> before = new ArrayList<>(data);
        final List<Models.Message> after = new ArrayList<>(list);
        final List<List<Object>> oldKeys = renderKeys;
        final List<List<Object>> newKeys = MessageRenderState.keys(after, Fb.uid());
        androidx.recyclerview.widget.DiffUtil.DiffResult diff = androidx.recyclerview.widget.DiffUtil.calculateDiff(
                new androidx.recyclerview.widget.DiffUtil.Callback() {
                    @Override public int getOldListSize() { return before.size(); }
                    @Override public int getNewListSize() { return after.size(); }
                    @Override public boolean areItemsTheSame(int oldPos, int newPos) {
                        return java.util.Objects.equals(before.get(oldPos).id, after.get(newPos).id);
                    }
                    @Override public boolean areContentsTheSame(int oldPos, int newPos) {
                        return oldPos < oldKeys.size() && oldKeys.get(oldPos).equals(newKeys.get(newPos));
                    }
                }, false);
        data.clear(); data.addAll(after); renderKeys = newKeys;
        diff.dispatchUpdatesTo(this);
    }

    public void appendOptimistic(Models.Message m) {
        if (m == null || m.id == null) return;
        List<Models.Message> next = new ArrayList<>(data);
        for (int i = 0; i < next.size(); i++) {
            if (m.id.equals(next.get(i).id)) { next.set(i, m); setData(next); return; }
        }
        next.add(m); setData(next);
    }

    public List<Models.Message> all() { return new ArrayList<>(data); }

    @Override
    public int getItemViewType(int position) {
        String me = Fb.uid();
        Models.Message m = data.get(position);
        return (me != null && m != null && me.equals(m.senderId)) ? 1 : 0;
    }

    @Override
    public int getItemCount() { return data.size(); }

    private boolean isGroupableBubble(Models.Message m) {
        if (m == null) return false;
        String t = m.type == null ? "text" : m.type;
        return !"system".equals(t) && !"sticker".equals(t);
    }

    private boolean isSameRun(int idxA, int idxB) {
        if (idxA < 0 || idxB < 0 || idxA >= data.size() || idxB >= data.size()) return false;
        Models.Message a = data.get(idxA);
        Models.Message b = data.get(idxB);
        if (!isGroupableBubble(a) || !isGroupableBubble(b)) return false;
        if (a.senderId == null || !a.senderId.equals(b.senderId)) return false;
        return Math.abs(b.createdAt - a.createdAt) <= GROUP_WINDOW_MS;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        Activity c = activity;
        boolean mine = (viewType == 1);
        int maxBubbleW = (int) (c.getResources().getDisplayMetrics().widthPixels * 0.74f);

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setGravity(mine ? (Gravity.END | Gravity.BOTTOM) : (Gravity.START | Gravity.BOTTOM));

        // Left 28dp circular avatar (for incoming messages)
        ImageView av = new ImageView(c);
        int avSize = Ui.dp(c, 28);
        LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(avSize, avSize);
        avLp.gravity = Gravity.BOTTOM;
        avLp.rightMargin = Ui.dp(c, 8);

        // Bubble column
        LinearLayout bubbleCol = Ui.col(c);
        bubbleCol.setGravity(mine ? Gravity.END : Gravity.START);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);

        LinearLayout bubble = Ui.col(c);
        Ui.pad(bubble, 13, 9, 13, 9);

        TextView senderHeader = Ui.tvBold(c, "", 11.5f, Ui.BLUE);
        senderHeader.setVisibility(View.GONE);
        senderHeader.setPadding(0, 0, 0, Ui.dp(c, 2));

        TextView quote = Ui.tv(c, "", 12, 0);
        Ui.pad(quote, 8, 4, 8, 4);
        quote.setMaxLines(2);
        quote.setMaxWidth(maxBubbleW - Ui.dp(c, 24));
        quote.setVisibility(View.GONE);
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        qlp.bottomMargin = Ui.dp(c, 4);

        TextView text = Ui.tv(c, "", 15.5f, 0);
        text.setMaxWidth(maxBubbleW);
        text.setLineSpacing(Ui.dp(c, 1.5f), 1.0f);
        Linkify.addLinks(text, Linkify.WEB_URLS);

        ImageView img = new ImageView(c);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setVisibility(View.GONE);

        // Voice note row
        LinearLayout voiceRow = Ui.row(c);
        voiceRow.setGravity(Gravity.CENTER_VERTICAL);
        voiceRow.setVisibility(View.GONE);
        TextView play = Ui.tvBold(c, "▶", 17, 0);
        TextView dur = Ui.tv(c, "", 13.5f, 0);
        dur.setPadding(Ui.dp(c, 8), 0, Ui.dp(c, 6), 0);
        TextView speed = Ui.roundChip(c, "1x", 0x33000000, Color.WHITE, 10);
        Ui.pad(speed, 7, 2, 7, 2);
        voiceRow.addView(play);
        voiceRow.addView(dur, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        voiceRow.addView(speed);

        // Call card row
        LinearLayout callRow = Ui.row(c);
        callRow.setGravity(Gravity.CENTER_VERTICAL);
        callRow.setVisibility(View.GONE);
        ImageView callIcon = new ImageView(c);
        int ciPx = Ui.dp(c, 34);
        callIcon.setPadding(Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8));
        callRow.addView(callIcon, new LinearLayout.LayoutParams(ciPx, ciPx));
        LinearLayout callTextCol = Ui.col(c);
        callTextCol.setPadding(Ui.dp(c, 10), 0, Ui.dp(c, 4), 0);
        TextView callTitle = Ui.tvBold(c, "", 14.5f, 0);
        TextView callSub = Ui.tv(c, "", 12f, 0);
        callTextCol.addView(callTitle);
        callTextCol.addView(callSub);
        callRow.addView(callTextCol);

        bubble.addView(senderHeader);
        bubble.addView(quote, qlp);
        bubble.addView(text);
        bubble.addView(voiceRow, new LinearLayout.LayoutParams(Ui.dp(c, 206), ViewGroup.LayoutParams.WRAP_CONTENT));
        bubble.addView(callRow);
        bubble.addView(img, new LinearLayout.LayoutParams(Ui.dp(c, 220), Ui.dp(c, 165)));

        TextView react = Ui.tvBold(c, "", 11.5f, 0);
        Ui.pad(react, 6, 2, 6, 2);
        react.setVisibility(View.GONE);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = Ui.dp(c, -4);

        bubbleCol.addView(bubble);
        bubbleCol.addView(react, rlp);

        // Right 16dp mini seen-avatar / delivery status slot (for outgoing messages, matching screenshot!)
        FrameLayout statusSlot = new FrameLayout(c);
        int stPx = Ui.dp(c, 16);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(stPx, stPx);
        stLp.gravity = Gravity.BOTTOM;
        stLp.leftMargin = Ui.dp(c, 4);

        ImageView seenAv = new ImageView(c);
        seenAv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        seenAv.setVisibility(View.GONE);
        statusSlot.addView(seenAv, new FrameLayout.LayoutParams(stPx, stPx));

        TextView tickDot = new TextView(c);
        tickDot.setGravity(Gravity.CENTER);
        tickDot.setTextSize(8.5f);
        tickDot.setTypeface(Typeface.DEFAULT_BOLD);
        tickDot.setVisibility(View.GONE);
        statusSlot.addView(tickDot, new FrameLayout.LayoutParams(stPx, stPx));

        if (mine) {
            av.setVisibility(View.GONE);
            row.addView(bubbleCol, colLp);
            row.addView(statusSlot, stLp);
        } else {
            statusSlot.setVisibility(View.GONE);
            row.addView(av, avLp);
            row.addView(bubbleCol, colLp);
        }

        VH vh = new VH(row, av, bubble, senderHeader, quote, text, img,
                voiceRow, play, dur, speed,
                callRow, callIcon, callTitle, callSub,
                react, statusSlot, seenAv, tickDot);
        vh.mine = mine;
        return vh;
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Models.Message m = data.get(position);
        Activity c = activity;
        LinearLayout row = (LinearLayout) h.itemView;

        // Centered system event pill in Group Chats ("👑 created group", etc.)
        if ("system".equals(m.type)) {
            h.av.setVisibility(View.GONE);
            h.statusSlot.setVisibility(View.GONE);
            h.senderHeader.setVisibility(View.GONE);
            h.quote.setVisibility(View.GONE);
            h.img.setVisibility(View.GONE);
            h.voiceRow.setVisibility(View.GONE);
            h.callRow.setVisibility(View.GONE);
            h.react.setVisibility(View.GONE);
            row.setGravity(Gravity.CENTER);
            row.setPadding(Ui.dp(c, 16), Ui.dp(c, 6), Ui.dp(c, 16), Ui.dp(c, 6));
            h.bubble.setPadding(Ui.dp(c, 12), Ui.dp(c, 5), Ui.dp(c, 12), Ui.dp(c, 5));
            h.bubble.setBackground(Ui.round(c, Ui.inputBg(c), 12));
            h.text.setVisibility(View.VISIBLE);
            h.text.setTextSize(12f);
            h.text.setTextColor(Ui.sub(c));
            h.text.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
            h.text.setText(m.text == null ? "" : m.text);
            h.itemView.setOnClickListener(null);
            h.itemView.setOnLongClickListener(null);
            return;
        }

        boolean samePrev = isSameRun(position - 1, position);
        boolean sameNext = isSameRun(position, position + 1);

        int topPad = Ui.dp(c, samePrev ? 1 : 6);
        int botPad = Ui.dp(c, sameNext ? 1 : 3);
        row.setGravity(h.mine ? (Gravity.END | Gravity.BOTTOM) : (Gravity.START | Gravity.BOTTOM));
        row.setPadding(Ui.dp(c, 10), topPad, Ui.dp(c, 8), botPad);
        h.bubble.setPadding(Ui.dp(c, 13), Ui.dp(c, 9), Ui.dp(c, 13), Ui.dp(c, 9));
        h.itemView.setOnClickListener(null);

        // 1. Incoming left avatar (shown ONLY on the bottom bubble of a consecutive incoming group!)
        if (!h.mine) {
            h.statusSlot.setVisibility(View.GONE);
            boolean isTeamMsg = Ui.TEAM_USER_ID.equals(m.senderId)
                    || Ui.isTeamIdentity(m.senderPhoto, m.senderName);
            if (!sameNext) {
                if (isTeamMsg) {
                    Ui.avatar(c, h.av, Ui.TEAM_AVATAR_TOKEN);
                } else if (isGroup) {
                    String sp = (m.senderPhoto != null && !m.senderPhoto.isEmpty())
                            ? m.senderPhoto : groupPhotos.get(m.senderId);
                    if (sp == null || sp.isEmpty()) {
                        Models.User cu = com.friendbook.social.core.FastCache.getUser(m.senderId);
                        if (cu != null) sp = cu.photoUrl;
                    }
                    Ui.avatar(c, h.av, sp);
                } else {
                    Ui.avatar(c, h.av, peerPhoto);
                }
                h.av.setVisibility(View.VISIBLE);
                if (!isTeamMsg && m.senderId != null && !m.senderId.isEmpty()) {
                    h.av.setOnClickListener(v -> c.startActivity(
                            new Intent(c, com.friendbook.social.profile.ProfileActivity.class)
                                    .putExtra("uid", m.senderId)));
                } else {
                    h.av.setOnClickListener(null);
                }
            } else {
                // Keep exact 28dp horizontal indent for upper bubbles in the run!
                h.av.setVisibility(View.INVISIBLE);
                h.av.setOnClickListener(null);
            }

            if (isGroup && !isTeamMsg && !samePrev) {
                String sn = (m.senderName != null && !m.senderName.isEmpty())
                        ? m.senderName : groupNames.get(m.senderId);
                if (sn == null || sn.isEmpty()) {
                    Models.User cu = com.friendbook.social.core.FastCache.getUser(m.senderId);
                    sn = cu != null ? cu.displayName : "Member";
                }
                boolean senderAdmin = m.senderId != null && groupAdminIds.contains(m.senderId);
                h.senderHeader.setText(sn + (senderAdmin ? " · Admin" : ""));
                h.senderHeader.setVisibility(View.VISIBLE);
            } else {
                h.senderHeader.setVisibility(View.GONE);
            }
        } else {
            h.av.setVisibility(View.GONE);
            h.senderHeader.setVisibility(View.GONE);
        }

        // 2. Grouped Bubble Background (#0084FF outgoing, #F0F2F5 incoming in light mode)
        int mineBg = Prefs.chatBg(c);
        int mineTxt = Prefs.chatTxt(c);
        int incomingBg = Ui.isDark(c) ? 0xFF2C2D30 : 0xFFF0F2F5;
        int incomingTxt = Ui.isDark(c) ? 0xFFFFFFFF : 0xFF050505;
        h.bubble.setBackground(buildGroupedBubbleDrawable(c, h.mine ? mineBg : incomingBg, h.mine, samePrev, sameNext));

        // 3. Outgoing bottom-right 16dp circular Seen Mini-Avatar (exact Messenger style!)
        if (h.mine) {
            h.statusSlot.setVisibility(View.VISIBLE);
            int lastSeenIdx = lastSeenMineIndex();
            int lastMineIdx = lastMineIndex();
            if (position == lastSeenIdx) {
                h.seenAv.setVisibility(View.VISIBLE);
                h.tickDot.setVisibility(View.GONE);
                Ui.avatar(c, h.seenAv, peerPhoto);
            } else if (position == lastMineIdx && !m.isSeen) {
                h.seenAv.setVisibility(View.GONE);
                h.tickDot.setVisibility(View.VISIBLE);
                GradientDrawable tdBg = new GradientDrawable();
                tdBg.setShape(GradientDrawable.OVAL);
                if (m.isDelivered) {
                    tdBg.setColor(mineBg);
                    h.tickDot.setTextColor(Color.WHITE);
                    h.tickDot.setText("✓");
                } else {
                    tdBg.setColor(Color.TRANSPARENT);
                    tdBg.setStroke(Ui.dp(c, 1.3f), mineBg);
                    h.tickDot.setTextColor(mineBg);
                    h.tickDot.setText(m.createdAt > 0 ? "✓" : "");
                }
                h.tickDot.setBackground(tdBg);
            } else {
                h.seenAv.setVisibility(View.GONE);
                h.tickDot.setVisibility(View.GONE);
            }
        }

        // 4. Reactions summary chip
        String rs = m.reactionSummary();
        if (rs.isEmpty()) {
            h.react.setVisibility(View.GONE);
        } else {
            h.react.setVisibility(View.VISIBLE);
            h.react.setText(rs);
            h.react.setBackground(Ui.roundStroke(c, Ui.card(c), 12, Ui.div(c), 1));
            h.react.setTextColor(Ui.txt(c));
        }

        // 5. Quoted reply snippet
        if (m.replyToText != null && !m.replyToText.isEmpty() && !m.isDeleted) {
            h.quote.setVisibility(View.VISIBLE);
            h.quote.setText("↩ " + (m.replyToSender == null || m.replyToSender.isEmpty() ? "" : m.replyToSender + ": ")
                    + m.replyToText);
            h.quote.setTextColor(h.mine ? 0xEEFFFFFF : Ui.sub(c));
            h.quote.setBackground(Ui.round(c, h.mine ? 0x26000000 : Ui.card(c), 10));
        } else {
            h.quote.setVisibility(View.GONE);
        }

        // 6. Content payload: Deleted / Sticker / Call Card / Voice Note / Photo+Text
        boolean isCall = "call".equals(m.type);
        boolean isVoice = "voice".equals(m.type) && m.voiceUrl != null && !m.voiceUrl.isEmpty();
        boolean hasImage = m.imageUrl != null && !m.imageUrl.isEmpty();

        if (m.isDeleted) {
            h.text.setTextSize(14.5f);
            h.text.setText("This message was unsent");
            h.text.setTextColor(h.mine ? 0xCCFFFFFF : Ui.sub(c));
            h.text.setTypeface(Typeface.DEFAULT, Typeface.ITALIC);
            h.text.setVisibility(View.VISIBLE);
            h.img.setVisibility(View.GONE);
            h.voiceRow.setVisibility(View.GONE);
            h.callRow.setVisibility(View.GONE);
        } else if ("sticker".equals(m.type)) {
            h.text.setVisibility(View.VISIBLE);
            h.img.setVisibility(View.GONE);
            h.voiceRow.setVisibility(View.GONE);
            h.callRow.setVisibility(View.GONE);
            h.bubble.setPadding(Ui.dp(c, 4), Ui.dp(c, 2), Ui.dp(c, 4), Ui.dp(c, 2));
            h.bubble.setBackgroundColor(Color.TRANSPARENT);
            h.text.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
            h.text.setText(m.text);
            h.text.setTextSize(46f);
        } else if (isCall) {
            h.text.setVisibility(View.GONE);
            h.img.setVisibility(View.GONE);
            h.voiceRow.setVisibility(View.GONE);
            h.callRow.setVisibility(View.VISIBLE);

            boolean vid = "video".equalsIgnoreCase(m.callType)
                    || (m.text != null && m.text.toLowerCase().contains("video"));
            boolean missed = "missed".equalsIgnoreCase(m.callStatus)
                    || "declined".equalsIgnoreCase(m.callStatus)
                    || (m.text != null && (m.text.contains("Missed") || m.text.contains("Declined")));

            GradientDrawable ciBg = new GradientDrawable();
            ciBg.setShape(GradientDrawable.OVAL);
            ciBg.setColor(missed ? 0x22E53935 : (h.mine ? 0x30FFFFFF : 0x1A0084FF));
            h.callIcon.setBackground(ciBg);
            h.callIcon.setImageResource(vid ? Icons.VIDEOCAM_FILLED : Icons.CALL_FILLED);
            h.callIcon.setColorFilter(missed ? 0xFFE53935 : (h.mine ? Color.WHITE : 0xFF0084FF));

            h.callTitle.setText(vid ? (missed ? "Missed video call" : "Video call")
                    : (missed ? "Missed voice call" : "Voice call"));
            h.callTitle.setTextColor(h.mine ? mineTxt : incomingTxt);

            String sub;
            if (m.callDurationSec > 0) {
                sub = Format.duration(m.callDurationSec) + " · Tap to call again";
            } else {
                sub = "Tap to call back";
            }
            h.callSub.setText(sub);
            h.callSub.setTextColor(h.mine ? 0xDDFFFFFF : Ui.sub(c));
            h.bubble.setOnClickListener(v -> {
                if (actions != null) actions.onCallClick(m);
            });
        } else if (isVoice) {
            h.text.setVisibility(View.GONE);
            h.img.setVisibility(View.GONE);
            h.callRow.setVisibility(View.GONE);
            h.voiceRow.setVisibility(View.VISIBLE);
            boolean isPlaying = m.id != null && m.id.equals(playingId);
            h.play.setText(isPlaying ? "⏸" : "▶");
            h.play.setTextColor(h.mine ? Color.WHITE : 0xFF0084FF);
            h.dur.setTextColor(h.mine ? 0xEEFFFFFF : incomingTxt);
            h.dur.setText("Voice · " + Format.duration(m.voiceDurationSec)
                    + (isPlaying ? "  playing" : ""));
            String spLabel = voiceSpeed == 1.5f ? "1.5x" : (voiceSpeed == 2.0f ? "2x" : "1x");
            h.speed.setText(spLabel);
            h.speed.setTextColor(h.mine ? Color.WHITE : 0xFF0084FF);
            h.speed.setBackground(Ui.round(c, h.mine ? 0x33000000 : Ui.card(c), 10));
            h.speed.setOnClickListener(v -> {
                voiceSpeed = voiceSpeed == 1.0f ? 1.5f : (voiceSpeed == 1.5f ? 2.0f : 1.0f);
                if (voicePlayer != null && playingId != null) {
                    try {
                        voicePlayer.setPlaybackParams(voicePlayer.getPlaybackParams().setSpeed(voiceSpeed));
                    } catch (Throwable ignored) { }
                }
                notifyDataSetChanged();
            });
            h.voiceRow.setOnClickListener(v -> toggleVoice(m));
            h.bubble.setOnClickListener(null);
        } else {
            h.voiceRow.setVisibility(View.GONE);
            h.callRow.setVisibility(View.GONE);
            h.bubble.setOnClickListener(null);
            h.text.setVisibility(View.VISIBLE);
            h.text.setTextSize(15.5f);
            h.text.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
            if (hasImage) {
                h.img.setVisibility(View.VISIBLE);
                Ui.img(c, h.img, m.imageUrl);
                h.img.setOnClickListener(v -> {
                    Intent i = new Intent(c, FullscreenMediaActivity.class);
                    i.putExtra("type", "image");
                    i.putExtra("url", m.imageUrl);
                    c.startActivity(i);
                });
            } else {
                h.img.setVisibility(View.GONE);
            }
            h.text.setText(m.text == null ? "" : m.text);
            h.text.setTextColor(h.mine ? mineTxt : incomingTxt);
            if (hasImage && (m.text == null || m.text.isEmpty())) {
                h.text.setVisibility(View.GONE);
            }
        }

        View.OnLongClickListener longPressHandler = v -> {
            if (actions != null && !m.isDeleted) actions.longPress(m);
            return true;
        };
        h.itemView.setOnLongClickListener(longPressHandler);
        h.bubble.setOnLongClickListener(longPressHandler);
    }

    private GradientDrawable buildGroupedBubbleDrawable(Activity c, int color, boolean mine,
                                                        boolean samePrev, boolean sameNext) {
        float big = Ui.dp(c, 20);
        float tight = Ui.dp(c, 5);
        float tl = (!mine && samePrev) ? tight : big;
        float tr = (mine && samePrev) ? tight : big;
        float br = (mine && sameNext) ? tight : big;
        float bl = (!mine && sameNext) ? tight : big;

        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setColor(color);
        gd.setCornerRadii(new float[]{tl, tl, tr, tr, br, br, bl, bl});
        return gd;
    }

    private void toggleVoice(Models.Message m) {
        if (m == null || m.voiceUrl == null || m.voiceUrl.isEmpty()) {
            Ui.toast(activity, "Voice note unavailable");
            return;
        }
        if (m.id != null && m.id.equals(playingId)) {
            stopVoice();
            notifyDataSetChanged();
            return;
        }
        stopVoice();
        try {
            voicePlayer = new MediaPlayer();
            voicePlayer.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            voicePlayer.setDataSource(m.voiceUrl);
            voicePlayer.setOnPreparedListener(mp -> {
                playingId = m.id;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    try {
                        mp.setPlaybackParams(mp.getPlaybackParams().setSpeed(voiceSpeed));
                    } catch (Throwable ignored) { }
                }
                voicePlayer.start();
                notifyDataSetChanged();
            });
            voicePlayer.setOnCompletionListener(mp -> {
                stopVoice();
                notifyDataSetChanged();
            });
            voicePlayer.setOnErrorListener((mp, what, extra) -> {
                stopVoice();
                Ui.toast(activity, "Couldn't play voice note");
                notifyDataSetChanged();
                return true;
            });
            voicePlayer.prepareAsync();
            Ui.toast(activity, "▶ Playing voice note…");
        } catch (Exception e) {
            stopVoice();
            Ui.toast(activity, "Couldn't play voice note");
        }
    }

    public static void stopVoice() {
        playingId = null;
        if (voicePlayer != null) {
            try { voicePlayer.release(); } catch (Throwable ignored) { }
            voicePlayer = null;
        }
    }

    private int lastMineIndex() {
        String me = Fb.uid();
        if (me == null) return -1;
        for (int i = data.size() - 1; i >= 0; i--) {
            Models.Message item = data.get(i);
            if (item != null && me.equals(item.senderId) && !"system".equals(item.type)) return i;
        }
        return -1;
    }

    private int lastSeenMineIndex() {
        String me = Fb.uid();
        if (me == null) return -1;
        for (int i = data.size() - 1; i >= 0; i--) {
            Models.Message item = data.get(i);
            if (item != null && me.equals(item.senderId) && item.isSeen && !"system".equals(item.type)) {
                return i;
            }
        }
        return -1;
    }

    static class VH extends RecyclerView.ViewHolder {
        ImageView av, img, callIcon, seenAv;
        LinearLayout bubble, voiceRow, callRow;
        android.widget.FrameLayout statusSlot;
        TextView senderHeader, quote, text, play, dur, speed, callTitle, callSub, react, tickDot;
        boolean mine;

        VH(View v, ImageView a, LinearLayout b, TextView sh, TextView q, TextView t, ImageView i,
           LinearLayout vr, TextView pl, TextView du, TextView sp,
           LinearLayout cr, ImageView ci, TextView ct, TextView cs,
           TextView re, android.widget.FrameLayout ss, ImageView sa, TextView td) {
            super(v);
            av = a; bubble = b; senderHeader = sh; quote = q; text = t; img = i;
            voiceRow = vr; play = pl; dur = du; speed = sp;
            callRow = cr; callIcon = ci; callTitle = ct; callSub = cs;
            react = re; statusSlot = ss; seenAv = sa; tickDot = td;
        }
    }
}
