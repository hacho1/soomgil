import { hasMapCoordinates, matchesMapCategory } from "./mapPlaces.js";

export const nearbyRadii = [1, 3, 5, 10];
export const mapPageSize = 6;

// Distance is supplied by Leaflet so filtering and the displayed radius use the same metric.
export function scopedMapPlaces({ places, scope, center, radiusKm, category = "", sort = "near", distanceBetween }) {
  if (scope === "nearby" && (!center || !nearbyRadii.includes(radiusKm))) return [];
  const origin = center ? [center.lat, center.lng] : [36, 127.8];
  return places.filter(place => hasMapCoordinates(place) && matchesMapCategory(place, category))
    .map(place => ({ ...place, distanceMeters: distanceBetween(origin, [Number(place.mapy), Number(place.mapx)]) }))
    .filter(place => Number.isFinite(place.distanceMeters) && place.distanceMeters >= 0
      && (scope !== "nearby" || place.distanceMeters <= radiusKm * 1000))
    .sort((a, b) => (sort === "slow" ? slowTravelPriority(a) - slowTravelPriority(b) : 0)
      || a.distanceMeters - b.distanceMeters || String(a.name).localeCompare(String(b.name), "ko"));
}

export function nearbyCriteria(center, radiusKm, category = "") {
  if (!center || !nearbyRadii.includes(radiusKm) || !(center.lat > 32 && center.lat < 39 && center.lng > 124 && center.lng < 132)) return null;
  // Cross province boundaries; add 100m for rounded coordinates, then apply the exact radius locally.
  return { mapX: Number(center.lng).toFixed(3), mapY: Number(center.lat).toFixed(3), radius: radiusKm * 1000 + 100, category };
}

export function distanceLabel(meters) {
  return meters < 1000 ? `${Math.round(meters / 10) * 10}m` : `${(meters / 1000).toFixed(1)}km`;
}

export const regionViews = {
  "": [36.0, 127.8, 6], 서울: [37.566, 126.978, 11], 인천: [37.46, 126.65, 10], 대전: [36.35, 127.38, 11],
  대구: [35.87, 128.6, 11], "전남·광주": [35.0, 126.85, 9], 부산: [35.17, 129.07, 11], 울산: [35.54, 129.31, 11],
  세종: [36.51, 127.26, 11], 경기: [37.45, 127.3, 9], 강원: [37.75, 128.2, 9], 충북: [36.8, 127.8, 9],
  충남: [36.55, 126.8, 9], 경북: [36.25, 128.85, 9], 경남: [35.35, 128.3, 9], 전북: [35.8, 127.1, 9],
  제주: [33.38, 126.55, 10],
};

export function normalizeRegion(value = "") {
  if (["광주", "광주광역시", "전남", "전라남도", "전남광주통합", "전남광주통합특별시", "전남·광주"].includes(value.trim())) return "전남·광주";
  return value.replace("충청북", "충북").replace("충청남", "충남").replace("경상북", "경북").replace("경상남", "경남")
    .replace("전라북", "전북").replace("전라남", "전남").replace(/특별자치도|특별자치시|광역시|특별시|도$/g, "").trim();
}

export function mergePlaces(...lists) {
  return [...new Map(lists.flat().map(place => [String(place.id), place])).values()];
}

export function withinView(place, bounds) {
  return hasMapCoordinates(place) && (!bounds || (Number(place.mapx) >= bounds.west && Number(place.mapx) <= bounds.east
    && Number(place.mapy) >= bounds.south && Number(place.mapy) <= bounds.north));
}

export function slowTravelPriority(place) {
  if (place.category === "walk" || /습지|생태|숲길|둘레길|수목원|자연휴양림/.test(place.name)) return 0;
  if (place.category === "nature" || /해변|계곡|등대|오름|산책|정원/.test(place.name)) return 1;
  if (["camping", "culture"].includes(place.category)) return 2;
  return 3;
}

export function viewPlaces(places, bounds, sort = "slow") {
  const center = bounds ? [(bounds.west + bounds.east) / 2, (bounds.south + bounds.north) / 2] : [127.8, 36.3];
  const distance = place => ((Number(place.mapx) - center[0]) * Math.cos(center[1] * Math.PI / 180)) ** 2 + (Number(place.mapy) - center[1]) ** 2;
  return places.filter(place => withinView(place, bounds)).sort((a, b) => (sort === "slow" ? slowTravelPriority(a) - slowTravelPriority(b) : 0)
    || distance(a) - distance(b) || String(a.name).localeCompare(String(b.name), "ko"));
}
