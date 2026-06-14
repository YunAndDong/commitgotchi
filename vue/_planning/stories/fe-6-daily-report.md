---
story: FE-6
status: done
current_implementation: done(mock)
scope: frontend-only (vue/ 하위)
related_fr: [FR-8, FR-10, FR-12, FR-21, FR-22, FR-23]
related_files:
  - src/views/ReportWriteView.vue
  - src/views/ReportResultView.vue
  - src/stores/game.js
---

# Story FE-6: 일일 리포트 흐름 A(작성 + 결과, 자정 배치)

Status: done

## Story

As a 사용자,
I want 오늘 학습을 리포트로 작성·저장하고 다음 날 AI 일일 레포트 결과를 받고,
so that 성장 변화(점수·전투력·진화·감정)를 확인한다.

## 목적과 범위

- 리포트 작성 #5: 기분(😊/😢/😠)·제목·내용·태그 + 캐릭터 반응 미리보기.
- 흐름 A(자정 배치): 저장 → "내일 오전 9시 도착" pending → 대시보드 `⏩ (데모) 내일 아침으로`로
  도착 시뮬레이션 → 점수 변화량 반영·전투력 갱신·1,000 통과 시 1회 진화 연출.
- AI 일일 레포트 결과 뷰: 학습 분석·능력치별 점수 변화량·다음 학습 추천·추천 퀴즈 종합.
- 성장 규칙(육아점수 = 5스탯 총합, 전투력 = 합 일치) 시각화는 FE-1 컴포넌트 사용.

## 현재 구현 상태

- mock 기반 구현 존재(`stores/game.js` 성장 규칙 포함).

## Acceptance Criteria

### AC1 — 리포트 작성·저장 + pending 카피
- **Given** 사용자가 리포트 작성 `/report`(화면 #5)에 진입했을 때,
- **When** 기분(😊/😢/😠)·제목·내용·태그를 입력하고 저장하면,
- **Then** `saveReport()`가 호출되어 **오늘 날짜 리포트 1개**를 저장하고(상태 `analyzing`), 활성 캐릭터의 감정·반응 메시지를 입력 기분에 맞게 즉시 갱신한다(작성 중 캐릭터 반응 미리보기).
- **And** 저장 후 "리포트 저장됨 — 자정에 분석돼요. 내일 오전 9시 도착." pending 카피를 노출한다(흐름 A, 자정 배치 — 즉시 채점 카피 사용 금지).

### AC2 — 자정 배치 도착 시뮬레이션
- **Given** 오늘 리포트가 저장돼 일일 레포트가 `pending`인 상태에서,
- **When** 대시보드의 `⏩ (데모) 내일 아침으로`를 누르면,
- **Then** `deliverDailyReport()`가 일일 레포트를 `ready`로 전환하고, 능력치 변화량(예 `algo+3, net+1`)을 활성 캐릭터에 반영하며 감정/메시지를 갱신한다.

### AC3 — 점수 반영 일 1회 멱등
- **Given** 같은 날 리포트를 여러 번 저장하거나 작성 폼을 재진입할 때,
- **When** 저장이 반복되어도,
- **Then** `todayReport` 1개를 **덮어쓰기**하여 하루 1리포트만 유지되며, 점수 반영(자정 배치 결과)은 하루 1회로 멱등하게 적용된다(중복 가산 금지).

### AC4 — 진화 1회 연출(육아점수 ≥ 1,000)
- **Given** 활성 캐릭터의 육아점수(`nurtureScore` = 5스탯 총합)가 1,000 미만인 상태에서,
- **When** 일일 레포트 반영으로 육아점수가 `EVOLVE_THRESHOLD(1000)`를 **최초로 통과**하면,
- **Then** `maybeEvolve()`가 `isEvolved=true`로 1회 진화시키고 진화 연출/공지("🎉 … 진화! 육아점수 1000 돌파.")를 표시한다.
- **And** 이미 진화한 캐릭터는 다시 진화하지 않는다(진화는 생애 1회). 전투력 표기는 육아점수(=5스탯 합)와 일치한다.

### AC5 — 결과 뷰 구성
- **Given** 일일 레포트가 `ready`인 상태에서,
- **When** 결과 뷰 `/report/result`에 진입하면,
- **Then** 학습 분석 요약·능력치별 점수 변화량(`deltas`)·퀴즈 코멘트(`quizComment`)·다음 학습 추천(`nextRecommendation`)이 표시되고, 성장 시각화는 FE-1 컴포넌트(게이지·레이더·감정칩)를 사용한다.
- **And** `pending`/`failed` 상태에서는 결과 대신 FE-10의 대기/Fallback 패턴을 보여준다.

## 검증

- 리포트 저장→pending 카피→데모 도착→결과 뷰 전체 흐름 A 확인.
- 같은 날 중복 저장 시 1리포트 유지·중복 가산 없음 확인.
- 육아점수 1,000 최초 통과 시 1회 진화, 재진화 없음 확인.
- 전투력 표기 = 5스탯 합 일치 확인, `npm run build` 통과. 변경은 `vue/` 하위로 한정.

### Review Findings

- [x] [Review][Patch] 동일 리포트 반복 전달 시 점수 중복 반영 방지 (`src/stores/game.js`)
- [x] [Review][Patch] 리포트 작성 캐릭터 귀속 및 다음 날 미반영 리포트 처리 (`src/stores/game.js`)
- [x] [Review][Patch] 재저장 시 대기 상태 복원 및 진화 공지 보존 (`src/stores/game.js`)
- [x] [Review][Patch] 결과 화면에 게이지·레이더·감정 칩 적용 (`src/views/ReportResultView.vue`)
