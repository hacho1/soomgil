export function visitInfoMessage(data, sourceId) {
  const state = data?.sources?.find(source => source.id === sourceId)?.state || data?.state;
  if (state === "not-configured") return "상세정보 서비스가 연결되지 않았어요.";
  if (state === "unmatched") return "공식 자료에서 동일한 장소를 확인하지 못했어요.";
  if (["unavailable", "busy", "quota"].includes(state)) return "지금은 공식 정보를 불러오지 못했어요. 잠시 후 다시 확인해주세요.";
  if (state === "stale") return "최신 자료를 불러오지 못해 이전에 확인한 정보를 표시하고 있어요.";
  return "관광공사에 등록된 정보가 없어요. 방문 전 시설에 문의해주세요.";
}

export function visitInfoSummary(data, kind) {
  const rows = data?.[kind] || [];
  if (rows.length) {
    if (kind === "pet") return (rows.find(row => row.id === "acmpyTypeCd") || rows[0]).value;
    return `접근성 시설 ${rows.length}개 항목 안내`;
  }
  const state = data?.sources?.find(source => source.id === (kind === "pet" ? "pet" : "access"))?.state || data?.state;
  if (["unavailable", "busy", "quota", "not-configured", "stale"].includes(state)) return "정보 연결 일시 중단";
  return "공식 정보 미등록";
}

export function visitInfoCheckedAt(data) {
  const times = (data?.sources || []).map(source => Number(source.checkedAt)).filter(time => time > 0 && Number.isFinite(time));
  if (!times.length) return "";
  return new Intl.DateTimeFormat("ko-KR", { year: "numeric", month: "2-digit", day: "2-digit", timeZone: "Asia/Seoul" }).format(new Date(Math.min(...times)));
}
