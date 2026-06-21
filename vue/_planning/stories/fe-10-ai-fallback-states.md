---
story: FE-10
status: done
current_implementation: done(mock)
scope: frontend-only (vue/ 하위)
related_fr: [FR-16]
related_files:
  - src/stores/game.js
  - src/components/CgSprite.vue
  - src/components/CgState.vue
  - src/constants/aiStates.js
  - (전 표면 공통 상태 패턴)
---

# Story FE-10: AI Fallback·로딩·에러 상태 패턴

Status: done

## Story

As a 사용자,
I want AI 처리(이미지·일일 레포트·퀴즈 채점)가 실패하거나 대기 중일 때도 흐름이 끊기지 않는 화면을,
so that 어떤 상황에서도 학습 기록과 사용 흐름이 유지된다.

## 목적과 범위

- 횡단 상태 패턴 일관화: 로딩(채점 중…) / 대기(자정 배치 pending) / Fallback(AI가 잠깐 쉬는 중 — 학습은 저장됐어요).
- 이미지 흐름 C: PENDING 플레이스홀더 → READY 스프라이트, 실패 시 Fallback.
- 각 화면 스토리(FE-4/6/7)의 완료 기준에 본 상태 패턴 적용을 포함.
- 카피 톤은 EXPERIENCE Voice 표 준수(에러를 "Error"로 노출 금지).

## 현재 구현 상태

- 부분 존재(PENDING 플레이스홀더 등). 일관 패턴화는 미상세(draft).

## Acceptance Criteria

### AC1 — 로딩 상태 표준
- **Given** 즉시 처리되는 AI 작업(퀴즈 채점 등)이 진행 중일 때,
- **When** 사용자가 제출/요청하면,
- **Then** 일관된 로딩 표현("채점 중…" 등)이 표시되고, 처리 중 중복 제출/조작은 막힌다(버튼 비활성). 완료 시 결과로 자연스럽게 전환된다.

### AC2 — 대기(배치) 상태 표준
- **Given** 자정 배치로 처리되는 작업(일일 레포트)이 `pending`일 때,
- **When** 사용자가 해당 결과 표면을 보면,
- **Then** 즉시 채점과 구분되는 대기 카피("자정에 분석돼요 / 내일 오전 9시 도착")가 일관되게 표시되고, 결과 영역은 도착 전까지 대기 상태로 유지된다(흐름 A/흐름 B 카피 혼용 금지).

### AC3 — Fallback 상태 표준 + 데이터 보존
- **Given** AI 처리(이미지·일일 레포트·퀴즈 채점)가 실패(`failed`)했을 때,
- **When** 실패 상태를 사용자에게 보여줄 때,
- **Then** 에러를 "Error"로 노출하지 않고 친화적 Fallback 카피("AI가 잠깐 쉬는 중 — 학습은 저장됐어요." 톤, EXPERIENCE Voice)를 표시한다.
- **And** 사용자가 입력·기록한 데이터(리포트·퀴즈 답안 등)는 실패와 무관하게 **보존**되어 흐름이 끊기지 않는다.

### AC4 — 이미지 PENDING→READY/실패 (흐름 C)
- **Given** 캐릭터 이미지의 `imageStatus`가 `PENDING`일 때,
- **When** 생성이 완료되면 `READY`로,
- **Then** 플레이스홀더 → 실제 스프라이트(`CgSprite`)로 전환된다.
- **And** 생성이 실패하면 깨진 이미지 대신 Fallback 스프라이트/플레이스홀더를 유지하며 캐릭터 사용(생성·상세·대시보드)은 정상 동작한다.

### AC5 — 횡단 적용
- **Given** FE-4(생성/이미지)·FE-6(일일 레포트)·FE-7(퀴즈 채점) 표면에서,
- **When** 각 화면이 로딩/대기/실패 상태를 만나면,
- **Then** 위 AC1~4의 동일 패턴·카피 톤을 재사용한다(상태 패턴이 화면별로 제각각이지 않음).

## 검증

- 로딩/대기/실패/이미지 4상태 카피·표현 일관성 확인(흐름 A·B 카피 미혼용).
- 실패 시 입력 데이터 보존 확인(리포트/퀴즈 답안 유지).
- "Error" 원문 노출이 없는지 카피 점검, `npm run build` 통과.
- 상태 패턴 컴포넌트화/적용은 모두 `vue/` 하위로 한정.

## Tasks / Subtasks

- [x] 상태 카피 단일 출처(SSOT) + 공통 컴포넌트 (AC1·AC2·AC3·AC5)
  - [x] `src/constants/aiStates.js` — 로딩/대기/Fallback 카피 상수화(흐름 A·B 혼용 방지)
  - [x] `src/components/CgState.vue` — 로딩(스피너)/대기/Fallback 공용 블록, `role="status"`·아이콘+카피 병기
