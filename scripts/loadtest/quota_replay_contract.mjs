import assert from 'node:assert/strict';

// Offline synthetic-workload contracts, NOT D1 billing or production quotas.
// Explicitly include query-over-POST routes: HTTP method cannot establish purity.
export const READ_ONLY_WORKLOADS = Object.freeze([
  'emptyMentionPrefix','asciiMentionPrefix','unicodeMentionPrefix','unicodePeopleSearch',
  'paymentOwnerPage','page-posts','page-reels','feedEngagementFive','page-stories',
  'page-lives','page-notifications','profileDetail','numericNotificationPageAll',
  'numericNotificationPageUnread','privateGroupProjection','tagSearch',
  'mirroredPeoplePage','followListMembership','canonicalCommentPage','multiTypeReactionSummary',
]);

export function assertReadOnlyWorkloads(report) {
  for (const name of READ_ONLY_WORKLOADS) {
    const result=report.workloads?.[name];
    assert.ok(result, `Missing required read workload: ${name}`);
    assert.ok(Number.isSafeInteger(result.requests) && result.requests>0,
      `Read workload did not execute: ${name}`);
    assert.equal(result.changedRowsIncludingTriggers,0,
      `Read workload mutated data (including triggers): ${name}`);
  }
}
