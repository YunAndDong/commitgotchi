---
title: RAG Enhancement 2 - 개념 검색 MMR 재정렬 + per-source 상한
status: done
created: 2026-06-17
owner: FastAPI AI 서버
epic: rag-enhancement
tier: 1
source_docs:
  - ../rag-enhancement-epic.md
  - ../../report/stories/report-3-rag-search-and-source-neighborhood.md
---

# RAG Enhancement 2. 개념 검색 MMR 재정렬 + per-source 상한

## Status

done

## 목표

`search_concept_chunks()`가 코사인 유사도 단독으로 top_k를 채워 같은 source 파일 청크로 도배되는 문제를 해소한다. 후보 풀을 넓게 뽑은 뒤 **MMR(Maximal Marginal Relevance) 재정렬**과 **per-source 상한**으로, 관련성을 유지하면서 더 많은 문서가 결과에 등장하게 한다.

## 배경

현재 `concept_search.py`:

- `score = (similarity * 10.0) + (field_overlap * 0.01) + (len(matched_terms) * 0.001)` → 사실상 similarity 단독 정렬.
- top_k=5를 그대로 잘라 반환. **per-source 상한 없음, 다양성 항 없음.**
- html-css 파일 하나에 62청크가 있어 CSS 질의가 그 파일 청크 5개로 채워질 수 있다.

MMR은 "쿼리 관련성"과 "이미 선택된 결과와의 비유사성(novelty)"을 λ로 가중해, 비슷한 청크 대신 새로운 청크를 선택한다. 청크 임베딩은 이미 `chunk-embeddings.jsonl`에 있으므로 청크-청크 유사도 계산에 재사용한다(추가 API 호출 없음).

## 구현 범위

- `search_concept_chunks()`에 다양성 옵션 인자 추가(기본값은 현행 동작과 호환되거나 명확히 개선되도록):
  - `candidate_pool` (예: `max(limit * 6, 30)`): MMR/cap 적용 전 코사인으로 뽑을 후보 수.
  - `mmr_lambda` (예: `0.7`): 1.0이면 순수 관련성, 낮출수록 다양성.
  - `max_per_source` (예: `2`): 한 sourcePath가 최종 결과에서 차지할 수 있는 최대 청크 수.
- 절차: ① 코사인으로 candidate_pool 후보 선정 → ② MMR 반복 선택(쿼리 유사도 − λ·기선택 청크와의 최대 유사도) + per-source 상한 동시 적용 → ③ limit까지 채움.
- 청크-청크 유사도는 `chunk-embeddings.jsonl` 벡터로 계산. 한쪽이라도 임베딩이 없으면 MMR penalty를 0으로 두고 관련성 순서로 폴백.
- per-source 상한으로 자리가 비면, 상한에 걸려 밀려난 다음 source의 후보로 채운다.
- tie-break는 기존 `_sorted_hits` 키(score, source_path, chunk_index, chunk_id)로 결정적 유지.
- keyword fallback 경로에도 per-source 상한을 적용(MMR은 임베딩 없으므로 cap 위주).
- `build_report_evidence_bundle()`이 새 옵션을 통과시키되 기본 동작이 다양성을 갖도록 기본값을 설정.

## 주요 파일 경로

- 구현 대상: `fastapi/app/rag/concept_search.py`
- 영향 확인: `fastapi/app/rag/embedding_store.py` (청크 임베딩 조회 helper 필요 시 소폭 추가)
- 테스트 대상: `fastapi/tests/rag/test_concept_search.py`
- 회귀: `fastapi/tests/rag/test_source_neighborhood.py`, `fastapi/tests/scoring/test_report_chunker.py`
- 측정: `fastapi/scripts/rag_diversity_eval.py` (Story 1)

## Acceptance Criteria

- 한 sourcePath가 최종 top-k에서 `max_per_source`를 초과하지 않는다.
- MMR 재정렬로, 강하게 관련된 단일 문서가 결과를 독점하지 않고 차순위 관련 문서가 포함된다.
- 같은 입력·catalog에서 결과가 deterministic하다.
- 청크 임베딩이 일부 없을 때도 crash 없이 관련성 폴백으로 동작한다.
- keyword fallback 결과도 per-source 상한을 따른다.
- 관련성 회귀 없음: Story 1 평가 하니스에서 기대 주제 적중이 베이스라인 대비 떨어지지 않는다.
- distinct sourcePath 메트릭이 베이스라인 대비 증가한다.
- `ConceptSearchHit` shape와 `to_dict()` key는 변경하지 않는다.
- §4 계약, `daily_report_recommender`·문제 뱅크 검색 contract 불변.

## Tasks/Subtasks

- [x] `search_concept_chunks()`와 `ConceptCatalogSearcher.search()`에 다양성 옵션 인자를 추가한다.
- [x] embedding 검색 경로에 candidate pool, MMR 재정렬, per-source 상한을 적용한다.
- [x] keyword fallback 경로에도 per-source 상한과 deterministic tie-break를 적용한다.
- [x] `build_report_evidence_bundle()` 호출부에 새 옵션을 연결하되 출력 shape는 유지한다.
- [x] source 분산, fallback, 결정성, 회귀 테스트를 추가/갱신한다.

