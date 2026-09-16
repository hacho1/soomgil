# 지리산 서버 배포 인수인계

대상: 숨길 전용 Windows 서버 `jiri.hknu.ac.kr` (`220.68.241.128`). 전용 사용은 사용자 확인을 받았고, 실제 관리자 권한·방화벽·HTTPS는 현장에서 확인해야 합니다.

최신 추가 점검: `docs/RELEASE_PREPARATION_20260916.md`. 검색 조회 최적화, 전시 배지 1개 제한, 오프라인 배포 점검 도구가 포함됩니다.

## 먼저 알아둘 점

- 외장하드는 운반·백업용입니다. `project`를 서버 내부 디스크의 `C:\Soomgil` 같은 고정 폴더로 복사하고 그곳에서 운영합니다. 기존 서비스나 파일을 덮어쓰지 않습니다.
- `private`에는 실제 회원·다이어리 DB 백업과 원래의 비밀 설정이 들어 있습니다. 암호화된 묶음이 아니므로 분실·공유에 주의하고, 웹 공개 폴더와 GitHub에 절대 넣지 않습니다.
- `private/config.env`는 로컬 개발 설정의 사본입니다. 그대로 공개 운영하지 말고 아래 운영 설정을 먼저 적용합니다. 채팅에 노출됐던 비밀키와 Gmail 앱 비밀번호도 교체합니다.
- `project`에는 미커밋 수정까지 포함한 최신 소스, 실행 JAR, 빌드된 화면, Java 17 JDK, Node 22, Caddy, WinSW가 있습니다. `history.bundle`은 마지막 커밋까지의 Git 이력만 담으며 최신 소스 전체와 다를 수 있습니다.
- `manifest.json`에 각 파일의 SHA-256이 있습니다. 이것은 전송 오류 확인용이며 악의적인 파일/manifest 동시 변조를 막는 전자서명은 아닙니다.
- Caddy/WinSW를 설치하거나 서버를 외부에 공개하는 동작은 포장 과정에서 실행하지 않습니다. WinSW .NET461 배포판은 공식 릴리스에서 받았지만 서명된 파일이 아니며 서버의 .NET Framework 4.8 사용을 권장합니다. Java·Node·Caddy는 공식 배포 해시를 대조했습니다.

## 서버에서 진행할 순서

1. 서버에 Windows 관리자 권한이 있는지, 기존 80/443/8080 포트 점유가 없는지 확인합니다. 기존 프로그램을 임의로 종료하지 않습니다.
2. 복사 후 `scripts/verify-deployment-bundle.ps1`로 외장하드 묶음의 모든 파일 해시를 확인합니다.
3. 백업은 `private`의 ZIP과 JSON을 사용합니다. 실행 중인 PC의 `backend/data/soomgil.mv.db`를 복사하지 않습니다. **백업 시각 이후의 새 가입·수정 기록은 포함되지 않으므로 이전 직전 최종 백업 시각을 확인합니다.**
4. 백업을 `java -jar backend/build/libs/backend-0.0.1-SNAPSHOT.jar --verify-backup ...`으로 검사한 뒤 새 비공개 폴더에 복원합니다. 정확한 명령과 복원 세션 폐기는 `docs/OPERATIONS.md`를 따릅니다. 원본 백업 ZIP은 보존합니다.
5. 검토한 비밀 설정을 `C:\Soomgil\.env`에 둡니다. `SOOMGIL_PUBLIC_ORIGIN=https://jiri.hknu.ac.kr`, `SOOMGIL_DEMO_USERS=false`, 실제 개인 관리자 아이디, SMTP와 관광공사 키, 복원 DB 경로를 설정합니다. `test1`, `test01`, `demo`는 운영 모드에서 로그인/관리자로 사용할 수 없습니다. 관리자 아이디는 먼저 개인 계정으로 가입돼 있어야 합니다.
6. 네이버 서비스 주소와 콜백 `https://jiri.hknu.ac.kr/api/auth/naver/callback`, Google JavaScript 원본 `https://jiri.hknu.ac.kr`을 제공자 콘솔에 등록합니다. 미연결 소셜 로그인을 숨기려면 관련 Client ID/Secret을 비워 둡니다. 소셜 로그인 제공자의 검수·테스트 사용자 제한도 확인합니다.
7. `deploy/windows`의 서비스 XML을 확인합니다. 백엔드는 동봉된 `runtime/java/bin/java.exe`, 웹서버는 `jiri.hknu.ac.kr`과 `frontend/dist`를 사용합니다. LocalService에 코드 읽기/실행, DB·백업·로그·인증서 폴더에 필요한 수정 권한을 줍니다. Everyone 전체 권한은 주지 않습니다.
8. Caddy 설정 검사 후 서비스 설치를 승인하고 진행합니다. HTTPS용 80/443만 공개하고 8080/5173/DB 포트는 공개하지 않습니다. 관리자 콘솔 실행, WinSW 서비스 설치, 방화벽 변경은 현장에서 따로 확인합니다.
9. `node scripts/check-release.mjs https://jiri.hknu.ac.kr --production`과 실제 휴대폰에서 가입·메일·로그인·검색·찜·다이어리 저장·재로그인을 확인합니다. 소셜 로그인을 의도적으로 껐다면 검사 결과에 해당 항목이 실패로 남는 것이 정상이며 전체 통과로 표현하지 않습니다.
10. 서버 재부팅 후 서비스 자동 시작과 데이터 유지, 즉시 DB 백업, 다른 디스크 사본과 복원 검사를 확인한 뒤 소수 사용자에게 공개합니다.

