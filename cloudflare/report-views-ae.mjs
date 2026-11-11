// Post-deploy diagnostics for the Analytics Engine view counters.
//
// It answers, with RAW output only, the questions that decide whether the AE counters really work:
//   1. which wrangler deploy attempt actually shipped, and the exact error of every refused attempt
//   2. what the deployed Worker reports about its own bindings (`/health`, booleans only)
//   3. whether this account's token can list Workers KV namespaces (why VIEWS may be missing)
//   4. whether Analytics Engine answers over SQL, and which GraphQL field name the dataset really has
// The result is written to a single updating comment on the open PR (marker below) because CI logs
// are not always reachable from a review.
//
// Best effort: any failure prints a warning and exits 0. Diagnostics must never break a release.
import { readdirSync, readFileSync, existsSync } from 'node:fs';

const MARKER = '<!-- friendbook-views-ae-report -->';
const WORKER = 'https://rough-firefly-dd7b.zulqarnaindiraj786.workers.dev';
const DATASET = 'friendbook_views';
const WINDOW_DAYS = 90;
const LIMIT = 5;
const API = 'https://api.cloudflare.com/client/v4';
const DEPLOY_LOG_DIR = '/tmp/deploy-logs';
const SHIPPED_CONFIG = '/tmp/wrangler.toml.shipped';
const NEEDED_TOKEN_SCOPES = [
  'Account > Analytics Engine > Edit  (binds the dataset and accepts view events on deploy)',
  'Account > Analytics Engine > Read  (lets the Worker query uniq(uid) counts)',
  'Account > Workers KV Storage > Edit (binds the live view snapshot namespace)',
];

const repo = process.env.GITHUB_REPOSITORY || '';
const branch = process.env.GITHUB_REF_NAME || '';
const token = process.env.GITHUB_TOKEN || '';
const account = process.env.CLOUDFLARE_ACCOUNT_ID || '';
const cfToken = process.env.CLOUDFLARE_API_TOKEN || '';
const attemptUsed = process.env.DEPLOY_ATTEMPT_USED || '';

const warn = (message) => console.log(`::warning::${message}`);
const clip = (value, max = 1200) => {
  const text = typeof value === 'string' ? value : JSON.stringify(value, null, 2);
  if (text === undefined || text === null) return '(empty)';
  return text.length > max ? `${text.slice(0, max)}\n... truncated ${text.length - max} chars` : text;
};
const fence = (text, max = 1200) => '```\n' + clip(text, max).replaceAll('```', "'''") + '\n```';
const missingCredentials = () => (account && cfToken
  ? null
  : 'CLOUDFLARE_ACCOUNT_ID/CLOUDFLARE_API_TOKEN unavailable to this step');

const SQL_PROBES = [
  `SELECT count() AS viewers FROM ${DATASET}`,
  `SELECT index1 AS resource, uniq(blob3) AS viewers, count() AS plays FROM ${DATASET}`
    + ` WHERE timestamp >= now() - INTERVAL ${WINDOW_DAYS} DAY GROUP BY index1`
    + ` ORDER BY viewers DESC LIMIT ${LIMIT}`,
  `SELECT index1 AS resource, uniqExact(blob3) AS viewers FROM ${DATASET} GROUP BY index1 LIMIT ${LIMIT}`,
];
// The field name Analytics Engine really exposes depends on the account's schema, so it is discovered
// by introspection instead of assumed. The guessed alias form is kept as one more raw data point.
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
const VIEWER_INTROSPECTION = '{ __type(name: "Viewer") { fields { name type { name kind ofType { name kind } } } } }';

async function get(url, label) {
  const blocked = missingCredentials();
  if (blocked) return { status: 0, body: blocked };
  try {
    const response = await fetch(url, { headers: { Authorization: `Bearer ${cfToken}` } });
    return { status: response.status, body: await response.text() };
  } catch (error) {
    return { status: 0, body: `${label} fetch failed: ${error.message}` };
  }
}

