export function isCourse(place) { return place?.contentKind === "course" || place?.contentTypeId === "25"; }

export function placeMeta(place) {
  return [place?.region?.trim(), isCourse(place) ? "여행 코스" : place?.categoryLabel?.trim()].filter(Boolean).join(" · ");
}

export function verifiedRating(place) {
  return place?.ratingVerified === true && typeof place.hiddenRating === "number"
    && Number.isFinite(place.hiddenRating) && place.hiddenRating >= 0 && place.hiddenRating <= 5;
}

export function placePhoto(place) {
  const image = typeof place?.image === "string" ? place.image.trim() : "";
  if (!image) return "";
  const submitted = place.imageSource === "submission" || /^submission(?::|$)/.test(place.source || "");
  return (place.imageVerified === true || submitted) && /^(https:\/\/|data:image\/(jpeg|png|webp);base64,)/.test(image) ? image : "";
}
