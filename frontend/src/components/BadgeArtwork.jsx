import { Award, Check, LockKeyhole } from "lucide-react";
import { useState } from "react";
import artwork from "../data/badge-art.json";

export default function BadgeArtwork({ badge, size = "regular", locked = false, status = false }) {
  const [failed, setFailed] = useState(false);
  const asset = artwork.assets[badge.id];
  return <span aria-hidden="true" className={`badge-art badge-art--${size} badge-tone-${asset?.tone || "mint"} ${locked ? "is-locked" : ""}`}>
    {asset && !failed ? <img src={`/assets/badges/${asset.file}`} alt="" width="256" height="256" loading="lazy" decoding="async" draggable="false" onError={() => setFailed(true)} /> : <Award size={32} />}
    {status && <span className={`badge-art-stamp ${locked ? "locked" : "earned"}`}>{locked ? <LockKeyhole size={12} /> : <Check size={13} strokeWidth={3} />}</span>}
  </span>;
}
