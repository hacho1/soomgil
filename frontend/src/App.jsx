import { useEffect, useMemo, useRef, useState } from "react";
import {
  Bell,
  Bookmark,
  CalendarDays,
  Camera,
  CheckCircle2,
  ChevronLeft,
  Compass,
  Coffee,
  Edit3,
  Heart,
  Home,
  ImagePlus,
  LogIn,
  Map as MapIcon,
  MapPin,
  Menu,
  MessageSquare,
  Moon,
  Palette,
  PenLine,
  Plus,
  RotateCw,
  Search,
  Send,
  Settings,
  Shield,
  Star,
  Sun,
  Trash2,
  User,
  UserPlus,
  Users,
  X,
  XCircle,
  ZoomIn
} from "lucide-react";
import categoryGroups from "./data/categories.json";

const API_BASE = import.meta.env.VITE_API_BASE_URL || "";
const ADMIN_CODE_STORAGE = "soomgilAdminCode";
const USER_STORAGE = "soomgilUser";
const SAVED_ID_STORAGE = "soomgilSavedIdentifier";
const AUTO_LOGIN_STORAGE = "soomgilAutoLogin";

const categoryIcons = {
  map: MapPin,
  compass: Compass,
  bookmark: Bookmark,
  star: Star,
  calendar: CalendarDays,
  heart: Heart,
  camera: Camera,
  coffee: Coffee
};

const categories = categoryGroups.map((item) => ({
  ...item,
  icon: categoryIcons[item.icon] || MapPin
}));

const badges = [
  { id: "first-search", label: "첫 검색", grade: "Basic", color: "#6d5dfc" },
  { id: "hidden-hunter", label: "숨은길 탐색", grade: "Rare", color: "#0ea5e9" },
  { id: "reviewer", label: "후기 기록자", grade: "Rare", color: "#16a34a" },
  { id: "traveler", label: "여행자", grade: "Epic", color: "#f97316" },
  { id: "community", label: "동네 제보자", grade: "Epic", color: "#d946ef" }
];

const seasonBanners = [
  {
    title: "여름 숨은 여행지",
    subtitle: "바다, 계곡, 캠핑을 한 번에 비교해보세요.",
    tag: "여름 추천",
    image: "https://images.unsplash.com/photo-1507525428034-b723cf961d3e?auto=format&fit=crop&w=1600&q=80",
    keyword: "바다"
  },
  {
    title: "가까운 산책길",
    subtitle: "혼잡도를 피해 걷기 좋은 코스를 찾습니다.",
    tag: "두루누비 후보",
    image: "https://images.unsplash.com/photo-1448375240586-882707db888b?auto=format&fit=crop&w=1600&q=80",
    keyword: "산책길"
  },
  {
    title: "조용한 캠핑 후보",
    subtitle: "고캠핑 키워드와 숨은 별점을 함께 봅니다.",
    tag: "캠핑",
    image: "https://images.unsplash.com/photo-1504280390367-361c6d9f38f4?auto=format&fit=crop&w=1600&q=80",
    keyword: "캠핑"
  }
];

const navItems = [
  { page: "category", path: "/category", label: "카테고리", icon: Compass },
  { page: "map", path: "/map", label: "지도", icon: MapIcon },
  { page: "home", path: "/", label: "홈", icon: Home },
  { page: "diary", path: "/diary", label: "다이어리", icon: CalendarDays },
  { page: "mypage", path: "/mypage", label: "마이", icon: User }
];

function pageFromPath(pathname) {
  if (pathname.includes("admin")) return "admin";
  if (pathname.includes("loginform")) return "login";
  if (pathname.includes("signupform")) return "signup";
  if (pathname.includes("category")) return "category";
  if (pathname.includes("map")) return "map";
  if (pathname.includes("diary")) return "diary";
  if (pathname.includes("notice")) return "notices";
  if (pathname.includes("mypage")) return "mypage";
  return "home";
}

async function api(path, options = {}) {
  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...(options.headers || {})
    }
  });
  if (!response.ok) {
    const text = await response.text();
    try {
      const json = JSON.parse(text);
      throw new Error(json.message || json.error || text);
    } catch {
      throw new Error(text || "요청에 실패했습니다.");
    }
  }
  return response.json();
}

function readStorage(key, fallback) {
  try {
    const value = localStorage.getItem(key);
    return value ? JSON.parse(value) : fallback;
  } catch {
    return fallback;
  }
}

function compactDate(value) {
  if (!value) return "";
  return String(value).slice(0, 10);
}

function stars(value) {
  const rating = Math.max(0, Math.min(5, Number(value || 0)));
  return "★".repeat(Math.round(rating)) + "☆".repeat(5 - Math.round(rating));
}

function getErrorMessage(error) {
  return String(error?.message || error || "처리 중 오류가 발생했습니다.").replace(/^.*"message":"?/, "").replace(/"}$/, "");
}

