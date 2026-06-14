---
story: FE-1
status: draft
current_implementation: done(mock)
scope: frontend-only (vue/ 하위)
related_fr: [FR-21, FR-22, FR-23]
related_files:
  - src/styles/tokens.css
  - src/styles/base.css
  - src/components/CgSprite.vue
  - src/components/CgGauge.vue
  - src/components/CgRadar.vue
  - src/components/CgStatTile.vue
  - src/components/CgEmo.vue
  - src/components/CgConfetti.vue
  - src/components/AppNav.vue
  - src/components/Mascot.vue
  - src/components/ThemeSwitcher.vue
---

# Story FE-1: 디자인 시스템·테마·공통 컴포넌트 기반

Status: draft

## Story

As a 사용자,
I want 일관된 픽셀 디자인 시스템과 3개 테마(cozy/device/cli) 위에서 동작하는 화면을,
so that 모든 표면이 동일한 시각 언어·접근성 기준으로 보인다.

## 목적과 범위

- DESIGN.md에서 추출한 토큰(`tokens.css`)과 `cg-*` 컴포넌트 클래스(`base.css`)를 기반으로 확정.
- 테마 스위처(`<html data-theme>`, localStorage 저장)와 공통 컴포넌트 세트 확정.
- 성장 규칙 시각화 컴포넌트(게이지·레이더·감정칩)의 표현 계약 확정.

## 현재 구현 상태

- 토큰/base 스타일·테마 스위처·공통 컴포넌트 구현 존재(mock 데이터로 렌더).
- 이 스토리는 구현을 명세에 맞춰 검증·보강하는 데 초점.

## Acceptance Criteria

### AC1 — 테마 전환·영속화
- **Given** 앱이 로드되고 `ThemeSwitcher`가 표시된 상태에서,
- **When** 사용자가 `cozy / device / cli` 중 하나의 테마 버튼을 누르면,
- **Then** `<html data-theme>`가 해당 키로 즉시 바뀌고, 선택값이 `localStorage('cg.theme')`에 저장되며, 누른 버튼만 `aria-pressed="true"`(`cg-btn--primary`)로 표시된다.
- **And** 새로고침해도 마지막 선택 테마가 복원되고, 저장값이 없으면 기본 `cozy`로 시작한다(`localStorage` 접근 실패 시에도 `cozy` 폴백).
- **And** 세 테마 모두 동일한 시맨틱 슬롯(`tokens.css`)을 공유하므로 어느 테마에서도 레이아웃이 깨지지 않는다.

### AC2 — 색만으로 정보 전달 금지(감정 표기)
- **Given** 감정 상태(`joy | sad | angry`)를 표시하는 어떤 표면에서든,
- **When** `CgEmo`가 렌더되면,
- **Then** 색 점(`--joy/--sad/--angry`) + 얼굴(😊/😢/😠) + 텍스트 라벨(기쁨/슬픔/화남)을 **항상 함께** 표시한다(색상 단독 전달 금지).
- **And** 알 수 없는 emotion 값이 들어오면 `joy`로 안전 폴백한다.

### AC3 — Reduce Motion 대응
- **Given** OS/브라우저가 `prefers-reduced-motion: reduce`로 설정된 사용자가,
- **When** 애니메이션을 포함한 화면(마스코트·컨페티·진화 연출 등)에 진입하면,
- **Then** `base.css`의 reduced-motion 미디어쿼리가 적용되어 모션이 축소/제거되고, `Mascot`은 숨겨지며(`display:none`), 정보 전달은 모션 없이도 유지된다.

### AC4 — 공통 컴포넌트 props/상태 계약
- **Given** 화면 스토리(FE-3~9)가 공통 컴포넌트를 사용할 때,
- **When** 각 컴포넌트에 정해진 props를 전달하면,
- **Then** 다음 계약대로 렌더된다:
  - `CgSprite` — `emotion` + 진화 여부 + `imageStatus(PENDING|READY)`에 따른 표현(PENDING 시 플레이스홀더, FE-10 연계).
  - `CgGauge` — 값/최대값(육아점수, 진화 임계 `EVOLVE_THRESHOLD=1000` 기준 표시).
  - `CgRadar` — 5스탯(`algo·cs·db·net·fw`) 5각형, `STAT_LABELS` 라벨 사용.
  - `CgStatTile` — 스탯별 수치 타일, `CgConfetti` — 완료 연출, `AppNav`/`Mascot`/`ThemeSwitcher` — 전역 셸.
- **And** 어떤 컴포넌트도 필수 데이터 누락 시 throw 없이 빈/기본 상태로 렌더된다.

## 검증

- `npm run build` 통과(타입/구문 오류 없음).
- 세 테마 각각에서 주요 화면 시각 점검 + 새로고침 후 테마 영속 확인.
- 접근성: 감정 칩이 색+얼굴+라벨 동시 표기되는지, reduced-motion에서 모션이 멎는지 확인.
- 변경은 모두 `vue/` 하위 파일로 한정(외부 파일 수정 금지).
