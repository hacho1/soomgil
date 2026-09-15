import { useEffect, useRef, useState } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { ArrowLeft, Eye, EyeOff, Mail, ShieldCheck } from "lucide-react";
import { api, readLocal, resetCsrf, writeLocal } from "../lib/api";
import { readAuthIntent, saveAuthIntent } from "../lib/authFlow";
import { useApp, useResource } from "../lib/context";
import { IconButton } from "../components/ui";
import EmailVerification, { useCountdown } from "../components/EmailVerification";

export function AuthLayout({ title, children }) {
  const navigate = useNavigate();
  const location = useLocation();
  return <div className="auth-page"><button className="back-link" onClick={() => location.state?.returnTo ? navigate(-1) : navigate("/welcome")}><ArrowLeft size={20} />돌아가기</button><Link className="brand" to="/"><span className="brand-mark">S</span>Soomgil</Link><h1>{title}</h1>{children}</div>;
}
export function PasswordField({ label, value, onChange, autoComplete = "current-password", ...props }) {
  const [visible, setVisible] = useState(false);
  return <label className="field-label">{label}<span className="password-field"><input type={visible ? "text" : "password"} value={value} onChange={onChange} autoComplete={autoComplete} required maxLength={72} {...props} /><IconButton label={visible ? "비밀번호 숨기기" : "비밀번호 보기"} onClick={() => setVisible(!visible)}>{visible ? <EyeOff size={18} /> : <Eye size={18} />}</IconButton></span></label>;
}
function GoogleButton({ onSuccess, autoLogin }) {
  const config = useResource("/api/auth/config");
  const container = useRef();
  const callback = useRef(onSuccess);
  const remember = useRef(autoLogin);
  const inFlight = useRef(false);
  callback.current = onSuccess; remember.current = autoLogin;
  const [error, setError] = useState("");
  useEffect(() => {
    if (!config.data?.googleClientId) return;
    let active = true;
    let ready = false;
    let renderedWidth = 0;
    function resizeButton() {
      if (!active || !ready || !container.current) return;
      const width = Math.min(340, container.current.clientWidth);
      if (width <= 0 || width === renderedWidth) return;
      renderedWidth = width;
      container.current.replaceChildren();
      window.google.accounts.id.renderButton(container.current, { theme: "outline", size: "large", width, text: "continue_with" });
    }
    function render() {
      if (!active || !window.google?.accounts?.id) return;
      window.google.accounts.id.initialize({ client_id: config.data.googleClientId, callback: async credential => {
        if (!active || inFlight.current) return;
        inFlight.current = true;
        try { const result = await api("/api/auth/google", { method: "POST", body: JSON.stringify({ credential: credential.credential, autoLogin: remember.current }) }); await callback.current(result); }
        catch (failure) { setError(failure.message); }
        finally { inFlight.current = false; }
      } });
      ready = true;
      resizeButton();
    }
    const resize = new ResizeObserver(resizeButton);
    resize.observe(container.current);
    const existing = document.getElementById("google-identity-script");
    if (window.google?.accounts?.id) render();
    else if (existing) existing.addEventListener("load", render);
    else {
      const script = document.createElement("script"); script.id = "google-identity-script"; script.src = "https://accounts.google.com/gsi/client"; script.async = true;
      script.addEventListener("load", render); script.onerror = () => { if (active) setError("Google 로그인을 불러오지 못했어요."); }; document.head.appendChild(script);
    }
    return () => { active = false; resize.disconnect(); document.getElementById("google-identity-script")?.removeEventListener("load", render); };
  }, [config.data?.googleClientId]);
  if (!config.data?.googleClientId) return <button className="secondary-button google-unavailable" disabled>Google 로그인 준비 중</button>;
  return <div className="google-login"><div ref={container} />{error && <p className="form-error" role="alert">{error}</p>}</div>;
}
export function NaverButton({ autoLogin = false }) {
  const config = useResource("/api/auth/config");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  useEffect(() => {
    const resume = () => setBusy(false);
    window.addEventListener("pageshow", resume);
    return () => window.removeEventListener("pageshow", resume);
  }, []);
  async function start() {
    if (busy) return;
    setBusy(true); setError("");
    try {
      const result = await api("/api/auth/naver/start", { method: "POST", body: JSON.stringify({ autoLogin }) });
      const url = new URL(result.url);
      if (url.origin !== "https://nid.naver.com" || url.pathname !== "/oauth2.0/authorize") throw new Error("로그인 주소를 확인하지 못했어요.");
      window.location.assign(url.href);
    } catch (failure) { setError(failure.message); setBusy(false); }
  }
  return <div className="naver-login"><button type="button" className="naver-button full" disabled={busy || !config.data?.naverAvailable} onClick={start}>
    <span aria-hidden="true">N</span>{busy ? "네이버로 이동 중..." : "네이버로 계속하기"}
  </button>{config.loading ? <small>로그인 연결 확인 중...</small> : !config.data?.naverAvailable && <small>{config.error || "네이버 로그인 준비 중"}</small>}{error && <p className="form-error" role="alert">{error}</p>}</div>;
}
export function LoginPage() {
  const { completeLogin, notify } = useApp();
  const location = useLocation();
  useEffect(() => { if (location.state?.returnTo) saveAuthIntent({ ...readAuthIntent(), returnTo: location.state.returnTo }); }, [location.state?.returnTo]);
  const [form, setForm] = useState({ identifier: readLocal("soomgilSavedIdentifier", ""), password: "", rememberId: Boolean(readLocal("soomgilSavedIdentifier", "")), autoLogin: false });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [retryAt, setRetryAt] = useState(0);
  const retry = useCountdown(retryAt);
  const change = (key, value) => setForm({ ...form, [key]: value });
  async function success() { await completeLogin(); notify("다시 만나 반가워요."); }
  async function submit(event) {
    event.preventDefault(); if (busy || retry) return;
    setBusy(true); setError("");
    try {
      await api("/api/auth/login", { method: "POST", body: JSON.stringify(form) });
      writeLocal("soomgilSavedIdentifier", form.rememberId ? form.identifier : "");
      await success();
    } catch (failure) { setError(failure.message); if (failure.retryAfterSeconds) setRetryAt(Date.now() + failure.retryAfterSeconds * 1000); } finally { setBusy(false); }
  }
  return <AuthLayout title="다시 만나 반가워요"><form className="form-stack" onSubmit={submit}>
    <label className="field-label">아이디 또는 이메일<input required autoComplete="username" value={form.identifier} maxLength={254} onChange={event => change("identifier", event.target.value)} placeholder="아이디 또는 이메일" /></label>
    <PasswordField label="비밀번호" value={form.password} onChange={event => change("password", event.target.value)} />
    <div className="login-options"><label><input type="checkbox" checked={form.rememberId} onChange={event => change("rememberId", event.target.checked)} />아이디 저장</label><label><input type="checkbox" checked={form.autoLogin} onChange={event => change("autoLogin", event.target.checked)} />자동 로그인</label></div>
    {error && <p role="alert" className="form-error">{error}</p>}<button className="primary-button full" disabled={busy || retry > 0}>{busy ? "로그인 중..." : retry ? `${retry}초 후 다시 로그인` : "로그인"}</button>
  </form><div className="auth-links"><Link to="/recovery">아이디·비밀번호 찾기</Link><Link to="/signup">회원가입</Link></div><div className="or-divider">또는</div><div className="signup-methods"><GoogleButton autoLogin={form.autoLogin} onSuccess={success} /><NaverButton autoLogin={form.autoLogin} /></div></AuthLayout>;
}
export function SignupChoicePage() {
  const { completeLogin } = useApp();
  return <AuthLayout title="숨길과 함께 여행을 시작해볼까요?">
    <img className="signup-art" src="/assets/badges/traveler.png" width="112" height="112" alt="" />
    <div className="signup-methods"><GoogleButton onSuccess={completeLogin} autoLogin={false} />
      <NaverButton />
      <div className="or-divider">또는</div><Link to="/signupform" className="primary-button full"><Mail size={19} />이메일로 회원가입</Link>
    </div><div className="auth-links"><Link to="/loginform">이미 계정이 있나요? 로그인</Link></div>
  </AuthLayout>;
}
export function SignupPage() {
  const { completeLogin, notify } = useApp();
  const [form, setForm] = useState({ nickname: "", password: "", passwordConfirm: "", email: "", privacyConsent: false });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [verified, setVerified] = useState(null);
  const change = (key, value) => setForm({ ...form, [key]: value });
  async function submit(event) {
    event.preventDefault(); if (busy) return;
    if (!verified || verified.expiresAt <= Date.now()) { setError("이메일 인증을 먼저 완료해주세요."); return; }
    if (form.password !== form.passwordConfirm) { setError("비밀번호 확인이 일치하지 않습니다."); return; }
    setBusy(true); setError("");
    try { await api("/api/auth/signup", { method: "POST", body: JSON.stringify({ ...form, emailProof: verified.proof }) }); await completeLogin(); notify("숨길에 오신 걸 환영해요."); }
    catch (failure) { setError(failure.message); } finally { setBusy(false); }
  }
  return <AuthLayout title="나만의 여행, 함께 시작해요"><form className="form-stack" onSubmit={submit}>
    <EmailVerification purpose="signup" email={form.email} onEmailChange={value => change("email", value)} onVerified={setVerified} />
    <label className="field-label">닉네임<input required autoComplete="nickname" pattern="[가-힣A-Za-z0-9_]{2,16}" minLength={2} maxLength={16} value={form.nickname} onChange={event => change("nickname", event.target.value)} /><small>한글, 영문, 숫자, _로 2~16자</small></label>
    <PasswordField label="비밀번호" autoComplete="new-password" minLength={8} value={form.password} onChange={event => change("password", event.target.value)} />
    <p className="field-hint">영문 대소문자, 숫자, 특수문자를 포함해 8자 이상</p>
    <PasswordField label="비밀번호 확인" autoComplete="new-password" value={form.passwordConfirm} onChange={event => change("passwordConfirm", event.target.value)} />
    <details className="consent-details"><summary>개인정보 수집·이용 안내</summary><p>회원 식별과 계정 관리를 위해 이메일, 닉네임, 암호화한 비밀번호를 저장합니다. 여행 취향과 관심 지역은 선택 정보입니다. 동의를 거부하면 가입이 제한되며, 가입하지 않고도 장소를 둘러볼 수 있습니다.</p></details>
    <label className="check-label"><input required type="checkbox" checked={form.privacyConsent} onChange={event => change("privacyConsent", event.target.checked)} />[필수] 개인정보 수집·이용 동의</label>
    {error && <p className="form-error" role="alert">{error}</p>}<button disabled={busy || !verified} className="primary-button full">{busy ? "가입 중..." : "가입하기"}</button>
  </form><div className="auth-links"><Link to="/loginform">이미 계정이 있나요? 로그인</Link></div></AuthLayout>;
}
export function RecoveryPage() {
  const [mode, setMode] = useState("find-id");
  return <AuthLayout title="계정 찾기"><div className="segmented recovery-tabs" aria-label="계정 찾기 방법">{[["find-id", "아이디 찾기"], ["reset-password", "비밀번호 재설정"]].map(([value, label]) => <button key={value} aria-pressed={mode === value} onClick={() => setMode(value)}>{label}</button>)}</div><RecoveryForm key={mode} mode={mode} /><div className="auth-links"><Link to="/loginform">로그인으로 돌아가기</Link></div></AuthLayout>;
}

