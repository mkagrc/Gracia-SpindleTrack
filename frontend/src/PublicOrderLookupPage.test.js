import test from 'node:test';
import assert from 'node:assert/strict';
import { formatLookupTime, normalizeLookupCode, normalizeLookupMobile } from './publicOrderLookup.js';

test('public walk-in lookup normalizes the order code and mobile without changing the source form', () => {
  assert.equal(normalizeLookupCode('  o-000123 '), 'O-000123');
  assert.equal(normalizeLookupMobile('+63 (917) 123-4567'), '639171234567');
});

test('public tracking time formatter handles missing and valid timestamps safely', () => {
  assert.equal(formatLookupTime(''), 'Time unavailable');
  assert.notEqual(formatLookupTime('2026-11-20T15:00:00Z'), 'Time unavailable');
});
