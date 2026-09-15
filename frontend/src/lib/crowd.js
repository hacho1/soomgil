import { isCourse } from "./placePresentation.js";

const labels = new Set(["한적함", "보통", "붐빔"]);

export function hasCrowdData(place) {
  if (isCourse(place)) return false;
  if (!labels.has(place?.crowd)) return false;
  if (place.crowdVerified === true) return true;
  return place.crowdAvailable === true && ["archived", "forecast"].includes(place.crowdBasis)
    && typeof place.crowdValue === "number" && Number.isFinite(place.crowdValue)
    && place.crowdValue >= 0 && place.crowdValue <= 100;
}

export function matchesCrowd(place, filter) {
  return !filter || (filter === "unknown" ? !hasCrowdData(place) : hasCrowdData(place) && place.crowd === filter);
}

export function crowdText(place) {
  if (hasCrowdData(place)) return place.crowd;
  switch (place?.crowdStatus) {
    case "loading": case "busy": return "혼잡도 확인 중";
    case "no-data": case "unsupported": return "혼잡도 미제공";
    case "unmatched": return "혼잡도 자료 없음";
    case "limited": case "unavailable": return "혼잡도 조회 지연";
    default: return "혼잡도 확인 전";
  }
}

export function crowdResolved(place) {
  return isCourse(place) || (hasCrowdData(place) && place.crowdBasis !== "archived")
    || ["no-data", "unsupported", "unmatched", "unavailable", "limited"].includes(place?.crowdStatus);
}

export function crowdRetryable(place) {
  return ["unavailable", "limited", "busy", "unmatched"].includes(place?.crowdStatus);
}

// Sequential requests keep visible-card hydration within the shared upstream limit.
export async function fetchCrowdUpdates(places, { request, signal, onUpdate, retryFailed = false }) {
  for (const place of places) {
    if (signal.aborted) return;
    if (crowdResolved(place) && !(retryFailed && crowdRetryable(place))) continue;
    try {
      let result = await request(place.id, signal);
      for (let attempt = 0; result.crowdStatus === "busy" && attempt < 3; attempt++) {
        await new Promise(resolve => setTimeout(resolve, 500 * (attempt + 1)));
        if (signal.aborted) return;
        result = await request(place.id, signal);
      }
      if (signal.aborted) return;
      if (String(result.id) !== String(place.id)) throw new Error("Mismatched place");
      onUpdate({ ...place, ...result, crowdStatus: result.crowdStatus === "busy" ? "unavailable" : result.crowdStatus });
    } catch (error) {
      if (signal.aborted || error.name === "AbortError") return;
      onUpdate({ ...place, crowdStatus: "unavailable" });
    }
  }
}

export function crowdNote(place) {
  if (!hasCrowdData(place)) {
    if (["no-data", "unsupported", "unmatched"].includes(place?.crowdStatus)) return "이 장소에 연결된 혼잡도 자료가 없어요.";
    if (["limited", "unavailable"].includes(place?.crowdStatus)) return "혼잡도 정보를 불러오지 못했어요. 잠시 후 다시 확인해주세요.";
    return "";
  }
  if (place.crowdBasis === "forecast") return `${place.crowdDate || ""} 관광공사 집중률 예측 기준 · 실제 혼잡도와 다를 수 있어요.`;
  if (place.crowdBasis === "archived") return "보관 자료 기준 · 수집일 미확인 · 현재 상황과 다를 수 있어요.";
  return "";
}
