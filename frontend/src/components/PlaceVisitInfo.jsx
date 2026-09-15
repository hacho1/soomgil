import { useState } from "react";
import { Accessibility, ExternalLink, PawPrint, RefreshCw } from "lucide-react";
import { useResource } from "../lib/context";
import { visitInfoCheckedAt, visitInfoMessage, visitInfoSummary } from "../lib/visitInfo";
import { isCourse } from "../lib/placePresentation";

function Facts({ rows }) {
  return <dl className="place-facilities official-facts">{rows.map(row => <div key={row.id}><dt>{row.label}</dt><dd>{row.value}</dd></div>)}</dl>;
}

export function PlaceVisitSummary({ id }) {
  const info = useResource(`/api/places/${id}/visit-info`);
  return <dl className="place-facilities"><div><dt>반려동물</dt><dd>{info.loading ? "안내 불러오는 중" : info.error ? "정보 연결 일시 중단" : visitInfoSummary(info.data, "pet")}</dd></div><div><dt>무장애</dt><dd>{info.loading ? "안내 불러오는 중" : info.error ? "정보 연결 일시 중단" : visitInfoSummary(info.data, "accessibility")}</dd></div></dl>;
}

export default function PlaceVisitInfo({ place }) {
  const [retry, setRetry] = useState(0);
  const info = useResource(`/api/places/${place.id}/visit-info`, retry);
  const data = info.data;
  const course = isCourse(place);
  if (info.loading) return <section className="visit-info-status" role="status"><h2>장소 안내</h2><p>관광공사 상세 정보를 불러오고 있어요.</p></section>;
  if (info.error) return <section className="visit-info-status"><h2>장소 안내</h2><p role="alert">{info.error}</p><button className="secondary-button" onClick={() => setRetry(value => value + 1)}><RefreshCw size={16} />다시 확인</button></section>;
  const updated = visitInfoCheckedAt(data);
  const stale = data?.sources?.some(source => source.state === "stale");
  return <>
    <section><h2>{course ? "코스 소개" : "장소 소개"}</h2>{data?.overview ? <p className="official-overview">{data.overview}</p> : <p className="visit-info-note">{visitInfoMessage(data, "common")}</p>}</section>
    <section><h2>{course ? "코스 안내" : "이용 안내"}</h2>{data?.facilities?.length ? <Facts rows={data.facilities} /> : <p className="visit-info-note">{visitInfoMessage(data, "intro")}</p>}{data?.homepage && <a className="official-homepage" href={data.homepage} target="_blank" rel="noopener noreferrer">홈페이지 <ExternalLink size={15} /></a>}</section>
    {!course && <><section><h2 className="visit-info-heading"><PawPrint size={19} />반려동물 동반 안내</h2>{data?.pet?.length ? <Facts rows={data.pet} /> : <p className="visit-info-note">{visitInfoMessage(data, "pet")}</p>}</section>
    <section><h2 className="visit-info-heading"><Accessibility size={19} />무장애·접근성 안내</h2>{data?.accessibility?.length ? <><Facts rows={data.accessibility} /><p className="visit-info-note">시설별 제공 정보입니다. 이동 경로 전체의 접근성을 보장하지 않으니 필요한 지원은 방문 전에 확인해주세요.</p></> : <p className="visit-info-note">{visitInfoMessage(data, "access")}</p>}</section>
    </>}<section className="visit-info-source"><p>출처: 한국관광공사 TourAPI{updated && ` · ${updated} 조회`}</p>{stale && <p>최신 조회가 지연돼 이전에 확인한 자료가 포함되어 있어요.</p>}{["unavailable", "busy", "quota", "partial"].includes(data?.state) && <button className="text-button" onClick={() => setRetry(value => value + 1)}><RefreshCw size={14} />다시 확인</button>}</section>
  </>;
}
