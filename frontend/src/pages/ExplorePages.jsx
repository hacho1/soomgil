import { useEffect, useMemo, useRef, useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { ArrowRight, Camera, ChevronLeft, ChevronRight, Coffee, Landmark, Leaf, MapPin, Pause, Play, Search, SlidersHorizontal, Sparkles, Tent, Trees, Utensils, Waves, X } from "lucide-react";
import categories from "../data/categories.json" with { type: "json" };
import regions from "../data/regions.json" with { type: "json" };
import { useApp, useResource } from "../lib/context";
import { matchesCrowd } from "../lib/crowd";
import { useCrowdInfo } from "../lib/useCrowdInfo";
import { usePlaceSearch } from "../lib/usePlaceSearch";
import { homeSlides, homeVisiblePlaces } from "../lib/recommendations";
import { ErrorState, IconButton, Loading, Modal, PageHeading, PlaceCard, PlaceImage, EmptyState } from "../components/ui";
import SubcategoryIcon from "../components/SubcategoryIcon";

const icons = { tour: Waves, walk: Trees, camping: Tent, food: Utensils, cafe: Coffee, culture: Landmark, nature: Leaf, experience: Sparkles, photo: Camera };
export function HomePage() {
  const { catalog, homeFeed, user, catalogLoading, catalogError, reloadCatalog, openSearch, openPlace, recentViews } = useApp();
  const navigate = useNavigate();
  const season = homeFeed?.season || "오늘";
  const banners = useMemo(() => homeSlides(homeFeed), [homeFeed]);
  const [index, setIndex] = useState(0);
  const [paused, setPaused] = useState(false);
  const [delayKey, setDelayKey] = useState(0);
  const touch = useRef(null);
  const interactionTime = useRef(0);
  const currentIndex = banners.length ? index % banners.length : 0;
  function move(next) { if (!banners.length) return; interactionTime.current = Date.now(); setIndex((next + banners.length) % banners.length); setDelayKey(value => value + 1); }
  useEffect(() => {
    if (paused || banners.length < 2 || window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
    const timer = setTimeout(() => { if (!document.hidden && Date.now() - interactionTime.current >= 5900) setIndex(value => (value + 1) % banners.length); else setDelayKey(value => value + 1); }, 6000);
    return () => clearTimeout(timer);
  }, [index, paused, delayKey, banners.length]);
  const visiblePlaces = useMemo(() => homeVisiblePlaces(catalog, recentViews), [catalog, recentViews]);
  const crowdInfo = useCrowdInfo(visiblePlaces, visiblePlaces.map(place => place.id));
  const withCrowd = place => crowdInfo.places.find(item => String(item.id) === String(place.id)) || place;
  const todayPlaces = catalog.slice(0, 4).map(withCrowd);
  return <div className="home-page">
    <div className="home-search"><button className="search-field" onClick={() => openSearch()}><Search size={20} /><span>어디로 떠날까요?</span></button></div>
    {banners.length > 0 && <section className="travel-banner" aria-label="계절 여행 추천" onPointerDown={event => { touch.current = event.clientX; interactionTime.current = Date.now(); }} onPointerCancel={() => { touch.current = null; }} onPointerUp={event => { if (touch.current !== null && Math.abs(event.clientX - touch.current) > 45) move(currentIndex + (event.clientX < touch.current ? 1 : -1)); touch.current = null; }}>
      <div className="banner-track" style={{ transform: `translateX(-${currentIndex * 100}%)` }}>
        {banners.map((banner, i) => <div className="banner-slide" key={banner.id} aria-hidden={i !== currentIndex} style={{ backgroundImage: `linear-gradient(0deg, rgba(10,20,25,.68), transparent 80%), url("${banner.image}")` }}>
          <span className="banner-kicker">{season}의 여행 제안</span><h1>{banner.name}</h1><p>{banner.region} · {banner.bannerTheme}</p>
          <button className="banner-place-link" onClick={() => openPlace(banner)} tabIndex={i === currentIndex ? 0 : -1}>장소 둘러보기 <ArrowRight size={17} /></button>
        </div>)}
      </div>
      {banners.length > 1 && <div className="banner-controls"><IconButton label="이전 추천" onClick={() => move(currentIndex - 1)}><ChevronLeft size={16} /></IconButton><span>{currentIndex + 1} / {banners.length}</span><IconButton label="다음 추천" onClick={() => move(currentIndex + 1)}><ChevronRight size={16} /></IconButton><IconButton label={paused ? "자동 넘김 재생" : "자동 넘김 정지"} onClick={() => setPaused(!paused)}>{paused ? <Play size={13} /> : <Pause size={13} />}</IconButton></div>}
    </section>}
    <section className="home-section quick-section"><div className="section-heading"><h2>어떤 여행을 좋아하세요?</h2><Link to="/category" aria-label="카테고리 전체 보기"><ChevronRight size={20} /></Link></div>
      <div className="quick-grid">{categories.slice(0, 6).map((item, i) => { const Icon = icons[item.id]; return <Link className={`quick-item tone-${i}`} to={`/search?category=${item.id}`} key={item.id}><span><Icon size={25} strokeWidth={1.7} /></span><strong>{item.label}</strong></Link>; })}</div>
    </section>
    <section className="home-section"><div className="section-heading"><div><span className="eyebrow">{homeFeed?.personalized ? "FOR YOU" : "TODAY'S PICKS"}</span><h2>오늘의 숨은 장소</h2></div><Link to={user ? "/mypage/interests" : "/search"}>{user ? "취향 설정" : "전체 보기"} <ChevronRight size={15} /></Link></div>
      {catalogLoading ? <Loading cards={2} /> : catalogError ? <ErrorState message={catalogError} retry={reloadCatalog} /> : todayPlaces.length ? <div className="compact-grid">{todayPlaces.map(place => <PlaceCard compact key={place.id} place={place} />)}</div> : <EmptyState title="추천할 장소를 준비하고 있어요" action={<Link to="/search" className="secondary-button">장소 검색</Link>} />}
    </section>
    <section className="home-section region-section"><div className="section-heading"><h2>지역으로 떠나볼까요?</h2></div><div className="region-grid">{["강원", "제주", "서울", "경기", "부산", "인천"].map(region => <Link key={region} to={`/search?region=${region}`}><MapPin size={17} />{region}<ArrowRight size={14} /></Link>)}</div><button className="text-button all-regions" onClick={() => navigate("/search")}>전국 여행지 보기 <ArrowRight size={16} /></button></section>
    {recentViews.length > 0 && <section className="home-section"><div className="section-heading"><h2>최근 본 장소</h2><Link to="/mypage/recent">모두 보기</Link></div><div className="horizontal-places">{recentViews.slice(0, 6).map(withCrowd).map(place => <PlaceCard compact key={place.id} place={place} />)}</div></section>}
    <footer className="home-footer"><strong>Soomgil</strong><span>발견하고, 머무르고, 기록하는 여행</span><Link to="/about">서비스 정보</Link></footer>
  </div>;
}

export function CategoryPage() {
  const [groupParams, setGroupParams] = useSearchParams();
  const group = categories.find(item => item.id === groupParams.get("group")) || categories[0];
  const setGroup = item => setGroupParams({ group: item.id }, { replace: true });
  const { openSearch } = useApp();
  return <div className="page category-page"><PageHeading title="카테고리" action={<IconButton label="검색" onClick={() => openSearch()}><Search size={22} /></IconButton>} />
    <div className="category-layout"><nav className="category-rail" aria-label="여행 대주제">{categories.map(item => { const Icon = icons[item.id]; return <button className={group.id === item.id ? "active" : ""} key={item.id} onClick={() => setGroup(item)} aria-pressed={group.id === item.id}><Icon size={22} /><span>{item.label}</span></button>; })}</nav>
      <section className="category-content"><div className="category-intro"><span className="eyebrow">EXPLORE</span><h2>{group.label}</h2><Link to={`/search?category=${group.id}`}>{group.label} 전체 보기 <ArrowRight size={15} /></Link></div>
        <div className="subcategory-grid">{group.items.map(item => <Link to={`/search?category=${group.id}&q=${encodeURIComponent(item)}`} key={item}><SubcategoryIcon group={group.id} topic={item} /><strong>{item}</strong></Link>)}</div>
      </section>
    </div>
  </div>;
}

export function SearchPage() {
  const [params, setParams] = useSearchParams();
  const { openSearch } = useApp();
  const [filterOpen, setFilterOpen] = useState(false);
  const [version, setVersion] = useState(0);
  const query = params.get("q") || "";
  const region = params.get("region") || "";
  const category = params.get("category") || "";
  const sort = params.get("sort") || "recommended";
  const kind = ["place", "course"].includes(params.get("kind")) ? params.get("kind") : "";
  const crowd = params.get("crowd") || "";
  const page = Math.max(1, Math.floor(Number(params.get("page")) || 1));
  const apiParams = new URLSearchParams({ query, region, category, sort, kind });
  const resource = usePlaceSearch(apiParams.toString(), crowd, page, version);
  const related = useResource(query.trim() ? `/api/suggestions/related?q=${encodeURIComponent(query)}` : null);
  const source = useResource("/api/places/status", resource.total);
  const sync = source.data?.sync;
  const catalogLabel = sync?.initialComplete ? "정기 갱신" : sync?.state === "running" ? "전체 목록 수집 중" : "저장된 장소 검색";
  const candidates = resource.items;
  const crowdInfo = useCrowdInfo(candidates, candidates.map(place => place.id));
  const filtered = crowdInfo.places.filter(place => matchesCrowd(place, crowd));
  const pages = Math.max(1, Math.ceil((crowd ? filtered.length : resource.total) / 6));
  const current = crowd ? Math.min(page, pages) : resource.page;
  const displayed = crowd ? filtered.slice((current - 1) * 6, current * 6) : filtered;
  const pageNumbers = Array.from({ length: Math.min(5, pages) }, (_, i) => Math.max(0, Math.min(current - 3, pages - 5)) + i);
  function update(values) { const next = new URLSearchParams(params); next.delete("page"); Object.entries(values).forEach(([key, value]) => value ? next.set(key, value) : next.delete(key)); setParams(next); }
  return <div className="page results-page">
    <div className="results-toolbar"><button className="search-field" onClick={() => openSearch(query)}><Search size={20} /><span>{query || "지역, 장소, 여행 키워드"}</span></button><div className="filter-row"><button className="filter-button" onClick={() => setFilterOpen(true)}><SlidersHorizontal size={17} /> 필터 {(region || category || crowd) && <i />}</button><div className="filter-chips">{[["region", region], ["category", categories.find(item => item.id === category)?.label], ["crowd", crowd === "unknown" ? "혼잡도 미확인" : crowd]].filter(([, value]) => value).map(([key, value]) => <button className="chip selected" key={key} onClick={() => update({ [key]: "" })}>{value}<X size={13} /></button>)}{!region && <span className="chip">전국</span>}</div></div></div>
    <div className="results-summary"><h1>{query ? `“${query}” 검색 결과` : "여행지 탐색"}<small>{resource.loading ? "" : `${crowd ? "확인된 " : ""}${crowd ? filtered.length : resource.total}${kind === "place" ? "곳" : "건"}`}</small></h1><label className="sort-select"><span className="sr-only">결과 정렬</span><select value={sort} onChange={event => update({ sort: event.target.value })}><option value="recommended">추천순</option><option value="name">이름순</option></select></label></div>
    <div className="content-kind-tabs" role="group" aria-label="콘텐츠 종류">{[["", "전체"], ["place", "장소"], ["course", "여행 코스"]].map(([value,label]) => <button key={value} aria-pressed={kind === value} onClick={() => update({kind:value, crowd:value === "course" ? "" : crowd})}>{label}</button>)}</div>
    {related.data?.length > 0 && <nav className="related-searches" aria-label="연관검색어"><span>연관</span>{related.data.map(term => <button key={term} onClick={() => update({ q: term, category: "" })}>{term}</button>)}</nav>}
    {!resource.loading && <p className="data-source-note">전국 관광정보 {Number(sync?.storedTotal ?? resource.total).toLocaleString()}건 · {catalogLabel}</p>}
    {crowd && !resource.loading && <p className="data-source-note" role="status">{crowdInfo.loading ? "혼잡도를 확인하고 있어요." : `조회한 ${candidates.length} / 전체 후보 ${resource.total}곳 · 혼잡도 미확인 ${crowdInfo.unchecked}곳`}</p>}
    {(resource.loading && !candidates.length) || (crowdInfo.loading && !filtered.length) ? <Loading /> : resource.error ? <ErrorState message={resource.error} retry={() => setVersion(version + 1)} /> : filtered.length === 0 ? <EmptyState title={crowd && candidates.length ? "확인된 장소 중 조건에 맞는 곳이 없어요" : "조건에 맞는 장소가 없어요"} action={<button className="secondary-button" onClick={() => setParams({})}>전체 장소 보기</button>}>검색어나 지역을 바꿔 다시 찾아보세요.</EmptyState> : <><div className="result-list">{displayed.map(place => <PlaceCard key={place.id} place={place} />)}</div><nav className="pagination" aria-label="검색 결과 페이지">{pageNumbers.map(i => <button key={i} aria-current={current === i + 1 ? "page" : undefined} onClick={() => { const next = new URLSearchParams(params); next.set("page", i + 1); setParams(next); window.scrollTo({ top: 0 }); }}>{i + 1}</button>)}</nav></>}
    {crowdInfo.failed && <button className="secondary-button full" disabled={crowdInfo.loading} onClick={crowdInfo.retry}>혼잡도 다시 확인</button>}
    {crowd && resource.hasMore && <button className="secondary-button full" disabled={resource.loading || crowdInfo.loading} onClick={resource.loadMore}>다음 24곳 혼잡도 확인</button>}
    {filterOpen && <FilterSheet initial={{ region, category, crowd }} onClose={() => setFilterOpen(false)} onApply={values => { update(values); setFilterOpen(false); }} />}
  </div>;
}

function FilterSheet({ initial, onClose, onApply }) {
  const [form, setForm] = useState(initial);
  const choose = (key, value) => setForm({ ...form, [key]: value });
  return <Modal title="상세 조건" onClose={onClose} className="filter-dialog">
    <div className="filter-body">
      <fieldset><legend>지역</legend><div className="filter-options">{["", ...regions].map(value => <button className={`chip ${form.region === value ? "selected" : ""}`} aria-pressed={form.region === value} key={value} onClick={() => choose("region", value)}>{value || "전국"}</button>)}</div></fieldset>
      <fieldset><legend>여행 종류</legend><div className="filter-options">{[{ id: "", label: "전체" }, ...categories].map(item => <button className={`chip ${form.category === item.id ? "selected" : ""}`} aria-pressed={form.category === item.id} key={item.id} onClick={() => choose("category", item.id)}>{item.label}</button>)}</div></fieldset>
      <fieldset><legend>혼잡도</legend><p className="data-source-note">집중률 예측 기준이며, 실제 혼잡도와 다를 수 있어요.</p><div className="filter-options">{["", "한적함", "보통", "붐빔", "unknown"].map(value => <button className={`chip ${form.crowd === value ? "selected" : ""}`} aria-pressed={form.crowd === value} key={value} onClick={() => choose("crowd", value)}>{value === "unknown" ? "미확인" : value || "전체"}</button>)}</div></fieldset>
    </div>
    <div className="dialog-actions"><button className="secondary-button" onClick={() => setForm({ region: "", category: "", crowd: "" })}>초기화</button><button className="primary-button" onClick={() => onApply(form)}>결과 보기</button></div>
  </Modal>;
}
