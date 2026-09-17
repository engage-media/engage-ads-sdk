#!/usr/bin/env node
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const source = await readFile(resolve(root, 'shared/mraid/mraid.js'));
const target = resolve(root, 'apple/Sources/EngageAdsMobile/Resources/mraid.js');
if (process.argv.includes('--check')) {
  let actual;
  try { actual = await readFile(target); } catch { actual = null; }
  if (!actual?.equals(source)) {
    console.error('Apple MRAID resource is stale. Run node scripts/sync-mraid.mjs.');
    process.exitCode = 1;
  }
} else {
  await mkdir(dirname(target), { recursive: true });
  await writeFile(target, source);
  console.log('Synchronized Apple MRAID resource. Android copies it during its build.');
}