**5~7번 설정과 DB 복원 후, 서비스를 설치하기 전에** 프로젝트 루트에서 아래 오프라인 점검을 실행합니다. DB 연결, 메일 발송, 설치, 설정 변경은 수행하지 않습니다. 실패 시 종료 코드 2이며 비밀값은 출력하지 않습니다.

```powershell
.\runtime\java\bin\java.exe -jar .\backend\build\libs\backend-0.0.1-SNAPSHOT.jar --check-deployment .
# 또는 실행 정책이 허용하는 PowerShell에서:
pwsh -NoProfile -File scripts/check-deployment.ps1
```

`configurationPassed=true`는 파일·설정 점검 통과일 뿐입니다. `launchApproved`는 항상 false이며 실제 DNS·인증·저장·재부팅 시험은 따로 수행해야 합니다. 관리자 계정의 DB 존재 여부, 바이너리 무결성, 파일 권한은 이 도구가 대신 검증하지 않습니다. 외장하드 묶음의 `private/config.env`와 DB ZIP은 아직 실행 위치에 복원된 것이 아니므로 묶음 상태 그대로 검사하면 누락으로 판정하는 것이 정상입니다.

## 동봉 런타임 사용

서버 내부 프로젝트 루트에서 PowerShell 세션에만 다음 설정을 적용할 수 있습니다. 서버 전체 PATH를 변경할 필요가 없습니다.

```powershell
$env:JAVA_HOME = Join-Path (Get-Location) 'runtime/java'
$env:PATH = "$env:JAVA_HOME\bin;$(Join-Path (Get-Location) 'runtime/node');$env:PATH"
java -version
node --version
```

서비스 실행에는 Node나 개발 서버가 필요하지 않습니다. 추가 수정 후 프론트 재빌드에는 `npm ci`와 `npm run build`, 백엔드 재빌드에는 동봉 Java JDK와 Gradle의 최초 의존성 다운로드 인터넷 연결이 필요합니다.

Codex 설치와 로그인은 프로젝트 자체의 서버 실행과 별개입니다. 학교 PC에서 작업할 때는 이 문서와 `docs/DEPLOYMENT_AUDIT_20260916.md`를 먼저 읽고, 본인의 계정으로 로그인합니다. 개인 계정의 로그인 정보나 Codex 설정 폴더를 공개 배포 파일에 넣지 않습니다.

## 현재 검증 범위와 남은 작업

검사 결과는 `docs/DEPLOYMENT_AUDIT_20260916.md`를 확인합니다. 로컬 자동 검사 통과는 학교 서버 배포 완료를 의미하지 않습니다. 실제 도메인의 OAuth/메일, TLS 인증서, 외부망 접속, 권한과 재부팅 복구 검증은 서버에서 수행해야 합니다.

## 이전 직전 최종 백업

공유 모드에서는 관리자 백업 API를 열지 않습니다. 공유 백엔드를 정상 종료한 뒤, 프로젝트 루트에서 다음 도구로 정지된 DB를 백업할 수 있습니다.

```powershell
powershell -NoProfile -ExecutionPolicy RemoteSigned -File scripts/backup-stopped-database.ps1 -DatabaseFile backend/data/soomgil.mv.db -Destination backup/database
```

이 도구는 DB 파일을 독점 접근으로 열어 사용 중이면 실패하고, ZIP을 만든 뒤 별도 사본을 읽기 전용으로 열어 검사합니다. 실제 DB는 수정하지 않습니다. 기본 H2 계정이 아니라면 검사용 DB 자격 증명을 비공개 프로세스 환경에 지정해야 합니다. `snapshotAt`이 포함 시점이며 `completedAt`은 검사 완료 시점입니다. 백업 이후 서버를 다시 열어 가입·수정하면 그 변경은 이미 만든 백업에 포함되지 않습니다.