export default function App() {
  const [page, setPage] = useState(() => pageFromPath(window.location.pathname));
  const [user, setUser] = useState(() => readStorage(USER_STORAGE, null));
  const [darkMode, setDarkMode] = useState(() => localStorage.getItem("soomgilDarkMode") === "true");
  const [places, setPlaces] = useState([]);
  const [query, setQuery] = useState("");
  const [region, setRegion] = useState("");
  const [category, setCategory] = useState("");
  const [sort, setSort] = useState("recommended");
  const [suggestions, setSuggestions] = useState([]);
  const [selectedPlace, setSelectedPlace] = useState(null);
  const [reviews, setReviews] = useState([]);
  const [reviewText, setReviewText] = useState("");
  const [favorites, setFavorites] = useState([]);
  const [notifications, setNotifications] = useState([]);
  const [notices, setNotices] = useState([]);
  const [inquiries, setInquiries] = useState([]);
  const [submissions, setSubmissions] = useState([]);
  const [diaries, setDiaries] = useState([]);
  const [toast, setToast] = useState("");
  const [activeBanner, setActiveBanner] = useState(0);
  const [searchSheetOpen, setSearchSheetOpen] = useState(false);
  const [mapPopup, setMapPopup] = useState(null);
  const [detailTab, setDetailTab] = useState("analysis");
  const [profileEditorOpen, setProfileEditorOpen] = useState(false);
  const [diaryMode, setDiaryMode] = useState("list");
  const [selectedDiary, setSelectedDiary] = useState(null);
  const [adminTab, setAdminTab] = useState("notices");
  const searchInputRef = useRef(null);

  const isLoggedIn = Boolean(user?.userId);
  const unreadCount = notifications.filter((item) => !item.read).length;
  const todayPlaces = places.slice(0, 4);
  const acquiredBadges = useMemo(() => {
    const count = Math.min(badges.length, 1 + favorites.length + reviews.length + diaries.length);
    return badges.slice(0, count);
  }, [favorites.length, reviews.length, diaries.length]);
  const displayedBadges = useMemo(() => {
    const ids = user?.displayBadgeIds || [];
    return badges.filter((badge) => ids.includes(badge.id)).slice(0, 3);
  }, [user]);

  useEffect(() => {
    document.body.className = darkMode ? "theme-dark" : "";
    localStorage.setItem("soomgilDarkMode", String(darkMode));
  }, [darkMode]);

  useEffect(() => {
    const onPopState = () => setPage(pageFromPath(window.location.pathname));
    window.addEventListener("popstate", onPopState);
    return () => window.removeEventListener("popstate", onPopState);
  }, []);

  useEffect(() => {
    loadPlaces();
    loadNotices();
  }, []);

  useEffect(() => {
    if (isLoggedIn) {
      loadUserData(user.userId);
    }
  }, [isLoggedIn, user?.userId]);

  useEffect(() => {
    const timer = window.setInterval(() => {
      setActiveBanner((current) => (current + 1) % seasonBanners.length);
    }, 5000);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => {
    const timer = window.setTimeout(async () => {
      if (!query.trim()) {
        setSuggestions([]);
        return;
      }
      try {
        setSuggestions(await api(`/api/suggestions?q=${encodeURIComponent(query)}`));
      } catch {
        setSuggestions(["바다", "해변", "산책길", "캠핑", "계곡"].filter((item) => item.includes(query)));
      }
    }, 180);
    return () => window.clearTimeout(timer);
  }, [query]);

  function navigate(nextPage, path) {
    setPage(nextPage);
    window.history.pushState({}, "", path);
    window.scrollTo({ top: 0, behavior: "smooth" });
  }

  function showToast(message) {
    setToast(message);
    window.setTimeout(() => setToast(""), 3000);
  }

  async function loadPlaces(nextQuery = query, nextRegion = region, nextCategory = category, nextSort = sort) {
    const params = new URLSearchParams({
      query: nextQuery,
      region: nextRegion,
      category: nextCategory,
      sort: nextSort
    });
    try {
      setPlaces(await api(`/api/places?${params}`));
    } catch {
      setPlaces([]);
    }
  }

  async function loadNotices() {
    try {
      setNotices(await api("/api/notices"));
    } catch {
      setNotices([]);
    }
  }

  async function loadUserData(userId) {
    try {
      const [freshUser, favs, notes, userInquiries, diaryList] = await Promise.all([
        api(`/api/users/${userId}/settings`),
        api(`/api/users/${userId}/favorites`),
        api(`/api/users/${userId}/notifications`),
        api(`/api/users/${userId}/inquiries`),
        api(`/api/users/${userId}/diaries`)
      ]);
      setUser(freshUser);
      localStorage.setItem(USER_STORAGE, JSON.stringify(freshUser));
      setFavorites(favs);
      setNotifications(notes);
      setInquiries(userInquiries);
      setDiaries(diaryList);
    } catch (error) {
      console.warn(error);
    }
  }

  async function openPlace(place) {
    try {
      const detail = await api(`/api/places/${place.id}`);
      const placeReviews = await api(`/api/places/${place.id}/reviews`);
      setSelectedPlace(detail);
      setReviews(placeReviews);
      setDetailTab("analysis");
    } catch {
      setSelectedPlace(place);
      setReviews([]);
    }
  }

  async function submitSearch(keyword = query) {
    setQuery(keyword);
    setSearchSheetOpen(false);
    navigate("home", "/");
    await loadPlaces(keyword, region, category, sort);
    window.setTimeout(() => document.getElementById("search-results")?.scrollIntoView({ behavior: "smooth" }), 80);
  }

  async function applyFilter(next) {
    const nextRegion = next.region ?? region;
    const nextCategory = next.category ?? category;
    const nextSort = next.sort ?? sort;
    setRegion(nextRegion);
    setCategory(nextCategory);
    setSort(nextSort);
    await loadPlaces(query, nextRegion, nextCategory, nextSort);
  }

  async function toggleFavorite(place) {
    if (!isLoggedIn) {
      showToast("로그인하면 찜목록에 저장할 수 있습니다.");
      navigate("login", "/loginform");
      return;
    }
    const exists = favorites.some((item) => item.id === place.id);
    try {
      if (exists) {
        await api(`/api/users/${user.userId}/favorites/${place.id}`, { method: "DELETE" });
      } else {
        await api(`/api/users/${user.userId}/favorites`, {
          method: "POST",
          body: JSON.stringify({ placeId: place.id })
        });
      }
      await loadUserData(user.userId);
      showToast(exists ? "찜목록에서 삭제했습니다." : "찜목록에 저장했습니다.");
    } catch (error) {
      showToast(getErrorMessage(error));
    }
  }

  async function submitReview() {
    if (!selectedPlace) return;
    if (!isLoggedIn) {
      navigate("login", "/loginform");
      return;
    }
    try {
      await api(`/api/places/${selectedPlace.id}/reviews`, {
        method: "POST",
        body: JSON.stringify({ userId: user.userId, text: reviewText })
      });
      setReviewText("");
      setReviews(await api(`/api/places/${selectedPlace.id}/reviews`));
      await loadUserData(user.userId);
      showToast("후기를 등록했습니다.");
    } catch (error) {
      showToast(getErrorMessage(error));
    }
  }

  async function likeReview(review) {
    const userId = user?.userId || "guest";
    const result = await api(`/api/reviews/${review.id}/like`, {
      method: "POST",
      body: JSON.stringify({ userId })
    });
    setReviews((items) => items.map((item) => item.id === review.id ? { ...item, likeCount: result.likeCount } : item));
  }

  async function deleteReview(review) {
    if (!isLoggedIn) return;
    await api(`/api/reviews/${review.id}?userId=${encodeURIComponent(user.userId)}`, { method: "DELETE" });
    setReviews(await api(`/api/places/${selectedPlace.id}/reviews`));
  }

  async function markNotificationsRead() {
    if (!isLoggedIn) {
      navigate("notices", "/notices");
      return;
    }
    await api(`/api/users/${user.userId}/notifications/read`, { method: "PUT" });
    await loadUserData(user.userId);
    navigate("notices", "/notices");
  }

  function logout() {
    setUser(null);
    setFavorites([]);
    setNotifications([]);
    setInquiries([]);
    setDiaries([]);
    localStorage.removeItem(USER_STORAGE);
    localStorage.removeItem(AUTO_LOGIN_STORAGE);
    navigate("home", "/");
  }

  const layoutClass = [
    "app",
    page === "admin" ? "admin-app" : "",
    page === "diary" ? "diary-app" : "",
    page === "diary" || page === "admin" || page === "login" || page === "signup" ? "no-bottom-nav" : ""
  ].filter(Boolean).join(" ");

  return (
    <div className={layoutClass}>
      {toast && <div className="toast">{toast}</div>}
      {selectedPlace && renderDetailModal()}
      {profileEditorOpen && (
        <ProfileEditor
          user={user}
          acquiredBadges={acquiredBadges}
          onClose={() => setProfileEditorOpen(false)}
          onSaved={() => loadUserData(user.userId)}
          showToast={showToast}
        />
      )}
      {page !== "diary" && page !== "admin" && page !== "login" && page !== "signup" && renderHeader()}

      <main>
        {page === "home" && renderHome()}
        {page === "category" && renderCategory()}
        {page === "map" && renderMap()}
        {page === "mypage" && renderMyPage()}
        {page === "notices" && renderNotices()}
        {page === "inquiries" && <InquiriesPage user={user} isLoggedIn={isLoggedIn} inquiries={inquiries} navigate={navigate} loadUserData={loadUserData} />}
        {page === "followers" && <FollowListPage type="followers" user={user} isLoggedIn={isLoggedIn} navigate={navigate} />}
        {page === "following" && <FollowListPage type="following" user={user} isLoggedIn={isLoggedIn} navigate={navigate} />}
        {page === "diary" && <DiaryPage user={user} isLoggedIn={isLoggedIn} diaries={diaries} navigate={navigate} loadUserData={loadUserData} />}
        {page === "login" && <LoginPage navigate={navigate} setUser={setUser} showToast={showToast} />}
        {page === "signup" && <SignupPage navigate={navigate} setUser={setUser} showToast={showToast} />}
        {page === "admin" && <AdminPage adminTab={adminTab} setAdminTab={setAdminTab} showToast={showToast} loadNotices={loadNotices} />}
      </main>

      {page !== "diary" && page !== "admin" && page !== "login" && page !== "signup" && renderBottomNav()}
    </div>
  );

  function renderHeader() {
    return (
      <header className="app-header">
        <button className="brand" onClick={() => navigate("home", "/")}>
          <span className="brand-mark">S</span>
          <span>Soomgil</span>
        </button>
        <div className="header-actions">
          <button className="icon-button" onClick={() => setDarkMode((value) => !value)} aria-label="다크모드">
            {darkMode ? <Sun size={20} /> : <Moon size={20} />}
          </button>
          <button className="icon-button notification-button" onClick={markNotificationsRead} aria-label="알림">
            <Bell size={20} />
            {unreadCount > 0 && <span className="notification-dot" />}
          </button>
        </div>
      </header>
    );
  }

  function renderHome() {
    const banner = seasonBanners[activeBanner];
    return (
      <div className="page home-page">
        <section className="hero" style={{ backgroundImage: `linear-gradient(90deg, rgba(10,20,35,.76), rgba(10,20,35,.22)), url(${banner.image})` }}>
          <div>
            <span className="pill light">{banner.tag}</span>
            <h1>{banner.title}</h1>
            <p>{banner.subtitle}</p>
            <button className="primary-button" onClick={() => submitSearch(banner.keyword)}>
              <Search size={18} /> 바로 찾기
            </button>
          </div>
          <div className="banner-dots">
            {seasonBanners.map((item, index) => (
              <button
                key={item.title}
                className={activeBanner === index ? "active" : ""}
                onClick={() => setActiveBanner(index)}
                aria-label={`${index + 1}번 배너`}
              />
            ))}
          </div>
        </section>

        <section className="search-entry">
          <button className="search-bar" onClick={() => setSearchSheetOpen(true)}>
            <Search size={20} />
            <span>{query || "어디로 떠날까요? 바다, 계곡, 캠핑"}</span>
          </button>
        </section>

        {searchSheetOpen && (
          <div className="sheet-backdrop" onClick={() => setSearchSheetOpen(false)}>
            <div className="search-sheet" onClick={(event) => event.stopPropagation()}>
              <div className="sheet-head">
                <strong>검색</strong>
                <button className="icon-button" onClick={() => setSearchSheetOpen(false)}><X size={20} /></button>
              </div>
              <form onSubmit={(event) => { event.preventDefault(); submitSearch(); }}>
                <div className="input-with-icon">
                  <Search size={18} />
                  <input
                    ref={searchInputRef}
                    value={query}
                    onChange={(event) => setQuery(event.target.value)}
                    placeholder="검색어 입력"
                    autoFocus
                  />
                </div>
                <button className="primary-button full" type="submit">검색</button>
              </form>
              <div className="recent-searches">
                <h3>추천 검색어</h3>
                <div className="chip-row">
                  {(suggestions.length ? suggestions : ["바다", "산책길", "캠핑", "무장애", "반려동물"]).map((item) => (
                    <button key={item} className="chip" onClick={() => submitSearch(item)}>{item}</button>
                  ))}
                </div>
              </div>
            </div>
          </div>
        )}

        <section className="section">
          <div className="section-title">
            <div>
              <h2>빠른 탐색</h2>
              <p>큰 주제만 먼저 보고 세부 키워드는 카테고리에서 선택합니다.</p>
            </div>
            <button className="text-button" onClick={() => navigate("category", "/category")}>전체 보기</button>
          </div>
          <div className="quick-grid">
            {categories.slice(0, 6).map((item) => {
              const Icon = item.icon;
              return (
                <button key={item.id} className="quick-card" onClick={() => applyFilter({ category: item.query || item.id })}>
                  <Icon size={22} />
                  <strong>{item.label}</strong>
                  <span>{item.items.slice(0, 2).join(" · ")}</span>
                </button>
              );
            })}
          </div>
        </section>

        <section className="section">
          <div className="section-title">
            <div>
              <h2>오늘의 숨은 장소</h2>
              <p>전국 기준으로 혼잡도와 숨길 별점을 함께 봅니다.</p>
            </div>
          </div>
          <div className="place-grid compact">
            {todayPlaces.map((place) => renderPlaceCard(place))}
          </div>
        </section>

        {renderSearchResults()}
      </div>
    );
  }

  function renderSearchResults() {
    return (
      <section className="section" id="search-results">
        <div className="section-title">
          <div>
            <h2>추천 결과</h2>
            <p>{places.length}개의 장소를 찾았습니다.</p>
          </div>
        </div>
        <div className="filter-bar">
          {["", "서울", "강원", "경기", "부산", "제주", "인천"].map((item) => (
            <button key={item || "all"} className={region === item ? "chip active" : "chip"} onClick={() => applyFilter({ region: item })}>
              {item || "전국"}
            </button>
          ))}
          <select value={sort} onChange={(event) => applyFilter({ sort: event.target.value })}>
            <option value="recommended">추천순</option>
            <option value="hidden">숨길 별점순</option>
            <option value="crowd">혼잡도순</option>
            <option value="name">이름순</option>
          </select>
        </div>
        <div className="place-list">
          {places.map((place) => renderPlaceCard(place))}
          {places.length === 0 && <EmptyState title="검색 결과가 없습니다." body="지역이나 필터를 초기화한 뒤 다시 검색해보세요." />}
        </div>
      </section>
    );
  }

  function renderPlaceCard(place) {
    const isFavorite = favorites.some((item) => item.id === place.id);
    return (
      <article key={place.id} className="place-card" onClick={() => openPlace(place)}>
        <img src={place.image} alt="" />
        <div className="place-body">
          <div className="place-topline">
            <span className="pill">{place.categoryLabel || "장소"}</span>
            <button
              className={isFavorite ? "icon-button heart active" : "icon-button heart"}
              onClick={(event) => { event.stopPropagation(); toggleFavorite(place); }}
              aria-label="찜"
            >
              <Heart size={18} />
            </button>
          </div>
          <h3>{place.name}</h3>
          <p className="address"><MapPin size={15} /> {place.address}</p>
          <div className="meta-row">
            <span className="pill yellow rating-pill">
              <span>숨길 별점 {Number(place.hiddenRating || 0).toFixed(1)}</span>
              <span className="rating-stars">{stars(place.hiddenRating)}</span>
            </span>
            <span className="pill red">{place.crowd}</span>
          </div>
          <div className="sub-info">
            <span>{place.petInfo}</span>
            <span>{place.barrierFreeInfo}</span>
          </div>
        </div>
      </article>
    );
  }

  function renderDetailModal() {
    const place = selectedPlace;
    return (
      <div className="modal-backdrop" onClick={() => setSelectedPlace(null)}>
        <section className="detail-modal" onClick={(event) => event.stopPropagation()}>
          <button className="icon-button modal-close" onClick={() => setSelectedPlace(null)}><X size={22} /></button>
          <img className="detail-image" src={place.image} alt="" />
          <div className="detail-content">
            <div className="detail-heading">
              <div>
                <h2>{place.name}</h2>
                <p><MapPin size={16} /> {place.address}</p>
              </div>
              <button className="secondary-button" onClick={() => toggleFavorite(place)}>
                <Heart size={18} /> 찜
              </button>
            </div>
            <div className="meta-row big">
              <span className="pill yellow">숨길 별점 {Number(place.hiddenRating || 0).toFixed(1)}</span>
              <span className="pill red">{place.crowd}</span>
              <span className="pill blue">{place.petInfo}</span>
              <span className="pill green">{place.barrierFreeInfo}</span>
            </div>
            <div className="tabs">
              {["analysis", "reviews", "nearby"].map((tab) => (
                <button key={tab} className={detailTab === tab ? "active" : ""} onClick={() => setDetailTab(tab)}>
                  {tab === "analysis" ? "추천분석" : tab === "reviews" ? "후기" : "주변 장소"}
                </button>
              ))}
            </div>
            {detailTab === "analysis" && (
              <div className="detail-panel">
                <h3>추천 이유</h3>
                <p>{place.recommendationReason || "혼잡도와 장소 특성을 기준으로 추천한 장소입니다."}</p>
                <h3>반나절 코스</h3>
                <div className="course-list">
                  {(place.halfDayCourse || []).map((step) => (
                    <div key={step.step} className="course-item">
                      <strong>{step.step}</strong>
                      <span>{step.title}</span>
                      <p>{step.description}</p>
                    </div>
                  ))}
                </div>
              </div>
            )}
            {detailTab === "reviews" && (
              <div className="detail-panel">
                <div className="review-form">
                  <textarea value={reviewText} onChange={(event) => setReviewText(event.target.value)} placeholder="후기만 간단히 남겨주세요." />
                  <button className="primary-button" onClick={submitReview}><Send size={17} /> 등록</button>
                </div>
                <div className="review-list">
                  {reviews.map((review) => (
                    <div key={review.id} className="review-card">
                      <div>
                        <strong>익명</strong>
                        <span>{compactDate(review.createdAt)}</span>
                      </div>
                      <p>{review.text}</p>
                      <div className="review-actions">
                        <button className="text-button" onClick={() => likeReview(review)}>좋아요 {review.likeCount}</button>
                        {review.ownerId === user?.userId && <button className="text-button danger" onClick={() => deleteReview(review)}>삭제</button>}
                      </div>
                    </div>
                  ))}
                  {reviews.length === 0 && <EmptyState title="아직 후기가 없습니다." body="첫 후기를 남겨보세요." />}
                </div>
              </div>
            )}
            {detailTab === "nearby" && (
              <div className="detail-panel">
                <h3>주변 숨은 장소 2곳</h3>
                <div className="mini-place-list">
                  {(place.nearbyHiddenPlaces || []).map((nearby) => (
                    <button key={nearby.id} onClick={() => openPlace(nearby)}>
                      <strong>{nearby.name}</strong>
                      <span>{nearby.address}</span>
                    </button>
                  ))}
                </div>
              </div>
            )}
          </div>
        </section>
      </div>
    );
  }

  function renderCategory() {
    const selectedGroup = categories.find((item) => {
      return category === item.id || category === item.query || item.items.includes(category);
    }) || categories[0];
    const group = selectedGroup;
    const setSelectedGroup = (item) => applyFilter({ category: item.query || item.id });
    return (
      <div className="page">
        <PageTitle title="카테고리 탐색" subtitle="대주제를 먼저 고르고 세부 키워드를 선택합니다." />
        <div className="category-grid">
          {categories.map((item) => {
            const Icon = item.icon;
            return (
              <button key={item.id} className={group.id === item.id ? "category-card active" : "category-card"} onClick={() => setSelectedGroup(item)}>
                <Icon size={26} />
                <div>
                  <strong>{item.label}</strong>
                  <p>{item.items.slice(0, 3).join(" · ")}</p>
                </div>
              </button>
            );
          })}
        </div>
        <section className="section card-section">
          <h2>{group.label} 세부 키워드</h2>
          <p className="category-description">{group.description}</p>
          <div className="chip-row">
            {group.items.map((item) => (
              <button key={item} className="chip" onClick={() => submitSearch(item)}>#{item}</button>
            ))}
          </div>
        </section>
      </div>
    );
  }

  function renderMap() {
    const visiblePlaces = category
      ? places.filter((item) => {
          const text = `${item.category || ""} ${item.categoryLabel || ""} ${item.tags || ""}`;
          return text.includes(category) || text.includes((categories.find((group) => group.query === category)?.label) || "");
        })
      : places;
    return (
      <div className="page">
        <PageTitle title="지도 탐색" subtitle="범례를 누르면 관련 장소 근처로 이동한 것처럼 결과를 좁혀 보여줍니다." />
        <div className="legend">
          {categories.map((item) => {
            const Icon = item.icon;
            return (
              <button key={item.id} className={category === item.id || category === item.query ? "chip active" : "chip"} onClick={() => applyFilter({ category: category === item.query ? "" : item.query || item.id })}>
                <Icon size={15} /> {item.label}
              </button>
            );
          })}
          <button className="chip" onClick={() => applyFilter({ category: "", region: "" })}>전체</button>
        </div>
        <section className="map-board">
          <div className="map-canvas">
            {visiblePlaces.slice(0, 12).map((place, index) => (
              <button
                key={place.id}
                className="map-marker"
                style={{ left: `${10 + (index * 23) % 74}%`, top: `${18 + (index * 17) % 62}%` }}
                onClick={() => setMapPopup(place)}
              >
                <MapPin size={20} />
              </button>
            ))}
            {mapPopup && (
              <div className="map-popup">
                <button className="icon-button modal-close" onClick={() => setMapPopup(null)}><X size={18} /></button>
                <img src={mapPopup.image} alt="" />
                <h3>{mapPopup.name}</h3>
                <p>{mapPopup.address}</p>
                <div className="meta-row">
                  <span className="pill yellow">별점 {mapPopup.hiddenRating}</span>
                  <span className="pill red">{mapPopup.crowd}</span>
                </div>
                <div className="sub-info">
                  <span>{mapPopup.petInfo}</span>
                  <span>{mapPopup.barrierFreeInfo}</span>
                </div>
                <button className="primary-button full" onClick={() => { openPlace(mapPopup); setMapPopup(null); }}>자세히 보기</button>
              </div>
            )}
          </div>
          <div className="map-side-list">
            {visiblePlaces.slice(0, 6).map((place) => (
              <button key={place.id} onClick={() => setMapPopup(place)}>
                <strong>{place.name}</strong>
                <span>{place.region} · {place.crowd}</span>
              </button>
            ))}
          </div>
        </section>
      </div>
    );
  }

  function renderMyPage() {
    if (!isLoggedIn) {
      return (
        <div className="page">
          <section className="guest-card">
            <h1>로그인하고 숨은 여행지를 저장하세요</h1>
            <p>찜목록, 여행 다이어리, 팔로우 기반 추천은 로그인 후 사용할 수 있습니다.</p>
            <div className="button-row">
              <button className="primary-button" onClick={() => navigate("login", "/loginform")}><LogIn size={18} /> 로그인</button>
              <button className="secondary-button" onClick={() => navigate("signup", "/signupform")}><UserPlus size={18} /> 회원가입</button>
            </div>
          </section>
          <section className="list-section">
            <button onClick={() => setDarkMode((value) => !value)}><Moon size={20} /> 다크모드 <span>{darkMode ? "켜짐" : "꺼짐"}</span></button>
            <button onClick={() => navigate("notices", "/notices")}><Bell size={20} /> 공지사항</button>
            <button><Settings size={20} /> 언어 설정 <span>한국어</span></button>
            <button><Shield size={20} /> 앱 정보 <span>rebuild 0.1</span></button>
          </section>
        </div>
      );
    }

    return (
      <div className="page">
        <section className="profile-card">
          <div className="profile-main">
            <div className="avatar" style={{ borderColor: user.profileBorder }}>
              {String(user.profileImage || "").startsWith("data:") || String(user.profileImage || "").startsWith("http")
                ? <img src={user.profileImage} alt="" />
                : <span>{user.profileImage || "👤"}</span>}
            </div>
            <div>
              <h1>{user.nickname || user.username}</h1>
              <p>{user.email}</p>
              <div className="badge-row">
                {displayedBadges.length === 0 ? <span className="pill">전시 배지 없음</span> : displayedBadges.map((badge) => <span key={badge.id} className="pill blue">{badge.label}</span>)}
              </div>
            </div>
          </div>
          <div className="profile-stats">
            <button onClick={() => navigate("followers", "/mypage/followers")}><strong>{user.stats?.followers || 0}</strong><span>팔로워</span></button>
            <button onClick={() => navigate("following", "/mypage/following")}><strong>{user.stats?.following || 0}</strong><span>팔로잉</span></button>
            <button><strong>{favorites.length}</strong><span>찜</span></button>
          </div>
          <button className="secondary-button full" onClick={() => setProfileEditorOpen(true)}><Edit3 size={18} /> 프로필 수정</button>
        </section>

        <section className="list-section">
          <h2>나의 거래/활동</h2>
          <button onClick={() => window.scrollTo({ top: document.body.scrollHeight, behavior: "smooth" })}><Heart size={20} /> 찜한 장소 <span>{favorites.length}</span></button>
          <button onClick={() => navigate("diary", "/diary")}><CalendarDays size={20} /> 여행 다이어리 <span>{diaries.length}</span></button>
          <button onClick={() => navigate("inquiries", "/mypage/inquiries")}><MessageSquare size={20} /> 문의하기 <span>{inquiries.length}</span></button>
          <button onClick={() => navigate("notices", "/notices")}><Bell size={20} /> 공지사항</button>
          <button onClick={logout}><LogIn size={20} /> 로그아웃</button>
        </section>

        <section className="section">
          <h2>찜한 장소</h2>
          <div className="place-grid compact">{favorites.map((place) => renderPlaceCard(place))}</div>
        </section>
      </div>
    );
  }

  function renderProfileEditor() {
    const [draft, setDraft] = useState({
      nickname: user?.nickname || "",
      profileImage: user?.profileImage || "👤",
      profileBorder: user?.profileBorder || "#6d5dfc",
      displayBadgeIds: user?.displayBadgeIds || [],
      zoom: 1,
      rotate: 0
    });

    async function saveProfile() {
      try {
        await api(`/api/users/${user.userId}/settings`, {
          method: "PUT",
          body: JSON.stringify({
            nickname: draft.nickname,
            profileImage: draft.profileImage,
            profileBorder: draft.profileBorder,
            displayBadgeIdsJson: JSON.stringify(draft.displayBadgeIds.slice(0, 3))
          })
        });
        await loadUserData(user.userId);
        setProfileEditorOpen(false);
        showToast("프로필을 저장했습니다.");
      } catch (error) {
        showToast(getErrorMessage(error));
      }
    }

    function handleFile(event) {
      const file = event.target.files?.[0];
      if (!file) return;
      const reader = new FileReader();
      reader.onload = () => setDraft((value) => ({ ...value, profileImage: String(reader.result) }));
      reader.readAsDataURL(file);
    }

    return (
      <div className="modal-backdrop" onClick={() => setProfileEditorOpen(false)}>
        <section className="profile-editor" onClick={(event) => event.stopPropagation()}>
          <div className="sheet-head">
            <strong>프로필 수정</strong>
            <button className="icon-button" onClick={() => setProfileEditorOpen(false)}><X size={20} /></button>
          </div>
          <label>닉네임<input value={draft.nickname} onChange={(event) => setDraft({ ...draft, nickname: event.target.value })} /></label>
          <div className="profile-preview">
            <div className="avatar large" style={{ borderColor: draft.profileBorder }}>
              {String(draft.profileImage).startsWith("data:") || String(draft.profileImage).startsWith("http")
                ? <img src={draft.profileImage} alt="" style={{ transform: `scale(${draft.zoom}) rotate(${draft.rotate}deg)` }} />
                : <span>{draft.profileImage}</span>}
            </div>
            <label className="secondary-button">
              <Camera size={18} /> 사진 변경
              <input hidden type="file" accept="image/*" onChange={handleFile} />
            </label>
          </div>
          <label><ZoomIn size={16} /> 확대<input type="range" min="1" max="2" step="0.05" value={draft.zoom} onChange={(event) => setDraft({ ...draft, zoom: event.target.value })} /></label>
          <label><RotateCw size={16} /> 회전<input type="range" min="-180" max="180" value={draft.rotate} onChange={(event) => setDraft({ ...draft, rotate: event.target.value })} /></label>
          <div>
            <strong>테두리</strong>
            <div className="color-row">
              {["#6d5dfc", "#0ea5e9", "#16a34a", "#f97316", "#111827"].map((color) => (
                <button key={color} className="color-dot" style={{ background: color }} onClick={() => setDraft({ ...draft, profileBorder: color })} />
              ))}
            </div>
          </div>
          <div>
            <strong>전시 배지 0~3개</strong>
            <div className="badge-edit-grid">
              {acquiredBadges.map((badge) => {
                const selected = draft.displayBadgeIds.includes(badge.id);
                return (
                  <button
                    key={badge.id}
                    className={selected ? "badge-card active" : "badge-card"}
                    onClick={() => {
                      const next = selected
                        ? draft.displayBadgeIds.filter((id) => id !== badge.id)
                        : [...draft.displayBadgeIds, badge.id].slice(0, 3);
                      setDraft({ ...draft, displayBadgeIds: next });
                    }}
                  >
                    <span style={{ background: badge.color }}>{badge.grade}</span>
                    <strong>{badge.label}</strong>
                  </button>
                );
              })}
            </div>
          </div>
          <button className="primary-button full" onClick={saveProfile}>저장</button>
        </section>
      </div>
    );
  }

  function renderLogin() {
    const [form, setForm] = useState({
      identifier: localStorage.getItem(SAVED_ID_STORAGE) || "",
      password: "",
      rememberId: Boolean(localStorage.getItem(SAVED_ID_STORAGE)),
      autoLogin: localStorage.getItem(AUTO_LOGIN_STORAGE) === "true"
    });

    async function submit(event) {
      event.preventDefault();
      try {
        const result = await api("/api/auth/login", {
          method: "POST",
          body: JSON.stringify(form)
        });
        setUser(result.user);
        localStorage.setItem(USER_STORAGE, JSON.stringify(result.user));
        if (form.rememberId) localStorage.setItem(SAVED_ID_STORAGE, form.identifier);
        else localStorage.removeItem(SAVED_ID_STORAGE);
        localStorage.setItem(AUTO_LOGIN_STORAGE, String(form.autoLogin));
        navigate("mypage", "/mypage");
      } catch (error) {
        showToast(getErrorMessage(error));
      }
    }

    return (
      <AuthShell title="로그인" subtitle="아이디 또는 이메일로 로그인합니다.">
        <form className="auth-form" onSubmit={submit}>
          <input value={form.identifier} onChange={(event) => setForm({ ...form, identifier: event.target.value })} placeholder="아이디 또는 이메일" />
          <input type="password" value={form.password} onChange={(event) => setForm({ ...form, password: event.target.value })} placeholder="비밀번호" />
          <label className="check-label"><input type="checkbox" checked={form.rememberId} onChange={(event) => setForm({ ...form, rememberId: event.target.checked })} /> 아이디 저장</label>
          <label className="check-label"><input type="checkbox" checked={form.autoLogin} onChange={(event) => setForm({ ...form, autoLogin: event.target.checked })} /> 자동 로그인</label>
          <button className="primary-button full" type="submit">로그인</button>
          <button className="secondary-button full" type="button" onClick={async () => {
            const email = prompt("구글 이메일을 입력하세요.");
            if (!email) return;
            const result = await api("/api/auth/google", { method: "POST", body: JSON.stringify({ email, fullName: email.split("@")[0] }) });
            setUser(result.user);
            localStorage.setItem(USER_STORAGE, JSON.stringify(result.user));
            navigate("mypage", "/mypage");
          }}>Google로 계속하기</button>
        </form>
        <div className="auth-links">
          <button onClick={() => navigate("signup", "/signupform")}>회원가입</button>
          <button onClick={() => alert("아이디/비밀번호 찾기는 API 복구 완료. 화면은 다음 단계에서 세분화합니다.")}>아이디/비밀번호 찾기</button>
        </div>
      </AuthShell>
    );
  }

  function renderSignup() {
    const [form, setForm] = useState({
      fullName: "",
      username: "",
      password: "",
      passwordConfirm: "",
      email: "",
      phone: "",
      verificationCode: "",
      privacyConsent: false
    });

    async function submit(event) {
      event.preventDefault();
      if (form.password !== form.passwordConfirm) {
        showToast("비밀번호 확인이 일치하지 않습니다.");
        return;
      }
      try {
        const result = await api("/api/auth/signup", {
          method: "POST",
          body: JSON.stringify(form)
        });
        setUser(result.user);
        localStorage.setItem(USER_STORAGE, JSON.stringify(result.user));
        navigate("mypage", "/mypage");
      } catch (error) {
        showToast(getErrorMessage(error));
      }
    }

    return (
      <AuthShell title="회원가입" subtitle="가입된 전화번호나 이메일은 중복 가입으로 처리됩니다.">
        <form className="auth-form" onSubmit={submit}>
          <input value={form.fullName} onChange={(event) => setForm({ ...form, fullName: event.target.value })} placeholder="성명" />
          <input value={form.username} onChange={(event) => setForm({ ...form, username: event.target.value })} placeholder="아이디: 영문 소문자 시작 4~20자" />
          <input type="password" value={form.password} onChange={(event) => setForm({ ...form, password: event.target.value })} placeholder="비밀번호: 영문+숫자+특수문자 8자 이상" />
          <input type="password" value={form.passwordConfirm} onChange={(event) => setForm({ ...form, passwordConfirm: event.target.value })} placeholder="비밀번호 한번 더" />
          <input value={form.email} onChange={(event) => setForm({ ...form, email: event.target.value })} placeholder="이메일" />
          <input value={form.phone} onChange={(event) => setForm({ ...form, phone: event.target.value })} placeholder="전화번호" />
          <input value={form.verificationCode} onChange={(event) => setForm({ ...form, verificationCode: event.target.value })} placeholder="인증번호" />
          <label className="check-label"><input type="checkbox" checked={form.privacyConsent} onChange={(event) => setForm({ ...form, privacyConsent: event.target.checked })} /> 개인정보보호 동의</label>
          <button className="primary-button full" type="submit">가입하기</button>
        </form>
        <div className="auth-links">
          <button onClick={() => navigate("login", "/loginform")}>이미 계정이 있어요</button>
        </div>
      </AuthShell>
    );
  }

  function renderNotices() {
    return (
      <div className="page narrow">
        <BackButton label="마이페이지로 돌아가기" onClick={() => navigate("mypage", "/mypage")} />
        <PageTitle title="공지사항" subtitle="제목을 누르면 자세한 내용을 볼 수 있습니다." />
        <div className="board-list">
          {notices.map((notice) => (
            <details key={notice.id} className="board-item">
              <summary>
                <strong>{notice.title}</strong>
                <span>{compactDate(notice.createdAt)}</span>
              </summary>
              {notice.imageUrl && <img src={notice.imageUrl} alt="" />}
              <p className={`${notice.bodySize === "large" ? "large-text" : ""} ${notice.bodyWeight === "bold" ? "bold-text" : ""}`}>{notice.body}</p>
            </details>
          ))}
        </div>
      </div>
    );
  }

  function renderInquiries() {
    const [form, setForm] = useState({ title: "", body: "" });
    if (!isLoggedIn) return <AuthRequired />;
    async function submit(event) {
      event.preventDefault();
      await api(`/api/users/${user.userId}/inquiries`, { method: "POST", body: JSON.stringify(form) });
      setForm({ title: "", body: "" });
      await loadUserData(user.userId);
    }
    return (
      <div className="page narrow">
        <BackButton label="마이페이지로 돌아가기" onClick={() => navigate("mypage", "/mypage")} />
        <PageTitle title="문의하기" subtitle="작성한 문의는 관리자 페이지로 전달됩니다." />
        <form className="card-form" onSubmit={submit}>
          <input value={form.title} onChange={(event) => setForm({ ...form, title: event.target.value })} placeholder="제목" />
          <textarea value={form.body} onChange={(event) => setForm({ ...form, body: event.target.value })} placeholder="문의 내용" />
          <button className="primary-button" type="submit"><Send size={17} /> 전송</button>
        </form>
        <div className="board-list">
          {inquiries.map((item) => (
            <div key={item.id} className="board-item">
              <div className="board-row"><strong>{item.title}</strong><span>{item.status}</span></div>
              <p>{item.body}</p>
              {item.adminReply && <p className="reply">답변: {item.adminReply}</p>}
              <button className="text-button danger" onClick={async () => { await api(`/api/users/${user.userId}/inquiries/${item.id}`, { method: "DELETE" }); loadUserData(user.userId); }}>삭제</button>
            </div>
          ))}
        </div>
      </div>
    );
  }

  function renderFollowList(type) {
    const [people, setPeople] = useState([]);
    useEffect(() => {
      if (!isLoggedIn) return;
      api(`/api/users/${user.userId}/${type}`).then(setPeople).catch(() => setPeople([]));
    }, [type, user?.userId]);
    return (
      <div className="page narrow">
        <BackButton label="마이페이지로 돌아가기" onClick={() => navigate("mypage", "/mypage")} />
        <PageTitle title={type === "followers" ? "팔로워" : "팔로잉"} subtitle={type === "followers" ? "나를 팔로우한 사용자" : "내가 팔로우한 사용자"} />
        <div className="people-list">
          {people.map((person) => (
            <div key={person.userId} className="person-card">
              <div className="avatar small" style={{ borderColor: person.profileBorder }}>{person.profileImage}</div>
              <div><strong>{person.nickname}</strong><span>@{person.username}</span></div>
            </div>
          ))}
          {people.length === 0 && <EmptyState title="아직 목록이 없습니다." body="test01 계정으로 팔로우 테스트를 진행할 수 있습니다." />}
        </div>
      </div>
    );
  }

  function renderDiary() {
    if (!isLoggedIn) return <AuthShell title="여행 다이어리" subtitle="로그인 후 여행 기록을 남길 수 있습니다."><button className="primary-button full" onClick={() => navigate("login", "/loginform")}>로그인하기</button></AuthShell>;
    if (diaryMode === "editor") return renderDiaryEditor();
    return (
      <div className="diary-page">
        <div className="diary-top">
          <BackButton label="마이페이지로 돌아가기" onClick={() => navigate("mypage", "/mypage")} />
          <button className="icon-button"><Search size={20} /></button>
        </div>
        <PageTitle title="여행 다이어리" subtitle="기록한 여행을 날짜별로 모아봅니다." />
        <div className="diary-tabs">
          {["list", "calendar", "settings"].map((mode) => (
            <button key={mode} className={diaryMode === mode ? "active" : ""} onClick={() => setDiaryMode(mode)}>
              {mode === "list" ? "기록한 여행" : mode === "calendar" ? "달력" : "설정"}
            </button>
          ))}
        </div>
        {diaryMode === "list" && (
          <div className="diary-list">
            {diaries.map((entry) => (
              <button key={entry.id} className="diary-card" onClick={() => { setSelectedDiary(entry); setDiaryMode("editor"); }}>
                <span>{compactDate(entry.visitedDate)}</span>
                <strong>{entry.title}</strong>
                <p>{entry.body}</p>
              </button>
            ))}
          </div>
        )}
        {diaryMode === "calendar" && (
          <div className="calendar-card">
            {Array.from({ length: 31 }, (_, index) => index + 1).map((day) => {
              const entry = diaries.find((item) => Number(String(item.visitedDate).slice(-2)) === day);
              return <button key={day} className={entry ? "has-entry" : ""}>{day}</button>;
            })}
          </div>
        )}
        {diaryMode === "settings" && <EmptyState title="설정은 다음 단계에서 확장합니다." body="현재는 기록 작성과 달력 표시를 우선 복구했습니다." />}
        <button className="floating-write" onClick={() => { setSelectedDiary(null); setDiaryMode("editor"); }}><Plus size={26} /></button>
      </div>
    );
  }

  function renderDiaryEditor() {
    const [form, setForm] = useState(selectedDiary || {
      title: "",
      body: "",
      placeName: "",
      visitedDate: new Date().toISOString().slice(0, 10),
      weatherIcon: "sun",
      pageColor: "#ffffff",
      fontFamily: "Pretendard",
      imageUrl: ""
    });
    async function save() {
      const method = selectedDiary ? "PUT" : "POST";
      const path = selectedDiary ? `/api/users/${user.userId}/diaries/${selectedDiary.id}` : `/api/users/${user.userId}/diaries`;
      await api(path, { method, body: JSON.stringify(form) });
      await loadUserData(user.userId);
      setDiaryMode("list");
    }
    function handleImage(event) {
      const file = event.target.files?.[0];
      if (!file) return;
      const reader = new FileReader();
      reader.onload = () => setForm({ ...form, imageUrl: String(reader.result) });
      reader.readAsDataURL(file);
    }
    return (
      <div className="diary-editor" style={{ background: form.pageColor }}>
        <div className="editor-top">
          <button className="icon-button" onClick={() => setDiaryMode("list")}><ChevronLeft size={24} /></button>
          <div className="button-row">
            {selectedDiary && <button className="icon-button" onClick={async () => { await api(`/api/users/${user.userId}/diaries/${selectedDiary.id}`, { method: "DELETE" }); await loadUserData(user.userId); setDiaryMode("list"); }}><Trash2 size={20} /></button>}
            <button className="primary-button" onClick={save}>저장</button>
          </div>
        </div>
        <div className="editor-date">
          <input type="date" value={compactDate(form.visitedDate)} onChange={(event) => setForm({ ...form, visitedDate: event.target.value })} />
          <select value={form.weatherIcon} onChange={(event) => setForm({ ...form, weatherIcon: event.target.value })}>
            <option value="sun">맑음</option>
            <option value="cloud">흐림</option>
            <option value="rain">비</option>
            <option value="snow">눈</option>
          </select>
        </div>
        {form.imageUrl && <img className="diary-photo" src={form.imageUrl} alt="" />}
        <input className="diary-title-input" value={form.title} onChange={(event) => setForm({ ...form, title: event.target.value })} placeholder="제목" />
        <textarea className="diary-body-input" value={form.body} onChange={(event) => setForm({ ...form, body: event.target.value })} placeholder="오늘의 여행을 기록하세요." />
        <nav className="editor-toolbar">
          <label><ImagePlus size={18} /><input hidden type="file" accept="image/*" onChange={handleImage} /></label>
          <label><Palette size={18} /><input hidden type="color" value={form.pageColor} onChange={(event) => setForm({ ...form, pageColor: event.target.value })} /></label>
          <button><PenLine size={18} /></button>
          <button><Star size={18} /></button>
        </nav>
      </div>
    );
  }

  function renderAdmin() {
    const [adminCode, setAdminCode] = useState(localStorage.getItem(ADMIN_CODE_STORAGE) || "");
    const [adminNotices, setAdminNotices] = useState([]);
    const [adminInquiries, setAdminInquiries] = useState([]);
    const [adminSubmissions, setAdminSubmissions] = useState([]);
    const [noticeForm, setNoticeForm] = useState({ title: "", body: "", imageUrl: "", bodySize: "normal", bodyWeight: "normal" });

    async function loadAdminData() {
      localStorage.setItem(ADMIN_CODE_STORAGE, adminCode);
      try {
        const [noticeList, inquiryList, submissionList] = await Promise.all([
          api("/api/notices"),
          api(`/api/admin/inquiries?adminCode=${encodeURIComponent(adminCode)}`),
          api(`/api/admin/place-submissions?adminCode=${encodeURIComponent(adminCode)}`)
        ]);
        setAdminNotices(noticeList);
        setAdminInquiries(inquiryList);
        setAdminSubmissions(submissionList);
      } catch (error) {
        showToast(getErrorMessage(error));
      }
    }

    async function createNotice(event) {
      event.preventDefault();
      await api(`/api/admin/notices?adminCode=${encodeURIComponent(adminCode)}`, { method: "POST", body: JSON.stringify(noticeForm) });
      setNoticeForm({ title: "", body: "", imageUrl: "", bodySize: "normal", bodyWeight: "normal" });
      await loadAdminData();
      await loadNotices();
    }

    return (
      <div className="admin-page">
        <div className="admin-shell">
          <aside>
            <h1>숨길 관리자</h1>
            <div className="admin-code">
              <input value={adminCode} onChange={(event) => setAdminCode(event.target.value)} placeholder="관리자 코드" />
              <button className="primary-button full" onClick={loadAdminData}>접속</button>
            </div>
            {["notices", "submissions", "inquiries"].map((tab) => (
              <button key={tab} className={adminTab === tab ? "admin-tab active" : "admin-tab"} onClick={() => setAdminTab(tab)}>
                {tab === "notices" ? "공지사항" : tab === "submissions" ? "장소 제보" : "사용자 문의"}
              </button>
            ))}
          </aside>
          <section>
            {adminTab === "notices" && (
              <>
                <h2>공지사항 작성</h2>
                <form className="notice-editor" onSubmit={createNotice}>
                  <div className="editor-menu">
                    <button type="button"><ImagePlus size={16} /> 이미지</button>
                    <button type="button"><Edit3 size={16} /> 외부콘텐츠</button>
                    <button type="button" onClick={() => setNoticeForm({ ...noticeForm, bodyWeight: noticeForm.bodyWeight === "bold" ? "normal" : "bold" })}>굵게</button>
                    <select value={noticeForm.bodySize} onChange={(event) => setNoticeForm({ ...noticeForm, bodySize: event.target.value })}>
                      <option value="normal">보통</option>
                      <option value="large">큰 글씨</option>
                    </select>
                  </div>
                  <input value={noticeForm.title} onChange={(event) => setNoticeForm({ ...noticeForm, title: event.target.value })} placeholder="제목" />
                  <input value={noticeForm.imageUrl} onChange={(event) => setNoticeForm({ ...noticeForm, imageUrl: event.target.value })} placeholder="이미지 URL" />
                  <textarea value={noticeForm.body} onChange={(event) => setNoticeForm({ ...noticeForm, body: event.target.value })} placeholder="내용" />
                  <div className="button-row end">
                    <button className="secondary-button" type="button">임시저장</button>
                    <button className="primary-button" type="submit">등록</button>
                  </div>
                </form>
                <div className="board-list">{adminNotices.map((notice) => <AdminNotice key={notice.id} notice={notice} adminCode={adminCode} reload={loadAdminData} />)}</div>
              </>
            )}
            {adminTab === "submissions" && (
              <div className="board-list">
                {adminSubmissions.map((item) => (
                  <div key={item.id} className="board-item">
                    <div className="board-row"><strong>{item.name}</strong><span>{item.status}</span></div>
                    <p>{item.address}</p>
                    <p>{item.description}</p>
                    <div className="button-row">
                      <button className="secondary-button" onClick={async () => { await api(`/api/admin/place-submissions/${item.id}?adminCode=${encodeURIComponent(adminCode)}`, { method: "PUT", body: JSON.stringify({ status: "승인" }) }); loadAdminData(); }}><CheckCircle2 size={17} /> 승인</button>
                      <button className="secondary-button danger" onClick={async () => { await api(`/api/admin/place-submissions/${item.id}?adminCode=${encodeURIComponent(adminCode)}`, { method: "PUT", body: JSON.stringify({ status: "반려" }) }); loadAdminData(); }}><XCircle size={17} /> 반려</button>
                    </div>
                  </div>
                ))}
              </div>
            )}
            {adminTab === "inquiries" && (
              <div className="board-list">
                {adminInquiries.map((item) => <AdminInquiry key={item.id} item={item} adminCode={adminCode} reload={loadAdminData} />)}
              </div>
            )}
          </section>
        </div>
      </div>
    );
  }

  function renderBottomNav() {
    return (
      <nav className="bottom-nav">
        {navItems.map((item) => {
          const Icon = item.icon;
          const className = [
            "nav-button",
            item.page === "home" ? "home-nav" : "",
            page === item.page ? "active" : ""
          ].filter(Boolean).join(" ");
          return (
            <button key={item.page} className={className} onClick={() => navigate(item.page, item.path)}>
              <Icon size={20} />
              <span>{item.label}</span>
            </button>
          );
        })}
      </nav>
    );
  }
}

function AdminNotice({ notice, adminCode, reload }) {
  const [editing, setEditing] = useState(false);
  const [form, setForm] = useState(notice);
  if (editing) {
    return (
      <form className="board-item" onSubmit={async (event) => {
        event.preventDefault();
        await api(`/api/admin/notices/${notice.id}?adminCode=${encodeURIComponent(adminCode)}`, { method: "PUT", body: JSON.stringify(form) });
        setEditing(false);
        reload();
      }}>
        <input value={form.title} onChange={(event) => setForm({ ...form, title: event.target.value })} />
        <textarea value={form.body} onChange={(event) => setForm({ ...form, body: event.target.value })} />
        <button className="primary-button">수정 완료</button>
      </form>
    );
  }
  return (
    <div className="board-item">
      <div className="board-row"><strong>{notice.title}</strong><span>{compactDate(notice.createdAt)}</span></div>
      <p>{notice.body}</p>
      <div className="button-row">
        <button className="text-button" onClick={() => setEditing(true)}>수정</button>
        <button className="text-button danger" onClick={async () => { await api(`/api/admin/notices/${notice.id}?adminCode=${encodeURIComponent(adminCode)}`, { method: "DELETE" }); reload(); }}>삭제</button>
      </div>
    </div>
  );
}

function ProfileEditor({ user, acquiredBadges, onClose, onSaved, showToast }) {
  const [draft, setDraft] = useState({
    nickname: user?.nickname || "",
    profileImage: user?.profileImage || "👤",
    profileBorder: user?.profileBorder || "#6d5dfc",
    displayBadgeIds: user?.displayBadgeIds || [],
    zoom: 1,
    rotate: 0
  });

  function handleFile(event) {
    const file = event.target.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => setDraft((value) => ({ ...value, profileImage: String(reader.result) }));
    reader.readAsDataURL(file);
  }

  async function saveProfile() {
    try {
      await api(`/api/users/${user.userId}/settings`, {
        method: "PUT",
        body: JSON.stringify({
          nickname: draft.nickname,
          profileImage: draft.profileImage,
          profileBorder: draft.profileBorder,
          displayBadgeIdsJson: JSON.stringify(draft.displayBadgeIds.slice(0, 3))
        })
      });
      await onSaved();
      onClose();
      showToast("프로필을 저장했습니다.");
    } catch (error) {
      showToast(getErrorMessage(error));
    }
  }

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <section className="profile-editor" onClick={(event) => event.stopPropagation()}>
        <div className="sheet-head">
          <strong>프로필 수정</strong>
          <button className="icon-button" onClick={onClose}><X size={20} /></button>
        </div>
        <label>닉네임<input value={draft.nickname} onChange={(event) => setDraft({ ...draft, nickname: event.target.value })} /></label>
        <div className="profile-preview">
          <div className="avatar large" style={{ borderColor: draft.profileBorder }}>
            {String(draft.profileImage).startsWith("data:") || String(draft.profileImage).startsWith("http")
              ? <img src={draft.profileImage} alt="" style={{ transform: `scale(${draft.zoom}) rotate(${draft.rotate}deg)` }} />
              : <span>{draft.profileImage}</span>}
          </div>
          <label className="secondary-button">
            <Camera size={18} /> 사진 변경
            <input hidden type="file" accept="image/*" onChange={handleFile} />
          </label>
        </div>
        <label><ZoomIn size={16} /> 확대<input type="range" min="1" max="2" step="0.05" value={draft.zoom} onChange={(event) => setDraft({ ...draft, zoom: event.target.value })} /></label>
        <label><RotateCw size={16} /> 회전<input type="range" min="-180" max="180" value={draft.rotate} onChange={(event) => setDraft({ ...draft, rotate: event.target.value })} /></label>
        <div>
          <strong>테두리</strong>
          <div className="color-row">
            {["#6d5dfc", "#0ea5e9", "#16a34a", "#f97316", "#111827"].map((color) => (
              <button key={color} className="color-dot" style={{ background: color }} onClick={() => setDraft({ ...draft, profileBorder: color })} />
            ))}
          </div>
        </div>
        <div>
          <strong>전시 배지 0~3개</strong>
          <div className="badge-edit-grid">
            {acquiredBadges.map((badge) => {
              const selected = draft.displayBadgeIds.includes(badge.id);
              return (
                <button
                  key={badge.id}
                  className={selected ? "badge-card active" : "badge-card"}
                  onClick={() => {
                    const next = selected
                      ? draft.displayBadgeIds.filter((id) => id !== badge.id)
                      : [...draft.displayBadgeIds, badge.id].slice(0, 3);
                    setDraft({ ...draft, displayBadgeIds: next });
                  }}
                >
                  <span style={{ background: badge.color }}>{badge.grade}</span>
                  <strong>{badge.label}</strong>
                </button>
              );
            })}
          </div>
        </div>
        <button className="primary-button full" onClick={saveProfile}>저장</button>
      </section>
    </div>
  );
}

