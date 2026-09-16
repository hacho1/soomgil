import { pathToFileURL } from "node:url";

export function serviceOrigin(value, production = false) {
  let url;
  try { url = new URL(value); } catch { throw new Error("Use a complete service origin."); }
  const local = ["localhost", "127.0.0.1", "[::1]"].includes(url.hostname);
  if (url.username || url.password || url.search || url.hash || url.pathname !== "/"
    || (url.protocol !== "https:" && !(url.protocol === "http:" && local)) || (production && local)) {
    throw new Error("Use an HTTPS origin without credentials/path, or local HTTP for development.");
  }
  return url.origin;
}

export async function checkRelease(value, { production = false, fetcher = fetch } = {}) {
  const origin = serviceOrigin(value, production);
  const checks = [];
  const add = (name, passed, detail = "") => checks.push({ name, status: passed ? "pass" : "fail", detail });
  async function read(path) {
    const start = performance.now();
    try {
      const response = await fetcher(origin + path, { redirect: "error", signal: AbortSignal.timeout(12000), headers: { Accept: "application/json, text/html" } });
      const text = await response.text();
      let body;
      try { body = JSON.parse(text); } catch { body = null; }
      const id = response.headers.get("X-Request-ID") || "";
      return { status: response.status, body, html: text, durationMs: Math.round(performance.now() - start), requestId: /^[0-9a-f-]{36}$/.test(id) ? id : "" };
    } catch {
      return { status: 0, body: null, html: "", durationMs: Math.round(performance.now() - start), requestId: "" };
    }
  }
  const health = await read("/api/health");
  add("api-and-database", health.status === 200 && health.body?.ok === true && health.body?.service === "soomgil");
  add("request-correlation", Boolean(health.requestId));
  if (health.status === 0) {
    return { origin, production, checkedAt: new Date().toISOString(), automatedChecksPassed: false,
      launchApproved: false, checks, timings: { health: [{ durationMs: health.durationMs }] },
      remaining: ["No HTTP response from the health endpoint. Check the server, port and client network before rerunning; other checks were not attempted."] };
  }
  const auth = await read("/api/auth/config");
  add("email-configured", auth.status === 200 && auth.body?.emailVerificationAvailable === true, "Configuration only; verify delivery manually.");
  add("naver-configured", auth.status === 200 && auth.body?.naverAvailable === true, "Complete real-domain login manually.");
  add("google-configured", auth.status === 200 && typeof auth.body?.googleClientId === "string" && auth.body.googleClientId.endsWith(".apps.googleusercontent.com"), "Check authorized JavaScript origin manually.");
  add("admin-requires-login", (await read("/api/admin/operations")).status === 401);
  const home = [];
  for (let i = 0; i < 3; i++) home.push(await read("/api/places/recommendations"));
  const eligible = place => place && typeof place.name === "string" && place.name.trim()
    && typeof place.address === "string" && place.address.trim()
    && +place.mapx > 124 && +place.mapx < 132 && +place.mapy > 32 && +place.mapy < 39
    && place.contentKind !== "course" && !["15", "25", "32", "38"].includes(place.contentTypeId)
    && !["course", "stay", "shopping"].includes(place.category);
  add("recommendation-policy", home.every(result => result.status === 200 && result.body?.method === "discovery-rules-v2"
    && Array.isArray(result.body.items) && result.body.items.length > 0 && result.body.items.length <= 4
    && result.body.items.every(eligible) && new Set(result.body.items.map(item => item.id)).size === result.body.items.length));
  const timings = { home: home.map(({ durationMs, requestId }) => ({ durationMs, requestId })) };
  for (const [name, query] of [["sea", "\uBC14\uB2E4"], ["cafe", "\uCE74\uD398"]]) {
    const samples = [];
    for (let i = 0; i < 2; i++) samples.push(await read(`/api/places/search?query=${encodeURIComponent(query)}&size=6`));
    add(`search-${name}`, samples.every(result => result.status === 200 && Array.isArray(result.body?.items)
      && result.body.items.length > 0 && result.body.items.length <= 6 && result.body.total >= result.body.items.length));
    timings[name] = samples.map(({ durationMs, requestId }) => ({ durationMs, requestId }));
  }
  for (const path of ["/loginform", "/signupform", "/search"]) {
    const result = await read(path);
    add(`spa-route-${path.slice(1)}`, result.status === 200 && /id=["']root["']/.test(result.html)
      && (!production || !result.html.includes("/@vite/client")), "HTML route only; browser rendering is a separate check.");
  }
  for (const [name, samples] of Object.entries(timings)) {
    if (samples.some(sample => sample.durationMs > 1500)) checks.push({ name: `latency-${name}`, status: "warn", detail: "At least one request exceeded 1500 ms; inspect matching request IDs in backend logs." });
  }
  return { origin, production, checkedAt: new Date().toISOString(), automatedChecksPassed: checks.every(check => check.status !== "fail"),
    launchApproved: false, checks, timings,
    remaining: ["Real Google/Naver login and email delivery", "Secure session cookies and auto-login", "Mobile navigation/back/refresh", "Backup restore drill and restart recovery", "School server ports, TLS and service ownership"] };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const args = process.argv.slice(2);
    const urls = args.filter(arg => !arg.startsWith("--"));
    if (urls.length !== 1 || args.some(arg => arg.startsWith("--") && arg !== "--production")) throw new Error("Usage: node scripts/check-release.mjs ORIGIN [--production]");
    const report = await checkRelease(urls[0], { production: args.includes("--production") });
    console.log(JSON.stringify(report, null, 2));
    process.exitCode = report.automatedChecksPassed ? 0 : 1;
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
