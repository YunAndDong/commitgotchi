---
title: RAG Enhancement 6 - 문제 뱅크 하이브리드 검색 & 추천 다양화
status: done
created: 2026-06-17
updated: 2026-06-19
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

done

## Story

As a FastAPI AI server maintainer,
I want problem-bank search and quiz recommendation to rank candidates with keyword relevance plus problem embeddings,
so that recommended quizzes can recover semantically relevant existing problems without changing the Spring/API/SQS contract.

## 목표

Story 5에서 생성한 문제 임베딩 sidecar를 사용해 문제 뱅크 검색/추천 ranking 내부를 **키워드 + 벡터 하이브리드**로 개선한다. 키워드가 못 잡는 동의어·다른 표현의 문제를 후보 풀에 회수하되, `recommend_quizzes()` 반환 shape, 최대 3개 limit, `_dedupe_hits()` 의미, `daily_report_recommender` contract, Spring Boot/API/SQS callback contract는 변경하지 않는다.

## 구현 범위

- `fastapi/app/rag/problem_bank_search.py`의 ranking 내부를 확장한다.
- `fastapi/app/rag/quiz_recommender.py`가 더 큰 하이브리드 후보 풀을 받아 기존 `_dedupe_hits()`로 최종 최대 3개만 반환하도록 연결한다.
- `fastapi/app/rag/problem_embedding_store.py`의 `ProblemEmbeddingStore`와 `embedding_for()`를 재사용한다.
- `fastapi/app/rag/problem_embeddings.py`에 필요하면 query embedding 입력 helper를 추가한다.
- `fastapi/scripts/rag_diversity_eval.py`의 problem-bank 평가 경로가 하이브리드 검색을 측정할 수 있게 optional fake/real embedding store/client 주입점을 추가한다.
- 테스트는 fake embedder와 in-memory fake `ProblemEmbeddingStore`를 사용한다. 실제 Gemini API 호출은 구현 테스트에서 하지 않는다.

## 제외 범위

- `problem-embeddings.jsonl` 재생성, manifest/summary 재생성, 문제 뱅크 생성 흐름 변경.
- 신규 문제 런타임 생성. 가까운 기존 문제가 없으면 기존처럼 빈 배열을 유지한다.
- Spring Boot, API endpoint, SQS callback, callback payload shape 변경.
- `daily_report_recommender.py`의 public payload contract 변경.
- 개념 검색, source neighborhood, multi-query 로직 변경.
- pgvector/운영 vector index 도입.

## 기존 Contract 불변 조건

- `recommend_quizzes()`와 `build_recommended_quizzes()`의 기존 호출자는 새 필수 인자 없이 계속 동작해야 한다.
- `recommend_quizzes()` 반환은 기존 snapshot list이며 최대 3개다.
- snapshot shape는 유지한다: `problemId`, `question`, `modelAnswer`, `scoreAllocation`, `difficulty`, `sourcePath`, `matchScore`.
- `problem_to_recommendation_snapshot()`는 새 debug field를 추가하지 않는다. `matchScore`는 계속 "추천 matching/ranking score"이며, keyword-only fallback에서는 기존 keyword score 값과 동일해야 한다.
- `daily_report_service`는 extra fields를 잘라 Spring-facing `recommendedQuizzes`를 `problemId`, `question`, `modelAnswer`, `scoreAllocation`만 남긴다. Story 6은 이 trimming contract를 바꾸지 않는다.
- `limit`, `_clamp_limit()`, `MAX_RECOMMENDATION_LIMIT = 3`, `SEARCH_LIMIT_MULTIPLIER = 8`, `_dedupe_hits()`의 dedupe 기준(problem id, source key, content hash, question, topic)은 의미를 바꾸지 않는다.
- `search_problem_bank()`에서 embedding store/client 미주입, embedding file 없음, query embedding 실패, dimension mismatch, empty store인 경우 기존 keyword-only 결과와 동일한 ordering/score/matched_terms로 fallback해야 한다.

