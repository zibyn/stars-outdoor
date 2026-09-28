import { test } from 'node:test';
import assert from 'node:assert/strict';
import { lintStyle } from './lint.mjs';

const style = (filter) => ({
  version: 8,
  sources: { c: { type: 'vector', url: 'pmtiles://x' } },
  layers: [{ id: 'contour', type: 'line', source: 'c', 'source-layer': 'contours', filter }],
});

test('flags value-typed get passed straight into a typed operator', () => {
  const errors = lintStyle(style(['==', ['%', ['get', 'ele'], 100], 0]));
  assert.equal(errors.length, 1);
  assert.match(errors[0], /contour.*%.*get/);
});

test('accepts explicit coercion', () => {
  assert.deepEqual(lintStyle(style(['==', ['%', ['to-number', ['get', 'ele']], 100], 0])), []);
});

test('accepts get in value-accepting operators', () => {
  assert.deepEqual(lintStyle(style(['==', ['get', 'kind'], 'path'])), []);
});

test('reports spec errors', () => {
  assert.equal(lintStyle({ ...style(null), layers: [{ id: 'x', type: 'nope' }] }).length > 0, true);
});
