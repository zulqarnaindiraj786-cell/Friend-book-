package com.friendbook.chat;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.text.util.Linkify;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.friendbook.core.Fb;
import com.friendbook.core.Format;
import com.friendbook.core.Prefs;
import com.friendbook.core.Ui;
import com.friendbook.models.Models;
import com.friendbook.post.FullscreenMediaActivity;

import java.util.ArrayList;
import java.util.List;

/** Chat bubbles: mine (theme color, right) / theirs (grey, left),
 *  text ✦ photos ✦ voice notes ✦ message reactions ✦ ticks ✦ deleted state. */
public class MessagesAdapter extends RecyclerView.Adapter<MessagesAdapter.VH> {

    public interface Actions { void longPress(Models.Message m); }

    private final Activity activity;
    private final Actions actions;
    private final List<Models.Message> data = new ArrayList<>();
    private String peerPhoto = "";

    /** one MediaPlayer at a time for voice notes + 1x / 1.5x / 2x speed (FB Lite Messenger feature) */
    private static MediaPlayer voicePlayer;
    private static String playingId;
    private static float voiceSpeed = 1.0f;

    public MessagesAdapter(Activity a, Actions actions) {
        this.activity = a;
        this.actions = actions;
    }

    public void setPeerPhoto(String url) { peerPhoto = url == null ? "" : url; }

    public void setData(List<Models.Message> list) {
        data.clear();
        data.addAll(list);
        notifyDataSetChanged();
    }

    public List<Models.Message> all() { return data; }

    @Override public int getItemViewType(int position) {
        return data.get(position).senderId.equals(Fb.uid()) ? 1 : 0;
    }

    @Override public int getItemCount() { return data.size(); }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        Activity c = activity;
        LinearLayout row = Ui.row(c);
        row.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
        Ui.pad(row, 10, 2, 10, 2);

        ImageView av = new ImageView(c);
        int s = Ui.dp(c, 28);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(s, s);

        LinearLayout bubbleCol = Ui.col(c);
        LinearLayout bubble = Ui.col(c);
        Ui.pad(bubble, 10, 8, 10, 8);

        TextView quote = Ui.tv(c, "", 11, 0);
        Ui.pad(quote, 8, 4, 8, 4);
        quote.setMaxLines(2);
        quote.setVisibility(View.GONE);
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(-2, -2);
        qlp.bottomMargin = Ui.dp(c, 4);

        TextView text = Ui.tv(c, "", 15, 0);
        Linkify.addLinks(text, Linkify.WEB_URLS);
        ImageView img = new ImageView(c);
        img.setVisibility(View.GONE);

        // voice row: ▶ icon + duration + 1x/1.5x/2x speed pill
        LinearLayout voiceRow = Ui.row(c);
        voiceRow.setVisibility(View.GONE);
        TextView play = Ui.tvBold(c, "▶", 18, 0);
        TextView dur = Ui.tv(c, "", 13, 0);
        dur.setPadding(Ui.dp(c, 8), 0, Ui.dp(c, 6), 0);
        TextView speed = Ui.roundChip(c, "1x", 0x33000000, Color.WHITE, 10);
        Ui.pad(speed, 7, 2, 7, 2);
        voiceRow.addView(play);
        voiceRow.addView(dur, new LinearLayout.LayoutParams(0, -2, 1));
        voiceRow.addView(speed);
        bubble.addView(quote, qlp);
        bubble.addView(text);
        bubble.addView(voiceRow, new LinearLayout.LayoutParams(Ui.dp(c, 210), -2));
        bubble.addView(img, new LinearLayout.LayoutParams(Ui.dp(c, 220), Ui.dp(c, 160)));

        TextView ticks = Ui.tv(c, "", 10, 0);
        ticks.setGravity(Gravity.END);
        Ui.pad(ticks, 0, 2, 0, 0);

        TextView react = Ui.tvBold(c, "", 11, 0);
        Ui.pad(react, 6, 2, 6, 2);
        react.setVisibility(View.GONE);

        bubbleCol.addView(bubble);
        bubbleCol.addView(react);
        bubbleCol.addView(ticks);

        row.addView(av, ap);
        row.addView(bubbleCol);
        if (viewType == 1) {
            row.setGravity(Gravity.END);
            bubbleCol.setGravity(Gravity.END);
            row.removeView(av);
            av.setVisibility(View.GONE);
            row.addView(av);
        } else {
            row.setGravity(Gravity.START);
        }
        VH vh = new VH(row, av, bubble, quote, text, img, ticks, voiceRow, play, dur, speed, react);
        vh.mine = viewType == 1;
        return vh;
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Models.Message m = data.get(position);
        Activity c = activity;

        if (!h.mine) {
            Ui.avatar(c, h.av, peerPhoto);
            h.av.setVisibility(View.VISIBLE);
        }
        int mineBg = Prefs.chatBg(c);
        int mineTxt = Prefs.chatTxt(c);
        h.bubble.setBackground(Ui.round(c, h.mine ? mineBg : Ui.inputBg(c), 16));

        // last mine message shows state ticks
        boolean lastMine = h.mine && position == lastMineIndex();
        h.ticks.setVisibility(lastMine ? View.VISIBLE : View.GONE);
        if (lastMine) {
            h.ticks.setTextColor(Ui.sub(c));
            if (m.isSeen) {
                h.ticks.setText("✓✓ Seen");
                h.ticks.setTextColor(Ui.BLUE);
            } else if (m.isDelivered) h.ticks.setText("✓✓ Delivered");
            else if (m.createdAt > 0) h.ticks.setText("✓ Sent");
            else h.ticks.setText("…");
        }

