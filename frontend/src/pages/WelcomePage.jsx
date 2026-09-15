import { ArrowRight, Compass } from "lucide-react";
import { Link } from "react-router-dom";
import { useApp } from "../lib/context";

export default function WelcomePage() {
  const { browseAsGuest } = useApp();
  return <div className="welcome-page">
    <div className="welcome-scene">
      <img src="https://tong.visitkorea.or.kr/cms/resource/02/2921902_image2_1.jpg" alt="순포해변의 바다" onError={event => { event.currentTarget.hidden = true; }} />
      <div className="welcome-shade" />
      <div className="welcome-brand"><span className="brand-mark">S</span><h1>Soomgil</h1></div>
      <div className="welcome-copy"><p>어서오세요!</p><h2>아직 알려지지 않은<br />여행지를 찾아볼까요?</h2></div>
      <span className="welcome-location">강릉 순포해변 · 한국관광공사</span>
    </div>
    <section className="welcome-actions" aria-label="여행 시작">
      <Link to="/signup" className="primary-button full">회원가입하기<ArrowRight size={18} /></Link>
      <Link to="/loginform" className="secondary-button full">로그인하기</Link>
      <button className="welcome-browse" onClick={browseAsGuest}><Compass size={17} />먼저 둘러보기</button>
    </section>
  </div>;
}
