# 운영·백업·복구

## 적용 범위

- 현재 DB는 H2 파일입니다. 기본 위치는 `backend/data/soomgil.mv.db`입니다.
- 이번 구현: H2 온라인 백업, 별도 복원 검사, 보관 정책, 선택적 추가 디스크 사본, 관리자 상태 화면, 장애 감시 스크립트, HTTPS 프록시 및 Windows 서비스 설정 파일.
- 실제 운영 도메인 연결, 인증서 발급, Windows 서비스 설치, 외부 알림 수신처 연결은 별도 작업입니다. 설정 파일이 있다고 이미 운영 중인 것은 아닙니다.
- MariaDB 전환 시 이 파일 백업은 지원되지 않습니다. MariaDB 전용 백업 및 복원 검증이 필요합니다.

## 백업 설정

프로젝트 루트의 비공개 `.env`에서 설정한 뒤 백엔드를 재시작합니다. 경로에 역슬래시 대신 `/`를 사용합니다.

```properties
SOOMGIL_BACKUP_ENABLED=true
SOOMGIL_BACKUP_DIRECTORY=../backup/database
SOOMGIL_BACKUP_KEEP=14
SOOMGIL_BACKUP_REPLICA=
```

자동 실행은 한국 시간 매일 04:00입니다. 종료 중 놓친 실행은 시작 약 60초 후 확인하고 이후 매시간 재확인합니다. 백엔드와 PC가 꺼져 있으면 실행되지 않습니다. 관리자 `운영·백업`의 즉시 실행은 자동 실행을 꺼도 가능합니다.

추가 디스크에 미리 **비공개 백업 폴더**를 만든 뒤 `SOOMGIL_BACKUP_REPLICA=D:/숨길/backup/database-replica`처럼 지정할 수 있습니다. 미연결 디스크나 존재하지 않는 폴더는 자동 생성하지 않고 실패로 표시합니다. 검증된 로컬 백업은 남습니다. 서버를 D에서 실행한다면 사본은 C 또는 다른 디스크로 지정하세요. 같은 물리 디스크의 두 폴더는 디스크 고장 대비가 아닙니다.

ZIP에는 계정, 비밀번호 해시, 이메일, 다이어리, 세션 등 개인정보가 포함됩니다. ZIP 자체는 암호화하지 않습니다. 백업 폴더는 운영 계정과 관리자만 읽도록 NTFS 권한을 제한하고 암호화된 디스크/저장소를 사용하세요. 웹 공개 폴더, Git, 공개 클라우드 링크에 넣지 마세요. `.env`와 외부 업로드 파일은 DB ZIP에 포함되지 않으므로 별도로 보호·백업해야 합니다.

### 검사와 보관

1. H2의 `BACKUP TO`로 실행 중에도 일관된 ZIP을 생성합니다. 열린 `.mv.db`를 복사하지 않습니다.
2. ZIP 내부 파일명·크기·CRC를 검사하고 새로운 `restore-check-*` 폴더에만 풉니다.
3. 사본을 읽기 전용 H2로 열어 테이블별 행 수와 첫 행을 읽습니다. `places`와 `user_accounts`가 없으면 실패합니다. 앱을 실행하지 않으므로 마이그레이션이나 관광공사 수집은 수행하지 않습니다.
4. SHA-256과 검사 시각, 테이블별 개수를 `.zip.json`에 기록합니다. 개인정보 내용은 기록하지 않습니다.
5. 추가 사본의 SHA-256을 비교합니다. 정상 기록과 짝을 이룬 자체 백업만 최근 N개 보관합니다. 임의 ZIP이나 손상된 기록은 삭제하지 않습니다. 권한 부족 등 정리 실패는 서버 로그를 확인하세요.

검사 임시 DB는 완료·실패 시 삭제합니다. PC 강제 종료로 `.partial`이나 `restore-check-*`가 남을 수 있으니, 서버와 백업 작업이 멈췄음을 확인한 후 해당 생성물만 점검하세요. 이 검사는 DB가 열리는지 확인하는 것으로, 모든 사용자 기능의 복구 성공을 보장하지는 않습니다.

## 복원 훈련과 실제 복구

빌드한 JAR로 백업을 독립 검사할 수 있습니다. 아래 상대 경로는 프로젝트 루트 기준이며, 백업 파일명은 실제 파일로 바꿉니다.

```powershell
java -jar backend/build/libs/backend-0.0.1-SNAPSHOT.jar --verify-backup backup/database/실제백업.zip backup/restore-drill
```

이 명령은 Spring 서버를 시작하거나 운영 DB를 덮어쓰지 않습니다. 검사 JSON을 출력하며 실패하면 종료 코드가 0이 아닙니다. H2 기본 연결 규칙인 `MODE=MariaDB;DATABASE_TO_LOWER=TRUE`를 사용합니다. **신뢰하는 숨길 자체 백업만** 검사하세요. `.env`는 읽지 않으므로 DB 사용자/비밀번호를 바꿨다면 `SOOMGIL_DB_USERNAME`, `SOOMGIL_DB_PASSWORD`를 비공개 프로세스 환경에 설정해야 합니다. 명령줄 인자로 비밀번호를 넣지 마세요.

