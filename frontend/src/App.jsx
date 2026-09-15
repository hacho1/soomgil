import { lazy, Suspense, useCallback, useEffect, useRef, useState } from "react";
import { Link, NavLink, Navigate, Route, Routes, ScrollRestoration, useLocation, useNavigate } from "react-router-dom";
import { Bell, CalendarDays, Compass, Home, Map as MapIcon, Moon, Sun, User } from "lucide-react";
import { api, readLocal, resetCsrf, writeLocal } from "./lib/api";
import { clearAuthIntent, isGuestBrowsing, readAuthIntent, rememberGuestBrowsing, saveAuthIntent } from "./lib/authFlow";
import { AppContext } from "./lib/context";
import { needsHomeRecommendations } from "./lib/recommendations";
import { IconButton, Loading, Modal } from "./components/ui";
import SearchSheet from "./components/SearchSheet";
import BadgeArtwork from "./components/BadgeArtwork";
import { HomePage, CategoryPage, SearchPage } from "./pages/ExplorePages";
import { LoginPage, SignupPage, SignupChoicePage, RecoveryPage } from "./pages/AuthPages";
import WelcomePage from "./pages/WelcomePage";
import OnboardingPage from "./pages/OnboardingPage";
import NaverCompletePage from "./pages/NaverCompletePage";
import "./onboarding.css";
import { MyPage, CollectionPage, SettingsPage, AccountPage, BadgePage, InterestsPage, AboutPage, FollowPage } from "./pages/MyPages";
import { NoticesPage, NotificationsPage, InquiriesPage, SubmissionPage, AdminPage } from "./pages/CommunityPages";
import PlaceDetail from "./pages/PlaceDetail";
const MapPage = lazy(() => import("./pages/MapPage"));
const DiaryPage = lazy(() => import("./pages/DiaryPage"));
const EMPTY_PLACES = [];

