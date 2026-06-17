---
title: RAG Enhancement 5 - 문제 뱅크 임베딩 인덱스 신규 구축
status: backlog
created: 2026-06-17
owner: FastAPI AI 서버
epic: rag-enhancement
tier: 3
source_docs:
  - ../rag-enhancement-epic.md
  - ../../report/stories/report-3-rag-search-and-source-neighborhood.md
  - ../../quiz-bank/problem-bank-implementation.md
---

# RAG Enhancement 5. 문제 뱅크 임베딩 인덱스 신규 구축

## Status

backlog

## 목표

문제 뱅크(`problems.jsonl`, 798문제)는 현재 키워드(용어 빈도)로만 검색된다. Story 6의 하이브리드 검색을 위해, 개념 임베딩과 **같은 model/dimension**으로 문제별 임베딩 sidecar를 신규 생성한다. 이 story는 임베딩 **빌드/저장/로드**까지만 하고, 검색 로직 변경은 Story 6에서 한다.

## 배경

개념 쪽(report-3)이 이미 동일 패턴을 구현해 두었으므로 그대로 재사용한다.

- 빌더: `build_concept_embeddings.py` / 클라이언트: `concept_embeddings.py` (`GeminiEmbeddingClient`, `gemini-embedding-2`, 768차원)
- 스토어: `embedding_store.py` (`chunkId` join, stale issue 수집)
- env: `GEMINI_EMBEDDING_MODEL`, `GEMINI_EMBEDDING_DIMENSIONS` (개념과 통일)

문제 뱅크 임베딩도 동일 model/dimension으로 만들어야 개념·문제 임베딩 공간이 일관되고, 향후 통합·디버깅이 쉽다.

## 구현 범위

- 문제 임베딩 입력 텍스트 구성: `question` + `model_answer` + `primary_field`/`fields` + rubric(`must_mention`/`optional_mention`) + `heading_path`. (검색에 유효한 의미 신호 위주, API key/원문 중복 저장 금지.)
- `gemini-embedding-2` 768차원으로 문제별 document embedding 생성.
- sidecar JSONL 저장: `fastapi/data/rag/catalog/problem-embeddings.jsonl`. record는 `problemId`(또는 `source_key`), `contentHash`, `model`, `outputDimensionality`, `embedding`을 포함.
- stale 처리: `contentHash`/model/dimension mismatch는 stale로 표시. 기존 매칭 임베딩은 재사용, 누락/stale만 재생성.
- 빌드 summary/manifest 리포트 생성(개념 쪽과 동일 형식).
- in-memory 스토어 로더: `problems.jsonl`과 sidecar를 `problemId`/`source_key`로 join, stale/missing issue 수집.
- CLI 빌드 진입점 제공. 실패 chunk를 report에 남기고 진행 위치를 알 수 있게.
- 테스트는 fake embedder로 실제 API 호출 없이 통과.

## 주요 파일 경로

- 구현 후보: `fastapi/app/rag/build_problem_bank_embeddings.py`
- 구현 후보: `fastapi/app/rag/problem_embedding_store.py`
- 재사용: `fastapi/app/rag/concept_embeddings.py` (`GeminiEmbeddingClient`, settings)
- 입력: `fastapi/data/rag/catalog/problems.jsonl`
- 생성 sidecar: `fastapi/data/rag/catalog/problem-embeddings.jsonl`
- 생성 리포트: `fastapi/data/rag/manifests/problem-embeddings-manifest.json`, `fastapi/data/rag/reports/problem-embeddings-summary.md`
- 테스트 후보: `fastapi/tests/rag/test_problem_bank_embeddings.py`, `fastapi/tests/rag/test_problem_embedding_store.py`
- 참고(수정 금지): `fastapi/app/rag/problem_bank.py`, `build_problem_bank.py`, `problem_bank_store.py`

## Acceptance Criteria

- 문제 임베딩이 `gemini-embedding-2` 768차원으로 생성되어 개념 임베딩과 model/dimension이 일치한다.
- sidecar record가 `problemId`/`source_key`, `contentHash`, `model`, `outputDimensionality`, `embedding`을 포함한다.
- `contentHash`/model/dimension mismatch가 stale로 수집되고, 매칭 임베딩은 재사용된다.
- in-memory 스토어가 `problems.jsonl`과 sidecar를 안정적으로 join하고 issue를 수집한다.
- sidecar/문제 뱅크 파일 없음, 빈 입력에서 crash 없이 빈 스토어를 반환한다.
- fake embedder로 단위 테스트가 실제 API 호출 없이 통과한다.
- `problems.jsonl` 생성 흐름과 `search_problem_bank()`/`recommend_quizzes()` contract를 변경하지 않는다.
- §4 계약 불변.

## Tasks/Subtasks

- [ ] 문제 임베딩 입력 텍스트 구성 helper와 content hash 기준을 정의한다.
- [ ] `problem-embeddings.jsonl` 생성 CLI와 manifest/summary 리포트를 추가한다.
- [ ] 문제 뱅크와 sidecar를 join하는 in-memory embedding store를 구현한다.
- [ ] missing/stale/model/dimension mismatch issue 수집과 재사용 경로를 구현한다.
- [ ] fake embedder 기반 생성, stale 판정, 빈/누락 파일 테스트를 추가한다.

## 테스트 기준

- fake embedder로 problem-embeddings.jsonl을 생성하고 record 필드를 검증한다.
- contentHash/model/dimension mismatch가 stale로 제외되는지 검증한다.
- 기존 임베딩 재사용, 누락/stale만 재생성되는지 검증한다.
- 스토어 join이 올바른 problem↔embedding 매칭을 만드는지 검증한다.
- 빈/누락 파일 smoke test.

## 제외 범위

- 문제 뱅크 검색/추천 로직 변경 (Story 6)
- 개념 임베딩 재빌드 (불필요, 재사용)
- pgvector/운영 vector index
- Gemini 채점/리포트 분석
- API/SQS/Spring Boot callback

## 개발 메모

- 실제 빌드는 798문제 전수 임베딩이라 API 호출이 발생한다. rate limit 대비 chunk 단위 실패 기록·재개 가능하게 둔다(개념 빌드와 동일).
- `problemId`가 정수라 stale/누락 판정 키로 충분하지만, source_key도 함께 기록해 디버깅을 돕는다.
- 임베딩 입력에 rubric을 넣으면 "무엇을 묻는 문제인지"가 강화돼 의미 검색 품질이 오른다. 다만 `must_not_confuse`처럼 혼동 방지용 텍스트는 신호를 흐릴 수 있어 포함 여부를 실험으로 결정한다.

## Dev Notes

- 이 story는 임베딩 빌드/저장/로드만 담당하고 검색 ranking은 Story 6에서 변경한다.
- 개념 임베딩의 model/dimension/env 패턴을 재사용한다.
- 단위 테스트는 fake embedder로 API 호출 없이 통과해야 한다.

## Dev Agent Record

### Debug Log

- TBD

### Completion Notes

- TBD

## File List

- TBD

## Change Log

- 2026-06-18: Added lightweight BMAD dev-story sections.
