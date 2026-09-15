import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { ArrowUpLeft, Clock3, Search, X } from "lucide-react";
import { api, readLocal, writeLocal } from "../lib/api";
import { Modal, IconButton } from "./ui";

export default function SearchSheet({ onClose, initial = "" }) {
  const navigate = useNavigate();
  const [query, setQuery] = useState(initial);
  const [recent, setRecent] = useState(() => readLocal("soomgilRecentSearches", []));
  const [suggestions, setSuggestions] = useState([]);
  const [activeIndex, setActiveIndex] = useState(-1);
  const [composing, setComposing] = useState(false);
  useEffect(() => {
    const controller = new AbortController();
    setSuggestions([]); setActiveIndex(-1);
    if (composing) return () => controller.abort();
    const timer = setTimeout(() => {
      if (!query.trim()) { setSuggestions([]); return; }
      api(`/api/suggestions?q=${encodeURIComponent(query)}`, { signal: controller.signal }).then(items => { if (!controller.signal.aborted) setSuggestions(items); }).catch(() => {});
    }, 200);
    return () => { clearTimeout(timer); controller.abort(); };
  }, [query, composing]);
  function search(value) {
    const term = value.trim();
    if (!term) return;
    writeLocal("soomgilRecentSearches", [term, ...recent.filter(item => item !== term)].slice(0, 10));
    onClose();
    navigate(`/search?q=${encodeURIComponent(term)}`);
  }
  function remove(value) {
    const next = value ? recent.filter(item => item !== value) : [];
    setRecent(next); writeLocal("soomgilRecentSearches", next);
  }
  return <Modal title="어디로 떠날까요?" onClose={onClose} className="search-dialog">
    <form className="search-field" onSubmit={event => { event.preventDefault(); if (!composing) search(suggestions[activeIndex] || query); }}>
      <Search size={20} /><input autoFocus aria-label="장소 검색" role="combobox" aria-autocomplete="list" aria-controls="search-options" aria-expanded={suggestions.length > 0} aria-activedescendant={activeIndex >= 0 ? `search-option-${activeIndex}` : undefined} value={query} maxLength={100} onChange={event => setQuery(event.target.value)} onCompositionStart={() => setComposing(true)} onCompositionEnd={() => setComposing(false)} onKeyDown={event => {
        if (event.nativeEvent.isComposing || composing || event.keyCode === 229) return;
        if (event.key === "ArrowDown" && suggestions.length) { event.preventDefault(); setActiveIndex(index => (index + 1) % suggestions.length); }
        if (event.key === "ArrowUp" && suggestions.length) { event.preventDefault(); setActiveIndex(index => (index <= 0 ? suggestions.length : index) - 1); }
      }} placeholder="지역, 장소, 여행 키워드" />
      {query && <IconButton label="검색어 지우기" onClick={() => setQuery("")}><X size={17} /></IconButton>}
      <button className="text-button" type="submit">검색</button>
    </form>
    {query.trim() ? <div className="search-suggestions"><h3>자동완성</h3><div id="search-options" role="listbox" aria-label="자동완성 검색어">{suggestions.map((item, index) => <button id={`search-option-${index}`} role="option" aria-selected={index === activeIndex} className="suggestion-option" key={item} onClick={() => search(item)}><Search size={17} /><span>{item}</span><ArrowUpLeft size={17} /></button>)}</div>
      <button onClick={() => search(query)}><Search size={17} /><span>“{query}” 검색</span></button></div>
      : <div className="search-suggestions"><div className="section-heading"><h3>최근 검색어</h3>{recent.length > 0 && <button className="text-button" onClick={() => remove()}>전체 삭제</button>}</div>
        {recent.length === 0 && <p className="muted">최근 검색어가 없어요.</p>}
        {recent.map(item => <div className="recent-search-row" key={item}><button onClick={() => search(item)}><Clock3 size={17} /><span>{item}</span></button><IconButton label={`${item} 검색 기록 삭제`} onClick={() => remove(item)}><X size={16} /></IconButton></div>)}
      </div>}
  </Modal>;
}
