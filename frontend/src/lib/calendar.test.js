import test from "node:test";
import assert from "node:assert/strict";
import { monthCells } from "./calendar.js";

test("February respects leap years, including century rules", () => {
  assert.equal(monthCells(2024, 1).filter(Boolean).length, 29);
  assert.equal(monthCells(2026, 1).filter(Boolean).length, 28);
  assert.equal(monthCells(2000, 1).filter(Boolean).length, 29);
  assert.equal(monthCells(1900, 1).filter(Boolean).length, 28);
});

test("Calendar grid preserves weekday offset and local date keys", () => {
  const cells = monthCells(2026, 8);
  assert.equal(cells.length % 7, 0);
  assert.deepEqual(cells.slice(0, 2), [null, null]);
  assert.deepEqual(cells[2], { day: 1, date: "2026-09-01" });
  assert.equal(cells.filter(Boolean).at(-1).date, "2026-09-30");
});

test("A Sunday-starting month has no unnecessary leading week", () => {
  assert.deepEqual(monthCells(2026, 2)[0], { day: 1, date: "2026-03-01" });
});