function LoginPage({ navigate, setUser, showToast }) {
  const [form, setForm] = useState({
    identifier: localStorage.getItem(SAVED_ID_STORAGE) || "",
    password: "",
    rememberId: Boolean(localStorage.getItem(SAVED_ID_STORAGE)),
    autoLogin: localStorage.getItem(AUTO_LOGIN_STORAGE) === "true"
  });

  async function submit(event) {
    event.preventDefault();
    try {
      const result = await api("/api/auth/login", { method: "POST", body: JSON.stringify(form) });
      setUser(result.user);
      localStorage.setItem(USER_STORAGE, JSON.stringify(result.user));
      if (form.rememberId) localStorage.setItem(SAVED_ID_STORAGE, form.identifier);
      else localStorage.removeItem(SAVED_ID_STORAGE);
      localStorage.setItem(AUTO_LOGIN_STORAGE, String(form.autoLogin));
      navigate("mypage", "/mypage");
    } catch (error) {
      showToast(getErrorMessage(error));
    }
  }

  async function googleLogin() {
    const email = window.prompt("구글 이메일을 입력하세요.");
    if (!email) return;
    try {
      const result = await api("/api/auth/google", { method: "POST", body: JSON.stringify({ email, fullName: email.split("@")[0] }) });
      setUser(result.user);
      localStorage.setItem(USER_STORAGE, JSON.stringify(result.user));
      navigate("mypage", "/mypage");
    } catch (error) {
      showToast(getErrorMessage(error));
    }
  }

  return (
    <AuthShell title="로그인" subtitle="아이디 또는 이메일로 로그인합니다.">
      <form className="auth-form" onSubmit={submit}>
        <input value={form.identifier} onChange={(event) => setForm({ ...form, identifier: event.target.value })} placeholder="아이디 또는 이메일" />
        <input type="password" value={form.password} onChange={(event) => setForm({ ...form, password: event.target.value })} placeholder="비밀번호" />
        <label className="check-label"><input type="checkbox" checked={form.rememberId} onChange={(event) => setForm({ ...form, rememberId: event.target.checked })} /> 아이디 저장</label>
        <label className="check-label"><input type="checkbox" checked={form.autoLogin} onChange={(event) => setForm({ ...form, autoLogin: event.target.checked })} /> 자동 로그인</label>
        <button className="primary-button full" type="submit">로그인</button>
        <button className="secondary-button full" type="button" onClick={googleLogin}>Google로 계속하기</button>
      </form>
      <div className="auth-links">
        <button onClick={() => navigate("signup", "/signupform")}>회원가입</button>
        <button onClick={() => window.alert("아이디/비밀번호 찾기 API는 복구되어 있고, 전용 화면은 다음 단계에서 붙이면 됩니다.")}>아이디/비밀번호 찾기</button>
      </div>
    </AuthShell>
  );
}

