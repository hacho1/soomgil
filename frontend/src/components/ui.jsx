import { useEffect, useRef, useState } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { ArrowLeft, ChevronRight, Heart, ImageOff, Search, Star, X } from "lucide-react";
import { useApp } from "../lib/context";
import { useReveal } from "../lib/useReveal";
import { saveAuthIntent } from "../lib/authFlow";
import { crowdText } from "../lib/crowd";
import { recommendationHint } from "../lib/recommendations";
import { isCourse, placeMeta, placePhoto, verifiedRating } from "../lib/placePresentation";

export function IconButton({ label, children, className = "", ...props }) {
  return <button type="button" aria-label={label} title={label} className={`icon-button ${className}`} {...props}>{children}</button>;
}

export function Modal({ title, onClose, children, className = "" }) {
  const ref = useRef();
  const close = useRef(onClose);
  close.current = onClose;
  useEffect(() => {
    const dialog = ref.current;
    const overflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    dialog.showModal();
    const cancel = event => { event.preventDefault(); close.current(); };
    dialog.addEventListener("cancel", cancel);
    return () => { dialog.removeEventListener("cancel", cancel); dialog.close(); document.body.style.overflow = overflow; };
  }, []);
  return <dialog ref={ref} aria-label={title} className={`dialog ${className}`} onClick={event => { if (event.target === event.currentTarget) onClose(); }}>
    <div className="dialog-inner"><div className="dialog-head"><h2>{title}</h2><IconButton label="닫기" onClick={onClose}><X size={22} /></IconButton></div>{children}</div>
  </dialog>;
}

export function PageHeading({ title, action, children }) {
  return <div className="page-heading"><div><h1>{title}</h1>{children && <p>{children}</p>}</div>{action}</div>;
}

export function BackLink({ fallback = "/mypage", label }) {
  const location = useLocation();
  const navigate = useNavigate();
  return <button className="back-link" onClick={() => location.state?.returnTo ? navigate(-1) : navigate(fallback)}>
    <ArrowLeft size={18} />{label || location.state?.returnLabel || "마이페이지로 돌아가기"}
  </button>;
}

export function EmptyState({ title, children, action }) {
  return <div className="empty-state"><Search size={28} strokeWidth={1.5} /><h3>{title}</h3>{children && <p>{children}</p>}{action}</div>;
}
export function Loading({ cards = 3 }) {
  return <div role="status" aria-label="불러오는 중" className="skeleton-list">{Array.from({ length: cards }, (_, index) => <div className="skeleton" key={index} />)}</div>;
}
export function ErrorState({ message, retry }) {
  return <EmptyState title="불러오지 못했어요" action={retry && <button className="secondary-button" onClick={retry}>다시 시도</button>}>{message}</EmptyState>;
}

export function PlaceImage({ place, className = "" }) {
  const source = placePhoto(place);
  const [failed, setFailed] = useState("");
  const visible = source && source !== failed;
  return <div className={`place-image ${className}`}>
    {visible ? <img src={source} alt={place.name} loading="lazy" onError={() => setFailed(source)} /> : <span className="image-placeholder" role="img" aria-label={`${place.name} 사진 없음`}><ImageOff size={24} aria-hidden="true" /><small>사진 없음</small></span>}
  </div>;
}

export function Rating({ place }) {
  if (!verifiedRating(place)) return null;
  return <span className="rating" aria-label={`숨길 평점 ${place.hiddenRating.toFixed(1)}점`}><Star size={14} fill="currentColor" />
    {place.hiddenRating.toFixed(1)}
  </span>;
}
export function Crowd({ place }) {
  const crowd = crowdText(place);
  return <span className={`crowd ${crowd === "한적함" ? "quiet" : crowd === "붐빔" ? "busy" : ""}`}><i aria-hidden="true" />{crowd}</span>;
}

export function PlaceCard({ place, compact = false }) {
  const { favorites, toggleFavorite, openPlace } = useApp();
  const revealRef = useReveal();
  const saved = favorites.some(item => item.id === place.id);
  const hint = recommendationHint(place);
  return <article ref={revealRef} className={`place-card ${compact ? "compact" : ""}`}>
    <button className="place-cover-button" onClick={() => openPlace(place)} aria-label={`${place.name} 자세히 보기`}><PlaceImage place={place} /></button>
    <div className="place-card-body">{placeMeta(place) && <span className="place-category">{placeMeta(place)}</span>}
      <button className="place-name-button" onClick={() => openPlace(place)}><h3>{place.name}</h3></button>
      {!compact && place.address?.trim() && <p className="place-address">{place.address}</p>}
      {hint && !isCourse(place) && <p className="recommendation-hint">{hint}</p>}
      {!isCourse(place) && <div className="place-metrics"><Rating place={place} /><Crowd place={place} /></div>}
    </div>
    <IconButton className={`favorite-button ${saved ? "saved" : ""}`} label={saved ? `${place.name} 찜 해제` : `${place.name} 찜하기`} aria-pressed={saved} onClick={() => toggleFavorite(place)}><Heart size={19} fill={saved ? "currentColor" : "none"} /></IconButton>
  </article>;
}

export function MenuLink({ icon: Icon, title, detail, to, onClick, state }) {
  const location = useLocation();
  const content = <><Icon size={20} /><span>{title}</span>{detail != null && <small>{detail}</small>}<ChevronRight size={17} className="chevron" /></>;
  return to ? <Link className="menu-row" to={to} state={state || { returnTo: location.pathname, returnLabel: location.pathname === "/settings" ? "설정으로 돌아가기" : "마이페이지로 돌아가기" }}>{content}</Link>
    : <button className="menu-row" onClick={onClick}>{content}</button>;
}
export function Switch({ label, checked, onChange, disabled }) {
  return <label className="switch-row"><span>{label}</span><input type="checkbox" role="switch" checked={checked} onChange={event => onChange(event.target.checked)} disabled={disabled} /><span className="switch-track" aria-hidden="true" /></label>;
}
export function Avatar({ user, size = "" }) {
  const picture = user?.profileImage || "👤";
  return <div className={`avatar ${size}`} style={{ borderColor: user?.profileBorder }}>
    {picture.startsWith("data:") || picture.startsWith("https://") ? <img src={picture} alt="프로필" /> : <span>{picture}</span>}
  </div>;
}
export function RequireLogin({ children }) {
  const { user, authLoading } = useApp();
  const location = useLocation();
  if (authLoading) return <Loading />;
  if (user) return children;
  const remember = () => saveAuthIntent({ returnTo: location.pathname + location.search });
  return <EmptyState title="로그인하고 여행을 이어가세요" action={<div className="login-prompt-actions"><Link to="/loginform" className="primary-button" onClick={remember}>로그인</Link><Link to="/signup" className="secondary-button" onClick={remember}>회원가입</Link></div>}>저장한 장소와 여행 기록을 한곳에서 만나요.</EmptyState>;
}
