package com.example.frienbook.chat;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.util.Linkify;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.frienbook.core.Fb;
import com.example.frienbook.core.Format;
import com.example.frienbook.core.Ui;
import com.example.frienbook.models.Models;
import com.example.frienbook.post.FullscreenMediaActivity;

import java.util.ArrayList;
import java.util.List;

/** Chat bubbles: mine (blue, right) / theirs (grey, left), images, ticks, deleted state. */
public class MessagesAdapter extends RecyclerView.Adapter<MessagesAdapter.VH> {

    public interface Actions { void longPress(Models.Message m); }

    private final Activity activity;
    private final Actions actions;
    private final List<Models.Message> data = new ArrayList<>();
    private String peerPhoto = "";

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

        TextView text = Ui.tv(c, "", 15, 0);
        Linkify.addLinks(text, Linkify.WEB_URLS);
        ImageView img = new ImageView(c);
        img.setVisibility(View.GONE);
        TextView ticks = Ui.tv(c, "", 10, 0);
        ticks.setGravity(Gravity.END);
        Ui.pad(ticks, 0, 2, 0, 0);

        bubble.addView(text);
        bubble.addView(img, new LinearLayout.LayoutParams(Ui.dp(c, 220), Ui.dp(c, 160)));
        bubbleCol.addView(bubble);
        bubbleCol.addView(ticks);

        row.addView(av, ap);
        row.addView(bubbleCol);
        if (viewType == 1) {
            // mine: right aligned
            row.setGravity(Gravity.END);
            row.removeView(av);
            av.setVisibility(View.GONE);
            row.addView(av); // keep layout spot (invisible)
        } else {
            row.setGravity(Gravity.START);
        }
        VH vh = new VH(row, av, bubble, text, img, ticks);
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
        h.bubble.setBackground(Ui.round(c, h.mine ? Ui.BLUE : Ui.inputBg(c), 16));

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

        if (m.isDeleted) {
            h.text.setText("🚫 This message was removed");
            h.text.setTextColor(h.mine ? 0xCCFFFFFF : Ui.sub(c));
            h.text.setTypeface(Typeface.DEFAULT, Typeface.ITALIC);
            h.img.setVisibility(View.GONE);
        } else {
            h.text.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
            boolean hasImage = m.imageUrl != null && !m.imageUrl.isEmpty();
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
            h.text.setTextColor(h.mine ? Color.WHITE : Ui.txt(c));
        }

        String time = Format.ago(m.createdAt);
        if (lastMine) h.ticks.setText(time + " · " + h.ticks.getText().toString());

        h.itemView.setOnLongClickListener(v -> {
            if (actions != null && !m.isDeleted) actions.longPress(m);
            return true;
        });
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
        LinearLayout bubble;
        TextView text, ticks;
        boolean mine;
        VH(View v, ImageView a, LinearLayout b, TextView t, ImageView i, TextView tk) {
            super(v);
            av = a; bubble = b; text = t; img = i; ticks = tk;
        }
    }
}
