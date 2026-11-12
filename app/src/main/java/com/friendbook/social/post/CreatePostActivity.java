package com.friendbook.social.post;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.core.content.FileProvider;

import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Format;
import com.friendbook.social.core.Prefs;
import com.friendbook.social.core.Ui;
import com.google.firebase.firestore.FieldValue;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Create / edit post: text, photos (multi), video, feeling, background, poll, check-in, audience. */
public class CreatePostActivity extends Activity {

    private static final int PICK_IMAGE = 11, PICK_VIDEO = 12, CAPTURE_IMAGE = 13, PICK_PHOTO_OR_VIDEO = 14;
    private boolean openedInQuickMediaMode = false;

    /** friendbook-style background colors for text posts. */
    private static final int[] BG_COLORS = {
            0xFFF02849, // red
            0xFF9360F7, // purple
            0xFF45BD62, // green
            0xFFF7B928, // yellow
            0xFFE66A23, // orange
            0xFF1877F2, // blue
            0xFF2ABBA7, // teal
            0xFF222222, // dark
            0xFFFF66C4, // pink
            0xFF5D4067  // plum
    };

    private EditText text;
    private java.util.List<java.util.Map<String,Object>> submittingMentions = new java.util.ArrayList<>();
    private TextView privacyBtn, feelingBtn, postBtn, mediaLabel, openVideoEditorBtn;
    private ImageView preview;
    private VideoView videoPreview;
    private LinearLayout previewWrap, multiThumbs;
    private final List<Uri> mediaUris = new ArrayList<>();
    private String mediaType = "none"; // none | image | images | video
    private String privacy;
    private String feeling = "";
    private String location = "";
    private final java.util.List<String> taggedUids = new ArrayList<>();
    private final java.util.List<String> taggedNames = new ArrayList<>();
    private TextView tagChip;
    private int bgColor = 0;
    private final List<String> pollOptions = new ArrayList<>();
    private String editId = null;
    private String groupId = null;
    private boolean groupNeedsApproval = false;
    private String pageId = null, pageName = null, pagePhoto = null;
    private String postType = "regular"; // regular | life_event | qna | marketplace
    private String lifeEventIcon = "", lifeEventTitle = "";
    private String marketTitle = "", marketPrice = "", marketCategory = "Electronics";
    private TextView specialBadge;
    private Uri cameraFile;
    private boolean busy;

