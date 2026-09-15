import { useEffect, useState } from "react";
import { Link, useBlocker, useLocation, useNavigate } from "react-router-dom";
import { ArrowLeft, BookOpen, CalendarDays, ChevronLeft, ChevronRight, Cloud, CloudRain, CloudSnow, ImagePlus, Palette, Pencil, Plus, Search, Settings, Sun, Trash2, Type, X } from "lucide-react";
import { api, dateLabel, today } from "../lib/api";
import { monthCells } from "../lib/calendar";
import { resizeImage } from "../lib/images";
import { useApp, useResource } from "../lib/context";
import { BackLink, EmptyState, ErrorState, IconButton, Loading, Modal, PageHeading, RequireLogin } from "../components/ui";

const weather = { sun: { label: "맑음", icon: Sun }, cloud: { label: "흐림", icon: Cloud }, rain: { label: "비", icon: CloudRain }, snow: { label: "눈", icon: CloudSnow } };
export default function DiaryPage() {
  const { user, revision } = useApp();
  const location = useLocation();
  const data = useResource(user ? `/api/users/${user.userId}/diaries` : null, revision);
  const mode = location.pathname.split("/")[2] || "list";
  const editing = location.pathname.endsWith("/edit") || mode === "new";
  const entry = data.data?.find(item => String(item.id) === mode);
  return <div className="diary-shell">{!user && <div className="page diary-guest-heading"><BackLink /><PageHeading title="여행 다이어리" /></div>}<RequireLogin>{data.loading ? <Loading /> : data.error ? <ErrorState message={data.error} /> : editing ? (mode === "new" || entry ? <DiaryEditor key={entry?.id || "new"} entry={entry} /> : <EmptyState title="기록을 찾을 수 없어요" />) : entry ? <DiaryDetail entry={entry} /> : ["list", "calendar", "settings"].includes(mode) ? <DiaryHome mode={mode} entries={data.data || []} /> : <EmptyState title="기록을 찾을 수 없어요" />}</RequireLogin></div>;
}

function DiaryHome({ mode, entries }) {
  const location = useLocation();
  const [searchOpen, setSearchOpen] = useState(false), [query, setQuery] = useState("");
  const date = new Date();
  const [month, setMonth] = useState(new Date(date.getFullYear(), date.getMonth(), 1));
  const [selected, setSelected] = useState(today());
  const year = month.getFullYear(), monthNumber = month.getMonth();
  const filtered = entries.filter(entry => `${entry.title} ${entry.body} ${entry.placeName}`.includes(query));
  const onDate = entries.filter(entry => entry.visitedDate.slice(0, 10) === selected);
  const list = values => <div className="diary-list">{values.map(entry => <Link to={`/diary/${entry.id}`} state={{ returnTo: location.pathname }} key={entry.id}><div className="diary-list-date"><strong>{Number(entry.visitedDate.slice(8, 10))}</strong><small>{entry.visitedDate.slice(0, 7).replace("-", ". ")}</small></div><div><h2>{entry.title}</h2><p>{entry.body}</p>{entry.placeName && <span>{entry.placeName}</span>}</div>{entry.imageUrl && <img src={entry.imageUrl} alt="여행 사진" />}</Link>)}</div>;
  return <><div className="page"><BackLink /><PageHeading title="여행 다이어리" action={<IconButton label="기록 검색" onClick={() => setSearchOpen(!searchOpen)}><Search size={22} /></IconButton>} />{searchOpen && <label className="search-field"><Search size={18} /><input autoFocus aria-label="다이어리 검색" value={query} onChange={event => setQuery(event.target.value)} placeholder="기억하고 싶은 여행" /></label>}
    {mode === "list" && <>{filtered.length ? list(filtered) : <EmptyState title="여행의 한 장면을 남겨보세요" action={<Link className="primary-button" to="/diary/new">첫 기록 쓰기</Link>} />}</>}
    {mode === "calendar" && <><div className="calendar-heading"><IconButton label="이전 달" onClick={() => setMonth(new Date(year, monthNumber - 1, 1))}><ChevronLeft size={21} /></IconButton><h2>{year}년 {monthNumber + 1}월</h2><IconButton label="다음 달" onClick={() => setMonth(new Date(year, monthNumber + 1, 1))}><ChevronRight size={21} /></IconButton></div><div className="calendar-grid">{["일", "월", "화", "수", "목", "금", "토"].map(day => <span className="weekday" key={day}>{day}</span>)}{monthCells(year, monthNumber).map((cell, index) => cell ? <button key={cell.date} aria-label={`${cell.date}${entries.some(entry => entry.visitedDate.slice(0, 10) === cell.date) ? " 기록 있음" : ""}`} aria-pressed={cell.date === selected} className={cell.date === today() ? "today" : ""} onClick={() => setSelected(cell.date)}>{cell.day}{entries.some(entry => entry.visitedDate.slice(0, 10) === cell.date) && <i />}</button> : <span key={`empty-${index}`} />)}</div><h3 className="calendar-date-title">{dateLabel(selected)}</h3>{onDate.length ? list(onDate) : <EmptyState title="이날의 기록이 없어요" />}</>}
    {mode === "settings" && <section className="menu-section"><h2>나의 기록</h2><p>다이어리는 본인만 볼 수 있어요.</p><p>기록 {entries.length}개 · 사진 {entries.filter(entry => entry.imageUrl).length}장</p></section>}
  </div><Link className="floating-write" to="/diary/new" aria-label="다이어리 쓰기"><Plus size={26} /></Link><nav className="diary-nav">{[["list", "/diary", "기록한 여행", BookOpen], ["calendar", "/diary/calendar", "달력", CalendarDays], ["settings", "/diary/settings", "설정", Settings]].map(([key, to, title, Icon]) => <Link key={key} className={mode === key ? "active" : ""} to={to}><Icon size={22} /><span>{title}</span></Link>)}</nav></>;
}

