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
    public static void enqueue(JSONObject body) { enqueue(body, false); }
    public static void enqueue(JSONObject body, boolean replaceRegistration) {
        String uid = Fb.uid(); if (uid == null) return;
        String action = body.optString("action");
        String key = "register".equals(action) ? "register_" + uid : uid + "_" + body.toString();
        OneTimeWorkRequest.Builder builder = new OneTimeWorkRequest.Builder(PushDeliveryWorker.class)
                .setInputData(new Data.Builder().putString("uid", uid).putString("body", body.toString()).build())
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .addTag("fcm_user_" + uid);
        // Pre-Android 12 expedited workers require a visible foreground service.
        // Use normal durable work there rather than creating a notification just to send a push.
        if (android.os.Build.VERSION.SDK_INT >= 31)
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST);
        OneTimeWorkRequest work = builder.build();
        PushTrace.stage("queue_requested", "work=" + work.getId() + " " + PushTrace.request(body));
        Operation operation = WorkManager.getInstance(App.instance).enqueueUniqueWork("fcm_" + key,
                replaceRegistration && "register".equals(action) ? ExistingWorkPolicy.REPLACE : ExistingWorkPolicy.KEEP, work);
        operation.getResult().addListener(() -> {
            try {
                operation.getResult().get();
                PushTrace.stage("queue_operation_ok", "work=" + work.getId()); // KEEP may retain an older active request.
            } catch (Exception e) { PushTrace.failure("queue_failed", e); }
        }, androidx.core.content.ContextCompat.getMainExecutor(App.instance));
    }
    @NonNull @Override public Result doWork() {
        String uid = getInputData().getString("uid");
        if (uid == null || !uid.equals(Fb.uid())) {
            PushTrace.stage("worker_skipped", "reason=account_changed work=" + getId());
            return Result.success();
        }
        boolean registration = false;
        try {
            JSONObject body = new JSONObject(getInputData().getString("body"));
            PushTrace.stage("worker_start", "work=" + getId() + " attempt=" + getRunAttemptCount()
                    + " actor=" + PushTrace.id(uid) + " " + PushTrace.request(body));
            boolean register = "register".equals(body.optString("action"));
            registration = register;
            if (register) {
                String token = Tasks.await(FirebaseMessaging.getInstance().getToken(), 20, TimeUnit.SECONDS);
                body.put("token", token);
                PushTrace.stage("fcm_token_ready", "present=" + (token != null && !token.isEmpty())
                        + " actor=" + PushTrace.id(uid));
            }
            String bearer = PushGateway.idToken();
            if (!uid.equals(Fb.uid())) return Result.success();
            PushGateway.Response response = PushGateway.call(body, bearer);
            if (response.status == 401 && uid.equals(Fb.uid())) {
                String freshBearer = PushGateway.idToken(true);
                if (!uid.equals(Fb.uid())) return Result.success();
                response = PushGateway.call(body, freshBearer);
            }
            String outcome = "HTTP " + response.status + " action=" + PushTrace.label(body.optString("action"))
                    + " error=" + PushTrace.label(response.data.optString("error"))
                    + " registered=" + response.data.optBoolean("registered")
                    + " accepted=" + response.data.optInt("accepted") + " duplicate=" + response.data.optInt("duplicate")
                    + " invalid=" + response.data.optInt("invalid") + " failed=" + response.data.optInt("failed");
            PushTrace.stage("gateway_result", "work=" + getId() + " " + outcome);
            getApplicationContext().getSharedPreferences("push_diagnostics", 0).edit()
                    .putString(register ? "registration" : "last_send", outcome)
                    .putLong("updated_at", System.currentTimeMillis()).apply();
            android.util.Log.i("FriendBookPush", outcome);
            if (response.status >= 200 && response.status < 300) {
                // Some devices may accept while another transiently fails. The
                // gateway deduplicates accepted devices on a bounded retry.
                if ("message".equals(body.optJSONObject("data") == null ? "" : body.optJSONObject("data").optString("type"))
                        && response.data.optInt("failed") > 0)
                    return PushRetryPolicy.shouldRetry(false, 503, getRunAttemptCount()) ? Result.retry() : Result.failure();
                return Result.success();
            }
            return PushRetryPolicy.shouldRetry(register, response.status, getRunAttemptCount(), response.data.optString("error"))
                    ? Result.retry() : Result.failure();
        } catch (Exception e) {
            android.util.Log.w("FriendBookPush", "Push gateway retry pending: " + e.getClass().getSimpleName());
            getApplicationContext().getSharedPreferences("push_diagnostics", 0).edit()
                    .putString("last_error", "Gateway unavailable; retry " + (getRunAttemptCount() + 1)).apply();
            return PushRetryPolicy.shouldRetry(registration, 0, getRunAttemptCount()) ? Result.retry() : Result.failure();
        }
    }
}
