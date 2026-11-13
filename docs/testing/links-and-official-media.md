# Global clickable links + official broadcast media — v2.8.16 (48)

## Complete implementation files

- `app/src/main/java/com/friendbook/social/core/LinkUtils.java`: reusable `LinkUtils.makeLinksClickable(TextView)`; WEB_URLS, blue #1A73E8, simple breaking/no hyphenation, safe ACTION_VIEW browser spans, hashtag-preserving spans, idempotent watcher, span-only touch handling, original click/focus flags preserved.
- `core/WebUrlPolicy.java`: only http(s) with an authority; rejects intent/file/javascript URLs.
- `core/Ui.java`: shared TextView factory installs the helper, covering programmatic content throughout this Java app, including group FeedAdapter/CommentsAdapter, Team chats and post detail headers. Explicit bind calls also cover chat, post/comment, reel caption and profile bio. Fullscreen media now renders the passed caption with links.
- `app/src/main/res/values/link_ids.xml`: watcher ID and reusable `ClickableWebText` XML style (autoLink=web, blue links, simple breaking). Existing layout XML contains activity containers and SDK-owned native ad views, not post/comment/caption/bio layouts; ad SDK text is deliberately not rewritten. No Compose screens are present.
- `admin/OfficialBroadcastActivity.java`: Admin Panel broadcast composer, SAF photo/video picker, preview/removal, upload progress/errors, Firebase Storage upload once under `official_broadcasts/{adminUid}/{broadcastId}`, job status.
- `admin/OfficialBroadcastWorker.java`: authenticated, network-constrained background sending; document-ID pagination (100 users/page, no old 400-user cutoff), per-recipient Firestore transactions. Creates notification + official Team room/message together. Stable IDs and existence checks avoid duplicate chat/unread increments on replay. Progress counts are accounts processed on that pass (including existing recipients), not phone receipts.
- `models/Models.java`, `chat/MessageRenderState.java`, `chat/MessagesAdapter.java`: videoUrl parsing/render signature, image above text, inline image or video play tile, linked caption, tap media -> fullscreen viewer. Videos play with controls in fullscreen, not autoplay inside recycled rows. Old text-only official messages still work.
- `firebase/firestore.rules`: admin-only broadcast specifications/progress. `firebase/storage.rules`: admin-only official writes; generic wildcard explicitly excludes this path to prevent overlapping permissive rules bypassing admin checks.

## One call in any adapter

```java
holder.caption.setText(item.caption);
LinkUtils.makeLinksClickable(holder.caption);
```

Or call once when constructing the view; its watcher relinks future setText calls. Repeated calls do not accumulate watchers. Use the helper after constructing styled hashtag/mention text; existing non-URL ClickableSpans are retained. For future XML user-content views:

```xml
<TextView
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    style="@style/ClickableWebText" />
```

Still call the helper in binding for safe browser spans and recycled text. Long URLs remain intact/clickable; they MUST wrap if wider than the device. `breakStrategy=simple` does not guarantee single-line display, and forcing that would clip URLs or require horizontal scrolling.

## Required deployment / billing boundary

The rule changes are source files, not an automatic live deployment. From an authorized Firebase CLI session:

```sh
firebase deploy --only firestore:rules,storage
```

Review the existing rules before deployment. Storage's Firestore-based admin lookup may require granting its cross-service permissions when deploying. Normal/moderator upload attempts must be denied; full admins may upload. Shared media is stored once, not once per recipient. Download URLs are shareable bearer URLs; this feature does not promise private encrypted media.

Firebase Storage must already have an accessible bucket and supported billing configuration. Firebase may require Blaze for Storage access; this implementation does NOT enable billing, add Cloud Functions, or bypass that requirement. If unavailable, attachment upload fails visibly without sending a text-only substitute. Text-only broadcast still uses Firestore. Uploads must finish/queue successfully before leaving the composer; an interrupted upload after process death may need retry. Queued fan-out retries in WorkManager while the originating admin account is signed in. Signing out/force-stop can interrupt client-only work. Existing direct-FCM notification delivery is not redesigned or claimed verified here: completion means in-app notification/chat persistence, NOT a push receipt.

## Tests and device acceptance

Automated: LinkUtilsTest (Robolectric) covers empty creation -> later bind, repeated helper calls, rebinding away old URLs, naked www URLs, hashtag retention, safe ACTION_VIEW, long URL integrity and retaining parent click flags. WebUrlPolicyTest rejects unsafe schemes. Python wiring guards cover shared/bind integration, video cache keys, pagination/replay checks and rule wildcard isolation. Existing badge/push guards remain.

Device checklist: tap http/https/www links in private + Team chat, image broadcast caption, text/color-background post, all post/photo/video/reel comment/reply screens, reel description, profile bio, group feed/comment and fullscreen captions. Recycle/rebind rows repeatedly; old URLs must disappear. Check non-link caption expand, hashtag tap, scroll, long-press actions and dark/light themes. No browser installed must show a friendly error. Pick/cancel/replace/remove image and video; upload failure, app background/resume, text-only, media-only, >400 users, replay, partially completed sending and non-admin access should behave correctly. Check video tap playback and linked text independently. No physical-device acceptance is implied by structural tests.
