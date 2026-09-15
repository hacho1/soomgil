# 배지 그림과 모션

## 그림 출처

24개 배지는 Microsoft Fluent Emoji의 3D PNG를 사용한다.

- 공식 저장소: https://github.com/microsoft/fluentui-emoji
- 고정 버전: `1ffb34c752ecf5d402f04cfb4b392c77f57c54bc`
- 라이선스: MIT, Copyright (c) Microsoft Corporation.
- 사용 파일: `frontend/public/assets/badges/`
- 원본 라이선스: 같은 폴더의 `LICENSE.txt`
- 원본 경로와 ID 연결: `frontend/src/data/badge-art.json`
- 파일별 SHA-256: 같은 에셋 폴더의 `integrity.json`

그림 파일은 원본 그대로 보관한다. 잠금 상태의 채도와 크기, 배경색은 화면 CSS로 처리한다. 앱 정보에도 출처와 라이선스 링크를 제공한다. 기존 배지 ID, 조건, EXP, 획득 기록, 전시 개수 제한은 변경하지 않는다.

배지 목록, 상세 팝업, 프로필 전시, 프로필 편집, 새 배지 알림에서 같은 `BadgeArtwork` 컴포넌트를 사용한다. 그림 로딩 실패 시 기존 Award 아이콘으로 대체한다. 배지는 사용자별 획득 여부와 무관하게 그림 파일 자체는 공개된 리소스다.

원본을 다시 받을 때 루트에서 `node scripts/fetch-badge-assets.mjs`를 실행한다. 고정 버전으로 내려받고 PNG 형식을 확인한다. 총 PNG 용량은 약 764 KiB다. 화면에서는 이미지 지연 로딩을 사용하며 CDN에 의존하지 않는다.

## 동작

- 장소 카드와 배지 카드는 화면에 처음 들어올 때 12px 아래에서 380ms 동안 나타난다. 다시 위아래로 스크롤한다고 계속 재생하지 않는다.
- 배지를 누르면 큰 그림, 획득 조건, 진행도, EXP 보상을 볼 수 있다. 이미 획득했다면 프로필 전시 편집으로 이동할 수 있다.
- PC에서 배지에 마우스를 올리면 작은 기울기/상승 효과를 준다. 모바일에는 호버 효과를 적용하지 않는다.
- 모달과 알림은 짧게 등장한다. 새 배지 알림은 기존의 ‘새로운 알림이 도착했어요!’ 제목을 유지하고 3초 뒤 사라진다. 첫 로그인에 과거 배지를 다시 축하하지 않는다.
- `prefers-reduced-motion: reduce`에서는 CSS 및 카드의 Web Animations를 끈다. 설정이 재생 도중 바뀌면 실행 중인 카드 모션도 취소한다. JavaScript 모션 기능이 없더라도 내용은 기본적으로 보인다.

## 검증

프론트 테스트는 배지 ID/PNG 대응, 파일 무결성, 라이선스 포함, 한 번만 재생, 동작 줄이기, 취소 정리, 미지원 브라우저 fallback을 검사한다. `npm.cmd test`와 `npm.cmd run build`를 실행한다.
