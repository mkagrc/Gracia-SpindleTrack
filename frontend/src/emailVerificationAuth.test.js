import assert from 'node:assert/strict';
import test from 'node:test';
import {
  RESEND_COOLDOWN_SECONDS,
  VERIFY_BEFORE_SIGNIN_MESSAGE,
  cooldownRemainingSeconds,
  createEmailAccountWithVerification,
  isPasswordProviderUser,
  resendVerificationForPasswordUser,
  signInEmailWithVerification,
} from './emailVerificationAuth.js';

test('sign up sends verification email and signs out immediately so user does not stay logged in', async () => {
  const calls = [];
  const email = await createEmailAccountWithVerification({
    email: 'newuser@gmail.com',
    password: 'password123',
    createUser: async (addr) => {
      calls.push('createUser');
      return { user: { uid: 'u1', email: addr, emailVerified: false, providerData: [{ providerId: 'password' }] } };
    },
    sendVerification: async (user) => {
      calls.push(`sendVerification:${user.email}`);
    },
    signOutUser: async () => {
      calls.push('signOut');
    },
  });

  assert.equal(email, 'newuser@gmail.com');
  assert.deepEqual(calls, ['createUser', 'sendVerification:newuser@gmail.com', 'signOut']);
});

test('sign up still signs out if sending verification email fails', async () => {
  let signedOut = false;
  await assert.rejects(
    () =>
      createEmailAccountWithVerification({
        email: 'newuser@gmail.com',
        password: 'password123',
        createUser: async (addr) => ({ user: { uid: 'u1', email: addr, emailVerified: false } }),
        sendVerification: async () => {
          throw new Error('rate limited');
        },
        signOutUser: async () => {
          signedOut = true;
        },
      }),
    /rate limited/,
  );
  assert.equal(signedOut, true);
});

test('unverified password user is blocked, signed out, and never calls backend session sync', async () => {
  let signedOut = false;
  let synced = false;
  await assert.rejects(
    () =>
      signInEmailWithVerification({
        email: 'unverified@gmail.com',
        password: 'password123',
        signIn: async () => ({
          user: {
            uid: 'u2',
            email: 'unverified@gmail.com',
            emailVerified: false,
            providerData: [{ providerId: 'password' }],
          },
        }),
        signOutUser: async () => {
          signedOut = true;
        },
        syncSession: async () => {
          synced = true;
        },
      }),
    (error) => {
      assert.equal(error.code, 'auth/email-not-verified');
      assert.equal(error.message, VERIFY_BEFORE_SIGNIN_MESSAGE);
      return true;
    },
  );

  assert.equal(signedOut, true);
  assert.equal(synced, false);
});

test('verified password user signs in and establishes backend session without signing out', async () => {
  let signedOut = false;
  const session = await signInEmailWithVerification({
    email: 'verified@gmail.com',
    password: 'password123',
    signIn: async () => ({
      user: {
        uid: 'u3',
        email: 'verified@gmail.com',
        emailVerified: true,
        providerData: [{ providerId: 'password' }],
      },
    }),
    signOutUser: async () => {
      signedOut = true;
    },
    syncSession: async (user) => ({ userId: 'doc-3', email: user.email, role: 'CUSTOMER' }),
  });

  assert.equal(signedOut, false);
  assert.equal(session.role, 'CUSTOMER');
  assert.equal(session.email, 'verified@gmail.com');
});

test('Google provider user is identified as non-password so no verification email gate applies', () => {
  assert.equal(
    isPasswordProviderUser({ emailVerified: true, providerData: [{ providerId: 'google.com' }] }),
    false,
  );
  assert.equal(
    isPasswordProviderUser({ emailVerified: false, providerData: [{ providerId: 'password' }] }),
    true,
  );
});

test('resend verification sends email and signs out, and cooldown calculates remaining seconds', async () => {
  const calls = [];
  const result = await resendVerificationForPasswordUser({
    email: 'unverified@gmail.com',
    password: 'password123',
    signIn: async () => {
      calls.push('signIn');
      return { user: { email: 'unverified@gmail.com', emailVerified: false } };
    },
    reloadUser: async () => {
      calls.push('reload');
    },
    sendVerification: async () => {
      calls.push('sendVerification');
    },
    signOutUser: async () => {
      calls.push('signOut');
    },
  });

  assert.deepEqual(result, { alreadyVerified: false });
  assert.deepEqual(calls, ['signIn', 'reload', 'sendVerification', 'signOut']);
  assert.equal(cooldownRemainingSeconds(100_000 + RESEND_COOLDOWN_SECONDS * 1000, 100_000), RESEND_COOLDOWN_SECONDS);
  assert.equal(cooldownRemainingSeconds(100_000, 100_500), 0);
});
