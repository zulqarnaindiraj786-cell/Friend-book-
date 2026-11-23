# FriendBook slow-mobile comparison

Use existing build 145 as the current baseline. Only label a comparison “Firebase” if the older APK is confirmed to use Firebase for the specific flow tested. Use the same two devices, accounts, conversation, feed fixtures, clip, and network settings. Alternate version order; separate cold and warm runs. Keep chat and inbox tests separate because a notification is not a rendered message.

| Flow | Start | Stop |
|---|---|---|
| Chat receive | Tap Send on A | Unique test message visible in B's open chat |
| Inbox receive | Tap Send on A | B's inbox shows matching preview, unread count and updated rank |
| Feed | Open app from launcher | First usable post appears |
| Reel | Settle swipe on target Reel | First rendered video frame (poster does not count) |
| Explicit full playback | Tap the Reel | First rendered full-quality frame |

Record both devices in one video for chat timing to avoid clock skew. Use a test account and harmless numbered messages. Run at least 20 times per flow per build on the same slow mobile data profile. Report median, p95, and timeout count per flow; count timeouts separately rather than dropping them silently. For Reels, verify the preview request occurs before tap and no full request occurs until tap. Record transferred bytes separately from decoded duration or file size. Also verify one on-device HEVC export, H.264 fallback on an unsupported device, and that older URLs still play.

## Measured so far

The local 18-second synthetic clip encoded to 436,806 bytes for the preview and 1,983,162 bytes for full. Preview-only transfer would use 78.0% fewer bytes than loading the full asset for this clip. This is a file-size comparison, not a handset first-frame or Firebase speed result. The two R2 encodings combined occupy 2,419,968 bytes for the sample. Android encoding, actual mobile data timings, and a Firebase baseline remain unverified.

Local slow-stream check: fed both MP4s to FFmpeg as sequential input at 256 kbps (3,200 bytes per 100 ms). Three runs each gave median first decoded frame at 0.71 s for preview and 1.51 s for full. This excludes Android UI, network round trips, and CDN behavior and is not a comparison to TikTok or Facebook.