function SignupPage({ navigate, setUser, showToast }) {
  const [form, setForm] = useState({
    fullName: "",
    username: "",
    password: "",
    passwordConfirm: "",
    email: "",
    phone: "",
    verificationCode: "",
    privacyConsent: false
  });

  async function submit(event) {
    event.preventDefault();
    if (form.password !== form.passwordConfirm) {
      showToast("비밀번호 확인이 일치하지 않습니다.");
      return;
    }
    try {
      const result = await api("/api/auth/signup", { method: "POST", body: JSON.stringify(form) });
      setUser(result.user);
      localStorage.setItem(USER_STORAGE, JSON.stringify(result.user));
      navigate("mypage", "/mypage");
    } catch (error) {
      showToast(getErrorMessage(error));
    }
  }

  return (
    <AuthShell title="회원가입" subtitle="가입된 전화번호나 이메일은 중복 가입으로 처리됩니다.">
      <form className="auth-form" onSubmit={submit}>
        <input value={form.fullName} onChange={(event) => setForm({ ...form, fullName: event.target.value })} placeholder="성명" />
        <input value={form.username} onChange={(event) => setForm({ ...form, username: event.target.value })} placeholder="아이디: 영문 소문자 시작 4~20자" />
        <input type="password" value={form.password} onChange={(event) => setForm({ ...form, password: event.target.value })} placeholder="비밀번호: 영문+숫자+특수문자 8자 이상" />
        <input type="password" value={form.passwordConfirm} onChange={(event) => setForm({ ...form, passwordConfirm: event.target.value })} placeholder="비밀번호 한번 더" />
        <input value={form.email} onChange={(event) => setForm({ ...form, email: event.target.value })} placeholder="이메일" />
        <input value={form.phone} onChange={(event) => setForm({ ...form, phone: event.target.value })} placeholder="전화번호" />
        <input value={form.verificationCode} onChange={(event) => setForm({ ...form, verificationCode: event.target.value })} placeholder="인증번호" />
        <label className="check-label"><input type="checkbox" checked={form.privacyConsent} onChange={(event) => setForm({ ...form, privacyConsent: event.target.checked })} /> 개인정보보호 동의</label>
        <button className="primary-button full" type="submit">가입하기</button>
      </form>
      <div className="auth-links"><button onClick={() => navigate("login", "/loginform")}>이미 계정이 있어요</button></div>
    </AuthShell>
  );
}