    private static final String[] FEELINGS = {
            "😊 happy", "😍 loved", "😎 cool", "🥳 excited", "😢 sad", "😡 angry",
            "🤒 sick", "😴 tired", "🤔 thoughtful", "🙏 blessed", "🎉 celebrating", "✈️ traveling"};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);
        if (!com.friendbook.social.core.Admin.checkNotSuspended(this)) {
            finish();
            return;
        }
        editId = getIntent().getStringExtra("edit_id");
        groupId = getIntent().getStringExtra("group_id");
        groupNeedsApproval = getIntent().getBooleanExtra("group_needs_approval", false);
        pageId = getIntent().getStringExtra("page_id");
        pageName = getIntent().getStringExtra("page_name");
        pagePhoto = getIntent().getStringExtra("page_photo");
        privacy = Prefs.defaultPrivacy(this);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));

        // bar
        LinearLayout bar = Ui.pageBar(this,
                editId != null ? "Edit post"
                        : pageId != null ? ("Post as " + pageName)
                        : groupId != null ? "New group post" : "Create post");
        if (bar.getChildCount() > 0) {
            bar.getChildAt(0).setOnClickListener(v -> onBackPressed());
        }
        postBtn = Ui.btn(this, "POST");
        postBtn.setMinHeight(Ui.dp(this, 34));
        bar.addView(postBtn);
        root.addView(bar);

        ScrollView sv = new ScrollView(this);
        LinearLayout body = Ui.col(this);
        Ui.pad(body, 12, 12, 12, 12);

        // me row
        LinearLayout meRow = Ui.row(this);
        ImageView av = new ImageView(this);
        int s = Ui.dp(this, 44);
        meRow.addView(av, new LinearLayout.LayoutParams(s, s));
        Ui.avatar(this, av, pagePhoto != null && !pagePhoto.isEmpty() ? pagePhoto : Fb.myPhoto());
        LinearLayout nameCol = Ui.col(this);
        LinearLayout.LayoutParams ncp = new LinearLayout.LayoutParams(0, -2, 1);
        ncp.leftMargin = Ui.dp(this, 10);
        TextView name = Ui.tvBold(this,
                pageName != null && !pageName.isEmpty() ? ("🚩 " + pageName) : Fb.myName(),
                15, Ui.txt(this));
        nameCol.addView(name);
        LinearLayout chips = Ui.row(this);
        chips.setPadding(0, Ui.dp(this, 4), 0, 0);
        privacyBtn = chip(privacyLabel());
        feelingBtn = chip(feeling.isEmpty() ? "＋ Feeling" : feeling);
        privacyBtn.setOnClickListener(v -> pickPrivacy());
        feelingBtn.setOnClickListener(v -> pickFeeling());
        chips.addView(privacyBtn);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-2, -2);
        fp.leftMargin = Ui.dp(this, 6);
        chips.addView(feelingBtn, fp);
        if (location.isEmpty()) {
            TextView locChip = chip("📍 Check in");
            locChip.setOnClickListener(v -> checkInDialog());
            chips.addView(locChip, fp);
        }
        nameCol.addView(chips);
        meRow.addView(nameCol, ncp);
        body.addView(meRow);

        specialBadge = Ui.tvBold(this, "", 13, Ui.BLUE);
        specialBadge.setBackground(Ui.round(this, Ui.inputBg(this), 10));
        Ui.pad(specialBadge, 12, 8, 12, 8);
        specialBadge.setVisibility(View.GONE);
        LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(-1, -2);
        sbp.topMargin = Ui.dp(this, 8);
        specialBadge.setOnClickListener(v -> {
            postType = "regular";
            lifeEventIcon = ""; lifeEventTitle = "";
            marketTitle = ""; marketPrice = "";
            specialBadge.setVisibility(View.GONE);
            Ui.toast(this, "Special card removed");
        });
        body.addView(specialBadge, sbp);

        text = Ui.multi(this, "What's on your mind?", 5);
        com.friendbook.social.core.Mentions.attach(text);
        text.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.topMargin = Ui.dp(this, 10);
        body.addView(text, tp);

        // media preview (single + multi thumbs)
        previewWrap = Ui.col(this);
        previewWrap.setVisibility(View.GONE);
        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        previewWrap.addView(preview, new LinearLayout.LayoutParams(-1, Ui.dp(this, 240)));
        videoPreview = new VideoView(this);
        videoPreview.setVisibility(View.GONE);
        previewWrap.addView(videoPreview, new LinearLayout.LayoutParams(-1, Ui.dp(this, 240)));
        HorizontalScrollView thumbsScroll = new HorizontalScrollView(this);
        multiThumbs = Ui.row(this);
        thumbsScroll.addView(multiThumbs);
        thumbsScroll.setVisibility(View.GONE);
        previewWrap.addView(thumbsScroll);
        mediaLabel = Ui.tv(this, "", 13, Ui.sub(this));
        mediaLabel.setGravity(Gravity.CENTER);
        Ui.pad(mediaLabel, 0, 4, 0, 4);
        previewWrap.addView(mediaLabel);
        openVideoEditorBtn = Ui.roundChip(this, "🎬 Edit Video / Add Text & Overlay", Ui.BLUE, Color.WHITE, 12.5f);
        Ui.pad(openVideoEditorBtn, 14, 7, 14, 7);
        openVideoEditorBtn.setVisibility(View.GONE);
        LinearLayout.LayoutParams oelp = new LinearLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL);
        oelp.bottomMargin = Ui.dp(this, 6);
        openVideoEditorBtn.setOnClickListener(v -> {
            if (!mediaUris.isEmpty()) {
                Intent ri = new Intent(this, com.friendbook.social.reels.AddReelActivity.class);
                ri.setData(mediaUris.get(0));
                ri.putExtra("video_uri", mediaUris.get(0).toString());
                startActivity(ri);
            }
        });
        previewWrap.addView(openVideoEditorBtn, oelp);
        TextView remove = Ui.btnGrey(this, "✕ Remove media");
        remove.setMinHeight(Ui.dp(this, 32));
        remove.setOnClickListener(v -> clearMedia());
        previewWrap.addView(remove, new LinearLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL));
        body.addView(previewWrap);

        // attachment rows
        LinearLayout attach = Ui.row(this);
        attach.setBackgroundColor(Ui.card(this));
        Ui.pad(attach, 8, 10, 8, 10);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, -2);
        ap.topMargin = Ui.dp(this, 14);
        attach.setLayoutParams(ap);
        attach.addView(attachBtn("🖼️ Photo/Video", v -> pickPhotosOrVideo()));
        attach.addView(attachBtn("🎥 Video", v -> pick(PICK_VIDEO, "video/*")));
        attach.addView(attachBtn("📷 Camera", v -> camera()));
        body.addView(attach);

        LinearLayout attach2 = Ui.row(this);
        attach2.setBackgroundColor(Ui.card(this));
        Ui.pad(attach2, 8, 10, 8, 10);
        attach.addView(Ui.divider(this));
        attach2.addView(attachBtn("🎨 Background", v -> pickBackground()));
        attach2.addView(attachBtn("📊 Poll", v -> pollDialog()));
        attach2.addView(attachBtn("👥 Tag friends", v -> tagFriendsDialog()));
        attach2.addView(attachBtn("📍 Check in", v -> checkInDialog()));
        body.addView(attach2);

        LinearLayout attach3 = Ui.row(this);
        attach3.setBackgroundColor(Ui.card(this));
        Ui.pad(attach3, 8, 10, 8, 10);
        attach3.addView(attachBtn("🎉 Life event", v -> lifeEventDialog()));
        attach3.addView(attachBtn("❓ Host Q&A", v -> qnaDialog()));
        attach3.addView(attachBtn("🛒 Sell item", v -> marketplaceDialog()));
        attach3.addView(attachBtn("😊 Feeling", v -> pickFeeling()));
        body.addView(attach3);

        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        if (editId != null) {
            loadForEdit();
        } else {
            String quickMode = getIntent().getStringExtra("quick_mode");
            if ("photo_video".equals(quickMode) || "photo".equals(quickMode)) {
                openedInQuickMediaMode = true;
                pickPhotosOrVideo();
            } else if ("feeling".equals(quickMode)) pickFeeling();
            else if ("checkin".equals(quickMode)) checkInDialog();
            else if ("life_event".equals(quickMode)) lifeEventDialog();
            String draft = Prefs.postDraft(this);
            if (!draft.isEmpty() && groupId == null && pageId == null) {
                text.setText(draft);
                text.setSelection(draft.length());
                Ui.toast(this, "📝 Restored saved draft");
            }
        }
        postBtn.setOnClickListener(v -> submit());
    }

    @Override
    public void onBackPressed() {
        String cur = text != null ? text.getText().toString().trim() : "";
        if (!busy && editId == null && !cur.isEmpty() && groupId == null && pageId == null) {
            Ui.choose(this, "Unfinished post", new String[]{
                    "💾 Save as draft",
                    "🗑 Discard post",
                    "✏️ Keep editing"
            }, w -> {
                if (w == 0) {
                    Prefs.setPostDraft(this, cur);
                    Ui.toast(this, "Saved to drafts 📝");
                    finish();
                } else if (w == 1) {
                    Prefs.setPostDraft(this, "");
                    finish();
                }
            });
            return;
        }
        super.onBackPressed();
    }

    private void lifeEventDialog() {
        String[] presets = {
                "💼 Started a New Job",
                "🎓 Graduated",
                "💍 Got Engaged / Married",
                "🏠 Moved to a New City",
                "🎂 Celebrated a Milestone",
                "🏆 Achieved a Goal"
        };
        String[] icons = {"💼", "🎓", "💍", "🏠", "🎂", "🏆"};
        Ui.choose(this, "🎉 Choose a Life Event", presets, w -> {
            EditText detail = Ui.input(this, "Headline (e.g. " + presets[w].substring(3) + " at TechCorp)");
            detail.setText(presets[w].substring(3));
            LinearLayout box = Ui.col(this);
            Ui.pad(box, 18, 10, 18, 0);
            box.addView(detail, new LinearLayout.LayoutParams(-1, Ui.dp(this, 46)));
            new AlertDialog.Builder(this)
                    .setTitle(presets[w])
                    .setView(box)
                    .setPositiveButton("Attach", (d, x) -> {
                        postType = "life_event";
                        lifeEventIcon = icons[w];
                        lifeEventTitle = detail.getText().toString().trim();
                        specialBadge.setVisibility(View.VISIBLE);
                        specialBadge.setText(lifeEventIcon + " Life Event: " + lifeEventTitle + "  (tap to remove)");
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
    }

    private void qnaDialog() {
        postType = "qna";
        if (text.getText().toString().trim().isEmpty()) {
            text.setText("Ask me anything! Drop your questions in the comments 👇");
        }
        applyBackground(0xFF9360F7);
        specialBadge.setVisibility(View.VISIBLE);
        specialBadge.setText("❓ Hosting Q&A card attached  (tap to remove)");
    }

    private void marketplaceDialog() {
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 18, 10, 18, 0);
        EditText tIn = Ui.input(this, "Item title (e.g. MacBook Air M2)");
        EditText pIn = Ui.input(this, "Price (e.g. Rs 195,000 or $650)");
        EditText cIn = Ui.input(this, "Category (Electronics / Vehicles / Fashion / Home)");
        cIn.setText(marketCategory);
        for (EditText e : new EditText[]{tIn, pIn, cIn}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 44));
            lp.topMargin = Ui.dp(this, 6);
            box.addView(e, lp);
        }
        new AlertDialog.Builder(this)
                .setTitle("🛒 Sell on Marketplace")
                .setView(box)
                .setPositiveButton("Attach listing", (d, w) -> {
                    String tt = tIn.getText().toString().trim();
                    String pr = pIn.getText().toString().trim();
                    if (tt.isEmpty() || pr.isEmpty()) { Ui.toast(this, "Enter item title & price"); return; }
                    postType = "marketplace";
                    marketTitle = tt;
                    marketPrice = pr;
                    marketCategory = cIn.getText().toString().trim().isEmpty() ? "Electronics" : cIn.getText().toString().trim();
                    specialBadge.setVisibility(View.VISIBLE);
                    specialBadge.setText("🛒 Selling: " + marketTitle + " · " + marketPrice + "  (tap to remove)");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private TextView chip(String label) {
        TextView t = Ui.tvBold(this, label, 12, Ui.BLUE);
        t.setBackground(Ui.roundStroke(this, Ui.inputBg(this), 14, Ui.div(this), 1));
        t.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4));
        return t;
    }

    private TextView attachBtn(String label, View.OnClickListener l) {
        TextView t = Ui.tvBold(this, label, 13, Ui.txt(this));
        t.setGravity(Gravity.CENTER);
        t.setOnClickListener(l);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        return t;
    }

    private String privacyLabel() {
        String icon = "🌐";
        if ("Friends".equalsIgnoreCase(privacy)) icon = "👥";
        if ("Only me".equalsIgnoreCase(privacy)) icon = "🔒";
        return icon + " " + privacy + " ▾";
    }

    private void pickPrivacy() {
        String[] opts = {"🌐 Public", "👥 Friends", "🔒 Only me"};
        Ui.choose(this, "Who can see this post?", opts, w -> {
            privacy = w == 1 ? "Friends" : w == 2 ? "Only me" : "Public";
            privacyBtn.setText(privacyLabel());
        });
    }

    private void pickFeeling() {
        Ui.choose(this, "How are you feeling?", FEELINGS, w -> {
            feeling = FEELINGS[w];
            feelingBtn.setText(feeling);
        });
    }

    /** 👥 Tag people — multi-pick from your friends; shows "— with X" on the post. */
    private void tagFriendsDialog() {
        java.util.Map<String,String> initial=new java.util.LinkedHashMap<>();
        for(int i=0;i<taggedUids.size();i++)initial.put(taggedUids.get(i),i<taggedNames.size()?taggedNames.get(i):"User");
        com.friendbook.social.core.FriendPicker.multi(this,"Tag friends",initial,selected->{
            taggedUids.clear();taggedNames.clear();taggedUids.addAll(selected.keySet());taggedNames.addAll(selected.values());
            Ui.toast(this,taggedNames.isEmpty()?"Tags cleared":"Tagged: "+android.text.TextUtils.join(", ",taggedNames));
        });
    }

    private void showTagPicker(java.util.List<String> uids, java.util.List<String> names) {
        boolean[] checked = new boolean[uids.size()];
        for (int i = 0; i < uids.size(); i++) checked[i] = taggedUids.contains(uids.get(i));
        String[] items = names.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("👥 Tag friends")
                .setMultiChoiceItems(items, checked, (d, which, on) -> checked[which] = on)
                .setPositiveButton("Done", (d, w) -> {
                    taggedUids.clear();
                    taggedNames.clear();
                    for (int i = 0; i < uids.size(); i++)
                        if (checked[i]) { taggedUids.add(uids.get(i)); taggedNames.add(names.get(i)); }
                    Ui.toast(this, taggedNames.isEmpty() ? "Tags cleared"
                            : "Tagged: " + android.text.TextUtils.join(", ", taggedNames));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void checkInDialog() {
        EditText in = Ui.input(this, "Where are you?  (e.g. Lahore Fort)");
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 18, 8, 18, 0);
        box.addView(in, new LinearLayout.LayoutParams(-1, Ui.dp(this, 46)));
        new AlertDialog.Builder(this)
                .setTitle("📍 Check in")
                .setView(box)
                .setPositiveButton("Add", (d, w) -> {
                    location = in.getText().toString().trim();
                    if (!location.isEmpty()) Ui.toast(this, "📍 " + location + " added to post");
                })
                .setNeutralButton("Remove", (d, w) -> location = "")
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------- background color ----------
    private void pickBackground() {
        LinearLayout grid = Ui.col(this);
        Ui.pad(grid, 16, 12, 16, 16);
        TextView title = Ui.tvBold(this, "Tap a color for your post background", 14, Ui.txt(this));
        grid.addView(title);
        LinearLayout row = Ui.row(this);
        row.setPadding(0, Ui.dp(this, 10), 0, 0);
        grid.addView(row);
        final View[] swatches = new View[BG_COLORS.length + 1];
        // none option
        swatches[0] = swatch(0, "✕");
        row.addView(swatches[0], swatchLp());
        for (int i = 0; i < BG_COLORS.length; i++) {
            swatches[i + 1] = swatch(BG_COLORS[i], "");
            row.addView(swatches[i + 1], swatchLp());
        }
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("🎨 Post background")
                .setView(grid)
                .setNegativeButton("Close", null)
                .create();
        for (int i = 0; i < swatches.length; i++) {
            final int color = i == 0 ? 0 : BG_COLORS[i - 1];
            swatches[i].setOnClickListener(v -> {
                applyBackground(color);
                dlg.dismiss();
            });
        }
        dlg.show();
    }

    private LinearLayout.LayoutParams swatchLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1);
        lp.leftMargin = Ui.dp(this, 3);
        lp.rightMargin = Ui.dp(this, 3);
        return lp;
    }

    private View swatch(int color, String glyph) {
        TextView t = new TextView(this);
        t.setText(glyph);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(18);
        t.setBackground(color == 0
                ? Ui.roundStroke(this, Ui.inputBg(this), 8, Ui.div(this), 1)
                : Ui.round(this, color, 8));
        return t;
    }

    private void applyBackground(int color) {
        bgColor = color;
        if (color != 0) {
            text.setBackground(Ui.gradient(color, darker(color), Ui.dp(this, 12)));
            text.setTextColor(0xFFFFFFFF);
            text.setHintTextColor(0xCCFFFFFF);
            text.setGravity(Gravity.CENTER_VERTICAL);
            text.setMinLines(4);
            Ui.pad(text, 18, 18, 18, 18);
        } else {
            text.setBackgroundColor(Color.TRANSPARENT);
            text.setTextColor(Ui.txt(this));
            text.setHintTextColor(Ui.sub(this));
            text.setGravity(Gravity.TOP);
            text.setMinLines(5);
            Ui.pad(text, 0, 18, 0, 18);
        }
    }

    private int darker(int color) {
        int a = (color >> 24) & 0xFF;
        int r = (int) (((color >> 16) & 0xFF) * 0.55f);
        int g = (int) (((color >> 8) & 0xFF) * 0.55f);
        int bl = (int) ((color & 0xFF) * 0.55f);
        return (a << 24) | (r << 16) | (g << 8) | bl;
    }

    // ---------- poll ----------
    private void pollDialog() {
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 18, 10, 18, 0);
        final EditText[] inputs = new EditText[4];
        for (int i = 0; i < 4; i++) {
            inputs[i] = Ui.input(this, "Option " + (i + 1) + (i < 2 ? " (required)" : " (optional)"));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 46));
            lp.topMargin = i == 0 ? 0 : Ui.dp(this, 8);
            box.addView(inputs[i], lp);
            if (i < pollOptions.size()) inputs[i].setText(pollOptions.get(i));
        }
        new AlertDialog.Builder(this)
                .setTitle("📊 Create poll")
                .setView(box)
                .setPositiveButton("Attach poll", (d, w) -> {
                    pollOptions.clear();
                    for (EditText e : inputs) {
                        String o = e.getText().toString().trim();
                        if (!o.isEmpty()) pollOptions.add(o);
                    }
                    if (pollOptions.size() < 2) {
                        pollOptions.clear();
                        Ui.toast(this, "A poll needs at least 2 options");
                        return;
                    }
                    Ui.toast(this, "Poll attached: " + pollOptions.size() + " options 📊");
                    text.append(pollOptionsPreview());
                })
                .setNeutralButton("Remove poll", (d, w) -> {
                    pollOptions.clear();
                    Ui.toast(this, "Poll removed");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String pollOptionsPreview() {
        if (pollOptions.isEmpty()) return "";
        String q = text.getText().toString();
        if (!q.trim().isEmpty()) return "";
        StringBuilder sb = new StringBuilder("📊 Which do you pick?\n");
        for (int i = 0; i < pollOptions.size(); i++)
            sb.append(i + 1).append(". ").append(pollOptions.get(i)).append("\n");
        return sb.toString().trim();
    }

    // ---------- media ----------
    /** Opens unified Android gallery picker allowing BOTH Photos (up to 10) AND Videos in one tap! */
    private void pickPhotosOrVideo() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, PICK_PHOTO_OR_VIDEO);
        } catch (Throwable t) {
            Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
            fallback.addCategory(Intent.CATEGORY_OPENABLE);
            fallback.setType("*/*");
            fallback.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
            fallback.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(Intent.createChooser(fallback, "Select photo or video"), PICK_PHOTO_OR_VIDEO);
        }
    }

    private boolean isVideoUri(Uri u) {
        if (u == null) return false;
        try {
            String mime = getContentResolver().getType(u);
            if (mime != null && mime.toLowerCase().startsWith("video/")) return true;
        } catch (Throwable ignored) {}
        String s = u.toString().toLowerCase();
        return s.endsWith(".mp4") || s.endsWith(".mov") || s.endsWith(".mkv")
                || s.endsWith(".webm") || s.endsWith(".3gp") || s.contains("/video/");
    }

    private void pickMultiImages() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(Intent.createChooser(i, "Select up to 10 photos"), PICK_IMAGE);
    }

    private void pick(int code, String type) {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType(type);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(i, "Select"), code);
    }

    private void camera() {
        try {
            File f = new File(getCacheDir(), "cam_" + System.currentTimeMillis() + ".jpg");
            cameraFile = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, cameraFile);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, CAPTURE_IMAGE);
        } catch (Throwable t) {
            Ui.toast(this, "Camera not available");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK) {
            if (openedInQuickMediaMode && mediaUris.isEmpty()
                    && (text == null || text.getText().toString().trim().isEmpty())) {
                openedInQuickMediaMode = false;
                finish();
            }
            return;
        }
        openedInQuickMediaMode = false;
        applyBackground(0); // media and text-background don't mix
        if (req == CAPTURE_IMAGE && cameraFile != null) {
            mediaUris.clear();
            mediaUris.add(cameraFile);
            showMedia("image");
        } else if (req == PICK_PHOTO_OR_VIDEO && data != null) {
            mediaUris.clear();
            ClipData clip = data.getClipData();
            if (clip != null && clip.getItemCount() > 0) {
                Uri first = clip.getItemAt(0).getUri();
                if (isVideoUri(first)) {
                    mediaUris.add(first);
                    showMedia("video");
                } else {
                    for (int i = 0; i < clip.getItemCount() && mediaUris.size() < 10; i++) {
                        Uri item = clip.getItemAt(i).getUri();
                        if (!isVideoUri(item)) mediaUris.add(item);
                    }
                    if (!mediaUris.isEmpty()) showMedia("image");
                }
            } else if (data.getData() != null) {
                Uri picked = data.getData();
                mediaUris.add(picked);
                showMedia(isVideoUri(picked) ? "video" : "image");
            }
        } else if (req == PICK_IMAGE && data != null) {
            mediaUris.clear();
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount() && mediaUris.size() < 10; i++)
                    mediaUris.add(clip.getItemAt(i).getUri());
            } else if (data.getData() != null) {
                mediaUris.add(data.getData());
            }
            if (!mediaUris.isEmpty()) showMedia("image");
        } else if (req == PICK_VIDEO && data != null && data.getData() != null) {
            mediaUris.clear();
            mediaUris.add(data.getData());
            showMedia("video");
        }
    }

    private void showMedia(String type) {
        mediaType = mediaUris.size() > 1 && "image".equals(type) ? "images" : type;
        previewWrap.setVisibility(View.VISIBLE);
        multiThumbs.removeAllViews();
        if ("video".equals(mediaType)) {
            preview.setVisibility(View.GONE);
            videoPreview.setVisibility(View.VISIBLE);
            videoPreview.setVideoURI(mediaUris.get(0));
            videoPreview.setOnPreparedListener(mp -> { mp.setLooping(true); videoPreview.start(); });
            mediaLabel.setText("🎥 Video attached");
            if (openVideoEditorBtn != null) openVideoEditorBtn.setVisibility(View.VISIBLE);
        } else {
            if (openVideoEditorBtn != null) openVideoEditorBtn.setVisibility(View.GONE);
            videoPreview.setVisibility(View.GONE);
            preview.setVisibility(View.VISIBLE);
            preview.setImageURI(mediaUris.get(0));
            if (mediaUris.size() > 1) {
                for (Uri u : mediaUris) {
                    ImageView th = new ImageView(this);
                    th.setImageURI(u);
                    int sz = Ui.dp(this, 64);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
                    lp.setMargins(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
                    th.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    th.setBackground(Ui.roundStroke(this, Color.TRANSPARENT, 6, Ui.BLUE, 2));
                    multiThumbs.addView(th, lp);
                }
                ((View) multiThumbs.getParent()).setVisibility(View.VISIBLE);
                mediaLabel.setText("🖼️ " + mediaUris.size() + " photos selected (swipe album in feed)");
            } else {
                ((View) multiThumbs.getParent()).setVisibility(View.GONE);
                mediaLabel.setText("🖼️ Photo attached · 🛡 Safety verified");
            }
        }
        // 🛡 Instant on-device safety scan on picked photo/video
        com.friendbook.social.core.ContentSafetyEngine.scanMediaAsync(
                this, new ArrayList<>(mediaUris), "video".equals(mediaType), "",
                verdict -> {
                    if (isFinishing() || !verdict.unsafe) return;
                    clearMedia();
                    Ui.info(this, "🛡 Content Automatically Removed", verdict.message);
                });
    }

    private void clearMedia() {
        mediaUris.clear();
        mediaType = "none";
        previewWrap.setVisibility(View.GONE);
        videoPreview.stopPlayback();
    }

    private void loadForEdit() {
        Fb.db().collection("posts").document(editId).get().addOnSuccessListener(d -> {
            if (!d.exists()) { Ui.toast(this, "Post not found"); finish(); return; }
            com.friendbook.social.core.Mentions.restore(text, d.getString("content"), com.friendbook.social.core.Mentions.read(d.get("mentions")));
            String f = d.getString("feeling");
            if (f != null && !f.isEmpty()) { feeling = f; feelingBtn.setText(f); }
            String p = d.getString("privacy");
            if (p != null) { privacy = p; privacyBtn.setText(privacyLabel()); }
            String loc = d.getString("location");
            if (loc != null) location = loc;
            String mt = d.getString("mediaType");
            String mu = d.getString("mediaUrl");
            if (mt != null && !"none".equals(mt) && mu != null && !mu.isEmpty()) {
                previewWrap.setVisibility(View.VISIBLE);
                if ("image".equals(mt) || "images".equals(mt)) {
                    preview.setVisibility(View.VISIBLE);
                    Ui.img(this, preview, mu);
                    mediaLabel.setText("🖼️ Current photo (remove to replace)");
                } else {
                    videoPreview.setVisibility(View.VISIBLE);
                    videoPreview.setVideoURI(Uri.parse(mu));
                    mediaLabel.setText("🎥 Current video (remove to replace)");
                }
                mediaType = mt; // keep old media unless removed
            }
            Long c = d.getLong("bgColor");
            if (c != null && c != 0) applyBackground(c.intValue());
        });
    }

    // ---------- submit ----------
    private void submit() {
        if (busy) return;
        if (!com.friendbook.social.core.Admin.checkNotSuspended(this)) return;
        String content = text.getText().toString().trim();
        submittingMentions = com.friendbook.social.core.Mentions.capture(text);
        boolean hasPoll = !pollOptions.isEmpty();
        boolean hasSpecial = !"regular".equals(postType);
        if (content.isEmpty() && mediaUris.isEmpty() && !hasPoll && !hasSpecial && bgColor == 0 && editId == null) {
            Ui.toast(this, "Say something first");
            return;
        }
        com.friendbook.social.core.ContentSafetyEngine.Verdict textVerdict =
                com.friendbook.social.core.ContentSafetyEngine.checkText(content, marketTitle, lifeEventTitle);
        if (textVerdict.unsafe) {
            Ui.info(this, "🛡 Post Automatically Blocked", textVerdict.message);
            return;
        }
        busy = true;
        postBtn.setText("Checking safety…");
        postBtn.setEnabled(false);
        Ui.hideKeyboard(this);

        if (!mediaUris.isEmpty()) {
            com.friendbook.social.core.ContentSafetyEngine.scanMediaAsync(
                    this, new ArrayList<>(mediaUris), "video".equals(mediaType), content,
                    verdict -> {
                        if (isFinishing()) return;
                        if (verdict.unsafe) {
                            clearMedia();
                            fail("Blocked by friendbook Safety");
                            Ui.info(this, "🛡 Unsafe Media Auto-Deleted", verdict.message);
                            return;
                        }
                        uploadAll(content, new ArrayList<>());
                    });
        } else {
            postBtn.setText("Posting…");
            save(content, null, null);
        }
    }

    private void uploadAll(String content, List<String> done) {
        if (done.size() >= mediaUris.size()) {
            save(content, done.isEmpty() ? null : done.get(0), done);
            return;
        }
        Uri uri = mediaUris.get(done.size());
        String ext = "video".equals(mediaType) ? ".mp4" : ".jpg";
        String path = "posts/" + Fb.uid() + "/" + System.currentTimeMillis() + "_" + done.size() + ext;
        postBtn.setText("Uploading " + (done.size() + 1) + "/" + mediaUris.size() + "…");
        com.friendbook.social.core.Cloudinary.uploadOrFirebase(this, path, uri)
                .addOnSuccessListener(url -> {
                    if ("video".equals(mediaType)) {
                        com.friendbook.social.reels.ReelVideoCache.seedLocalUriToCache(getApplicationContext(), uri, url);
                    }
                    done.add(url.toString());
                    uploadAll(content, done);
                })
                .addOnFailureListener(e -> fail("Upload failed: " + e.getMessage()));
    }

    private void save(String content, String uploadedUrl, List<String> allUrls) {
        Map<String, Object> m = new HashMap<>();
        m.put("content", content);
        m.put("mentions", submittingMentions);
        m.put("feeling", feeling);
        m.put("privacy", privacy);
        m.put("location", location == null ? "" : location);
        m.put("postType", postType);
        if ("life_event".equals(postType)) {
            m.put("lifeEventIcon", lifeEventIcon);
            m.put("lifeEventTitle", lifeEventTitle);
        } else if ("marketplace".equals(postType)) {
            m.put("marketTitle", marketTitle);
            m.put("marketPrice", marketPrice);
            m.put("marketCategory", marketCategory);
            m.put("marketSold", false);
        }
        if (pageId != null && !pageId.isEmpty()) {
            m.put("pageId", pageId);
            m.put("pageName", pageName == null ? "" : pageName);
            m.put("pagePhoto", pagePhoto == null ? "" : pagePhoto);
        }
        if (uploadedUrl != null) {
            m.put("mediaUrl", uploadedUrl);
            m.put("mediaType", mediaType);
            if (allUrls != null && allUrls.size() > 1) m.put("mediaUrls", new ArrayList<>(allUrls));
        } else if (mediaType.equals("none") && editId != null) {
            m.put("mediaUrl", "");
            m.put("mediaType", "none");
            m.put("mediaUrls", new ArrayList<String>());
        }
        if (bgColor != 0) {
            m.put("bgColor", (long) (bgColor & 0xFFFFFFFFL));
            m.put("mediaType", "none");
            m.put("mediaUrl", "");
        }
        if (!pollOptions.isEmpty()) {
            m.put("pollOptions", new ArrayList<>(pollOptions));
            m.put("pollVotes", new HashMap<String, Long>());
        }
        if (editId != null) {
            m.put("editedAt", FieldValue.serverTimestamp());
            if (com.friendbook.social.core.CloudflareStore.enabled()) {
                com.friendbook.social.core.CloudflareStore.put("posts", editId, m);
            } else {
                Fb.db().collection("posts").document(editId).update(m);
            }
            Ui.toast(this, "Post updated ✓");
            finish();
            return;
        }
        String myUid = Fb.uid();
        String myName = Fb.myName();
        String myPhoto = Fb.myPhoto();
        boolean myVer = Fb.me != null && Fb.me.isVerified;
        String myTick = Fb.me != null && Fb.me.tickLevel != null ? Fb.me.tickLevel : "none";

        m.put("authorId", myUid);
        m.put("authorName", myName);
        m.put("authorPhotoUrl", myPhoto);
        m.put("authorVerified", myVer);
        m.put("authorTickLevel", myTick);
        m.put("taggedUids", new ArrayList<>(taggedUids));
        m.put("taggedNames", new ArrayList<>(taggedNames));
        if (uploadedUrl == null && bgColor == 0) { m.put("mediaUrl", ""); m.put("mediaType", "none"); }
        if (groupId != null) {
            m.put("groupId", groupId);
            m.put("groupApprovalStatus", groupNeedsApproval ? "pending" : "approved");
        }
        m.put("likes", 0);
        m.put("comments", 0);
        m.put("shares", 0);
        m.put("savedBy", new ArrayList<String>());
        m.put("createdAt", FieldValue.serverTimestamp());

        // ⚡ 0.0ms Optimistic Publish: write to local persistence + prepend to FastCache immediately!
        com.google.firebase.firestore.DocumentReference docRef = Fb.db().collection("posts").document();
        com.friendbook.social.models.Models.Post optimistic = new com.friendbook.social.models.Models.Post();
        optimistic.id = docRef.getId();
        optimistic.authorId = myUid;
        optimistic.authorName = myName;
        optimistic.authorPhoto = myPhoto;
        optimistic.authorVerified = myVer;
        optimistic.authorTickLevel = myTick;
        optimistic.content = content;
        optimistic.mentions = new java.util.ArrayList<>(submittingMentions);
        optimistic.feeling = feeling;
        optimistic.privacy = privacy;
        optimistic.location = location == null ? "" : location;
        optimistic.postType = postType;
        optimistic.lifeEventIcon = lifeEventIcon;
        optimistic.lifeEventTitle = lifeEventTitle;
        optimistic.marketTitle = marketTitle;
        optimistic.marketPrice = marketPrice;
        optimistic.marketCategory = marketCategory;
        optimistic.pageId = pageId;
        optimistic.pageName = pageName;
        optimistic.pagePhoto = pagePhoto;
        optimistic.groupId = groupId;
        optimistic.bgColor = bgColor;
        optimistic.mediaUrl = uploadedUrl == null ? "" : uploadedUrl;
        optimistic.mediaType = bgColor != 0 ? "none" : (uploadedUrl == null ? "none" : mediaType);
        if (allUrls != null) optimistic.mediaUrls = new ArrayList<>(allUrls);
        optimistic.taggedUids = new ArrayList<>(taggedUids);
        optimistic.taggedNames = new ArrayList<>(taggedNames);
        optimistic.pollOptions = new ArrayList<>(pollOptions);
        optimistic.createdAt = System.currentTimeMillis();
        if (groupId == null || groupId.isEmpty()) {
            com.friendbook.social.core.FastCache.prependFeedPost(this, optimistic);
        }

        Runnable afterPostSaved = () -> {
            if(com.friendbook.social.core.Mentions.hasBulk(submittingMentions)) com.friendbook.social.push.FCMPush.bulkMention(docRef.getId(), "");
            if ((groupId == null || groupId.isEmpty()) && (pageId == null || pageId.isEmpty())
                    && ("Public".equalsIgnoreCase(privacy) || "Friends".equalsIgnoreCase(privacy)))
                com.friendbook.social.push.FCMPush.friendPost(docRef.getId());
        };
        if (com.friendbook.social.core.CloudflareStore.enabled()) {
            com.friendbook.social.core.CloudflareStore.put("posts", docRef.getId(), m, afterPostSaved);
        } else {
            docRef.set(m).addOnSuccessListener(v -> afterPostSaved.run()).addOnFailureListener(e ->
                    android.util.Log.e("CreatePost", "Background sync failed: " + e));
        }
        Prefs.setPostDraft(this, "");
        if (groupId != null && groupNeedsApproval) {
            Ui.toast(this, "⏳ Post submitted for Group Admin/Moderator approval!");
        } else {
            Ui.toast(this, com.friendbook.social.core.FastCache.isOffline(this)
                    ? "Posted offline ✓ — will sync automatically"
                    : "Posted ✓");
        }
        finish();
    }

    private void fail(String msg) {
        busy = false;
        postBtn.setText("POST");
        postBtn.setEnabled(true);
        Ui.toast(this, msg == null ? "Failed" : msg);
    }
}
