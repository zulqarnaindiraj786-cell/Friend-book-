// Provision the Workers KV namespace that holds the live view-counter snapshot.
//
// The request path reads KV/Analytics Engine only and performs zero D1 view reads or writes. A
// separate guarded pre-deploy step copies historical D1 totals to an immutable KV key once.
//
// If this best-effort provisioning cannot establish a namespace, the subsequent history-seed step
// fails closed before deployment rather than silently shipping without the planned KV binding.
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
  const block = `# Live view counters (aggregate totals). View reads/writes stay in Analytics Engine,
# Workers KV and Worker memory; the view request path does no D1 reads or writes.
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
  warn('CLOUDFLARE_ACCOUNT_ID/CLOUDFLARE_API_TOKEN unavailable; the required pre-deploy view snapshot step will block deployment.');
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
  console.log('VIEWS namespace is configured; the guarded historical snapshot step must pass before deployment.');
} catch (error) {
  warn(`Workers KV namespace unavailable (${error.message}); deploying with in-memory view counters (still zero D1 row reads).`);
}
process.exit(0);
