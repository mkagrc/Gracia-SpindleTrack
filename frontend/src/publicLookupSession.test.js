import assert from 'node:assert/strict';
import test from 'node:test';
import { PUBLIC_LOOKUP_SESSION_KEY, readPublicLookupCredentials, writePublicLookupCredentials } from './publicLookupSession.js';

function memorySessionStorage() {
  const values = new Map();
  return {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, String(value)),
  };
}

test('stores only lookup inputs under the session-specific key and restores them after refresh', () => {
  const sessionStorage = memorySessionStorage();
  assert.equal(writePublicLookupCredentials(sessionStorage, { orderCode: ' O-000003 ', mobile: ' 09171234567 ' }), true);
  assert.equal(PUBLIC_LOOKUP_SESSION_KEY, 'spindletrack.public-order-lookup.session.v1');
  assert.deepEqual(readPublicLookupCredentials(sessionStorage), { orderCode: 'O-000003', mobile: '09171234567' });
});

test('invalid or unavailable session storage fails closed without throwing', () => {
  assert.equal(readPublicLookupCredentials(null), null);
  assert.equal(readPublicLookupCredentials({ getItem: () => '{bad json' }), null);
  assert.equal(writePublicLookupCredentials(null, { orderCode: 'O-000003', mobile: '09171234567' }), false);
});
