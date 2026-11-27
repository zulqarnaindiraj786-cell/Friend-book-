import { copyFile, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

// Never publish all of docs/: it contains test reports and deployment configs.
const source = new URL('../docs/', import.meta.url);
const output = new URL('../.worker-assets/', import.meta.url);
export const publicFiles = ['index.html', 'logo.png', 'app-ads.txt', '_headers',
  'privacy-policy.html', 'delete-account.html', 'child-safety.html'];
export async function buildAssets() {
  await rm(output, { recursive: true, force: true });
  await mkdir(output, { recursive: true });
  for (const file of publicFiles) {
    await copyFile(new URL(file, source), new URL(file, output));
  }
  const index = await readFile(new URL('index.html', output), 'utf8');
  // Public pages share assets from the site root.
  await writeFile(new URL('index.html', output), index.replace('<head>', '<head>\n  <base href="/" />'));
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await buildAssets();
