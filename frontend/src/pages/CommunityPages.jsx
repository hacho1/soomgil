import { useState } from "react";
import { Link, useLocation, useNavigate, useParams } from "react-router-dom";
import { Bell, Bold, BookOpen, Check, CheckCheck, ChevronRight, ImagePlus, Inbox, MapPin, MessageCircle, Pencil, Plus, Save, ShieldCheck, Trash2, Type, X } from "lucide-react";
import { api, dateLabel, readLocal, writeLocal } from "../lib/api";
import { resizeImage } from "../lib/images";
import PlaceSyncPanel from "../components/PlaceSyncPanel";
import OperationsPanel from "../components/OperationsPanel";
import { useApp, useResource } from "../lib/context";
import { BackLink, EmptyState, ErrorState, IconButton, Loading, Modal, PageHeading, RequireLogin } from "../components/ui";

export function NoticesPage() {
  const { id } = useParams();
  const { revision } = useApp();
  const notices = useResource(id ? `/api/notices/${id}` : "/api/notices", revision);
  const location = useLocation();
  return <div className="page"><BackLink fallback={id ? "/notices" : "/mypage"} label={id ? "공지사항으로 돌아가기" : undefined} /><PageHeading title="공지사항" />{notices.loading ? <Loading /> : notices.error ? <ErrorState message={notices.error} /> : id ? <article className="notice-detail"><span className="notice-tag">공지</span><h2>{notices.data.title}</h2><time>{dateLabel(notices.data.createdAt)}</time>{notices.data.imageUrl && <img src={notices.data.imageUrl} alt="공지 첨부 이미지" />}<div className={`notice-body ${notices.data.bodySize === "large" ? "large" : ""} ${notices.data.bodyWeight === "bold" ? "bold" : ""}`}>{notices.data.body}</div></article> : <div className="notice-list">{notices.data?.map(notice => <Link key={notice.id} to={`/notices/${notice.id}`} state={{ returnTo: location.pathname }}><span className="notice-tag">공지</span><div><h2>{notice.title}</h2><time>{dateLabel(notice.createdAt)}</time></div><ChevronRight size={18} /></Link>)}{notices.data?.length === 0 && <EmptyState title="새로운 공지가 없어요" />}</div>}</div>;
}

export function NotificationsPage() {
  const { user, notifications, refreshPrivateData, notify } = useApp();
  const navigate = useNavigate();
  async function read(note) {
    try {
      await api(`/api/users/${user.userId}/notifications/${note.id}/read`, { method: "PUT" });
      await refreshPrivateData();
      const destinations = { notice: `/notices/${note.referenceId}`, badge: "/mypage/badges", inquiry: "/mypage/inquiries", "place-submission": "/mypage/submissions", user: "/mypage/followers" };
      navigate(destinations[note.referenceType] || "/mypage", { state: { returnTo: "/notifications", returnLabel: "알림으로 돌아가기" } });
    } catch (error) { notify(error.message); }
  }
  return <div className="page"><PageHeading title="알림" action={user && notifications.some(note => !note.read) && <button className="text-button" onClick={async () => { try { await api(`/api/users/${user.userId}/notifications/read`, { method: "PUT" }); await refreshPrivateData(); } catch (error) { notify(error.message); } }}><CheckCheck size={16} />모두 읽음</button>} /><RequireLogin><div className="notification-list">{notifications.map(note => <button key={note.id} className={note.read ? "" : "unread"} onClick={() => read(note)}><span className="notification-symbol"><Bell size={21} /></span><div><strong>{note.title}</strong><p>{note.message}</p><time>{dateLabel(note.createdAt)}</time></div>{!note.read && <i />}</button>)}{notifications.length === 0 && <EmptyState title="도착한 알림이 없어요" />}</div></RequireLogin></div>;
}

