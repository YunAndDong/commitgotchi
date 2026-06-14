# Commit-Gotchi — Frontend (Vue SPA)

프로토타입/목업과 SSOT 디자인 문서(`DESIGN.md`, `EXPERIENCE.md`)를 기준으로 구현한 Vue 3 + Vite SPA.
인증은 실제 Spring Boot API에 연결되어 있고, 아직 백엔드 엔드포인트가 없는
캐릭터·리포트·퀴즈·랭킹·게시판은 mock 스토어로 동작한다.

> 이 프로젝트의 모든 프론트엔드 변경은 `vue/` 하위에만 작성한다.
> `springboot/`, `docs/`, `_bmad-output/` 등 외부 파일은 참고만 하고 수정하지 않는다.

## 실행

```bash
cd vue
npm install
cp .env.example .env.local      # VITE_API_BASE_URL 확인 (기본 http://localhost:8080)
npm run dev                     # http://localhost:5173
```

빌드: `npm run build` · 미리보기: `npm run preview`

인증 화면은 실제 백엔드가 필요하다. Spring Boot를 `local` 프로필로 띄우거나
(`cd springboot && SPRING_PROFILES_ACTIVE=local ./gradlew bootRun`), 백엔드 없이
UI만 보려면 로그인 화면에서 회원가입을 시도해 응답을 확인한다.

## 디자인 시스템

`src/styles/tokens.css` — `DESIGN.md`에서 추출한 3개 테마 토큰(cozy/device/cli).
`<html data-theme>`로 전환하며 상단 내비의 테마 스위처가 제어한다(선택값 localStorage 저장).
`src/styles/base.css` — `cg-*` 컴포넌트 클래스(버튼·카드·게이지·배지·감정칩 등),
Galmuri 픽셀 폰트, hover 떠오름 / active 눌림 하드 오프셋 그림자, Reduce Motion 대응.

폰트는 `index.html`에서 CDN으로 로드(Galmuri, Gasoek One, Gothic A1 폴백).

## 구조

```
src/
├── api/client.js        # fetch 래퍼 + JWT + refresh rotation, ApiError
├── stores/
│   ├── auth.js          # 실제 인증(로그인/가입/me/로그아웃) reactive 싱글톤
│   └── game.js          # MOCK: 캐릭터·리포트·퀴즈·랭킹·게시판 + 성장 규칙
├── router/index.js      # 라우트 + 인증 가드
├── components/          # CgSprite·CgGauge·CgRadar·CgStatTile·CgEmo·CgConfetti·AppNav·Mascot·ThemeSwitcher
├── views/               # 화면별 컴포넌트(아래 표)
├── styles/              # tokens.css, base.css
└── App.vue / main.js
```

## 화면 ↔ EXPERIENCE.md IA 매핑

| 라우트 | 화면 | EXPERIENCE # | 데이터 |
|---|---|---|---|
| `/login` | 로그인/회원가입 | 1 | **실제 API** |
| `/` | 대시보드(+빈 상태) | 2-2 | mock |
| `/create` | 캐릭터 생성 폼 | 보강 | mock |
| `/complete/:id` | 생성 완료(컨페티) | 11 | mock |
| `/character/:id` | 캐릭터 상세(레이더 + 리포트/퀴즈 2-페이저) | 4 | mock |
| `/report` | 일일 리포트 작성 | 5 | mock |
| `/report/result` | AI 일일 레포트 결과 | 보강 | mock |
| `/quiz` | 퀴즈 풀이·즉시 채점(흐름 B) | 보강 | mock |
| `/ranking` | 랭킹(포디움 TOP3) | 7 | mock |
| `/codex` | 도감 커버플로우 | 3 | mock |
| `/board` | 공유 게시판 | 8 | mock |
| `/board/:id` | 공유 상세 + 리뷰 | 9 | mock |

## 두 가지 시간 리듬 (EXPERIENCE.md)

- **퀴즈 = 즉시 채점(흐름 B):** 제출 → "채점 중…" → 점수·피드백·스탯 +N·감정 갱신이 그 자리에서 재생.
- **리포트 = 자정 배치(흐름 A):** 저장 → "내일 오전 9시 도착" pending. 대시보드의
  `⏩ (데모) 내일 아침으로` 버튼이 자정 배치 결과 도착을 시뮬레이션해 점수 반영·진화 연출을 보여준다.
- **이미지 = 비동기-즉시(흐름 C):** 생성 직후 PENDING 플레이스홀더 → 약 2초 뒤 READY 스프라이트.

## 성장 규칙(PRD FR-21~23, `stores/game.js`)

육아점수 = 5스탯(알고리즘·CS·DB·네트워크·프레임워크) 총합. 1,000 최초 통과 시 1회 진화.
감정 joy/sad/angry는 색 + 얼굴 + 라벨로 병기(색만으로 전달 금지).

## 캐릭터 스프라이트

`CgSprite.vue`는 AI 생성 이미지가 들어올 자리를 대신하는 SVG 픽셀 캐릭터다.
`(is_evolved, emotion)`에 따라 표정/형태가 바뀌고 baby↔진화, grey(도감 미획득),
bob idle 모션, PENDING 플레이스홀더, 바닥 그림자를 지원한다.
실제 6프레임 스프라이트시트가 준비되면 이 컴포넌트만 교체하면 된다.

## 실제 API 연동으로 전환하기

`stores/game.js`의 각 액션은 의도적으로 API 형태로 작성돼 있다.
백엔드 엔드포인트가 생기면 mock 본문을 `authed('GET'|'POST', '/api/...')` 호출로 교체한다
(`api/client.js`의 `authed`가 access token 부착 + 401 시 refresh rotation을 처리).

## 검증 메모

이 세션에서는 격리 빌드 환경(디스크 부족)으로 `npm run build`를 실행하지 못했다.
임포트/라우트/리액티비티/ref 언래핑을 수동 점검했다. 로컬에서 `npm install && npm run build`로
최종 확인 권장.
