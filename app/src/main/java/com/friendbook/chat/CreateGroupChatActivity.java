package com.friendbook.chat;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.friendbook.core.Cloudinary;
import com.friendbook.core.FastCache;
import com.friendbook.core.Fb;
import com.friendbook.core.Icons;
import com.friendbook.core.Ui;
import com.friendbook.models.Models;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 👥 Create a Messenger Group Chat:
 * - Creator automatically becomes Group Admin (`createdBy` + `adminIds`)!
 * - Pick Group Name, optional Group Photo, and multi-select any Friends / Users!
 */
public class CreateGroupChatActivity extends Activity {

    private static final int PICK_GROUP_PHOTO = 701;

    private ImageView groupPhotoIv;
    private EditText groupNameIn, searchIn;
    private TextView createBtn, selectedCountTv;
    private LinearLayout usersListCol;

    private Uri pickedPhotoUri;
    private final List<Models.User> allUsers = new ArrayList<>();
    private final Set<String> selectedUids = new LinkedHashSet<>();
    private final Map<String, Models.User> userById = new HashMap<>();
    private String query = "";
    private boolean creating = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        root.addView(Ui.pageBar(this, "👥 New Group Chat"));
        root.addView(Ui.divider(this));

        // Top Group Metadata Card (Photo + Group Name)
        LinearLayout metaCard = Ui.col(this);
        metaCard.setBackgroundColor(Ui.card(this));
        Ui.pad(metaCard, 16, 14, 16, 14);

        LinearLayout topRow = Ui.row(this);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        groupPhotoIv = new ImageView(this);
        int ps = Ui.dp(this, 56);
        groupPhotoIv.setBackground(Ui.circle(Ui.inputBg(this)));
        groupPhotoIv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        groupPhotoIv.setImageDrawable(Icons.tinted(this, Icons.CAMERA, Ui.BLUE));
        int iconPad = Ui.dp(this, 14);
        groupPhotoIv.setPadding(iconPad, iconPad, iconPad, iconPad);
        groupPhotoIv.setOnClickListener(v -> {
            Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
            pick.setType("image/*");
            startActivityForResult(pick, PICK_GROUP_PHOTO);
        });
        topRow.addView(groupPhotoIv, new LinearLayout.LayoutParams(ps, ps));

