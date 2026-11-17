# Convert `Fb.db().collection("x").document(id).update(fields)` into a D1 write.

The remaining Firestore writes are the reason a new account still cannot do
everything: Firestore is switched off, so a write there is refused and the app
reports a failure the user cannot act on. Every one of them is mechanically the
same shape, so they are converted with a script rather than by hand - hand
editing 100 call sites guarantees some are missed.

What this does:

    Fb.db().collection("x").document(idVar).update(fields)
    -> com.friendbook.social.core.DataRouter.batch().set("x", idVar, fields).commit()

    ...update("field", value)
    -> ...set("x", idVar, Fb.map("field", value)).commit()

    ...delete()
    -> ...delete("x", idVar).commit()

`update` becomes `set`: the worker's PUT merges into the stored document and does
not clear fields it was not given, so a merge is a merge either way, and a `set`
also creates the document when D1 does not have it yet (a patch would answer 404).

`FieldValue` sentinels inside the fields are handled by D1Batch: a time-shaped
one becomes a real timestamp, an array one is reported rather than stored.

## Second pass: the reads, and the stores nobody noticed

The first pass moved the 127 writes. Three classes of failure were left, and each one was a
screen that answered nothing rather than an error a user could act on:

1. **Lists still queried Firestore.** Profile posts/reels/friends, Search, New chat, Follow
   lists, Birthdays, Saved, Watch, Leaderboard, Activity log, Mentions, Reels paging, stories.
   Each now pages with `DocPager`/`DocQuery` (server cursor, bounded page) and renders from the
   raw `Map<String,Object>` row via `Models.*.fromMap`, because a `DocumentSnapshot` cannot be
   built without Firestore.
2. **Ids and timestamps minted by Firestore.** `document().getId()` threw with Firestore off,
   which killed the whole call before the D1 write - `Notify.send` was the worst case: every
   like, comment and friend request notified nobody. Ids are local now (`DataRouter.add` already
   did this; the remaining call sites follow it).
3. **`FieldValue.serverTimestamp()` was being dropped.** `CloudflareStore.toJson` only wrote a
   sentinel when the field name contained "at", "time" or "seen". Every other timestamp
   (`createdAt` on a post, a message, a payment request) was silently omitted. A sentinel now
   always becomes the current millis, so a row never lands without its time.

Also converted in this pass: both WorkManager fan-outs (`OfficialBroadcastWorker`,
`PushFanoutWorker`) - they were built on Firestore transactions, so a broadcast reached nobody
and a shared post notified nobody. Replay safety comes from job-derived document ids, and a
shared `D1Blocking` helper gives a worker the same "value or refusal" contract `Tasks.await` did.