## Story 5 산출물 사용 규칙

- 사용 가능 산출물:
  - `fastapi/data/rag/catalog/problems.jsonl`: 798 records
  - `fastapi/data/rag/catalog/problem-embeddings.jsonl`: 798 records
  - 검증 상태: `embedded_items=798`, `issues=0`
  - model/dimension: `gemini-embedding-2` / 768
  - 완료 커밋: `bd0ef2d feat(rag): add problem bank embeddings`
- runtime loader는 `load_problem_embedding_store()`를 사용한다. 직접 JSONL을 새로 파싱하는 중복 로더를 만들지 않는다.
- `problem-embeddings.jsonl`, `problem-embeddings-manifest.json`, `problem-embeddings-summary.md`를 재생성하거나 수정하지 않는다.
- sidecar가 없거나 stale/missing issue가 있어도 search/recommend는 crash 없이 keyword-only 또는 available-embedding hybrid로 동작해야 한다.
- Story 6 테스트는 임시 fixture와 fake embedding store를 사용한다. Gemini API key, `.env`, 실제 sidecar 존재 여부에 의존하지 않는다.

## 하이브리드 Ranking 요구사항

### 후보 추출

- keyword score로 lexical relevance를 확보한다. 현재 `_score_problem()`과 field bonus 로직을 보존하고, keyword-only fallback에서는 현재 `ProblemBankSearcher.search()` 결과와 byte-for-byte에 가깝게 동일해야 한다.
- query embedding과 problem embedding cosine similarity로 semantic recall을 확보한다.
- hybrid 후보 풀은 keyword 후보와 vector 후보의 union이다.
  - keyword lane: 기존 raw keyword score가 `min_score` 이상인 문제.
  - vector lane: `ProblemEmbeddingStore.embedding_for(problem_id, source_key)`가 있고 query similarity를 계산할 수 있는 문제.
  - field/difficulty filter는 두 lane 모두 동일하게 적용한다.
- keyword score가 0 또는 낮은 문제도 vector similarity가 높으면 후보에 들어올 수 있어야 한다.
- `recommend_quizzes()`는 최종 limit보다 큰 후보 풀을 받는다. 기본 구조는 기존처럼 `max(capped_limit * SEARCH_LIMIT_MULTIPLIER, capped_limit)`를 유지하고, 그 결과를 기존 `_dedupe_hits()`에 넘겨 최종 최대 3개만 반환한다.

### 점수 정규화와 결합

- candidate pool 내 raw keyword score를 0..1로 정규화한다.
  - 권장: max 기반 정규화 `normalized_keyword = raw_keyword / max_raw_keyword`, `max_raw_keyword <= 0`이면 0.0.
  - 모든 값은 0.0..1.0으로 clamp한다.
- vector similarity는 cosine similarity를 사용하고 0.0..1.0 범위로 clamp/정규화한다.
  - 권장: `normalized_vector = min(max(cosine, 0.0), 1.0)`.
  - cosine 계산은 기존 `concept_search.cosine_similarity()`를 재사용하거나 동일 동작의 shared helper로 옮긴다. 중복 구현을 만들 경우 테스트로 dimension mismatch/zero norm 안전성을 보장한다.
- final score는 명시 가중합이다.
  - `final_score = keyword_weight * normalized_keyword + vector_weight * normalized_vector`
  - 기본 가중치는 코드 상수로 둔다. 권장 기본값: `DEFAULT_KEYWORD_WEIGHT = 0.65`, `DEFAULT_VECTOR_WEIGHT = 0.35`.
  - 인자로 가중치를 바꿀 수 있어야 하며, 음수 가중치나 합계 0은 안전하게 clamp/normalize하거나 `ValueError` 대신 fallback-safe default로 처리한다.
- hybrid path의 `ProblemSearchHit.score`와 snapshot `matchScore`는 final score를 사용한다. keyword-only fallback path에서는 기존 raw keyword score를 그대로 사용한다.
- matched_terms는 keyword lane에서 계산한 기존 matched terms를 유지한다. vector-only 후보는 빈 tuple일 수 있다.

