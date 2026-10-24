package com.example.frienbook.story;

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

import com.example.frienbook.core.Fb;
import com.example.frienbook.core.Ui;
import com.example.frienbook.models.Models;

import java.util.ArrayList;
import java.util.List;

/** Horizontal stories rail: "Your story" + one bubble per author with a seen ring. */
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
        wrap.setLayoutParams(new RecyclerView.LayoutParams(w, Ui.dp(c, 140)));
        Ui.pad(wrap, 4, 4, 4, 4);

        FrameLayout frame = new FrameLayout(c);
        ImageView img = new ImageView(c);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(img, new FrameLayout.LayoutParams(-1, -1));
        // small avatar top-left
        ImageView av = new ImageView(c);
        int s = Ui.dp(c, 30);
        FrameLayout.LayoutParams avp = new FrameLayout.LayoutParams(s, s, Gravity.TOP | Gravity.START);
        avp.setMargins(Ui.dp(c, 6), Ui.dp(c, 6), 0, 0);
        frame.addView(av, avp);
        // plus badge for create card
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
        return new VH(wrap, frame, img, av, plus, name);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Context c = h.itemView.getContext();
        if (position == 0) {
            // create story card
            Ui.img(c, h.img, Fb.myPhoto());
            h.plus.setVisibility(View.VISIBLE);
            h.av.setVisibility(View.GONE);
            h.name.setText("Create story");
            h.name.setTextColor(Ui.txt(c));
            h.frame.setBackground(Ui.round(c, Ui.card(c), 12));
            h.itemView.setOnClickListener(v -> { if (listener != null) listener.onCreate(); });
            return;
        }
        Group g = groups.get(position - 1);
        Models.Story latest = g.stories.get(g.stories.size() - 1);
        if ("image".equals(latest.mediaType) || latest.mediaUrl == null) Ui.img(c, h.img, latest.mediaUrl);
        else h.img.setImageDrawable(Ui.round(c, 0xFF333333, 0));
        Ui.avatar(c, h.av, g.authorPhoto);
        h.av.setVisibility(View.VISIBLE);
        h.plus.setVisibility(View.GONE);
        h.name.setText(g.authorName.split(" ")[0]);
        // unseen ring = blue, seen ring = grey
        int ring = g.unseen ? Ui.BLUE : Ui.div(c);
        h.frame.setBackground(Ui.roundStroke(c, Ui.card(c), 12, ring, 2.5f));
        h.itemView.setAlpha(g.unseen ? 1f : 0.75f);
        h.itemView.setOnClickListener(v -> { if (listener != null) listener.onGroup(g); });
    }

    static class VH extends RecyclerView.ViewHolder {
        FrameLayout frame;
        ImageView img, av;
        TextView plus, name;
        VH(View v, FrameLayout f, ImageView i, ImageView a, TextView p, TextView n) {
            super(v);
            frame = f; img = i; av = a; plus = p; name = n;
        }
    }
}
