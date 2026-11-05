package com.friendbook.social.core;

import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** New video uploads: server-side 480p processing -> R2 only; never Cloudinary playback fallback. */
public final class R2VideoClient {
    private R2VideoClient() {}
    static final String ORIGIN = "https://pub-88ab0a56178742af95cbbf62df152a67.r2.dev";
    private static final String ENDPOINT = "https://rough-firefly-dd7b.zulqarnaindiraj786.workers.dev/v1/videos";
    private static final long MAX_FILE_BYTES = 99_000_000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    static boolean isVideo(Context context, Uri uri, String mime) {
        if (mime != null && mime.startsWith("video/")) return true;
        String path = uri.getPath();
        if (path != null && path.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(mp4|mov|mkv|webm|3gp|avi|m4v)$")) return true;
        if (mime == null || mime.equals("application/octet-stream")) {
            MediaMetadataRetriever metadata = new MediaMetadataRetriever();
            try { metadata.setDataSource(context, uri); return "yes".equals(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)); }
            catch (Exception ignored) { return false; }
            finally { try { metadata.release(); } catch (Exception ignored) {} }
        }
        return false;
    }
    private static String hex(byte[] data) {
        StringBuilder text = new StringBuilder();
        for (byte item : data) text.append(String.format(java.util.Locale.ROOT,"%02x",item & 255));
        return text.toString();
    }
    static String verifiedUrl(JSONObject body, String owner, String id) throws IOException {
        String expected = ORIGIN + "/videos/" + owner + "/" + id + ".mp4";
        if (!expected.equals(body.optString("url")) || !"r2".equals(body.optString("provider"))
                || !"forever".equals(body.optString("retention")) || !"480p".equals(body.optString("resolution"))
                || body.optLong("bytes",0) < 1) throw new IOException("Unverified R2 video result");
        return expected;
    }
    private static long size(Context ctx, Uri uri) {
        try (Cursor cursor = ctx.getContentResolver().query(uri,new String[]{OpenableColumns.SIZE},null,null,null)) {
            if(cursor!=null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0);
        } catch(Exception ignored) {}
        try(android.content.res.AssetFileDescriptor descriptor=ctx.getContentResolver().openAssetFileDescriptor(uri,"r")) {
            if(descriptor!=null)return descriptor.getLength();
        }catch(Exception ignored){}
        return -1;
    }
    static String uploadBlocking(Context ctx, Uri uri, String expectedUid, Cloudinary.ProgressListener progress) throws Exception {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if(expectedUid==null || user==null || !expectedUid.equals(user.getUid()))throw new IOException("Sign in again before uploading video");
        long length=size(ctx,uri);
        if(length<1 || length>MAX_FILE_BYTES)throw new IOException("Choose a video under 99 MB per upload");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=ctx.getContentResolver().openInputStream(uri)) {
            if(in==null)throw new IOException("Video unavailable");
            byte[] buffer=new byte[65536];int n;long read=0;
            while((n=in.read(buffer))!=-1){read+=n;if(read>length)throw new IOException("Video changed");digest.update(buffer,0,n);}
            if(read!=length)throw new IOException("Video changed");
        }
        String id=hex(digest.digest()),owner=hex(MessageDigest.getInstance("SHA-256").digest(expectedUid.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        String token=Tasks.await(user.getIdToken(false),30,TimeUnit.SECONDS).getToken();
        if(token==null || !expectedUid.equals(FirebaseAuth.getInstance().getUid()))throw new IOException("Account changed");
        URL endpoint=new URL(ENDPOINT);
        JSONObject response=send(endpoint,"GET",token,id,null,0,null);
        if(response==null) {
            try {
                response=stageAndCommit(ctx,uri,endpoint,token,id,length,progress);
            } catch (Exception stageErr) {
                try(InputStream in=ctx.getContentResolver().openInputStream(uri)) {
                    if(in==null)throw new IOException("Video unavailable");
                    response=send(endpoint,"POST",token,id,in,length,progress);
                }
            }
        }
        if(!expectedUid.equals(FirebaseAuth.getInstance().getUid()))throw new IOException("Account changed; video not attached");
        String result=verifiedUrl(response,owner,id);
        if(progress!=null)MAIN.post(()->progress.onProgress(100,length,length));
        return result;
    }
    private static JSONObject stageAndCommit(Context ctx,Uri uri,URL endpoint,String token,String id,long length,Cloudinary.ProgressListener progress)throws Exception {
        String boundary="fbstage"+System.currentTimeMillis();
        HttpURLConnection stage=(HttpURLConnection)new URL("https://api.cloudinary.com/v1_1/x5ed4d0b/video/upload").openConnection();
        JSONObject staged;
        try {
            stage.setConnectTimeout(25000);stage.setReadTimeout(300000);stage.setInstanceFollowRedirects(false);
            stage.setRequestMethod("POST");stage.setDoOutput(true);stage.setChunkedStreamingMode(65536);
            stage.setRequestProperty("Content-Type","multipart/form-data; boundary="+boundary);
            try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(stage.getOutputStream(),65536));
                InputStream in=ctx.getContentResolver().openInputStream(uri)) {
                if(in==null)throw new IOException("Video unavailable");
                out.writeBytes("--"+boundary+"\r\nContent-Disposition: form-data; name=\"upload_preset\"\r\n\r\nMyapp_upload\r\n");
                out.writeBytes("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"video.mp4\"\r\nContent-Type: video/mp4\r\n\r\n");
                byte[] buffer=new byte[65536];int n;long sent=0;int lastPercent=-1;
                while((n=in.read(buffer))!=-1) {
                    sent+=n;if(sent>length)throw new IOException("Video changed");out.write(buffer,0,n);
                    int percent=(int)(sent*85/length);
                    if(progress!=null && percent!=lastPercent){lastPercent=percent;final long done=sent;MAIN.post(()->progress.onProgress(percent,done,length));}
                }
                if(sent!=length)throw new IOException("Video changed");
                out.writeBytes("\r\n--"+boundary+"--\r\n");out.flush();
            }
            if(stage.getResponseCode()<200||stage.getResponseCode()>=300)throw new IOException("Stage failed");
            try(InputStream in=stage.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[4096];int n;
                while((n=in.read(buffer))!=-1){if(out.size()+n>65536)throw new IOException("Stage response too large");out.write(buffer,0,n);}
                staged=new JSONObject(out.toString("UTF-8"));
            }
        }finally{stage.disconnect();}
        JSONObject payload=new JSONObject().put("publicId",staged.optString("public_id"));
        if(staged.has("version"))payload.put("version",staged.optLong("version"));
        if(staged.has("delete_token"))payload.put("deleteToken",staged.optString("delete_token"));
        byte[] body=payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        HttpURLConnection conn=(HttpURLConnection)endpoint.openConnection();
        try {
            conn.setConnectTimeout(20000);conn.setReadTimeout(300000);conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("POST");conn.setDoOutput(true);conn.setFixedLengthStreamingMode(body.length);
            conn.setRequestProperty("Authorization","Bearer "+token);conn.setRequestProperty("X-Video-Id",id);
            conn.setRequestProperty("Content-Type","application/json");
            try(OutputStream out=conn.getOutputStream()){out.write(body);}
            if(conn.getResponseCode()!=200)throw new IOException("R2 commit failed ("+conn.getResponseCode()+")");
            try(InputStream in=conn.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[4096];int n;
                while((n=in.read(buffer))!=-1){if(out.size()+n>65536)throw new IOException("Video response too large");out.write(buffer,0,n);}
                return new JSONObject(out.toString("UTF-8"));
            }
        }finally{conn.disconnect();}
    }
    // Package-private loopback seam. Public upload endpoint is fixed HTTPS.
    static JSONObject send(URL endpoint,String method,String token,String id,InputStream input,long length,Cloudinary.ProgressListener progress)throws Exception {
        HttpURLConnection connection=(HttpURLConnection)endpoint.openConnection();
        try {
            connection.setConnectTimeout(20000);connection.setReadTimeout(660000);
            connection.setInstanceFollowRedirects(false);connection.setRequestMethod(method);
            connection.setRequestProperty("Authorization","Bearer "+token);
            connection.setRequestProperty("X-Video-Id",id);
            if(input!=null) {
                connection.setDoOutput(true);connection.setFixedLengthStreamingMode(length);
                connection.setRequestProperty("Content-Type","application/octet-stream");
                try(OutputStream out=connection.getOutputStream()) {
                    byte[] buffer=new byte[65536];int n;long sent=0;int lastPercent=-1;
                    while((n=input.read(buffer))!=-1) {
                        sent+=n;if(sent>length)throw new IOException("Video changed");out.write(buffer,0,n);
                        int percent=(int)(sent*90/length);
                        if(progress!=null && percent!=lastPercent){lastPercent=percent;final long done=sent;MAIN.post(()->progress.onProgress(percent,done,length));}
                    }
                    if(sent!=length)throw new IOException("Video changed");
                }
            }
            int status=connection.getResponseCode();
            if(method.equals("GET")&&status==404)return null;
            if(status!=200)throw new IOException("R2 video service unavailable ("+status+")");
            try(InputStream in=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[4096];int n;
                while((n=in.read(buffer))!=-1){if(out.size()+n>65536)throw new IOException("Video response too large");out.write(buffer,0,n);}
                return new JSONObject(out.toString("UTF-8"));
            }
        }finally{connection.disconnect();}
    }
}