### Determinism

- 같은 입력, 같은 problem bank, 같은 embedding store/client 결과에서 ranking은 deterministic해야 한다.
- tie-break는 명시한다: `(-score, difficulty, problemId, sourceKey)` 순서를 기본으로 한다.
- keyword-only fallback tie-break는 기존 정렬 `(-score, difficulty, problem_id)`와 호환되어야 한다. 필요하면 hybrid 전용 sort key에만 `sourceKey`를 마지막에 추가한다.

## 예상 구현 대상 파일과 함수/인자 변경안

### `fastapi/app/rag/problem_bank_search.py`

- `ProblemBankSearcher.__init__` 확장:
  - 기존: `ProblemBankSearcher(store: ProblemBankStore)`
  - 변경안: `ProblemBankSearcher(store, *, embedding_store: ProblemEmbeddingStore | None = None, client: EmbeddingClient | None = None, keyword_weight: float = DEFAULT_KEYWORD_WEIGHT, vector_weight: float = DEFAULT_VECTOR_WEIGHT)`
- `ProblemBankSearcher.search()` 확장:
  - 기존 인자 `query`, `limit`, `min_score`, `field`, `difficulty` 유지.
  - optional `embedding_store`, `client`, `keyword_weight`, `vector_weight`, `candidate_pool` 또는 `candidate_multiplier`를 받을 수 있게 한다.
  - embedding 구성 요소가 없거나 query embedding 실패 시 내부에서 기존 keyword-only helper로 즉시 fallback한다.
- `search_problem_bank()` 확장:
  - 기존 호출자는 변경 없이 동작.
  - optional `embedding_store`, `client`, `keyword_weight`, `vector_weight`를 전달할 수 있게 한다.
- 내부 helper 권장:
  - `_keyword_search(...)`: 현재 동작을 분리해 fallback 동일성 테스트의 기준점으로 사용.
  - `_raw_keyword_scores(...)`: 문제별 raw keyword score와 matched terms 계산.
  - `_hybrid_search(...)`: query embedding, vector similarity, 후보 union, 정규화, final score sort.
  - `_normalize_keyword_scores(...)`, `_normalize_vector_similarity(...)`, `_hybrid_score(...)`.

### `fastapi/app/rag/quiz_recommender.py`

- `recommend_quizzes()`와 `build_recommended_quizzes()`에 optional args를 추가할 수 있다:
  - `embedding_store: ProblemEmbeddingStore | None = None`
  - `embedding_client` 또는 `client: EmbeddingClient | None = None`
  - `keyword_weight`, `vector_weight`
- 기존 호출 signature는 깨지면 안 된다. 새 인자는 keyword-only optional이어야 한다.
- `hits = search_problem_bank(...)` 호출에 hybrid 옵션을 전달한다.
- `_dedupe_hits()`, `_topic_key()`, `_clamp_limit()`은 변경하지 않는다. 테스트가 필요할 때만 기존 의미를 보존하는 범위에서 최소 수정한다.
- embedding store/client 준비 실패는 `recommend_quizzes()` 전체 실패가 아니라 keyword-only fallback으로 처리한다.

### `fastapi/app/rag/problem_embeddings.py`

- 필요하면 query embedding input helper를 추가한다:
  - 예: `build_problem_query_embedding_input(query: str, *, field: str | None = None, difficulty: str | None = None) -> str`
- document embedding input(`build_problem_embedding_input`)은 변경하지 않는다. content hash나 sidecar record를 건드리지 않는다.

### `fastapi/scripts/rag_diversity_eval.py`

- `evaluate_problem_queries()`에 optional `embedding_store`와 `client`를 추가한다.
- fake embedding mode에서 problem bank도 API 없이 평가할 수 있게 `ProblemEmbeddingStore` fixture 생성 helper를 추가한다.
- real sidecar 평가가 필요하면 `--problem-embeddings-path` 같은 optional CLI 인자만 추가한다. 기본 실행이 실제 Gemini API를 호출하면 안 된다.