export function InquiriesPage() {
  const { user, revision, changed, notify } = useApp();
  const resource = useResource(user ? `/api/users/${user.userId}/inquiries` : null, revision);
  const [editing, setEditing] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  async function save(event) {
    event.preventDefault(); if (busy) return; setBusy(true); setError("");
    try { await api(`/api/users/${user.userId}/inquiries${editing.id ? `/${editing.id}` : ""}`, { method: editing.id ? "PUT" : "POST", body: JSON.stringify(editing) }); await changed(); setEditing(null); notify("문의를 보냈어요."); }
    catch (failure) { setError(failure.message); } finally { setBusy(false); }
  }
  return <div className="page"><BackLink /><PageHeading title="문의하기" action={user && <IconButton label="문의 작성" onClick={() => { setEditing({ title: "", body: "" }); setError(""); }}><Plus size={23} /></IconButton>} /><RequireLogin>{resource.loading ? <Loading /> : resource.error ? <ErrorState message={resource.error} /> : <div className="board-list">{resource.data?.map(item => <article className="board-item" key={item.id}><div className="board-meta"><span className={`status ${item.status === "답변완료" ? "approved" : ""}`}>{item.status}</span><time>{dateLabel(item.createdAt)}</time></div><h2>{item.title}</h2><p>{item.body}</p>{item.adminReply && <div className="admin-reply"><strong>숨길의 답변</strong><p>{item.adminReply}</p></div>}<div className="button-row">{item.status !== "답변완료" && <button className="text-button" onClick={() => { setEditing(item); setError(""); }}><Pencil size={14} />수정</button>}<button className="text-button danger" onClick={async () => { try { await api(`/api/users/${user.userId}/inquiries/${item.id}`, { method: "DELETE" }); await changed(); } catch (e) { notify(e.message); } }}><Trash2 size={14} />삭제</button></div></article>)}{resource.data?.length === 0 && <EmptyState title="무엇을 도와드릴까요?" action={<button className="primary-button" onClick={() => setEditing({ title: "", body: "" })}>문의 작성</button>}>불편한 점이나 여행 아이디어를 보내주세요.</EmptyState>}</div>}</RequireLogin>
    {editing && <Modal title={editing.id ? "문의 수정" : "문의 작성"} onClose={() => setEditing(null)}><form className="form-stack" onSubmit={save}><label className="field-label">제목<input required maxLength={150} value={editing.title} onChange={event => setEditing({ ...editing, title: event.target.value })} /></label><label className="field-label">내용<textarea required maxLength={3000} value={editing.body} onChange={event => setEditing({ ...editing, body: event.target.value })} /></label>{error && <p className="form-error">{error}</p>}<button className="primary-button" disabled={busy}>{busy ? "전송 중..." : "보내기"}</button></form></Modal>}
  </div>;
}

