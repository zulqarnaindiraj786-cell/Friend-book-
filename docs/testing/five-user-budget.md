# Five-user daily D1 budget (from this codebase)

`python3 -m scripts.loadtest.five_user_budget` prints the model; every cost is a
limit that exists in the SQL, not a guess about traffic.

| Per user per day | Rows read |
| --- | ---: |
| 20 app opens × (feed 30 + badges 32/100/32 + me + config) | 3,920 |
| 40 feed pages × 40 | 1,600 |
| 45 received messages × (30 messages + room doc) | 1,530 |
| 10 chat-list opens × 100 cap | 1,000 |
| 30 sends × (room auth + membership + room doc) | 360 |
| 5 notification opens × 32 | 160 |
| 20 profile opens × 3 | 60 |
| **Total** | **8,630** |

Five users: **43,630 reads/day** plus 96 cron runs × 5 lookups = **44,110**
(limit 100,000).

| Per user per day | Row writes |
| --- | ---: |
| 30 messages × 2 tables | 60 |
| 30 chat previews × 2 tables | 60 |
| 30 notifications × 2 tables | 60 |
| 50 reactions × 2 | 100 |
| 22 receipts × 2 | 45 |
| posts/comments 10 × 2 | 20 |
| settings 5 × 2 | 10 |
| live heartbeats 10 × 2 | 20 |
| **Total** | **375** |

Five users: **1,875 writes/day**, and the 15-minute cron only writes when a row
is actually stale, so it adds nothing on a quiet day (limit 5,000).

Both targets hold. The largest remaining read consumer is the app-open badge set
(3,920/user/day): the notification and friend-request windows are 32 each and the
chat badge is capped at 100, so no query grows with account size any more.
