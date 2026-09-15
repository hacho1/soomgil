export function recommendationHint(place, day = new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Seoul" }).format(new Date())) {
  const signals = Array.isArray(place?.recommendationSignals) ? place.recommendationSignals : [];
  return signals.filter(signal => typeof signal === "string" && (signal !== "오늘 한적함 예측" || (
    place.crowdBasis === "forecast" && place.crowdDate === day && Number.isFinite(place.crowdValue) && place.crowdValue >= 0 && place.crowdValue < 40
  ))).slice(0, 2).join(" · ");
}

export function homeSlides(feed) {
  return (Array.isArray(feed?.banners) ? feed.banners : []).filter(place => place.imageVerified === true && place.image && place.name).slice(0, 3);
}

export function homeVisiblePlaces(catalog, recentViews) {
  const seen = new Set();
  return [...catalog.slice(0, 4), ...recentViews.slice(0, 6)].filter(place => {
    const id = String(place.id);
    if (seen.has(id)) return false;
    seen.add(id);
    return true;
  });
}

export function needsHomeRecommendations(path, backgroundPath, canBrowse) {
  const surface = backgroundPath || path;
  return Boolean(canBrowse) && (surface === "/" || /^\/places\/\d+$/.test(surface));
}
