# Engagement: zero-visible blocked-history subtraction

Source **c548a3a**. Targets the existing engagement summary/preview consumer (4,261 reads in the latest reported six-hour window), not a newly ranked family.

Even after the earlier zero-preview fix, the visible-count expression subtracted blocked authors by seeking their canonical comments and joining removal details whenever the viewer had blocks. When the authoritative visible-comment total is zero, this still traversed removed blocked-author history only to subtract zero.

The totals-schema path now reads the maintained total once in a non-flattened subquery and skips blocked-author subtraction only when that total equals zero. Positive totals still execute the exact indexed blocked-author subtraction; DISTINCT still prevents duplicate blocked IDs counting twice. The old-schema count path remains unchanged. No migration, new counter/index/trigger, TTL, ACL shortcut or write was added.

Verification:
- **296 Worker tests passed**.
- New actual summary-SQL test seeds 3,000 removed comments by a blocked author and uses a local SQLite probe. The counterfactual forces the former unconditional subtraction branch: >=3,000 visits. Optimized branch: **0 visits**, identical full summary.
- Adding a visible normal-author comment and a visible blocked-author comment restores subtraction; visible count is exactly one and preview shows the permitted comment. Duplicate blocked IDs do not double-subtract.
- **428 Python tests passed**; **48-workload quota replay completed successfully**; diff check passed.
- Local probe counts are not D1 billed reads, and the prevalence of zero-total removed histories among the 4,261 production engagement reads is unknown. No all-engagement percentage savings claim.
- No Android changes; no APK/AAB, deployment, migration or merge. Representative-day quota acceptance remains unverified.
