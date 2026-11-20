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
const { LAUNDRY_STAGES, nextLaundryStage, paymentStatusLabel, suggestTotalPrice, createOrderPayload } = await vite.ssrLoadModule('/src/OrdersPages.jsx');
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

test('service rate suggestion multiplies by kilos but remains a frontend suggestion', () => {
  assert.equal(suggestTotalPrice(18.5, '2.35'), '43.48');
  assert.equal(suggestTotalPrice(18.5, ''), '');
});

test('payment status labels distinguish unpaid, pending online, and paid', () => {
  assert.equal(paymentStatusLabel('UNPAID'), 'Unpaid');
  assert.equal(paymentStatusLabel('PENDING'), 'Pending online');
  assert.equal(paymentStatusLabel('PAID'), 'Paid');
});

test('new-order payload includes only the explicitly attached trimmed email and keeps staff-entered price', () => {
  const fields = { customerName: 'Customer', mobile: '639171234567', serviceId: 'svc-1', kilos: 2.5, totalPrice: 72, notes: '' };
  assert.deepEqual(createOrderPayload(fields, ' Customer@Example.com '), { ...fields, customerEmail: 'Customer@Example.com' });
  assert.deepEqual(createOrderPayload(fields), fields);
  assert.equal(createOrderPayload(fields, ' ').customerEmail, undefined);
  assert.equal(createOrderPayload(fields, ' Customer@Example.com ').totalPrice, 72);
});
