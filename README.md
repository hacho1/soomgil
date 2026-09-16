# Soomgil

숨길 복구 프로젝트. `docs/planning.pdf`, `docs/design.pdf`, 대화에서 합의한 변경사항과 남아 있던 DB 덤프를 기준으로 복구 중입니다. 과거 완성본 전체를 복원한 상태는 아닙니다.

## 실행

Java 17 이상과 Node.js가 필요합니다. 프로젝트 루트에서 두 터미널을 열어 실행합니다.

```bat
run-backend.cmd
```

```bat
run-frontend.cmd
```

- 서비스: http://127.0.0.1:5173/
- 관리자: http://127.0.0.1:5173/admin.html
- 상태 확인: http://127.0.0.1:8080/api/health

이미 실행 중인 서버가 있다면 중복 실행하지 않습니다. 다른 포트를 사용하면 백엔드 허용 출처도 해당 포트에 맞춰야 합니다.

## 구조

| 위치 | 역할 |
| --- | --- |
| `frontend/src/pages/` | 홈, 검색, 지도, 인증, 마이페이지, 커뮤니티, 다이어리 |
| `frontend/src/components/` | 공통 UI, 검색 패널, 프로필 이미지 편집 |
| `frontend/src/lib/` | API/CSRF, 상태, 이미지 처리, 날짜 |
| `frontend/src/data/` | 카테고리와 지역 JSON |
| `backend/src/main/java/backend/` | REST API, 세션 인증, 권한 검사, Google 검증, 활동 보상, 관광공사 프록시 |
| `backend/src/main/resources/data/` | 덤프에서 분리한 공개 장소 자료 |
| `backend/data/` | 실제 로컬 H2 DB. Git 제외 |
| `backup/` | 개인 DB 백업. Git 제외 |
| `docs/RESTORATION_STATUS.md` | 복구 범위, 검증 결과와 남은 과제 |

## 설정과 인증

루트 `.env`는 `run-backend.cmd`로 실행할 때 백엔드만 읽습니다. 항목 설명은 `.env.example`에 있습니다. API 키를 프론트엔드 또는 `VITE_*` 변수에 넣지 않습니다.

- 관광공사: `SOOMGIL_TOUR_API_KEY`. 전국 목록을 DB에 확보하고 매일 오전 3시 변경분, 7일마다 전체 목록을 갱신합니다. 검색·지도는 DB 페이지 조회를 사용합니다. [수집/운영 안내](docs/CATALOG_SYNC.md)
- 추천: 홈 4곳과 주변 2곳에 여행 취향·지역 다양성·당일 집중률 예측을 반영합니다. 검색은 장소명 일치를 우선하며, 추천 근거와 불확실성을 구분합니다. AI가 생성한 평점이 아닙니다. [추천 기준과 검증 범위](docs/RECOMMENDATIONS.md)
- Google: `GOOGLE_CLIENT_ID`. Google 콘솔의 승인된 자바스크립트 출처에 실제 접속 주소가 필요합니다. 백엔드가 ID 토큰을 검증합니다.
- 관리자: 일반 계정을 만든 뒤 서버의 `SOOMGIL_ADMIN_USERNAME`을 해당 아이디로 지정하고 서버를 재시작합니다. 다시 로그인하면 관리자 권한을 받습니다. 공용 관리자 코드는 없습니다. 기본값은 관리자 미지정입니다.
- 인증: BCrypt 비밀번호, DB 저장 세션, HttpOnly 쿠키, CSRF 보호. 자동 로그인은 마지막 요청 후 30일 동안 활동이 없으면 만료됩니다.
- 이메일 인증: Gmail SMTP 연결 구조, 이메일 가입 인증, 아이디 찾기, 비밀번호 재설정, 이메일 변경 인증, 로그인 반복 시도 제한을 구현했습니다. 발신 Gmail과 앱 비밀번호 설정 및 실제 수신 확인은 필요합니다. [Gmail 연결 및 검증 안내](docs/EMAIL_AUTH.md)
- 문자 인증은 미연결이며 휴대폰 번호는 인증됨으로 표시하지 않습니다.
- 다이어리 이미지·그림·배지 검증, 계정별 저장 한도, 검색·작성 반복 요청 제한을 적용합니다. [용량과 호출 횟수 기준](docs/UPLOAD_AND_ABUSE_PROTECTION.md)