export function SubmissionPage() {
  const { user, revision, changed, notify } = useApp();
  const submissions = useResource(user ? `/api/users/${user.userId}/place-submissions` : null, revision);
  const [form, setForm] = useState(null), [busy, setBusy] = useState(false), [error, setError] = useState("");
  return <div className="page"><BackLink /><PageHeading title="숨은 장소 제보" action={user && <IconButton label="장소 제보하기" onClick={() => setForm({ name: "", address: "", category: "tour", description: "" })}><Plus size={22} /></IconButton>} /><RequireLogin>{submissions.loading ? <Loading /> : submissions.error ? <ErrorState message={submissions.error} /> : <div className="board-list">{submissions.data?.map(item => <article className="board-item" key={item.id}><div className="board-meta"><span className={`status ${item.status === "승인" ? "approved" : item.status === "반려" ? "rejected" : ""}`}>{item.status}</span><time>{dateLabel(item.createdAt)}</time></div><h2>{item.name}</h2><p>{item.address}</p><p>{item.description}</p></article>)}{submissions.data?.length === 0 && <EmptyState title="나만 아는 장소가 있나요?" action={<button className="primary-button" onClick={() => setForm({ name: "", address: "", category: "tour", description: "" })}>첫 장소 제보하기</button>} />}</div>}</RequireLogin>
    {form && <Modal title="장소 제보" onClose={() => setForm(null)}><form className="form-stack" onSubmit={async event => { event.preventDefault(); if (busy) return; setBusy(true); setError(""); try { await api("/api/place-submissions", { method: "POST", body: JSON.stringify({ ...form, categoryLabel: { tour: "관광지", food: "음식점", cafe: "카페", walk: "산책길", camping: "캠핑" }[form.category] }) }); await changed(); setForm(null); notify("제보를 보냈어요. 검토 후 알림으로 알려드릴게요."); } catch (e) { setError(e.message); } finally { setBusy(false); } }}><label className="field-label">장소명<input required maxLength={150} value={form.name} onChange={event => setForm({ ...form, name: event.target.value })} /></label><label className="field-label">주소<input required maxLength={600} value={form.address} onChange={event => setForm({ ...form, address: event.target.value })} /></label><label className="field-label">종류<select value={form.category} onChange={event => setForm({ ...form, category: event.target.value })}>{Object.entries({ tour: "관광지", food: "음식점", cafe: "카페", walk: "산책길", camping: "캠핑" }).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label><label className="field-label">추천하는 이유<textarea required maxLength={2000} value={form.description} onChange={event => setForm({ ...form, description: event.target.value })} /></label>{error && <p className="form-error">{error}</p>}<button className="primary-button" disabled={busy}>제보 보내기</button></form></Modal>}
  </div>;
}

export function AdminPage() {
  const { user, authLoading } = useApp();
  const [tab, setTab] = useState("notices");
  if (authLoading) return <div className="page"><Loading /></div>;
  if (!user || user.role !== "ADMIN") return <div className="auth-page"><Link to="/" className="back-link">숨길로 돌아가기</Link><ShieldCheck size={40} /><h1>숨길 관리자</h1><p>관리자 계정으로 로그인해주세요.</p><Link className="primary-button" to="/loginform" state={{ returnTo: "/admin.html" }}>로그인</Link></div>;
  return <div className="admin-layout"><aside><Link className="brand" to="/"><span className="brand-mark">S</span>Soomgil</Link><span className="admin-label">관리자</span><nav>{[["notices", "공지사항", BookOpen], ["submissions", "장소 제보", MapPin], ["inquiries", "사용자 문의", Inbox], ["place-sync", "장소 데이터", MapPin], ["operations", "운영·백업", ShieldCheck]].map(([key, label, Icon]) => <button className={tab === key ? "active" : ""} key={key} onClick={() => setTab(key)}><Icon size={20} />{label}</button>)}</nav><Link to="/mypage">서비스로 돌아가기</Link></aside><section className="admin-content">{tab === "place-sync" ? <PlaceSyncPanel /> : tab === "operations" ? <OperationsPanel /> : <AdminBoard key={tab} type={tab} />}</section></div>;
}

function AdminBoard({ type }) {
  const { notify, changed, reloadCatalog } = useApp();
  const [version, setVersion] = useState(0), [editing, setEditing] = useState(null), [busy, setBusy] = useState(false), [error, setError] = useState("");
  const path = type === "notices" ? "/api/notices" : type === "submissions" ? "/api/admin/place-submissions" : "/api/admin/inquiries";
  const data = useResource(path, version);
  const labels = { notices: "공지사항 관리", submissions: "장소 제보 관리", inquiries: "사용자 문의" };
  async function mutate(url, options) {
    if (busy) return false; setBusy(true); setError("");
    try { await api(url, options); setVersion(v => v + 1); await changed(); await reloadCatalog(); return true; }
    catch (failure) { setError(failure.message); return false; } finally { setBusy(false); }
  }
  const startNotice = () => setEditing(readLocal(`soomgilNoticeDraft:${userId()}`, { title: "", body: "", imageUrl: "", bodySize: "normal", bodyWeight: "normal" }));
  function userId() { return "admin"; }
  return <><PageHeading title={labels[type]} action={type === "notices" && <button className="primary-button" onClick={startNotice}><Plus size={18} />공지 작성</button>} />{error && <p className="form-error" role="alert">{error}</p>}{data.loading ? <Loading /> : data.error ? <ErrorState message={data.error} retry={() => setVersion(version + 1)} /> : <div className="admin-board">{data.data?.map(item => <article className="board-item" key={item.id}><div className="board-meta"><span className="status">{item.status || "게시됨"}</span><time>{dateLabel(item.createdAt)}</time></div><h2>{item.title || item.name}</h2><p>{item.address || item.body}</p>{item.description && <p>{item.description}</p>}{item.adminReply && <p className="admin-reply">{item.adminReply}</p>}<div className="button-row">
    <button className="secondary-button" onClick={() => { setEditing(item); setError(""); }}><Pencil size={16} />{type === "inquiries" ? "답변" : "수정"}</button>
    {type === "submissions" ? <><button className="secondary-button" disabled={busy || item.status === "승인"} onClick={() => mutate(`/api/admin/place-submissions/${item.id}`, { method: "PUT", body: JSON.stringify({ status: "승인" }) })}><Check size={16} />승인</button><button className="secondary-button danger" disabled={busy || item.status === "반려"} onClick={() => mutate(`/api/admin/place-submissions/${item.id}`, { method: "PUT", body: JSON.stringify({ status: "반려" }) })}><X size={16} />반려</button></> : <button className="secondary-button danger" disabled={busy} onClick={() => mutate(`/api/admin/${type}/${item.id}`, { method: "DELETE" })}><Trash2 size={16} />삭제</button>}
    </div></article>)}{data.data?.length === 0 && <EmptyState title="등록된 항목이 없어요" />}</div>}
    {editing && <Modal title={type === "notices" ? "공지 작성" : type === "inquiries" ? "문의 답변" : "제보 수정"} onClose={() => setEditing(null)} className="admin-editor-dialog"><form className="form-stack" onSubmit={async event => { event.preventDefault(); const endpoint = type === "notices" ? `/api/admin/notices${editing.id ? `/${editing.id}` : ""}` : type === "submissions" ? `/api/admin/place-submissions/${editing.id}` : `/api/admin/inquiries/${editing.id}/reply`; const body = type === "inquiries" ? { reply: editing.adminReply } : editing; const ok = await mutate(endpoint, { method: type === "notices" && !editing.id ? "POST" : "PUT", body: JSON.stringify(body) }); if (ok) { if (type === "notices") writeLocal("soomgilNoticeDraft:admin", null); setEditing(null); notify("저장했어요."); } }}>
      {type === "inquiries" ? <><h3>{editing.title}</h3><p>{editing.body}</p><label className="field-label">답변<textarea required maxLength={3000} value={editing.adminReply || ""} onChange={event => setEditing({ ...editing, adminReply: event.target.value })} /></label></> : type === "submissions" ? <><label className="field-label">장소명<input required value={editing.name} onChange={event => setEditing({ ...editing, name: event.target.value })} /></label><label className="field-label">주소<input required value={editing.address} onChange={event => setEditing({ ...editing, address: event.target.value })} /></label><label className="field-label">설명<textarea value={editing.description} onChange={event => setEditing({ ...editing, description: event.target.value })} /></label></> : <>
        <label className="field-label">제목<input required maxLength={150} value={editing.title} onChange={event => setEditing({ ...editing, title: event.target.value })} /></label><div className="notice-toolbar"><label className="icon-button" title="이미지 첨부"><ImagePlus size={19} /><input hidden type="file" accept="image/jpeg,image/png,image/webp" onChange={async event => { try { setEditing({ ...editing, imageUrl: await resizeImage(event.target.files[0]) }); } catch (failure) { setError(failure.message); } }} /></label><IconButton label="굵게" aria-pressed={editing.bodyWeight === "bold"} onClick={() => setEditing({ ...editing, bodyWeight: editing.bodyWeight === "bold" ? "normal" : "bold" })}><Bold size={18} /></IconButton><IconButton label="큰 글씨" aria-pressed={editing.bodySize === "large"} onClick={() => setEditing({ ...editing, bodySize: editing.bodySize === "large" ? "normal" : "large" })}><Type size={19} /></IconButton></div>{editing.imageUrl && <div className="attachment-preview"><img src={editing.imageUrl} alt="첨부 이미지" /><IconButton label="첨부 이미지 삭제" onClick={() => setEditing({ ...editing, imageUrl: "" })}><X size={18} /></IconButton></div>}<label className="sr-only" htmlFor="notice-body">공지 내용</label><textarea id="notice-body" className="notice-compose" required maxLength={4000} value={editing.body} style={{ fontSize: editing.bodySize === "large" ? 20 : 16, fontWeight: editing.bodyWeight === "bold" ? 700 : 400 }} onChange={event => setEditing({ ...editing, body: event.target.value })} placeholder="공지 내용을 작성해주세요." />
      </>}{error && <p className="form-error">{error}</p>}<div className="dialog-actions">{type === "notices" && <button className="secondary-button" type="button" onClick={() => { writeLocal("soomgilNoticeDraft:admin", editing); notify("이 기기에 임시저장했어요."); }}><Save size={16} />임시저장</button>}<button className="primary-button" disabled={busy}>{busy ? "저장 중..." : editing.id ? "수정 완료" : "등록"}</button></div>
    </form></Modal>}
  </>;
}
