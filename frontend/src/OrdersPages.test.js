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
const { LAUNDRY_STAGES, nextLaundryStage, paymentStatusLabel, suggestTotalPrice, createOrderPayload, customerOrderSnapshot, stageProgressPercent, quantityInputLabel, orderQuantityDisplay } = await vite.ssrLoadModule('/src/OrdersPages.jsx');
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
  const payload = { customerName: 'Customer', mobile: '639171234567', serviceId: 'svc-1', quantity: 2.5, totalPrice: 72, notes: '' };
  assert.deepEqual(createOrderPayload(fields, ' Customer@Example.com '), { ...payload, customerEmail: 'Customer@Example.com' });
  assert.deepEqual(createOrderPayload(fields), payload);
  assert.equal(createOrderPayload(fields, ' ').customerEmail, undefined);
  assert.equal(createOrderPayload(fields, ' Customer@Example.com ').totalPrice, 72);
});

test('order quantity label and display follow kg, piece, and load service units', () => {
  assert.equal(quantityInputLabel('kg'), 'Kilos');
  assert.equal(quantityInputLabel('piece'), 'Pieces');
  assert.equal(quantityInputLabel('load'), 'Loads');
  assert.equal(orderQuantityDisplay({ quantity: 2, unit: 'piece' }), '2.00 piece');
  assert.equal(orderQuantityDisplay({ kilos: 1.5 }), '1.50 kg');
});

test('customer dashboard summary uses the server total and labels stage counts as page-scoped', () => {
  const rows = [
    { stage: 'READY' },
    { stage: 'WASHING' },
    { stage: 'CLAIMED' },
  ];
  assert.deepEqual(customerOrderSnapshot(rows, 27), {
    totalOrders: 27,
    ordersOnPage: 3,
    inProgressOnPage: 2,
    readyOnPage: 1,
  });
  assert.deepEqual(customerOrderSnapshot([], 0), {
    totalOrders: 0,
    ordersOnPage: 0,
    inProgressOnPage: 0,
    readyOnPage: 0,
  });
});

test('customer order progress percentage follows the six-stage workflow and handles unknown status', () => {
  assert.equal(stageProgressPercent('RECEIVED'), 0);
  assert.equal(stageProgressPercent('READY'), 80);
  assert.equal(stageProgressPercent('CLAIMED'), 100);
  assert.equal(stageProgressPercent('UNKNOWN'), 0);
});
