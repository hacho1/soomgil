import { createLucideIcon } from "lucide-react";

// Local motifs missing from Lucide, using its 24px grid and shared stroke props.
export const Lighthouse = createLucideIcon("Lighthouse", [
  ["path", { d: "M7 22 9 9h6l2 13M6 22h12M8 9V5l4-3 4 3v4ZM9 14h6M8 18h8M12 5v4M2 5l3 1M2 11l3-1M22 5l-3 1M22 11l-3-1", key: "tower" }],
]);

export const SuspensionBridge = createLucideIcon("SuspensionBridge", [
  ["path", { d: "M4 3v18M20 3v18M2 17h20M4 5q8 12 16 0M8 10v7M12 11v6M16 10v7M1 10l3-5M20 5l3 5", key: "bridge" }],
]);

export const Cave = createLucideIcon("Cave", [
  ["path", { d: "m2 21 2-10 5-8h6l5 8 2 10ZM8 21v-7l4-5 4 5v7M9 3l1 3M15 3l-1 3", key: "entrance" }],
]);

export const Waterfall = createLucideIcon("Waterfall", [
  ["path", { d: "M2 5h5v11M17 16V5h5M10 3v12M14 3v12M2 19q2-2 4 0t4 0 4 0 4 0 4 0M8 22h8", key: "water" }],
]);

export const Hanok = createLucideIcon("Hanok", [
  ["path", { d: "M2 6c4 2 6-3 10-3s6 5 10 3l-2 4H4ZM5 10v10M19 10v10M3 20h18M10 20v-7h4v7M5 14h2M17 14h2", key: "house" }],
]);

export const Pagoda = createLucideIcon("Pagoda", [
  ["path", { d: "M12 2v2M8 5l4-1 4 1 1 2H7ZM9 7v3M15 7v3M5 11l7-2 7 2 1 2H4ZM7 13v3M17 13v3M3 17l9-2 9 2 1 2H2ZM6 19v3M18 19v3M4 22h16", key: "pagoda" }],
]);

export const Bamboo = createLucideIcon("Bamboo", [
  ["path", { d: "M8 22V3M6 6h4M6 12h4M6 18h4M15 22V7M13 11h4M13 17h4M15 8q0-5 6-5-1 5-6 5ZM8 11Q2 11 2 7q5 0 6 4Z", key: "bamboo" }],
]);

export const Kayak = createLucideIcon("Kayak", [
  ["path", { d: "M12 2C3 8 3 16 12 22c9-6 9-14 0-20Z", key: "hull" }],
  ["ellipse", { cx: "12", cy: "12", rx: "2.5", ry: "4", key: "seat" }],
  ["path", { d: "m3 4 18 16M2 2l3 1 1 3-3-1ZM22 22l-3-1-1-3 3 1Z", key: "paddle" }],
]);

export const Surfboard = createLucideIcon("Surfboard", [
  ["path", { d: "M17 2c-6 1-11 8-11 15l4 2c6-5 9-12 7-17ZM10 15l4-8M2 22q2-2 4 0t4 0 4 0 4 0 4 0", key: "board" }],
]);

export const Zipline = createLucideIcon("Zipline", [
  ["path", { d: "m2 3 20 5M13 6l-2 4M10 16l-2 5M10 16l6 3M7 11l3-1 3 4 4-3", key: "line" }],
  ["circle", { cx: "8", cy: "14", r: "2", key: "rider" }],
]);

export const Hydrangea = createLucideIcon("Hydrangea", [
  ["path", { d: "M12 17v5M12 21q-6 0-6-4 6 0 6 4Z", key: "stem" }],
  ["path", { d: "M12 3c3-3 6 0 5 2 4-1 6 4 3 6 2 3-1 6-4 5-1 3-6 3-7 0-4 1-7-3-4-6-3-3 0-7 3-5-1-3 2-4 4-2Z", key: "bloom" }],
  ["path", { d: "M9 8h.01M14 7h.01M12 11h.01M17 11h.01M8 13h.01M14 15h.01", key: "petals" }],
]);
