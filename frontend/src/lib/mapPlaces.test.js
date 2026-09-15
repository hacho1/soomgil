import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createHash } from "node:crypto";
import { getMapStatus, getMapType, hasMapCoordinates, mapPlaceLabel, mapTypes, matchesMapCategory } from "./mapPlaces.js";
import categories from "../data/categories.json" with { type: "json" };
import art from "../data/map-art.json" with { type: "json" };

test("map legend covers all nine discovery categories and all places", () => {
  assert.equal(mapTypes.length, 10);
  assert.equal(new Set(mapTypes.map(type => type.id)).size, 10);
  for (const category of categories) assert.equal(getMapType(category.id).id, category.id);
});

test("specific category icons survive broader tourism aliases", () => {
  assert.equal(getMapType("nature").id, "nature");
  assert.equal(getMapType("culture").id, "culture");
  assert.equal(getMapType("history").id, "culture");
  assert.equal(getMapType("leisure").id, "experience");
  assert.equal(getMapType("unrecognized").id, "");
  assert.equal(getMapType(null).id, "");
});

test("legend navigation and visible results share taxonomy matching", () => {
  for (const category of categories) {
    for (const alias of category.types) assert.equal(matchesMapCategory({ category: alias }, category.id), true);
  }
  assert.equal(matchesMapCategory({ category: "nature" }, "tour"), true);
  assert.equal(matchesMapCategory({ category: "food" }, "cafe"), false);
  assert.equal(matchesMapCategory({ category: "other" }, ""), true);
});

test("favorite and following indicators are independent and never replace the place category", () => {
  const place = { id: 7, name: "작은 카페", category: "cafe" };
  for (const favorite of [false, true]) for (const following of [false, true]) {
    const status = getMapStatus(place, new Set(favorite ? ["7"] : []), new Set(following ? ["7"] : []));
    assert.deepEqual(status, { favorite, following });
    const label = mapPlaceLabel(place, status);
    assert.ok(label.includes("카페"));
    assert.equal(label.includes("찜한 장소"), favorite);
    assert.equal(label.includes("팔로잉 추천"), following);
    assert.equal(getMapType(place.category).image, "/assets/map/cafe.png");
  }
});

test("invalid coordinates never become map markers", () => {
  assert.equal(hasMapCoordinates({ mapx: "127.1", mapy: "37.5" }), true);
  assert.equal(hasMapCoordinates({ mapx: 126.5, mapy: 33.3 }), true);
  for (const place of [{}, { mapx: "", mapy: "" }, { mapx: null, mapy: null }, { mapx: 0, mapy: 0 }, { mapx: "NaN", mapy: 37 }, { mapx: 37, mapy: 127 }, { mapx: 128, mapy: Infinity }]) {
    assert.equal(hasMapCoordinates(place), false);
  }
});

test("all map artwork is local, pinned, intact PNG imagery with its license", () => {
  const directory = new URL("../../public/assets/map/", import.meta.url);
  const inventory = JSON.parse(readFileSync(new URL("integrity.json", directory), "utf8"));
  assert.match(art.revision, /^[a-f0-9]{40}$/);
  assert.equal(art.license, "MIT");
  assert.equal(inventory.length, 10);
  for (const asset of Object.values(art.assets)) {
    assert.match(asset.file, /^[a-z0-9-]+\.png$/);
    const bytes = readFileSync(new URL(asset.file, directory));
    assert.equal(bytes.subarray(0, 8).toString("hex"), "89504e470d0a1a0a");
    assert.equal(bytes.readUInt32BE(16), 256);
    assert.equal(bytes.readUInt32BE(20), 256);
    const stored = inventory.find(item => item.file === asset.file);
    assert.equal(stored.bytes, bytes.length);
    assert.equal(stored.sha256, createHash("sha256").update(bytes).digest("hex"));
  }
  assert.match(readFileSync(new URL("LICENSE.txt", directory), "utf8"), /Copyright \(c\) Microsoft Corporation/);
});
