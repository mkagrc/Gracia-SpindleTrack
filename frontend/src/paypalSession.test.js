import assert from 'node:assert/strict';
import test from 'node:test';
import {
  clearPendingPayPalOrder,
  readPendingPayPalOrder,
  savePendingPayPalOrder,
  validatePayPalReturnSession,
} from './paypalSession.js';

function memoryStorage() {
  const map = new Map();
  return {
    getItem: (k) => (map.has(k) ? map.get(k) : null),
    setItem: (k, v) => map.set(k, String(v)),
    removeItem: (k) => map.delete(k),
  };
}

test('pending PayPal order session saves, reads, and clears cleanly without mobile numbers', () => {
  const storage = memoryStorage();
  assert.equal(readPendingPayPalOrder(storage), null);
  savePendingPayPalOrder(storage, {
    flow: 'public',
    orderId: '',
    orderCode: 'O-000123',
    paypalOrderId: 'PP-123',
    mobile: '639171234567',
  });
  const stored = readPendingPayPalOrder(storage);
  assert.deepEqual(stored, { flow: 'public', orderId: '', orderCode: 'O-000123', paypalOrderId: 'PP-123' });
  assert.equal(stored.mobile, undefined);
  clearPendingPayPalOrder(storage);
  assert.equal(readPendingPayPalOrder(storage), null);
});

test('PayPal return session validation checks empty session, token presence, and order matching', () => {
  assert.deepEqual(validatePayPalReturnSession({ stored: null, token: 'PP-1', orderIdParam: 'o-1' }), {
    status: 'missing_session',
  });

  const stored = { flow: 'customer', orderId: 'order-1', orderCode: 'O-000001', paypalOrderId: 'PP-123' };
  assert.equal(validatePayPalReturnSession({ stored, token: '', orderIdParam: 'order-1' }).status, 'error');
  assert.equal(validatePayPalReturnSession({ stored, token: 'PP-OTHER', orderIdParam: 'order-1' }).status, 'error');
  assert.equal(validatePayPalReturnSession({ stored, token: 'PP-123', orderIdParam: 'order-OTHER' }).status, 'error');

  assert.deepEqual(validatePayPalReturnSession({ stored, token: 'PP-123', orderIdParam: 'order-1' }), {
    status: 'valid',
    flow: 'customer',
    orderId: 'order-1',
    orderCode: 'O-000001',
    paypalOrderId: 'PP-123',
  });
});
