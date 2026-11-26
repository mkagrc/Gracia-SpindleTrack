import assert from 'node:assert/strict';
import test from 'node:test';
import { formatDateTime, formatOrderPaymentMethod, formatPaymentMethod, formatPeso, formatQuantity, formatWeight } from './formatters.js';

test('formats PHP currency with the Philippine locale', () => {
  assert.equal(formatPeso(3500), '₱3,500.00');
});

test('formats weight to two decimals and missing legacy values as N/A', () => {
  assert.equal(formatWeight(3.5), '3.50 kg');
  assert.equal(formatWeight(null), 'N/A');
  assert.equal(formatQuantity(2, 'piece'), '2.00 piece');
});

test('formats dates and times in Asia/Manila with en-PH conventions', () => {
  assert.equal(formatDateTime('2026-11-25T21:36:00Z'), 'Nov 26, 2026, 5:36 AM');
  assert.equal(formatDateTime(null), '—');
});

test('formats payment methods for Cash, PayPal, and legacy PayMongo', () => {
  assert.equal(formatPaymentMethod('CASH'), 'Cash');
  assert.equal(formatPaymentMethod('PAYPAL'), 'Online payment (PayPal)');
  assert.equal(formatPaymentMethod('ONLINE'), 'Online payment (PayPal)');
  assert.equal(formatPaymentMethod('PAYMONGO'), 'Online payment (PayMongo, legacy)');
  assert.equal(formatPaymentMethod(null), 'Not specified');
  assert.equal(formatOrderPaymentMethod({ paymentMethod: 'ONLINE', paidByUid: 'PAYMONGO' }), 'Online payment (PayMongo, legacy)');
  assert.equal(formatOrderPaymentMethod({ paymentMethod: 'PAYPAL', paidByUid: 'PAYPAL' }), 'Online payment (PayPal)');
});