## Acceptance Criteria

1. `search_problem_bank()`는 embedding store/client가 준비된 경우 keyword score와 vector similarity를 결합한 하이브리드 ranking을 반환한다.
2. embedding store/client 미주입, embedding file 없음, empty embedding store, query embedding 실패 시 기존 keyword-only 결과와 score/order/matched_terms가 동일하다.
3. keyword score가 0 또는 낮은 문제라도 fake embedding similarity가 높은 경우 후보와 ranking에 포함된다.
4. keyword/vector 가중치를 바꾸면 동일 fixture에서 ranking이 예측 가능하게 바뀐다.
5. `recommend_quizzes()`는 하이브리드 후보 풀을 받아 기존 `_dedupe_hits()`로 최종 최대 3개를 반환하며 snapshot shape를 유지한다.
6. `daily_report_recommender`와 `daily_report_service` contract가 유지된다. Spring-facing `recommendedQuizzes` shape는 변하지 않는다.
7. deterministic tie-break가 테스트로 보호된다.
8. Story 5 sidecar와 manifest/report는 재생성 또는 수정되지 않는다.
9. 기존 회귀 테스트 `tests.rag.test_problem_bank_search`, `tests.rag.test_quiz_recommender`, `tests.scoring.test_daily_report_service`가 통과한다.

## Tasks / Subtasks

- [x] (AC: 1, 2) keyword-only 검색을 helper로 분리하고 현재 behavior를 fallback 기준으로 고정한다.
  - [x] 기존 `_score_problem()` field bonus, `min_score`, field/difficulty filter, sort order를 보존한다.
  - [x] fallback 동일성 테스트에서 기존 호출과 새 fallback 호출의 problem ids, scores, matched_terms를 비교한다.
- [x] (AC: 1, 3, 4, 7) 하이브리드 scoring 구현.
  - [x] query embedding input helper를 추가하거나 기존 helper를 명확히 재사용한다.
  - [x] `ProblemEmbeddingStore.embedding_for(problem_id, source_key)`로 problem embedding을 조회한다.
  - [x] cosine similarity를 계산하고 0..1로 clamp/정규화한다.
  - [x] keyword score를 candidate pool 기준 0..1로 정규화한다.
  - [x] `keyword_weight * normalized_keyword + vector_weight * normalized_vector` final score를 적용한다.
  - [x] `(-score, difficulty, problemId, sourceKey)` tie-break를 구현한다.
- [x] (AC: 2) fallback/error handling 구현.
  - [x] embedding store/client가 없으면 기존 keyword-only helper를 반환한다.
  - [x] embedding store가 비었거나 sidecar 파일이 없으면 기존 keyword-only helper를 반환한다.
  - [x] `client.embed_query(...)` 예외, dimension mismatch, zero norm은 keyword-only fallback 또는 해당 vector candidate 제외로 안전 처리한다.
- [x] (AC: 5, 6) quiz recommender 연결.
  - [x] `recommend_quizzes()`와 `build_recommended_quizzes()`에 optional hybrid args를 추가하되 기존 호출자는 변경 없이 유지한다.
  - [x] 검색 후보 수는 기존 `SEARCH_LIMIT_MULTIPLIER` 기반으로 최종 limit보다 크게 유지한다.
  - [x] `_dedupe_hits()`는 그대로 사용한다.
  - [x] `problem_to_recommendation_snapshot()` shape를 변경하지 않는다.
- [x] (AC: 1, 8) Story 5 산출물 사용 경로 연결.
  - [x] `load_problem_embedding_store()` 재사용 여부를 검토한다.
  - [x] sidecar나 manifest/report를 생성하는 스크립트를 실행하지 않는다.
  - [x] stale/missing embedding issue가 있어도 search/recommend가 crash하지 않게 한다.
