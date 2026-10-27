package com.friendbook.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.friendbook.chat.ChatActivity;
import com.friendbook.core.Cloudinary;
import com.friendbook.core.Fb;
import com.friendbook.core.Format;
import com.friendbook.core.Ui;
import com.friendbook.models.Models;
import com.friendbook.post.FullscreenMediaActivity;
import com.friendbook.post.PostDetailActivity;
import com.friendbook.profile.ProfileActivity;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.firestore.WriteBatch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 🛒 MarketplaceActivity — Full Facebook-Lite Marketplace:
 * 2-Column Product Grid, Search, Category Chips, ＋ Sell Item Uploader,
 * Mark as Sold, Save Listing & 1-Tap "Is this still available?" Message Seller.
 */
public class MarketplaceActivity extends Activity {

    private static final int PICK_ITEM_PHOTO = 501;
    private static final String[] CATS = {
            "All", "My Listings", "Electronics", "Vehicles", "Property", "Fashion", "Home", "Hobbies"
    };

    private final List<Models.Post> allItems = new ArrayList<>();
    private final List<Models.Post> shown = new ArrayList<>();
    private final List<TextView> catChips = new ArrayList<>();
    private String selectedCat = "All";
    private String searchQuery = "";
    private Adapter adapter;
    private ListenerRegistration reg;
    private TextView empty;
    private Uri pendingPhotoUri;
    private ImageView pendingPhotoPreview;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));

        LinearLayout bar = Ui.pageBar(this, "Marketplace");
        TextView sellBtn = Ui.roundChip(this, "＋ Sell", Color.WHITE, Ui.BLUE, 13);
        Ui.pad(sellBtn, 12, 6, 12, 6);
        sellBtn.setOnClickListener(v -> showSellDialog());
        bar.addView(sellBtn);
        root.addView(bar);

        // Search + Categories header
        LinearLayout top = Ui.col(this);
        top.setBackgroundColor(Ui.card(this));
        Ui.pad(top, 10, 8, 10, 8);
        EditText search = Ui.input(this, "🔎 Search Marketplace items, price or city…");
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b2, int c) {
                searchQuery = s.toString().trim().toLowerCase();
                applyFilter();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        top.addView(search, new LinearLayout.LayoutParams(-1, Ui.dp(this, 40)));

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setPadding(0, Ui.dp(this, 8), 0, 0);
        LinearLayout crow = Ui.row(this);
        for (int i = 0; i < CATS.length; i++) {
            final String c = CATS[i];
            TextView chip = Ui.tvBold(this, c, 12, Ui.sub(this));
            Ui.pad(chip, 12, 6, 12, 6);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) lp.leftMargin = Ui.dp(this, 6);
            crow.addView(chip, lp);
            catChips.add(chip);
            chip.setOnClickListener(v -> {
                selectedCat = c;
                refreshCatChips();
                applyFilter();
            });
        }
        hsv.addView(crow);
        top.addView(hsv);
        root.addView(top);
        root.addView(Ui.divider(this));
        refreshCatChips();

        empty = Ui.tv(this, "No listings match yet.\nTap ＋ Sell in the top-right to list an item!", 14, Ui.sub(this));
        empty.setGravity(Gravity.CENTER);
        Ui.pad(empty, 24, 54, 24, 0);
        root.addView(empty);

        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new GridLayoutManager(this, 2));
        rv.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 14));
        rv.setClipToPadding(false);
        adapter = new Adapter();
        rv.setAdapter(adapter);
        root.addView(rv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private void refreshCatChips() {
        for (int i = 0; i < CATS.length; i++) {
            boolean on = CATS[i].equals(selectedCat);
            TextView c = catChips.get(i);
            c.setBackground(Ui.round(this, on ? Ui.BLUE : Ui.inputBg(this), 16));
            c.setTextColor(on ? Color.WHITE : Ui.sub(this));
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        reg = Fb.db().collection("posts")
                .orderBy("createdAt", Query.Direction.DESCENDING).limit(200)
                .addSnapshotListener((snap, err) -> {
                    allItems.clear();
                    if (snap != null) {
                        for (DocumentSnapshot d : snap) {
                            Models.Post p = Models.Post.fromDoc(d);
                            if (p.trashedAt > 0 || p.archived) continue;
                            boolean legacySale = p.content != null &&
                                    (p.content.toLowerCase().contains("#forsale")
                                            || p.content.toLowerCase().contains("#sell"));
                            if (p.isMarketplace() || legacySale) {
                                allItems.add(p);
                            }
                        }
                    }
                    applyFilter();
                });
    }

    private void applyFilter() {
        shown.clear();
        for (Models.Post p : allItems) {
            if ("My Listings".equals(selectedCat) && !p.authorId.equals(Fb.uid())) continue;
            if (!"All".equals(selectedCat) && !"My Listings".equals(selectedCat)) {
                if (p.marketCategory == null || !p.marketCategory.equalsIgnoreCase(selectedCat)) continue;
            }
            if (!searchQuery.isEmpty()) {
                String hay = ((p.marketTitle == null ? "" : p.marketTitle) + " "
                        + (p.marketPrice == null ? "" : p.marketPrice) + " "
                        + (p.location == null ? "" : p.location) + " "
                        + (p.content == null ? "" : p.content)).toLowerCase();
                if (!hay.contains(searchQuery)) continue;
            }
            shown.add(p);
        }
        adapter.notifyDataSetChanged();
        empty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (reg != null) reg.remove();
    }

    private void showSellDialog() {
        pendingPhotoUri = null;
        ScrollView sv = new ScrollView(this);
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 18, 10, 18, 6);

        pendingPhotoPreview = new ImageView(this);
        pendingPhotoPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        pendingPhotoPreview.setBackground(Ui.round(this, Ui.inputBg(this), 10));
        pendingPhotoPreview.setVisibility(View.GONE);
        box.addView(pendingPhotoPreview, new LinearLayout.LayoutParams(-1, Ui.dp(this, 150)));

        TextView pickPhoto = Ui.btnOutline(this, "📷 Choose item photo", Ui.BLUE);
        pickPhoto.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 42));
        pp.topMargin = Ui.dp(this, 6);
        pickPhoto.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            startActivityForResult(i, PICK_ITEM_PHOTO);
        });
        box.addView(pickPhoto, pp);

        EditText title = Ui.input(this, "What are you selling? (e.g. iPhone 14 Pro)");
        EditText price = Ui.input(this, "Price (e.g. Rs 85,000 or $299)");
        EditText loc = Ui.input(this, "City / Location (e.g. Lahore / Frankfurt)");
        EditText desc = Ui.input(this, "Condition & description…");
        desc.setMinLines(3);

        final String[] catHolder = {"Electronics"};
        TextView catBtn = Ui.btnGrey(this, "Category: Electronics ▾");
        catBtn.setOnClickListener(v -> {
            String[] pickCats = {"Electronics", "Vehicles", "Property", "Fashion", "Home", "Hobbies"};
            Ui.choose(this, "Select category", pickCats, w -> {
                catHolder[0] = pickCats[w];
                catBtn.setText("Category: " + pickCats[w] + " ▾");
            });
        });

        for (View v : new View[]{title, price, catBtn, loc, desc}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = Ui.dp(this, 8);
            box.addView(v, lp);
        }
        sv.addView(box);

        new AlertDialog.Builder(this)
                .setTitle("🛒 Create Marketplace Listing")
                .setView(sv)
                .setPositiveButton("Publish Listing", (dlg, w) -> {
                    String t = title.getText().toString().trim();
                    String pr = price.getText().toString().trim();
                    if (t.isEmpty() || pr.isEmpty()) {
                        Ui.toast(this, "Enter item title and price");
                        return;
                    }
                    publishListing(t, pr, catHolder[0],
                            loc.getText().toString().trim(),
                            desc.getText().toString().trim());
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == PICK_ITEM_PHOTO && res == RESULT_OK && data != null && data.getData() != null) {
            pendingPhotoUri = data.getData();
            if (pendingPhotoPreview != null) {
                pendingPhotoPreview.setVisibility(View.VISIBLE);
                pendingPhotoPreview.setImageURI(pendingPhotoUri);
            }
        }
    }

    private void publishListing(String title, String price, String cat, String loc, String desc) {
        Ui.toast(this, "Publishing listing…");
        if (pendingPhotoUri != null) {
            String path = "posts/" + Fb.uid() + "/market_" + System.currentTimeMillis() + ".jpg";
            Cloudinary.uploadOrFirebase(this, path, pendingPhotoUri)
                    .addOnSuccessListener(u -> saveListingDoc(title, price, cat, loc, desc, u.toString()))
                    .addOnFailureListener(e -> Ui.toast(this, "Photo upload failed: " + e.getMessage()));
        } else {
            saveListingDoc(title, price, cat, loc, desc, "");
        }
    }

    private void saveListingDoc(String title, String price, String cat, String loc, String desc, String photoUrl) {
        Map<String, Object> m = new HashMap<>();
        m.put("authorId", Fb.uid());
        m.put("authorName", Fb.myName());
        m.put("authorPhotoUrl", Fb.myPhoto());
        m.put("authorVerified", Fb.me != null && Fb.me.isVerified);
        m.put("authorTickLevel", Fb.me != null ? Fb.me.tickLevel : "none");
        m.put("postType", "marketplace");
        m.put("marketTitle", title);
        m.put("marketPrice", price);
        m.put("marketCategory", cat);
        m.put("marketSold", false);
        m.put("location", loc);
        m.put("content", desc.isEmpty() ? ("Selling " + title + " — " + price + " #forsale") : desc);
        m.put("mediaUrl", photoUrl);
        m.put("mediaType", photoUrl.isEmpty() ? "none" : "image");
        m.put("privacy", "Public");
        m.put("likes", 0);
        m.put("comments", 0);
        m.put("shares", 0);
        m.put("savedBy", new ArrayList<String>());
        m.put("createdAt", FieldValue.serverTimestamp());
        Fb.db().collection("posts").add(m)
                .addOnSuccessListener(x -> Ui.toast(this, "Listed on Marketplace ✓"))
                .addOnFailureListener(e -> Ui.toast(this, e.getMessage()));
    }

    private void openItemSheet(Models.Post p) {
        ScrollView sv = new ScrollView(this);
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 16, 12, 16, 10);

        if (p.mediaUrl != null && !p.mediaUrl.isEmpty()) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Ui.img(this, iv, p.mediaUrl);
            iv.setOnClickListener(v -> {
                Intent i = new Intent(this, FullscreenMediaActivity.class);
                i.putExtra("url", p.mediaUrl);
                i.putExtra("type", "image");
                startActivity(i);
            });
            box.addView(iv, new LinearLayout.LayoutParams(-1, Ui.dp(this, 210)));
        }

        String priceTxt = (p.marketSold ? "🔴 SOLD · " : "") +
                (p.marketPrice == null || p.marketPrice.isEmpty() ? "Contact for price" : p.marketPrice);
        TextView pr = Ui.tvBold(this, priceTxt, 20, p.marketSold ? Ui.RED : 0xFF00A400);
        pr.setPadding(0, Ui.dp(this, 10), 0, 0);
        box.addView(pr);

        String itemTitle = p.marketTitle == null || p.marketTitle.isEmpty() ? p.content : p.marketTitle;
        box.addView(Ui.tvBold(this, itemTitle, 17, Ui.txt(this)));

        String meta = (p.marketCategory == null || p.marketCategory.isEmpty() ? "Item" : p.marketCategory)
                + (p.location == null || p.location.isEmpty() ? "" : " · 📍 " + p.location)
                + " · Listed " + Format.ago(p.createdAt);
        box.addView(Ui.tv(this, meta, 12, Ui.sub(this)));

        if (p.content != null && !p.content.isEmpty()) {
            TextView d = Ui.tv(this, p.content, 14, Ui.txt(this));
            d.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
            box.addView(d);
        }

        // Seller row
        LinearLayout srow = Ui.row(this);
        srow.setBackground(Ui.round(this, Ui.inputBg(this), 10));
        Ui.pad(srow, 10, 8, 10, 8);
        ImageView av = new ImageView(this);
        Ui.avatar(this, av, p.authorPhoto);
        srow.addView(av, new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));
        TextView sn = Ui.tvBold(this, "Seller: " + p.authorName, 14, Ui.txt(this));
        LinearLayout.LayoutParams snp = new LinearLayout.LayoutParams(0, -2, 1);
        snp.leftMargin = Ui.dp(this, 8);
        srow.addView(sn, snp);
        srow.setOnClickListener(v -> {
            Intent i = new Intent(this, ProfileActivity.class);
            i.putExtra("uid", p.authorId);
            startActivity(i);
        });
        box.addView(srow);
        sv.addView(box);

        AlertDialog.Builder ab = new AlertDialog.Builder(this).setView(sv);
        if (p.authorId.equals(Fb.uid())) {
            ab.setPositiveButton(p.marketSold ? "Mark Available" : "✓ Mark as Sold", (dlg, w) -> {
                Fb.db().collection("posts").document(p.id).update("marketSold", !p.marketSold);
                Ui.toast(this, !p.marketSold ? "Marked as SOLD" : "Marked available again");
            });
            ab.setNeutralButton("🗑 Delete", (dlg, w) ->
                    Fb.db().collection("posts").document(p.id).delete());
        } else {
            ab.setPositiveButton("💬 Ask: Still available?", (dlg, w) -> messageSeller(p));
            boolean saved = Fb.uid() != null && p.savedBy.contains(Fb.uid());
            ab.setNeutralButton(saved ? "Saved ✓" : "🔖 Save item", (dlg, w) -> {
                if (Fb.uid() == null) return;
                Fb.db().collection("posts").document(p.id).update("savedBy",
                        saved ? FieldValue.arrayRemove(Fb.uid()) : FieldValue.arrayUnion(Fb.uid()));
                Ui.toast(this, saved ? "Removed from Saved" : "Saved ✓");
            });
        }
        ab.setNegativeButton("Comments", (dlg, w) -> {
            Intent i = new Intent(this, PostDetailActivity.class);
            i.putExtra("post_id", p.id);
            startActivity(i);
        });
        ab.show();
    }

    private void messageSeller(Models.Post p) {
        String me = Fb.uid();
        if (me == null) return;
        String title = p.marketTitle == null || p.marketTitle.isEmpty() ? "your listing" : p.marketTitle;
        String text = "🛒 Hi " + p.authorName + "! Is \"" + title + "\" ("
                + (p.marketPrice == null ? "" : p.marketPrice) + ") still available?";
        String chatId = Fb.chatId(me, p.authorId);
        long now = System.currentTimeMillis();
        Map<String, Object> msg = Fb.map(
                "senderId", me, "receiverId", p.authorId,
                "text", text, "imageUrl", p.mediaUrl, "type", "text",
                "isSeen", false, "isDelivered", false,
                "createdAtMs", now,
                "createdAt", FieldValue.serverTimestamp());
        WriteBatch batch = Fb.db().batch();
        batch.set(Fb.db().collection("chats").document(chatId).collection("messages").document(), msg);
        batch.set(Fb.db().collection("chats").document(chatId), Fb.map(
                "participants", Arrays.asList(me, p.authorId),
                "lastMessage", text,
                "lastMessageTimeMs", now,
                "lastMessageTime", FieldValue.serverTimestamp()), SetOptions.merge());
        batch.commit().addOnSuccessListener(x -> {
            Intent i = new Intent(this, ChatActivity.class);
            i.putExtra("uid", p.authorId);
            i.putExtra("name", p.authorName);
            startActivity(i);
        });
    }

    class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        @Override public int getItemCount() { return shown.size(); }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int vt) {
            LinearLayout card = Ui.col(MarketplaceActivity.this);
            RecyclerView.LayoutParams rp = new RecyclerView.LayoutParams(-1, -2);
            rp.setMargins(Ui.dp(MarketplaceActivity.this, 5), Ui.dp(MarketplaceActivity.this, 5),
                    Ui.dp(MarketplaceActivity.this, 5), Ui.dp(MarketplaceActivity.this, 5));
            card.setLayoutParams(rp);
            card.setBackground(Ui.round(MarketplaceActivity.this, Ui.card(MarketplaceActivity.this), 10));

            ImageView iv = new ImageView(MarketplaceActivity.this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(Ui.inputBg(MarketplaceActivity.this));
            card.addView(iv, new LinearLayout.LayoutParams(-1, Ui.dp(MarketplaceActivity.this, 148)));

            LinearLayout info = Ui.col(MarketplaceActivity.this);
            Ui.pad(info, 10, 8, 10, 10);
            TextView price = Ui.tvBold(MarketplaceActivity.this, "", 15, Ui.txt(MarketplaceActivity.this));
            price.setSingleLine(true);
            TextView title = Ui.tv(MarketplaceActivity.this, "", 13, Ui.txt(MarketplaceActivity.this));
            title.setSingleLine(true);
            TextView loc = Ui.tv(MarketplaceActivity.this, "", 11, Ui.sub(MarketplaceActivity.this));
            loc.setSingleLine(true);
            info.addView(price);
            info.addView(title);
            info.addView(loc);
            card.addView(info);
            return new VH(card, iv, price, title, loc);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            Models.Post p = shown.get(pos);
            if (p.mediaUrl != null && !p.mediaUrl.isEmpty()) {
                Ui.img(MarketplaceActivity.this, h.iv, p.mediaUrl);
            } else {
                h.iv.setImageDrawable(Ui.round(MarketplaceActivity.this, Ui.inputBg(MarketplaceActivity.this), 8));
            }
            String pr = p.marketPrice == null || p.marketPrice.isEmpty() ? "For Sale" : p.marketPrice;
            h.price.setText(p.marketSold ? ("SOLD · " + pr) : pr);
            h.price.setTextColor(p.marketSold ? Ui.RED : Ui.txt(MarketplaceActivity.this));
            String tt = p.marketTitle == null || p.marketTitle.isEmpty() ? p.content : p.marketTitle;
            h.title.setText(tt == null ? "Listing" : tt);
            h.loc.setText(p.location == null || p.location.isEmpty()
                    ? ("by " + p.authorName) : ("📍 " + p.location));
            h.itemView.setOnClickListener(v -> openItemSheet(p));
        }

        class VH extends RecyclerView.ViewHolder {
            ImageView iv;
            TextView price, title, loc;
            VH(View v, ImageView i, TextView pr, TextView t, TextView l) {
                super(v);
                iv = i; price = pr; title = t; loc = l;
            }
        }
    }
}
