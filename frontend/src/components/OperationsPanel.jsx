import { useEffect, useState } from "react";
import { DatabaseBackup, RefreshCw, ShieldCheck } from "lucide-react";
import { api } from "../lib/api";
import { useResource } from "../lib/context";
import { ErrorState, Loading, PageHeading } from "./ui";

const date = value => value ? new Date(value).toLocaleString("ko-KR", { timeZone: "Asia/Seoul" }) : "아직 없음";
const labels = { idle: "백업 대기", running: "백업·복원 검사 중", verified: "복원 검사 통과", failed: "백업 실패", "replica-failed": "추가 사본 저장 실패", busy: "다른 백업 진행 중", "metadata-error": "백업 기록 확인 필요" };
export default function OperationsPanel() {
  const [revision, setRevision] = useState(0), [busy, setBusy] = useState(false), [error, setError] = useState("");
  const resource = useResource("/api/admin/operations", revision);
  const [last, setLast] = useState(null);
  useEffect(() => { if (resource.data) setLast(resource.data); }, [resource.data]);
  useEffect(() => { const timer = setInterval(() => { if (!document.hidden) setRevision(value => value + 1); }, 10000); return () => clearInterval(timer); }, []);
  const data = resource.data || last, backup = data?.backup;
  async function run() {
    if (busy) return; setBusy(true); setError("");
    try { await api("/api/admin/operations/backup", { method: "POST", body: "{}" }); setRevision(value => value + 1); }
    catch (failure) { setError(failure.message); } finally { setBusy(false); }
  }
  return <><PageHeading title="운영·백업" action={<ShieldCheck size={23} />} />
    {resource.error && <ErrorState message={resource.error} retry={() => setRevision(value => value + 1)} />}
    {!data && !resource.error && <Loading cards={1} />}
    {data && <section className="operations-panel">
      <div className="section-heading"><h2>{data.database === "up" ? "DB 연결 정상" : "DB 연결 확인 필요"}</h2><span className="status">가동 {Math.floor(data.uptimeSeconds / 60).toLocaleString()}분</span></div>
      <h3>{backup.supported ? labels[backup.state] || "확인 필요" : "H2 파일 DB 백업만 지원"}</h3>
      {backup.supported && backup.stale && <p className="form-error" role="status">최근 26시간 안에 검증된 백업이 없어요.</p>}
      <dl><dt>자동 백업</dt><dd>{backup.enabled && backup.supported ? "매일 오전 4시" : "꺼짐"}</dd><dt>다음 예정</dt><dd>{backup.enabled && backup.supported ? date(backup.nextRunAt) : "미설정"}</dd><dt>마지막 복원 검사</dt><dd>{date(backup.last.completedAt)}</dd><dt>추가 사본</dt><dd>{!backup.replicaConfigured ? "저장 위치 미설정" : backup.replicaState === "verified" ? "해시 일치 확인" : "저장 확인 필요"}</dd><dt>보관 개수</dt><dd>최근 {backup.keep}개</dd><dt>백업 크기</dt><dd>{backup.last.bytes ? `${(backup.last.bytes / 1024 / 1024).toFixed(1)} MB` : "아직 없음"}</dd></dl>
      {backup.last.file && <p className="backup-filename">{backup.last.file}</p>}
      {backup.last.tables && <div className="backup-counts"><span>장소 {Number(backup.last.tables.places || 0).toLocaleString()}곳</span><span>계정 {Number(backup.last.tables.user_accounts || 0).toLocaleString()}개</span><span>다이어리 {Number(backup.last.tables.diary_entries || 0).toLocaleString()}개</span></div>}
      <div className="button-row"><button className="primary-button" disabled={busy || !backup.supported || backup.state === "running" || data.database !== "up"} onClick={run}><DatabaseBackup size={18} />지금 백업·검사</button><button className="secondary-button" disabled={resource.loading} onClick={() => setRevision(value => value + 1)}><RefreshCw size={17} />새로고침</button></div>
      {error && <p className="form-error" role="alert">{error}</p>}
    </section>}
  </>;
}
