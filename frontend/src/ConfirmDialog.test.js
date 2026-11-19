import assert from 'node:assert/strict';
import test, { after } from 'node:test';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import react from '@vitejs/plugin-react';
import { createServer } from 'vite';
import { fileURLToPath } from 'node:url';

const vite = await createServer({
  configFile: false,
  root: fileURLToPath(new URL('..', import.meta.url)),
  plugins: [react()],
  appType: 'custom',
  logLevel: 'silent',
  server: { middlewareMode: true, fs: { strict: false } },
});
const { ConfirmDialog, executeConfirmDialogAction, handleDialogKeyDown, requestDialogClose } = await vite.ssrLoadModule('/src/ConfirmDialog.jsx');
after(async () => vite.close());

function renderDialog(props = {}) {
  return renderToStaticMarkup(React.createElement(ConfirmDialog, {
    title: 'Remove document?',
    message: 'Remove this verification file?',
    confirmLabel: 'Remove',
    cancelLabel: 'Cancel',
    tone: 'danger',
    onConfirm: async () => {},
    onCancel: () => {},
    ...props,
  }));
}

test('dialog opens with accessible modal semantics and danger Cancel autofocus', () => {
  const markup = renderDialog();
  assert.match(markup, /role="dialog"/);
  assert.match(markup, /aria-modal="true"/);
  assert.match(markup, /aria-labelledby="[^"]+"/);
  assert.match(markup, /Remove document\?/);
  assert.match(markup, /data-autofocus="cancel"/);
});

test('confirm invokes the action and preserves the returned success state', async () => {
  let calls = 0;
  const result = await executeConfirmDialogAction(async () => { calls += 1; }, () => 'formatted error');
  assert.equal(calls, 1);
  assert.deepEqual(result, { ok: true, errorMessage: '' });
});

test('cancel button and Escape request close when idle but Escape cannot close while loading', () => {
  const cancelled = [];
  assert.equal(renderDialog().includes('>Cancel</button>'), true);
  assert.equal(requestDialogClose(false, () => cancelled.push('cancel-button')), true);
  assert.deepEqual(cancelled, ['cancel-button']);
  assert.equal(handleDialogKeyDown({ key: 'Escape', preventDefault() {} }, {
    loading: false,
    onCancel: () => cancelled.push('cancel'),
  }), true);
  assert.deepEqual(cancelled, ['cancel-button', 'cancel']);

  assert.equal(handleDialogKeyDown({ key: 'Escape', preventDefault() {} }, {
    loading: true,
    onCancel: () => cancelled.push('loading-cancel'),
  }), false);
  assert.deepEqual(cancelled, ['cancel-button', 'cancel']);
});

test('failed confirmation surfaces its formatted backend error inside the dialog', async () => {
  const failure = { response: { data: { message: 'Document is required.' } } };
  const result = await executeConfirmDialogAction(async () => { throw failure; }, (error) => error.response.data.message);
  assert.deepEqual(result, { ok: false, errorMessage: 'Document is required.' });
  const markup = renderDialog({ error: result.errorMessage });
  assert.match(markup, /role="alert"/);
  assert.match(markup, /Document is required\./);
});

test('Tab focus wraps from the final control to the first control', () => {
  let focused = '';
  let prevented = false;
  const first = { focus: () => { focused = 'first'; } };
  const last = { focus: () => { focused = 'last'; } };
  const dialogElement = {
    querySelectorAll: () => [first, last],
    contains: () => true,
  };
  assert.equal(handleDialogKeyDown({
    key: 'Tab', shiftKey: false, preventDefault: () => { prevented = true; },
  }, { loading: false, dialogElement, activeElement: last }), true);
  assert.equal(prevented, true);
  assert.equal(focused, 'first');
});
