import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const frontendDir = path.resolve(fileURLToPath(new URL('..', import.meta.url)));
const repoRoot = path.resolve(frontendDir, '..');

// Common UTF-8-interpreted-as-Latin1/CP1252 mojibake patterns built with escapes.
const MOJIBAKE_REGEX = new RegExp(
  [
    '\u00c2[\u0080-\u00bf]',
    '\u00e2[\u0080-\u0086][\u0080-\u00ff]?',
    '\u00c3[\u0080-\u00bf]',
    '\u0393[\u00c7\u00e5\u00e9\u0192\u00f6]',
  ].join('|'),
  'g',
);

function collectFiles(target) {
  if (!fs.existsSync(target)) return [];
  const stat = fs.statSync(target);
  if (stat.isFile()) return [target];
  const entries = fs.readdirSync(target, { withFileTypes: true });
  return entries.flatMap((entry) => collectFiles(path.join(target, entry.name)));
}

test('source files contain no UTF-8 BOM or mojibake sequences and index.html declares UTF-8', () => {
  const indexPath = path.join(frontendDir, 'index.html');
  const indexHtml = fs.readFileSync(indexPath, 'utf8');
  assert.match(indexHtml, /<meta\s+charset=["']?utf-8["']?\s*\/?>/i);

  const targets = [
    ...collectFiles(path.join(frontendDir, 'src')),
    indexPath,
    ...collectFiles(path.join(repoRoot, 'backend', 'src', 'main')),
  ];

  const violations = [];
  for (const filePath of targets) {
    const raw = fs.readFileSync(filePath);
    if (raw.length >= 3 && raw[0] === 0xef && raw[1] === 0xbb && raw[2] === 0xbf) {
      violations.push(`${path.relative(repoRoot, filePath)}: UTF-8 BOM found`);
    }
    const text = raw.toString('utf8');
    const matches = text.match(MOJIBAKE_REGEX);
    if (matches?.length) {
      violations.push(`${path.relative(repoRoot, filePath)}: ${matches.length} mojibake sequence(s)`);
    }
  }

  assert.deepEqual(violations, []);
});