function InquiriesPage({ user, isLoggedIn, inquiries, navigate, loadUserData }) {
  const [form, setForm] = useState({ title: "", body: "" });
  if (!isLoggedIn) return <AuthRequired />;

  async function submit(event) {
    event.preventDefault();
    await api(`/api/users/${user.userId}/inquiries`, { method: "POST", body: JSON.stringify(form) });
    setForm({ title: "", body: "" });
    await loadUserData(user.userId);
  }

  return (
    <div className="page narrow">
      <BackButton label="마이페이지로 돌아가기" onClick={() => navigate("mypage", "/mypage")} />
      <PageTitle title="문의하기" subtitle="작성한 문의는 관리자 페이지로 전달됩니다." />
      <form className="card-form" onSubmit={submit}>
        <input value={form.title} onChange={(event) => setForm({ ...form, title: event.target.value })} placeholder="제목" />
        <textarea value={form.body} onChange={(event) => setForm({ ...form, body: event.target.value })} placeholder="문의 내용" />
        <button className="primary-button" type="submit"><Send size={17} /> 전송</button>
      </form>
      <div className="board-list">
        {inquiries.map((item) => (
          <div key={item.id} className="board-item">
            <div className="board-row"><strong>{item.title}</strong><span>{item.status}</span></div>
            <p>{item.body}</p>
            {item.adminReply && <p className="reply">답변: {item.adminReply}</p>}
            <button className="text-button danger" onClick={async () => { await api(`/api/users/${user.userId}/inquiries/${item.id}`, { method: "DELETE" }); loadUserData(user.userId); }}>삭제</button>
          </div>
        ))}
      </div>
    </div>
  );
}

