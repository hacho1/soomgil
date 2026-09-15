import { useEffect, useMemo, useRef, useState } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import L from "leaflet";
import "leaflet/dist/leaflet.css";
import "leaflet.markercluster";
import "leaflet.markercluster/dist/MarkerCluster.css";
import { ChevronLeft, ChevronRight, Heart, LocateFixed, MapPin, Search, Users } from "lucide-react";
import { api } from "../lib/api";
import { useApp, useResource } from "../lib/context";
import { useCrowdInfo } from "../lib/useCrowdInfo";
import { getMapStatus, mapPlaceLabel, mapTypes } from "../lib/mapPlaces";
import { distanceLabel, mapPageSize, mergePlaces, nearbyCriteria, nearbyRadii, regionViews, scopedMapPlaces } from "../lib/mapDiscovery";
import MapPlaceIcon from "../components/MapPlaceIcon";
import { PlaceVisitSummary } from "../components/PlaceVisitInfo";
import { Crowd, EmptyState, ErrorState, IconButton, Loading, Modal, PageHeading, PlaceImage, Rating, RequireLogin } from "../components/ui";

const emptyPlaces = [];
const pointOf = place => [Number(place.mapy), Number(place.mapx)];
const distanceBetween = (a, b) => L.latLng(a).distanceTo(L.latLng(b));
const motionOptions = () => ({ animate: !window.matchMedia("(prefers-reduced-motion: reduce)").matches });

