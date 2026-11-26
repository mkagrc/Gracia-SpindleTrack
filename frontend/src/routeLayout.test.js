import assert from 'node:assert/strict';
import test from 'node:test';
import { publicLayoutMode } from './routeLayout.js';

test('signed-in public-page routes stay inside the workspace layout', () => {
  assert.equal(publicLayoutMode({ role: 'CUSTOMER' }), 'workspace');
});

test('signed-out routes use the public header and footer frame', () => {
  assert.equal(publicLayoutMode(null), 'public');
});
