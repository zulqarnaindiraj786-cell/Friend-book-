# Owner's updated FINAL video policy — Save Forever in R2 (2026-11-05)

This updates the video storage policy after the owner explicitly canceled both
30-day auto-deletion and the 10 GB upload hard stop.

## Owner-authorized rules

1. Cloudinary is used for 480p video compression processing only. Temporary
   processing assets created under `friendbook_r2_processing/` are destroyed
   after verified R2 transfer; legacy Cloudinary originals are never touched.
2. Final saved video bytes go to existing R2 bucket `friendbook`.
3. Final playback URLs for newly uploaded videos use only:
   `https://pub-88ab0a56178742af95cbbf62df152a67.r2.dev/videos/<owner>/<sha256>.mp4`.
4. New video uploads never fall back to publishing a Cloudinary video URL.
5. No 30-day auto-delete and no 10 GB bucket hard stop: saved R2 videos are
   retained (`retention=forever`), and active age-based object expiration on R2
   bucket `friendbook` is removed during setup preflight while preserving any
   multipart-abort or storage-class rules.
6. Because the 10 GB upload stop is removed, R2 storage/operation usage above
   Cloudflare's monthly free tier can incur Cloudflare charges. AdMob revenue is
   not guaranteed to cover R2 charges.

## Activation & credential safety

- Worker route `/v1/videos` requires a verified Firebase ID token, enforces a
  per-upload 99 MB transport bound, verifies 480p MP4 output + MD5 integrity,
  writes immutable R2 objects (`etagDoesNotMatch: '*'`), verifies `HEAD`, and
  returns only the R2 URL.
- Live activation requires repository secrets `CLOUDINARY_API_KEY` and
  `CLOUDINARY_API_SECRET` in GitHub Actions (plus existing `CLOUDFLARE_API_TOKEN`)
  so the Worker can sign 480p processing and destroy temporary Cloudinary
  processing assets server-side. Never expose secrets in APK code, git, or chat.
