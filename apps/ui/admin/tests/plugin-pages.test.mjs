import assert from 'node:assert/strict';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import test from 'node:test';

// The plugin host mounts pages with createRoot and no router, so React Router hooks and <Link> throw there.
const pluginEntry = readFileSync(new URL('../src/plugin.tsx', import.meta.url), 'utf8');
const pageDirs = [...pluginEntry.matchAll(/from "\.\/Pages\/(\w+)\//g)].map((match) => match[1]);

function sources(dir) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? sources(path) : /\.tsx?$/.test(name) ? [path] : [];
  });
}

test('plugin mounts the change history page', () => {
  assert.ok(pageDirs.includes('ChangeHistory'));
});

test('pages mounted by the plugin do not depend on React Router', () => {
  assert.ok(pageDirs.length > 0);
  for (const dir of pageDirs) {
    for (const file of sources(new URL(`../src/Pages/${dir}`, import.meta.url).pathname)) {
      if (file.endsWith('index.ts') || file.endsWith('router-exports.tsx')) continue;
      assert.doesNotMatch(readFileSync(file, 'utf8'), /from ["']react-router(-dom)?["']/, file);
    }
  }
});
