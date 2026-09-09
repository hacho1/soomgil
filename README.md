# Soomgil Recovery

숨길 프로젝트 긴급 복구본입니다. 기존 완성본 파일이 유실된 상황에서 채팅 기록, 남아 있는 DB 덤프, 백엔드 초기 백업을 기준으로 프론트엔드/백엔드 MVP를 다시 구성했습니다.

## Project Structure

- `frontend/` React + Vite client
- `backend/` Spring Boot API server
- `backup/` local DB dump 보관용. SQL 파일은 Git에 올리지 않습니다.
- `docs/` 기획안/설계서 등 문서 보관용
- `run-frontend.cmd` frontend dev server 실행
- `run-backend.cmd` backend server 실행

## Current Features

- 홈 화면, 검색, 카테고리, 지도형 탐색
- 장소 상세, 숨길 별점, 혼잡도, 반려동물/무장애/산책길/캠핑 정보 표시
- 회원가입, 로그인, 아이디 찾기, 비밀번호 재설정
- Google 로그인 데모 흐름
- 마이페이지, 프로필 수정, 다크모드, 찜목록, 팔로우/팔로잉
- 공지사항, 문의하기, 장소 제보
- 관리자 페이지: 공지 CRUD, 장소 제보 승인/반려, 문의 답변/삭제
- 후기: 익명 표시, 작성일, 좋아요, 최신순/좋아요순, 금칙어 필터
- 여행 다이어리: 기록 목록, 작성/수정/삭제, 달력 보기, 이미지 첨부
- 알림: 공지/제보/배지 계열 알림 저장 및 읽음 처리

## Run Locally

Backend:

```bat
run-backend.cmd
```

Frontend:

```bat
run-frontend.cmd
```

URLs:

- Frontend: http://127.0.0.1:5173/
- Admin: http://127.0.0.1:5173/admin.html
- Backend health: http://127.0.0.1:8080/api/health

## Environment Variables

Copy `.env.example` to `.env` when real keys are needed. Do not commit `.env`.

Important variables:

- `SOOMGIL_TOUR_API_KEY`
- `GEMINI_API_KEY`
- `VITE_GOOGLE_CLIENT_ID`
- `SOOMGIL_ADMIN_CODE`
- `SOOMGIL_DB_URL`
- `SOOMGIL_DB_USERNAME`
- `SOOMGIL_DB_PASSWORD`
- `SOOMGIL_DB_DRIVER`

The default local backend uses H2 file DB so the app can run without MariaDB. For deployment, configure MariaDB through the DB environment variables.

## Verification

Last checked:

- `backend`: `gradlew.bat clean test bootJar`
- `frontend`: `npm.cmd run build`
- API health and search through Vite proxy

## Recovery Notes

- Previous external drive data was not available during this rebuild.
- A MariaDB dump is copied under `backup/`, but SQL files are ignored by Git.
- The planning/design PDFs should be placed in `docs/` once OneDrive makes them available locally.
- GitHub push still requires a GitHub remote URL or connected GitHub account.
