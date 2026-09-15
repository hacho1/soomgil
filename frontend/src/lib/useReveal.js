import { useEffect, useRef } from "react";
import { revealWhenVisible } from "./motion";

export function useReveal() {
  const ref = useRef(null);
  useEffect(() => revealWhenVisible(ref.current), []);
  return ref;
}