function DiaryDetail({ entry }) {
  const { user, changed, notify } = useApp();
  const navigate = useNavigate();
  const [confirm, setConfirm] = useState(false), [busy, setBusy] = useState(false);
  const Weather = weather[entry.weatherIcon]?.icon || Sun;
  return <><div className="diary-reading" style={{ background: entry.pageColor, fontFamily: entry.fontFamily }}><div className="editor-top"><Link className="icon-button" to="/diary" aria-label="기록 목록"><ArrowLeft size={22} /></Link><div className="button-row"><Link className="icon-button" to={`/diary/${entry.id}/edit`} aria-label="다이어리 수정"><Pencil size={20} /></Link><IconButton label="다이어리 삭제" onClick={() => setConfirm(true)}><Trash2 size={20} /></IconButton></div></div><div className="diary-date"><time>{dateLabel(entry.visitedDate)}</time><Weather size={22} aria-label={weather[entry.weatherIcon]?.label} /></div><h1>{entry.title}</h1>{entry.placeName && <p>{entry.placeName}</p>}{entry.imageUrl && <img className="diary-photo" src={entry.imageUrl} alt="여행 사진" />}<div className="diary-body">{entry.body}</div>{entry.drawingImage && <img className="diary-photo" src={entry.drawingImage} alt="여행 그림" />}</div>
    {confirm && <Modal title="이 기록을 삭제할까요?" onClose={() => setConfirm(false)}><p>삭제한 기록은 되돌릴 수 없어요.</p><div className="dialog-actions"><button className="secondary-button" onClick={() => setConfirm(false)}>취소</button><button className="primary-button danger" disabled={busy} onClick={async () => { setBusy(true); try { await api(`/api/users/${user.userId}/diaries/${entry.id}`, { method: "DELETE" }); await changed(); navigate("/diary", { replace: true }); notify("기록을 삭제했어요."); } catch (e) { notify(e.message); } finally { setBusy(false); } }}>삭제</button></div></Modal>}
  </>;
}

