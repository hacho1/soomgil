import test from "node:test";
import assert from "node:assert/strict";
import { safeReturnTo, normalizeInterestIds, readAuthIntent, isGuestBrowsing } from "./authFlow.js";
import choices from "../data/travel-preferences.json" with { type: "json" };

test("authentication returns only to internal non-auth pages", () => {
  for (const invalid of [null, "https://example.com", "//example.com", "/\\example.com", "/loginform", "/signup", "/signupform", "/onboarding?edit=1", "/welcome", "/auth/naver/complete"]) assert.equal(safeReturnTo(invalid), "/");
  assert.equal(safeReturnTo("/places/12"), "/places/12");
  assert.equal(safeReturnTo("/search?q=바다"), "/search?q=바다");
});
test("legacy interest labels and new codes resolve to the same choices", () => {
  assert.deepEqual(normalizeInterestIds(["바다", "sea", "카페", "가족", "unknown"], choices), ["sea", "cafe", "family"]);
  assert.equal(choices.filter(choice => choice.onboarding).length, 9);
});
test("disabled browser storage does not block login or guest entry", () => {
  assert.deepEqual(readAuthIntent(), { returnTo: "/", favoriteId: null });
  assert.equal(isGuestBrowsing(), false);
});
