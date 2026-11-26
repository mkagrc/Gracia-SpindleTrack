/**
 * Public shop directory API calls.
 * No authentication required — uses plain fetch (no firebase token).
 */

const BASE = import.meta.env?.VITE_API_BASE_URL || 'http://localhost:8080';

async function directoryFetch(path) {
  const res = await fetch(`${BASE}${path}`);
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    const err = new Error(body?.message || `Request failed (${res.status})`);
    err.status = res.status;
    throw err;
  }
  return res.json();
}

/**
 * @param {{ search?: string, lat?: number, lng?: number, page?: number }} opts
 */
export function searchShops({ search = '', lat, lng, page = 0 } = {}) {
  const params = new URLSearchParams();
  if (search) params.set('search', search);
  if (lat != null) params.set('lat', lat);
  if (lng != null) params.set('lng', lng);
  params.set('page', page);
  return directoryFetch(`/api/public/shops?${params}`);
}

export function getShopDetail(shopId) {
  return directoryFetch(`/api/public/shops/${encodeURIComponent(shopId)}`);
}

/**
 * Returns shops grouped by municipality: own municipality first, then nearby areas.
 * @param {{ lat?: number, lng?: number, municipality?: string, radiusKm?: number }} opts
 */
export function getNearbyGrouped({ lat, lng, municipality = '', radiusKm = 15 } = {}) {
  const params = new URLSearchParams();
  if (lat != null) params.set('lat', lat);
  if (lng != null) params.set('lng', lng);
  if (municipality) params.set('municipality', municipality);
  params.set('radiusKm', radiusKm);
  return directoryFetch(`/api/public/shops/nearby?${params}`);
}

/**
 * Reverse geocodes a lat/lng to municipality and province using the backend Nominatim proxy.
 * @param {{ lat: number, lng: number }} opts
 * @returns {Promise<{ municipality: string|null, province: string|null }>}
 */
export function reverseGeocode({ lat, lng }) {
  const params = new URLSearchParams({ lat, lng });
  return directoryFetch(`/api/public/shops/reverse-geocode?${params}`);
}
