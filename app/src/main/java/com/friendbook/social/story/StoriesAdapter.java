package com.friendbook.social.story;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
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
import com.friendbook.social.core.Icons;
import com.friendbook.social.core.Ui;
import com.friendbook.social.friends.SearchActivity;
import com.friendbook.social.models.Models;

import java.util.ArrayList;
import java.util.List;

/**
 * Authentic friendbook v525 Tall Vertical Story Cards Rail:
 * - Card 0: Split "Add to story" card (User photo top + blue '+' circle + white bottom label)
 * - Cards 1..N: Full-bleed vertical story cards with top-left blue count badge ("2") or ring avatar + bottom name
 * - Final Card: Vibrant Blue "Find friends" discovery card!
 */
public class StoriesAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_CREATE = 0;
    private static final int TYPE_STORY = 1;
    private static final int TYPE_FIND_FRIENDS = 2;

    public interface Listener {
        void onGroup(Group g);
        void onCreate();
        default void onFindFriends() { }
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

    @Override
    public int getItemCount() {
        return groups.size() + 2; // 0 = Add to story, 1..N = Stories, last = Find friends
    }

    @Override
    public int getItemViewType(int position) {
        if (position == 0) return TYPE_CREATE;
        if (position == groups.size() + 1) return TYPE_FIND_FRIENDS;
        return TYPE_STORY;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        Context c = parent.getContext();
        int cardW = Ui.dp(c, 108);
        int cardH = Ui.dp(c, 190);

        if (viewType == TYPE_CREATE) {
            FrameLayout outer = new FrameLayout(c);
            RecyclerView.LayoutParams rlp = new RecyclerView.LayoutParams(cardW, cardH);
            rlp.setMargins(Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 4));
            outer.setLayoutParams(rlp);
            outer.setBackground(Ui.roundStroke(c, Ui.card(c), 14, Ui.div(c), 1));
            outer.setClipToOutline(true);

            LinearLayout split = Ui.col(c);
            ImageView topPhoto = new ImageView(c);
            topPhoto.setScaleType(ImageView.ScaleType.CENTER_CROP);
            topPhoto.setBackgroundColor(0xFFD8E6F8);
            split.addView(topPhoto, new LinearLayout.LayoutParams(-1, Ui.dp(c, 122)));

            LinearLayout bottomBox = Ui.col(c);
            bottomBox.setBackgroundColor(Ui.card(c));
            bottomBox.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            Ui.pad(bottomBox, 6, 18, 6, 10);
            TextView lbl = Ui.tvBold(c, "Add to story", 12.5f, Ui.txt(c));
            lbl.setGravity(Gravity.CENTER);
            bottomBox.addView(lbl);
            split.addView(bottomBox, new LinearLayout.LayoutParams(-1, 0, 1));
            outer.addView(split, new FrameLayout.LayoutParams(-1, -1));

            // Overlapping blue '+' circle with crisp white border right on the seam
            TextView plus = Ui.tvBold(c, "＋", 20, Color.WHITE);
            plus.setGravity(Gravity.CENTER);
            plus.setBackground(Ui.roundStroke(c, Ui.BLUE, 19, Ui.card(c), 3));
            int ps = Ui.dp(c, 38);
            FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(ps, ps, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            pp.topMargin = Ui.dp(c, 103);
            outer.addView(plus, pp);

            return new CreateVH(outer, topPhoto);
        }

        if (viewType == TYPE_FIND_FRIENDS) {
            FrameLayout card = new FrameLayout(c);
            RecyclerView.LayoutParams rlp = new RecyclerView.LayoutParams(cardW, cardH);
            rlp.setMargins(Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 6), Ui.dp(c, 4));
            card.setLayoutParams(rlp);
            card.setBackground(Ui.gradient(0xFF1877F2, 0xFF0B57D0, Ui.dp(c, 14)));
            card.setClipToOutline(true);

            // Center overlapping friend bubbles
            LinearLayout center = Ui.col(c);
            center.setGravity(Gravity.CENTER);
            FrameLayout iconCircle = new FrameLayout(c);
            iconCircle.setBackground(Ui.roundStroke(c, 0x33FFFFFF, 28, Color.WHITE, 2));
            ImageView ic = Icons.icon(c, Icons.FRIENDS, 28, Color.WHITE);
            FrameLayout.LayoutParams icLp = new FrameLayout.LayoutParams(Ui.dp(c, 28), Ui.dp(c, 28), Gravity.CENTER);
            iconCircle.addView(ic, icLp);
            center.addView(iconCircle, new LinearLayout.LayoutParams(Ui.dp(c, 56), Ui.dp(c, 56)));
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
            clp.bottomMargin = Ui.dp(c, 12);
            card.addView(center, clp);

            TextView title = Ui.tvBold(c, "Find friends", 13.5f, Color.WHITE);
            title.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
            tlp.bottomMargin = Ui.dp(c, 12);
            card.addView(title, tlp);

            return new FindVH(card);
        }

        // TYPE_STORY
        FrameLayout frame = new FrameLayout(c);
        RecyclerView.LayoutParams rlp = new RecyclerView.LayoutParams(cardW, cardH);
        rlp.setMargins(Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 4));
        frame.setLayoutParams(rlp);
        frame.setClipToOutline(true);

        ImageView img = new ImageView(c);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(img, new FrameLayout.LayoutParams(-1, -1));

        TextView textPrev = Ui.tvBold(c, "", 12, Color.WHITE);
        textPrev.setGravity(Gravity.CENTER);
        textPrev.setMaxLines(5);
        Ui.pad(textPrev, 8, 36, 8, 30);
        textPrev.setVisibility(View.GONE);
        frame.addView(textPrev, new FrameLayout.LayoutParams(-1, -1));

        // Bottom dark scrim so white author name is always crisp
        View scrim = new View(c);
        GradientDrawable grad = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0xAA000000});
        scrim.setBackground(grad);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-1, Ui.dp(c, 72), Gravity.BOTTOM);
        frame.addView(scrim, slp);

        // Top-left avatar ring (when 1 story)
        FrameLayout avRing = new FrameLayout(c);
        int rs = Ui.dp(c, 36);
        FrameLayout.LayoutParams avp = new FrameLayout.LayoutParams(rs, rs, Gravity.TOP | Gravity.START);
        avp.setMargins(Ui.dp(c, 8), Ui.dp(c, 8), 0, 0);
        ImageView av = new ImageView(c);
        int as = Ui.dp(c, 30);
        avRing.addView(av, new FrameLayout.LayoutParams(as, as, Gravity.CENTER));
        frame.addView(avRing, avp);

        // Top-left blue count badge (e.g. "2" when multiple stories — exact friendbook look!)
        TextView countBadge = Ui.tvBold(c, "", 12, Color.WHITE);
        countBadge.setGravity(Gravity.CENTER);
        countBadge.setBackground(Ui.round(c, Ui.BLUE, 7));
        int bs = Ui.dp(c, 25);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(bs, bs, Gravity.TOP | Gravity.START);
        blp.setMargins(Ui.dp(c, 8), Ui.dp(c, 8), 0, 0);
        frame.addView(countBadge, blp);

        // Bottom author name
        TextView name = Ui.tvBold(c, "", 12.5f, Color.WHITE);
        name.setMaxLines(2);
        name.setShadowLayer(4f, 0f, 1f, 0xCC000000);
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.START);
        nlp.setMargins(Ui.dp(c, 8), 0, Ui.dp(c, 8), Ui.dp(c, 8));
        frame.addView(name, nlp);

        return new StoryVH(frame, img, textPrev, avRing, av, countBadge, name);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Context c = holder.itemView.getContext();
        if (holder instanceof CreateVH) {
            CreateVH ch = (CreateVH) holder;
            String myPhoto = Fb.myPhoto();
            if (myPhoto != null && !myPhoto.isEmpty()) {
                Ui.img(c, ch.topPhoto, myPhoto);
            } else {
                Ui.avatar(c, ch.topPhoto, null);
            }
            ch.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onCreate();
            });
            return;
        }

        if (holder instanceof FindVH) {
            holder.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onFindFriends();
                else c.startActivity(new Intent(c, SearchActivity.class));
            });
            return;
        }

        StoryVH h = (StoryVH) holder;
        Group g = groups.get(position - 1);
        Models.Story latest = g.stories.get(g.stories.size() - 1);
        if ("text".equals(latest.mediaType) || latest.mediaUrl == null || latest.mediaUrl.isEmpty()) {
            h.img.setVisibility(View.GONE);
            h.textPrev.setVisibility(View.VISIBLE);
            h.textPrev.setText(latest.caption == null || latest.caption.isEmpty() ? "Aa" : latest.caption);
            int bg = latest.bgColor != 0 ? latest.bgColor : Ui.BLUE;
            h.textPrev.setBackground(Ui.round(c, bg, 14));
        } else {
            h.textPrev.setVisibility(View.GONE);
            h.img.setVisibility(View.VISIBLE);
            Ui.img(c, h.img, latest.mediaUrl);
        }

        int count = g.stories.size();
        if (count > 1) {
            h.countBadge.setVisibility(View.VISIBLE);
            h.countBadge.setText(String.valueOf(count));
            h.avRing.setVisibility(View.GONE);
        } else {
            h.countBadge.setVisibility(View.GONE);
            h.avRing.setVisibility(View.VISIBLE);
            h.avRing.setBackground(Ui.roundStroke(c, Color.TRANSPARENT, 18,
                    g.unseen ? Ui.BLUE : 0xCCFFFFFF, 2.5f));
            Ui.avatar(c, h.av, g.authorPhoto);
        }

        h.name.setText(g.authorName != null && !g.authorName.isEmpty() ? g.authorName : "Story");
        h.frame.setBackground(Ui.roundStroke(c, Ui.card(c), 14, g.unseen ? Ui.BLUE : Ui.div(c), g.unseen ? 1.5f : 1f));
        h.itemView.setAlpha(g.unseen ? 1f : 0.88f);
        h.itemView.setOnClickListener(v -> { if (listener != null) listener.onGroup(g); });
    }

    static class CreateVH extends RecyclerView.ViewHolder {
        final ImageView topPhoto;
        CreateVH(View v, ImageView tp) { super(v); topPhoto = tp; }
    }

    static class FindVH extends RecyclerView.ViewHolder {
        FindVH(View v) { super(v); }
    }

    static class StoryVH extends RecyclerView.ViewHolder {
        final FrameLayout frame, avRing;
        final ImageView img, av;
        final TextView textPrev, countBadge, name;
        StoryVH(FrameLayout f, ImageView i, TextView tp, FrameLayout ar, ImageView a, TextView cb, TextView n) {
            super(f);
            frame = f; img = i; textPrev = tp; avRing = ar; av = a; countBadge = cb; name = n;
        }
    }
}
