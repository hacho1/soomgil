import { createContext, useContext, useEffect, useState } from "react";
import { api } from "./api";

export const AppContext = createContext(null);
export const useApp = () => useContext(AppContext);

export function useResource(path, version = 0) {
  const [state, setState] = useState({ data: null, loading: Boolean(path), error: "" });
  useEffect(() => {
    if (!path) { setState({ data: null, loading: false, error: "" }); return; }
    const controller = new AbortController();
    setState({ data: null, loading: true, error: "" });
    api(path, { signal: controller.signal }).then(data => setState({ data, loading: false, error: "" }))
      .catch(error => { if (!controller.signal.aborted) setState({ data: null, loading: false, error: error.message }); });
    return () => controller.abort();
  }, [path, version]);
  return state;
}