실제 장애 복구는 자동 덮어쓰기 API로 제공하지 않습니다.

1. 공개 프록시와 백엔드를 중지하고 DB를 쓰는 프로세스가 없는지 확인합니다. 손상 의심 DB도 원인 분석용으로 별도 보존합니다.
2. 복구할 날짜를 선택하고 ZIP의 SHA-256을 별도로 보관한 기록과 비교합니다. 위 명령으로 검사합니다. DB와 JSON을 함께 변조할 수 있는 공격에 SHA 비교만으로 대응할 수는 없습니다.
3. 검증된 ZIP을 **새로운 빈 비공개 복구 폴더**에 풉니다. 기존 운영 DB 위에 풀지 않습니다. 새 DB 파일명을 기준으로 `SOOMGIL_DB_URL`을 지정할 준비를 합니다.
4. 복원 DB에 남은 로그인 세션과 인증 요청을 폐기합니다. 동일 H2 버전의 관리 도구에서 **복원 사본에만** 아래 SQL을 실행합니다. 이전 버전 백업에 테이블이 없으면 먼저 스키마를 확인합니다.

```sql
DELETE FROM spring_session_attributes;
DELETE FROM spring_session;
DELETE FROM email_challenges;
```

5. 외부 접속을 막은 채 복원 DB로 앱을 실행하여 로그인, 관리자 권한, 장소 검색, 찜, 다이어리를 점검합니다. 관광공사 동기화는 점검 중 꺼 둡니다.
6. 백업 이후 변경한 비밀번호·탈퇴·차단·권한은 되돌아갈 수 있습니다. 이 변경 내역을 복구하고 필요한 비밀번호 재설정/사용자 안내를 마친 후 공개합니다.
7. 복구 직후 새 백업을 만들고 시각·데이터 개수·복구에 걸린 시간·유실된 기간을 기록합니다. 월 1회 별도 환경에서 반복합니다.

매일 1회 백업의 일반적인 유실 범위 목표는 최대 약 24시간입니다. 백업 실패나 PC 장기 종료 시 더 길어집니다. 현재 복구 시간 보장/SLA는 없습니다.

## 장애 감시

한 번 확인:

```powershell
powershell -NoProfile -File scripts/watch-service.ps1 -Once -BackupDirectory "C:/Users/ses18/soomgil/backup/database"
```

`-Once` 없이 실행하면 30초마다 확인합니다. `/api/health`의 DB 연결, 백업 생성 후 26시간 경과, 추가 사본 실패를 검사합니다. 연속 3회 실패 시 장애, 이후 정상 시 복구를 한 번 기록합니다. 상태는 `runtime/monitor/state.json`, 이력은 `alerts.jsonl`입니다. 오래된 이력 파일은 운영자가 보관 기간에 맞춰 관리합니다. `-Once` 결과는 JSON의 `problems`를 확인하세요.

감시 프로세스의 비공개 환경변수 `SOOMGIL_OPS_ALERT_WEBHOOK`에 HTTPS Slack/Discord 호환 수신 URL을 설정하면 일반적인 장애·복구 문장을 보냅니다. 사용자 정보, API 키, DB 내용을 보내지 않습니다. 전송 실패는 5분 간격 재시도하며 재시작에도 대기 상태를 보존합니다. 수신처가 없으면 파일 기록만 하며 실제 푸시/메일 발송 성공으로 표시하지 않습니다. 이 스크립트는 루트 `.env`를 읽지 않습니다.

같은 PC의 감시만으로 정전·인터넷 전체 단절을 감지해 외부에 알릴 수는 없습니다. 공개 후 **다른 서버의 외부 가동 감시**도 실제 HTTPS `/api/health`에 연결하고 장애/복구 수신 시험을 해야 합니다. 장애 감시가 DB 장애 시 서버를 강제 재시작하지는 않습니다.

Windows PowerShell에서 스크립트 실행 정책 오류가 나면 관리자 정책에 맞는 서명/실행 승인이 필요합니다. 시스템 전체 실행 정책을 끄지 마세요. 서비스 설정은 검토한 로컬 스크립트용 `RemoteSigned`를 해당 프로세스에만 요청하며, 조직의 강제 정책을 우회하지 않습니다.

## Windows 운영 배포

운영 대상은 학교의 Windows 지리산 서버로 지정됐습니다. 호스트 전용/공유 여부, HTTPS와 접속 권한은 아직 확인이 필요합니다. [지리산 배포 준비](JIRI_DEPLOYMENT.md)를 먼저 확인하세요. 실행 중인 개발 서버나 OS 서비스를 자동 변경하지 않습니다.

