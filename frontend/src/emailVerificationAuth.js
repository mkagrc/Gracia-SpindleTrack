export const VERIFICATION_SENT_MESSAGE =
  'Verification email sent. Check your inbox (and Spam folder), then sign in.';
export const VERIFY_BEFORE_SIGNIN_MESSAGE = 'Please verify your email before signing in.';
export const RESEND_COOLDOWN_SECONDS = 60;

/**
 * Returns true if the Firebase user uses the email/password provider (or lacks provider metadata).
 */
export function isPasswordProviderUser(user) {
  const providers = Array.isArray(user?.providerData) ? user.providerData : [];
  if (!providers.length) return true;
  return providers.some((entry) => entry?.providerId === 'password');
}

/**
 * Computes remaining cooldown seconds before another verification email can be sent.
 */
export function cooldownRemainingSeconds(cooldownUntilMs, nowMs = Date.now()) {
  if (!Number.isFinite(cooldownUntilMs) || cooldownUntilMs <= nowMs) return 0;
  return Math.ceil((cooldownUntilMs - nowMs) / 1000);
}

function unverifiedPasswordError() {
  const error = new Error(VERIFY_BEFORE_SIGNIN_MESSAGE);
  error.code = 'auth/email-not-verified';
  return error;
}

/**
 * Creates an email/password account, sends a verification email, and signs out immediately.
 */
export async function createEmailAccountWithVerification({
  email,
  password,
  createUser,
  sendVerification,
  signOutUser,
}) {
  const credential = await createUser(email, password);
  const user = credential?.user ?? credential;
  try {
    await sendVerification(user);
  } finally {
    await signOutUser();
  }
  return user?.email || email;
}

/**
 * Signs in with email/password, blocking and signing out unverified password accounts.
 */
export async function signInEmailWithVerification({
  email,
  password,
  signIn,
  signOutUser,
  syncSession,
}) {
  const credential = await signIn(email, password);
  const user = credential?.user ?? credential;
  if (isPasswordProviderUser(user) && !user?.emailVerified) {
    await signOutUser();
    throw unverifiedPasswordError();
  }
  return syncSession(user);
}

/**
 * Signs in with email/password solely to resend a verification email, then signs out immediately.
 */
export async function resendVerificationForPasswordUser({
  email,
  password,
  signIn,
  reloadUser,
  sendVerification,
  signOutUser,
}) {
  const credential = await signIn(email, password);
  const user = credential?.user ?? credential;
  try {
    if (reloadUser) await reloadUser(user);
    if (user?.emailVerified) {
      return { alreadyVerified: true, user };
    }
    await sendVerification(user);
    return { alreadyVerified: false };
  } finally {
    await signOutUser();
  }
}
