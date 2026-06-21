---
story: FE-2
status: draft
current_implementation: done(real)
scope: frontend-only (vue/ 하위)
related_fr: [FR-1, FR-2, FR-24, FR-25, FR-27, FR-28]
related_files:
  - src/router/index.js
  - src/stores/auth.js
  - src/api/client.js
  - src/views/LoginView.vue
---

# Story FE-2: 라우팅·인증 가드·실제 인증 연동

Status: draft

## Story

As a 사용자,
I want 로그인/회원가입을 실제 백엔드로 처리하고 보호 라우트가 인증 가드로 막히는 SPA를,
so that 인증된 상태에서만 캐릭터·리포트·랭킹 화면을 사용할 수 있다.

## 목적과 범위

- LoginView ↔ 실제 Spring Boot 인증 API(가입/로그인/me/로그아웃) 연동.
- `api/client.js`: access token 부착 + 401 시 refresh rotation 처리.
- `router/index.js`: 인증 가드, 미인증 시 /login 리다이렉트.
- 인증 오류를 공통 오류 형식(FR-28)에 맞춰 표시.

## 현재 구현 상태

- 실제 API 연동 완료(done(real)). refresh rotation 포함.
- 이 스토리는 엣지 케이스(토큰 만료·회전 실패·동시 요청) 검증에 초점.

## Acceptance Criteria

### AC1 — 로그인/가입 성공·실패 흐름
- **Given** `LoginView`에서 이메일·비밀번호를 입력한 사용자가,
- **When** 로그인을 제출하면,
- **Then** `auth.login()`이 토큰쌍을 받아 `localStorage('cg.tokens')`에 저장하고, `users.me()`로 사용자 정보를 채운 뒤 대시보드로 이동한다.
- **And** 가입(`signup`)은 성공 직후 자동 로그인되어 동일 흐름으로 진입한다(Flow 2, 첫 실행 매끄러움).
- **And** 실패 시 `authMessage(e)`로 변환된 한국어 메시지를 표시한다: 자격증명 오류(`AUTH_INVALID_CREDENTIALS`)·이메일 중복(`USER_EMAIL_CONFLICT`)·검증 실패(`VALIDATION_FAILED`, 비밀번호 12~64자)·네트워크 오류는 각각 구분된 카피로 안내하며 화면 전환은 일어나지 않는다.

### AC2 — 보호 라우트 가드
- **Given** 미인증 상태(`isAuthenticated()===false`)의 사용자가,
- **When** `/login` 외의 보호 라우트(대시보드·캐릭터·리포트·퀴즈·랭킹·도감·게시판)에 접근하면,
- **Then** `router.beforeEach`가 `/login`으로 리다이렉트하며 원래 목적지를 `query.redirect`에 보존한다.
- **And** 이미 인증된 사용자가 `/login`에 접근하면 대시보드로 보낸다.
- **And** 알 수 없는 경로는 `/`로 리다이렉트한다(hash history 기반, 확장프로그램/딥링크에서도 404 없음).

### AC3 — 401 → refresh rotation → 재시도
- **Given** 만료된 access token을 가진 인증 사용자가,
- **When** `authed()` 보호 요청이 401을 받으면,
- **Then** 저장된 refresh token으로 `auth.refresh()`를 1회 호출해 새 토큰쌍을 저장(rotation)하고 원 요청을 새 access token으로 재시도한다.
- **And** 401 이외의 오류는 회전 없이 그대로 throw된다.
- **And** refresh 자체가 실패하면 로그아웃 상태로 간주(`bootstrap`에서 토큰 폐기·`/login` 유도)한다.

### AC4 — 로그아웃 후 토큰 무효화
- **Given** 로그인 상태의 사용자가,
- **When** 로그아웃하면,
- **Then** 서버 `auth.logout(refreshToken)`을 호출(204 멱등, 실패해도 무시)하고 `localStorage` 토큰·데모 플래그를 제거하며 `state.tokens/user`를 null로 비운다.
- **And** 이후 보호 라우트 접근은 다시 가드에 의해 `/login`으로 막힌다.

### AC5 — 데모/오프라인 모드(경계 명시)
- **Given** 백엔드/CORS 없이 SPA·확장프로그램 팝업을 둘러봐야 하는 상황에서,
- **When** `demoLogin()`을 사용하면,
- **Then** `cg.demo=1` 플래그와 가짜 토큰으로 인증 게이트만 통과시키고, 게임 데이터는 mock(`stores/game.js`)으로 동작한다(실제 인증 흐름과 분리).

## 검증

- 실제 Spring Boot `local` 프로필에 연동해 가입→자동로그인→me→로그아웃 happy path 확인.
- 엣지 케이스: access 만료 시 401→refresh 재시도 1회 성공, refresh 실패 시 로그아웃 처리, 오류코드별 한국어 카피 확인.
- 미인증 딥링크 접근 시 `redirect` 쿼리 보존 확인, `npm run build` 통과.
- 변경은 모두 `vue/` 하위 파일로 한정.
