export function revealWhenVisible(element, environment = window) {
  if (!element || typeof element.animate !== "function" || !environment.IntersectionObserver) return () => {};
  const preference = environment.matchMedia("(prefers-reduced-motion: reduce)");
  let played = false;
  let animation;
  const observer = new environment.IntersectionObserver(entries => {
    if (played || preference.matches || !entries.some(entry => entry.isIntersecting)) return;
    played = true;
    observer.disconnect();
    animation = element.animate([
      { opacity: 0.25, transform: "translateY(12px)" },
      { opacity: 1, transform: "translateY(0)" }
    ], { duration: 380, easing: "cubic-bezier(.2,.7,.2,1)" });
  }, { threshold: 0.08 });
  const preferenceChanged = () => {
    if (preference.matches) { observer.disconnect(); animation?.cancel(); }
    else if (!played) observer.observe(element);
  };
  preference.addEventListener("change", preferenceChanged);
  if (!preference.matches) observer.observe(element);
  return () => { observer.disconnect(); animation?.cancel(); preference.removeEventListener("change", preferenceChanged); };
}
