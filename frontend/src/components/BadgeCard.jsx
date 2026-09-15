import { useReveal } from "../lib/useReveal";
import BadgeArtwork from "./BadgeArtwork";

export const badgeGrades = { Basic: "기본", Rare: "희귀", Epic: "에픽" };

export default function BadgeCard({ badge, onOpen }) {
  const ref = useReveal();
  return <article ref={ref} className={`badge-card ${badge.earned ? "earned" : "locked"}`}>
    <button type="button" className="badge-open" onClick={() => onOpen(badge)} aria-label={`${badge.label} 배지 자세히 보기`}>
      <BadgeArtwork badge={badge} locked={!badge.earned} status />
      <span className={`badge-grade grade-${badge.grade.toLowerCase()}`}>{badgeGrades[badge.grade] || badge.grade} · {badge.group}</span>
      <span className="badge-name">{badge.label}</span>
    </button>
    <p className="badge-condition">{badge.condition}</p>
    <span className="badge-state">{badge.earned ? "획득 완료" : `${badge.count} / ${badge.target}`}</span>
    <progress aria-label={`${badge.label} 달성도`} max={badge.target} value={badge.count} />
  </article>;
}
