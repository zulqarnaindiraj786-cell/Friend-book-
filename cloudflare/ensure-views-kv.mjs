// Best-effort provisioning of the Workers KV namespace that holds the LIVE view counters.
//
// Why this exists: counting views in KV + Worker memory keeps the view path at ZERO D1 row reads,
// while D1 still receives exactly one write per resource per hour (permanent history, never lost).
//
// It uses the same CLOUDFLARE_API_TOKEN / CLOUDFLARE_ACCOUNT_ID the deploy step already uses. If
// anything is missing or refused, it prints a warning and exits 0: the Worker then counts in memory
// only, which still costs zero D1 row reads. This script never fails a deploy.
import { readFileSync, writeFileSync } from 'node:fs';

const TITLE = 'friendbook-views';
const BINDING = 'VIEWS';
const WRANGLER = new URL('../wrangler.toml', import.meta.url);
const account = process.env.CLOUDFLARE_ACCOUNT_ID;
const token = process.env.CLOUDFLARE_API_TOKEN;

const warn = (message) => console.log(`::warning::${message}`);

function bindInWrangler(id) {
  const text = readFileSync(WRANGLER, 'utf8');
  if (text.includes(`binding = "${BINDING}"`)) {
    writeFileSync(WRANGLER, text.replace(/(binding = "VIEWS"\s*\n\s*id = ")[^"]*(")/, `$1${id}$2`));
    console.log(`Updated the existing ${BINDING} binding id in wrangler.toml.`);
    return;
  }
  const block = `# Live view counters (aggregate totals). Counting stays in KV + Worker memory so the
# view path costs ZERO D1 row reads; D1 receives one write per resource per hour.
[[kv_namespaces]]
binding = "${BINDING}"
id = "${id}"

`;
  // Inserted before the R2 block so the R2-stripped deploy fallback still keeps this binding.
  const anchor = '# Existing owner-provided bucket.';
  const index = text.indexOf(anchor);
  writeFileSync(WRANGLER, index === -1 ? text + `\n${block.trimEnd()}\n` : text.slice(0, index) + block + text.slice(index));
  console.log(`Bound ${BINDING} in wrangler.toml.`);
}

if (!account || !token) {
  warn('CLOUDFLARE_ACCOUNT_ID/CLOUDFLARE_API_TOKEN unavailable here; deploying with in-memory view counters (still zero D1 row reads).');
  process.exit(0);
}

const base = `https://api.cloudflare.com/client/v4/accounts/${account}/storage/kv/namespaces`;
const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };

try {
  const listResponse = await fetch(`${base}?per_page=100`, { headers });
  const list = await listResponse.json();
  if (!list?.success) throw new Error(list?.errors?.[0]?.message || `list namespaces HTTP ${listResponse.status}`);
  let namespace = (list.result || []).find((entry) => entry.title === TITLE);
  if (namespace) {
    console.log(`Reusing existing Workers KV namespace '${TITLE}' (${namespace.id}).`);
  } else {
    const created = await fetch(base, { method: 'POST', headers, body: JSON.stringify({ title: TITLE }) });
    const body = await created.json();
    if (!body?.success) throw new Error(body?.errors?.[0]?.message || `create namespace HTTP ${created.status}`);
    namespace = body.result;
    console.log(`Created Workers KV namespace '${TITLE}' (${namespace.id}).`);
  }
  if (!namespace?.id) throw new Error('namespace response had no id');
  bindInWrangler(namespace.id);
  console.log('View counters will run on KV + memory: zero D1 row reads, one D1 write per resource per hour.');
} catch (error) {
  warn(`Workers KV namespace unavailable (${error.message}); deploying with in-memory view counters (still zero D1 row reads).`);
}
process.exit(0);
