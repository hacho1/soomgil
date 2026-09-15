import { useEffect, useRef, useState } from "react";
import { CheckCircle2, Mail } from "lucide-react";
import { api } from "../lib/api";
import { useResource } from "../lib/context";

export function useCountdown(until) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    setNow(Date.now());
    if (!until) return;
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [until]);
  return Math.max(0, Math.ceil((until - now) / 1000));
}

export default function EmailVerification({ email, onEmailChange, onVerified, purpose, readOnly = false }) {
  const config = useResource("/api/auth/config");
  const [challenge, setChallenge] = useState(null);
  const [grant, setGrant] = useState(null);
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [resendAt, setResendAt] = useState(0);
  const version = useRef(0);
  const callback = useRef(onVerified); callback.current = onVerified;
  const resend = useCountdown(resendAt);
  const remaining = useCountdown(grant?.expiresAt || challenge?.expiresAt || 0);
  useEffect(() => () => { version.current++; }, []);
  useEffect(() => {
    if (grant && remaining === 0) {
      setGrant(null); callback.current(null); setError("인증이 만료됐어요. 새 인증번호를 요청해주세요.");
    }
  }, [grant, remaining]);
  function reset() {
    version.current++; setChallenge(null); setGrant(null); setCode(""); setError(""); setMessage(""); callback.current(null);
  }
  async function send() {
    if (busy || resend) return;
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())) { setError("이메일 형식을 확인해주세요."); return; }
    reset(); const current = version.current;
    setBusy(true);
    try {
      const result = await api("/api/auth/email/request", { method: "POST", body: JSON.stringify({ email, purpose }) });
      if (current !== version.current) return;
      setChallenge({ id: result.challengeId, expiresAt: Date.now() + result.expiresIn * 1000 });
      setResendAt(Date.now() + result.resendAfter * 1000); setMessage(result.message);
    } catch (failure) {
      if (current !== version.current) return;
      setError(failure.message); if (failure.retryAfterSeconds) setResendAt(Date.now() + failure.retryAfterSeconds * 1000);
    } finally { if (current === version.current) setBusy(false); }
  }
  async function confirm() {
    if (busy || !challenge || !remaining) return;
    const current = version.current; setBusy(true); setError("");
    try {
      const result = await api("/api/auth/email/confirm", { method: "POST", body: JSON.stringify({ challengeId: challenge.id, code }) });
      if (current !== version.current) return;
      const next = { ...result, email: email.trim().toLowerCase(), expiresAt: Date.now() + result.expiresIn * 1000 };
      setGrant(next); callback.current(next); setCode(""); setMessage("");
    } catch (failure) { if (current === version.current) setError(failure.message); }
    finally { if (current === version.current) setBusy(false); }
  }
  return <div className="email-verification">
    <label className="field-label">이메일<input required type="email" autoComplete="email" maxLength={254} readOnly={readOnly} disabled={busy} value={email} onChange={event => { reset(); onEmailChange?.(event.target.value); }} placeholder="hello@example.com" /></label>
    {config.loading ? <p className="field-hint">이메일 인증 연결 확인 중...</p> : !config.data?.emailVerificationAvailable ? <p className="form-error" role="status">{config.error || "이메일 인증을 준비 중이에요. 기존 계정 로그인과 소셜 로그인은 계속 이용할 수 있어요."}</p> : <>
      {grant ? <div className="verification-success" role="status"><CheckCircle2 size={18} /><span>이메일 인증 완료</span><button type="button" className="text-button" onClick={reset}>다시 인증</button></div> : <>
        <button type="button" className="secondary-button full" disabled={busy || resend > 0 || !email.trim()} onClick={send}><Mail size={17} />{busy ? "처리 중..." : resend > 0 ? `${resend}초 후 재전송` : challenge ? "인증번호 다시 받기" : "인증번호 받기"}</button>
        {challenge && <div className="verification-code"><label className="field-label">인증번호<input inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}" maxLength={6} value={code} onChange={event => setCode(event.target.value.replace(/\D/g, "").slice(0, 6))} onKeyDown={event => { if (event.key === "Enter") { event.preventDefault(); confirm(); } }} placeholder="6자리 숫자" aria-label="이메일 인증번호" /></label><button type="button" className="primary-button" disabled={busy || code.length !== 6 || !remaining} onClick={confirm}>확인</button><small>{remaining ? `${Math.floor(remaining / 60)}:${String(remaining % 60).padStart(2, "0")} 남음` : "인증번호가 만료됐어요"}</small></div>}
      </>}
    </>}
    {message && <p className="field-hint" role="status">{message}</p>}{error && <p className="form-error" role="alert">{error}</p>}
  </div>;
}
