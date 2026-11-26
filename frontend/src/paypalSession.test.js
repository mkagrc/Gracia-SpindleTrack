import assert from 'node:assert/strict';
import test from 'node:test';
import { clearPendingPayPalOrder, readPendingPayPalOrder, savePendingPayPalOrder } from './paypalSession.js';

function memoryStorage() {
  const map = new Map();
  return {
    getItem: (k) => (map.has(k) ? map.get(k) : null),
    setItem: (k, v) => map.set(k, String(v)),
    removeItem: (k) => map.delete(k),
  };
}

test('pending PayPal order session saves, reads, and clears cleanly', () => {
  const storage = memoryStorage();
  assert.equal(readPendingPayPalOrder(storage), null);
  savePendingPayPalOrder(storage, { orderId: 'order-10', paypalOrderId: 'PP-123' });
  assert.deepEqual(readPendingPayPalOrder(storage), { orderId: 'order-10', paypalOrderId: 'PP-123' });
  clearPendingPayPalOrder(storage);
  assert.equal(readPendingPayPalOrder(storage), null);
});
