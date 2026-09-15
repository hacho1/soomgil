const intentKey = "soomgilAuthIntent";
const guestKey = "soomgilGuestBrowsing";

export function safeReturnTo(value) {
  if (typeof value !== "string" || !value.startsWith("/") || value.startsWith("//") || /[\\\r\n]/.test(value)) return "/";
  if (/^\/(welcome|loginform|signupform|signup|onboarding|recovery|auth)(\/|\?|$)/.test(value)) return "/";
  return value;
}

export function readAuthIntent() {
  try {
    const value = JSON.parse(sessionStorage.getItem(intentKey)) || {};
    return { returnTo: safeReturnTo(value.returnTo), favoriteId: Number.isSafeInteger(value.favoriteId) && value.favoriteId > 0 ? value.favoriteId : null };
  } catch { return { returnTo: "/", favoriteId: null }; }
}
export function saveAuthIntent(value) {
  try { sessionStorage.setItem(intentKey, JSON.stringify({ ...value, returnTo: safeReturnTo(value.returnTo) })); } catch { /* Storage is optional. */ }
}
export function clearAuthIntent() { try { sessionStorage.removeItem(intentKey); } catch { /* Storage is optional. */ } }
export function isGuestBrowsing() { try { return sessionStorage.getItem(guestKey) === "true"; } catch { return false; } }
export function rememberGuestBrowsing() { try { sessionStorage.setItem(guestKey, "true"); } catch { /* Storage is optional. */ } }
export function normalizeInterestIds(values, choices) {
  return [...new Set((values || []).map(value => choices.find(choice => choice.id === value || choice.label === value)?.id).filter(Boolean))];
}
