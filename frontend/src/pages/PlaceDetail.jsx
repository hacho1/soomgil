import { useState } from "react";
import { Link } from "react-router-dom";
import { Heart, MapPin, Share2, ThumbsUp, Trash2 } from "lucide-react";
import { api, dateLabel } from "../lib/api";
import { crowdNote } from "../lib/crowd";
import { isCourse, placeMeta, verifiedRating } from "../lib/placePresentation";
import { useApp, useResource } from "../lib/context";
import { useCrowdInfo } from "../lib/useCrowdInfo";
import PlaceVisitInfo from "../components/PlaceVisitInfo";
import { Crowd, EmptyState, ErrorState, IconButton, Loading, Modal, PlaceCard, PlaceImage, Rating } from "../components/ui";

export default function PlaceDetail({ id, onClose }) {
  const { user, favorites, toggleFavorite, notify, changed, openPlace } = useApp();
  const [tab, setTab] = useState("info");
  const [text, setText] = useState("");
  const [sort, setSort] = useState("latest");
  const [version, setVersion] = useState(0);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const detail = useResource(`/api/places/${id}`);
  const reviews = useResource(tab === "reviews" ? `/api/places/${id}/reviews?sort=${sort}` : null, version);
  const place = detail.data;
  const saved = favorites.some(item => item.id === Number(id));
  async function share() {
    const url = `${window.location.origin}/places/${id}`;
    try { if (navigator.share) await navigator.share({ title: place.name, url }); else { await navigator.clipboard.writeText(url); notify("장소 링크를 복사했어요."); } }
    catch (failure) { if (failure.name !== "AbortError") notify("링크를 공유하지 못했어요. 주소를 확인해주세요."); }
  }
  async function reviewAction(path, options) {
    if (busy) return; setBusy(true); setError("");
    try { await api(path, options); setVersion(v => v + 1); await changed(); if (options.method === "POST" && path.endsWith("/reviews")) setText(""); }
    catch (failure) { setError(failure.message); } finally { setBusy(false); }
  }
  return <Modal title="장소 상세" onClose={onClose} className="place-detail-dialog">{detail.loading ? <Loading /> : detail.error ? <ErrorState message={detail.error} /> : place && <>
    <PlaceImage place={place} className="detail-photo" /><div className="detail-content">{placeMeta(place) && <span className="place-category">{placeMeta(place)}</span>}<h1>{place.name}</h1>{place.address?.trim() ? <p className="detail-address"><MapPin size={17} />{place.address}</p> : <p className="detail-address">{isCourse(place) ? "여러 장소를 둘러보는 여행 코스" : "등록된 주소가 없어요."}</p>}
      <div className="detail-actions"><button className={`secondary-button ${saved ? "saved" : ""}`} onClick={() => toggleFavorite(place)}><Heart size={18} fill={saved ? "currentColor" : "none"} />{saved ? "찜한 장소" : "찜하기"}</button><button className="secondary-button" onClick={share}><Share2 size={18} />공유</button></div>
      {!isCourse(place) && <><div className="detail-metrics">{verifiedRating(place) && <div><span>숨길 평점</span><Rating place={place} /></div>}<div><span>혼잡도</span><Crowd place={place} /></div></div>
      {crowdNote(place) && <p className="crowd-source-note">{crowdNote(place)}</p>}</>}
      <div className="detail-tabs" role="tablist">{[["info", isCourse(place) ? "코스 정보" : "장소 정보"], ["reviews", "후기"], ...(!isCourse(place) ? [["nearby", "주변 장소"]] : [])].map(([value, label]) => <button key={value} role="tab" aria-selected={tab === value} onClick={() => setTab(value)}>{label}</button>)}</div>
      {tab === "info" && place.recommendationReason && <section className="detail-panel recommendation-summary"><h2>추천 이유</h2><p>{place.recommendationReason}</p></section>}
      {tab === "info" && <div className="detail-panel"><PlaceVisitInfo key={place.id} place={place} />{!isCourse(place) && <section><h2>함께 둘러볼 코스</h2>{place.halfDayCourse?.length > 1 ? <><ol className="course-list">{place.halfDayCourse.map(step => <li key={step.step}><span>{step.step}</span><div><button className="place-name-button" onClick={() => { if (step.placeId !== place.id) openPlace({ id: step.placeId }); }}>{step.title}</button><p>{step.description}</p></div></li>)}</ol><p className="visit-info-note">직선거리 기준 주변 장소입니다. 실제 이동 경로와 운영시간을 확인해주세요.</p></> : <p className="muted">주변 장소가 더 등록되면 코스를 안내해드릴게요.</p>}</section>}<p className="data-note">{place.source?.startsWith("tour-api:") || place.source === "recovered-tour-api" ? "출처: 한국관광공사" : place.source?.startsWith("submission") ? "사용자 제보" : "관광 정보"}</p></div>}
      {tab === "reviews" && <section className="detail-panel"><div className="section-heading"><h2>여행자 후기</h2><select aria-label="후기 정렬" value={sort} onChange={event => setSort(event.target.value)}><option value="latest">최신순</option><option value="likes">좋아요순</option></select></div>{user ? <form className="review-form" onSubmit={event => { event.preventDefault(); reviewAction(`/api/places/${id}/reviews`, { method: "POST", body: JSON.stringify({ text }) }); }}><label className="sr-only" htmlFor="review-text">후기 내용</label><textarea id="review-text" value={text} required maxLength={2000} onChange={event => setText(event.target.value)} placeholder="다녀온 경험을 나눠주세요." /><div className="review-footer"><small>{text.length}/2,000</small><button className="primary-button" disabled={busy || !text.trim()}>후기 등록</button></div></form> : <Link className="secondary-button" to="/loginform" state={{ returnTo: `/places/${id}` }}>로그인하고 후기 남기기</Link>}{error && <p className="form-error" role="alert">{error}</p>}
        {reviews.loading ? <Loading cards={2} /> : reviews.error ? <ErrorState message={reviews.error} /> : <div className="review-list">{reviews.data?.map(review => <article className="review-item" key={review.id}><div className="review-footer"><strong>익명{review.mine && <small> · 내 후기</small>}</strong><time>{dateLabel(review.createdAt)}</time></div><p>{review.text}</p><div className="review-footer"><button className={`like-button ${review.liked ? "liked" : ""}`} disabled={busy} onClick={() => user ? reviewAction(`/api/reviews/${review.id}/like`, { method: "POST", body: "{}" }) : notify("좋아요는 로그인 후 이용할 수 있어요.")}><ThumbsUp size={15} />{review.likeCount}</button>{review.mine && <IconButton label="후기 삭제" disabled={busy} onClick={() => reviewAction(`/api/reviews/${review.id}`, { method: "DELETE" })}><Trash2 size={16} /></IconButton>}</div></article>)}{reviews.data?.length === 0 && <EmptyState title="아직 후기가 없어요">첫 여행 이야기를 들려주세요.</EmptyState>}</div>}
      </section>}
      {tab === "nearby" && <section className="detail-panel"><h2>주변 숨은 장소</h2>{place.nearbyHiddenPlaces?.length ? <NearbyPlaces places={place.nearbyHiddenPlaces} /> : <EmptyState title="주변에 등록된 장소가 아직 없어요" />}</section>}
    </div>
  </>}</Modal>;
}

function NearbyPlaces({ places }) {
  const crowdInfo = useCrowdInfo(places, places.map(place => place.id));
  return <div className="result-list">{crowdInfo.places.map(place => <div key={place.id}><PlaceCard place={place} /><small className="distance-label">직선거리 {place.distanceKm}km</small></div>)}{crowdInfo.failed && <button className="text-button" disabled={crowdInfo.loading} onClick={crowdInfo.retry}>혼잡도 다시 확인</button>}</div>;
}