- [x] (AC: 1, 3, 9) diversity eval problem-bank 경로를 optional hybrid 측정 가능하게 갱신한다.
  - [x] fake problem embedding store 생성 helper를 추가한다.
  - [x] 기본 test/eval 경로가 실제 Gemini API를 호출하지 않게 한다.
- [x] (AC: 2-7, 9) 테스트 추가/보강.
  - [x] embedding 미주입 fallback 동일성.
  - [x] fake embedding으로 keyword 0 또는 낮은 문제의 semantic recall.
  - [x] 가중치 변경에 따른 ranking 변화.
  - [x] `recommend_quizzes()` shape/limit/dedupe 유지.
  - [x] query embedding 실패 fallback.
  - [x] deterministic tie-break.
  - [x] daily report service contract 회귀.
- [x] (Review follow-up: AC 2, 제외 범위) code review Changes Requested 후속 수정.
  - [x] 일부 embedding만 dimension mismatch인 경우 keyword-only fallback 동일성을 보장한다.
  - [x] 일부 embedding만 zero norm인 경우 keyword-only fallback 동일성을 보장한다.
  - [x] vector-only 후보에 similarity threshold를 적용해 무관 입력 추천을 방지한다.
  - [x] 무관 입력 `[]` recommender 회귀 테스트를 추가한다.
  - [x] troubleshooting 문서에 증상, 원인, 해결, 재발 방지를 기록한다.

## 테스트 계획

- `fastapi/tests/rag/test_problem_bank_search.py`
  - fake `ProblemEmbeddingStore`와 fake client로 vector-only semantic recall을 검증한다.
  - `embedding_store=None`, `client=None`일 때 기존 keyword-only 결과와 완전히 동일한지 검증한다.
  - fake client가 `embed_query()`에서 예외를 던질 때 fallback 동일성을 검증한다.
  - `keyword_weight/vector_weight` 변경으로 ranking이 바뀌는 fixture를 추가한다.
  - 동일 final score fixture에서 deterministic tie-break를 검증한다.
- `fastapi/tests/rag/test_quiz_recommender.py`
  - 하이브리드 후보가 들어와도 결과는 최대 3개이고 중복 source/topic은 기존처럼 제거되는지 검증한다.
  - snapshot required fields와 기존 extra fields(`difficulty`, `sourcePath`, `matchScore`) 존재를 보호한다.
- `fastapi/tests/scoring/test_daily_report_service.py`
  - Spring-facing payload가 `problemId`, `question`, `modelAnswer`, `scoreAllocation`만 남기는 기존 contract를 재검증한다.
- `fastapi/tests/rag/test_diversity_eval.py`
  - problem-bank 평가가 fake embedding store/client로 API 없이 실행되는 smoke test를 추가한다.
- 권장 회귀 명령:
  - `python3 -m unittest tests.rag.test_problem_bank_search`
  - `python3 -m unittest tests.rag.test_quiz_recommender`
  - `python3 -m unittest tests.scoring.test_daily_report_service`
  - `python3 -m unittest tests.rag.test_diversity_eval`

## Dev Notes

- 이 story는 ranking 내부 개선이다. 새 문제 생성, Spring callback, API shape 변경으로 문제를 해결하려고 하면 범위 이탈이다.
- fallback 동일성은 가장 중요한 회귀 가드다. hybrid path를 추가하더라도 embedding 미구성 환경에서 기존 테스트 기대값이 흔들리면 안 된다.
- `ProblemSearchHit`는 내부 dataclass지만 recommender와 tests가 사용한다. 새 필드를 추가한다면 default를 둬서 기존 생성/테스트를 깨지 않게 한다.
- `matchScore`를 vector similarity debug 값으로 바꾸지 않는다. hybrid path에서는 final ranking score, fallback path에서는 기존 keyword score다.
- 실제 Gemini API 호출은 Story 5의 빌드 검증에서 이미 수행되었다. Story 6 구현 테스트에서는 fake client/store만 사용한다.
- `problem_embedding_store`는 `problemId + sourceKey` join을 이미 검증한다. Story 6에서 별도 join 로직을 만들지 않는다.