`localhost`와 `127.0.0.1`은 쿠키가 공유되지 않으므로 테스트 중에는 한 주소로 접속합니다.

## DB와 배포

지리산 전용 Windows 서버로 옮길 때는 [배포 인수인계](docs/JIRI_HANDOFF.md)와 [2026-09-16 검사 결과](docs/DEPLOYMENT_AUDIT_20260916.md)를 먼저 확인합니다. 외장하드는 운반용이며 운영은 서버 내부 디스크에서 실행합니다.

기본 실행 DB는 **MariaDB가 아닌 H2 파일 DB**입니다. MariaDB 환경변수 예시는 `.env.example`에 있으며, 실제 MariaDB 전환과 기존 개인정보 덤프 이관은 별도 검증이 필요합니다. 현재는 공개 장소 자료만 덤프에서 추출했습니다.

배포 전에는 HTTPS, `SOOMGIL_SECURE_COOKIES=true`, 실제 도메인 허용 출처, 관리자 지정, 테스트 계정 제거/비밀번호 변경, 인증 서비스, 호출 제한, DB 마이그레이션 및 백업 복구 훈련을 완료해야 합니다. 공개 서비스 출시 준비가 모두 끝난 상태는 아닙니다.

관리자 **운영·백업**에서 DB 상태, 마지막 복원 검사, 보관 개수, 추가 사본 상태를 확인하고 즉시 백업할 수 있습니다. H2 온라인 백업은 매일 오전 4시 실행하며, 각 ZIP을 격리된 임시 DB로 열어 검사한 뒤 최근 14개를 보관합니다. [운영·백업·복구 및 Windows 배포 안내](docs/OPERATIONS.md)

## 검증

지인과 임시 테스트: 기존 백엔드를 종료한 뒤 `run-share.cmd` 실행. 종료는 `stop-share.cmd`. 공유 모드는 배포용 화면과 API를 함께 제공하며 관리자·기본 테스트 계정·소셜 로그인은 비활성화한다. [임시 공유 안내](docs/TEMPORARY_SHARING.md)

```powershell
cd backend
.\gradlew.bat test bootJar
```

```powershell
cd frontend
npm.cmd run build
npm.cmd test
```

백엔드 테스트는 별도 메모리 DB와 테스트용 임시 파일 DB를 사용하며 사용자의 DB를 수정하지 않습니다. 실행 중인 H2 파일을 직접 복사하지 말고 관리자 온라인 백업을 사용합니다. 장애 감시 스크립트 테스트는 `powershell -File scripts/test-ops-monitor.ps1`로 실행합니다.

소스 원격 저장소는 [hacho1/soomgil](https://github.com/hacho1/soomgil)이며 비공개로 운영합니다. 프론트엔드·백엔드·테스트·문서·공개 장소 기초 데이터와 소스 이력을 보관합니다. 실제 회원 DB, 수집된 전체 장소 DB, `.env`, 실행 로그와 로컬 백업 ZIP은 GitHub에 포함하지 않습니다. 이 자료는 별도로 안전하게 백업해야 합니다. 채팅에 노출된 API 비밀키는 운영 전에 재발급해야 합니다.

다른 컴퓨터에서 소스를 복구할 때는 GitHub 권한이 있는 계정으로 `git clone https://github.com/hacho1/soomgil.git`을 실행합니다. 이후 Java 17·Node.js를 준비하고 `frontend`에서 `npm ci`를 실행하며, 비밀 설정과 실제 DB는 별도 백업에서 복원합니다. GitHub 소스만 복제하면 기존 회원·다이어리와 수집된 전체 장소 데이터까지 복원되는 것은 아닙니다.

현재 소스와 Git 이력의 로컬 백업: `powershell -ExecutionPolicy Bypass -File scripts/backup-local.ps1`. 이 스크립트는 기존 파일을 지우지 않으며, `.env`와 DB는 별도로 백업해야 합니다. 외장하드 연결 후 `-Destination 'D:\숨길\백업'`을 지정할 수 있습니다.