function FollowListPage({ type, user, isLoggedIn, navigate }) {
  const [people, setPeople] = useState([]);
  useEffect(() => {
    if (!isLoggedIn) return;
    api(`/api/users/${user.userId}/${type}`).then(setPeople).catch(() => setPeople([]));
  }, [type, user?.userId, isLoggedIn]);

  return (
    <div className="page narrow">
      <BackButton label="마이페이지로 돌아가기" onClick={() => navigate("mypage", "/mypage")} />
      <PageTitle title={type === "followers" ? "팔로워" : "팔로잉"} subtitle={type === "followers" ? "나를 팔로우한 사용자" : "내가 팔로우한 사용자"} />
      <div className="people-list">
        {people.map((person) => (
          <div key={person.userId} className="person-card">
            <div className="avatar small" style={{ borderColor: person.profileBorder }}>{person.profileImage}</div>
            <div><strong>{person.nickname}</strong><span>@{person.username}</span></div>
          </div>
        ))}
        {people.length === 0 && <EmptyState title="아직 목록이 없습니다." body="test01 계정으로 팔로우 테스트를 진행할 수 있습니다." />}
      </div>
    </div>
  );
}

function DiaryPage({ user, isLoggedIn, diaries, navigate, loadUserData }) {
  const [mode, setMode] = useState("list");
  const [selectedDiary, setSelectedDiary] = useState(null);
  if (!isLoggedIn) {
    return (
      <AuthShell title="여행 다이어리" subtitle="로그인 후 여행 기록을 남길 수 있습니다.">
        <button className="primary-button full" onClick={() => navigate("login", "/loginform")}>로그인하기</button>
      </AuthShell>
    );
  }
  if (mode === "editor") {
    return <DiaryEditor user={user} selectedDiary={selectedDiary} setMode={setMode} loadUserData={loadUserData} />;
  }
  return (
    <div className="diary-page">
      <div className="diary-top">
        <BackButton label="마이페이지로 돌아가기" onClick={() => navigate("mypage", "/mypage")} />
        <button className="icon-button"><Search size={20} /></button>
      </div>
      <PageTitle title="여행 다이어리" subtitle="기록한 여행을 날짜별로 모아봅니다." />
      <div className="diary-tabs">
        {["list", "calendar", "settings"].map((item) => (
          <button key={item} className={mode === item ? "active" : ""} onClick={() => setMode(item)}>
            {item === "list" ? "기록한 여행" : item === "calendar" ? "달력" : "설정"}
          </button>
        ))}
      </div>
      {mode === "list" && (
        <div className="diary-list">
          {diaries.map((entry) => (
            <button key={entry.id} className="diary-card" onClick={() => { setSelectedDiary(entry); setMode("editor"); }}>
              <span>{compactDate(entry.visitedDate)}</span>
              <strong>{entry.title}</strong>
              <p>{entry.body}</p>
            </button>
          ))}
          {diaries.length === 0 && <EmptyState title="기록한 여행이 없습니다." body="오른쪽 아래 버튼으로 첫 기록을 작성하세요." />}
        </div>
      )}
      {mode === "calendar" && (
        <div className="calendar-card">
          {Array.from({ length: 31 }, (_, index) => index + 1).map((day) => {
            const entry = diaries.find((item) => Number(String(item.visitedDate).slice(-2)) === day);
            return <button key={day} className={entry ? "has-entry" : ""}>{day}</button>;
          })}
        </div>
      )}
      {mode === "settings" && <EmptyState title="설정은 다음 단계에서 확장합니다." body="현재는 기록 작성과 달력 표시를 우선 복구했습니다." />}
      <button className="floating-write" onClick={() => { setSelectedDiary(null); setMode("editor"); }}><Plus size={26} /></button>
    </div>
  );
}