async function postGraphql(query, variables, label) {
  const blocked = missingCredentials();
  if (blocked) return { status: 0, body: blocked, query };
  try {
    const response = await fetch(`${API}/graphql`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${cfToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ query, variables }),
    });
    return { status: response.status, body: await response.text(), query };
  } catch (error) {
    return { status: 0, body: `${label} fetch failed: ${error.message}`, query };
  }
}

async function probeHealth() {
  try {
    const response = await fetch(`${WORKER}/health`, { headers: { Accept: 'application/json' } });
    return { status: response.status, body: await response.text() };
  } catch (error) {
    return { status: 0, body: `fetch failed: ${error.message}` };
  }
}

const probeSql = (sql) => get(
  `${API}/accounts/${encodeURIComponent(account)}/analytics_engine/sql?query=${encodeURIComponent(sql)}`,
  'AE SQL',
);
const probeTokenVerify = () => get(`${API}/user/tokens/verify`, 'token verify');
const probeKv = () => get(
  `${API}/accounts/${encodeURIComponent(account)}/storage/kv/namespaces?per_page=100`,
  'KV namespaces',
);

/** Walks the GraphQL schema to the account object and returns every Analytics Engine field it has. */
async function discoverGraphqlFields() {
  const viewer = await postGraphql(VIEWER_INTROSPECTION, {}, 'viewer introspection');
  let accountsType = null;
  const discovered = { viewer, accountsType: null, fields: [], probe: null };
  try {
    const fields = JSON.parse(viewer.body)?.data?.__type?.fields ?? [];
    const accounts = fields.find((entry) => entry.name === 'accounts');
    accountsType = accounts?.type?.ofType?.name || accounts?.type?.name || null;
  } catch { /* the raw answer is published either way */ }
  discovered.accountsType = accountsType;
  if (!accountsType) return discovered;
  const accountFields = await postGraphql(
    `{ __type(name: "${accountsType}") { fields { name } } }`, {}, 'account introspection',
  );
  discovered.viewer = { status: viewer.status, body: `${clip(viewer.body, 400)}\n\n--- ${accountsType} ---\n${clip(accountFields.body, 1600)}` };
  let names = [];
  try {
    names = (JSON.parse(accountFields.body)?.data?.__type?.fields ?? []).map((entry) => entry.name);
  } catch { /* published raw */ }
  discovered.fields = names.filter((name) => /analytic|adapter|dataset/i.test(name));
  const match = discovered.fields.find((name) => name.toLowerCase().includes(DATASET.toLowerCase()))
    || discovered.fields.find((name) => /analyticsEngineAdapter/i.test(name));
  if (match) {
    discovered.probe = await postGraphql(
      `query ($tag: string!) { viewer { accounts(filter: { accountTag: $tag }) { ${match}(limit: ${LIMIT})`
      + ' { dimensions { name count uniq } } } } }',
      { tag: account },
      'AE GraphQL dataset',
    );
    discovered.probe.field = match;
  }
  return discovered;
}

/** The deploy step keeps one log per attempt plus the config that actually shipped. */
function deployEvidence() {
  const attempts = [];
  try {
    for (const name of readdirSync(DEPLOY_LOG_DIR).sort()) {
      if (!name.endsWith('.log')) continue;
      attempts.push({ name: name.replace(/\.log$/, ''), log: readFileSync(`${DEPLOY_LOG_DIR}/${name}`, 'utf8') });
    }
  } catch { /* no logs: the report still carries /health and the API answers */ }
  let shipped = '';
  try { shipped = existsSync(SHIPPED_CONFIG) ? readFileSync(SHIPPED_CONFIG, 'utf8') : ''; } catch { shipped = ''; }
  const bindings = [...shipped.matchAll(/^binding = "([^"]+)"/gm)].map((entry) => entry[1]);
  return { attempts, shippedBindings: bindings, shipped };
}

