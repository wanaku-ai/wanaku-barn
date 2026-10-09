import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { after, afterEach, test } from 'node:test';
import ts from 'typescript';

// Compile the production adapter without introducing another test runner.
const outputDir = await mkdtemp(join(tmpdir(), 'wanaku-fetch-test-'));
for (const name of ['plugin-host', 'custom-fetch']) {
  const source = await readFile(new URL(`../src/${name}.ts`, import.meta.url), 'utf8');
  const { outputText } = ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.ESNext },
  });
  await writeFile(join(outputDir, `${name}.mjs`), outputText.replace('"./plugin-host"', '"./plugin-host.mjs"'));
}
const { customFetch } = await import(pathToFileURL(join(outputDir, 'custom-fetch.mjs')));
const { setPluginHost, clearPluginHost, SERVICE_ID } = await import(pathToFileURL(join(outputDir, 'plugin-host.mjs')));

const originalFetch = globalThis.fetch;
afterEach(() => {
  clearPluginHost();
  globalThis.fetch = originalFetch;
  delete globalThis.VITE_API_URL;
  delete globalThis.sessionStorage;
});
after(() => rm(outputDir, { recursive: true, force: true }));

function useHost(payload, calls = []) {
  const http = {};
  for (const method of ['get', 'post', 'put', 'delete']) {
    http[method] = async (...args) => {
      calls.push([method, ...args]);
      return payload;
    };
  }
  setPluginHost({ http });
}

const catalogs = [{ id: 'catalog-1', name: 'Example catalog' }];

test('plugin raw list is available at the same data.data path as standalone', async () => {
  globalThis.VITE_API_URL = 'http://localhost:8080';
  globalThis.sessionStorage = { removeItem() {} };
  globalThis.fetch = async () => Response.json({ data: catalogs });
  const standalone = await customFetch('/api/v1/service/catalog/list', {});

  useHost(catalogs);
  const plugin = await customFetch('/api/v1/service/catalog/list', {});
  assert.deepEqual(plugin.data.data, catalogs);
  assert.deepEqual(plugin.data, standalone.data);
  assert.equal(plugin.status, 200);
  assert.ok(plugin.headers instanceof Headers);
});

test('plugin preserves already enveloped responses', async () => {
  const envelope = { data: catalogs, error: null };
  useHost(envelope);
  const response = await customFetch('/api/v1/service/catalog/list', {});
  assert.equal(response.data, envelope);
  assert.deepEqual(response.data.data, catalogs);
});

for (const payload of [{ id: 'catalog-1', name: 'Updated catalog' }, [], null, 'created', 0]) {
  test(`plugin wraps payload ${JSON.stringify(payload)}`, async () => {
    useHost(payload);
    const response = await customFetch('/api/v1/service/catalog', {});
    assert.deepEqual(response.data.data, payload);
  });
}

for (const method of ['GET', 'POST', 'PUT', 'DELETE']) {
  test(`plugin routes ${method} and preserves its returned payload`, async () => {
    const calls = [];
    const payload = method === 'DELETE' ? null : { id: 'catalog-1' };
    useHost(payload, calls);
    const options = { method };
    const args = [method.toLowerCase(), SERVICE_ID, '/api/v1/service/catalog?name=example'];
    if (method === 'POST' || method === 'PUT') {
      options.body = JSON.stringify({ name: 'example' });
      args.push({ name: 'example' });
    }
    const response = await customFetch('https://backend.example/api/v1/service/catalog?name=example', options);
    assert.deepEqual(calls, [args]);
    assert.deepEqual(response.data.data, payload);
  });
}

test('plugin rejects error envelopes', async () => {
  useHost({ data: null, error: 'Catalog not found' });
  await assert.rejects(customFetch('/api/v1/service/catalog', {}), /Catalog not found/);
});

test('plugin propagates host request failures', async () => {
  const failure = new Error('Backend unavailable');
  setPluginHost({ http: { get: async () => { throw failure; } } });
  await assert.rejects(customFetch('/api/v1/service/catalog/list', {}), failure);
});

test('standalone preserves HTTP errors', async () => {
  globalThis.VITE_API_URL = 'http://localhost:8080';
  globalThis.fetch = async () => Response.json({ error: 'Invalid catalog' }, { status: 400 });
  await assert.rejects(customFetch('/api/v1/service/catalog', {}), /Invalid catalog/);
});

test('standalone exposes the message in a Barn WanakuError response', async () => {
  globalThis.VITE_API_URL = 'http://localhost:8080';
  globalThis.fetch = async () => Response.json({ error: { message: 'Stored file is unavailable.' } }, { status: 404 });
  await assert.rejects(customFetch('/api/v1/data-store/missing', {}), /Stored file is unavailable\./);
});

test('plugin exposes the message in a Barn WanakuError response', async () => {
  useHost({ data: null, error: { message: 'Stored file is unavailable.' } });
  await assert.rejects(customFetch('/api/v1/data-store/missing', {}), /Stored file is unavailable\./);
});
