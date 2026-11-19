export const INVITATION_RETURN_KEY = 'spindletrack.invitationReturnTo';

export function invitationPath(location) {
  const pathname = location?.pathname || '';
  if (pathname !== '/accept-invitation') return '';
  const search = location?.search || '';
  const params = new URLSearchParams(search);
  return params.has('token') ? `${pathname}${search}` : pathname;
}

export function rememberInvitationReturn(location, storage = globalThis.sessionStorage) {
  const target = invitationPath(location);
  if (target && storage) storage.setItem(INVITATION_RETURN_KEY, target);
  return target;
}

function validatedInvitationReturn(storage) {
  if (!storage) return '';
  const target = storage.getItem(INVITATION_RETURN_KEY) || '';
  if (!target.startsWith('/accept-invitation') || target.startsWith('//') || target.includes('\\')) return '';
  const parsed = new URL(target, 'https://spindletrack.invalid');
  return parsed.origin === 'https://spindletrack.invalid' ? `${parsed.pathname}${parsed.search}${parsed.hash}` : '';
}

export function getInvitationReturn(storage = globalThis.sessionStorage) {
  return validatedInvitationReturn(storage);
}

export function takeInvitationReturn(storage = globalThis.sessionStorage) {
  const target = validatedInvitationReturn(storage);
  if (storage) storage.removeItem(INVITATION_RETURN_KEY);
  return target;
}

export function postAuthDestination(session, fallback, storage = globalThis.sessionStorage) {
  const invitation = validatedInvitationReturn(storage);
  if (invitation) return invitation;
  if (fallback && typeof fallback === 'string' && fallback.startsWith('/') && !fallback.startsWith('//')) return fallback;
  const homes = { PLATFORM_ADMIN: '/admin/shops', OWNER: '/owner', STAFF: '/staff', CUSTOMER: '/customer/orders' };
  return homes[session?.role] || '/';
}
