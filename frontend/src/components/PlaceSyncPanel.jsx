import { useEffect, useState } from "react";
import { Database, Pause, Play, RefreshCw } from "lucide-react";
import { api } from "../lib/api";
import { useResource } from "../lib/context";
import { ErrorState, Loading, PageHeading } from "./ui";

const states = { pending: "첫 수집 대기", running: "수집 중", waiting: "갱신 대기", paused: "일시정지", disabled: "자동 갱신 꺼짐", "not-configured": "API 설정 필요", unavailable: "연결 재시도 대기", retrying: "저장 재시도 대기", busy: "요청 대기", "budget-limited": "오늘 호출 한도 도달" };
const date = value => value ? new Date(value).toLocaleString("ko-KR", { timeZone: "Asia/Seoul" }) : "아직 없음";
export default function PlaceSyncPanel() {
  const [version, setVersion] = useState(0), [busy, setBusy] = useState(false), [error, setError] = useState("");
  const resource = useResource("/api/admin/place-sync", version);
  const [last, setLast] = useState(null);
  useEffect(() => { if (resource.data) setLast(resource.data); }, [resource.data]);
  useEffect(() => { const timer = setInterval(() => { if (!document.hidden) setVersion(value => value + 1); }, 10000); return () => clearInterval(timer); }, []);
  const data = resource.data || last;
  async function control(action) {
    setBusy(true); setError("");
    try { setLast(await api("/api/admin/place-sync", { method: "POST", body: JSON.stringify({ action }) })); setVersion(value => value + 1); }
    catch (failure) { setError(failure.message); } finally { setBusy(false); }
  }
  return <><PageHeading title="장소 데이터" action={<Database size={23} />} />
    {resource.error && <ErrorState message={resource.error} retry={() => setVersion(value => value + 1)} />}
    {!data ? <Loading cards={1} /> : <section className="place-sync-panel">
      <div className="section-heading"><h2>{states[data.state] || "상태 확인 중"}</h2><span className="status">{data.initialComplete ? "초기 수집 완료" : "초기 수집"}</span></div>
      {data.mode === "delta" && <p>변경일 {data.syncDate} · {data.page}페이지</p>}
      <progress aria-label="이번 장소 수집 진행률" value={data.processed} max={Math.max(data.total, 1)} />
      <dl><dt>공개 장소</dt><dd>{data.storedTotal.toLocaleString()}곳</dd><dt>이번 수집</dt><dd>{data.processed.toLocaleString()} / {data.total.toLocaleString()}건</dd><dt>제외·미연결 항목</dt><dd>{data.skipped.toLocaleString()}건</dd><dt>오늘 목록 API 호출</dt><dd>{data.dailyCalls} / {data.dailyLimit}회</dd><dt>마지막 전체 수집</dt><dd>{date(data.lastFullAt)}</dd><dt>마지막 갱신</dt><dd>{date(data.lastCompletedAt)}</dd><dt>다음 실행</dt><dd>{data.paused || !data.enabled ? "중지됨" : date(data.nextRunAt)}</dd><dt>정기 갱신</dt><dd>매일 오전 3시 · 한국 시간</dd><dt>전체 목록 재점검</dt><dd>7일마다</dd></dl>
      <div className="button-row"><button className="secondary-button" disabled={busy || !data.enabled || !data.configured} onClick={() => control(data.paused ? "resume" : "pause")}>{data.paused ? <Play size={17} /> : <Pause size={17} />}{data.paused ? "이어받기" : "일시정지"}</button><button className="primary-button" disabled={busy || !data.enabled || !data.configured || data.state === "running"} onClick={() => control("run")}><RefreshCw size={17} />지금 갱신</button></div>
      {error && <p className="form-error" role="alert">{error}</p>}
    </section>}
  </>;
}