- [x] 이미지 흐름 C Fallback (AC4)
  - [x] `CgSprite.vue` `failed` prop — 깨진 이미지 대신 기본 스프라이트 + "기본 모습" 표시
  - [x] `game.js` `imageStatus` FAILED 경로 + `retryImage()`, 실패해도 캐릭터 사용 가능
- [x] 실패 경로 + 데이터 보존 (AC3)
  - [x] `submitQuiz(…, { fail })` — 답안 보존·점수 미반영·재시도 허용
  - [x] `deliverDailyReport({ fail })` — status='failed'·작성 리포트 보존·점수 변화 0
- [x] 횡단 적용 (AC5)
  - [x] QuizView(로딩/Fallback/재시도), ReportResultView(대기/Fallback), DashboardView(대기/Fallback/스프라이트)
  - [x] CreationCompleteView·CharacterCreateView·CharacterDetailView(이미지 Fallback)
- [x] 검증
  - [x] `_tests/fe10-states.mjs` 20/20 통과(답안·리포트 보존, 이미지 FAILED→복구)
  - [x] `npm run build`(vite) 통과 — 76 모듈 변환, 번들 생성
  - [x] 사용자 노출 "Error" 원문 없음(실패는 친화적 Fallback 카피)

## Dev Agent Record

### Completion Notes

- AI 상태 패턴을 단일 출처로 통합: 카피는 `constants/aiStates.js`, 표현은 `components/CgState.vue`.
  퀴즈·리포트·이미지 표면이 동일 컴포넌트·카피를 재사용하므로 화면별 제각각 상태가 제거됨(AC5).
- AC1 로딩: 퀴즈 채점 중 `CgState(loading)` + 옵션/버튼 비활성으로 중복 제출 차단.
- AC2 대기: 자정 배치 카피(`WAITING.report`/`reportResult`)를 즉시 채점 카피와 분리해 흐름 A·B 혼용 방지.
- AC3 Fallback + 보존: 퀴즈 채점 실패 시 답안 보존·점수 미반영·재시도, 일일 레포트 실패 시 작성 리포트 보존·점수 0.
  실패는 모두 "AI가 잠깐 쉬는 중…" 톤으로 표시하고 "Error" 원문은 노출하지 않음.
- AC4 이미지: `imageStatus` PENDING→READY/FAILED. 실패 시 `CgSprite` 가 기본 스프라이트로 폴백(깨진 이미지 없음),
  생성/상세/대시보드 모두 정상 동작. `retryImage()`로 복구 가능.
- 각 실패 상태는 데모 토글/버튼으로 재현 가능(QuizView·CharacterCreateView 체크박스, Dashboard "(데모) 분석 실패").
- 모든 변경은 `vue/` 하위로 한정(FRONTEND.md 규칙 준수).
- 참고: 에픽 목록(`frontend-epic.md`)의 FE-10 상태는 마스터 편입 단계에서 일괄 갱신 예정.

### Debug Log

- 빌드 환경 메모: 샌드박스(linux-arm64)에서 `npm run build` 시 rollup 네이티브 바이너리 불일치(EPERM은
  macOS에서 만든 기존 `dist/` 삭제 권한 문제)로, 검증은 `vite build --outDir <clean>` 로 수행해 통과 확인.
  사용자 macOS 환경에는 영향 없음(darwin 바이너리 그대로 사용).

## File List

- src/constants/aiStates.js (신규)
- src/components/CgState.vue (신규)
- src/components/CgSprite.vue (수정 — `failed` prop·Fallback 표시)
- src/stores/game.js (수정 — 퀴즈/리포트/이미지 실패 경로·데이터 보존·`retryImage`)
- src/views/QuizView.vue (수정)
- src/views/ReportResultView.vue (수정)
- src/views/DashboardView.vue (수정)
- src/views/CreationCompleteView.vue (수정)
- src/views/CharacterCreateView.vue (수정)
- src/views/CharacterDetailView.vue (수정)
- _tests/fe10-states.mjs (신규 — store 실패/보존 로직 검증)

## Change Log

- 2026-06-14: FE-10 구현 — AI 로딩/대기/Fallback 상태 패턴 일관화 + 이미지 흐름 C Fallback + 실패 시 데이터 보존. Status: draft → review.

### Review Findings

- [x] [Review][Patch] 실패한 퀴즈 답안을 화면 재진입 시 복원 (`src/views/QuizView.vue`)
- [x] [Review][Patch] 인증 refresh 401 이후 로컬 인증 상태 정리 (`src/api/client.js`)
