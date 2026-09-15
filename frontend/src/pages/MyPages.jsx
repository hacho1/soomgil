import { useMemo, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { Award, Bell, BookOpen, CalendarDays, ChevronRight, Clock3, Heart, Info, Languages, LockKeyhole, LogOut, MapPin, MessageCircle, Moon, Pencil, Search, Settings, SlidersHorizontal, Sparkles, UserRound } from "lucide-react";
import { api } from "../lib/api";
import { useApp, useResource } from "../lib/context";
import { useCrowdInfo } from "../lib/useCrowdInfo";
import { Avatar, BackLink, EmptyState, ErrorState, IconButton, Loading, MenuLink, Modal, PageHeading, PlaceCard, RequireLogin, Switch } from "../components/ui";
import ProfileEditor from "../components/ProfileEditor";
import BadgeArtwork from "../components/BadgeArtwork";
import BadgeCard, { badgeGrades } from "../components/BadgeCard";
import { PasswordField } from "./AuthPages";
import EmailVerification from "../components/EmailVerification";
import regions from "../data/regions.json" with { type: "json" };
import travelChoices from "../data/travel-preferences.json" with { type: "json" };
import { saveAuthIntent } from "../lib/authFlow";

export function MyPage() {
  const { user, authLoading, favorites, darkMode, setDarkMode } = useApp();
  const [profileOpen, setProfileOpen] = useState(false);
  if (authLoading) return <div className="page"><Loading /></div>;
  const displayed = user?.progress?.badges?.filter(badge => badge.earned && user.displayBadgeIds.includes(badge.id)) || [];
  return <div className="page my-page"><PageHeading title="마이페이지" action={<Link to="/settings" className="icon-button" aria-label="설정" state={{ returnTo: "/mypage" }}><Settings size={22} /></Link>} />
    {user ? <section className="profile-section"><div className="profile-main"><Avatar user={user} /><div><span className="profile-level">Lv.{user.progress?.level || 1} {user.progress?.levelName || "새싹 여행자"}</span><h2>{user.nickname}</h2><div className="display-badges">{displayed.map(badge => <Link to="/mypage/badges" key={badge.id} title={badge.label}><BadgeArtwork badge={badge} size="small" /><span>{badge.label}</span></Link>)}</div></div></div>
      <div className="profile-stats"><Link to="/mypage/followers" state={{ returnTo: "/mypage" }}><strong>{user.stats.followers}</strong><span>팔로워</span></Link><Link to="/mypage/following" state={{ returnTo: "/mypage" }}><strong>{user.stats.following}</strong><span>팔로잉</span></Link><Link to="/mypage/favorites" state={{ returnTo: "/mypage" }}><strong>{favorites.length}</strong><span>찜한 장소</span></Link></div>
      <button className="secondary-button full" onClick={() => setProfileOpen(true)}><Pencil size={16} />프로필 수정</button>
    </section> : <section className="guest-section"><div className="guest-symbol"><UserRound size={29} /></div><h2>나만의 여행을 모아보세요</h2><p>마음에 드는 장소부터 소중한 여행 기록까지.</p><Link className="primary-button full" to="/loginform">로그인 / 회원가입 <ChevronRight size={18} /></Link></section>}
    {user && <><section className="menu-section"><h2>나의 여행</h2><MenuLink icon={Heart} title="찜한 장소" detail={favorites.length} to="/mypage/favorites" /><MenuLink icon={Clock3} title="최근 본 장소" to="/mypage/recent" /><MenuLink icon={CalendarDays} title="여행 다이어리" detail={user.stats.diaries} to="/diary" /><MenuLink icon={MessageCircle} title="작성한 후기" detail={user.stats.reviews} to="/mypage/reviews" /></section>
      <section className="menu-section"><h2>나의 활동</h2><MenuLink icon={Award} title="배지와 레벨" detail={`${user.progress?.badges.filter(b => b.earned).length || 0}개 획득`} to="/mypage/badges" /><MenuLink icon={SlidersHorizontal} title="여행 관심사" to="/mypage/interests" /><MenuLink icon={MapPin} title="숨은 장소 제보" to="/mypage/submissions" /></section></>}
    <section className="menu-section"><h2>서비스 관리</h2>{user && <><MenuLink icon={Bell} title="알림 설정" to="/settings/notifications" /><MenuLink icon={MessageCircle} title="문의하기" to="/mypage/inquiries" /></>}<MenuLink icon={BookOpen} title="공지사항" to="/notices" /><MenuLink icon={Info} title="앱 정보" to="/about" />{!user && <><Switch label="다크 모드" checked={darkMode} onChange={setDarkMode} /><div className="menu-row"><Languages size={20} /><span>언어</span><small>한국어</small></div></>}</section>
    {profileOpen && <ProfileEditor onClose={() => setProfileOpen(false)} />}
  </div>;
}

export function CollectionPage({ type }) {
  const { user, favorites, recentViews, revision, notify, changed, openPlace } = useApp();
  const reviews = useResource(type === "reviews" && user ? `/api/users/${user.userId}/reviews` : null, revision);
  const title = type === "favorites" ? "찜한 장소" : type === "recent" ? "최근 본 장소" : "작성한 후기";
  const places = type === "favorites" ? favorites : recentViews;
  const [listing, setListing] = useState({ type, limit: 6 });
  const limit = listing.type === type ? listing.limit : 6;
  const visible = useMemo(() => type === "reviews" || (type === "favorites" && !user) ? [] : places.slice(0, limit), [places, type, user, limit]);
  const crowdInfo = useCrowdInfo(visible, visible.map(place => place.id));
  return <div className="page"><BackLink /><PageHeading title={title} />{type === "reviews" ? <RequireLogin>{reviews.loading ? <Loading /> : reviews.error ? <ErrorState message={reviews.error} /> : <div className="review-list">{reviews.data?.map(review => <article className="review-item" key={review.id}><button className="text-button" onClick={() => openPlace({ id: review.placeId })}>{review.placeName}<ChevronRight size={15} /></button><p>{review.text}</p><div className="review-footer"><time>{String(review.createdAt).slice(0, 10)}</time><button className="text-button danger" onClick={async () => { try { await api(`/api/reviews/${review.id}`, { method: "DELETE" }); await changed(); notify("후기를 삭제했어요."); } catch (e) { notify(e.message); } }}>삭제</button></div></article>)}{reviews.data?.length === 0 && <EmptyState title="아직 작성한 후기가 없어요" />}</div>}</RequireLogin> : type === "favorites" && !user ? <RequireLogin /> : <div className="result-list">{crowdInfo.places.map(place => <PlaceCard place={place} key={place.id} />)}{crowdInfo.failed && <button className="text-button" disabled={crowdInfo.loading} onClick={crowdInfo.retry}>혼잡도 다시 확인</button>}{places.length > limit && <button className="secondary-button full" onClick={() => setListing({ type, limit: limit + 6 })}>장소 더 보기</button>}{places.length === 0 && <EmptyState title={type === "favorites" ? "마음에 드는 장소를 모아보세요" : "최근 본 장소가 없어요"} action={<Link className="secondary-button" to="/search">여행지 둘러보기</Link>} />}</div>}</div>;
}

export function SettingsPage({ notificationsOnly = false }) {
  const { user, darkMode, setDarkMode, changed, notify, logout } = useApp();
  const [busy, setBusy] = useState(false);
  async function notifications(value) {
    setBusy(true);
    try { await api(`/api/users/${user.userId}/settings`, { method: "PUT", body: JSON.stringify({ notificationEnabled: value }) }); await changed(); }
    catch (error) { notify(error.message); } finally { setBusy(false); }
  }
  return <div className="page"><BackLink /><PageHeading title={notificationsOnly ? "알림 설정" : "설정"} />
    {notificationsOnly ? <RequireLogin><section className="menu-section"><Switch label="알림 표시" checked={Boolean(user?.notificationEnabled)} onChange={notifications} disabled={busy} /><p className="muted">공지, 배지, 문의 답변 알림</p></section></RequireLogin> : <>
      {user && <section className="menu-section"><h2>계정</h2><MenuLink icon={UserRound} title="내 계정" to="/settings/account" /></section>}
      <section className="menu-section"><h2>화면과 알림</h2><Switch label="다크 모드" checked={darkMode} onChange={setDarkMode} />{user && <MenuLink icon={Bell} title="알림 설정" to="/settings/notifications" />}<div className="menu-row"><Languages size={20} /><span>언어</span><small>한국어</small></div></section>
      <section className="menu-section"><h2>서비스</h2><MenuLink icon={BookOpen} title="공지사항" to="/notices" />{user && <MenuLink icon={MessageCircle} title="문의하기" to="/mypage/inquiries" />}<MenuLink icon={Info} title="앱 정보" to="/about" /></section>
      {user && <button className="logout-button" onClick={logout}><LogOut size={18} />로그아웃</button>}
    </>}
  </div>;
}

export function AccountPage() {
  const { user, notify, changed, refreshUser } = useApp();
  const socialProvider = user?.authProvider === "google" ? "Google" : user?.authProvider === "naver" ? "네이버" : "";
  const navigate = useNavigate();
  const [editing, setEditing] = useState(null);
  const [value, setValue] = useState("");
  const [password, setPassword] = useState("");
  const [address, setAddress] = useState(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [verified, setVerified] = useState(null);
  function edit(type) { setEditing(type); setValue(type === "verifyEmail" ? user.email : user[type] || ""); setPassword(""); setError(""); setVerified(null); }
  async function save(event) {
    event.preventDefault(); if (busy) return;
    setBusy(true); setError("");
    try {
      if (address) await api(`/api/users/${user.userId}/settings`, { method: "PUT", body: JSON.stringify({ addressSido: address.sido, addressSigungu: address.sigungu, addressEupmyeondong: address.eupmyeondong }) });
      else if (editing === "verifyEmail") await api("/api/auth/verify-email", { method: "POST", body: JSON.stringify({ email: value, emailProof: verified?.proof }) });
      else await api(`/api/users/${user.userId}/account`, { method: "PUT", body: JSON.stringify({ [editing]: value, currentPassword: password, emailProof: verified?.proof }) });
      if (editing === "newPassword") { await refreshUser().catch(() => {}); navigate("/loginform"); notify("비밀번호를 변경했어요. 다시 로그인해주세요."); }
      else { await changed(); notify("계정 정보를 저장했어요."); }
      setEditing(null); setAddress(null);
    } catch (failure) { setError(failure.message); } finally { setBusy(false); }
  }
  const labels = { email: "이메일", phone: "휴대폰 번호", newPassword: "새 비밀번호", verifyEmail: "이메일 인증" };
  return <div className="page"><BackLink fallback="/settings" label="설정으로 돌아가기" /><PageHeading title="내 계정" /><RequireLogin>{user && <>
    <section className="menu-section"><h2>내 정보</h2><div className="account-id"><span>아이디</span><strong>{user.username}</strong></div><MenuLink icon={UserRound} title="이메일" detail={user.email} onClick={() => edit("email")} /><MenuLink icon={UserRound} title="휴대폰 번호" detail={socialProvider ? "미등록" : user.phone} onClick={() => edit("phone")} /><MenuLink icon={LockKeyhole} title="비밀번호 변경" onClick={() => edit("newPassword")} /><MenuLink icon={MapPin} title="주소 관리" detail={user.address.sido || "미등록"} onClick={() => { setAddress(user.address); setError(""); }} /></section>
    <section className="menu-section"><h2>이메일 보안</h2>{user.emailVerified ? <p className="field-hint">인증된 이메일이에요.</p> : <button className="secondary-button full" onClick={() => edit("verifyEmail")}>현재 이메일 인증하기</button>}</section>
    {(editing || address) && <Modal title={address ? "주소 관리" : editing === "verifyEmail" ? labels[editing] : `${labels[editing]} 변경`} onClose={() => { setEditing(null); setAddress(null); }}><form className="form-stack" onSubmit={save}>
      {address ? <><label className="field-label">시·도<select value={address.sido} onChange={event => setAddress({ ...address, sido: event.target.value })}><option value="">선택</option>{regions.map(region => <option key={region}>{region}</option>)}</select></label><label className="field-label">시·군·구<input maxLength={50} value={address.sigungu} onChange={event => setAddress({ ...address, sigungu: event.target.value })} /></label><label className="field-label">읍·면·동<input maxLength={50} value={address.eupmyeondong} onChange={event => setAddress({ ...address, eupmyeondong: event.target.value })} /></label></> : editing === "verifyEmail" ? <EmailVerification key={editing} purpose="verify-email" email={value} readOnly onVerified={setVerified} /> : socialProvider ? <p>{socialProvider} 로그인 계정에는 숨길 비밀번호가 없어요. 비밀번호는 {socialProvider}에서 관리해주세요. 소셜 계정의 연락처 변경은 아직 지원하지 않아요.</p> : <>{editing === "newPassword" ? <PasswordField label="새 비밀번호" autoComplete="new-password" value={value} onChange={event => setValue(event.target.value)} /> : editing === "email" ? <EmailVerification key={editing} purpose="change-email" email={value} onEmailChange={setValue} onVerified={setVerified} /> : <label className="field-label">{labels[editing]}<input required type="tel" value={value} onChange={event => setValue(event.target.value)} /></label>}<PasswordField label="현재 비밀번호" value={password} onChange={event => setPassword(event.target.value)} /></>}
      {error && <p className="form-error" role="alert">{error}</p>}{(address || !socialProvider || editing === "verifyEmail") && <button className="primary-button" disabled={busy || (!address && ["email", "verifyEmail"].includes(editing) && !verified)}>{busy ? "저장 중..." : "저장"}</button>}
    </form></Modal>}
  </>}</RequireLogin></div>;
}

export function BadgePage() {
  const { user } = useApp();
  const progress = user?.progress;
  const [filter, setFilter] = useState("all");
  const [activity, setActivity] = useState("");
  const [editing, setEditing] = useState(false);
  const [selectedBadge, setSelectedBadge] = useState(null);
  const allBadges = progress?.badges || [];
  const groups = [...new Set(allBadges.map(badge => badge.group))];
  const badges = allBadges.filter(badge => (!activity || badge.group === activity) && (filter === "all" || (filter === "earned" ? badge.earned : !badge.earned)));
  return <div className="page badge-page"><BackLink /><PageHeading title="나의 배지" action={user && <button className="text-button" onClick={() => setEditing(true)}>전시 편집</button>} /><RequireLogin>{progress && <>
    <section className="level-section"><Award size={34} /><span>Lv.{progress.level}</span><h2>{progress.levelName}</h2><p>{progress.exp} EXP</p><progress max={Math.max(1, progress.nextLevelExp - progress.currentLevelExp)} value={progress.level === 5 ? 1 : progress.exp - progress.currentLevelExp} /><small>{progress.level === 5 ? "최고 레벨에 도달했어요" : `다음 레벨까지 ${progress.nextLevelExp - progress.exp} EXP`}</small></section>
    <div className="section-heading badge-summary"><h2>배지 컬렉션</h2><span>{allBadges.filter(badge => badge.earned).length} / {allBadges.length} 획득</span></div>
    <div className="segmented">{[["all", "전체"], ["earned", "획득"], ["locked", "미획득"]].map(([key, label]) => <button key={key} aria-pressed={filter === key} onClick={() => setFilter(key)}>{label}</button>)}</div>
    <div className="badge-group-filter" aria-label="배지 활동 종류">{["", ...groups].map(group => <button key={group} className="chip" aria-pressed={activity === group} onClick={() => setActivity(group)}>{group || "모든 활동"}</button>)}</div>
    <div className="badge-grid">{badges.map(badge => <BadgeCard key={badge.id} badge={badge} onOpen={setSelectedBadge} />)}</div>
    {badges.length === 0 && <EmptyState title="해당하는 배지가 없어요" />}
  </>}</RequireLogin>{editing && <ProfileEditor onClose={() => setEditing(false)} />}
    {selectedBadge && <Modal title="배지 이야기" onClose={() => setSelectedBadge(null)} className="badge-detail-dialog">
      <div className="badge-detail"><BadgeArtwork badge={selectedBadge} size="large" locked={!selectedBadge.earned} status />
        <span className={`badge-grade grade-${selectedBadge.grade.toLowerCase()}`}>{badgeGrades[selectedBadge.grade]} · {selectedBadge.group}</span>
        <h2>{selectedBadge.label}</h2><p>{selectedBadge.condition}</p>
        <span className="badge-detail-state">{selectedBadge.earned ? "내 컬렉션에 담긴 배지" : `${selectedBadge.count} / ${selectedBadge.target} 달성`}</span>
        <progress aria-label="배지 달성도" max={selectedBadge.target} value={selectedBadge.count} />
        <small>획득 보상 {selectedBadge.reward} EXP</small>
      </div>
      <div className="dialog-actions">{selectedBadge.earned ? <button className="primary-button" onClick={() => { setSelectedBadge(null); setEditing(true); }}>프로필에 전시하기</button> : <Link className="primary-button full" onClick={() => setSelectedBadge(null)} to={selectedBadge.activity === "diary" ? "/diary" : selectedBadge.group === "제보" ? "/mypage/submissions" : selectedBadge.activity === "review" ? "/mypage/recent" : "/search"}>활동하러 가기</Link>}</div>
    </Modal>}
  </div>;
}

const interests = ["바다", "산", "자연", "공원", "산책", "사진", "카페", "맛집", "역사", "박물관", "체험", "가족", "현장체험학습", "조용한 장소", "캠핑", "문화·예술", "관광지"];
export function InterestsPage() {
  const { user, changed, notify } = useApp();
  return <div className="page"><BackLink /><PageHeading title="여행 관심사" /><RequireLogin>{user && <><InterestForm key={user.userId} user={user} saved={async () => { await changed(); notify("관심사를 저장했어요."); }} /><Link to="/onboarding?edit=1" className="back-link" onClick={() => saveAuthIntent({ returnTo: "/mypage/interests" })}>혼잡도·관심 지역 설정</Link></>}</RequireLogin></div>;
}
function InterestForm({ user, saved }) {
  const [selected, setSelected] = useState((user.interests || []).map(value => travelChoices.find(choice => choice.id === value)?.label || value));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  return <form className="form-stack" onSubmit={async event => { event.preventDefault(); setBusy(true); try { await api(`/api/users/${user.userId}/settings`, { method: "PUT", body: JSON.stringify({ interestsJson: JSON.stringify(selected) }) }); await saved(); } catch (e) { setError(e.message); } finally { setBusy(false); } }}><div className="interest-grid">{interests.map(item => <button className="chip" type="button" aria-pressed={selected.includes(item)} key={item} onClick={() => setSelected(selected.includes(item) ? selected.filter(value => value !== item) : [...selected, item])}>{item}</button>)}</div>{error && <p className="form-error">{error}</p>}<button className="primary-button" disabled={busy}>저장</button></form>;
}

export function FollowPage({ type }) {
  const { user, revision, changed, notify } = useApp();
  const [query, setQuery] = useState("");
  const people = useResource(user ? query.trim() ? `/api/people?query=${encodeURIComponent(query.trim())}` : `/api/users/${user.userId}/${type}` : null, revision);
  return <div className="page"><BackLink /><PageHeading title={type === "followers" ? "팔로워" : "팔로잉"} /><RequireLogin>
    <label className="search-field"><Search size={18} /><input value={query} onChange={event => setQuery(event.target.value)} placeholder="닉네임 또는 아이디 검색" maxLength={30} /></label>
    {people.loading ? <Loading /> : people.error ? <ErrorState message={people.error} /> : <div className="people-list">{people.data?.map(person => <div className="person-row" key={person.userId}><Avatar user={person} size="small" /><div><strong>{person.nickname}</strong><small>@{person.username}</small></div>{person.userId !== user.userId && <button className="secondary-button" onClick={async () => { try { await api(`/api/users/${user.userId}/following/${person.userId}`, { method: person.following ? "DELETE" : "POST" }); await changed(); } catch (e) { notify(e.message); } }}>{person.following ? "팔로잉" : "팔로우"}</button>}</div>)}{people.data?.length === 0 && <EmptyState title="아직 목록이 없어요" />}</div>}
  </RequireLogin></div>;
}

export function AboutPage() {
  return <div className="page"><BackLink /><PageHeading title="앱 정보" />
    <div className="about-brand"><span className="brand-mark">S</span><h2>Soomgil</h2><p>발견하고, 머무르고, 기록하는 여행</p></div>
    <section className="menu-section"><h2>장소 정보 안내</h2><p>관광공사에서 수집한 장소 정보와 사용자 제보를 제공합니다. 운영시간, 반려동물 동반 여부, 접근성은 방문 전에 해당 시설에 확인해주세요.</p><p>확인되지 않은 평점과 혼잡도는 ‘확인 중’으로 표시합니다. 주변 거리는 직선거리이며 실제 이동 거리와 다를 수 있습니다.</p><p>참고 이미지는 실제 장소 사진과 다를 수 있습니다.</p></section>
    <section className="menu-section"><h2>정보 출처</h2><a href="https://korean.visitkorea.or.kr/" target="_blank" rel="noreferrer">한국관광공사 대한민국 구석구석</a><p>지도: OpenStreetMap 기여자</p>
      <p>배지·지도 아이콘: <a href="https://github.com/microsoft/fluentui-emoji" target="_blank" rel="noreferrer">Microsoft Fluent Emoji</a></p>
      <p>Copyright (c) Microsoft Corporation. <a href="/assets/badges/LICENSE.txt" target="_blank" rel="noreferrer">MIT 라이선스</a></p>
      <p>지도 마커 묶음: Leaflet.markercluster · <a href="/licenses/leaflet-markercluster.txt" target="_blank" rel="noreferrer">MIT 라이선스</a></p>
    </section>
  </div>;
}
