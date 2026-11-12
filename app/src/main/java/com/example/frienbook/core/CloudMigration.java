package com.example.frienbook.core;

import android.content.Context;
import android.content.SharedPreferences;

import com.example.frienbook.BuildConfig;

import org.json.JSONObject;

/**
 * The update bridge: the moment a signed-in user opens a newer build, THEIR OWN existing history moves
 * to Cloudflare - without a single byte of the old copy being touched.
 *
 * The phone no longer carries the old database's SDK (Firebase here is authentication and nothing
 * else), so the carry-over cannot run on the device. It runs on the Worker instead: this class posts
 * one request, and the Worker reads that user's own slices from the legacy store *with that user's own
 * identity token* - so it can only ever see what this person was already allowed to see - and writes
 * them into D1.
 *
 * What the Worker guarantees, and what this class relies on:
 *  - Additive only. Every write is insert-if-absent, so a document the user has edited since updating
 *    is never overwritten by the older copy, and nothing is deleted anywhere - not in D1, not in the
 *    legacy store, which is never sent a write of any kind.
 *  - Bounded: 100 rows per page, 3 pages per slice, 50 chats, 50 messages per chat, 1000 documents
 *    total. One request can never turn into a crawl.
 *  - Honest: a slice the legacy store refuses comes back in `errors` with `ok:false`.
 *
 * This pass runs at most once per versionCode (the flag is written before the work, so two launches
 * cannot both run it) and it is silent: if it fails, the user simply sees their data as it is, and the
 * next version tries again. Re-running is harmless because the writes are insert-if-absent.
 */
public final class CloudMigration {

    private CloudMigration() {}

    /** One thread, one request, off the main thread - the request blocks until the Worker answers. */
    private static final java.util.concurrent.ExecutorService IO =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "cf-migration");
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });

    /** Call once from the signed-in entry point; returns immediately, all work is asynchronous. */
    public static void runOnceAfterUpdate(Context ctx) {
        if (ctx == null || !BuildConfig.CLOUDFLARE_DOCS_ENABLED) return;
        if (Fb.uid() == null || Fb.uid().isEmpty()) return;
        final SharedPreferences sp = ctx.getApplicationContext()
                .getSharedPreferences("cf_migration", Context.MODE_PRIVATE);
        if (sp.getInt("version", 0) >= BuildConfig.VERSION_CODE) return;
        sp.edit().putInt("version", BuildConfig.VERSION_CODE).apply();

        IO.execute(() -> {
            try {
                CloudTransport.request("/v1/migrate/legacy", "POST", new JSONObject());
            } catch (Throwable ignored) {
                // A failed carry-over must never block the app: the user's data is still where it was,
                // and the next launch of a newer build tries again.
            }
        });
    }
}
