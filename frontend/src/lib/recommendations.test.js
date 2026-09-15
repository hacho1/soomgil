import test from "node:test";
import assert from "node:assert/strict";
import { homeSlides, homeVisiblePlaces, needsHomeRecommendations, recommendationHint } from "./recommendations.js";

test("quiet recommendation evidence disappears when hydration returns busy, missing or old data", () => {
  const place = { recommendationSignals: ["관심 지역", "오늘 한적함 예측"], crowdBasis: "forecast", crowdDate: "2026-09-15", crowdValue: 25 };
  assert.equal(recommendationHint(place, "2026-09-15"), "관심 지역 · 오늘 한적함 예측");
  for (const change of [{ crowdValue: 70 }, { crowdValue: -1 }, { crowdValue: NaN }, { crowdDate: "2026-09-14" }, { crowdBasis: "archived" }, { crowdValue: null }]) {
    assert.equal(recommendationHint({ ...place, ...change }, "2026-09-15"), "관심 지역");
  }
});
test("home banners use actual named verified photos and never an arbitrary stock substitute", () => {
  const official = { id: 1, name: "가을 숲길", image: "https://tong.visitkorea.or.kr/photo.jpg", imageVerified: true };
  assert.deepEqual(homeSlides({ banners: [official, { ...official, id: 2, imageVerified: false }, { ...official, id: 3, image: "" }] }), [official]);
  assert.deepEqual(homeSlides(null), []);
  assert.equal(homeSlides({ banners: [official, official, official, official] }).length, 3);
});
test("missing recommendation metadata does not invent a recommendation or a rating", () => {
  assert.equal(recommendationHint({ crowdValue: 0, hiddenRating: 5 }), "");
  assert.equal(recommendationHint({ recommendationSignals: [null, "산책 취향", "관심 지역", "가을 여행"] }), "산책 취향 · 관심 지역");
});

test("recent views never overwrite home recommendation metadata and hydration stays bounded", () => {
  const catalog = Array.from({ length: 8 }, (_, id) => ({ id, recommendationSignals: ["산책 취향"] }));
  const recent = [{ id: "0" }, ...Array.from({ length: 8 }, (_, i) => ({ id: i + 10 }))];
  const visible = homeVisiblePlaces(catalog, recent);
  assert.equal(visible[0], catalog[0]);
  assert.equal(recommendationHint(visible[0]), "산책 취향");
  assert.deepEqual(visible.map(place => place.id), [0, 1, 2, 3, 10, 11, 12, 13, 14]);
  assert.equal(homeVisiblePlaces([], recent).length, 6);
});

test("home recommendations do not contend with direct search, map, or authentication requests", () => {
  for (const path of ["/search", "/map", "/loginform", "/signup", "/mypage", "/diary", "/onboarding"]) {
    assert.equal(needsHomeRecommendations(path, undefined, true), false);
  }
  assert.equal(needsHomeRecommendations("/", undefined, false), false);
  assert.equal(needsHomeRecommendations("/", undefined, true), true);
  assert.equal(needsHomeRecommendations("/places/32", "/map", true), false);
  assert.equal(needsHomeRecommendations("/places/32", "/search", true), false);
  assert.equal(needsHomeRecommendations("/places/32", "/", true), true);
  assert.equal(needsHomeRecommendations("/places/32", undefined, true), true);
});