## References

- Epic: `fastapi/docs/rag-enhancement/rag-enhancement-epic.md`
- Previous story: `fastapi/docs/rag-enhancement/stories/rag-enhancement-5-problem-bank-embeddings.md`
- Keyword search current behavior: `fastapi/app/rag/problem_bank_search.py`
- Recommender dedupe/limit current behavior: `fastapi/app/rag/quiz_recommender.py`
- Story 5 embedding store: `fastapi/app/rag/problem_embedding_store.py`
- Story 5 embedding input/sidecar codec: `fastapi/app/rag/problem_embeddings.py`
- Daily report contract guard: `fastapi/tests/scoring/test_daily_report_service.py`

## Dev Agent Record

### Agent Model Used

GPT-5 Codex

### Debug Log References

- `cd fastapi && python3 -m unittest tests.rag.test_problem_bank_search`
- `cd fastapi && python3 -m unittest tests.rag.test_quiz_recommender`
- `cd fastapi && python3 -m unittest tests.scoring.test_daily_report_service`
- `cd fastapi && python3 -m unittest tests.rag.test_diversity_eval`
- `git diff --check`

### Completion Notes List

- `ProblemBankSearcher`에 keyword-only helper와 optional hybrid path를 추가했다. embedding store/client 미주입, empty store, query embedding 실패, dimension mismatch/zero norm은 keyword-only 결과로 fallback한다.
- query/problem embedding cosine similarity와 keyword score를 0..1로 정규화하고 `keyword_weight * normalized_keyword + vector_weight * normalized_vector` final score를 적용했다.
- keyword 후보와 vector 후보를 union하고 hybrid 정렬 tie-break를 `(-score, difficulty, problemId, sourceKey)`로 고정했다.
- `recommend_quizzes()`와 `build_recommended_quizzes()`는 optional hybrid args를 받지만 기존 호출, 최대 3개 limit, `_dedupe_hits()`, snapshot shape는 유지한다.
- diversity eval problem-bank 경로에 fake/sidecar embedding 주입점을 추가했고 기본 fake mode는 Gemini API를 호출하지 않는다.
- Story 5 sidecar, manifest, summary, problem catalog는 재생성하거나 수정하지 않았다.
- Review follow-up으로 vector-only 후보 threshold를 추가하고, partial invalid embedding 감지 시 keyword-only fallback 동일성을 보장했다.
- 무관 리포트 추천 회귀와 partial dimension mismatch/zero norm fallback 동일성 테스트를 추가했다.

### File List

- `fastapi/app/rag/problem_bank_search.py`
- `fastapi/app/rag/problem_embeddings.py`
- `fastapi/app/rag/quiz_recommender.py`
- `fastapi/scripts/rag_diversity_eval.py`
- `fastapi/tests/rag/test_problem_bank_search.py`
- `fastapi/tests/rag/test_quiz_recommender.py`
- `fastapi/tests/rag/test_diversity_eval.py`
- `fastapi/docs/troubleshooting/rag/problem-bank-hybrid-search.md`
- `fastapi/docs/rag-enhancement/rag-enhancement-sprint-status.yaml`
- `fastapi/docs/rag-enhancement/stories/rag-enhancement-6-problem-bank-hybrid-search.md`

## Change Log

- 2026-06-19: Addressed code review follow-up for partial invalid embedding fallback and vector-only threshold; added troubleshooting docs and moved status to done.
- 2026-06-19: Implemented Story 6 problem-bank hybrid search, recommender wiring, diversity eval fake embedding support, and regression tests; moved status to review.
- 2026-06-18: Refined Story 6 to ready-for-dev with hybrid scoring formula, fallback invariants, implementation targets, Story 5 sidecar usage rules, and regression test plan.
