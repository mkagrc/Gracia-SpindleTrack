import assert from 'node:assert/strict';
import test from 'node:test';
import {
  billingTypeBadgeLabel,
  formatActualWeight,
  formatOrderQuantity,
  previewOrderTotal,
  quantityInputRules,
} from './orderPricing.js';

test('billing type drives the quantity input and fixed flat-rate value', () => {
  assert.deepEqual(quantityInputRules({ billingType: 'PER_KG' }), { billingType: 'PER_KG', label: 'Weight (kg)', inputType: 'number', min: 0.01, max: 999.99, step: 0.01, fixedQuantity: null });
  assert.equal(quantityInputRules({ unit: 'load' }).step, 1);
  assert.equal(quantityInputRules({ billingType: 'PER_LOAD' }).label, 'Number of loads');
  assert.equal(quantityInputRules({ billingType: 'PER_PIECE' }).label, 'Number of pieces');
  assert.equal(quantityInputRules({ billingType: 'FLAT' }).fixedQuantity, 1);
});

test('estimate math matches the server for each billing type and per-kilogram minimums', () => {
  assert.equal(previewOrderTotal({ billingType: 'PER_KG', pricePerUnit: 19.99 }, '2.35').total, 46.98);
  assert.equal(previewOrderTotal({ billingType: 'PER_KG', pricePerUnit: 19.99, minimumKg: 2.5 }, '1').serviceSubtotal, 49.98);
  assert.equal(previewOrderTotal({ billingType: 'PER_LOAD', pricePerUnit: 230 }, 2).total, 460);
  assert.equal(previewOrderTotal({ billingType: 'PER_PIECE', pricePerUnit: 12.5 }, 3).total, 37.5);
  assert.equal(previewOrderTotal({ billingType: 'FLAT', pricePerUnit: 45.23 }, 1).total, 45.23);
});

test('add-on estimate is itemized and currency calculation rounds half up to cents', () => {
  const result = previewOrderTotal({ billingType: 'PER_KG', pricePerUnit: 0.105 }, '1', [
    { addOnId: 'a', quantity: 2 }, { addOnId: 'b', quantity: 1 },
  ], [
    { id: 'a', itemName: 'Detergent', addOnPrice: 7.5 }, { id: 'b', itemName: 'Bleach', addOnPrice: 3.25 },
  ]);
  assert.deepEqual(result.addOnLines.map((line) => line.lineTotal), [15, 3.25]);
  assert.equal(result.serviceSubtotal, 0.11);
  assert.equal(result.addOnsSubtotal, 18.25);
  assert.equal(result.total, 18.36);
});

test('quantity display uses the correct label, singular/plural units, and never invents zero for legacy rows', () => {
  assert.equal(formatOrderQuantity({ quantity: 3.5, quantityUnit: 'kg' }), 'Weight: 3.50 kg');
  assert.equal(formatOrderQuantity({ quantity: 1, quantityUnit: 'load' }), 'Loads: 1 load');
  assert.equal(formatOrderQuantity({ quantity: 2, quantityUnit: 'load' }), 'Loads: 2 loads');
  assert.equal(formatOrderQuantity({ quantity: 1, unit: 'piece' }), 'Pieces: 1 piece');
  assert.equal(formatOrderQuantity({ quantity: 5, unit: 'piece' }), 'Pieces: 5 pieces');
  assert.equal(formatOrderQuantity({ quantity: 1, billingType: 'FLAT' }), 'Flat rate');
  assert.equal(formatOrderQuantity({ kilos: 4.25 }), 'Weight: 4.25 kg');
  assert.equal(formatOrderQuantity({ quantity: null, kilos: null }), 'N/A');
  assert.equal(formatOrderQuantity({ quantity: 0, quantityUnit: 'load' }), 'N/A');
  assert.equal(billingTypeBadgeLabel({ quantity: 2, quantityUnit: 'load' }), 'Per load');
  assert.equal(billingTypeBadgeLabel({ quantity: 1, billingType: 'FLAT' }), 'Flat rate');
  assert.equal(formatActualWeight({ actualWeightKg: 6.4 }), 'Actual weight: 6.40 kg');
  assert.equal(formatActualWeight({}), '');
});
