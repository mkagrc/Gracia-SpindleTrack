export const PUBLIC_LOOKUP_SESSION_KEY = 'spindletrack.public-order-lookup.session.v1';

export function readPublicLookupCredentials(storage) {
  if (!storage) return null;
  try {
    const value = JSON.parse(storage.getItem(PUBLIC_LOOKUP_SESSION_KEY) || 'null');
    const orderCode = typeof value?.orderCode === 'string' ? value.orderCode.trim() : '';
    const mobile = typeof value?.mobile === 'string' ? value.mobile.trim() : '';
    return orderCode && mobile ? { orderCode, mobile } : null;
  } catch {
    return null;
  }
}

export function writePublicLookupCredentials(storage, credentials) {
  if (!storage || !credentials) return false;
  try {
    storage.setItem(PUBLIC_LOOKUP_SESSION_KEY, JSON.stringify({
      orderCode: String(credentials.orderCode || '').trim(),
      mobile: String(credentials.mobile || '').trim(),
    }));
    return true;
  } catch {
    return false;
  }
}

export function clearPublicLookupCredentials(storage) {
  if (!storage) return false;
  try {
    storage.removeItem(PUBLIC_LOOKUP_SESSION_KEY);
    return true;
  } catch {
    return false;
  }
}
