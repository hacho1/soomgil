import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { getSubcategoryIcon, subcategoryIcons } from "./subcategoryIcons.js";

const categories = JSON.parse(readFileSync(new URL("../data/categories.json", import.meta.url), "utf8"));
const tones = new Set(["sky", "green", "gold", "clay", "rose", "violet"]);

test("every subcategory has explicitly assigned artwork", () => {
  assert.deepEqual(Object.keys(subcategoryIcons).sort(), categories.map(group => group.id).sort());
  for (const group of categories) {
    assert.deepEqual(Object.keys(subcategoryIcons[group.id]).sort(), [...group.items].sort());
    for (const topic of group.items) {
      const art = getSubcategoryIcon(group.id, topic);
      assert.equal(art, subcategoryIcons[group.id][topic]);
      assert.ok(tones.has(art.tone), `${group.id}/${topic}: unknown palette`);
    }
  }
});

test("siblings have distinct symbols even without their colors", () => {
  for (const group of categories) {
    const silhouettes = group.items.map(topic => {
      const { Icon, Detail } = getSubcategoryIcon(group.id, topic);
      return [Icon, Detail].filter(Boolean).map(Symbol => renderToStaticMarkup(createElement(Symbol))).join("");
    });
    assert.equal(new Set(silhouettes).size, group.items.length, group.id);
  }
});

test("all bundled main and supporting icons render valid pictograms", () => {
  for (const items of Object.values(subcategoryIcons)) {
    for (const { Icon, Detail } of Object.values(items)) {
      for (const Symbol of [Icon, Detail].filter(Boolean)) {
        const svg = renderToStaticMarkup(createElement(Symbol, { size: 30, "aria-hidden": true }));
        assert.match(svg, /<svg/);
        assert.match(svg, /<(path|circle|rect|line|polyline|polygon|ellipse)\b/);
        assert.doesNotMatch(svg, /undefined|NaN/);
      }
    }
  }
});

test("missing or inherited keys use a safe fallback", () => {
  const fallback = getSubcategoryIcon("missing", "missing");
  assert.equal(fallback.Icon.displayName, "Compass");
  assert.equal(getSubcategoryIcon("cafe", "missing"), fallback);
  assert.equal(getSubcategoryIcon("constructor", "name"), fallback);
  assert.equal(getSubcategoryIcon("cafe", "toString"), fallback);
});
