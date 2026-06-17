---
title: RAG Enhancement 6 - 문제 뱅크 하이브리드 검색 & 추천 다양화
status: backlog
created: 2026-06-17
owner: FastAPI AI 서버
epic: rag-enhancement
tier: 3
source_docs:
  - ../rag-enhancement-epic.md
  - ./rag-enhancement-5-problem-bank-embeddings.md
  - ../../quiz-bank/problem-bank-implementation.md
---

# RAG Enhancement 6. 문제 뱅크 하이브리드 검색 & 추천 다양화

## Status

backlog

## 목표

Story 5의 문제 임베딩을 사용해 문제 뱅크 검색을 **키워드 + 벡터 하이브리드**로 바꾼다. 키워드가 못 잡는 의미적 매치를 회수하고, 추천 퀴즈가 더 다양한 source·주제에서 나오게 한다. `recommend_quizzes()`의 **출력 shape(§4 계약)는 불변**이며, 내부 ranking과 다양성만 개선한다.

## 배경

- 현재 `problem_bank_search.py`는 용어 빈도 + 필드 가산만 사용 → 용어가 많이 박힌 동일 문제 반복 노출.
- `quiz_recommender.py`는 이미 `_dedupe_hits`로 source_key·topic_key 다양성을 일부 갖지만, 후보 풀이 키워드 ranking이라 의미적으로 다른 좋은 문제를 애초에 못 본다.
- `SEARCH_LIMIT_MULTIPLIER=8`로 후보를 8배 뽑아 dedupe하는 구조라, 하이브리드로 후보 풀 품질만 올려도 추천 다양성이 개선된다.

## 구현 범위

- 하이브리드 스코어: 기존 키워드 score와 벡터 코사인 유사도를 결합(가중합 또는 정규화 후 합). 임베딩 없는 문제는 키워드 score로 폴백.
  - query embedding은 Story 4의 query 입력 helper 재사용 또는 recommender query를 임베딩.
  - 가중치는 상수/인자로 노출(예: `keyword_weight`, `vector_weight`).
- `search_problem_bank()`에 하이브리드 경로 추가(옵션 인자). 임베딩 스토어 미주입/임베딩 실패 시 **기존 키워드 동작으로 정확히 폴백**.
- (선택) per-source / per-field 상한을 후보 ranking 단계에도 적용해 dedupe 전부터 다양성을 확보.
- `quiz_recommender.recommend_quizzes()`가 하이브리드 후보 풀을 쓰도록 연결하되, **반환 snapshot shape(`problemId`, `question`, `modelAnswer`, `scoreAllocation`)와 limit(최대 3)·dedupe 의미는 유지**.
- 같은 입력에서 deterministic. 동점 tie-break를 명시.

## 주요 파일 경로

- 구현 대상: `fastapi/app/rag/problem_bank_search.py`
- 연결: `fastapi/app/rag/quiz_recommender.py` (후보 풀 소스만 교체, 출력 contract 유지)
- 재사용: `fastapi/app/rag/problem_embedding_store.py` (Story 5), `fastapi/app/rag/concept_embeddings.py`
- 테스트 대상: `fastapi/tests/rag/test_problem_bank_search.py`, `fastapi/tests/rag/test_quiz_recommender.py`
- 측정: `fastapi/scripts/rag_diversity_eval.py`
- 참고(수정 금지): `fastapi/app/rag/daily_report_recommender.py` 추천 contract

## Acceptance Criteria

- 문제 검색이 키워드+벡터 하이브리드로 동작하고, 임베딩 없음/실패 시 기존 키워드 동작으로 폴백한다.
- 키워드로 못 잡던 의미적 매치(동의어·다른 표현)가 회수되는 케이스가 있다.
- 추천 퀴즈의 source/주제 다양성이 베이스라인 대비 증가한다(평가 하니스).
- `recommend_quizzes()` 반환 shape·limit(≤3)·dedupe 동작이 유지된다.
- 같은 입력에서 deterministic하다.
- 관련성 회귀 없음: 명백히 관련된 문제가 여전히 상위에 온다.
- §4 계약, `daily_report_recommender` contract, `problems.jsonl` 생성 흐름 불변.

## 테스트 기준

- fake embedder + fixture 문제 뱅크에서 의미적 매치(키워드 0 매치지만 벡터 유사)가 후보에 들어오는지 검증한다.
- 임베딩 스토어 미주입 시 결과가 기존 키워드 검색과 동일한지(폴백 정확성) 검증한다.
- 하이브리드 가중치 변경이 ranking에 반영되는지 검증한다.
- `recommend_quizzes()` 출력 snapshot shape·limit·dedupe 회귀를 검증한다.
- 결정성·동점 tie-break를 검증한다.
- 기존 `test_problem_bank_search`, `test_quiz_recommender` 회귀가 깨지지 않는지 확인한다.

## 제외 범위

- 신규 문제 런타임 생성 (MVP 제외 — 가까운 문제 없으면 빈 배열 유지)
- 문제 임베딩 빌드 (Story 5에서 완료)
- 개념 검색/이웃/멀티쿼리 (Story 2~4)
- Spring Boot 계약·`recommendedQuizzes` shape 변경
- pgvector/운영 vector index
- API/SQS/Spring Boot callback

## 개발 메모

- 키워드 score와 코사인은 스케일이 다르다(키워드는 정수 누적, 코사인은 0~1). 정규화 후 가중합하거나, 코사인을 키워드 스케일에 맞춰 boost로 더하는 방식 중 결정성·해석가능성이 좋은 쪽을 택한다.
- `quiz_recommender`의 dedupe는 그대로 두고 **후보 풀의 질만** 올리는 것이 가장 안전하다. dedupe 로직을 바꾸면 기존 회귀가 깨질 수 있으니 최소 변경.
- 최종적으로 Story 1 평가 하니스로 before/after를 PR에 첨부해 다양성↑·관련성 유지 두 축을 함께 보인다.
