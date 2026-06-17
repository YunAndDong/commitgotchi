---
title: RAG Enhancement 3 - source-neighborhood 다양성 재균형
status: backlog
created: 2026-06-17
owner: FastAPI AI 서버
epic: rag-enhancement
tier: 1
source_docs:
  - ../rag-enhancement-epic.md
  - ../../report/stories/report-3-rag-search-and-source-neighborhood.md
---

# RAG Enhancement 3. source-neighborhood 다양성 재균형

## Status

backlog

## 목표

이웃 확장이 씨앗 문서 한 개로 evidence를 굳히는 문제를 해소한다. 같은-source 컨텍스트는 유지하되 총량에 cap을 두고, 타 문서 컨텍스트(`same_folder_related` 및 신규 cross-source 관련 후보)가 evidence bundle에서 안정적인 비중을 갖게 재균형한다.

## 배경

현재 `source_neighborhood.py`의 `NEIGHBORHOOD_REASON_ORDER`:

```
previous_chunk, next_chunk, parent_heading, sibling_heading, same_source_nearby, same_folder_related
```

6개 중 앞 5개가 전부 같은 source 파일에서만 뽑고, 타 문서로 가는 `same_folder_related`는 맨 뒤다. `max_neighborhood_chunks=8`이 같은-source 후보로 먼저 소진되면 타 문서는 거의 들어오지 못한다. 결과적으로 Story 2로 matches를 분산시켜도 neighborhood가 다시 한 문서로 집중시킨다.

## 구현 범위

- **같은-source 이웃 총량 cap** 도입: `max_same_source_neighbors`(예: `max_neighborhood_chunks`의 절반). previous/next/parent/sibling/same_source_nearby 합계가 이 cap을 넘지 않게 한다.
- **cross-document 최소 쿼터** 보장: neighborhood에 타 source 후보를 최소 `min_cross_source`(예: 2)개까지 cap 안에서 확보. 후보가 충분하면 같은-source가 cross-source 자리를 잠식하지 못하게 한다.
- `same_folder_related`를 같은-source 후보보다 먼저 평가하거나, 라운드 방식(같은-source 1개 → cross-source 1개 교차)으로 선택해 균형을 맞춘다.
- (선택) **cross-source 의미 관련 reason** 추가 검토: 같은 폴더가 아니어도 field/topic/matched-term 신호가 강한 타 문서 청크를 보조 후보로. 과하면 prompt noise이므로 강한 신호일 때만, cap 안에서만.
- 중복 chunkId 제거, matches에 이미 포함된 청크 제외는 현행 유지.
- `reason` 라벨과 `NeighborhoodEvidence` shape, `to_dict()` key는 변경하지 않는다(신규 reason 추가 시 라벨만 추가).

## 주요 파일 경로

- 구현 대상: `fastapi/app/rag/source_neighborhood.py`
- 호출부 확인: `fastapi/app/rag/concept_search.py` (`build_report_evidence_bundle`의 인자 통과)
- 테스트 대상: `fastapi/tests/rag/test_source_neighborhood.py`
- 측정: `fastapi/scripts/rag_diversity_eval.py` (same-source 이웃 비율 메트릭)

## Acceptance Criteria

- neighborhood의 같은-source 청크 수가 `max_same_source_neighbors`를 넘지 않는다.
- 같은 폴더/관련 타 문서 후보가 존재하면 cross-source 최소 쿼터만큼 cap 안에서 포함된다.
- 후보가 부족할 때(타 문서 후보 없음)는 기존처럼 같은-source로 채우되 cap을 지킨다.
- 같은 입력에서 neighborhood 선택과 순서가 deterministic하다.
- matches와 중복되는 chunkId가 직렬화되지 않는다.
- evidence bundle의 char cap(`max_bundle_chars`, `max_text_chars_per_item`)을 계속 지킨다.
- same-source 이웃 비율 메트릭이 베이스라인 대비 낮아진다(다양성 증가).
- 관련성 grounding이 무너지지 않는다(약한 신호의 타 문서를 억지로 넣지 않는다).
- §4 계약, `NeighborhoodEvidence`/evidence bundle shape 불변.

## Tasks/Subtasks

- [ ] `build_source_neighborhood()`에 same-source cap과 cross-source quota 옵션을 추가한다.
- [ ] 같은-source 후보와 cross-source 후보를 분리해 cap/쿼터 기준으로 병합한다.
- [ ] 관련 신호가 약한 cross-source 후보를 제외하는 임계 로직을 유지/보강한다.
- [ ] 중복 제거, matches 제외, char cap, reason/to_dict shape를 보존한다.
- [ ] same-source 비율 감소, 후보 부족 fallback, 결정성 테스트를 추가/갱신한다.

## 테스트 기준

- 같은-source 후보가 많아도 cap에서 잘리고 타 문서가 들어오는지 검증한다.
- 타 문서 후보가 전혀 없을 때 같은-source로 cap 안에서 채우는지 검증한다.
- cross-source 쿼터가 약한 신호로 채워지지 않는지(신호 임계 미달 후보 제외) 검증한다.
- 결정성: 동일 입력 2회 결과가 동일한지 검증한다.
- duplicate chunkId·char cap 회귀를 검증한다.

## 제외 범위

- 개념 검색 ranking/MMR (Story 2)
- 멀티쿼리 병합 (Story 4)
- 문제 뱅크 (Story 5~6)
- 개념 임베딩 재빌드, 필드 힌트 재균형 (Tier 4 후속)
- API/SQS/Spring Boot callback

## 개발 메모

- Story 2가 matches를 분산시키고 Story 3이 neighborhood를 분산시켜야 evidence bundle 전체의 문서 폭이 넓어진다. 두 story는 짝이다.
- cross-source 후보의 신호 임계는 기존 `_has_related_signal`을 재사용/강화한다. `framework`·`cs`처럼 흔한 필드는 신호에서 제외하는 현행 로직을 유지한다.

## Dev Notes

- Story 2 이후 적용하면 evidence bundle 전체의 분산 효과를 확인하기 쉽다.
- cross-source 쿼터는 관련성이 있는 후보가 있을 때만 채운다.
- evidence bundle의 크기 제한과 직렬화 contract는 변경하지 않는다.

## Dev Agent Record

### Debug Log

- TBD

### Completion Notes

- TBD

## File List

- TBD

## Change Log

- 2026-06-18: Added lightweight BMAD dev-story sections.
