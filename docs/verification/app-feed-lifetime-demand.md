# Feed lifecycle and repeated demand verification

Source verified: f0f0b7e. Android compile/full JVM workflow 45331457582 succeeded. Python suite: 451 passed. No APK packaged, merge, backend deployment, or device quota measurement.

## Fixed
- Repainting flagged cached posts keeps local hiding but submits remote moderation only once per actor/collection/document per process, including transport failures. This is not durable exactly-once delivery; failures are not automatically retried.
- Hide, stop and view destruction invalidate pending Home ownership. Lives, friends and notes responses check actor and visible-session generation. Old notes responses cannot unlock a newer request.
- Interrupted first Stories and Suggestions loads can be requested on visible reentry. Hidden pagination is rejected.
- Refresh while a page is pending returns without starting another load or changing ranking generation.
- Confirmed empty server feeds clear saved posts; cached paints do not overwrite saved feed state.
- Account transitions clear old feed models, cursor, section models and visible adapter data before new reads.

## Behavioral regressions
- FeedSafetyDemandTest: 100 cache paints bound remote moderation to one action each; account change cannot cause an old completion to delete as the new actor.
- HomeLateReadDemandTest: old visible-session response cannot repaint a newer session; interrupted initial sections become retryable; 100 hidden pagination demands do not access the database.
- HomeRefreshDemandTest: 100 refresh gestures during a pending page start no work; confirmed empty feed clears cache once.
- HomeAccountScopeTest: actor transition clears old models and cursor without a database request.

## Still open
The complete Feed acceptance checklist is not signed off. Remaining consolidated audit includes adapter author hydration, observer feedback, reaction/comment/share-driven reload paths, cache/delta/page boundaries and bounded failure behavior. These tests establish the listed regressions only, not measured production reads/writes or resolution of the reported rapid quota consumption. No intermediate download or owner trace is requested.
