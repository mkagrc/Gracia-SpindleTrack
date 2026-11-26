import assert from 'node:assert/strict';
import test from 'node:test';
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
const { default: ErrorBoundary } = await vite.ssrLoadModule('/src/ErrorBoundary.jsx');
test.after(async () => vite.close());

test('Error Boundary displays a friendly reload fallback after a rendering error', () => {
  const boundary = new ErrorBoundary({ children: null });
  boundary.state = ErrorBoundary.getDerivedStateFromError(new Error('private diagnostic text'));
  const html = renderToStaticMarkup(boundary.render());
  assert.match(html, /Something went wrong\. Reload the page/);
  assert.match(html, /Reload page/);
  assert.match(html, /role="alert"/);
});

test('Error Boundary console logging excludes an error message and stack contents', () => {
  const boundary = new ErrorBoundary({ children: null });
  const calls = [];
  const original = console.error;
  console.error = (...args) => calls.push(args);
  try {
    boundary.componentDidCatch(new Error('private token or personal data'), { componentStack: 'sensitive stack' });
  } finally {
    console.error = original;
  }
  assert.deepEqual(calls, [['[SpindleTrack] A rendering error was caught; details omitted.']]);
});