        // reactions pill
        String rs = m.reactionSummary();
        if (rs.isEmpty()) {
            h.react.setVisibility(View.GONE);
        } else {
            h.react.setVisibility(View.VISIBLE);
            h.react.setText(rs);
            h.react.setBackground(Ui.roundStroke(c, Ui.card(c), 12, Ui.div(c), 1));
            h.react.setTextColor(Ui.txt(c));
        }

        if (m.replyToText != null && !m.replyToText.isEmpty() && !m.isDeleted) {
            h.quote.setVisibility(View.VISIBLE);
            h.quote.setText("↩ " + (m.replyToSender == null || m.replyToSender.isEmpty() ? "" : m.replyToSender + ": ")
                    + m.replyToText);
            h.quote.setTextColor(h.mine ? 0xEEFFFFFF : Ui.sub(c));
            h.quote.setBackground(Ui.round(c, h.mine ? 0x33000000 : Ui.card(c), 8));
        } else {
            h.quote.setVisibility(View.GONE);
        }

        boolean isVoice = "voice".equals(m.type) && m.voiceUrl != null && !m.voiceUrl.isEmpty();
        boolean hasImage = m.imageUrl != null && !m.imageUrl.isEmpty();

        if (m.isDeleted) {
            h.text.setTextSize(14);
            h.text.setText("🚫 This message was removed");
            h.text.setTextColor(h.mine ? 0xCCFFFFFF : Ui.sub(c));
            h.text.setTypeface(Typeface.DEFAULT, Typeface.ITALIC);
            h.text.setVisibility(View.VISIBLE);
            h.img.setVisibility(View.GONE);
            h.voiceRow.setVisibility(View.GONE);
        } else if ("sticker".equals(m.type)) {
            // Big emoji sticker — transparent bubble, huge text
            h.text.setVisibility(View.VISIBLE);
            h.img.setVisibility(View.GONE);
            h.voiceRow.setVisibility(View.GONE);
            h.bubble.setBackground(Ui.round(c, Color.TRANSPARENT, 16));
            h.text.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
            h.text.setText(m.text);
            h.text.setTextSize(52);
        } else if (isVoice) {
            h.text.setTextSize(14);
            h.text.setVisibility(View.GONE);
            h.img.setVisibility(View.GONE);
            h.voiceRow.setVisibility(View.VISIBLE);
            boolean isPlaying = m.id.equals(playingId);
            h.play.setText(isPlaying ? "⏸" : "▶");
            h.play.setTextColor(h.mine ? Color.WHITE : Ui.BLUE);
            h.dur.setTextColor(h.mine ? 0xDDFFFFFF : Ui.txt(c));
            h.dur.setText("Voice · " + Format.duration(m.voiceDurationSec)
                    + (isPlaying ? "  playing" : ""));
            String spLabel = voiceSpeed == 1.5f ? "1.5x" : (voiceSpeed == 2.0f ? "2x" : "1x");
            h.speed.setText(spLabel);
            h.speed.setTextColor(h.mine ? Color.WHITE : Ui.BLUE);
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
            h.voiceRow.setOnClickListener(v -> toggleVoice(h, m));
        } else {
            h.voiceRow.setVisibility(View.GONE);
            h.text.setVisibility(View.VISIBLE);
            h.text.setTextSize(14);
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
            } else h.img.setVisibility(View.GONE);
            h.text.setText(m.text);
            h.text.setTextColor(h.mine ? mineTxt : Ui.txt(c));
            if (hasImage && (m.text == null || m.text.isEmpty())) h.text.setVisibility(View.GONE);
        }

        String time = Format.ago(m.createdAt);
        if (lastMine) h.ticks.setText(time + " · " + h.ticks.getText().toString());

        h.itemView.setOnLongClickListener(v -> {
            if (actions != null && !m.isDeleted) actions.longPress(m);
            return true;
        });
    }

    private void toggleVoice(VH h, Models.Message m) {
        if (m.id.equals(playingId)) {
            stopVoice();
            notifyDataSetChanged();
            return;
        }
        stopVoice();
        try {
            voicePlayer = new MediaPlayer();
            voicePlayer.setDataSource(m.voiceUrl);
            voicePlayer.setOnPreparedListener(mp -> {
                playingId = m.id;
                try {
                    mp.setPlaybackParams(mp.getPlaybackParams().setSpeed(voiceSpeed));
                } catch (Throwable ignored) { }
                voicePlayer.start();
                notifyDataSetChanged();
            });
            voicePlayer.setOnCompletionListener(mp -> {
                stopVoice();
                notifyDataSetChanged();
            });
            voicePlayer.prepareAsync();
            Ui.toast(activity, "▶ Playing voice note…");
        } catch (Exception e) {
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
        for (int i = data.size() - 1; i >= 0; i--) {
            if (data.get(i).senderId.equals(me)) return i;
        }
        return -1;
    }

    static class VH extends RecyclerView.ViewHolder {
        ImageView av, img;
        LinearLayout bubble, voiceRow;
        TextView quote, text, ticks, play, dur, speed, react;
        boolean mine;
        VH(View v, ImageView a, LinearLayout b, TextView q, TextView t, ImageView i, TextView tk,
           LinearLayout vr, TextView pl, TextView du, TextView sp, TextView re) {
            super(v);
            av = a; bubble = b; quote = q; text = t; img = i; ticks = tk;
            voiceRow = vr; play = pl; dur = du; speed = sp; react = re;
        }
    }
}
