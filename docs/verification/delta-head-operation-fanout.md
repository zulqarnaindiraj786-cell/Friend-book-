# Delta-head operation fanout

Source **14127f92971f7b86a6521240cbb2db2b07c12503**; Android compile/JVM **45289736174 SUCCESS**.

`Query.refreshCachedHead` used its own raw transport and queued merge operation, bypassing the DEFAULT whole-query registry. Home lifecycle refresh and CursorPager delta starts could therefore repeat an identical queued delta request even after ordinary list sharing was added. The empty-cache branch also forced SERVER rather than joining ordinary initial reads.

Now the empty-cache branch uses DEFAULT (network read, not a TTL hit). Nonempty heads share the entire delta fetch/merge operation, keyed by account, original head query, exact delta query and the complete serialized cached merge input. Including only the first cursor would be incorrect: two cached pages can have the same anchor but different body/engagement/timestamps. Cached rows are serialized before enqueueing; bundled engagement is moved to its transport envelope without mutating the input snapshot. Existing query mutation/push invalidation detaches these flights. Explicit forced Home/CursorPager refresh remains SERVER and does not call this path.

Verification:
- Three new JVM tests cover serialization/timestamps/engagement preservation, different cached body/summary separation, and 100 additional overlapping registry callers sharing one pending operation with invalidation.
- Python **425 passed**, including production delta wiring guards.
- Worker **293 passed**, quota replay **48 workloads completed successfully**, diff check passed.
- Registry and source-wiring tests are not an installed-device HTTP trace or proof of production duplication frequency/billed savings.

This closes the raw-delta source bypass listed in `engagement-operation-fanout.md`. Required authorization and legitimate list/engagement reads remain. Other production traffic attribution and <=100k reads/<=5k writes representative-day acceptance remain unverified. No APK/AAB, deployment, migration or merge performed.
