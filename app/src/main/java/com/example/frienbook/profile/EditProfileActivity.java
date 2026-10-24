package com.example.frienbook.profile;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.frienbook.core.Fb;
import com.example.frienbook.core.Ui;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.SetOptions;

import java.util.HashMap;
import java.util.Map;

/** Edit profile: name, bio, avatar, cover — with instant upload. */
public class EditProfileActivity extends Activity {

    private static final int PICK_AVATAR = 61, PICK_COVER = 62;

    private ImageView cover, avatar;
    private EditText name, bio;
    private TextView save;
    private ListenerRegistration userReg;
    private String avatarUrl = "", coverUrl = "";
    private boolean busy;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        root.addView(Ui.pageBar(this, "Edit profile"));

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        LinearLayout body = Ui.col(this);

        // cover pick
        FrameLayout coverWrap = new FrameLayout(this);
        cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        coverWrap.addView(cover, new FrameLayout.LayoutParams(-1, Ui.dp(this, 150)));
        TextView coverBtn = Ui.btnGrey(this, "🖼 Change cover");
        FrameLayout.LayoutParams cbp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        cbp.setMargins(0, 0, Ui.dp(this, 10), Ui.dp(this, 10));
        coverWrap.addView(coverBtn, cbp);
        body.addView(coverWrap, new LinearLayout.LayoutParams(-1, Ui.dp(this, 150)));
        coverBtn.setOnClickListener(v -> pick(PICK_COVER));

        // avatar pick
        LinearLayout avRow = Ui.row(this);
        avRow.setGravity(Gravity.CENTER_VERTICAL);
        Ui.pad(avRow, 14, 16, 14, 4);
        avatar = new ImageView(this);
        int s = Ui.dp(this, 84);
        avRow.addView(avatar, new LinearLayout.LayoutParams(s, s));
        TextView avBtn = Ui.btnGrey(this, "📷 Change photo");
        LinearLayout.LayoutParams abp = new LinearLayout.LayoutParams(-2, -2);
        abp.leftMargin = Ui.dp(this, 16);
        avRow.addView(avBtn, abp);
        body.addView(avRow);
        avBtn.setOnClickListener(v -> pick(PICK_AVATAR));

        LinearLayout form = Ui.col(this);
        Ui.pad(form, 14, 12, 14, 14);
        TextView nl = Ui.tvBold(this, "Name", 13, Ui.sub(this));
        name = Ui.input(this, "Your name");
        TextView bl = Ui.tvBold(this, "Bio", 13, Ui.sub(this));
        bio = Ui.multi(this, "Tell people about yourself…", 3);
        form.addView(nl);
        form.addView(name, lpm());
        form.addView(sp(10));
        form.addView(bl);
        form.addView(bio, lpm());
        body.addView(form);

        save = Ui.btn(this, "Save changes");
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 46));
        sp.setMargins(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 16));
        body.addView(save, sp);
        save.setOnClickListener(v -> save());

        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private View sp(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, Ui.dp(this, h)));
        return v;
    }

    private LinearLayout.LayoutParams lpm() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = Ui.dp(this, 4);
        return p;
    }

    @Override
    protected void onStart() {
        super.onStart();
        userReg = Fb.db().collection("users").document(Fb.uid())
                .addSnapshotListener((d, err) -> {
                    if (d == null || !d.exists()) return;
                    com.example.frienbook.models.Models.User u =
                            com.example.frienbook.models.Models.User.fromDoc(d);
                    name.setText(u.displayName);
                    name.setSelection(name.getText().length());
                    bio.setText(u.bio);
                    avatarUrl = u.photoUrl;
                    coverUrl = u.coverUrl;
                    Ui.avatar(this, avatar, avatarUrl);
                    if (!coverUrl.isEmpty()) Ui.img(this, cover, coverUrl);
                    else cover.setBackground(Ui.round(this, Ui.div(this), 0));
                });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (userReg != null) userReg.remove();
    }

    private void pick(int code) {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        startActivityForResult(i, code);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        boolean isAvatar = req == PICK_AVATAR;
        Ui.toast(this, "Uploading…");
        String path = "profiles/" + Fb.uid() + "/" + (isAvatar ? "avatar" : "cover")
                + "_" + System.currentTimeMillis() + ".jpg";
        Fb.upload(path, uri)
                .addOnSuccessListener(url -> {
                    Map<String, Object> patch = new HashMap<>();
                    patch.put(isAvatar ? "photoUrl" : "coverUrl", url.toString());
                    Fb.patchMe(patch);
                    Ui.toast(this, isAvatar ? "Profile photo updated ✓" : "Cover updated ✓");
                })
                .addOnFailureListener(e -> Ui.toast(this, "Upload failed"));
    }

    private void save() {
        if (busy) return;
        String n = name.getText().toString().trim();
        if (n.length() < 2) { Ui.toast(this, "Name is too short"); return; }
        busy = true;
        save.setText("Saving…");
        Map<String, Object> m = new HashMap<>();
        m.put("displayName", n);
        m.put("bio", bio.getText().toString().trim());
        Fb.db().collection("users").document(Fb.uid()).set(m, SetOptions.merge())
                .addOnSuccessListener(x -> {
                    Ui.toast(this, "Profile updated ✓");
                    finish();
                })
                .addOnFailureListener(e -> {
                    busy = false;
                    save.setText("Save changes");
                    Ui.toast(this, e.getMessage());
                });
    }
}
