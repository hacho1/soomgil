export function displayBadgeIds(ids) {
  return Array.isArray(ids) ? ids.filter(id => typeof id === "string" && id.length > 0).slice(0, 1) : [];
}

export function toggleDisplayBadge(ids, id) {
  return displayBadgeIds(ids).includes(id) ? [] : [id];
}