function DiaryEditor({ user, selectedDiary, setMode, loadUserData }) {
  const [form, setForm] = useState(selectedDiary || {
    title: "",
    body: "",
    placeName: "",
    visitedDate: new Date().toISOString().slice(0, 10),
    weatherIcon: "sun",
    pageColor: "#ffffff",
    fontFamily: "Pretendard",
    imageUrl: ""
  });

  async function save() {
    const method = selectedDiary ? "PUT" : "POST";
    const path = selectedDiary ? `/api/users/${user.userId}/diaries/${selectedDiary.id}` : `/api/users/${user.userId}/diaries`;
    await api(path, { method, body: JSON.stringify(form) });
    await loadUserData(user.userId);
    setMode("list");
  }

  function handleImage(event) {
    const file = event.target.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => setForm({ ...form, imageUrl: String(reader.result) });
    reader.readAsDataURL(file);
  }

  return (
    <div className="diary-editor" style={{ background: form.pageColor }}>
      <div className="editor-top">
        <button className="icon-button" onClick={() => setMode("list")}><ChevronLeft size={24} /></button>
        <div className="button-row">
          {selectedDiary && <button className="icon-button" onClick={async () => { await api(`/api/users/${user.userId}/diaries/${selectedDiary.id}`, { method: "DELETE" }); await loadUserData(user.userId); setMode("list"); }}><Trash2 size={20} /></button>}
          <button className="primary-button" onClick={save}>저장</button>
        </div>
      </div>
      <div className="editor-date">
        <input type="date" value={compactDate(form.visitedDate)} onChange={(event) => setForm({ ...form, visitedDate: event.target.value })} />
        <select value={form.weatherIcon} onChange={(event) => setForm({ ...form, weatherIcon: event.target.value })}>
          <option value="sun">맑음</option>
          <option value="cloud">흐림</option>
          <option value="rain">비</option>
          <option value="snow">눈</option>
        </select>
      </div>
      {form.imageUrl && <img className="diary-photo" src={form.imageUrl} alt="" />}
      <input className="diary-title-input" value={form.title} onChange={(event) => setForm({ ...form, title: event.target.value })} placeholder="제목" />
      <textarea className="diary-body-input" value={form.body} onChange={(event) => setForm({ ...form, body: event.target.value })} placeholder="오늘의 여행을 기록하세요." />
      <nav className="editor-toolbar">
        <label><ImagePlus size={18} /><input hidden type="file" accept="image/*" onChange={handleImage} /></label>
        <label><Palette size={18} /><input hidden type="color" value={form.pageColor} onChange={(event) => setForm({ ...form, pageColor: event.target.value })} /></label>
        <button><PenLine size={18} /></button>
        <button><Star size={18} /></button>
      </nav>
    </div>
  );
}

