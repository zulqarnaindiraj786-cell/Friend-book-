package com.friendbook.social.admin;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.widget.*;
import com.friendbook.social.core.*;
import com.google.firebase.firestore.*;
import java.util.*;

/** Admin-only composer: one shared upload, image/video + linked text in official Team chats. */
public final class OfficialBroadcastActivity extends Activity {
    private static final int PICK = 4102;
    private EditText message;
    private TextView status, attachment, send;
    private ImageView preview;
    private Uri selected;
    private String mediaType = "", mediaUrl = "", uploadPath = "", jobId;
    private boolean busy;
    private ListenerRegistration listener;
    private com.google.android.gms.tasks.Task<String> pendingUpload;
    private String uploadingText, uploadingUid;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!Admin.isAdmin()) { finish(); return; }
        jobId = state == null ? UUID.randomUUID().toString() : state.getString("job");
        if (jobId == null) jobId = UUID.randomUUID().toString();
        if (state != null) {
            String uri = state.getString("uri"); selected = uri == null ? null : Uri.parse(uri);
            mediaType = state.getString("type", ""); mediaUrl = state.getString("url", "");
            uploadPath = state.getString("path", "");
        }
        LinearLayout body = Ui.col(this); Ui.pad(body, 16, 12, 16, 20);
        body.addView(Ui.pageBar(this, "Official broadcast"));
        body.addView(Ui.tv(this, "Photo/video + text will appear together in everyone's official Team chat. Links open in a browser.", 14, Ui.sub(this)));
        message = Ui.multi(this, "Message, including https:// links…", 5);
        if (state != null) message.setText(state.getString("text", ""));
        body.addView(message);
        TextView pick = Ui.btnGrey(this, "Attach photo or video"); body.addView(pick);
        pick.setOnClickListener(v -> {
            if (busy) return;
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            try { startActivityForResult(intent, PICK); }
            catch (android.content.ActivityNotFoundException e) { Ui.toast(this, "No media picker available"); }
        });
        attachment = Ui.tv(this, "No attachment", 14, Ui.txt(this)); body.addView(attachment);
        preview = new ImageView(this); preview.setAdjustViewBounds(true); preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        body.addView(preview, new LinearLayout.LayoutParams(-1, Ui.dp(this, 200)));
        TextView remove = Ui.btnGrey(this, "Remove attachment"); body.addView(remove);
        remove.setOnClickListener(v -> { if (!busy) { selected = null; mediaUrl = ""; mediaType = ""; uploadPath = ""; renderAttachment(); } });
        status = Ui.tv(this, "Images: Cloudinary (20 MB). Videos: 480p, R2 Save Forever (99 MB per upload).", 13, Ui.sub(this));
        // Progress is fetched on demand rather than polled: tap the status line to refresh.
        status.setOnClickListener(v -> { if (jobId != null && !jobId.isEmpty()) watchJob(); });
        body.addView(status);
        send = Ui.btn(this, "Send to everyone"); body.addView(send); send.setOnClickListener(v -> send());
        ScrollView scroll = new ScrollView(this); scroll.addView(body); setContentView(scroll);
        renderAttachment(); watchJob();
    }
    private void renderAttachment() {
        attachment.setText(selected == null ? "No attachment" : ("video".equals(mediaType) ? "Video attached (tap to preview)" : "Photo attached"));
        preview.setImageDrawable(null);
        preview.setVisibility(selected == null ? android.view.View.GONE : android.view.View.VISIBLE);
        if (selected != null) {
            if ("video".equals(mediaType)) preview.setImageResource(android.R.drawable.ic_media_play);
            else com.bumptech.glide.Glide.with(this).load(selected).override(720, 720).fitCenter().into(preview);
        }
        preview.setOnClickListener(v -> {
            if (selected == null || busy) return;
            Intent i = new Intent(this, com.friendbook.social.post.FullscreenMediaActivity.class);
            i.putExtra("type", mediaType); i.putExtra("url", selected.toString()); i.putExtra("caption", message.getText().toString());
            startActivity(i);
        });
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData(); String mime = getContentResolver().getType(uri);
        if (mime == null || !(mime.startsWith("image/") || mime.startsWith("video/"))) { Ui.toast(this, "Choose a photo or video"); return; }
        long size = -1;
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) size = cursor.getLong(0);
        } catch (Exception ignored) {}
        long limit = mime.startsWith("image/") ? 20L * 1024 * 1024 : 99_000_000L;
        if (size < 0 || size > limit) { Ui.toast(this, "Cannot determine file size, or attachment exceeds the size limit"); return; }
        try { getContentResolver().takePersistableUriPermission(uri, data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (SecurityException | IllegalArgumentException ignored) {}
        selected = uri; mediaType = mime.startsWith("video/") ? "video" : "image"; mediaUrl = ""; uploadPath = "";
        renderAttachment();
    }
    private void send() {
        if (busy || !Admin.isAdmin()) return;
        String text = message.getText().toString().trim(), uid = Fb.uid();
        if (uid == null || (text.isEmpty() && selected == null)) { Ui.toast(this, "Enter text or attach media"); return; }
        if (text.length() > 20000) { Ui.toast(this, "Message must be under 20,000 characters"); return; }
        busy = true; send.setEnabled(false); message.setEnabled(false);
        if (selected == null || !mediaUrl.isEmpty()) { queue(text, uid); return; }
        // Legacy queued jobs/URLs stay readable. New attachments never use Firebase Storage.
        uploadPath = "";
        status.setText("Uploading attachment to Cloudinary…");
        uploadingText = text; uploadingUid = uid;
        try {
        pendingUpload = Cloudinary.uploadWithProgress(getApplicationContext(), selected,
                (percent, sentBytes, totalBytes) -> {
                    if (!isFinishing() && !isDestroyed()) status.setText("Uploading: " + percent + "%");
                });
        observeUpload();
        } catch (Exception error) { fail("Could not read attachment. Pick the file again before sending."); }
    }
    private void observeUpload() {
        if (pendingUpload == null) return;
        com.google.android.gms.tasks.Task<String> task = pendingUpload;
        task.addOnSuccessListener(this, url -> {
            if (pendingUpload != task) return;
            pendingUpload = null; mediaUrl = url; queue(uploadingText, uploadingUid);
        }).addOnFailureListener(this, error -> {
            if (pendingUpload != task) return;
            pendingUpload = null;
            fail("Cloudinary upload failed. Nothing was broadcast; retry the attachment.");
        });
    }
    @Override protected void onStart() { super.onStart(); observeUpload(); }
    private void queue(String text, String uid) {
        if (!uid.equals(Fb.uid()) || !Admin.isAdmin()) { fail("Admin account changed; broadcast stopped"); return; }
        Map<String, Object> spec = new HashMap<>();
        spec.put("adminId", uid); spec.put("text", text); spec.put("mediaUrl", mediaUrl);
        spec.put("mediaType", mediaType); spec.put("storagePath", uploadPath);
        if (mediaUrl.startsWith("https://res.cloudinary.com/")) spec.put("mediaProvider", "cloudinary");
        else if (mediaUrl.startsWith("https://pub-88ab0a56178742af95cbbf62df152a67.r2.dev/videos/")) {
            spec.put("mediaProvider", "r2"); spec.put("mediaRetention", "forever");
        }
        spec.put("createdAt", System.currentTimeMillis()); spec.put("status", "queued"); spec.put("processed", 0);
        // Queued in D1. This was a Firestore transaction, so with Firestore off the broadcast was
        // never queued and the admin was told to "check deployed Firestore rules".
        com.friendbook.social.core.DataRouter.batch()
                .set("official_broadcasts", jobId, spec)
                .commit((ok, error) -> {
                    if (!ok) { if (!isDestroyed()) fail("Could not queue broadcast. Try again."); return; }
                    OfficialBroadcastWorker.enqueue(getApplicationContext(), jobId, uid);
                    if (!isDestroyed()) status.setText("Queued. Background sending will continue while this admin account is signed in.");
                });
    }
    private void fail(String reason) { busy = false; send.setEnabled(true); message.setEnabled(true); status.setText(reason); }
    /**
     * Shows the progress of a broadcast job.
     *
     * <p>This used to be a listener, so the progress counter updated itself as the worker
     * advanced. D1 has no push, and an interval poll for the ~minute a broadcast runs would be
     * spent reads on every admin client. Instead it reads once when the job is started or the
     * screen resumes, and offers an explicit refresh - the operator is watching one screen and
     * a tap is cheaper than a timer.
     */
    private void watchJob() {
        com.friendbook.social.core.CloudflareStore.get("officialBroadcasts", jobId, doc -> {
            if (doc == null) return;
            String state = com.friendbook.social.core.Format.str(doc, "status");
            Long processed = doc.get("processed") instanceof Number
                    ? ((Number) doc.get("processed")).longValue() : 0L;
            status.setText("Broadcast: " + state + " • " + processed + " accounts processed");
            if ("complete".equals(state) || "queued".equals(state) || "sending".equals(state) || "retrying".equals(state)) {
                busy = true; send.setEnabled(false); message.setEnabled(false);
            } else if ("permission_denied".equals(state)) fail("Broadcast stopped: permission denied. Deploy the admin rules before retrying.");
        });
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out); out.putString("job", jobId);
        out.putString("uri", selected == null ? null : selected.toString()); out.putString("type", mediaType);
        out.putString("url", mediaUrl); out.putString("path", uploadPath); out.putString("text", message.getText().toString());
    }
    @Override protected void onResume() { super.onResume(); if (jobId != null && !jobId.isEmpty()) watchJob(); }
    @Override protected void onDestroy() { if (listener != null) listener.remove(); super.onDestroy(); }
}
