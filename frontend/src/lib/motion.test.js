import test from "node:test";
import assert from "node:assert/strict";
import { revealWhenVisible } from "./motion.js";

function scene(reduced = false) {
  const events = new Map();
  const state = { observed: 0, disconnected: 0, animations: [], cancelled: 0 };
  const preference = { matches: reduced, addEventListener: (name, fn) => events.set(name, fn), removeEventListener: name => events.delete(name) };
  const environment = { matchMedia: () => preference, IntersectionObserver: class {
    constructor(callback) { state.intersect = callback; }
    observe() { state.observed++; }
    disconnect() { state.disconnected++; }
  } };
  const element = { animate: (frames, timing) => { state.animations.push({ frames, timing }); return { cancel() { state.cancelled++; } }; } };
  return { state, environment, element, preference, events };
}

test("cards animate once when visible, without changing layout styles", () => {
  const { state, environment, element } = scene();
  const cleanup = revealWhenVisible(element, environment);
  state.intersect([{ isIntersecting: false }]);
  assert.equal(state.animations.length, 0);
  state.intersect([{ isIntersecting: true }]);
  state.intersect([{ isIntersecting: true }]);
  assert.equal(state.animations.length, 1);
  assert.equal(state.animations[0].timing.duration, 380);
  assert.equal(element.style, undefined);
  cleanup();
  assert.equal(state.cancelled, 1);
});

test("reduced motion never hides content or starts a reveal", () => {
  const { state, environment, element, events } = scene(true);
  const cleanup = revealWhenVisible(element, environment);
  state.intersect([{ isIntersecting: true }]);
  assert.equal(state.observed, 0);
  assert.equal(state.animations.length, 0);
  cleanup();
  assert.equal(events.size, 0);
});

test("turning reduced motion on cancels an in-flight animation", () => {
  const { state, environment, element, preference, events } = scene();
  const cleanup = revealWhenVisible(element, environment);
  state.intersect([{ isIntersecting: true }]);
  preference.matches = true;
  events.get("change")();
  assert.equal(state.cancelled, 1);
  assert.ok(state.disconnected > 0);
  cleanup();
});

test("unsupported browsers leave cards visible without throwing", () => {
  assert.doesNotThrow(() => revealWhenVisible({}, {})());
  assert.doesNotThrow(() => revealWhenVisible(null, {})());
});