function DiaryEditor({ entry }) {
  const { user, changed, notify } = useApp();
  const navigate = useNavigate();
  const [form, setForm] = useState(entry || { title: "", body: "", placeName: "", visitedDate: today(), weatherIcon: "sun", pageColor: "#ffffff", fontFamily: "sans-serif", imageUrl: "" });
  const [menu, setMenu] = useState(null), [busy, setBusy] = useState(false), [error, setError] = useState("");
  const [dirty, setDirty] = useState(false);
  const blocker = useBlocker(({ currentLocation, nextLocation }) => dirty && !busy && currentLocation.pathname !== nextLocation.pathname);
  function change(key, value) { setDirty(true); setForm(current => ({ ...current, [key]: value })); }
  useEffect(() => { const warn = event => { if (dirty) { event.preventDefault(); event.returnValue = ""; } }; window.addEventListener("beforeunload", warn); return () => window.removeEventListener("beforeunload", warn); }, [dirty]);
  const back = () => navigate(entry ? `/diary/${entry.id}` : "/diary");
  async function save() {
    if (busy || !form.title.trim()) { if (!form.title.trim()) setError("제목을 입력해주세요."); return; }
    setBusy(true); setError("");
    try { const result = await api(`/api/users/${user.userId}/diaries${entry ? `/${entry.id}` : ""}`, { method: entry ? "PUT" : "POST", body: JSON.stringify(form) }); setDirty(false); await changed(); navigate(`/diary/${result.diary.id}`, { replace: true }); notify("여행 기록을 저장했어요."); }
    catch (failure) { setError(failure.message); } finally { setBusy(false); }
  }
  return <><div className="diary-editor" style={{ background: form.pageColor, fontFamily: form.fontFamily }}>
    <div className="editor-top"><IconButton label="작성 나가기" onClick={back}><ArrowLeft size={23} /></IconButton><button className="primary-button" disabled={busy} onClick={save}>{busy ? "저장 중..." : "저장"}</button></div>
    {error && <p role="alert" className="form-error">{error}</p>}
    <div className="diary-date"><input aria-label="여행 날짜" required type="date" value={form.visitedDate.slice(0, 10)} onChange={event => change("visitedDate", event.target.value)} /><select aria-label="날씨" value={form.weatherIcon} onChange={event => change("weatherIcon", event.target.value)}>{Object.entries(weather).map(([key, info]) => <option key={key} value={key}>{info.label}</option>)}</select></div>
    <input aria-label="다이어리 제목" className="diary-title-input" maxLength={150} value={form.title} onChange={event => change("title", event.target.value)} placeholder="오늘의 여행 제목" /><input aria-label="방문한 장소" className="diary-place-input" maxLength={200} value={form.placeName} onChange={event => change("placeName", event.target.value)} placeholder="방문한 장소" />{form.imageUrl && <div className="attachment-preview"><img src={form.imageUrl} className="diary-photo" alt="여행 사진" /><IconButton label="사진 삭제" onClick={() => change("imageUrl", "")}><X size={18} /></IconButton></div>}
    <textarea aria-label="다이어리 본문" className="diary-body-input" maxLength={3000} value={form.body} onChange={event => change("body", event.target.value)} placeholder="기억하고 싶은 순간을 적어보세요." /></div>
    <div className="diary-editor-tools">{menu && <div className="diary-tool-panel">{menu === "theme" ? <div className="swatches">{["#ffffff", "#eaf4f0", "#edf1fa", "#fcf0f2", "#f1edf8"].map(color => <button key={color} style={{ background: color }} aria-label={`${color} 페이지 색상`} aria-pressed={form.pageColor === color} onClick={() => change("pageColor", color)} />)}</div> : <select aria-label="글꼴" value={form.fontFamily} onChange={event => change("fontFamily", event.target.value)}><option value="sans-serif">기본체</option><option value="serif">명조체</option><option value="monospace">타자기체</option></select>}</div>}<div className="editor-toolbar"><IconButton label="테마 펼치기" aria-expanded={menu === "theme"} onClick={() => setMenu(menu === "theme" ? null : "theme")}><Palette size={22} /></IconButton><label className="icon-button" title="사진 삽입"><ImagePlus size={22} /><input type="file" hidden accept="image/jpeg,image/png,image/webp" aria-label="사진 삽입" onChange={async event => { try { change("imageUrl", await resizeImage(event.target.files[0])); setError(""); } catch (e) { setError(e.message); } event.target.value = ""; }} /></label><IconButton label="글꼴 펼치기" aria-expanded={menu === "font"} onClick={() => setMenu(menu === "font" ? null : "font")}><Type size={22} /></IconButton></div></div>
    {blocker.state === "blocked" && <Modal title="작성을 나갈까요?" onClose={() => blocker.reset()}><p>아직 저장하지 않은 내용이 있어요.</p><div className="dialog-actions"><button className="secondary-button" onClick={() => blocker.reset()}>계속 작성</button><button className="primary-button" onClick={() => blocker.proceed()}>나가기</button></div></Modal>}
  </>;
}