## 테스트 기준

- fixture catalog에서 같은 source에 강한 청크가 몰려 있어도 결과 source가 2개 이상으로 분산되는지 검증한다.
- `max_per_source=1`일 때 source가 전부 distinct한지 검증한다.
- `mmr_lambda=1.0`이면 기존 관련성 순서와 동일해지는지 검증한다.
- 청크 임베딩 누락 시 폴백이 동작하는지 검증한다.
- keyword fallback에서 per-source 상한이 적용되는지 검증한다.
- 결정성: 동일 입력 2회 실행 결과가 동일한지 검증한다.

## 제외 범위

- source-neighborhood 확장 로직 (Story 3)
- 멀티쿼리 병합 (Story 4)
- 문제 뱅크 검색 (Story 5~6)
- 개념 임베딩 재빌드, 필드 힌트 재균형 (Tier 4 후속)
- API/SQS/Spring Boot callback

## 개발 메모

- MMR penalty 계산을 위해 후보 청크 임베딩을 candidate_pool 범위에서만 들고 와 O(pool²) 비용으로 제한한다(pool이 작아 충분).
- 기본값은 "다양성 켜짐"으로 두되, 회귀 테스트가 깨지면 테스트에서 명시 인자로 기존 동작을 재현할 수 있게 인자를 노출한다.

## Dev Notes

- Story 1 baseline을 먼저 실행한 뒤 before/after 수치를 비교한다.
- 기존 `ConceptSearchHit`와 evidence bundle 직렬화 contract는 변경하지 않는다.
- MMR/cap은 관련성 회귀가 보이면 인자 조정이 가능하도록 구현한다.

## Dev Agent Record

### Debug Log

- 2026-06-18: 기존 `python3 -m unittest tests.rag.test_concept_search` 통과 확인 후 Story 1 하니스 before 리포트를 `/tmp/rag-diversity-story2-before.*`에 생성했다.
- 2026-06-18: RED 단계에서 새 다양성 옵션 테스트 7개가 옵션 미지원 TypeError로 실패함을 확인했다.
- 2026-06-18: GREEN 단계에서 `python3 -m unittest tests.rag.test_concept_search` 통과를 확인했다.
- 2026-06-18: 회귀 테스트 `tests.rag.test_source_neighborhood`, `tests.scoring.test_report_chunker`, `tests.rag.test_diversity_eval` 통과를 확인했다.
- 2026-06-18: Story 1 하니스 after 리포트를 `/tmp/rag-diversity-story2.*`에 생성했다. Tier A concept top-k avgDistinctSourceCount는 4.296703에서 4.56044로 증가했고 recallAtK/hitRateAtK/MRR도 0.137363/0.241758/0.106044에서 0.181319/0.340659/0.155861로 증가했다.

### Implementation Plan

- 기존 `ConceptSearchHit` score 및 `to_dict()` shape는 유지하고, candidate pool 이후 선택 순서에만 MMR과 source cap을 적용한다.
- `mmr_lambda=1.0`은 기존 관련성 정렬을 보존하고, 기본값은 candidate pool 30 이상, `mmr_lambda=0.7`, `max_per_source=2`로 다양성을 켠다.
- 청크-청크 similarity 계산에 후보 임베딩을 재사용하고, penalty 계산에 필요한 임베딩이 없으면 0 penalty로 처리해 관련성 순서 폴백을 유지한다.
- keyword fallback은 기존 `_sorted_hits` 결정성 tie-break 이후 per-source cap만 적용한다.

### Completion Notes

- `search_concept_chunks()`와 `ConceptCatalogSearcher.search()`에 `candidate_pool`, `mmr_lambda`, `max_per_source` 옵션을 추가했다.
- embedding 검색은 넓은 후보 풀을 만든 뒤 MMR과 per-source cap을 적용하며, source cap에 걸린 후보는 건너뛰고 다음 source 후보로 채운다.
- keyword fallback에도 동일한 per-source cap을 적용했고, `build_report_evidence_bundle()`/`build_report_evidence_bundles()`가 새 옵션을 전달하도록 연결했다.
- `ConceptSearchHit`와 evidence bundle 직렬화 key는 변경하지 않았다.
- source 분산, MMR 차순위 source 선택, `mmr_lambda=1.0` 관련성 순서 보존, 결정성, 일부 임베딩 누락, keyword fallback cap, bundle shape 회귀 테스트를 추가했다.

## File List

- fastapi/app/rag/concept_search.py
- fastapi/tests/rag/test_concept_search.py
- fastapi/docs/rag-enhancement/rag-enhancement-sprint-status.yaml
- fastapi/docs/rag-enhancement/stories/rag-enhancement-2-concept-search-mmr-source-cap.md

## Change Log

- 2026-06-18: Added lightweight BMAD dev-story sections.
- 2026-06-18: Implemented concept search candidate-pool MMR reranking, per-source cap, keyword fallback cap, option pass-through, and regression coverage.
