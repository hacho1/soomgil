import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { createHash } from 'node:crypto';

const root = fileURLToPath(new URL('../', import.meta.url));
const collection = process.argv[2] || 'badges';
if (!['badges', 'map'].includes(collection)) throw new Error('Choose badges or map');
const manifestFile = collection === 'badges' ? 'badge-art.json' : 'map-art.json';
const manifest = JSON.parse(await readFile(path.join(root, 'frontend/src/data', manifestFile), 'utf8'));
const directory = path.join(root, 'frontend/public/assets', collection);
await mkdir(directory, { recursive: true });
const base = `https://raw.githubusercontent.com/microsoft/fluentui-emoji/${manifest.revision}/`;
const inventory = [];
for (const asset of Object.values(manifest.assets)) {
  if (!/^[a-z0-9-]+\.png$/.test(asset.file)) throw new Error('Invalid asset filename');
  const response = await fetch(base + asset.source.split('/').map(encodeURIComponent).join('/'), { signal: AbortSignal.timeout(20000) });
  if (!response.ok) throw new Error(`Asset download failed: ${asset.file} (${response.status})`);
  const bytes = Buffer.from(await response.arrayBuffer());
  if (!bytes.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]))) throw new Error(`Invalid PNG: ${asset.file}`);
  await writeFile(path.join(directory, asset.file), bytes);
  inventory.push({ file: asset.file, bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') });
}
const license = await fetch(base + 'LICENSE', { signal: AbortSignal.timeout(20000) });
if (!license.ok) throw new Error('License download failed');
await writeFile(path.join(directory, 'LICENSE.txt'), await license.text());
await writeFile(path.join(directory, 'integrity.json'), JSON.stringify(inventory, null, 2) + '\n');
console.log(`Downloaded ${inventory.length} pinned PNG assets (${inventory.reduce((sum, item) => sum + item.bytes, 0)} bytes) and MIT license.`);
