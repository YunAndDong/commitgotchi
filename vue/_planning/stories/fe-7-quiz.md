---
story: FE-7
status: done
current_implementation: done(mock)
scope: frontend-only (vue/ 하위)
related_fr: [FR-13, FR-14, FR-15]
related_files:
  - src/views/QuizView.vue
  - src/stores/game.js
---

# Story FE-7: 퀴즈 즉시 채점 흐름 B

Status: done

## Story

As a 사용자,
I want 추천 퀴즈를 풀고 제출 즉시 점수·피드백·스탯 변화를 보고,
so that 그 자리에서 학습 결과를 확인한다.

## 목적과 범위

- 흐름 B(즉시 채점): 제출 → "채점 중…" → 점수·피드백·스탯 +N·감정 갱신 인라인 재생.
- 퀴즈는 자정 배치 카피를 쓰지 않는다(흐름 혼동 금지, EXPERIENCE Voice).
- 채점 결과는 활성 캐릭터에 중복 없이 반영(FR-15).

## 현재 구현 상태

- mock 기반 구현 존재.

## Acceptance Criteria

### AC1 — 퀴즈 조회·풀이
- **Given** 사용자가 퀴즈 `/quiz`에 진입했을 때,
- **When** `todayQuizzes`(오늘 추천 퀴즈)가 로드되면,
- **Then** 각 퀴즈의 질문·보기(선택지)가 표시되고 사용자가 보기 하나를 선택할 수 있다(미선택 시 제출 차단).

### AC2 — 제출 → 즉시 채점 연출(흐름 B)
- **Given** 보기를 선택한 사용자가,
- **When** 제출하면,
- **Then** "채점 중…" 로딩 상태를 거쳐(`submitQuiz`의 비동기 채점) 결과가 **인라인**으로 재생된다(흐름 B, 즉시 채점 — 자정 배치 카피 사용 금지, EXPERIENCE Voice 준수).

### AC3 — 점수·피드백·스탯/감정 갱신
- **Given** 채점이 완료되면,
- **When** 정답/오답이 확정되면,
- **Then** 정답 여부·피드백 텍스트가 표시되고, 활성 캐릭터의 해당 스탯에 변화량(정답 `+12`, 오답 `+3`)이 가산되며, 감정이 정답=`joy`/오답=`sad`로, 반응 메시지가 함께 갱신된다.
- **And** 이 가산으로 육아점수가 1,000을 최초 통과하면 `maybeEvolve()`로 1회 진화 연출이 트리거된다(`_evolvedNow`).

### AC4 — 중복 반영 방지
- **Given** 이미 제출·채점된(`submitted/scored`) 퀴즈에 대해,
- **When** 사용자가 같은 퀴즈를 다시 제출하려 하면,
- **Then** 재채점·재가산이 일어나지 않아 활성 캐릭터에 점수가 중복 반영되지 않는다(FR-15). 채점 완료된 퀴즈는 결과 상태로 고정 표시된다.

## 검증

- 선택→제출→"채점 중…"→인라인 결과 흐름 B 확인(자정 배치 카피 미사용).
- 정답/오답별 스탯 +12/+3, 감정 joy/sad, 메시지 갱신 확인.
- 동일 퀴즈 재제출 시 중복 가산 없음 확인, 1,000 통과 시 진화 트리거 확인.
- `npm run build` 통과. 변경은 `vue/` 하위로 한정.

### Review Findings

- [x] [Review][Patch] 동시 제출 시 점수 중복 가산 방지 (`src/stores/game.js`)
- [x] [Review][Patch] 퀴즈 생성 캐릭터에 점수 귀속 (`src/stores/game.js`)
- [x] [Review][Patch] 채점 완료 고정 상태와 안내 문구 일치 (`src/views/QuizView.vue`)