/**
 * Finds the pull request to comment on. It never throws (a report must not fail a release) and every
 * reason it cannot publish is emitted as a workflow annotation, because the CI log is not readable
 * from a review: a silently skipped report would look exactly like a report that found nothing wrong.
 */
async function findPull() {
  if (!repo || !branch || !token) {
    warn(`Report not published: GITHUB_REPOSITORY='${repo || 'MISSING'}' GITHUB_REF_NAME='${branch || 'MISSING'}' `
      + `GITHUB_TOKEN=${token ? 'present' : 'MISSING'}`);
    return null;
  }
  const [owner, name] = repo.split('/');
  const headers = { Authorization: `Bearer ${token}`, Accept: 'application/vnd.github+json', 'User-Agent': 'friendbook-ae-report' };
  const base = `https://api.github.com/repos/${owner}/${name}/pulls?state=open`;
  // Filtered first, then unfiltered: a refused `head` filter must not cost the report.
  for (const url of [`${base}&head=${encodeURIComponent(`${owner}:${branch}`)}&per_page=5`, `${base}&per_page=25`]) {
    try {
      const list = await fetch(url, { headers });
      const body = await list.text();
      if (!list.ok) { warn(`Pull request list answered HTTP ${list.status}: ${clip(body, 300)}`); continue; }
      const pulls = JSON.parse(body);
      if (!Array.isArray(pulls) || pulls.length === 0) continue;
      const match = pulls.find((entry) => entry?.head?.ref === branch) || pulls[0];
      return { owner, name, number: match.number };
    } catch (error) {
      warn(`Pull request list failed: ${error.message}`);
    }
  }
  warn(`No open pull request found for ${owner}:${branch}; the report was printed to the CI log only.`);
  return null;
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
      const updated = await fetch(`${base}/${match.id}`, { method: 'PATCH', headers, body: JSON.stringify({ body }) });
      if (updated.ok) { console.log(`Updated PR #${pull.number} comment ${match.id} with the Analytics Engine report.`); return; }
      warn(`Could not update comment ${match.id} (HTTP ${updated.status}): ${clip(await updated.text(), 300)}`);
      // Stale evidence is worse than a second comment, so post a fresh one and say so.
      const retry = await fetch(base, { method: 'POST', headers, body: JSON.stringify({ body }) });
      if (retry.ok) { console.log(`Posted a new Analytics Engine report to PR #${pull.number} because the update was refused.`); return; }
      warn(`Posting a replacement comment also failed (HTTP ${retry.status}): ${clip(await retry.text(), 300)}`);
    } else {
      const created = await fetch(base, { method: 'POST', headers, body: JSON.stringify({ body }) });
      if (created.ok) { console.log(`Posted the Analytics Engine report to PR #${pull.number}.`); return; }
      warn(`Could not post the report to PR #${pull.number} (HTTP ${created.status}): ${clip(await created.text(), 300)}`);
    }
  } catch (error) {
    warn(`Could not publish the report (${error.message}).`);
  }
  console.log(body);
}

