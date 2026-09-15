import test from "node:test";
import assert from "node:assert/strict";
import { visitInfoMessage, visitInfoSummary, visitInfoCheckedAt } from "./visitInfo.js";

test("unregistered information is distinct from an upstream failure", () => {
  assert.match(visitInfoMessage({ sources: [{ id: "pet", state: "empty" }] }, "pet"), /등록된 정보가 없/);
  assert.match(visitInfoMessage({ sources: [{ id: "pet", state: "unavailable" }] }, "pet"), /불러오지 못/);
  assert.match(visitInfoMessage({ state: "unmatched" }), /동일한 장소/);
  assert.match(visitInfoMessage({ sources: [{ id: "common", state: "stale" }] }, "common"), /이전에 확인한/);
});

test("map summaries preserve pet restrictions and never claim universal accessibility", () => {
  assert.equal(visitInfoSummary({ pet: [{ id: "acmpyTypeCd", value: "야외만 동반 가능" }] }, "pet"), "야외만 동반 가능");
  assert.equal(visitInfoSummary({ accessibility: [{ id: "parking", value: "장애인 주차구역 없음" }] }, "accessibility"), "접근성 시설 1개 항목 안내");
  assert.equal(visitInfoSummary({ state: "partial", sources: [{ id: "pet", state: "unavailable" }] }, "pet"), "정보 연결 일시 중단");
  assert.equal(visitInfoSummary({ state: "ready", pet: [] }, "pet"), "공식 정보 미등록");
});

test("the displayed verification date uses the oldest contributing source", () => {
  assert.equal(visitInfoCheckedAt({ sources: [] }), "");
  const earlier = Date.UTC(2026, 8, 10), later = Date.UTC(2026, 8, 13);
  assert.equal(visitInfoCheckedAt({ sources: [{ checkedAt: later }, { checkedAt: earlier }, { checkedAt: 0 }] }), visitInfoCheckedAt({ sources: [{ checkedAt: earlier }] }));
});
