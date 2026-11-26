const pesoFormatter = new Intl.NumberFormat('en-PH', {
  style: 'currency',
  currency: 'PHP',
});
const quantityFormatter = new Intl.NumberFormat('en-PH', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});
const manilaDateTimeFormatter = new Intl.DateTimeFormat('en-PH', {
  timeZone: 'Asia/Manila',
  year: 'numeric',
  month: 'short',
  day: 'numeric',
  hour: 'numeric',
  minute: '2-digit',
  hour12: true,
});
const manilaDateFormatter = new Intl.DateTimeFormat('en-PH', {
  timeZone: 'Asia/Manila',
  year: 'numeric',
  month: 'short',
  day: 'numeric',
});

function validDate(value) {
  if (value === null || value === undefined || value === '') return null;
  const parsed = value instanceof Date ? value : new Date(value);
  return Number.isNaN(parsed.getTime()) ? null : parsed;
}

export function formatPeso(value) {
  const amount = Number(value ?? 0);
  return pesoFormatter.format(Number.isFinite(amount) ? amount : 0);
}

export function formatQuantity(value, unit = 'kg') {
  if (value === null || value === undefined || value === '') return 'N/A';
  const amount = Number(value);
  if (!Number.isFinite(amount)) return 'N/A';
  return `${quantityFormatter.format(amount)} ${unit || 'kg'}`;
}

export function formatWeight(value) {
  return formatQuantity(value, 'kg');
}

export function formatDateTime(value) {
  const parsed = validDate(value);
  return parsed ? manilaDateTimeFormatter.format(parsed) : '—';
}

export function formatDate(value) {
  const parsed = validDate(value);
  return parsed ? manilaDateFormatter.format(parsed) : '\u2014';
}

export function formatPaymentMethod(value) {
  const normalized = String(value || '').trim().toUpperCase();
  if (normalized === 'CASH') return 'Cash';
  if (normalized === 'ONLINE' || normalized === 'PAYMONGO') return 'Online payment (PayMongo)';
  return 'Not specified';
}

export function formatPaymentTiming(value) {
  const normalized = String(value || '').trim().toUpperCase();
  if (normalized === 'PAY_AT_CLAIM') return 'Pay when claiming the laundry';
  if (normalized === 'PAY_NOW') return 'Pay now';
  return 'Pay now';
}

export function formatPaymentStatus(value) {
  const normalized = String(value || '').trim().toUpperCase();
  if (normalized === 'PAID') return 'Paid';
  if (normalized === 'PENDING') return 'Pending online';
  return 'Unpaid';
}
