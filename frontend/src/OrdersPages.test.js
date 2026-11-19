import assert from 'node:assert/strict';
import test, { after } from 'node:test';
import react from '@vitejs/plugin-react';
import { createServer } from 'vite';
import { fileURLToPath } from 'node:url';

const vite = await createServer({
  configFile: false,
  root: fileURLToPath(new URL('..', import.meta.url)),
  plugins: [react()],
  appType: 'custom',
  logLevel: 'silent',
  server: { middlewareMode: true, fs: { strict: false } },
});
const { LAUNDRY_STAGES, nextLaundryStage } = await vite.ssrLoadModule('/src/OrdersPages.jsx');
after(async () => vite.close());

test('frontend follows the six existing laundry stages in the defined order', () => {
  assert.deepEqual(LAUNDRY_STAGES, ['RECEIVED', 'WASHING', 'DRYING', 'FOLDING', 'READY', 'CLAIMED']);
  assert.deepEqual(LAUNDRY_STAGES.slice(0, -1).map(nextLaundryStage), LAUNDRY_STAGES.slice(1));
});

test('stage helper has no backward, skip, or beyond-Claimed transition', () => {
  assert.equal(nextLaundryStage('CLAIMED'), null);
  assert.equal(nextLaundryStage('UNKNOWN'), null);
  assert.equal(nextLaundryStage('DRYING'), 'FOLDING');
});
