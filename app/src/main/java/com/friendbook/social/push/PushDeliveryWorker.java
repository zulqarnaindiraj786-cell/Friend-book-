package com.friendbook.social.push;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;
import com.friendbook.social.App;
import com.friendbook.social.core.Fb;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.messaging.FirebaseMessaging;
import org.json.JSONObject;
import java.util.concurrent.TimeUnit;

/** Durable network-constrained outbox survives normal app process death and transient gateway errors. */
public final class PushDeliveryWorker extends Worker {
    public PushDeliveryWorker(@NonNull Context c, @NonNull WorkerParameters p) { super(c, p); }
    public static void enqueue(JSONObject body) {
        String uid = Fb.uid(); if (uid == null) return;
        String action = body.optString("action");
        String key = "register".equals(action) ? "register_" + uid : uid + "_" + body.toString();
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(PushDeliveryWorker.class)
                .setInputData(new Data.Builder().putString("uid", uid).putString("body", body.toString()).build())
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .addTag("fcm_user_" + uid).build();
        WorkManager.getInstance(App.instance).enqueueUniqueWork("fcm_" + key,
                ExistingWorkPolicy.KEEP, work);
    }
    @NonNull @Override public Result doWork() {
        String uid = getInputData().getString("uid");
        if (uid == null || !uid.equals(Fb.uid())) return Result.success();
        try {
            JSONObject body = new JSONObject(getInputData().getString("body"));
            boolean register = "register".equals(body.optString("action"));
            if (register) body.put("token", Tasks.await(FirebaseMessaging.getInstance().getToken(), 20, TimeUnit.SECONDS));
            String bearer = PushGateway.idToken();
            if (!uid.equals(Fb.uid())) return Result.success();
            PushGateway.Response response = PushGateway.call(body, bearer);
            String outcome = "HTTP " + response.status + " " + response.data.toString();
            getApplicationContext().getSharedPreferences("push_diagnostics", 0).edit()
                    .putString(register ? "registration" : "last_send", outcome)
                    .putLong("updated_at", System.currentTimeMillis()).apply();
            android.util.Log.i("FriendBookPush", outcome);
            if (response.status >= 200 && response.status < 300) return Result.success();
            return response.retryable() && getRunAttemptCount() < 8 ? Result.retry() : Result.failure();
        } catch (Exception e) {
            android.util.Log.w("FriendBookPush", "Push gateway retry pending: " + e.getClass().getSimpleName());
            getApplicationContext().getSharedPreferences("push_diagnostics", 0).edit()
                    .putString("last_error", "Gateway unavailable; retry " + (getRunAttemptCount() + 1)).apply();
            return getRunAttemptCount() < 8 ? Result.retry() : Result.failure();
        }
    }
}
