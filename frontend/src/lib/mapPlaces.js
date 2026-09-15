import art from "../data/map-art.json" with { type: "json" };
import categories from "../data/categories.json" with { type: "json" };

export const mapTypes = Object.entries(art.assets).map(([key, asset]) => ({
  id: key === "all" ? "" : key,
  label: asset.label,
  tone: asset.tone,
  image: `/assets/map/${asset.file}`,
}));

export function getMapType(category) {
  const exact = mapTypes.find(type => type.id === category);
  if (exact) return exact;
  const alias = categories.find(type => type.id !== "tour" && type.types.includes(category));
  return mapTypes.find(type => type.id === alias?.id) || mapTypes[0];
}

export function matchesMapCategory(place, category) {
  if (!category) return true;
  return categories.find(type => type.id === category)?.types.includes(place.category) || place.category === category;
}

export function hasMapCoordinates(place) {
  return Number(place.mapx) > 124 && Number(place.mapx) < 132 && Number(place.mapy) > 32 && Number(place.mapy) < 39;
}

export function getMapStatus(place, favoriteIds, followingIds) {
  return { favorite: favoriteIds.has(String(place.id)), following: followingIds.has(String(place.id)) };
}

export function mapPlaceLabel(place, { favorite, following }) {
  return [place.name, getMapType(place.category).label, favorite && "찜한 장소", following && "팔로잉 추천"].filter(Boolean).join(", ");
}
