import assert from 'node:assert/strict';
import test from 'node:test';
import { roleMayAccess } from './roleAccess.js';

test('staff unclaimed route role policy allows staff and rejects owner, admin, and customer', () => {
  const allowedRoles = ['STAFF'];
  assert.equal(roleMayAccess('STAFF', allowedRoles), true);
  for (const role of ['OWNER', 'PLATFORM_ADMIN', 'CUSTOMER']) {
    assert.equal(roleMayAccess(role, allowedRoles), false, `${role} must not open the staff page`);
  }
});
