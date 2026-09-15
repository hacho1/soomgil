import { useEffect, useRef, useState } from "react";
import { ArrowLeft, Check, Send, Trees, Users, Footprints } from "lucide-react";
import { Navigate, useNavigate, useSearchParams } from "react-router-dom";
import { useApp } from "../lib/context";
import { api } from "../lib/api";
import { clearAuthIntent, normalizeInterestIds } from "../lib/authFlow";
import { IconButton, Loading } from "../components/ui";
import choices from "../data/travel-preferences.json" with { type: "json" };
import regions from "../data/regions.json" with { type: "json" };

const crowdChoices = [
  { id: "quiet", title: "최대한 한적한 곳", detail: "사람보다 풍경에 집중하고 싶어요", icon: Trees },
  { id: "normal", title: "적당히 한적한 곳", detail: "편안함과 활기를 함께 느끼고 싶어요", icon: Footprints },
  { id: "popular", title: "인기 있는 곳도 괜찮아요", detail: "사람이 많아도 가보고 싶은 곳이 있어요", icon: Users },
];

export default function OnboardingPage() {
  const { user, authLoading } = useApp();
  if (authLoading) return <Loading />;
  if (!user) return <Navigate to="/loginform" replace />;
  return <OnboardingForm key={user.userId} user={user} />;
}

function OnboardingForm({ user }) {
  const { refreshUser, finishAuth } = useApp();
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const editing = params.has("edit") && user.onboardingCompleted;
  const [step, setStep] = useState(params.has("edit") ? 0 : Math.min(3, user.onboardingStep || 0));
  const [interests, setInterests] = useState(() => normalizeInterestIds(user.interests, choices));
  const [crowd, setCrowd] = useState(user.crowdPreference || "normal");
  const [selectedRegions, setRegions] = useState(user.preferredRegions || []);
  const [busy, setBusy] = useState(false);
  const [departing, setDeparting] = useState(false);
  const [error, setError] = useState("");
  const timer = useRef();
  const active = useRef(true);
  useEffect(() => { active.current = true; return () => { active.current = false; clearTimeout(timer.current); }; }, []);
  function toggle(values, value) { return values.includes(value) ? values.filter(item => item !== value) : [...values, value]; }
  async function save(nextStep, skipRegions = false) {
    if (busy) return;
    setBusy(true); setError("");
    try {
      await api(`/api/users/${user.userId}/preferences`, { method: "PUT", body: JSON.stringify({ interests, crowdPreference: crowd, preferredRegions: skipRegions ? [] : selectedRegions, onboardingStep: nextStep, onboardingCompleted: false }) });
      await refreshUser();
      if (active.current) { if (skipRegions) setRegions([]); setStep(nextStep); }
    } catch (failure) { if (active.current) setError(failure.message); }
    finally { if (active.current) setBusy(false); }
  }
  async function depart() {
    if (busy) return;
    setBusy(true); setError("");
    try {
      await api(`/api/users/${user.userId}/preferences`, { method: "PUT", body: JSON.stringify({ interests, crowdPreference: crowd, preferredRegions: selectedRegions, onboardingStep: 3, onboardingCompleted: true }) });
      await refreshUser();
      if (!active.current) return;
      setDeparting(true);
      const complete = async () => { try { await finishAuth(); } catch (failure) { if (active.current) { setDeparting(false); setBusy(false); setError(failure.message); } } };
      if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) await complete();
      else timer.current = setTimeout(complete, 1200);
    } catch (failure) { if (active.current) { setError(failure.message); setBusy(false); } }
  }
  return <div className={`onboarding-page ${departing ? "is-departing" : ""}`} aria-busy={busy}>
    <header className="onboarding-header">
      <IconButton label={step === 0 && editing ? "여행 관심사로 돌아가기" : "이전 단계"} disabled={(step === 0 && !editing) || busy} onClick={() => { if (step === 0) { clearAuthIntent(); navigate("/mypage/interests"); } else setStep(value => value - 1); }}><ArrowLeft size={21} /></IconButton>
      <span className="brand"><span className="brand-mark">S</span>Soomgil</span>
      <span className="onboarding-counter">{step < 3 ? `${step + 1} / 3` : <Check size={20} />}</span>
    </header>
    <progress className="onboarding-progress" max="3" value={step < 3 ? step + 1 : 3} aria-label="여행 취향 설정 진행률" />
    <div className="onboarding-body">
      {step === 0 && <><p className="onboarding-eyebrow">나의 여행 취향</p><h1>어떤 여행을<br />좋아하시나요?</h1><div className="onboarding-interests" role="group" aria-label="관심 여행 카테고리">
        {choices.filter(choice => choice.onboarding).map(choice => <button key={choice.id} disabled={busy} aria-pressed={interests.includes(choice.id)} onClick={() => setInterests(toggle(interests, choice.id))}>
          <img src={`/assets/map/${choice.art}.png`} alt="" width="44" height="44" /><span>{choice.label}</span>{interests.includes(choice.id) && <Check className="choice-check" size={13} />}
        </button>)}
      </div></>}
      {step === 1 && <><p className="onboarding-eyebrow">편안한 여행의 기준</p><h1>어떤 장소를<br />찾고 계신가요?</h1><fieldset className="onboarding-crowd"><legend className="sr-only">혼잡도 선호</legend>{crowdChoices.map(({ id, title, detail, icon: Icon }) => <label key={id} className={crowd === id ? "is-selected" : ""}>
        <Icon size={25} /><span><strong>{title}</strong><small>{detail}</small></span><input disabled={busy} type="radio" name="crowd" value={id} checked={crowd === id} onChange={() => setCrowd(id)} />
      </label>)}</fieldset></>}
      {step === 2 && <><p className="onboarding-eyebrow">마음이 향하는 곳</p><h1>관심 있는<br />지역이 있나요?</h1><div className="onboarding-regions" role="group" aria-label="관심 지역">{regions.map(region => <button key={region} disabled={busy} aria-pressed={selectedRegions.includes(region)} onClick={() => setRegions(toggle(selectedRegions, region))}>{region}{selectedRegions.includes(region) && <Check size={13} />}</button>)}</div></>}
      {step === 3 && <div className="onboarding-complete"><div className="departure-stage" aria-hidden="true"><div className="flight-trail" /><Send className="departure-plane" size={62} strokeWidth={1.5} /></div><p className="onboarding-eyebrow">나만의 여행 준비 완료</p><h1>준비가 끝났어요!</h1><p>당신만의 숨은 여행지를 찾아보세요.</p><div className="preference-receipt"><span>관심 여행 <strong>{interests.length}개</strong></span><span>관심 지역 <strong>{selectedRegions.length ? `${selectedRegions.length}곳` : "전국"}</strong></span></div></div>}
    </div>
    <footer className="onboarding-footer">{error && <p className="form-error" role="alert">{error}</p>}
      {step < 3 ? <><button className="primary-button full" disabled={busy} onClick={() => save(step + 1)}>{busy ? "저장 중..." : "다음"}</button>{step === 2 && <button className="onboarding-skip" disabled={busy} onClick={() => save(3, true)}>나중에 선택할게요</button>}</> : <button className="primary-button full" disabled={busy} onClick={depart}><Send size={18} />{departing ? "출발하고 있어요" : busy ? "저장 중..." : "탐험하러 가기"}</button>}
    </footer>
  </div>;
}
