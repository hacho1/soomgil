import { useEffect, useRef, useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { api } from "../lib/api";
import { useApp, useResource } from "../lib/context";
import { Loading } from "../components/ui";
import { AuthLayout, NaverButton } from "./AuthPages";

const errors = {
  cancelled: "네이버 로그인을 취소했어요. 원할 때 다시 시작할 수 있어요.",
  expired: "로그인 시간이 만료됐거나 처음 시작한 브라우저와 달라요. 다시 시도해주세요.",
  existing_account: "이 이메일로 가입한 계정이 있어요. 기존 로그인 방법으로 로그인해주세요.",
  failed: "네이버 인증을 완료하지 못했어요. 잠시 후 다시 시도해주세요.",
  unavailable: "네이버 로그인 연결을 준비하고 있어요.",
};

export default function NaverCompletePage() {
  const [params] = useSearchParams();
  const callbackError = params.get("error");
  const result = useResource(callbackError ? null : "/api/auth/naver/pending");
  const { completeLogin } = useApp();
  const [error, setError] = useState("");
  const completed = useRef(false);
  useEffect(() => {
    if (result.data?.authenticated && !completed.current) {
      completed.current = true;
      completeLogin().catch(failure => setError(failure.message));
    }
  }, [result.data?.authenticated, completeLogin]);
  const message = callbackError ? errors[callbackError] || errors.failed : error || result.error;
  if (message) return <AuthLayout title="네이버 로그인"><p className="form-error" role="alert">{message}</p><div className="signup-methods"><NaverButton /><Link className="secondary-button" to="/loginform">다른 방법으로 로그인</Link><Link className="text-button" to="/welcome">처음으로</Link></div></AuthLayout>;
  if (result.loading || !result.data || result.data.authenticated) return <AuthLayout title="네이버 로그인"><Loading /></AuthLayout>;
  return <NaverSignup profile={result.data} />;
}

function NaverSignup({ profile }) {
  const { completeLogin } = useApp();
  const navigate = useNavigate();
  const [nickname, setNickname] = useState(profile.nickname);
  const [email, setEmail] = useState(profile.email);
  const [consent, setConsent] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  async function submit(event) {
    event.preventDefault(); if (busy) return;
    setBusy(true); setError("");
    try {
      await api("/api/auth/naver/complete", { method: "POST", body: JSON.stringify({ nickname, email, privacyConsent: consent }) });
      await completeLogin();
    } catch (failure) { setError(failure.message); setBusy(false); }
  }
  async function cancel() {
    setBusy(true);
    try { await api("/api/auth/naver/pending", { method: "DELETE" }); navigate("/signup", { replace: true }); }
    catch (failure) { setError(failure.message); setBusy(false); }
  }
  return <AuthLayout title="숨길에서 만날 이름을 정해주세요"><form className="form-stack" onSubmit={submit}>
    {profile.image && <img className="naver-profile-preview" src={profile.image} alt="네이버 프로필" referrerPolicy="no-referrer" />}
    <label className="field-label">닉네임<input required autoComplete="nickname" maxLength={16} minLength={2} pattern="[가-힣A-Za-z0-9_]{2,16}" value={nickname} onChange={event => setNickname(event.target.value)} /><small>한글, 영문, 숫자, _로 2~16자</small></label>
    <label className="field-label">이메일<input required type="email" autoComplete="email" maxLength={254} readOnly={Boolean(profile.email)} value={email} onChange={event => setEmail(event.target.value)} /></label>
    <details className="consent-details"><summary>개인정보 수집·이용 안내</summary><p>회원 식별과 계정 관리를 위해 네이버 회원 식별자, 이메일, 닉네임을 저장합니다. 제공에 동의한 프로필 사진도 저장합니다. 동의를 거부하면 가입이 제한되며, 가입 없이도 장소를 둘러볼 수 있습니다.</p></details>
    <label className="check-label"><input type="checkbox" required checked={consent} onChange={event => setConsent(event.target.checked)} />[필수] 개인정보 수집·이용 동의</label>
    {error && <p role="alert" className="form-error">{error}</p>}<button className="primary-button full" disabled={busy}>{busy ? "가입 중..." : "동의하고 시작하기"}</button>
    <button className="text-button" type="button" disabled={busy} onClick={cancel}>취소</button>
  </form></AuthLayout>;
}
