import test from "node:test";
import assert from "node:assert/strict";
import { displayBadgeIds, toggleDisplayBadge } from "./displayBadge.js";

test("only the first legacy display badge is retained", () => {
  assert.deepEqual(displayBadgeIds(["first", "second", "third"]), ["first"]);
  assert.deepEqual(displayBadgeIds(undefined), []);
  assert.deepEqual(displayBadgeIds([]), []);
});

test("a different badge replaces the selection and pressing it again hides it", () => {
  assert.deepEqual(toggleDisplayBadge(["first"], "second"), ["second"]);
  assert.deepEqual(toggleDisplayBadge(["second"], "second"), []);
  assert.deepEqual(toggleDisplayBadge([], "first"), ["first"]);
});
