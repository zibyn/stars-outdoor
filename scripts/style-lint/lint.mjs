// Validate MapLibre styles against the spec, plus a stricter check native MapLibre needs (spec §4.5 #3):
// a value-typed lookup (["get", …]) fed straight into a typed operator makes the native engine silently drop the layer.
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { validateStyleMin, CompoundExpression } from '@maplibre/maplibre-gl-style-spec';

// ponytail: only direct lookups; untyped values routed via coalesce/at/match slip through. Add when a style needs them.
const VALUE_LOOKUPS = new Set(['get', 'feature-state']);

// Param kinds an operator accepts at argument position i, across all overloads.
function paramKinds(op, i) {
  const def = CompoundExpression.definitions[op];
  const sigs = Array.isArray(def) ? [def[1]] : def.overloads.map((o) => o[0]);
  return sigs.map((params) => (Array.isArray(params) ? params[i]?.kind : params.type.kind));
}

function walk(expr, where, errors) {
  if (!Array.isArray(expr) || expr[0] === 'literal') return;
  const op = expr[0];
  expr.slice(1).forEach((arg, i) => {
    if (typeof op !== 'string' || !CompoundExpression.definitions[op] || !Array.isArray(arg) || !VALUE_LOOKUPS.has(arg[0])) {
      return walk(arg, where, errors);
    }
    const kinds = paramKinds(op, i);
    if (kinds.every((k) => k && k !== 'value')) {
      errors.push(`${where}: ["${op}"] gets untyped ${JSON.stringify(arg)}; wrap it in ["to-${kinds[0]}", …]`);
    }
    walk(arg, where, errors);
  });
}

export function lintStyle(style) {
  const errors = validateStyleMin(style).map((e) => e.message);
  for (const layer of style.layers ?? []) {
    walk(layer.filter, `layers.${layer.id}.filter`, errors);
    for (const group of ['layout', 'paint']) {
      for (const [prop, value] of Object.entries(layer[group] ?? {})) walk(value, `layers.${layer.id}.${prop}`, errors);
    }
  }
  return errors;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  let failed = false;
  for (const file of process.argv.slice(2)) {
    for (const e of lintStyle(JSON.parse(readFileSync(file, 'utf8')))) {
      console.error(`${file}: ${e}`);
      failed = true;
    }
  }
  process.exit(failed ? 1 : 0);
}