export default function App() {
  const location = useLocation();
  const navigate = useNavigate();
  const [user, setUser] = useState(null);
  const [authLoading, setAuthLoading] = useState(true);
  const [darkMode, setDarkModeState] = useState(() => readLocal("soomgilDarkMode", false));
  const [homeFeed, setHomeFeed] = useState(null);
  const catalog = homeFeed?.items || EMPTY_PLACES;
  const [catalogLoading, setCatalogLoading] = useState(true);
  const [catalogError, setCatalogError] = useState("");
  const [favorites, setFavorites] = useState([]);
  const [recentViews, setRecentViews] = useState(() => readLocal("soomgilRecentViews", []));
  const [notifications, setNotifications] = useState([]);
  const [toast, setToast] = useState("");
  const [badgeToast, setBadgeToast] = useState(null);
  const [search, setSearch] = useState(null);
  const [loginPrompt, setLoginPrompt] = useState(false);
  const [guestBrowsing, setGuestBrowsing] = useState(isGuestBrowsing);
  const [revision, setRevision] = useState(0);
  const toastTimer = useRef();
  const badgeToastTimer = useRef();
  const seenNotifications = useRef(null);
  const userRef = useRef(null);
  const favoritePending = useRef(new Set());
  const catalogRequest = useRef(null);

  const notify = useCallback(message => {
    clearTimeout(toastTimer.current);
    setToast(message);
    toastTimer.current = setTimeout(() => setToast(""), 3000);
  }, []);
  useEffect(() => () => { clearTimeout(toastTimer.current); clearTimeout(badgeToastTimer.current); }, []);

  const reloadCatalog = useCallback(async () => {
    catalogRequest.current?.abort();
    const controller = new AbortController(); catalogRequest.current = controller;
    setCatalogLoading(true); setCatalogError(""); setHomeFeed(null);
    try { const feed = await api("/api/places/recommendations", { signal: controller.signal }); if (!controller.signal.aborted) setHomeFeed(feed); }
    catch (error) { if (!controller.signal.aborted) setCatalogError(error.message); }
    finally { if (!controller.signal.aborted) setCatalogLoading(false); }
  }, []);
  const homeActive = needsHomeRecommendations(location.pathname, location.state?.backgroundLocation?.pathname,
    !authLoading && (user ? user.onboardingCompleted !== false : guestBrowsing));
  useEffect(() => { if (homeActive) reloadCatalog(); return () => catalogRequest.current?.abort(); }, [reloadCatalog, homeActive, user?.userId, revision]);
  useEffect(() => {
    document.body.classList.toggle("theme-dark", darkMode);
    document.documentElement.style.colorScheme = darkMode ? "dark" : "light";
    writeLocal("soomgilDarkMode", darkMode);
  }, [darkMode]);

  const refreshUser = useCallback(async () => {
    try {
      const result = await api("/api/auth/me");
      if (userRef.current?.userId !== result.user.userId) { catalogRequest.current?.abort(); setHomeFeed(null); setDarkModeState(Boolean(result.user.darkMode)); }
      userRef.current = result.user;
      setUser(result.user);
      return result.user;
    } catch (error) {
      if (error.status === 401) { setUser(null); userRef.current = null; setFavorites([]); setNotifications([]); setRecentViews(readLocal("soomgilRecentViews", [])); }
      throw error;
    }
  }, []);
  useEffect(() => {
    let active = true;
    // Old localStorage profiles are never used as authentication.
    try { localStorage.removeItem("soomgilUser"); } catch { /* Storage may be disabled. */ }
    refreshUser().catch(() => {}).finally(() => { if (active) setAuthLoading(false); });
    return () => { active = false; };
  }, [refreshUser]);

  const refreshPrivateData = useCallback(async () => {
    const id = userRef.current?.userId;
    if (!id) return;
    const results = await Promise.allSettled([
      api(`/api/users/${id}/favorites`),
      api(`/api/users/${id}/recent-views`),
      api(`/api/users/${id}/notifications`)
    ]);
    if (userRef.current?.userId !== id) return;
    if (results[0].status === "fulfilled") setFavorites(results[0].value);
    if (results[1].status === "fulfilled") setRecentViews(results[1].value);
    if (results[2].status === "fulfilled") {
      const notes = results[2].value;
      if (seenNotifications.current && userRef.current.notificationEnabled) {
        const incoming = notes.filter(note => !note.read && !seenNotifications.current.has(note.id));
        const earned = incoming.find(note => note.referenceType === "badge");
        if (earned) {
          clearTimeout(badgeToastTimer.current);
          setBadgeToast({ id: earned.referenceId, message: earned.message });
          badgeToastTimer.current = setTimeout(() => setBadgeToast(null), 3000);
        } else if (incoming.length) notify("새로운 알림이 도착했어요!");
      }
      seenNotifications.current = new Set(notes.map(note => note.id));
      setNotifications(notes);
    }
  }, [notify]);
  useEffect(() => {
    clearTimeout(badgeToastTimer.current); setBadgeToast(null);
    if (!user?.userId) return;
    seenNotifications.current = null;
    refreshPrivateData();
    const timer = setInterval(() => { if (!document.hidden) refreshPrivateData(); }, 30000);
    return () => clearInterval(timer);
  }, [user?.userId, refreshPrivateData]);

  async function changed() { await refreshUser(); await refreshPrivateData(); setRevision(value => value + 1); }
  function browseAsGuest() {
    rememberGuestBrowsing(); setGuestBrowsing(true); navigate("/", { replace: true });
  }
  async function finishAuth(account) {
    const current = account || await refreshUser();
    const intent = readAuthIntent();
    if (intent.favoriteId) {
      try { await api(`/api/users/${current.userId}/favorites`, { method: "POST", body: JSON.stringify({ placeId: intent.favoriteId }) }); }
      catch (error) { notify(`로그인했지만 장소를 저장하지 못했어요. ${error.message}`); }
    }
    clearAuthIntent();
    await refreshPrivateData();
    navigate(intent.returnTo, { replace: true });
  }
  async function completeLogin() {
    const current = await refreshUser();
    if (current.onboardingCompleted === false) navigate("/onboarding", { replace: true });
    else await finishAuth(current);
  }
  async function setDarkMode(value) {
    const before = darkMode;
    const id = userRef.current?.userId;
    setDarkModeState(value);
    if (id) {
      try { await api(`/api/users/${id}/settings`, { method: "PUT", body: JSON.stringify({ darkMode: value }) }); }
      catch (error) { if (userRef.current?.userId === id) { setDarkModeState(before); notify(error.message); } }
    }
  }
  async function logout() {
    try {
      await api("/api/auth/logout", { method: "POST" });
      resetCsrf(); setUser(null); userRef.current = null; setFavorites([]); setNotifications([]);
      catalogRequest.current?.abort(); setHomeFeed(null);
      setRecentViews(readLocal("soomgilRecentViews", []));
      navigate("/mypage"); notify("로그아웃했어요.");
    } catch (error) { notify(error.message); }
  }
  async function toggleFavorite(place) {
    if (!user) { saveAuthIntent({ returnTo: `/places/${place.id}`, favoriteId: Number(place.id) }); setLoginPrompt(true); return; }
    if (favoritePending.current.has(place.id)) return;
    favoritePending.current.add(place.id);
    const saved = favorites.some(item => item.id === place.id);
    try {
      await api(saved ? `/api/users/${user.userId}/favorites/${place.id}` : `/api/users/${user.userId}/favorites`, { method: saved ? "DELETE" : "POST", body: saved ? undefined : JSON.stringify({ placeId: place.id }) });
      await changed();
      notify(saved ? "찜목록에서 삭제했어요." : "찜목록에 저장했어요.");
    } catch (error) { notify(error.message); }
    finally { favoritePending.current.delete(place.id); }
  }
  function openPlace(place) {
    if (!user && place.name) {
      const next = [place, ...readLocal("soomgilRecentViews", []).filter(item => item.id !== place.id)].slice(0, 12);
      writeLocal("soomgilRecentViews", next); setRecentViews(next);
    }
    const background = location.state?.backgroundLocation || location;
    navigate(`/places/${place.id}`, { state: { backgroundLocation: background, returnTo: background.pathname + background.search } });
  }

  const path = location.pathname;
  const background = location.state?.backgroundLocation;
  const detailId = path.match(/^\/places\/(\d+)$/)?.[1];
  const welcome = !user && !guestBrowsing && path === "/";
  const needsOnboarding = user?.onboardingCompleted === false && path !== "/onboarding" && path !== "/auth/naver/complete" && !path.startsWith("/admin");
  const noShell = welcome || needsOnboarding || /^\/(welcome|onboarding|loginform|signup|recovery|diary|admin|auth)/.test(background?.pathname || path);
  const unread = user?.notificationEnabled && notifications.some(note => !note.read);
  const context = { user, authLoading, favorites, recentViews, notifications, catalog, homeFeed, catalogLoading, catalogError, darkMode, setDarkMode, toggleFavorite, openPlace, openSearch: (initial = "") => setSearch(initial), notify, changed, refreshUser, refreshPrivateData, logout, reloadCatalog, revision, browseAsGuest, completeLogin, finishAuth };

  return <AppContext.Provider value={context}>
    <div className={`app ${noShell ? "no-shell" : ""} ${path.startsWith("/admin") ? "admin-app" : ""}`}>
      {!noShell && <header className="app-header"><Link className="brand" to="/"><span className="brand-mark">S</span><span>Soomgil</span></Link><div className="header-actions">
        <IconButton label={darkMode ? "라이트 모드" : "다크 모드"} onClick={() => setDarkMode(!darkMode)}>{darkMode ? <Sun size={20} /> : <Moon size={20} />}</IconButton>
        <Link to={user ? "/notifications" : "/notices"} className="icon-button notification-button" aria-label="알림"><Bell size={20} />{unread && <i className="notification-dot" />}</Link>
      </div></header>}
      <main><Suspense fallback={<Loading />}>{needsOnboarding ? <Navigate to="/onboarding" replace /> : <Routes location={background || location}>
        <Route path="/" element={authLoading ? <Loading /> : welcome ? <WelcomePage /> : <HomePage />} />
        <Route path="/welcome" element={user ? <Navigate to="/" replace /> : <WelcomePage />} />
        <Route path="/onboarding" element={<OnboardingPage />} />
        <Route path="/auth/naver/complete" element={<NaverCompletePage />} />
        <Route path="/category" element={<CategoryPage />} />
        <Route path="/search" element={<SearchPage />} />
        <Route path="/map" element={<MapPage />} />
        <Route path="/mypage" element={<MyPage />} />
        <Route path="/mypage/favorites" element={<CollectionPage type="favorites" />} />
        <Route path="/mypage/recent" element={<CollectionPage type="recent" />} />
        <Route path="/mypage/reviews" element={<CollectionPage type="reviews" />} />
        <Route path="/mypage/followers" element={<FollowPage type="followers" />} />
        <Route path="/mypage/following" element={<FollowPage type="following" />} />
        <Route path="/mypage/inquiries" element={<InquiriesPage />} />
        <Route path="/mypage/submissions" element={<SubmissionPage />} />
        <Route path="/mypage/badges" element={<BadgePage />} />
        <Route path="/mypage/interests" element={<InterestsPage />} />
        <Route path="/settings" element={<SettingsPage />} />
        <Route path="/settings/notifications" element={<SettingsPage notificationsOnly />} />
        <Route path="/settings/account" element={<AccountPage />} />
        <Route path="/about" element={<AboutPage />} />
        <Route path="/notices" element={<NoticesPage />} />
        <Route path="/notices/:id" element={<NoticesPage />} />
        <Route path="/notifications" element={<NotificationsPage />} />
        <Route path="/loginform" element={<LoginPage />} />
        <Route path="/signupform" element={<SignupPage />} />
        <Route path="/signup" element={<SignupChoicePage />} />
        <Route path="/recovery" element={<RecoveryPage />} />
        <Route path="/diary/*" element={<DiaryPage />} />
        <Route path="/admin.html" element={<AdminPage />} />
        <Route path="/places/:id" element={<HomePage />} />
        <Route path="*" element={<div className="page"><h1>페이지를 찾을 수 없어요</h1><Link to="/">홈으로</Link></div>} />
      </Routes>}</Suspense></main>
      {!noShell && <nav className="bottom-nav" aria-label="주 메뉴">{[
        ["/category", "카테고리", Compass], ["/map", "지도", MapIcon], ["/", "홈", Home], ["/diary", "다이어리", CalendarDays], ["/mypage", "마이", User]
      ].map(([to, label, Icon]) => <NavLink to={to} state={to === "/diary" ? { returnTo: path + location.search, returnLabel: path === "/mypage" ? "마이페이지로 돌아가기" : "이전 화면으로 돌아가기" } : undefined} end={to === "/"} key={to} className={({ isActive }) => `${isActive ? "active" : ""} ${to === "/" ? "home-nav" : ""}`}><Icon size={22} strokeWidth={1.8} /><span>{label}</span></NavLink>)}</nav>}
      {search !== null && <SearchSheet initial={search} onClose={() => setSearch(null)} />}
      {detailId && !needsOnboarding && <PlaceDetail key={detailId} id={detailId} onClose={() => { background ? navigate(-1) : navigate("/search", { replace: true }); refreshPrivateData(); }} />}
      {loginPrompt && <Modal title="마음에 드는 장소를 저장할까요?" onClose={() => { setLoginPrompt(false); clearAuthIntent(); }}><p>로그인하면 찜한 장소를 언제든 다시 볼 수 있어요.</p><div className="login-prompt-actions"><button className="primary-button full" onClick={() => { setLoginPrompt(false); navigate("/loginform"); }}>로그인</button><button className="secondary-button full" onClick={() => { setLoginPrompt(false); navigate("/signup"); }}>회원가입</button><button className="text-button" onClick={() => { setLoginPrompt(false); clearAuthIntent(); }}>취소</button></div></Modal>}
      {toast && !badgeToast && <div className="toast" role="status">{toast}</div>}
      {badgeToast && <div key={badgeToast.id} className="toast badge-toast" role="status"><BadgeArtwork badge={badgeToast} size="small" /><div><strong>새로운 알림이 도착했어요!</strong><span>{badgeToast.message}</span></div></div>}
    </div>
    <ScrollRestoration />
  </AppContext.Provider>;
}