1. 운영 프로젝트는 고정된 로컬 디스크에 두고 테스트 계정/임시 비밀번호를 제거하거나 변경합니다. `SOOMGIL_DEMO_USERS=false`만으로 이미 만들어진 테스트 계정이 삭제되지는 않습니다. 관리자 계정과 Gmail 실제 수신, OAuth 운영 도메인 등록, 노출된 비밀키 재발급을 완료합니다.
2. `frontend`에서 `npm ci`, `npm run build`, `backend`에서 `gradlew.bat test bootJar`를 실행합니다. Vite 개발 서버를 공개하지 않습니다.
3. 비공개 `.env`에 `SOOMGIL_PUBLIC_ORIGIN=https://실제도메인`, `SOOMGIL_ADMIN_USERNAME=실제관리자아이디`, `SOOMGIL_DEMO_USERS=false`를 지정합니다. production 프로필은 HTTPS 출처, 단일 CORS, Secure 쿠키, 루프백 바인딩, 관리자 지정이 빠지면 시작을 거부합니다.
4. 공식 Caddy Windows 바이너리를 `deploy/windows/caddy.exe`에 준비합니다. 공식 WinSW 배포판을 각 XML과 이름이 같게 `soomgil-backend.exe`, `soomgil-web.exe`, `soomgil-monitor.exe`로 준비합니다. 신뢰하는 버전을 고정하고 배포 해시를 확인합니다. 실행 파일은 Git에 넣지 않습니다.
5. 서비스 환경에 `JAVA_HOME`, `SOOMGIL_DOMAIN`(호스트명만)을 지정합니다. Web 서비스 XML은 `SOOMGIL_WEB_ROOT`를 `frontend/dist`로 지정합니다. Caddy는 `.env`를 읽지 않습니다. **프로젝트 루트나 DB 폴더를 웹 루트로 지정하면 안 됩니다.**
6. 기본 서비스 계정은 `NT AUTHORITY\LocalService`입니다. 프로젝트 실행 파일에는 읽기/실행만, `backend/data`, `backup`, `runtime`, Caddy 인증서 저장소에는 필요한 수정 권한만 부여합니다. `.env`는 백엔드 운영 계정/관리자에게만 허용합니다. 더 강한 분리가 필요하면 개별 비관리자 서비스 계정으로 교체합니다. 관리자/Everyone 전체 권한으로 우회하지 마세요.
7. Caddy 설정을 `caddy.exe validate --config ../Caddyfile --adapter caddyfile`로 검사합니다. 실제 도메인 DNS를 서버로 연결하고 웹 접속용 80/443만 허용합니다. 8080/5173/DB 포트는 공개하지 않습니다. production 백엔드는 127.0.0.1:8080에만 바인딩합니다.
8. 관리자 터미널의 `deploy/windows`에서 각 `soomgil-*.exe install`, `soomgil-*.exe start`로 설치·실행합니다. 서비스 설치/방화벽 변경은 배포 시 직접 승인해서 진행합니다. 기존 개발 백엔드와 동시에 같은 DB를 열지 않습니다.
9. HTTP→HTTPS, 브라우저 새로고침/경로 직접 진입, Secure 쿠키 로그인, CSRF, Google/Naver 콜백, 이메일 수신, 관리자 접근 차단을 실제 도메인에서 확인합니다. 서비스 비정상 종료 후 10/30/60초 재시작과 PC 재부팅 후 시작도 시험합니다. 정상 종료/수동 중지는 장애 재시작 대상이 아닙니다.
10. 관리자에서 즉시 백업을 실행하고 별도 디스크 사본과 외부 감시 수신을 확인합니다. 자동 복구 준비가 끝난 시점은 이 실측 확인 이후입니다.

production은 로컬 Caddy의 전달 헤더만 신뢰합니다. 프록시를 다른 PC/CDN으로 변경하면 신뢰할 프록시 설정을 다시 검토해야 합니다. Caddy 인증서 상태도 서비스 계정의 영구 저장소에 보존하세요.

## 아직 필요한 운영 결정

- 지리산 서버의 전용/공유 여부, 실제 HTTPS 경로·서버 권한, 외부 장애 알림 수신처
- 암호화된 외부 백업 저장소, Gmail 실제 수신 및 비밀키 교체
- GitHub 원격 저장소: 현재 미등록. C/D 소스 백업은 클라우드 백업이 아닙니다.
- 실제 운영 DB를 이용한 백업 확인, 별도 환경 전체 복원 훈련, 장애/재부팅/HTTPS 시험

## 근거 문서

- [H2 BACKUP 명령](https://h2database.com/html/commands.html#backup)
- [Spring Boot 정상 종료](https://docs.spring.io/spring-boot/reference/web/graceful-shutdown.html)
- [Caddy 설정 패턴](https://caddyserver.com/docs/caddyfile/patterns)
- [WinSW 서비스 XML](https://github.com/winsw/winsw/blob/v3/docs/xml-config-file.md)
