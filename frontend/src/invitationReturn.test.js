import assert from 'node:assert/strict';
import test from 'node:test';
import { INVITATION_RETURN_KEY, postAuthDestination, rememberInvitationReturn, takeInvitationReturn } from './invitationReturn.js';

function memoryStorage() {
  const values = new Map();
  return {
    getItem: (key) => values.has(key) ? values.get(key) : null,
    setItem: (key, value) => values.set(key, String(value)),
    removeItem: (key) => values.delete(key),
    has: (key) => values.has(key),
  };
}

test('signed-out invitation target survives sign-in and precedes the CUSTOMER home redirect', () => {
  const storage = memoryStorage();
  const invitation = { pathname: '/accept-invitation', search: '?token=XYZ' };

  assert.equal(rememberInvitationReturn(invitation, storage), '/accept-invitation?token=XYZ');
  assert.equal(storage.getItem(INVITATION_RETURN_KEY), '/accept-invitation?token=XYZ');
  assert.equal(postAuthDestination({ role: 'CUSTOMER' }, '/customer/orders', storage), '/accept-invitation?token=XYZ');
});

test('taking the invitation return target clears it from session storage after use', () => {
  const storage = memoryStorage();
  rememberInvitationReturn({ pathname: '/accept-invitation', search: '?token=XYZ' }, storage);

  assert.equal(takeInvitationReturn(storage), '/accept-invitation?token=XYZ');
  assert.equal(storage.has(INVITATION_RETURN_KEY), false);
  assert.equal(takeInvitationReturn(storage), '');
});

test('non-invitation authentication falls back to the role home', () => {
  assert.equal(postAuthDestination({ role: 'CUSTOMER' }, '', memoryStorage()), '/customer/orders');
});


test('invitation continuation survives sign-up and verification, clears after acceptance, then routes staff home', () => {
  const storage = memoryStorage();
  const invitation = { pathname: '/accept-invitation', search: '?token=XYZ' };

  rememberInvitationReturn(invitation, storage);
  assert.equal(postAuthDestination({ role: 'CUSTOMER' }, '/customer/orders', storage), '/accept-invitation?token=XYZ');

  // Sign-up and the out-of-band email verification keep the same sessionStorage target.
  assert.equal(postAuthDestination({ role: 'CUSTOMER' }, '/customer/orders', storage), '/accept-invitation?token=XYZ');
  assert.equal(takeInvitationReturn(storage), '/accept-invitation?token=XYZ');
  assert.equal(storage.has(INVITATION_RETURN_KEY), false);

  // Once accepted, the refreshed STAFF session goes to its dashboard.
  assert.equal(postAuthDestination({ role: 'STAFF' }, '', storage), '/staff');
});
