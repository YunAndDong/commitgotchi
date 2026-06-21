---
story: FE-3
status: draft
current_implementation: done(mock)
scope: frontend-only (vue/ 하위)
related_fr: [FR-17, FR-23]
related_files:
  - src/views/DashboardView.vue
  - src/stores/game.js
---

# Story FE-3: 대시보드 + 빈 상태

Status: draft

## Story

As a 사용자,
I want 활성 캐릭터·육아점수·감정·상태 메시지·최근 활동을 한 화면에서 보고,
so that 오늘의 성장 현황을 즉시 파악하고 다음 행동으로 이동한다.

## 목적과 범위

- 히어로 캐릭터 스테이지 + 사이드 레일(육아점수 게이지·랭킹·감정·상태 메시지·활동 로그). (EXPERIENCE #2-2)
- 캐릭터 0개 빈 상태 → 생성 유도(FR-17).
- 데모용 `⏩ (데모) 내일 아침으로` 버튼으로 자정 배치 도착 시뮬레이션 진입점.

## 현재 구현 상태

- mock 기반 구현 존재.

## Acceptance Criteria

### AC1 — 캐릭터 보유 시 대시보드 구성
- **Given** 활성 캐릭터(`activeCharacter`)가 존재하는 사용자가,
- **When** 대시보드(`/`)에 진입하면,
- **Then** 히어로 스테이지(활성 캐릭터 스프라이트)와 사이드 레일이 함께 보인다: 육아점수 게이지(`nurtureScore` = 5스탯 총합, `EVOLVE_THRESHOLD=1000` 기준), 내 랭킹 요약, 감정 칩, 상태 메시지(`character.message`), 최근 활동 로그(오늘 리포트/퀴즈).
- **And** 육아점수·감정·메시지는 스토어 상태와 항상 일치한다(스탯 변동 시 즉시 반영).

### AC2 — 빈 상태 → 생성 유도
- **Given** 보유 캐릭터가 0개(`hasCharacter===false`)인 사용자가,
- **When** 대시보드에 진입하면,
- **Then** 히어로/사이드 레일 대신 빈 상태 카드가 표시되고 `🌱 첫 분신 만들기` CTA(`/create`)로만 이동을 유도한다(FR-17).

### AC3 — 상태 메시지/감정 표기(색+얼굴+라벨)
- **Given** 활성 캐릭터의 감정이 `joy | sad | angry` 중 하나일 때,
- **When** 대시보드가 감정을 표시하면,
- **Then** `CgEmo`로 색+얼굴+라벨을 함께 표기하고(색 단독 금지, FE-1 AC2), 상태 메시지 텍스트를 함께 노출한다.

### AC4 — 자정 배치 도착 데모 진입점
- **Given** 오늘 리포트가 저장돼 일일 레포트가 `pending`인 상태에서,
- **When** 사용자가 `⏩ (데모) 내일 아침으로` 버튼을 누르면,
- **Then** `deliverDailyReport()`가 호출되어 자정 배치 도착이 시뮬레이션되고(점수·감정·메시지 갱신, FE-6 연계), 안내 카피("오늘 학습 리포트는 자정에 분석돼요. 내일 오전 9시 도착.")가 도착 상태로 전환된다.

## 검증

- 활성 캐릭터 보유/미보유 두 경로 각각 렌더 확인(빈 상태 CTA 동작).
- 데모 버튼 클릭 시 점수·감정·활동 로그가 갱신되는지 확인.
- 감정 표기가 색+얼굴+라벨 동시 표기인지 확인, `npm run build` 통과.
- 변경은 모두 `vue/` 하위 파일로 한정.