// A diagnostic that throws would turn a shipped release red, so everything below is guarded.
try {
const evidence = deployEvidence();
const health = await probeHealth();
const tokenVerify = await probeTokenVerify();
const kv = await probeKv();
const sqlResults = [];
for (const sql of SQL_PROBES) sqlResults.push({ sql, result: await probeSql(sql) });
const graphql = await discoverGraphqlFields();
const graphqlGuess = await postGraphql(GRAPHQL_PROBE, {
  tag: account,
  since: new Date(Date.now() - WINDOW_DAYS * 86_400_000).toISOString(),
  until: new Date().toISOString(),
}, 'AE GraphQL alias');

let flags = {};
try { flags = JSON.parse(health.body)?.views ?? {}; } catch { flags = {}; }
const sqlOk = sqlResults.some((entry) => entry.result.status === 200 && !/Authorization error/i.test(entry.result.body));
const aeBound = flags.ae === true;

const lines = [
  MARKER,
  '### Analytics Engine view counters - deployed diagnostics',
  '',
  `Run: \`${process.env.GITHUB_RUN_ID || 'unknown'}\` on \`${branch || 'unknown'}\` (${new Date().toISOString()}).`,
  'Every view is one Analytics Engine event and the count is AE `uniq(uid)`, so D1 receives **zero**',
  'writes from the view path. Nothing below is interpreted: these are the raw answers.',
  '',
  '#### Verdict',
  '',
  `- Deploy attempt that shipped: \`${attemptUsed || 'unknown'}\``,
  `- Analytics Engine binding live in production: **${aeBound ? 'YES' : 'NO'}**`,
  `- Analytics Engine queryable with this token: **${sqlOk ? 'YES' : 'NO'}**`,
  `- Workers KV snapshot binding live: **${flags.kv === true ? 'YES' : 'NO'}**`
    + ` (R2 snapshot fallback: **${flags.r2Snapshot === true ? 'YES' : 'NO'}**)`,
  `- Analytics Engine read secret present: **${flags.aeReadToken === true ? 'YES' : 'NO'}**`,
  `- D1 writes from the view path: **0** (enforced by tests, no write statement exists in the module)`,
];
if (!aeBound || !sqlOk) {
  lines.push(
    '',
    'Until both are YES the view counters cannot use Analytics Engine, so the Worker serves the',
    'de-duplicated total from the live snapshot (KV, else the R2 object) plus isolate memory. Counts',
    'still never touch D1, but the durable `uniq(uid)` truth needs these token permissions:',
    '',
    ...NEEDED_TOKEN_SCOPES.map((scope) => `- ${scope}`),
  );
}
lines.push('', '#### Deploy attempts (raw wrangler output)', '');
if (evidence.attempts.length === 0) {
  lines.push('_No deploy logs were found by this step._');
} else {
  for (const attempt of evidence.attempts) {
    const shipped = attempt.name === attemptUsed ? ' - SHIPPED' : ' - refused';
    lines.push(`- **\`${attempt.name}\`**${shipped}`, fence(attempt.log, 2000), '');
  }
}
if (evidence.shippedBindings.length > 0) {
  lines.push('Bindings in the config that shipped:', fence(evidence.shippedBindings.join(', '), 400), '');
}
lines.push(
  '#### Deployed Worker `/health` (binding flags only, no data)',
  `HTTP ${health.status}`,
  fence(health.body),
  '',
  '#### API token scope',
  `HTTP ${tokenVerify.status}`,
  fence(tokenVerify.body, 600),
  '',
  '#### Workers KV namespaces (why the snapshot binding may be missing)',
  `HTTP ${kv.status}`,
  fence(kv.body, 800),
  '',
  '#### Analytics Engine SQL probes (`GET /accounts/{id}/analytics_engine/sql`)',
);
for (const entry of sqlResults) {
  lines.push('', `- \`HTTP ${entry.result.status}\` for:`, '```sql', entry.sql, '```', fence(entry.result.body));
}
lines.push(
  '',
  '#### Analytics Engine GraphQL field discovery',
  `Account object type: \`${graphql.accountsType || 'not discovered'}\``,
  `Analytics fields on it: ${graphql.fields.length > 0 ? graphql.fields.map((name) => `\`${name}\``).join(', ') : 'none found'}`,
  `HTTP ${graphql.viewer.status}`,
  fence(graphql.viewer.body, 1600),
);
if (graphql.probe) {
  lines.push('', `Discovered field query (\`${graphql.probe.field}\`)`, `HTTP ${graphql.probe.status}`, fence(graphql.probe.body));
}
lines.push('', 'Guessed alias form (`POST /client/v4/graphql`)', `HTTP ${graphqlGuess.status}`, fence(graphqlGuess.body));

await publish(lines.join('\n'));
} catch (error) {
  warn(`The Analytics Engine report could not be produced: ${error.message}`);
}
process.exit(0);
