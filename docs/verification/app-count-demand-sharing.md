# App count-request demand sharing

Source5af886e155b91db595df4e661d2cd26592035f9f. Android compile/JVM run45311999858 SUCCESS.

Whole-source demand review previously left AggregateQuery as an unranked concurrency candidate. Its implementation submitted a new queued server count for every get, unlike the existing document/query/engagement active-flight paths. The corrected implementation shares only an active identical actor/parent/filter count operation before executor submission. It freezes the exact filters at submission and checks actor identity before and after transport. Aggregate results contain only an immutable final count.

No completed result is cached; a subsequent count after completion always starts fresh server work. No TTL, local estimate, ACL cache or count-page substitution. Document mutations and collection pushes detach count flights together with ordinary query/listener flights, so a post-mutation caller does not join pre-mutation work. Different actors, parents and filters stay separate.

Verification:
- Actual AggregateQuery.get with a real flight registry and controlled store executor:101 overlapping calls ->1 queued operation ->1 count transport; the next call after completion ->fresh second transport.
- Tests isolate actor, collection and filter differences and both document/collection invalidations.
- Account switches before queue execution invoke no transport; account changes during transport cancel the result.
- Android full compile/JVM45311999858 SUCCESS;434 Python tests PASS; diff check PASS.

This is a controlled reproducible source-demand fix, not evidence that concurrent counts are a leading production consumer. Legitimate sequential fresh counts and server authorization remain. No APK build, Worker/schema change or PR merge. APK178 lacks this fix and the preceding verified client changes; installed-client savings and <=100kreads/<=5kwrites/day acceptance remain unverified.
