---
title: RAG Enhancement 4 - 필드/주제 멀티쿼리 검색 & 쿼터 병합
status: backlog
created: 2026-06-17
owner: FastAPI AI 서버
epic: rag-enhancement
tier: 2
source_docs:
  - ../rag-enhancement-epic.md
  - ../../report/stories/report-3-rag-search-and-source-neighborhood.md
---

# RAG Enhancement 4. 필드/주제 멀티쿼리 검색 & 쿼터 병합

## Status

backlog

## 목표

리포트가 여러 주제·필드를 담을 때, 단일 쿼리 임베딩 하나로 검색하면 가장 강한 주제(흔히 framework)가 결과를 독점한다. report chunk에서 감지된 **필드/주제별로 분리 검색**한 뒤 **쿼터/라운드로빈으로 병합**해, db·network·algorithm 같은 소외 필드가 검색 결과에서 대표성을 갖게 한다.

## 배경

- `ReportChunk`는 이미 `topic_hints`, `field_hints`를 가진다(report-2).
- `build_query_embedding_input()`은 이들을 한 쿼리 문자열로 합쳐 단일 임베딩을 만든다 → 지배적 신호로 쏠림.
- 필드 분포가 framework 448로 치우쳐 field_overlap 가산도 framework를 편애한다.

멀티쿼리는 "필드/주제별 sub-query를 각각 임베딩·검색 → 결과를 필드 쿼터로 병합"한다. sub-query 임베딩은 query embedding이라 호출 수가 늘지만, report chunk 수가 적어 비용은 제한적이다(쿼리 캐싱/상한으로 관리).

## 구현 범위

- report chunk의 `field_hints`/`topic_hints`에서 sub-query 후보 구성:
  - 필드별 sub-query (예: `db` 신호 + 원문) 와/또는 주제별 sub-query (`topic_hints` 각 항목 + 원문).
  - sub-query 개수 상한(`max_subqueries`, 예: 3)으로 호출 폭증 방지. 신호가 없으면 단일 쿼리로 폴백.
- 각 sub-query로 `search_concept_chunks()`(Story 2의 MMR·cap 적용본) 호출.
- **쿼터 병합**: 필드/주제별로 최소 자리를 배정하고 라운드로빈으로 top_k를 채움. 동점/중복 chunkId는 한 번만. 전역 per-source 상한(Story 2)을 병합 후에도 유지.
- 병합 결과를 기존 `build_report_evidence_bundle()` 흐름에 연결(matches로 사용). neighborhood는 Story 3 로직 재사용.
- 단일 쿼리 대비 호출 수·지연을 합리적으로 유지하고, 임베딩 실패 sub-query는 건너뛰되 나머지로 진행.
- 출력 evidence bundle shape 불변.

## 주요 파일 경로

- 구현 대상: `fastapi/app/rag/concept_search.py` (또는 신규 `fastapi/app/rag/multi_query.py`로 분리)
- 입력 schema 확인: `fastapi/app/scoring/schemas.py` (`ReportChunk`)
- 쿼리 입력 helper: `fastapi/app/rag/concept_embeddings.py` (`build_query_embedding_input`)
- 테스트 대상: `fastapi/tests/rag/test_concept_search.py` (또는 `test_multi_query.py`)
- 측정: `fastapi/scripts/rag_diversity_eval.py` (필드 커버리지 메트릭)

## Acceptance Criteria

- 여러 필드/주제를 담은 report chunk가 필드/주제별 sub-query로 분리 검색된다.
- 병합 결과가 소외 필드(db·network 등)의 청크를 포함해 필드 커버리지가 베이스라인 대비 증가한다.
- sub-query 수가 `max_subqueries`를 넘지 않고, 신호 없으면 단일 쿼리로 폴백한다.
- 일부 sub-query 임베딩 실패에도 전체 검색이 실패하지 않는다.
- 전역 per-source 상한(Story 2)과 중복 제거가 병합 후에도 유지된다.
- 같은 입력에서 deterministic하다.
- 관련성 회귀 없음(평가 하니스의 기대 주제 적중 유지).
- evidence bundle shape·§4 계약·문제 뱅크 검색 contract 불변.

## 테스트 기준

- `db`+`framework` 두 필드를 가진 chunk가 두 필드의 청크를 모두 포함하는지 검증한다.
- 단일 필드/무신호 chunk가 단일 쿼리 경로로 폴백하는지 검증한다.
- sub-query 1개를 fake embedder에서 실패시켜도 나머지로 결과가 나오는지 검증한다.
- 병합 후 per-source 상한·중복 제거가 유지되는지 검증한다.
- `max_subqueries` 상한이 지켜지는지, 호출 수가 폭증하지 않는지 검증한다.
- 결정성을 검증한다.

## 제외 범위

- 개념 검색 단일 쿼리 MMR/cap (Story 2에서 완료, 여기선 재사용)
- neighborhood 재균형 (Story 3에서 완료, 재사용)
- 문제 뱅크 (Story 5~6)
- Gemini 리포트 분석/점수 산정
- API/SQS/Spring Boot callback

## 개발 메모

- 사용자의 실제 리포트는 짧은 편이라 sub-query는 2~3개면 충분하다. 과한 분해는 noise이므로 신호 임계를 둔다.
- `infer_fields()`가 신호 없을 때 `["cs"]`로 폴백하는 점을 주의 — 약한 cs sub-query가 자리를 차지하지 않도록 실제 keyword evidence 우선(report-3 개발 메모와 동일 원칙).
