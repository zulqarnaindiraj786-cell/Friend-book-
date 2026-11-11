// Post-deploy diagnostics for the Analytics Engine view counters.
//
// It answers three questions with RAW API output, so the counters can be verified instead of
// assumed: (1) did the deployed Worker really get the AE/KV bindings, (2) can this account query
// Analytics Engine over SQL, (3) can it query them over GraphQL. The result is written to a single
// updating comment on the open PR (marker below) because CI logs are not always reachable.
//
// Best effort: any failure prints a warning and exits 0. Diagnostics must never break a release.
const MARKER = '<!-- friendbook-views-ae-report -->';
const WORKER = 'https://rough-firefly-dd7b.zulqarnaindiraj786.workers.dev';
const DATASET = 'friendbook_views';
const WINDOW_DAYS = 90;
const LIMIT = 5;

const repo = process.env.GITHUB_REPOSITORY || '';
const branch = process.env.GITHUB_REF_NAME || '';
const token = process.env.GITHUB_TOKEN || '';
const account = process.env.CLOUDFLARE_ACCOUNT_ID || '';
const cfToken = process.env.CLOUDFLARE_API_TOKEN || '';

const warn = (message) => console.log(`::warning::${message}`);
const clip = (value, max = 1200) => {
  const text = typeof value === 'string' ? value : JSON.stringify(value, null, 2);
  if (text === undefined) return '(undefined)';
  return text.length > max ? `${text.slice(0, max)}\n... truncated ${text.length - max} chars` : text;
};
const fence = (text) => '```\n' + clip(text).replaceAll('```', "'''") + '\n```';

const SQL_PROBES = [
  `SELECT count() AS viewers FROM ${DATASET}`,
  `SELECT index1 AS resource, uniq(blob3) AS viewers, count() AS plays FROM ${DATASET}`
    + ` WHERE timestamp >= now() - INTERVAL ${WINDOW_DAYS} DAY GROUP BY index1`
    + ` ORDER BY viewers DESC LIMIT ${LIMIT}`,
  `SELECT index1 AS resource, uniqExact(blob3) AS viewers FROM ${DATASET} GROUP BY index1 LIMIT ${LIMIT}`,
];
const GRAPHQL_PROBE = `query ($tag: string!, $since: datetime!, $until: datetime!) {
  viewer {
    accounts(filter: { accountTag: $tag }) {
      views: ${DATASET}(
        filter: { datetime_geq: $since, datetime_ltq: $until }
        limit: ${LIMIT}
        orderBy: [countDesc]
      ) {
        dimensions { name count uniq }
        metrics { count sum }
      }
    }
  }
}`;

async function probeHealth() {
  try {
    const response = await fetch(`${WORKER}/health`, { headers: { Accept: 'application/json' } });
    return { status: response.status, body: await response.text() };
  } catch (error) {
    return { status: 0, body: `fetch failed: ${error.message}` };
  }
}

async function probeSql(sql) {
  if (!account || !cfToken) return { status: 0, body: 'CLOUDFLARE_ACCOUNT_ID/CLOUDFLARE_API_TOKEN unavailable to this step' };
  const url = `https://api.cloudflare.com/client/v4/accounts/${encodeURIComponent(account)}`
    + `/analytics_engine/sql?query=${encodeURIComponent(sql)}`;
  try {
    const response = await fetch(url, { headers: { Authorization: `Bearer ${cfToken}` } });
    return { status: response.status, body: await response.text() };
  } catch (error) {
    return { status: 0, body: `fetch failed: ${error.message}` };
  }
}

async function probeGraphql() {
  if (!account || !cfToken) return { status: 0, body: 'CLOUDFLARE_ACCOUNT_ID/CLOUDFLARE_API_TOKEN unavailable to this step' };
  const until = new Date();
  const since = new Date(until.getTime() - WINDOW_DAYS * 86_400_000);
  try {
    const response = await fetch('https://api.cloudflare.com/client/v4/graphql', {
      method: 'POST',
      headers: { Authorization: `Bearer ${cfToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        query: GRAPHQL_PROBE,
        variables: { tag: account, since: since.toISOString(), until: until.toISOString() },
      }),
    });
    return { status: response.status, body: await response.text() };
  } catch (error) {
    return { status: 0, body: `fetch failed: ${error.message}` };
  }
}

async function findPull() {
  if (!repo || !branch || !token) return null;
  const [owner, name] = repo.split('/');
  const list = await fetch(
    `https://api.github.com/repos/${owner}/${name}/pulls?state=open&head=${encodeURIComponent(`${owner}:${branch}`)}&per_page=5`,
    { headers: { Authorization: `Bearer ${token}`, Accept: 'application/vnd.github+json', 'User-Agent': 'friendbook-ae-report' } },
  );
  if (!list.ok) return null;
  const pulls = await list.json();
  return Array.isArray(pulls) && pulls.length > 0 ? { owner, name, number: pulls[0].number } : null;
}

async function publish(body) {
  const pull = await findPull();
  if (!pull) { warn('No open pull request found for this branch; printing the report to the log instead.'); console.log(body); return; }
  const headers = { Authorization: `Bearer ${token}`, Accept: 'application/vnd.github+json', 'User-Agent': 'friendbook-ae-report' };
  const base = `https://api.github.com/repos/${pull.owner}/${pull.name}/issues/${pull.number}/comments`;
  try {
    const existing = await fetch(`${base}?per_page=100`, { headers });
    const comments = existing.ok ? await existing.json() : [];
    const match = Array.isArray(comments) ? comments.find((entry) => String(entry?.body || '').includes(MARKER)) : null;
    if (match) {
      const updated = await fetch(`${base}/${match.id}`, {
        method: 'PATCH', headers, body: JSON.stringify({ body }),
      });
      console.log(updated.ok ? `Updated PR #${pull.number} comment ${match.id} with the Analytics Engine report.` : `Could not update the comment (HTTP ${updated.status}).`);
      return;
    }
    const created = await fetch(base, { method: 'POST', headers, body: JSON.stringify({ body }) });
    console.log(created.ok ? `Posted the Analytics Engine report to PR #${pull.number}.` : `Could not post the report (HTTP ${created.status}).`);
  } catch (error) {
    warn(`Could not publish the report (${error.message}); printing it to the log instead.`);
    console.log(body);
  }
}

const health = await probeHealth();
const sqlResults = [];
for (const sql of SQL_PROBES) sqlResults.push({ sql, result: await probeSql(sql) });
const graphql = await probeGraphql();

const lines = [
  MARKER,
  '### Analytics Engine view counters - deployed diagnostics',
  '',
  `Run: \`${process.env.GITHUB_RUN_ID || 'unknown'}\` on \`${branch || 'unknown'}\` (${new Date().toISOString()}).`,
  'Every view is one Analytics Engine event and the count is AE `uniq(uid)`, so D1 receives **zero**',
  'writes from the view path. This comment is refreshed by each deploy so the raw API answers stay visible.',
  '',
  '**Deployed Worker `/health`** (proves which bindings reached production)',
  `HTTP ${health.status}`,
  fence(health.body),
  '',
  '**Analytics Engine SQL probes** (`GET /accounts/{id}/analytics_engine/sql`)',
];
for (const entry of sqlResults) {
  lines.push('', `- \`HTTP ${entry.result.status}\` for:`, '```sql', entry.sql, '```', fence(entry.result.body));
}
lines.push('', '**Analytics Engine GraphQL probe** (`POST /client/v4/graphql`)', `HTTP ${graphql.status}`, fence(graphql.body));

await publish(lines.join('\n'));
process.exit(0);
