import test from 'node:test';
import assert from 'node:assert/strict';
import { getNearbyGrouped, reverseGeocode, searchShops } from './publicShopDirectory.js';
import { CEBU_MUNICIPALITIES, DEFAULT_MUNICIPALITY, DEFAULT_PROVINCE } from './cebuMunicipalities.js';

test('getNearbyGrouped builds query string with coordinates, municipality, and radiusKm', async () => {
  const originalFetch = globalThis.fetch;
  let capturedUrl = '';
  globalThis.fetch = async (url) => {
    capturedUrl = String(url);
    return {
      ok: true,
      json: async () => ({ customerMunicipality: 'Consolacion', ownMunicipalityShops: [], hasOwnMunicipalityShops: false, nearbyGroups: [] }),
    };
  };
  try {
    await getNearbyGrouped({ lat: 10.3772, lng: 123.9578, municipality: 'Consolacion', radiusKm: 15 });
    assert.match(capturedUrl, /\/api\/public\/shops\/nearby\?/);
    assert.match(capturedUrl, /lat=10\.3772/);
    assert.match(capturedUrl, /lng=123\.9578/);
    assert.match(capturedUrl, /municipality=Consolacion/);
    assert.match(capturedUrl, /radiusKm=15/);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('getNearbyGrouped omits coordinates cleanly for manual municipality fallback', async () => {
  const originalFetch = globalThis.fetch;
  let capturedUrl = '';
  globalThis.fetch = async (url) => {
    capturedUrl = String(url);
    return { ok: true, json: async () => ({}) };
  };
  try {
    await getNearbyGrouped({ municipality: 'Liloan' });
    assert.ok(!capturedUrl.includes('lat='));
    assert.ok(!capturedUrl.includes('lng='));
    assert.match(capturedUrl, /municipality=Liloan/);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('reverseGeocode calls /api/public/shops/reverse-geocode with lat and lng', async () => {
  const originalFetch = globalThis.fetch;
  let capturedUrl = '';
  globalThis.fetch = async (url) => {
    capturedUrl = String(url);
    return { ok: true, json: async () => ({ municipality: 'Consolacion', province: 'Cebu' }) };
  };
  try {
    const res = await reverseGeocode({ lat: 10.3772, lng: 123.9578 });
    assert.match(capturedUrl, /\/api\/public\/shops\/reverse-geocode\?lat=10\.3772&lng=123\.9578/);
    assert.equal(res.municipality, 'Consolacion');
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('searchShops keeps flat endpoint working', async () => {
  const originalFetch = globalThis.fetch;
  let capturedUrl = '';
  globalThis.fetch = async (url) => {
    capturedUrl = String(url);
    return { ok: true, json: async () => [] };
  };
  try {
    await searchShops({ search: 'clean', page: 0 });
    assert.match(capturedUrl, /\/api\/public\/shops\?search=clean&page=0$/);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('CEBU_MUNICIPALITIES starts with the required Cebu priority list', () => {
  assert.deepEqual(CEBU_MUNICIPALITIES.slice(0, 8), [
    'Consolacion',
    'Liloan',
    'Mandaue',
    'Lapu-Lapu',
    'Cebu City',
    'Talisay',
    'Compostela',
    'Danao',
  ]);
  assert.equal(DEFAULT_MUNICIPALITY, 'Consolacion');
  assert.equal(DEFAULT_PROVINCE, 'Cebu');
});
