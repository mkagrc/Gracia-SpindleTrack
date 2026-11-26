const BILLING_TYPES = ['PER_KG', 'PER_LOAD', 'PER_PIECE', 'FLAT'];

export function normalizeBillingType(service = {}) {
  const configured = String(service.billingType || service.unit || 'kg').trim().toLowerCase();
  const legacy = { kg: 'PER_KG', load: 'PER_LOAD', piece: 'PER_PIECE', flat: 'FLAT' };
  const value = legacy[configured] || configured.toUpperCase();
  return BILLING_TYPES.includes(value) ? value : 'PER_KG';
}

export function billingUnit(billingType) {
  return ({ PER_KG: 'kg', PER_LOAD: 'load', PER_PIECE: 'piece', FLAT: 'flat' })[billingType] || 'kg';
}

export function quantityInputRules(service = {}) {
  const type = normalizeBillingType(service);
  if (type === 'FLAT') return { billingType: type, label: 'Flat rate', inputType: 'fixed', min: 1, max: 1, step: 1, fixedQuantity: 1 };
  if (type === 'PER_LOAD') return { billingType: type, label: 'Number of loads', inputType: 'number', min: 1, max: 999, step: 1, fixedQuantity: null };
  if (type === 'PER_PIECE') return { billingType: type, label: 'Number of pieces', inputType: 'number', min: 1, max: 999, step: 1, fixedQuantity: null };
  return { billingType: type, label: 'Weight (kg)', inputType: 'number', min: 0.01, max: 999.99, step: 0.01, fixedQuantity: null };
}

const cents = (value) => {
  const number = Number(value);
  return Number.isFinite(number) && number >= 0 ? Math.round(number * 100) : 0;
};
const roundScaledCents = (unitPriceCents, quantityHundredths) => Math.floor((unitPriceCents * quantityHundredths + 50) / 100);

const BADGE_LABELS = {
  PER_KG: 'Per kg',
  PER_LOAD: 'Per load',
  PER_PIECE: 'Per piece',
  FLAT: 'Flat rate',
};

export function orderBillingType(order = {}) {
  const service = order?.service && typeof order.service === 'object' ? order.service : {};
  const configured = order?.billingType || service.billingType || order?.quantityUnit || order?.unit;
  if (configured) return normalizeBillingType({ billingType: configured });
  if (order?.kilos != null || order?.quantity != null) return 'PER_KG';
  return null;
}

export function billingTypeBadgeLabel(order = {}) {
  const type = orderBillingType(order);
  return type ? (BADGE_LABELS[type] || null) : null;
}

export function formatActualWeight(order = {}) {
  const raw = order?.actualWeightKg;
  if (raw == null || raw === '') return '';
  const value = Number(raw);
  if (!Number.isFinite(value) || value <= 0) return '';
  return `Actual weight: ${value.toFixed(2)} kg`;
}

export function formatOrderQuantity(order = {}) {
  const service = order.service && typeof order.service === 'object' ? order.service : {};
  const configured = order.billingType || service.billingType || order.quantityUnit || order.unit;
  const type = normalizeBillingType({ billingType: configured || (order.kilos != null ? 'PER_KG' : 'kg') });
  if (type === 'FLAT') return 'Flat rate';
  const raw = order.quantity ?? order.kilos;
  if (raw == null || raw === '') return 'N/A';
  const value = Number(raw);
  if (!Number.isFinite(value) || value <= 0) return 'N/A';
  if (type === 'PER_LOAD') return `Loads: ${Number.isInteger(value) ? value : value.toFixed(2)} ${value === 1 ? 'load' : 'loads'}`;
  if (type === 'PER_PIECE') return `Pieces: ${Number.isInteger(value) ? value : value.toFixed(2)} ${value === 1 ? 'piece' : 'pieces'}`;
  return `Weight: ${value.toFixed(2)} kg`;
}

export function previewOrderTotal(service, quantity, addOnRows = [], addOnCatalog = []) {
  if (!service) return { serviceSubtotal: 0, addOnsSubtotal: 0, total: 0, serviceLine: 0, addOnLines: [] };
  const type = normalizeBillingType(service);
  const quantityNumber = type === 'FLAT' ? 1 : Number(quantity);
  if (!Number.isFinite(quantityNumber) || quantityNumber <= 0) return { serviceSubtotal: 0, addOnsSubtotal: 0, total: 0, serviceLine: 0, addOnLines: [] };
  let billedQuantity = quantityNumber;
  if (type === 'PER_KG' && Number(service.minimumKg) > billedQuantity) billedQuantity = Number(service.minimumKg);
  const quantityHundredths = type === 'FLAT' ? 100 : Math.round(billedQuantity * 100);
  const serviceSubtotalCents = roundScaledCents(cents(service.pricePerUnit ?? service.price), quantityHundredths);
  const addOnLines = addOnRows.map((row) => {
    const item = addOnCatalog.find((entry) => entry.id === row.addOnId);
    const count = Number(row.quantity);
    const lineCents = item && Number.isInteger(count) && count > 0 ? cents(item.addOnPrice ?? item.price) * count : 0;
    return { addOnId: row.addOnId || '', quantity: Number.isInteger(count) ? count : 0, name: item?.itemName || item?.name || '', lineTotal: lineCents / 100 };
  });
  const addOnsSubtotalCents = addOnLines.reduce((sum, row) => sum + Math.round(row.lineTotal * 100), 0);
  return { serviceSubtotal: serviceSubtotalCents / 100, addOnsSubtotal: addOnsSubtotalCents / 100,
    total: (serviceSubtotalCents + addOnsSubtotalCents) / 100, serviceLine: serviceSubtotalCents / 100, addOnLines };
}
