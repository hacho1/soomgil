import { test } from "node:test";
import assert from "node:assert/strict";
import { checkRelease, serviceOrigin } from "./check-release.mjs";

const fixture = (url) => {
  const path = new URL(url).pathname;
  const headers = { "X-Request-ID": "11111111-1111-4111-a111-111111111111" };
  const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers });
  if (path === "/api/health") return json({ ok: true, service: "soomgil" });
  if (path === "/api/auth/config") return json({ emailVerificationAvailable: true, naverAvailable: true, googleClientId: "public.apps.googleusercontent.com" });
  if (path === "/api/admin/operations") return json({}, 401);
  const place = { id: 1, name: "Test beach", address: "Test address", mapx: "128.9", mapy: "37.8", contentKind: "place", contentTypeId: "12" };
  if (path === "/api/places/recommendations") return json({ method: "discovery-rules-v2", items: [place] });
  if (path === "/api/places/search") return json({ total: 1, items: [place] });
  return new Response('<html><div id="root"></div></html>');
};
test("origins reject credentials, public HTTP, paths and local production targets", () => {
  for (const value of ["https://name:secret@example.com", "http://example.com", "https://example.com/x", "https://example.com?token=x"]) assert.throws(() => serviceOrigin(value));
  assert.throws(() => serviceOrigin("http://127.0.0.1:5173", true));
  assert.equal(serviceOrigin("http://127.0.0.1:5173"), "http://127.0.0.1:5173");
});
test("passing read-only checks do not claim launch approval or expose responses", async () => {
  const methods = [];
  const report = await checkRelease("https://travel.example.com", { production: true, fetcher: (url, options) => { methods.push(options.method || "GET"); return fixture(url); } });
  assert.equal(report.automatedChecksPassed, true);
  assert.equal(report.launchApproved, false);
  assert.equal(report.timings.home.length, 3);
  assert.ok(methods.every(method => method === "GET"));
  assert.ok(!JSON.stringify(report).includes("public.apps.googleusercontent.com"));
});
test("wrong API, redirects/failures and a public development frontend fail", async () => {
  const report = await checkRelease("https://travel.example.com", { production: true, fetcher: () => new Response('<div id="root"></div><script src="/@vite/client"></script>') });
  assert.equal(report.automatedChecksPassed, false);
  assert.ok(report.checks.some(check => check.name === "spa-route-loginform" && check.status === "fail"));
  const failed = await checkRelease("http://localhost:5173", { fetcher: () => { throw new Error("secret transport details"); } });
  assert.equal(failed.automatedChecksPassed, false);
  assert.ok(!JSON.stringify(failed).includes("secret transport details"));
});

test("an unreachable health endpoint stops before repeated connection timeouts", async () => {
  let calls = 0;
  const report = await checkRelease("http://127.0.0.1:18787", { fetcher: () => { calls++; throw new Error("private transport details"); } });
  assert.equal(calls, 1);
  assert.equal(report.automatedChecksPassed, false);
  assert.equal(report.launchApproved, false);
  assert.equal(report.checks.length, 2);
  assert.ok(report.remaining[0].includes("other checks were not attempted"));
  assert.ok(!JSON.stringify(report).includes("private transport details"));
});
