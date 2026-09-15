import test from "node:test";
import assert from "node:assert/strict";
import { isCourse, placeMeta, placePhoto, verifiedRating } from "./placePresentation.js";

test("metadata has no dangling separator or guessed region", () => {
  assert.equal(placeMeta({ region: "", categoryLabel: "관광지" }), "관광지");
  assert.equal(placeMeta({ region: " 강원 ", categoryLabel: " " }), "강원");
  assert.equal(placeMeta({}), "");
  assert.equal(placeMeta({ region: "강원", contentKind: "course", categoryLabel: "산책길" }), "강원 · 여행 코스");
  assert.equal(isCourse({ contentTypeId: "25" }), true);
  assert.equal(isCourse({ name: "해변을 걷다", category: "walk" }), false);
});
test("only documented finite ratings in range are displayed", () => {
  for (const value of [undefined, null, NaN, Infinity, -1, 5.1, "4.5"]) assert.equal(verifiedRating({ ratingVerified: true, hiddenRating: value }), false);
  assert.equal(verifiedRating({ ratingVerified: false, hiddenRating: 4.5 }), false);
  assert.equal(verifiedRating({ ratingVerified: true, hiddenRating: 0 }), true);
  assert.equal(verifiedRating({ ratingVerified: true, hiddenRating: 4.5 }), true);
});
test("stock photo fallbacks are hidden while actual official and submission photos remain", () => {
  assert.equal(placePhoto({ image: "https://images.unsplash.com/stock.jpg", imageVerified: false }), "");
  assert.equal(placePhoto({}), "");
  assert.equal(placePhoto({ image: "javascript:alert(1)", imageVerified: true }), "");
  assert.equal(placePhoto({ image: "https://tong.visitkorea.or.kr/place.jpg", imageVerified: true }), "https://tong.visitkorea.or.kr/place.jpg");
  assert.equal(placePhoto({ image: "https://example.test/user.jpg", source: "submission:1" }), "https://example.test/user.jpg");
});
