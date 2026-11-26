import assert from 'node:assert/strict';
import test, { after } from 'node:test';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import react from '@vitejs/plugin-react';
import { createServer } from 'vite';
import { fileURLToPath } from 'node:url';

const vite = await createServer({
  configFile: false,
  root: fileURLToPath(new URL('../..', import.meta.url)),
  plugins: [react()],
  appType: 'custom',
  logLevel: 'silent',
  server: { middlewareMode: true, fs: { strict: false } },
});
const { default: OrderStatusTracker, TRACKER_STAGES, mapStageSteps, stageDisplayLabel } = await vite.ssrLoadModule('/src/components/OrderStatusTracker.jsx');
after(async () => vite.close());

const HISTORY = [
  { stage: 'RECEIVED', changedAt: '2026-11-01T08:00:00Z', changedByName: 'Alice' },
  { stage: 'WASHING',  changedAt: '2026-11-01T09:00:00Z', changedByName: 'Bob'   },
];

test('tracker exposes the six canonical stages in order', () => {
  assert.deepEqual(TRACKER_STAGES, ['RECEIVED', 'WASHING', 'DRYING', 'FOLDING', 'READY', 'CLAIMED']);
});

test('RECEIVED keeps its stored enum but displays Pending with a clock icon', () => {
  const first = mapStageSteps([], 'RECEIVED', false)[0];
  assert.equal(first.stage, 'RECEIVED');
  assert.equal(first.label, 'Pending');
  assert.equal(stageDisplayLabel('RECEIVED'), 'Pending');
  assert.ok(first.Icon);
});

test('steps before current are completed, current is current, future steps are future', () => {
  const steps = mapStageSteps(HISTORY, 'WASHING', true);
  assert.equal(steps.find(s => s.stage === 'RECEIVED').state, 'completed');
  assert.equal(steps.find(s => s.stage === 'WASHING').state,  'current');
  assert.equal(steps.find(s => s.stage === 'DRYING').state,   'future');
  assert.equal(steps.find(s => s.stage === 'CLAIMED').state,  'future');
});

test('staff name is included when showStaffName is true', () => {
  const steps = mapStageSteps(HISTORY, 'WASHING', true);
  assert.equal(steps.find(s => s.stage === 'RECEIVED').staffName, 'Alice');
  assert.equal(steps.find(s => s.stage === 'WASHING').staffName,  'Bob');
  assert.equal(steps.find(s => s.stage === 'DRYING').staffName,   null);
});

test('public view hides staff names when showStaffName is false', () => {
  const steps = mapStageSteps(HISTORY, 'WASHING', false);
  for (const step of steps) {
    assert.equal(step.staffName, null, `Step ${step.stage} must not expose staff name on public view`);
  }
});

test('timestamp is null for future steps and present for reached steps with history', () => {
  const steps = mapStageSteps(HISTORY, 'WASHING', false);
  assert.ok(steps.find(s => s.stage === 'RECEIVED').timestamp !== null);
  assert.ok(steps.find(s => s.stage === 'WASHING').timestamp  !== null);
  assert.equal(steps.find(s => s.stage === 'DRYING').timestamp, null);
});

test('unknown currentStage marks all steps as future', () => {
  const steps = mapStageSteps([], 'UNKNOWN_STAGE', false);
  for (const step of steps) {
    assert.equal(step.state, 'future', `Step ${step.stage} should be future when stage is unknown`);
  }
});

test('null history produces no timestamps or staff names but preserves states', () => {
  const steps = mapStageSteps(null, 'FOLDING', false);
  const folding = steps.find(s => s.stage === 'FOLDING');
  assert.equal(folding.state, 'current');
  assert.equal(folding.timestamp, null);
  assert.equal(folding.staffName, null);
});

test('lucide forwardRef icons render as JSX and malformed history falls back safely', () => {
  const html = renderToStaticMarkup(React.createElement(OrderStatusTracker, {
    stageHistory: { stage: 'RECEIVED' }, currentStage: 'WASHING', showStaffName: false,
  }));
  assert.match(html, /Pending/);
  assert.match(html, /Washing/);
  assert.match(html, /<svg/);
});

test('all 6 stages have a label and an icon component', () => {
  const steps = mapStageSteps([], 'RECEIVED', false);
  for (const step of steps) {
    assert.ok(step.label, `${step.stage} must have a label`);
    assert.ok(step.Icon);
  }
});

test('complete journey marks all steps completed or current with CLAIMED', () => {
  const fullHistory = TRACKER_STAGES.map(stage => ({
    stage, changedAt: '2026-11-01T10:00:00Z', changedByName: 'Staff',
  }));
  const steps = mapStageSteps(fullHistory, 'CLAIMED', true);
  for (const step of steps) {
    assert.ok(step.state === 'completed' || step.state === 'current',
      `All stages should be completed or current at CLAIMED, got ${step.state} for ${step.stage}`);
  }
  assert.equal(steps.at(-1).state, 'current');
});
