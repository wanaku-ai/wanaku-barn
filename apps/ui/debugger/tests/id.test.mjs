import { test } from 'node:test';
import assert from 'node:assert/strict';
import { newId } from '../src/id.ts';

const uuidV4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

test('uses crypto.randomUUID when the browser provides it', () => {
  assert.match(newId(), uuidV4);
});

test('falls back to getRandomValues outside secure contexts', (t) => {
  // Browsers leave randomUUID undefined on plain HTTP; shadow the prototype method the same way.
  Object.defineProperty(crypto, 'randomUUID', { value: undefined, configurable: true });
  t.after(() => delete crypto.randomUUID);
  const ids = new Set(Array.from({ length: 100 }, () => newId()));
  assert.equal(ids.size, 100);
  for (const id of ids) assert.match(id, uuidV4);
});
