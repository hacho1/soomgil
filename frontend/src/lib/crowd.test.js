import test from "node:test";
import assert from "node:assert/strict";
import { hasCrowdData, matchesCrowd, crowdNote, crowdText, fetchCrowdUpdates } from "./crowd.js";

const archived = { crowd: "한적함", crowdAvailable: true, crowdVerified: false, crowdBasis: "archived", crowdValue: 17.93, crowdBackupAt: "2026-09-09T19:50:04" };

test("archived crowd is available without claiming live verification", () => {
  assert.equal(hasCrowdData(archived), true);
  assert.equal(matchesCrowd(archived, "한적함"), true);
  assert.equal(matchesCrowd(archived, "unknown"), false);
  assert.equal(matchesCrowd(archived, "붐빔"), false);
  assert.match(crowdNote(archived), /보관 자료 기준/);
  assert.match(crowdNote(archived), /수집일 미확인/);
  assert.doesNotMatch(crowdNote(archived), /이전 자료|백업 작성일/);
});

test("missing, sentinel and guessed values never count as crowd observations", () => {
  for (const value of [null, undefined, "17.93", NaN, Infinity, -1, 101, 999]) {
    const place = { ...archived, crowdValue: value };
    assert.equal(hasCrowdData(place), false);
    assert.equal(matchesCrowd(place, "unknown"), true);
    assert.equal(crowdNote(place), "");
  }
  assert.equal(hasCrowdData({ crowd: "한적함", crowdVerified: false }), false);
  assert.equal(hasCrowdData({ ...archived, crowdBasis: "unknown" }), false);
  assert.equal(hasCrowdData({ ...archived, crowd: "미확인" }), false);
  assert.equal(matchesCrowd({}, ""), true);
});

test("verified data remains supported and absent backup dates are not invented", () => {
  assert.equal(hasCrowdData({ crowd: "보통", crowdVerified: true }), true);
  assert.equal(hasCrowdData({ crowd: "invalid", crowdVerified: true }), false);
  assert.doesNotMatch(crowdNote({ ...archived, crowdBackupAt: null }), /백업 작성일/);
});

test("forecast supports the common label and discloses its date in detail only", () => {
  const forecast = { ...archived, crowdBasis: "forecast", crowdDate: "2026-09-13", crowd: "보통", crowdValue: 51.22 };
  assert.equal(hasCrowdData(forecast), true);
  assert.equal(matchesCrowd(forecast, "보통"), true);
  assert.match(crowdNote(forecast), /2026-09-13 관광공사 집중률 예측/);
});

test("unrequested, delayed, unmatched and absent forecasts have distinct text", () => {
  assert.equal(crowdText({}), "혼잡도 확인 전");
  assert.equal(crowdText({ crowdStatus: "loading" }), "혼잡도 확인 중");
  assert.equal(crowdText({ crowdStatus: "unavailable" }), "혼잡도 조회 지연");
  assert.equal(crowdText({ crowdStatus: "unmatched" }), "혼잡도 자료 없음");
  assert.equal(crowdText({ crowdStatus: "no-data" }), "혼잡도 미제공");
  assert.equal(crowdText({ ...archived, crowdStatus: "unavailable" }), "한적함");
});

test("course crowd is not treated as the live crowd of one physical stop", async () => {
  const course = { ...archived, contentKind: "course", id: 1 };
  assert.equal(hasCrowdData(course), false);
  const calls = [];
  await fetchCrowdUpdates([course], { signal: new AbortController().signal, request: async id => { calls.push(id); return course; }, onUpdate: () => {} });
  assert.deepEqual(calls, []);
  assert.match(crowdNote({ crowdStatus: "unmatched" }), /자료가 없어요/);
  assert.match(crowdNote({ crowdStatus: "unavailable" }), /불러오지 못했어요/);
});

test("visible places hydrate sequentially without opening details and skip resolved results", async () => {
  const calls = [], updates = [];
  let active = 0, maxActive = 0;
  const forecast = { ...archived, id: 3, crowdBasis: "forecast", crowdStatus: "ready" };
  await fetchCrowdUpdates([{ id: 1 }, { id: 2 }, forecast, { id: 4, crowdStatus: "no-data" }], {
    signal: new AbortController().signal,
    request: async id => {
      calls.push(id); maxActive = Math.max(maxActive, ++active);
      await Promise.resolve(); active--;
      return { ...forecast, id };
    },
    onUpdate: result => updates.push(result),
  });
  assert.deepEqual(calls, [1, 2]);
  assert.equal(maxActive, 1);
  assert.deepEqual(updates.map(item => item.id), [1, 2]);
});

test("old observations refresh while request failures never become no-data", async () => {
  const updates = [];
  await fetchCrowdUpdates([{ ...archived, id: 1 }, { id: 2 }], {
    signal: new AbortController().signal,
    request: async id => { if (id === 1) throw new Error("Offline"); return { id: 999, crowd: "한적함" }; },
    onUpdate: result => updates.push(result),
  });
  assert.deepEqual(updates.map(item => item.crowdStatus), ["unavailable", "unavailable"]);
  assert.equal(updates[0].crowdValue, 17.93);
  assert.equal(updates[1].id, 2);
  assert.equal(hasCrowdData(updates[1]), false);
});

test("switching searches cancels old results and remaining requests", async () => {
  const controller = new AbortController(), calls = [], updates = [];
  await fetchCrowdUpdates([{ id: 1 }, { id: 2 }], {
    signal: controller.signal,
    request: async id => { calls.push(id); controller.abort(); return { id, crowdStatus: "no-data" }; },
    onUpdate: result => updates.push(result),
  });
  assert.deepEqual(calls, [1]);
  assert.deepEqual(updates, []);
});

test("retry explicitly rechecks failed source responses and preserves card-specific fields", async () => {
  const updates = [], calls = [];
  await fetchCrowdUpdates([{ id: 1, crowdStatus: "unavailable", distanceMeters: 520, distanceKm: 0.52 }, { id: 2, crowdStatus: "no-data" }], {
    signal: new AbortController().signal, retryFailed: true,
    request: async id => { calls.push(id); return { ...archived, id, crowdBasis: "forecast", crowdStatus: "ready" }; },
    onUpdate: result => updates.push(result),
  });
  assert.deepEqual(calls, [1]);
  assert.equal(updates[0].distanceMeters, 520);
  assert.equal(updates[0].distanceKm, 0.52);
  assert.equal(crowdText(updates[0]), "한적함");
});
