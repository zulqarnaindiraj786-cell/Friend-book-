package com.friendbook.story;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.friendbook.core.Fb;
import com.friendbook.core.Ui;
import com.friendbook.models.Models;

import java.util.ArrayList;
import java.util.List;

/** Horizontal stories rail: "Create story" + one bubble per author (supports Aa text stories). */
public class StoriesAdapter extends RecyclerView.Adapter<StoriesAdapter.VH> {

    public interface Listener {
        void onGroup(Group g);
        void onCreate();
    }

    public static class Group {
        public String authorId, authorName, authorPhoto;
        public List<Models.Story> stories = new ArrayList<>();
        public boolean unseen;
    }

    public Listener listener;
    private final List<Group> groups = new ArrayList<>();

    public void setData(List<Group> g) {
        groups.clear();
        if (g != null) groups.addAll(g);
        notifyDataSetChanged();
    }

    @Override public int getItemCount() { return groups.size() + 1; }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        Context c = parent.getContext();
        LinearLayout wrap = Ui.col(c);
        int w = Ui.dp(c, 92);
        wrap.setLayoutParams(new RecyclerView.LayoutParams(w, Ui.dp(c, 144)));
        Ui.pad(wrap, 4, 4, 4, 4);

        FrameLayout frame = new FrameLayout(c);
        ImageView img = new ImageView(c);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(img, new FrameLayout.LayoutParams(-1, -1));

        TextView textPrev = Ui.tvBold(c, "", 10, Color.WHITE);
        textPrev.setGravity(Gravity.CENTER);
        textPrev.setMaxLines(4);
        Ui.pad(textPrev, 6, 26, 6, 6);
        textPrev.setVisibility(View.GONE);
        frame.addView(textPrev, new FrameLayout.LayoutParams(-1, -1));

        ImageView av = new ImageView(c);
        int s = Ui.dp(c, 30);
        FrameLayout.LayoutParams avp = new FrameLayout.LayoutParams(s, s, Gravity.TOP | Gravity.START);
        avp.setMargins(Ui.dp(c, 6), Ui.dp(c, 6), 0, 0);
        frame.addView(av, avp);

        TextView plus = Ui.tvBold(c, "+", 20, Color.WHITE);
        plus.setGravity(Gravity.CENTER);
        plus.setBackground(Ui.circle(Ui.BLUE));
        int ps = Ui.dp(c, 30);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(ps, ps, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        pp.setMargins(0, 0, 0, Ui.dp(c, 8));
        frame.addView(plus, pp);
        wrap.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView name = Ui.tv(c, "", 11, Ui.txt(c));
        name.setMaxLines(1);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, Ui.dp(c, 4), 0, 0);
        wrap.addView(name, new LinearLayout.LayoutParams(-1, -2));
        return new VH(wrap, frame, img, textPrev, av, plus, name);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Context c = h.itemView.getContext();
        if (position == 0) {
            Ui.img(c, h.img, Fb.myPhoto());
            h.img.setVisibility(View.VISIBLE);
            h.textPrev.setVisibility(View.GONE);
            h.plus.setVisibility(View.VISIBLE);
            h.av.setVisibility(View.GONE);
            h.name.setText("Create story");
            h.name.setTextColor(Ui.txt(c));
            h.frame.setBackground(Ui.round(c, Ui.card(c), 12));
            h.itemView.setAlpha(1f);
            h.itemView.setOnClickListener(v -> { if (listener != null) listener.onCreate(); });
            return;
        }
        Group g = groups.get(position - 1);
        Models.Story latest = g.stories.get(g.stories.size() - 1);
        if ("text".equals(latest.mediaType) || latest.mediaUrl == null || latest.mediaUrl.isEmpty()) {
            h.img.setVisibility(View.GONE);
            h.textPrev.setVisibility(View.VISIBLE);
            h.textPrev.setText(latest.caption == null ? "Aa" : latest.caption);
            int bg = latest.bgColor != 0 ? latest.bgColor : Ui.BLUE;
            h.textPrev.setBackground(Ui.round(c, bg, 10));
        } else if ("image".equals(latest.mediaType)) {
            h.textPrev.setVisibility(View.GONE);
            h.img.setVisibility(View.VISIBLE);
            Ui.img(c, h.img, latest.mediaUrl);
        } else {
            h.textPrev.setVisibility(View.GONE);
            h.img.setVisibility(View.VISIBLE);
            h.img.setImageDrawable(Ui.round(c, 0xFF222222, 10));
        }
        Ui.avatar(c, h.av, g.authorPhoto);
        h.av.setVisibility(View.VISIBLE);
        h.plus.setVisibility(View.GONE);
        h.name.setText(g.authorName != null ? g.authorName.split(" ")[0] : "Story");
        int ring = g.unseen ? Ui.BLUE : Ui.div(c);
        h.frame.setBackground(Ui.roundStroke(c, Ui.card(c), 12, ring, 2.5f));
        h.itemView.setAlpha(g.unseen ? 1f : 0.78f);
        h.itemView.setOnClickListener(v -> { if (listener != null) listener.onGroup(g); });
    }

    static class VH extends RecyclerView.ViewHolder {
        FrameLayout frame;
        ImageView img, av;
        TextView textPrev, plus, name;
        VH(View v, FrameLayout f, ImageView i, TextView tp, ImageView a, TextView p, TextView n) {
            super(v);
            frame = f; img = i; textPrev = tp; av = a; plus = p; name = n;
        }
    }
}
