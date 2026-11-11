// Binds Cloudflare Analytics Engine to the Worker so every view becomes an AE event and the real
// count comes from AE's uniq(uid) - which is what keeps "1 user = 1 view" true with ZERO D1 writes.
//
// Idempotent and offline: it only edits wrangler.toml. Whether the account can actually use
// Analytics Engine is decided by `wrangler deploy` (the deploy step strips this binding and retries
// if it is refused), and cloudflare/report-views-ae.mjs reports the real API answer after deploy.
import { readFileSync, writeFileSync } from 'node:fs';

const BINDING = 'AE';
const DATASET = 'friendbook_views';
const WRANGLER = new URL('../wrangler.toml', import.meta.url);
const ACCOUNT_ID = process.env.CLOUDFLARE_ACCOUNT_ID || '';
// The R2 block is where the deploy fallback truncates wrangler.toml, so every binding this script
// adds must sit BEFORE it or it would be dropped from the redeploy.
const ANCHOR = '# Existing owner-provided bucket.';

const warn = (message) => console.log(`::warning::${message}`);

function insertBefore(text, block) {
  const index = text.indexOf(ANCHOR);
  return index === -1 ? `${text.trimEnd()}\n\n${block.trimEnd()}\n` : text.slice(0, index) + block + text.slice(index);
}

let text = readFileSync(WRANGLER, 'utf8');

// 1. The Analytics Engine dataset binding.
if (/^\[\[analytics_engine_datasets\]\]/m.test(text)) {
  console.log('Analytics Engine binding already present in wrangler.toml.');
} else {
  const block = `# Every view is one Analytics Engine event; the count is AE's uniq(uid) per Reel/post, so
# "1 user = 1 view" needs no D1 row at all. Never written to D1, never read from it.
[[analytics_engine_datasets]]
binding = "${BINDING}"
dataset_name = "${DATASET}"

`;
  text = insertBefore(text, block);
  console.log(`Bound Analytics Engine as ${BINDING} (dataset ${DATASET}).`);
}

// 2. The account id the Worker needs to query AE (public value, no secret).
if (/^CF_ACCOUNT_ID\s*=/m.test(text)) {
  if (ACCOUNT_ID) text = text.replace(/^CF_ACCOUNT_ID\s*=.*$/m, `CF_ACCOUNT_ID = "${ACCOUNT_ID}"`);
  console.log('CF_ACCOUNT_ID already present in wrangler.toml.');
} else if (ACCOUNT_ID) {
  text = text.replace(/^(\[vars\]\n)/m, `$1CF_ACCOUNT_ID = "${ACCOUNT_ID}"\n`);
  console.log('Added CF_ACCOUNT_ID to [vars].');
} else {
  warn('CLOUDFLARE_ACCOUNT_ID unavailable here; the Worker can still write AE events but cannot query them.');
}

writeFileSync(WRANGLER, text);

// The Worker reads AE with an encrypted secret (CF_AE_TOKEN), never with a plaintext var.
if (!process.env.CLOUDFLARE_API_TOKEN) {
  warn('CLOUDFLARE_API_TOKEN unavailable here; AE counts will be served from KV + memory instead (still zero D1 writes).');
}
console.log('View counters: Analytics Engine events + uniq(uid) counts, KV live snapshot, D1 never written.');
process.exit(0);