export default function MapPage() {
  const { user, favorites, openPlace, revision } = useApp();
  const container = useRef(), map = useRef(), layer = useRef(), radiusLayer = useRef(), locationMarker = useRef();
  const markers = useRef(new Map()), request = useRef(null), requestVersion = useRef(0), locationVersion = useRef(0);
  const [category, setCategory] = useState("");
  const [region, setRegion] = useState("");
  const [scope, setScope] = useState("nearby");
  const [center, setCenter] = useState(null), [radiusKm, setRadiusKm] = useState(3);
  const [locating, setLocating] = useState(false), [locationError, setLocationError] = useState("");
  const [selected, setSelected] = useState(null), [selectedId, setSelectedId] = useState(null);
  const selectedMarkerId = useRef(null);
  selectedMarkerId.current = selectedId;
  const [zoom, setZoom] = useState(6);
  const [page, setPage] = useState(1), [sort, setSort] = useState("slow");
  const [additional, setAdditional] = useState([]), [feed, setFeed] = useState(null);
  const [discovering, setDiscovering] = useState(false), [discoveryError, setDiscoveryError] = useState("");
  const [retry, setRetry] = useState(0);
  const followed = useResource(user && scope === "following" ? `/api/users/${user.userId}/following-favorites` : null, `${revision}:${retry}`);
  const followingPlaces = user && Array.isArray(followed.data) ? followed.data : emptyPlaces;
  const favoriteIds = useMemo(() => new Set(favorites.map(place => String(place.id))), [favorites]);
  const followingIds = useMemo(() => new Set(followingPlaces.map(place => String(place.id))), [followingPlaces]);
  const source = scope === "favorites" ? (user ? favorites : emptyPlaces) : scope === "following" ? followingPlaces : additional;
  const located = useMemo(() => scopedMapPlaces({ places: source, scope, center, radiusKm, category, sort, distanceBetween }), [source, scope, center, radiusKm, category, sort]);
  const pages = Math.max(1, Math.ceil(located.length / mapPageSize));
  const current = Math.min(page, pages);
  const visible = useMemo(() => located.slice((current - 1) * mapPageSize, current * mapPageSize), [located, current]);
  const crowdInfo = useCrowdInfo(visible, visible.map(place => place.id));
  const loading = scope === "following" && followed.loading;
  const error = scope === "following" && followed.error;
  const privateGuest = scope !== "nearby" && !user;

  function cancelDiscovery() { request.current?.abort(); requestVersion.current++; setDiscovering(false); }
  function cancelLocation() { locationVersion.current++; setLocating(false); }
  function clearSelection() { setPage(1); setSelected(null); setSelectedId(null); setFeed(null); }

  async function discover(criteria, nextPage = 1, requestedSort = sort) {
    if (!criteria) return;
    request.current?.abort();
    const controller = new AbortController(); request.current = controller;
    const version = ++requestVersion.current;
    setDiscovering(true); setDiscoveryError("");
    try {
      const path = `/api/places/nearby?${new URLSearchParams({ ...criteria, page: nextPage, size: 60, sort: requestedSort })}`;
      const result = await api(path, { signal: controller.signal });
      if (version !== requestVersion.current) return;
      setAdditional(previous => nextPage === 1 ? result.items : mergePlaces(previous, result.items));
      setFeed({ ...result, criteria });
    } catch (failure) {
      if (version === requestVersion.current && failure.name !== "AbortError") setDiscoveryError(failure.message);
    } finally { if (version === requestVersion.current) setDiscovering(false); }
  }

  useEffect(() => () => { request.current?.abort(); requestVersion.current++; locationVersion.current++; }, []);

  useEffect(() => {
    const animate = motionOptions().animate;
    const instance = L.map(container.current, { zoomControl: false, scrollWheelZoom: false, zoomAnimation: animate, fadeAnimation: animate, markerZoomAnimation: animate }).setView(regionViews[""].slice(0, 2), 6);
    map.current = instance;
    L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", { attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>', maxZoom: 19 }).addTo(instance);
    L.control.zoom({ position: "bottomright" }).addTo(instance);
    layer.current = L.markerClusterGroup({
      maxClusterRadius: 48, showCoverageOnHover: false, animate, spiderfyDistanceMultiplier: 1.8,
      iconCreateFunction: cluster => L.divIcon({ className: "map-cluster", html: renderToStaticMarkup(<span className="map-cluster-count" aria-label={`주변 장소 ${cluster.getChildCount()}곳 확대`}>{cluster.getChildCount()}<small aria-hidden="true">곳</small></span>), iconSize: [40, 40], iconAnchor: [20, 20] }),
    }).addTo(instance);
    instance.on("zoomend", () => setZoom(instance.getZoom()));
    const observer = new ResizeObserver(() => instance.invalidateSize()); observer.observe(container.current);
    return () => { observer.disconnect(); instance.remove(); map.current = null; radiusLayer.current = null; locationMarker.current = null; markers.current.clear(); };
  }, []);

  useEffect(() => {
    const instance = map.current;
    if (!instance) return;
    radiusLayer.current?.remove(); radiusLayer.current = null;
    if (scope === "nearby" && center) {
      radiusLayer.current = L.circle([center.lat, center.lng], { radius: radiusKm * 1000, color: "#197563", fillOpacity: 0.04, weight: 1, dashArray: "5 6", interactive: false }).addTo(instance);
      instance.fitBounds(radiusLayer.current.getBounds(), { padding: [14, 14], maxZoom: 15, ...motionOptions() });
    }
  }, [scope, center, radiusKm]);

  useEffect(() => {
    if (!map.current) return;
    layer.current.clearLayers(); markers.current.clear();
    visible.forEach(place => {
      const status = getMapStatus(place, favoriteIds, followingIds), label = mapPlaceLabel(place, status);
      const icon = L.divIcon({ className: "map-pin", html: renderToStaticMarkup(<MapPlaceIcon category={place.category} {...status} size="marker" />), iconSize: [36, 40], iconAnchor: [18, 40], tooltipAnchor: [0, -38] });
      const marker = L.marker(pointOf(place), { icon, title: label, keyboard: true });
      marker.on("add", () => {
        marker.getElement()?.setAttribute("aria-label", `${label} 미리 보기`);
        marker.getElement()?.classList.toggle("is-selected", selectedMarkerId.current === String(place.id));
        marker.setZIndexOffset(selectedMarkerId.current === String(place.id) ? 1000 : 0);
      });
      const tooltip = document.createElement("span"); tooltip.textContent = label; marker.bindTooltip(tooltip);
      marker.on("click", () => preview(place));
      markers.current.set(String(place.id), marker);
    });
    layer.current.addLayers([...markers.current.values()]);
    if (scope !== "nearby" && visible.length) map.current.fitBounds(L.latLngBounds(visible.map(pointOf)), { padding: [44, 44], maxZoom: 14, ...motionOptions() });
  }, [visible, favoriteIds, followingIds, scope]);

  useEffect(() => {
    markers.current.forEach((marker, id) => { marker.getElement()?.classList.toggle("is-selected", id === selectedId); marker.setZIndexOffset(id === selectedId ? 1000 : 0); });
  }, [selectedId, visible]);

  function preview(place) { setSelectedId(String(place.id)); setSelected(place); }
  function searchFrom(point, nextRegion = "", nextRadius = radiusKm) {
    cancelLocation();
    const criteria = nearbyCriteria(point, nextRadius, category);
    if (!criteria) { setLocationError("국내 지역을 선택해서 찾아주세요."); return false; }
    clearSelection(); setLocationError("");
    setScope("nearby"); setCenter(point); setRegion(nextRegion); setRadiusKm(nextRadius);
    discover(criteria);
    return true;
  }
  function chooseRegion(value) {
    if (!value) return;
    const [lat, lng] = regionViews[value]; searchFrom({ lat, lng, kind: "selected" }, value);
  }
  function chooseCategory(value) {
    cancelLocation(); cancelDiscovery(); clearSelection(); setCategory(value); setDiscoveryError("");
    if (scope === "nearby") discover(nearbyCriteria(center, radiusKm, value));
  }
  function selectScope(value) {
    cancelLocation(); cancelDiscovery(); setSelected(null); setSelectedId(null); setPage(1);
    setScope(value); setDiscoveryError("");
  }
  function locate() {
    if (!navigator.geolocation) { setLocationError("위치를 지원하지 않는 브라우저예요. 지역을 선택해 주세요."); return; }
    const instance = map.current, version = ++locationVersion.current;
    setLocating(true); setLocationError("");
    navigator.geolocation.getCurrentPosition(position => {
      if (map.current !== instance || version !== locationVersion.current) return;
      const point = { lat: position.coords.latitude, lng: position.coords.longitude, kind: "device" };
      if (!searchFrom(point)) return;
      if (locationMarker.current) locationMarker.current.setLatLng([point.lat, point.lng]);
      else locationMarker.current = L.circleMarker([point.lat, point.lng], { radius: 7, color: "#fff", weight: 3, fillColor: "#3679d8", fillOpacity: 1 }).addTo(instance).bindTooltip("내 위치");
    }, failure => {
      if (map.current !== instance || version !== locationVersion.current) return;
      setLocating(false);
      setLocationError(failure.code === 1 ? "위치 권한이 꺼져 있어요. 지역을 선택해도 여행지를 찾을 수 있어요." : "위치를 확인하지 못했어요. 다시 시도하거나 지역을 선택해 주세요.");
    }, { timeout: 10000, maximumAge: 60000, enableHighAccuracy: false });
  }
  const heading = scope === "following" ? "팔로잉의 발견" : scope === "favorites" ? "찜한 장소" : center?.kind === "device" ? "내 주변의 발견" : "선택 위치 주변";

  return <div className="page map-page">
    <PageHeading title="지도 탐색" />
    <nav className="map-scope" aria-label="장소 범위">{[["nearby", "내 주변", LocateFixed], ["favorites", "찜", Heart], ["following", "팔로잉", Users]].map(([value, label, Icon]) => <button key={value} aria-pressed={scope === value} onClick={() => selectScope(value)}><Icon size={17} />{label}</button>)}</nav>
    {scope === "nearby" && <div className="map-discovery-toolbar"><label className="map-region"><MapPin size={16} /><span className="sr-only">탐색 지역</span><select aria-label="탐색 지역" value={region} onChange={event => chooseRegion(event.target.value)}><option value="">{center?.kind === "device" ? "내 위치" : "지역 선택"}</option>{Object.keys(regionViews).filter(Boolean).map(value => <option value={value} key={value}>{value}</option>)}</select></label><label className="map-radius"><span>반경</span><select aria-label="탐색 반경" value={radiusKm} onChange={event => { const value = Number(event.target.value); if (center) searchFrom(center, region, value); else { cancelLocation(); setRadiusKm(value); } }}>{nearbyRadii.map(value => <option key={value} value={value}>{value}km</option>)}</select></label></div>}
    {scope === "nearby" && !center && <div className="map-location-start"><button className="primary-button full" disabled={locating} onClick={locate}><LocateFixed size={18} />{locating ? "위치 확인 중" : "내 위치로 찾기"}</button></div>}
    {locationError && scope === "nearby" && <p className="map-location-error" role="status">{locationError}</p>}
    <nav className="map-legend" aria-label="지도 범례">{mapTypes.map(({ id, label }) => <button key={id} aria-pressed={category === id} onClick={() => chooseCategory(id)}><MapPlaceIcon category={id} size="legend" /><span>{label}</span></button>)}</nav>
    <div className="map-container">
      <div ref={container} className="real-map" aria-label="여행지 지도" />
      {scope === "nearby" && zoom >= 10 && <button className="map-search-area" disabled={discovering} onClick={() => { const point = map.current.getCenter(); searchFrom({ lat: point.lat, lng: point.lng, kind: "selected" }); }}><Search size={16} />{discovering ? "찾는 중" : "여기서 찾기"}</button>}
      <IconButton className="locate-button" label={locating ? "위치 확인 중" : "내 위치로 찾기"} disabled={locating} onClick={locate}><LocateFixed size={21} /></IconButton>
    </div>
    <div className="map-coverage" aria-live="polite"><span>{scope === "nearby" ? center ? `반경 ${radiusKm}km · ${located.length.toLocaleString()}곳` : "위치 선택 전" : `${heading} ${located.length.toLocaleString()}곳`}</span><span>지도 {visible.length}곳</span></div>
    {privateGuest ? <RequireLogin /> : <>
      {(center || scope !== "nearby") && <div className="section-heading map-results-heading"><h2>{heading}</h2><label><span className="sr-only">지도 장소 정렬</span><select aria-label="지도 장소 정렬" value={sort} onChange={event => { const value = event.target.value; setSort(value); setPage(1); setSelectedId(null); if (scope === "nearby") discover(nearbyCriteria(center, radiusKm, category), 1, value); }}><option value="near">{center?.kind === "device" ? "내 위치 가까운순" : center ? "선택 위치 가까운순" : "지역순"}</option><option value="slow">자연·산책 우선</option></select></label></div>}
      {error && <ErrorState message={error} retry={() => setRetry(value => value + 1)} />}
      {(loading || discovering) && !located.length ? <Loading cards={1} /> : <div className="map-place-list">{crowdInfo.places.map(place => {
        const status = getMapStatus(place, favoriteIds, followingIds);
        const distance = visible.find(item => String(item.id) === String(place.id))?.distanceMeters;
        return <button key={place.id} className={selectedId === String(place.id) ? "is-selected" : ""} aria-label={`${mapPlaceLabel(place, status)} 미리 보기`} onClick={() => preview(place)}><PlaceImage place={place} /><span className="map-place-copy"><strong>{place.name}</strong><small>{place.region} · {place.categoryLabel}{center && Number.isFinite(distance) && ` · ${distanceLabel(distance)}`}</small><Crowd place={place} /></span><ChevronRight size={16} className="map-place-chevron" /></button>;
      })}</div>}
      {!loading && !discovering && !error && !located.length && (scope !== "nearby" || center) && <EmptyState title={scope === "favorites" ? "찜한 장소가 없어요" : scope === "following" ? "팔로잉이 공유한 장소가 없어요" : "이 반경에 찾은 장소가 없어요"} />}
      {crowdInfo.failed && <button className="text-button" disabled={crowdInfo.loading} onClick={crowdInfo.retry}>혼잡도 다시 확인</button>}
      {pages > 1 && <nav className="map-pagination" aria-label="지도 장소 목록 페이지"><IconButton label="이전 장소 목록" disabled={current === 1} onClick={() => { setPage(current - 1); setSelectedId(null); }}><ChevronLeft size={18} /></IconButton><span>{current} / {pages}</span><IconButton label="다음 장소 목록" disabled={current === pages} onClick={() => { setPage(current + 1); setSelectedId(null); }}><ChevronRight size={18} /></IconButton></nav>}
    </>}
    {scope === "nearby" && <div className="map-feed-footer">{feed && <p>DB 주변 장소 · {Math.min(feed.page * feed.pageSize, feed.total).toLocaleString()} / {feed.total.toLocaleString()}건 조회</p>}{discoveryError && <><p role="status" className="form-error">{discoveryError}</p><button className="secondary-button" disabled={discovering} onClick={() => discover(nearbyCriteria(center, radiusKm, category))}>주변 장소 다시 찾기</button></>}{feed?.hasMore && <button className="secondary-button full" disabled={discovering} onClick={() => discover(feed.criteria, feed.page + 1)}>{discovering ? "불러오는 중" : "주변 장소 더 불러오기"}</button>}</div>}
    {selected && <MapPreview place={selected} onClose={() => setSelected(null)} onDetail={() => { setSelected(null); openPlace(selected); }} />}
  </div>;
}

function MapPreview({ place, onClose, onDetail }) {
  const places = useMemo(() => [place], [place]);
  const crowd = useCrowdInfo(places, [place.id]);
  return <Modal title="장소 미리보기" onClose={onClose} className="map-summary-dialog"><PlaceImage place={place} /><div className="map-summary"><h2>{place.name}</h2><p>{place.address}</p><div className="place-metrics"><Rating place={place} /><Crowd place={crowd.places[0]} /></div>{crowd.failed && <button className="text-button" onClick={crowd.retry}>혼잡도 다시 확인</button>}<PlaceVisitSummary key={place.id} id={place.id} /><button className="primary-button full" onClick={onDetail}>자세히 보기</button></div></Modal>;
}
