import assert from 'node:assert/strict';
import test from 'node:test';
import {
  normalizeOrderDetailResponse,
  normalizePagedRows,
  normalizePublicNearbyResult,
  normalizePublicOrderLookup,
  normalizePublicShopDetail,
  normalizePublicShopList,
  normalizeReadyOrderLists,
} from './orderPageData.js';

const ORDER = {
  id: 'order-1', orderCode: 'O-000123', stage: 'READY', paymentStatus: 'UNPAID',
  customerName: 'Sample Customer', serviceName: 'Wash and fold', quantity: 2.5,
  unit: 'kg', totalPrice: 100,
};

for (const role of ['staff', 'owner read-only']) {
  test(`${role} order detail shape retains order, stage history, and price log arrays`, () => {
    const detail = normalizeOrderDetailResponse({
      order: ORDER,
      history: [{ stage: 'RECEIVED', changedAt: '2026-11-01T08:00:00Z', changedByName: 'Staff' }],
      priceLog: [{ id: 'price-1', newPrice: 100 }],
    });
    assert.equal(detail.order.orderCode, 'O-000123');
    assert.equal(detail.order.quantity, 2.5);
    assert.equal(detail.history[0].stage, 'RECEIVED');
    assert.equal(detail.priceLog[0].newPrice, 100);
    assert.deepEqual(detail.paymentHistory, []);
  });
}

test('customer order detail accepts history and stageHistory payload variants while retaining payment events', () => {
  const detail = normalizeOrderDetailResponse({
    order: { ...ORDER, shopName: 'Sample Laundry' },
    stageHistory: [{ stage: 'READY', changedAt: '2026-11-01T10:00:00Z' }],
    paymentHistory: [{ status: 'PAID', method: 'CASH' }],
  });
  assert.equal(detail.order.shopName, 'Sample Laundry');
  assert.equal(detail.history[0].stage, 'READY');
  assert.equal(detail.paymentHistory[0].method, 'CASH');
});

test('detail and page payloads tolerate missing or malformed arrays without exposing null rows to maps', () => {
  assert.deepEqual(normalizeOrderDetailResponse({ order: null, history: {}, priceLog: [null], paymentHistory: 'bad' }), {
    order: null, history: [], priceLog: [], paymentHistory: [],
  });
  assert.deepEqual(normalizePagedRows({ content: [null, ORDER], totalElements: 1, totalPages: 1 }).content, [ORDER]);
  assert.deepEqual(normalizePagedRows({ content: {}, totalElements: {} }).content, []);
});

test('public lookup results normalize stageHistory and display fields before rendering', () => {
  const result = normalizePublicOrderLookup({
    orderCode: 'O-000123', maskedMobile: '•••4567', currentStage: 'READY',
    quantity: '2.5', unit: 'kg', paymentStatus: 'UNPAID', stageHistory: [{ stage: 'READY' }],
  });
  assert.equal(result.orderCode, 'O-000123');
  assert.equal(result.quantity, 2.5);
  assert.equal(result.stageHistory[0].stage, 'READY');
  assert.equal(normalizePublicOrderLookup({ orderCode: {}, stageHistory: {} }).orderCode, '');
  assert.deepEqual(normalizePublicOrderLookup({ stageHistory: {} }).stageHistory, []);
});

test('Unclaimed and Claimed tab data stays in separate arrays and malformed values become empty lists', () => {
  const lists = normalizeReadyOrderLists({
    unclaimed: [{ ...ORDER, readyAt: '2026-11-01T10:00:00Z' }, null],
    claimed: [{ ...ORDER, stage: 'CLAIMED', claimedAt: '2026-11-02T10:00:00Z' }],
  });
  assert.equal(lists.unclaimed.length, 1);
  assert.equal(lists.unclaimed[0].stage, 'READY');
  assert.equal(lists.claimed.length, 1);
  assert.equal(lists.claimed[0].stage, 'CLAIMED');
  assert.deepEqual(normalizeReadyOrderLists({ unclaimed: {}, claimed: null }), { unclaimed: [], claimed: [] });
});

test('public shop list and detail service collections are normalized before rendering', () => {
  assert.deepEqual(normalizePublicShopList([null, { shopId: 'shop-1', name: 'Clean' }]), [
    { shopId: 'shop-1', name: 'Clean' },
  ]);
  assert.deepEqual(normalizePublicShopList({ content: [] }), []);
  assert.deepEqual(normalizePublicShopDetail({ shopId: 'shop-1', services: {} }), {
    shopId: 'shop-1', services: [], addOns: [],
  });
});

test('public lookup weight accepts either canonical kilos or legacy quantity', () => {
  assert.equal(normalizePublicOrderLookup({ kilos: 4.2 }).quantity, 4.2);
  assert.equal(normalizePublicOrderLookup({ quantity: 3.1 }).kilos, 3.1);
});

test('public nearby grouped result normalizes own municipality and nearby groups safely', () => {
  const result = normalizePublicNearbyResult({
    customerMunicipality: 'Consolacion',
    ownMunicipalityShops: [{ shopId: 'shop-1', name: 'Wash Co', municipality: 'Consolacion', province: 'Cebu', distanceKm: 1.2 }],
    hasOwnMunicipalityShops: true,
    nearbyGroups: [
      { municipality: 'Liloan', shops: [{ shopId: 'shop-2', name: 'Liloan Wash', municipality: 'Liloan' }] },
      { municipality: 'EmptyGroup', shops: [] },
    ],
  });
  assert.equal(result.customerMunicipality, 'Consolacion');
  assert.equal(result.hasOwnMunicipalityShops, true);
  assert.equal(result.ownMunicipalityShops[0].shopId, 'shop-1');
  assert.equal(result.nearbyGroups.length, 1);
  assert.equal(result.nearbyGroups[0].municipality, 'Liloan');

  assert.deepEqual(normalizePublicNearbyResult(null), {
    customerMunicipality: null,
    ownMunicipalityShops: [],
    hasOwnMunicipalityShops: false,
    nearbyGroups: [],
  });
});
