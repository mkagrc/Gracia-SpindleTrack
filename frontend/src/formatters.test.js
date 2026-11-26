import assert from 'node:assert/strict';
import test from 'node:test';
import { formatDateTime, formatPeso, formatQuantity, formatWeight } from './formatters.js';

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
