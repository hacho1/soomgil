import test from "node:test";
import assert from "node:assert/strict";
import { distanceLabel, mapPageSize, mergePlaces, nearbyCriteria, nearbyRadii, normalizeRegion, regionViews, scopedMapPlaces, viewPlaces } from "./mapDiscovery.js";

const bounds = { west: 127, east: 129, south: 36, north: 38 };
const sample = [
  { id: 1, name: "경계의 카페", category: "cafe", mapx: 127, mapy: 37.02 },
  { id: 2, name: "가까운 숲길", category: "walk", mapx: 127, mapy: 37.01 },
  { id: 3, name: "멀리 있는 해변", category: "tour", mapx: 127, mapy: 37.031 },
  { id: 4, name: "좌표 없음", mapx: "", mapy: "" },
];
const selection = { places: sample, scope: "nearby", center: { lat: 37, lng: 127 }, radiusKm: 3,
  distanceBetween: (a, b) => Math.round(Math.abs(a[0] - b[0]) * 100000) };

test("nearby mode never dumps the national catalog before a location is selected", () => {
  assert.deepEqual(scopedMapPlaces({ ...selection, center: null }), []);
  assert.deepEqual(scopedMapPlaces({ ...selection, radiusKm: 0 }), []);
  assert.deepEqual(scopedMapPlaces({ ...selection, radiusKm: NaN }), []);
  assert.equal(mapPageSize, 6);
});
test("nearby markers and cards share radius, distance order and category filtering", () => {
  assert.deepEqual(scopedMapPlaces(selection).map(p => p.id), [2, 1]);
  assert.deepEqual(scopedMapPlaces({ ...selection, radiusKm: 1 }).map(p => p.id), [2]);
  assert.deepEqual(scopedMapPlaces({ ...selection, category: "cafe" }).map(p => p.id), [1]);
  assert.deepEqual(scopedMapPlaces({ ...selection, distanceBetween: () => NaN }), []);
  assert.equal(sample[0].distanceMeters, undefined);
});
test("favorites and following keep only supplied places and are not limited to a nearby radius", () => {
  for (const scope of ["favorites", "following"]) {
    assert.deepEqual(scopedMapPlaces({ ...selection, scope, places: [sample[2]], center: null }).map(p => p.id), [3]);
    assert.deepEqual(scopedMapPlaces({ ...selection, scope, places: [] }), []);
  }
});
test("radius discovery crosses regional boundaries and rounds location before the server request", () => {
  assert.equal(nearbyCriteria(null, 3), null);
  assert.equal(nearbyCriteria({ lat: NaN, lng: 127 }, 3), null);
  assert.equal(nearbyCriteria({ lat: 48.85, lng: 2.35 }, 3), null);
  const criteria = nearbyCriteria({ lat: 37.1234567, lng: 127.7654321 }, 3, "walk");
  assert.deepEqual(criteria, { mapX: "127.765", mapY: "37.123", radius: 3100, category: "walk" });
  for (const radius of nearbyRadii) assert.ok(nearbyCriteria(selection.center, radius).radius <= 20000);
  assert.equal(distanceLabel(520), "520m");
  assert.equal(distanceLabel(1234), "1.2km");
});
test("only viewport places appear in the list, not all stored places", () => {
  const places = [{ id: 1, name: "동네 카페", category: "cafe", mapx: 128, mapy: 37 },
    { id: 2, name: "생태 숲길", category: "walk", mapx: 128.8, mapy: 37 },
    { id: 3, name: "제주 해변", category: "tour", mapx: 126.5, mapy: 33.3 },
    { id: 4, name: "잘못된 좌표", mapx: "", mapy: "" }];
  assert.deepEqual(viewPlaces(places, bounds).map(p => p.id), [2, 1]);
  assert.deepEqual(viewPlaces(places, bounds, "near").map(p => p.id), [1, 2]);
  assert.equal(places[0].id, 1);
});
test("additional pages merge by stable identity without losing previous places", () => {
  assert.deepEqual(mergePlaces([{ id: 1, name: "old" }, { id: 2 }], [{ id: "1", name: "new" }, { id: 3 }]), [{ id: "1", name: "new" }, { id: 2 }, { id: 3 }]);
});
test("all regional choices fit Korea and normalize official names", () => {
  assert.equal(Object.keys(regionViews).length, 17);
  assert.equal(normalizeRegion("전남광주통합특별시"), "전남·광주");
  assert.equal(normalizeRegion("강원특별자치도"), "강원");
  assert.equal(normalizeRegion("전북특별자치도"), "전북");
  assert.equal(normalizeRegion("충청남도"), "충남");
  for (const [lat, lng] of Object.values(regionViews)) assert.ok(lat > 32 && lat < 39 && lng > 124 && lng < 132);
});
