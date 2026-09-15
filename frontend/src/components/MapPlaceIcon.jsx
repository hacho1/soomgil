import { Heart, Users } from "lucide-react";
import { getMapType } from "../lib/mapPlaces";

export default function MapPlaceIcon({ category, favorite = false, following = false, size = "list" }) {
  const type = getMapType(category);
  return <span className={`map-place-icon map-place-icon-${size} map-tone-${type.tone}`} aria-hidden="true">
    <img src={type.image} width="256" height="256" alt="" draggable="false" decoding="async" />
    {favorite && <span className="map-status-mark map-status-favorite"><Heart size={11} fill="currentColor" strokeWidth={2.5} /></span>}
    {following && <span className="map-status-mark map-status-following"><Users size={11} strokeWidth={2.5} /></span>}
  </span>;
}
