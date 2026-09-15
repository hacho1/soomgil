import { useEffect, useRef, useState } from "react";
import { api } from "./api";
import { crowdResolved, crowdRetryable, fetchCrowdUpdates, hasCrowdData } from "./crowd";

function retainedUpdates(state, places) {
  if (state.source === places) return state.updates;
  const previous = new Map((state.source || []).map(place => [String(place.id), place]));
  return Object.fromEntries(places.filter(place => previous.get(String(place.id)) === place && state.updates[place.id])
    .map(place => [place.id, state.updates[place.id]]));
}

export function useCrowdInfo(places, requestedIds) {
  const [state, setState] = useState({ source: null, updates: {} });
  const [version, setVersion] = useState(0);
  const current = useRef(state);
  current.current = state;
  const requestKey = requestedIds.map(String).join(",");
  const ids = new Set(requestedIds.map(String));
  const updates = retainedUpdates(state, places);

  useEffect(() => {
    const controller = new AbortController();
    const wanted = new Set(requestKey.split(","));
    const saved = retainedUpdates(current.current, places);
    const targets = places.filter(place => wanted.has(String(place.id)) && !saved[place.id]);
    const timer = setTimeout(() => fetchCrowdUpdates(targets, {
      signal: controller.signal,
      retryFailed: version > 0,
      request: (id, signal) => api(`/api/places/${id}/crowd-info`, { signal }),
      onUpdate: result => setState(previous => ({ source: places, updates: { ...retainedUpdates(previous, places), [result.id]: result } })),
    }), 0);
    return () => { clearTimeout(timer); controller.abort(); };
  }, [places, requestKey, version]);

  const enriched = places.map(place => updates[place.id] || (ids.has(String(place.id)) && (!crowdResolved(place) || (version > 0 && crowdRetryable(place)))
    ? { ...place, crowdStatus: "loading" } : place));
  const loading = enriched.some(place => ids.has(String(place.id)) && place.crowdStatus === "loading");
  const failed = enriched.some(place => ids.has(String(place.id)) && crowdRetryable(place));
  const unchecked = enriched.filter(place => !hasCrowdData(place) && !["no-data", "unsupported"].includes(place.crowdStatus)).length;
  function retry() { setState({ source: places, updates: {} }); setVersion(value => value + 1); }
  return { places: enriched, loading, failed, unchecked, retry };
}
