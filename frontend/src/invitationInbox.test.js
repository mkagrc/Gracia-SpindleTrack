import assert from 'node:assert/strict';
import test from 'node:test';
import { customerMenuForInvitations, dismissInvitationPrompt, hasPendingInvitations, shouldRedirectEmptyInbox, undismissedInvitations } from './invitationInbox.js';

function memoryStorage() {
  const values = new Map();
  return {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, String(value)),
  };
}

const menu = [
  { label: 'My orders', to: '/customer/orders' },
  { label: 'Invitations', to: '/customer/invitations', invitationBadge: true },
];

test('no invitations means no inbox menu item, badge, or popup', () => {
  assert.equal(hasPendingInvitations([]), false);
  assert.deepEqual(customerMenuForInvitations(menu, []), [menu[0]]);
  assert.deepEqual(undismissedInvitations([], memoryStorage()), []);
});

test('one pending invitation enables the menu and badge count and prompts once per session', () => {
  const storage = memoryStorage();
  const rows = [{ invitationId: 'inv-A', shopName: 'Laba Laba' }];
  assert.equal(hasPendingInvitations(rows), true);
  assert.deepEqual(customerMenuForInvitations(menu, rows), menu);
  assert.equal(rows.length, 1);
  assert.deepEqual(undismissedInvitations(rows, storage), rows);
  dismissInvitationPrompt('inv-A', storage);
  assert.deepEqual(undismissedInvitations(rows, storage), []);
});

test('after accept or decline removes the invitation from menu and popup', () => {
  const storage = memoryStorage();
  const rows = [{ invitationId: 'inv-A', shopName: 'Laba Laba' }];
  dismissInvitationPrompt('inv-A', storage);
  const afterAction = rows.filter((row) => row.invitationId !== 'inv-A');
  assert.deepEqual(undismissedInvitations(afterAction, storage), []);
  assert.deepEqual(customerMenuForInvitations(menu, afterAction), [menu[0]]);
});

test('empty inbox route redirects only after a successful loaded result', () => {
  assert.equal(shouldRedirectEmptyInbox({ loading: true, error: '', invitations: [] }), false);
  assert.equal(shouldRedirectEmptyInbox({ loading: false, error: 'failed', invitations: [] }), false);
  assert.equal(shouldRedirectEmptyInbox({ loading: false, error: '', invitations: [] }), true);
  assert.equal(shouldRedirectEmptyInbox({ loading: false, error: '', invitations: [{ invitationId: 'inv-A' }] }), false);
});
