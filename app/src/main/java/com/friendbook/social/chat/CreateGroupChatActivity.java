package com.friendbook.social.chat;

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

import com.friendbook.social.core.Cloudinary;
import com.friendbook.social.core.FastCache;
import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Icons;
import com.friendbook.social.core.Ui;
import com.friendbook.social.models.Models;
import com.friendbook.social.d1.documents.DocumentReference;
import com.friendbook.social.d1.documents.DocumentSnapshot;
import com.friendbook.social.d1.documents.FieldValue;

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
    private LinearLayout usersListCol,peopleControls;
    private com.friendbook.social.d1.documents.ListenerRegistration peopleReg;
    private String peopleActor;

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

        peopleControls=Ui.col(this);root.addView(peopleControls);
        LinearLayout bottomBar = Ui.col(this);
        bottomBar.setBackgroundColor(Ui.card(this));
        bottomBar.addView(Ui.divider(this));
        Ui.pad(bottomBar, 14, 10, 14, 12);
        createBtn = Ui.btn(this, "Create Group Chat");
        createBtn.setOnClickListener(v -> createGroupNow());
        bottomBar.addView(createBtn, new LinearLayout.LayoutParams(-1, -2));
        root.addView(bottomBar);

        setContentView(root);
    }

    @Override protected void onStart(){
        super.onStart();
        String actor=Fb.uid();if(actor==null){finish();return;}
        if(peopleActor!=null && !java.util.Objects.equals(peopleActor,actor)){finish();return;}
        peopleActor=actor;loadPeople();
    }
    @Override protected void onStop(){
        if(peopleReg!=null){peopleReg.remove();peopleReg=null;}
        super.onStop();
    }

    private void loadPeople() {
        String me = Fb.uid();
        if(peopleReg!=null)peopleReg.remove();
        peopleReg=com.friendbook.social.core.PagedLists.attach(this,peopleControls,Fb.db().collection("users"),10,
                "chat-people:create-group",(snap,error)->{
            if(!java.util.Objects.equals(me,Fb.uid()))return;
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
        if (me == null || !java.util.Objects.equals(peopleActor,me)) return;
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
        if(!java.util.Objects.equals(me,Fb.uid()) || isFinishing() || isDestroyed())return;
        DocumentReference chatRef = Fb.db().collection("chats").document();
        List<String> parts = new ArrayList<>();
        parts.add(me);
        for(String uid:selectedUids)if(uid!=null && !uid.isEmpty() && !parts.contains(uid))parts.add(uid);
        if(parts.size()<2){creating=false;createBtn.setText("Create Group Chat");Ui.toast(this,"Select a valid group member");return;}

        Map<String, String> names = new HashMap<>();
        Map<String, String> photos = new HashMap<>();
        names.put(me, Fb.myName());
        photos.put(me, Fb.myPhoto());
        Map<String, Object> unread = new HashMap<>();
        unread.put(me, 0);
        for (String uid : parts.subList(1,parts.size())) {
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

        com.friendbook.social.d1.documents.WriteBatch batch=Fb.db().batch();
        batch.set(chatRef,doc);
        batch.set(chatRef.collection("messages").document(),m);
        batch.commit().addOnSuccessListener(x -> {
            if(!java.util.Objects.equals(me,Fb.uid()) || isFinishing() || isDestroyed())return;
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
