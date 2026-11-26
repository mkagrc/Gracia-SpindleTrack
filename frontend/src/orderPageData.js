const asRecord = (value) => value && typeof value === 'object' && !Array.isArray(value) ? value : {};
const asRecords = (value) => Array.isArray(value) ? value.filter((item) => item && typeof item === 'object' && !Array.isArray(item)) : [];
const asText = (value) => typeof value === 'string' ? value : '';
const asCount = (value, fallback = 0) => {
  const number = Number(value);
  return Number.isFinite(number) && number >= 0 ? number : fallback;
};

export function normalizeOrderDetailResponse(value) {
  const source = asRecord(value);
  const order = asRecord(source.order);
  const history = Array.isArray(source.history)
    ? source.history
    : Array.isArray(source.stageHistory) ? source.stageHistory : [];
  return {
    ...source,
    order: Object.keys(order).length ? order : null,
    history,
    priceLog: asRecords(source.priceLog),
    paymentHistory: asRecords(source.paymentHistory),
  };
}

export function normalizePagedRows(value) {
  const source = asRecord(value);
  const content = asRecords(source.content);
  return {
    ...source,
    content,
    totalElements: asCount(source.totalElements, content.length),
    totalPages: Math.max(1, asCount(source.totalPages, 1)),
  };
}

export function normalizeReadyOrderLists(value) {
  const source = asRecord(value);
  return {
    unclaimed: asRecords(source.unclaimed),
    claimed: asRecords(source.claimed),
  };
}

export function normalizePublicOrderLookup(value) {
  const source = asRecord(value);
  const rawQuantity = source.kilos ?? source.quantity;
  const quantity = rawQuantity == null ? null : Number(rawQuantity);
  return {
    ...source,
    kilos: quantity !== null && Number.isFinite(quantity) ? quantity : null,
    orderCode: asText(source.orderCode),
    maskedMobile: asText(source.maskedMobile),
    currentStage: asText(source.currentStage),
    paymentStatus: asText(source.paymentStatus),
    paymentMethodPreference: asText(source.paymentMethodPreference) || null,
    paymentTiming: asText(source.paymentTiming) || 'PAY_NOW',
    unit: asText(source.quantityUnit) || asText(source.unit),
    quantityUnit: asText(source.quantityUnit) || asText(source.unit),
    billingType: asText(source.billingType),
    quantity: quantity !== null && Number.isFinite(quantity) ? quantity : null,
    stageHistory: Array.isArray(source.stageHistory) ? source.stageHistory : [],
  };
}

export function isPayPalCheckoutEligible(order) {
  if (!order) return false;
  const status = order.paymentStatus || 'UNPAID';
  if (status === 'PAID') return false;
  const stage = order.stage || order.currentStage;
  if (stage === 'CLAIMED') return false;
  if (order.paymentMethod === 'PAYMONGO') return false;
  const pref = order.paymentMethodPreference || null;
  const method = order.paymentMethod || null;
  const isOnlineOrder = pref === 'ONLINE' || (pref === null && method === 'PAYPAL');
  if (!isOnlineOrder) return false;
  const timing = order.paymentTiming || 'PAY_NOW';
  return timing === 'PAY_NOW' || stage === 'READY';
}

function normalizeShopRecord(shop) {
  return {
    ...shop,
    ...(shop.municipality !== undefined ? { municipality: asText(shop.municipality) || null } : {}),
    ...(shop.province !== undefined ? { province: asText(shop.province) || null } : {}),
  };
}

export function normalizePublicShopList(value) {
  return asRecords(value).map(normalizeShopRecord);
}

export function normalizePublicShopDetail(value) {
  const source = asRecord(value);
  return {
    ...normalizeShopRecord(source),
    services: asRecords(source.services),
    addOns: asRecords(source.addOns),
  };
}

export function normalizePublicNearbyResult(value) {
  const source = asRecord(value);
  const ownMunicipalityShops = asRecords(source.ownMunicipalityShops).map(normalizeShopRecord);
  const nearbyGroups = asRecords(source.nearbyGroups)
    .map((group) => ({
      municipality: asText(group.municipality) || 'Other nearby',
      shops: asRecords(group.shops).map(normalizeShopRecord),
    }))
    .filter((group) => group.shops.length > 0);
  return {
    customerMunicipality: asText(source.customerMunicipality) || null,
    ownMunicipalityShops,
    hasOwnMunicipalityShops: Boolean(source.hasOwnMunicipalityShops) && ownMunicipalityShops.length > 0,
    nearbyGroups,
  };
}