        groupNameIn = Ui.input(this, "Enter Group Name (e.g. Friends Squad 🔥)…");
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, -2, 1);
        nlp.leftMargin = Ui.dp(this, 12);
        topRow.addView(groupNameIn, nlp);
        metaCard.addView(topRow);

        TextView adminHint = Ui.tv(this,
                "👑 You will be the Group Admin — you can add/remove members & promote other admins anytime.",
                11.5f, Ui.sub(this));
        adminHint.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 10));
        metaCard.addView(adminHint);

        searchIn = Ui.input(this, "🔍 Search people to add…");
        searchIn.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b2, int c) {
                query = s.toString().trim().toLowerCase(Locale.US);
                renderUsers();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        metaCard.addView(searchIn, new LinearLayout.LayoutParams(-1, -2));

        selectedCountTv = Ui.tvBold(this, "Selected: 0 members", 12.5f, Ui.BLUE);
        selectedCountTv.setPadding(0, Ui.dp(this, 8), 0, 0);
        metaCard.addView(selectedCountTv);
        root.addView(metaCard);
        root.addView(Ui.divider(this));

        ScrollView sv = new ScrollView(this);
        usersListCol = Ui.col(this);
        Ui.pad(usersListCol, 12, 8, 12, 16);
        sv.addView(usersListCol);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout bottomBar = Ui.col(this);
        bottomBar.setBackgroundColor(Ui.card(this));
        bottomBar.addView(Ui.divider(this));
        Ui.pad(bottomBar, 14, 10, 14, 12);
        createBtn = Ui.btn(this, "Create Group Chat");
        createBtn.setOnClickListener(v -> createGroupNow());
        bottomBar.addView(createBtn, new LinearLayout.LayoutParams(-1, -2));
        root.addView(bottomBar);

        setContentView(root);
        loadPeople();
    }

    private void loadPeople() {
        String me = Fb.uid();
        Fb.db().collection("users").limit(250).get().addOnSuccessListener(snap -> {
            allUsers.clear();
            if (snap != null) {
                for (DocumentSnapshot d : snap.getDocuments()) {
                    Models.User u = Models.User.fromDoc(d);
                    if (me != null && me.equals(u.uid)) continue;
                    if (u.banned) continue;
                    FastCache.putUser(u);
                    allUsers.add(u);
                    userById.put(u.uid, u);
                }
            }
            renderUsers();
        });
    }

    private void renderUsers() {
        usersListCol.removeAllViews();
        selectedCountTv.setText("Selected: " + selectedUids.size() + " member" + (selectedUids.size() == 1 ? "" : "s"));
        createBtn.setText(selectedUids.isEmpty()
                ? "Create Group Chat"
                : "Create Group Chat (" + (selectedUids.size() + 1) + " people)");

        for (Models.User u : allUsers) {
            if (!query.isEmpty() && !u.displayName.toLowerCase(Locale.US).contains(query)) continue;
            LinearLayout row = Ui.row(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(Ui.round(this, Ui.card(this), 12));
            Ui.pad(row, 12, 9, 12, 9);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = Ui.dp(this, 6);
            row.setLayoutParams(lp);

            ImageView av = new ImageView(this);
            Ui.avatar(this, av, u.photoUrl);
            int s = Ui.dp(this, 42);
            row.addView(av, new LinearLayout.LayoutParams(s, s));

            LinearLayout mid = Ui.col(this);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -2, 1);
            mp.leftMargin = Ui.dp(this, 10);
            LinearLayout nr = Ui.row(this);
            nr.setGravity(Gravity.CENTER_VERTICAL);
            nr.addView(Ui.tvBold(this, u.displayName, 14.5f, Ui.txt(this)));
            ImageView tk = new ImageView(this);
            Icons.setTickBadge(tk, u.tickLevel, false);
            int ts = Ui.dp(this, 14);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ts, ts);
            tlp.leftMargin = Ui.dp(this, 4);
            nr.addView(tk, tlp);
            mid.addView(nr);
            if (u.bio != null && !u.bio.isEmpty()) {
                TextView bio = Ui.tv(this, u.bio, 11.5f, Ui.sub(this));
                bio.setSingleLine(true);
                mid.addView(bio);
            }
            row.addView(mid, mp);

            CheckBox cb = new CheckBox(this);
            cb.setChecked(selectedUids.contains(u.uid));
            cb.setOnCheckedChangeListener((btn, checked) -> {
                if (checked) selectedUids.add(u.uid);
                else selectedUids.remove(u.uid);
                selectedCountTv.setText("Selected: " + selectedUids.size() + " member" + (selectedUids.size() == 1 ? "" : "s"));
                createBtn.setText(selectedUids.isEmpty()
                        ? "Create Group Chat"
                        : "Create Group Chat (" + (selectedUids.size() + 1) + " people)");
            });
            row.addView(cb);
            row.setOnClickListener(v -> cb.setChecked(!cb.isChecked()));
            usersListCol.addView(row);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == PICK_GROUP_PHOTO && res == RESULT_OK && data != null && data.getData() != null) {
            pickedPhotoUri = data.getData();
            groupPhotoIv.setPadding(0, 0, 0, 0);
            Ui.avatar(this, groupPhotoIv, pickedPhotoUri.toString());
        }
    }

    private void createGroupNow() {
        if (creating) return;
        String me = Fb.uid();
        if (me == null) return;
        String gName = groupNameIn.getText().toString().trim();
        if (gName.isEmpty()) {
            Ui.toast(this, "Please enter a Group Name");
            return;
        }
        if (selectedUids.isEmpty()) {
            Ui.toast(this, "Select at least 1 member for the group");
            return;
        }
        creating = true;
        createBtn.setText("Creating group…");

        if (pickedPhotoUri != null) {
            String path = "group_chats/" + System.currentTimeMillis() + ".jpg";
            Cloudinary.uploadOrFirebase(this, path, pickedPhotoUri)
                    .addOnSuccessListener(url -> saveGroupDoc(me, gName, url.toString()))
                    .addOnFailureListener(e -> saveGroupDoc(me, gName, ""));
        } else {
            saveGroupDoc(me, gName, "");
        }
    }

    private void saveGroupDoc(String me, String gName, String photoUrl) {
        DocumentReference chatRef = Fb.db().collection("chats").document();
        List<String> parts = new ArrayList<>();
        parts.add(me);
        parts.addAll(selectedUids);

        Map<String, String> names = new HashMap<>();
        Map<String, String> photos = new HashMap<>();
        names.put(me, Fb.myName());
        photos.put(me, Fb.myPhoto());
        Map<String, Object> unread = new HashMap<>();
        unread.put(me, 0);
        for (String uid : selectedUids) {
            Models.User u = userById.get(uid);
            if (u != null) {
                names.put(uid, u.displayName);
                photos.put(uid, u.photoUrl == null ? "" : u.photoUrl);
            }
            unread.put(uid, 1);
        }

        String sysMsg = "👑 " + Fb.myName() + " created group \"" + gName + "\"";
        long now = System.currentTimeMillis();

        Map<String, Object> doc = new HashMap<>();
        doc.put("isGroup", true);
        doc.put("groupName", gName);
        doc.put("groupPhoto", photoUrl);
        doc.put("createdBy", me);
        doc.put("adminIds", Collections.singletonList(me));
        doc.put("onlyAdminsCanSend", false);
        doc.put("participants", parts);
        doc.put("participantNames", names);
        doc.put("participantPhotos", photos);
        doc.put("lastMessage", sysMsg);
        doc.put("lastMessageTime", FieldValue.serverTimestamp());
        doc.put("lastMessageTimeMs", now);
        doc.put("unreadCount", unread);

        chatRef.set(doc).addOnSuccessListener(x -> {
            Map<String, Object> m = new HashMap<>();
            m.put("senderId", me);
            m.put("senderName", Fb.myName());
            m.put("senderPhoto", Fb.myPhoto());
            m.put("text", sysMsg);
            m.put("type", "system");
            m.put("isDelivered", true);
            m.put("isSeen", true);
            m.put("createdAtMs", now);
            m.put("createdAt", FieldValue.serverTimestamp());
            chatRef.collection("messages").add(m);

            Ui.toast(this, "Group \"" + gName + "\" created! 🎉");
            Intent open = new Intent(this, ChatActivity.class);
            open.putExtra("chat_id", chatRef.getId());
            open.putExtra("is_group", true);
            open.putExtra("name", gName);
            startActivity(open);
            finish();
        }).addOnFailureListener(e -> {
            creating = false;
            createBtn.setText("Create Group Chat");
            Ui.toast(this, "Failed: " + e.getMessage());
        });
    }
}
