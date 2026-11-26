const PAYPAL_SESSION_KEY = 'spindletrack.pendingPayPalOrder';

export function savePendingPayPalOrder(storage, value) {
  if (!storage || (!value?.orderId && !value?.orderCode)) return;
  const flow = value.flow === 'public' || value.flow === 'staff' ? value.flow : 'customer';
  try {
    storage.setItem(PAYPAL_SESSION_KEY, JSON.stringify({
      flow,
      orderId: value.orderId ? String(value.orderId) : '',
      orderCode: value.orderCode ? String(value.orderCode) : '',
      paypalOrderId: value.paypalOrderId ? String(value.paypalOrderId) : '',
    }));
  } catch {
    // Ignore storage quota / privacy mode errors
  }
}

export function readPendingPayPalOrder(storage) {
  if (!storage) return null;
  try {
    const raw = storage.getItem(PAYPAL_SESSION_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object' || (!parsed.orderId && !parsed.orderCode)) return null;
    const flow = parsed.flow === 'public' || parsed.flow === 'staff' ? parsed.flow : 'customer';
    return {
      flow,
      orderId: parsed.orderId ? String(parsed.orderId) : '',
      orderCode: parsed.orderCode ? String(parsed.orderCode) : '',
      paypalOrderId: parsed.paypalOrderId ? String(parsed.paypalOrderId) : '',
    };
  } catch {
    return null;
  }
}

export function clearPendingPayPalOrder(storage) {
  if (!storage) return;
  try {
    storage.removeItem(PAYPAL_SESSION_KEY);
  } catch {
    // Ignore storage errors
  }
}

export function validatePayPalReturnSession({ stored, token, orderIdParam }) {
  if (!stored) {
    return { status: 'missing_session' };
  }
  const cleanToken = token ? String(token).trim() : '';
  if (!cleanToken) {
    return { status: 'error', reason: 'PayPal order token is missing. Check My orders or Track a walk-in order to verify payment status.' };
  }
  if (stored.paypalOrderId && cleanToken !== stored.paypalOrderId) {
    return { status: 'error', reason: 'The returned PayPal order token does not match the pending payment in this browser session.' };
  }
  if (stored.flow !== 'public' && orderIdParam && stored.orderId && String(orderIdParam) !== stored.orderId) {
    return { status: 'error', reason: 'The returned order reference does not match the pending payment in this browser session.' };
  }
  return { status: 'valid', flow: stored.flow, orderId: stored.orderId || String(orderIdParam || ''), orderCode: stored.orderCode || '', paypalOrderId: cleanToken };
}
