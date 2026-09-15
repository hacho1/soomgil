import { useEffect, useState } from "react";
import { api } from "./api";
import { mergePlaces } from "./mapDiscovery";

const empty = [];
export function usePlaceSearch(query, crowd, page, version) {
  const key = `${query}:${crowd}:${version}`;
  const [batch, setBatch] = useState({ key: "", page: 1 });
  const requestPage = crowd ? batch.key === key ? batch.page : 1 : page;
  const requestKey = `${key}:${requestPage}`;
  const [state, setState] = useState({ key: "", requestKey: "", items: empty, total: 0, loading: true });
  const items = state.key === key && (crowd || state.requestKey === requestKey) ? state.items : empty;
  useEffect(() => {
    const controller = new AbortController();
    setState(previous => ({ ...previous, loading: true, error: "" }));
    api(`/api/places/search?${query}&page=${requestPage}&size=${crowd ? 24 : 6}`, { signal: controller.signal })
      .then(result => {
        if (controller.signal.aborted) return;
        setState(previous => ({ key, requestKey, items: crowd && previous.key === key && requestPage > 1 ? mergePlaces(previous.items, result.items) : result.items,
          total: result.total, page: result.page, hasMore: result.hasMore, loading: false, error: "" }));
      }).catch(error => { if (!controller.signal.aborted) setState(previous => ({ ...previous, key, requestKey, items: previous.key === key ? previous.items : empty, loading: false, error: error.message })); });
    return () => controller.abort();
  }, [key, requestKey, query, crowd, requestPage]);
  return { items, total: state.key === key ? state.total : 0, page: state.requestKey === requestKey ? state.page || 1 : page,
    loading: state.requestKey !== requestKey || state.loading, error: state.key === key ? state.error : "",
    hasMore: state.key === key && state.hasMore,
    loadMore: () => setBatch({ key, page: requestPage + 1 }) };
}
