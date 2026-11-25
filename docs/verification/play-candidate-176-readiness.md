# Signed Play candidate 176: technical validation

Validation run: https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45177136355 — SUCCESS.

The workflow downloaded the existing signed AAB from successful build 45166501055; it did not rebuild or change its Android source. It verified:

- JAR signature verification succeeds.
- Google bundletool 1.18.1 validates the AAB.
- Manifest package is com.friendbook.social, version 2.10.34 / 176.
- Target SDK is at least 35 (source config is 36).
- Portrait and Wi-Fi hardware requirements are explicitly optional.

The run contains downloadable artifact `validated-play-candidate-176`, including the AAB, extracted manifest and SHA256SUMS. Download through the Actions page; downloading locally in this sandbox failed at the artifact storage host (EOF), not at the validation job.

Fresh local release regression: 251 Worker tests and 375 Python tests passed. No app runtime change, database migration, Play upload, GitHub release publication or PR merge occurred in this follow-up.

## Not certified by this check

This does not certify Play review approval, Data safety answers, privacy policy, listing assets, account testing eligibility, signing-key match with an existing Play listing, version-code availability in that listing, or full Android device compatibility. Those require Play Console and/or device evidence. No Play Console connection is available to this workflow.

Quota work remains separate: compatibility inbox retrieval is unbounded before JS sort/page, and mirror write caller attribution is incomplete. A full post-rollout day below 100,000 D1 reads and 5,000 writes has not been established. See remaining-consumer-attribution.md. Do not label the release as all quota work complete.
