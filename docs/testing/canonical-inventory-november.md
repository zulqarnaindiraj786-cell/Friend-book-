# Canonical coverage and bounded user promotion

Production aggregate inventory run 45020790757 sampled the first 5,001 legacy rows by path and was truncated. In that sample the legacy-only totals included 319 chats, 896 messages, 1,381 notifications, 2 friend requests, 1 friendship, 10 groups, 1 live and 431 other records. These are minimum sample counts, not a complete database inventory. It reported 16,963 D1 rows read. Removing legacy reads after copying only users would hide data, including chat history.

Targeted users inventory run 45020882816 found 424 legacy user paths, was not truncated, and found 2 without canonical rows. Its reported cost was 1,270 rows read. Read-only preview 45021216684 validated both candidates against the legacy document and authorization projections with no writes. Promotion run 45021256173 used an atomic, guarded insert for both; it reports **legacy_only_before=2, inserted=2, legacy_only_after=0**, and re-read and compared every canonical field. The legacy rows were not deleted. Follow-index triggers continue to run. To preserve each source document, a redundant `id` field equal to the path ID is retained in the promoted row; the previous Firestore facade stripped that field from legacy user data, so it may now appear there in addition to the existing document ID. The older document and authorization projections were checked for equality. Concurrent source/canonical changes prevent the entire guarded insert.

Account-wide adaptive analytics reported 1,314,352 reads and 56,795 writes around these checks; these figures are delayed and cannot identify per-query billing or establish a daily forecast. Promotion's actual incremental bill was not isolated. Old and new canonical coverage are point-in-time observations; old clients or other writers may continue to create legacy-only records. The rest of the data needs a separately designed, bounded and verified backfill before canonical-only cutover.

## Full bounded inventory and projection preview

Read-only run 45021996256 scanned 10,473 rows (cap 25,001; not truncated) in a single D1 statement and reported 37,224 rows read. These are root-path shadow counts at that instant, not a long-term coverage guarantee. Subsequently two friend requests and one friendship were promoted and verified by runs 45022557828 and 45022749353; no other collections were promoted after the user-only promotion.

| Collection | Legacy-only at inventory | Backfilled since user promotion | Legacy-only after |
|---|---:|---:|---:|
| users | 0 | 0 (2 in prior promotion) | 0 at inventory |
| chats | 318 | 0 | not yet rechecked |
| comments | 422 | 0 | not yet rechecked |
| friend_requests | 2 | 2 | 0 (run 45022557828) |
| friends | 1 | 1 | 0 (run 45022749353) |
| groups | 10 | 0 | not yet rechecked |
| lives | 1 | 0 | not yet rechecked |
| messages | 896 | 0 | not yet rechecked |
| notifications | 2,407 | 0 | not yet rechecked |
| posts | 598 | 0 | not yet rechecked |
| reels | 3 | 0 | not yet rechecked |
| stories | 1 | 0 | not yet rechecked |
| other (unknown collection names grouped to avoid disclosing arbitrary data) | 9 | 0 | not yet rechecked |
| config, gifts, officialBroadcasts, pages, payments, reports | 0 each | 0 | not yet rechecked |

Read-only moving scan 45022108993 evaluated 4,668 unshadowed root records without storing or logging their data; it reported 16,278 D1 rows read. Friend-request preview 45022529063 confirmed both candidates passed the bounded source and projection checks; promotion 45022557828 inserted both atomically and verified all canonical columns and zero remaining. As with the user promotion, a redundant matching `id` is retained, so the Firestore facade now exposes that field where it previously stripped it. Read/delete authorization on representative requests is covered by local tests. Friendship preview 45022699325 and promotion 45022749353 similarly verified one candidate and 1→0 coverage. That row also retains its matching `id`; the Firestore facade and friendship view may now include that field. Local tests check that membership authorization remains unchanged. This is a paginated moving scan rather than an atomic snapshot. Of those, 4,531 preserve the old plain and authorization projections with a matching redundant `id` preserved, but would add that `id` to the old Firestore facade projection. Another 101 comments failed authorization-field projection equivalence; 34 comments and 2 reels failed the old plain projection comparison. These categories do not establish trigger fanout safety, and no bulk write was authorized by the preview. Exactly reproducing all projections and testing dependent authorization/query paths remain prerequisites to safely promote them.

A disposable notification fixture compares cursor pages, unread filtering, privacy shadows and full response projections before and after promoting matching-ID legacy rows; it passes with the documented added `id`. This is useful local coverage, not proof for all production records or permission to bulk-write them.

Root-path coverage is not enough for messages: the live code also synthesizes nested `chats/{chatId}/messages/{messageId}` from legacy root messages using an ID prefix or `chatId`. It keeps any existing nested message in preference to the legacy one. A promotion must model that separate alias mapping, potential prefix ambiguity and conflicts, and concurrent changes; simply copying the root `messages/…` path or observing zero root-only paths does not justify deleting the chat bridge. Retain the fallback.
