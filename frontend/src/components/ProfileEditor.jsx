import { useState } from "react";
import Cropper from "react-easy-crop";
import { Camera, RotateCcw, RotateCw, ZoomIn } from "lucide-react";
import { api } from "../lib/api";
import { readImage, cropImage } from "../lib/images";
import { useApp } from "../lib/context";
import { Avatar, IconButton, Modal } from "./ui";
import BadgeArtwork from "./BadgeArtwork";

export default function ProfileEditor({ onClose }) {
  const { user, changed, notify } = useApp();
  const [form, setForm] = useState({ nickname: user.nickname, profileImage: user.profileImage, profileBorder: user.profileBorder, displayBadgeIds: user.displayBadgeIds });
  const [source, setSource] = useState("");
  const [crop, setCrop] = useState({ x: 0, y: 0 });
  const [area, setArea] = useState(null);
  const [zoom, setZoom] = useState(1);
  const [rotation, setRotation] = useState(0);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  async function fileChanged(event) {
    try { setSource(await readImage(event.target.files?.[0])); setCrop({ x: 0, y: 0 }); setArea(null); setZoom(1); setRotation(0); setError(""); }
    catch (failure) { setError(failure.message); }
    event.target.value = "";
  }
  async function save(event) {
    event.preventDefault(); if (busy || (source && !area)) return;
    setBusy(true); setError("");
    try {
      const image = source ? await cropImage(source, area, rotation) : form.profileImage;
      await api(`/api/users/${user.userId}/settings`, { method: "PUT", body: JSON.stringify({ ...form, profileImage: image, displayBadgeIdsJson: JSON.stringify(form.displayBadgeIds) }) });
      await changed(); onClose(); notify("프로필을 저장했어요.");
    } catch (failure) { setError(failure.message); } finally { setBusy(false); }
  }
  const earned = user.progress?.badges?.filter(badge => badge.earned) || [];
  return <Modal title="프로필 수정" onClose={onClose} className="profile-dialog"><form className="form-stack" onSubmit={save}>
    <div className="profile-photo-editor">{source ? <div className="crop-area"><Cropper image={source} crop={crop} zoom={zoom} rotation={rotation} aspect={1} cropShape="round" showGrid onCropChange={setCrop} onZoomChange={setZoom} onRotationChange={setRotation} onCropComplete={(_, pixels) => setArea(pixels)} /></div> : <Avatar user={form} size="large" />}
      <label className="secondary-button photo-upload"><Camera size={18} />사진 변경<input type="file" accept="image/jpeg,image/png,image/webp" hidden onChange={fileChanged} /></label>
    </div>
    {source && <div className="crop-controls"><label><ZoomIn size={18} /><input aria-label="사진 확대" type="range" min="1" max="3" step="0.05" value={zoom} onChange={event => setZoom(Number(event.target.value))} /></label><div className="button-row"><IconButton label="왼쪽 90도 회전" onClick={() => setRotation(rotation - 90)}><RotateCcw size={20} /></IconButton><input aria-label="사진 회전" type="range" min="-180" max="180" value={rotation} onChange={event => setRotation(Number(event.target.value))} /><IconButton label="오른쪽 90도 회전" onClick={() => setRotation(rotation + 90)}><RotateCw size={20} /></IconButton></div></div>}
    <div className="avatar-choices">{["👤", "🌊", "⛰️", "☕", "🌿"].map(emoji => <button type="button" key={emoji} aria-label={`${emoji} 프로필 선택`} aria-pressed={!source && form.profileImage === emoji} onClick={() => { setSource(""); setForm({ ...form, profileImage: emoji }); }}>{emoji}</button>)}</div>
    <label className="field-label">닉네임<input required minLength={2} maxLength={16} value={form.nickname} onChange={event => setForm({ ...form, nickname: event.target.value })} /></label>
    <fieldset><legend>프로필 테두리</legend><div className="swatches">{["#0d9488", "#3679d8", "#a855a3", "#df735f", "#697580"].map(color => <button key={color} type="button" style={{ background: color }} aria-label={`${color} 테두리`} aria-pressed={form.profileBorder === color} onClick={() => setForm({ ...form, profileBorder: color })} />)}</div></fieldset>
    <fieldset><legend>전시 배지 <span>{form.displayBadgeIds.length}/3</span></legend><div className="badge-select">{earned.map(badge => <button type="button" key={badge.id} aria-pressed={form.displayBadgeIds.includes(badge.id)} onClick={() => {
      const selected = form.displayBadgeIds.includes(badge.id);
      if (!selected && form.displayBadgeIds.length >= 3) { setError("배지는 최대 3개까지 전시할 수 있어요."); return; }
      setError(""); setForm({ ...form, displayBadgeIds: selected ? form.displayBadgeIds.filter(id => id !== badge.id) : [...form.displayBadgeIds, badge.id] });
    }}><BadgeArtwork badge={badge} size="small" /><span>{badge.label}</span></button>)}</div>{earned.length === 0 && <p className="muted">아직 획득한 배지가 없어요.</p>}</fieldset>
    {error && <p className="form-error" role="alert">{error}</p>}<button className="primary-button full" disabled={busy || Boolean(source && !area)}>{busy ? "저장 중..." : "저장"}</button>
  </form></Modal>;
}