function RecoveryForm({ mode }) {
  const { refreshUser } = useApp();
  const [email, setEmail] = useState("");
  const [verified, setVerified] = useState(null);
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [result, setResult] = useState(null);
  const social = verified && verified.provider !== "local";
  async function submit(event) {
    event.preventDefault(); if (busy || !verified) return;
    if (mode === "reset-password" && password !== confirm) { setError("비밀번호 확인이 일치하지 않습니다."); return; }
    setBusy(true); setError("");
    try {
      const response = await api(`/api/auth/${mode}`, { method: "POST", body: JSON.stringify({ email, emailProof: verified.proof, newPassword: password, passwordConfirm: confirm }) });
      if (mode === "reset-password") { resetCsrf(); await refreshUser().catch(() => {}); }
      setResult(response); setPassword(""); setConfirm(""); setVerified(null);
    } catch (failure) { setError(failure.message); } finally { setBusy(false); }
  }
  if (result) return <section className="recovery-result" role="status"><ShieldCheck size={30} /><h2>{mode === "find-id" ? "계정을 찾았어요" : "비밀번호를 변경했어요"}</h2>{mode === "find-id" ? <><p className="recovered-id">{result.username}</p><p>{result.provider === "local" ? "아이디 또는 이메일로 로그인할 수 있어요." : `${result.provider === "google" ? "Google" : "네이버"}로 가입한 계정이에요. 같은 방법으로 로그인해주세요.`}</p></> : <p>모든 기기에서 로그아웃되었어요. 새 비밀번호로 로그인해주세요.</p>}<Link to="/loginform" className="primary-button full">로그인</Link></section>;
  return <form className="form-stack" onSubmit={submit}><p className="field-hint">계정에서 인증한 이메일을 입력해주세요. 인증하지 않은 기존 계정은 로그인 후 내 계정에서 이메일 인증이 필요해요.</p>
    <EmailVerification purpose={mode} email={email} onEmailChange={setEmail} onVerified={setVerified} />
    {mode === "reset-password" && verified && (social ? <p role="status" className="field-hint">{verified.provider === "google" ? "Google" : "네이버"} 계정의 비밀번호는 해당 서비스에서 변경해주세요.</p> : <><PasswordField label="새 비밀번호" autoComplete="new-password" minLength={8} value={password} onChange={event => setPassword(event.target.value)} /><p className="field-hint">영문 대소문자, 숫자, 특수문자를 포함해 8자 이상</p><PasswordField label="새 비밀번호 확인" autoComplete="new-password" value={confirm} onChange={event => setConfirm(event.target.value)} /></>)}
    {error && <p role="alert" className="form-error">{error}</p>}<button className="primary-button full" disabled={busy || !verified || (mode === "reset-password" && social)}>{busy ? "처리 중..." : mode === "find-id" ? "아이디 확인" : "비밀번호 변경"}</button>
  </form>;
}
