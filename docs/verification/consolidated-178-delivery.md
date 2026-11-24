# Owner-authorized consolidated Android 178

The owner explicitly requested the APK after checking remaining large consumers. No speculative additional source patch was added during packaging.

- Version **2.10.36 (178)**, source **3867a0aa431890ccc9d6e67ae63d6d24fa2e8752**.
- Signed APK+AAB workflow **45293481597 SUCCESS**. Release JVM build/tests, actual Android core-result gate, APK/AAB signature verification, emulator/gateway and source guards succeeded.
- Pre-build local validation: **428 Python / 297 Worker passed**, 48-workload replay completed successfully, diff check passed.
- Candidate (prerelease, not Latest): https://github.com/zulqarnaindiraj786-cell/Friend-book-/releases/tag/friendbook-2.10.36-signed-3867a0aa
- APK: https://github.com/zulqarnaindiraj786-cell/Friend-book-/releases/download/friendbook-2.10.36-signed-3867a0aa/friendbook.apk
- AAB: https://github.com/zulqarnaindiraj786-cell/Friend-book-/releases/download/friendbook-2.10.36-signed-3867a0aa/friendbook-playstore.aab

GitHub release API independently verified uploaded asset state, size and SHA256 digest:

| Asset | Bytes | SHA256 |
|---|---:|---|
| APK | 4,972,273 | 02f59598d11797de06973295279c3ca560f48604b8e8853420a72d328eda50ed |
| AAB | 8,855,437 | 36279e6109bd5d4ee4aae1b838d4f981923c0e39ac01497e56615e5606998c2c |

Sandbox download hit the known release-asset-host EOF; no local binary download/hash verification is claimed. Use release download links. Mapping and checksums also attached to the release.

Includes cumulative Android operation sharing (documents, queries, engagement), initial/delta page sharing, short search-head reuse, ordered people prefixes and profile bundled-engagement consumption. Source-only server fixes (canonical GET/list authorization N+1, legacy GET, zero-visible blocked-history scans) are **not activated by installing this APK**; the production Worker has not been deployed in this turn. No migration, merge, Play upload, Latest change, automatic install or automatic update publication was performed.

This is a tested signed candidate, not evidence of installed-client/full-day <=100k reads/<=5k writes acceptance. All four consumer families still contain legitimate reads; production billed savings remain unverified.
