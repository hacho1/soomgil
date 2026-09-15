import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createHash } from "node:crypto";

const json = path => JSON.parse(readFileSync(new URL(path, import.meta.url), "utf8"));
const manifest = json("../data/badge-art.json");
const definitions = json("../../../backend/src/main/resources/data/badges.json");
const inventory = json("../../public/assets/badges/integrity.json");

test("all 24 badge IDs have distinct, locally bundled artwork", () => {
  assert.equal(definitions.length, 24);
  assert.deepEqual(Object.keys(manifest.assets).sort(), definitions.map(badge => badge.id).sort());
  assert.equal(new Set(Object.values(manifest.assets).map(asset => asset.file)).size, 24);
  assert.equal(manifest.license, "MIT");
});

test("badge PNG files are intact and keep the upstream license", () => {
  for (const asset of Object.values(manifest.assets)) {
    const bytes = readFileSync(new URL(`../../public/assets/badges/${asset.file}`, import.meta.url));
    assert.equal(bytes.subarray(1, 4).toString(), "PNG");
    assert.ok(bytes.readUInt32BE(16) >= 128);
    assert.equal(createHash("sha256").update(bytes).digest("hex"), inventory.find(item => item.file === asset.file).sha256);
  }
  const license = readFileSync(new URL("../../public/assets/badges/LICENSE.txt", import.meta.url), "utf8");
  assert.match(license, /MIT License/);
  assert.match(license, /Microsoft Corporation/);
});
