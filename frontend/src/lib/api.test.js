import test from "node:test";
import assert from "node:assert/strict";
import { api, resetCsrf } from "./api.js";

test("API preserves rate-limit status and retry time without losing CSRF protection", async t => {
  resetCsrf(); const calls = [];
  t.mock.method(globalThis, "fetch", async (url, options) => {
    calls.push({ url, options });
    return url.endsWith("/csrf") ? Response.json({ token: "test-csrf" }) : Response.json({ message: "Try later", retryAfterSeconds: 60 }, { status: 429, headers: { "Retry-After": "60" } });
  });
  await assert.rejects(api("/api/auth/login", { method: "POST", body: "{}" }), error => error.status === 429 && error.retryAfterSeconds === 60);
  assert.equal(calls[1].options.credentials, "include");
  assert.equal(calls[1].options.headers["X-CSRF-TOKEN"], "test-csrf");
});

test("API also reads Retry-After header and handles missing values", async t => {
  t.mock.method(globalThis, "fetch", async () => Response.json({}, { status: 429, headers: { "Retry-After": "25" } }));
  await assert.rejects(api("/api/auth/config"), error => error.retryAfterSeconds === 25);
  globalThis.fetch = async () => Response.json({}, { status: 401 });
  await assert.rejects(api("/api/auth/me"), error => error.status === 401 && error.retryAfterSeconds === 0);
});

test("403 invalidates cached CSRF so the next request gets a fresh token", async t => {
  resetCsrf(); let reads = 0;
  t.mock.method(globalThis, "fetch", async url => url.endsWith("/csrf") ? Response.json({ token: `token-${++reads}` }) : Response.json({}, { status: 403 }));
  await assert.rejects(api("/api/auth/signup", { method: "POST" }));
  await assert.rejects(api("/api/auth/signup", { method: "POST" }));
  assert.equal(reads, 2);
});

test("expired sessions clear CSRF before signing in again", async t => {
  resetCsrf(); let reads = 0, expired = false;
  t.mock.method(globalThis, "fetch", async url => {
    if (url.endsWith("/csrf")) return Response.json({ token: `token-${++reads}` });
    if (expired) return Response.json({}, { status: 401 });
    return Response.json({ ok: true });
  });
  await api("/api/auth/login", { method: "POST" });
  expired = true; await assert.rejects(api("/api/auth/me"));
  expired = false; await api("/api/auth/login", { method: "POST" });
  assert.equal(reads, 2);
});

test("API failures retain only well-formed request IDs for support diagnostics", async t => {
  const id = "11111111-1111-4111-a111-111111111111";
  t.mock.method(globalThis, "fetch", async () => Response.json({}, { status: 503, headers: { "X-Request-ID": id } }));
  await assert.rejects(api("/api/places/search"), error => error.status === 503 && error.requestId === id);
  globalThis.fetch = async () => Response.json({}, { status: 503, headers: { "X-Request-ID": "untrusted extra information" } });
  await assert.rejects(api("/api/places/search"), error => error.requestId === "");
});

test("proxy limits have useful messages and never silently retry a diary write", async t => {
  resetCsrf(); let writes = 0;
  t.mock.method(globalThis, "fetch", async url => {
    if (url.endsWith("/csrf")) return Response.json({ token: "test-csrf" });
    writes++;
    return new Response("Request Entity Too Large", { status: 413 });
  });
  const draft = { title: "Unsaved trip", body: "Keep my text" };
  await assert.rejects(api("/api/users/owner/diaries", { method: "POST", body: JSON.stringify(draft) }), error => error.status === 413 && error.message.includes("용량"));
  assert.deepEqual(draft, { title: "Unsaved trip", body: "Keep my text" });
  assert.equal(writes, 1);
  globalThis.fetch = async () => new Response("Too Many Requests", { status: 429, headers: { "Retry-After": "61" } });
  await assert.rejects(api("/api/places/search"), error => error.message.includes("2분") && error.retryAfterSeconds === 61);
  globalThis.fetch = async () => Response.json({ retryAfterSeconds: "Infinity" }, { status: 429 });
  await assert.rejects(api("/api/places/search"), error => error.retryAfterSeconds === 0);
});