function AdminPage({ adminTab, setAdminTab, showToast, loadNotices }) {
  const [adminCode, setAdminCode] = useState(localStorage.getItem(ADMIN_CODE_STORAGE) || "");
  const [adminNotices, setAdminNotices] = useState([]);
  const [adminInquiries, setAdminInquiries] = useState([]);
  const [adminSubmissions, setAdminSubmissions] = useState([]);
  const [noticeForm, setNoticeForm] = useState({ title: "", body: "", imageUrl: "", bodySize: "normal", bodyWeight: "normal" });

  async function loadAdminData() {
    localStorage.setItem(ADMIN_CODE_STORAGE, adminCode);
    try {
      const [noticeList, inquiryList, submissionList] = await Promise.all([
        api("/api/notices"),
        api(`/api/admin/inquiries?adminCode=${encodeURIComponent(adminCode)}`),
        api(`/api/admin/place-submissions?adminCode=${encodeURIComponent(adminCode)}`)
      ]);
      setAdminNotices(noticeList);
      setAdminInquiries(inquiryList);
      setAdminSubmissions(submissionList);
    } catch (error) {
      showToast(getErrorMessage(error));
    }
  }

  async function createNotice(event) {
    event.preventDefault();
    await api(`/api/admin/notices?adminCode=${encodeURIComponent(adminCode)}`, { method: "POST", body: JSON.stringify(noticeForm) });
    setNoticeForm({ title: "", body: "", imageUrl: "", bodySize: "normal", bodyWeight: "normal" });
    await loadAdminData();
    await loadNotices();
  }

  return (
    <div className="admin-page">
      <div className="admin-shell">
        <aside>
          <h1>숨길 관리자</h1>
          <div className="admin-code">
            <input value={adminCode} onChange={(event) => setAdminCode(event.target.value)} placeholder="관리자 코드" />
            <button className="primary-button full" onClick={loadAdminData}>접속</button>
          </div>
          {["notices", "submissions", "inquiries"].map((tab) => (
            <button key={tab} className={adminTab === tab ? "admin-tab active" : "admin-tab"} onClick={() => setAdminTab(tab)}>
              {tab === "notices" ? "공지사항" : tab === "submissions" ? "장소 제보" : "사용자 문의"}
            </button>
          ))}
        </aside>
        <section>
          {adminTab === "notices" && (
            <>
              <h2>공지사항 작성</h2>
              <form className="notice-editor" onSubmit={createNotice}>
                <div className="editor-menu">
                  <button type="button"><ImagePlus size={16} /> 이미지</button>
                  <button type="button"><Edit3 size={16} /> 외부콘텐츠</button>
                  <button type="button" onClick={() => setNoticeForm({ ...noticeForm, bodyWeight: noticeForm.bodyWeight === "bold" ? "normal" : "bold" })}>굵게</button>
                  <select value={noticeForm.bodySize} onChange={(event) => setNoticeForm({ ...noticeForm, bodySize: event.target.value })}>
                    <option value="normal">보통</option>
                    <option value="large">큰 글씨</option>
                  </select>
                </div>
                <input value={noticeForm.title} onChange={(event) => setNoticeForm({ ...noticeForm, title: event.target.value })} placeholder="제목" />
                <input value={noticeForm.imageUrl} onChange={(event) => setNoticeForm({ ...noticeForm, imageUrl: event.target.value })} placeholder="이미지 URL" />
                <textarea value={noticeForm.body} onChange={(event) => setNoticeForm({ ...noticeForm, body: event.target.value })} placeholder="내용" />
                <div className="button-row end">
                  <button className="secondary-button" type="button">임시저장</button>
                  <button className="primary-button" type="submit">등록</button>
                </div>
              </form>
              <div className="board-list">{adminNotices.map((notice) => <AdminNotice key={notice.id} notice={notice} adminCode={adminCode} reload={loadAdminData} />)}</div>
            </>
          )}
          {adminTab === "submissions" && (
            <div className="board-list">
              {adminSubmissions.map((item) => (
                <div key={item.id} className="board-item">
                  <div className="board-row"><strong>{item.name}</strong><span>{item.status}</span></div>
                  <p>{item.address}</p>
                  <p>{item.description}</p>
                  <div className="button-row">
                    <button className="secondary-button" onClick={async () => { await api(`/api/admin/place-submissions/${item.id}?adminCode=${encodeURIComponent(adminCode)}`, { method: "PUT", body: JSON.stringify({ status: "승인" }) }); loadAdminData(); }}><CheckCircle2 size={17} /> 승인</button>
                    <button className="secondary-button danger" onClick={async () => { await api(`/api/admin/place-submissions/${item.id}?adminCode=${encodeURIComponent(adminCode)}`, { method: "PUT", body: JSON.stringify({ status: "반려" }) }); loadAdminData(); }}><XCircle size={17} /> 반려</button>
                  </div>
                </div>
              ))}
            </div>
          )}
          {adminTab === "inquiries" && (
            <div className="board-list">
              {adminInquiries.map((item) => <AdminInquiry key={item.id} item={item} adminCode={adminCode} reload={loadAdminData} />)}
            </div>
          )}
        </section>
      </div>
    </div>
  );
}

function AdminInquiry({ item, adminCode, reload }) {
  const [reply, setReply] = useState(item.adminReply || "");
  return (
    <div className="board-item">
      <div className="board-row"><strong>{item.title}</strong><span>{item.status}</span></div>
      <p>{item.body}</p>
      {item.adminReply && <p className="reply">답변: {item.adminReply}</p>}
      <textarea value={reply} onChange={(event) => setReply(event.target.value)} placeholder="답변 내용" />
      <div className="button-row">
        <button className="secondary-button" onClick={async () => { await api(`/api/admin/inquiries/${item.id}/reply?adminCode=${encodeURIComponent(adminCode)}`, { method: "PUT", body: JSON.stringify({ reply }) }); reload(); }}>답변</button>
        <button className="secondary-button danger" onClick={async () => { await api(`/api/admin/inquiries/${item.id}?adminCode=${encodeURIComponent(adminCode)}`, { method: "DELETE" }); reload(); }}>삭제</button>
      </div>
    </div>
  );
}

function PageTitle({ title, subtitle, eyebrow = "SOOMGIL" }) {
  return (
    <div className="page-title">
      <span>{eyebrow}</span>
      <h1>{title}</h1>
      {subtitle && <p>{subtitle}</p>}
    </div>
  );
}

function BackButton({ label, onClick }) {
  return (
    <button className="back-button" onClick={onClick}>
      <ChevronLeft size={20} /> {label}
    </button>
  );
}

function AuthShell({ title, subtitle, children }) {
  return (
    <div className="auth-page">
      <button className="back-button" onClick={() => window.history.length > 1 ? window.history.back() : window.location.assign("/")}>
        <ChevronLeft size={20} /> 돌아가기
      </button>
      <section className="auth-card">
        <div className="brand center"><span className="brand-mark">S</span><span>Soomgil</span></div>
        <h1>{title}</h1>
        <p>{subtitle}</p>
        {children}
      </section>
    </div>
  );
}

function AuthRequired() {
  return (
    <AuthShell title="로그인이 필요합니다" subtitle="이 기능은 로그인 후 사용할 수 있습니다.">
      <a className="primary-button full" href="/loginform">로그인하기</a>
    </AuthShell>
  );
}

function EmptyState({ title, body }) {
  return (
    <div className="empty-state">
      <Menu size={28} />
      <strong>{title}</strong>
      <p>{body}</p>
    </div>
  );
}
