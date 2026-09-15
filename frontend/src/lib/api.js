const BASE = import.meta.env?.VITE_API_BASE_URL || "";
let csrfPromise;
export function resetCsrf() { csrfPromise = undefined; }

export async function api(path, options = {}) {
  const method = options.method || "GET";
  let csrf;
  if (!["GET", "HEAD", "OPTIONS"].includes(method)) {
    csrfPromise ||= fetch(`${BASE}/api/auth/csrf`, { credentials: "include" })
      .then(async response => {
        if (!response.ok) throw new Error("인증 서버에 연결할 수 없습니다.");
        return (await response.json()).token;
      }).catch(error => { resetCsrf(); throw error; });
    csrf = await csrfPromise;
  }
  let response;
  try {
    response = await fetch(`${BASE}${path}`, {
      ...options, credentials: "include",
      headers: { "Content-Type": "application/json", ...(csrf ? { "X-CSRF-TOKEN": csrf } : {}), ...options.headers }
    });
  } catch (error) {
    if (error.name === "AbortError") throw error;
    throw new Error("서버에 연결할 수 없습니다. 연결 상태를 확인하고 다시 시도해주세요.");
  }
  const result = await response.json().catch(() => ({}));
  if (!response.ok) {
    if (response.status === 401 || response.status === 403) resetCsrf();
    const retry = Number(result.retryAfterSeconds || response.headers.get("Retry-After"));
    const retryAfterSeconds = Number.isFinite(retry) ? Math.max(0, Math.ceil(retry)) : 0;
    const fallback = response.status === 413 ? "전송 용량이 너무 큽니다. 사진이나 그림 크기를 줄여주세요."
      : response.status === 429 ? `요청이 많아요. ${retryAfterSeconds ? `약 ${retryAfterSeconds >= 60 ? `${Math.ceil(retryAfterSeconds / 60)}분` : `${retryAfterSeconds}초`} 후` : "잠시 후"} 다시 시도해주세요.`
      : "요청을 처리하지 못했습니다. 잠시 후 다시 시도해주세요.";
    const error = new Error(result.message || fallback);
    error.status = response.status;
    const requestId = response.headers.get("X-Request-ID") || "";
    error.requestId = /^[0-9a-f-]{36}$/.test(requestId) ? requestId : "";
    error.retryAfterSeconds = retryAfterSeconds;
    throw error;
  }
  return result;
}

export function readLocal(key, fallback) {
  try { return JSON.parse(localStorage.getItem(key)) ?? fallback; } catch { return fallback; }
}
export function writeLocal(key, value) {
  try { localStorage.setItem(key, JSON.stringify(value)); } catch { /* Private browsing may disable storage. */ }
}
export const dateLabel = value => String(value || "").slice(0, 10).replaceAll("-", ". ");
export function today() {
  const date = new Date();
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
}
