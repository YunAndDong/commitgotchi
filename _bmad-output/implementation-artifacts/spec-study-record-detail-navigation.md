---
title: '공부 기록 상세 이동'
type: 'feature'
created: '2026-06-23'
status: 'done'
route: 'one-shot'
---

# 공부 기록 상세 이동

## Intent

**Problem:** 캐릭터 상세 화면의 공부 기록 카드가 클릭 가능한 상세 진입점이 아니어서, 사용자가 저장된 리포트나 완료한 퀴즈 내용을 다시 확인하기 어렵다.

**Approach:** 공부 기록 레코드에 타입별 상세 라우트를 연결하고, 리포트와 퀴즈 각각의 개별 상세 화면을 추가했다.

## Suggested Review Order

**기록 진입점**

- 공부 기록 타입별 목적지를 한 곳에서 결정한다.
  [`CharacterDetailView.vue:33`](../../vue/src/views/CharacterDetailView.vue#L33)

- 카드 전체를 상세 링크로 만들어 클릭 범위를 명확히 한다.
  [`CharacterDetailView.vue:179`](../../vue/src/views/CharacterDetailView.vue#L179)

**라우팅**

- 기존 일일 결과 경로를 보존하면서 개별 리포트 상세 경로를 추가한다.
  [`index.js:18`](../../vue/src/router/index.js#L18)

- 완료 퀴즈 기록을 개별 상세 경로로 열 수 있게 한다.
  [`index.js:20`](../../vue/src/router/index.js#L20)

**상세 화면**

- 리포트 상세는 작성 내용, 태그, 반영 상태를 같은 데이터 모델에서 읽는다.
  [`ReportDetailView.vue:6`](../../vue/src/views/ReportDetailView.vue#L6)

- 퀴즈 상세는 제출 답변 저장 방식 차이를 흡수해 답변을 안정적으로 표시한다.
  [`QuizDetailView.vue:27`](../../vue/src/views/QuizDetailView.vue#L27)
