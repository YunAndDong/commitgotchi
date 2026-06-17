---
title: RAG Enhancement 1 - 검색 다양성 평가 하니스 & 베이스라인
status: backlog
created: 2026-06-17
owner: FastAPI AI 서버
epic: rag-enhancement
tier: measurement
source_docs:
  - ../rag-enhancement-epic.md
  - ../rag-enhancement-evaluation-methodology.md
  - ../../report/stories/report-3-rag-search-and-source-neighborhood.md
---

# RAG Enhancement 1. 검색 다양성 평가 하니스 & 베이스라인

## Status

backlog

## 목표

이후 Story 2~6의 효과를 **수치로 비교**할 수 있도록, 현재 검색의 다양성·관련성을 측정하는 평가 하니스를 만들고 베이스라인을 박제한다. 고도화 작업이 실제로 "더 많고 다채로운 문서"를 검색하게 만들었는지, 그 과정에서 관련성이 떨어지지 않았는지 가드한다.

이 story는 검색 로직을 바꾸지 않는다. **측정 도구만** 만든다. 측정할 지표·골든셋·프로토콜의 정의는 [rag-enhancement-evaluation-methodology.md](../rag-enhancement-evaluation-methodology.md)를 SSOT로 따른다. 전체 ablation·통계·포트폴리오 리포트는 [Story 7](./rag-enhancement-7-benchmark-ablation-report.md)이 이 하니스를 확장해 수행한다.

## 배경

`build_report_evidence_bundle()` / `search_concept_chunks()`(개념)와 `search_problem_bank()`(문제 뱅크)가 이미 동작한다. 다양성을 정량화하려면 대표 질의 세트에 대해 두 검색을 돌리고 결과의 문서 분포를 집계해야 한다.

## 구현 범위

- 대표 평가 질의 세트 정의 (`fastapi/tests/fixtures/rag/eval/queries.jsonl` 등). 각 질의는 리포트형 텍스트 + 기대 주제/필드 라벨을 가진다. db·network·algorithm·framework·cs 5개 필드를 골고루 커버한다.
- 개념 검색과 문제 뱅크 검색 각각에 대해 다양성 메트릭 계산:
  - 질의당 **distinct sourcePath 수** (top-k 및 evidence bundle 기준)
  - **source 집중도**: 최빈 source 비율, HHI(Herfindahl) 또는 normalized entropy
  - **필드 커버리지**: 결과에 등장한 distinct fieldHints 수
  - **폴더 커버리지**: distinct 상위 폴더(`sourcePath`의 첫 segment) 수
  - 이웃 확장의 **same-source 비율** (neighborhood 중 씨앗과 같은 source 비중)
- 관련성 회귀 가드: 질의별 기대 주제/필드가 top-k 안에 들어오는지 hit@k / 기대 필드 적중 여부.
- 베이스라인 리포트 생성: 현재 코드 기준 메트릭을 `fastapi/data/rag/reports/rag-diversity-baseline.md`(+ `.json`)로 저장.
- 실제 Gemini 호출 없이도 돌 수 있게 fake embedder 주입 경로를 제공하되, 실제 sidecar(`chunk-embeddings.jsonl`)로 돌리는 CLI 모드도 제공.

## 주요 파일 경로

- 구현 후보: `fastapi/scripts/rag_diversity_eval.py`
- 평가 질의 fixture: `fastapi/tests/fixtures/rag/eval/queries.jsonl`
- 베이스라인 산출물: `fastapi/data/rag/reports/rag-diversity-baseline.md`, `fastapi/data/rag/reports/rag-diversity-baseline.json`
- 테스트 후보: `fastapi/tests/rag/test_diversity_eval.py`
- 참고(수정 금지): `fastapi/app/rag/concept_search.py`, `fastapi/app/rag/problem_bank_search.py`

## Acceptance Criteria

- 평가 질의 세트가 5개 필드를 모두 커버하고 각 질의에 기대 주제/필드 라벨이 있다.
- 개념 검색·문제 뱅크 검색 각각에 대해 distinct sourcePath, source 집중도, 필드/폴더 커버리지, same-source 이웃 비율을 산출한다.
- 같은 입력에서 메트릭이 deterministic하다.
- 베이스라인 리포트가 현재 코드 기준으로 생성되고 파일로 저장된다.
- 평가 하니스는 검색/추천/카탈로그 로직을 수정하지 않는다.
- fake embedder로 단위 테스트가 실제 API 호출 없이 통과한다.

## 테스트 기준

- 합성 catalog/문제 뱅크 fixture에서 메트릭 계산이 기대값과 일치하는지 검증한다(예: 모두 같은 source면 distinct=1, 집중도=최대).
- 결과가 두 source에 고르게 퍼지면 집중도가 낮아지는지 검증한다.
- 관련성 가드가 기대 주제 미적중 질의를 회귀로 표시하는지 검증한다.
- 빈 catalog/빈 결과에서 0으로 안전하게 처리되는지 검증한다.

## 제외 범위

- 검색·이웃·추천·카탈로그 로직 변경 (Story 2~6)
- 문제 뱅크 임베딩 생성 (Story 5)
- API/SQS/Spring Boot callback
- pgvector

## 개발 메모

- 메트릭은 "관련성 vs 다양성" 두 축을 함께 보여줘야 한다. 다양성만 올리고 관련성이 떨어지면 실패다.
- 베이스라인 숫자는 Story 2~6 PR에서 before/after로 인용한다. 리포트에 측정 시각·catalog 청크 수·model/dimension을 함께 기록한다.
