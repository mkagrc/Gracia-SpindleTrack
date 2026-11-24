import assert from 'node:assert/strict';
import test from 'node:test';
import { formatAdminOrderCount } from './adminOrderCounts.js';

test('admin order counts display zero as a real count', () => {
  assert.equal(formatAdminOrderCount(0), '0');
  assert.equal(formatAdminOrderCount('0'), '0');
  assert.equal(formatAdminOrderCount(12), '12');
  assert.equal(formatAdminOrderCount(undefined), '0');
});
