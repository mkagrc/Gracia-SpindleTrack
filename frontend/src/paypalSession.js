const PAYPAL_SESSION_KEY = 'spindletrack.pendingPayPalOrder';

export function savePendingPayPalOrder(storage, value) {
  if (!storage || !value?.orderId) return;
  try {
    storage.setItem(PAYPAL_SESSION_KEY, JSON.stringify({
      orderId: String(value.orderId),
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
    if (!parsed || typeof parsed !== 'object' || !parsed.orderId) return null;
    return {
      orderId: String(parsed.orderId),
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
