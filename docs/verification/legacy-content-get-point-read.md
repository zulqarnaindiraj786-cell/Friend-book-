# Remaining legacy content-GET duplicate point read

Source **75d1e0d**. The earlier canonical GET fix covered `/v1/firestore/document`; `/v1/docs/{collection}/{id}` still separately loaded the canonical content row and reloaded it in authorizePost. This patch closes that route-specific omission for posts/reels/stories using the same internal raw canonical-fields argument.

Only fields read directly by readPreferredDocumentJson in this request can seed the ACL. The returned document and content authorization now use that same observation. No client JSON, projected row, stored authorization result or TTL is trusted. Viewer/author/group/friendship dependencies retain existing checks. Cross-dependency snapshot atomicity is not claimed.

Verification:
- New actual legacy-handler test for all three content roots asserts **one root SELECT per GET** and checks returned content, private-viewer rejection, owner access, and fresh author block/ban/deactivation rejection on subsequent requests.
- **297 Worker tests passed**, **428 Python passed**, **48-workload quota replay completed successfully**, diff check passed.
- No Android changes; no package/deploy/migration/merge. Production invocation frequency of the legacy route is unknown, so no percentage of the aggregate 6,769 point reads is claimed saved.

Current source coverage: new/legacy content GET and bounded/generic/legacy content lists have exact-request canonical snapshot reuse. Required fresh profile, block, group and friendship reads remain. This is not proof that all point traffic is redundant or eliminated; installed-client/full-day quota acceptance is still pending.
