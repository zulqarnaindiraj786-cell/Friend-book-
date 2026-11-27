package com.friendbook.social.admin;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;
import com.friendbook.social.core.*;
import com.google.android.gms.tasks.Tasks;
import com.friendbook.social.d1.documents.*;
import java.util.concurrent.TimeUnit;

/** Advances a server-owned D1 broadcast in resumable, idempotent steps. */
public final class OfficialBroadcastWorker extends Worker {
    public OfficialBroadcastWorker(@NonNull Context c, @NonNull WorkerParameters p) { super(c, p); }
    public static void enqueue(Context context, String job, String uid) {
        WorkManager.getInstance(context).enqueueUniqueWork("official_broadcast_" + job, ExistingWorkPolicy.KEEP,
                new OneTimeWorkRequest.Builder(OfficialBroadcastWorker.class)
                        .setInputData(new Data.Builder().putString("job", job).putString("uid", uid).build())
                        .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS).build());
    }
    @NonNull @Override public Result doWork() {
        String uid = getInputData().getString("uid"), id = getInputData().getString("job");
        if (uid == null || !uid.equals(Fb.uid()) || id == null) return Result.failure();
        try {
            // Bounded runs keep WorkManager retry/resume safe. The cursor and
            // duplicate protection are committed on the Worker, not this phone.
            for (int i = 0; i < 100 && !isStopped(); i++) {
                if (!uid.equals(Fb.uid())) return Result.failure();
                if (Tasks.await(Fb.db().advanceOfficialBroadcast(id), 40, TimeUnit.SECONDS)) return Result.success();
            }
            return Result.retry();
        } catch (Exception e) {
            com.friendbook.social.push.PushTrace.failure("official_broadcast_failed", e);
            Throwable cause = e;
            while (cause.getCause() != null) cause = cause.getCause();
            if (cause instanceof D1DocumentStoreException) {
                D1DocumentStoreException.Code code = ((D1DocumentStoreException) cause).getCode();
                if (code == D1DocumentStoreException.Code.PERMISSION_DENIED || code == D1DocumentStoreException.Code.NOT_FOUND) return Result.failure();
            }
            return Result.retry();
        }
    }
}
