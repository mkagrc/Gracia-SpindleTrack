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
const { LAUNDRY_STAGES, nextLaundryStage, paymentStatusLabel, suggestTotalPrice, createOrderPayload, customerOrderSnapshot, stageProgressPercent, quantityInputLabel, orderQuantityDisplay, createClientRequestId } = await vite.ssrLoadModule('/src/OrdersPages.jsx');
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

test('new-order payload omits client-controlled price and unit while including only an explicitly attached email', () => {
  const fields = { customerName: 'Customer', mobile: '639171234567', serviceId: 'svc-1', kilos: 2.5, totalPrice: 72, notes: '' };
  const payload = { customerName: 'Customer', mobile: '639171234567', serviceId: 'svc-1', quantity: 2.5, notes: '' };
  assert.deepEqual(createOrderPayload(fields, ' Customer@Example.com '), { ...payload, customerEmail: 'Customer@Example.com' });
  assert.deepEqual(createOrderPayload(fields), payload);
  assert.equal(createOrderPayload(fields, ' ').customerEmail, undefined);
  assert.equal(createOrderPayload({ ...fields, unit: 'load', pricePerUnit: 25 }, ' Customer@Example.com ').unit, undefined);
  assert.equal(createOrderPayload(fields).totalPrice, undefined);
});

test('order quantity label and display follow kg, piece, load, and flat billing types', () => {
  assert.equal(quantityInputLabel('kg'), 'Weight (kg)');
  assert.equal(quantityInputLabel('piece'), 'Number of pieces');
  assert.equal(quantityInputLabel('load'), 'Number of loads');
  assert.equal(quantityInputLabel({ billingType: 'FLAT' }), 'Flat rate');
  assert.equal(orderQuantityDisplay({ quantity: 2, unit: 'piece' }), 'Pieces: 2 pieces');
  assert.equal(orderQuantityDisplay({ quantity: 2, quantityUnit: 'load' }), 'Loads: 2 loads');
  assert.equal(orderQuantityDisplay({ kilos: 1.5 }), 'Weight: 1.50 kg');
  assert.equal(orderQuantityDisplay({ quantity: 1, billingType: 'FLAT' }), 'Flat rate');
  assert.equal(orderQuantityDisplay({ kilos: null, quantity: null }), 'N/A');
  assert.equal(orderQuantityDisplay({}), 'N/A');
  assert.equal(orderQuantityDisplay({ kilos: 3.5, unit: 'kg' }), 'Weight: 3.50 kg');
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

test('order creation UUID uses a secure random UUID provider', () => {
  const expected = '9a9f1d90-74db-4a8f-9bc3-1091b12c5c99';
  assert.equal(createClientRequestId({ randomUUID: () => expected }), expected);
});
