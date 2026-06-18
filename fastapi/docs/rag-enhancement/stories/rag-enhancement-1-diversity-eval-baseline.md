---
title: RAG Enhancement 1 - 검색 다양성 평가 하니스 & 베이스라인
status: done
created: 2026-06-17
owner: FastAPI AI 서버
epic: rag-enhancement
tier: measurement
source_docs:
  - ../rag-enhancement-epic.md
  - ../rag-enhancement-evaluation-methodology.md
  - ../labeling/label-set-spec.md
  - ../../report/stories/report-3-rag-search-and-source-neighborhood.md
---

# RAG Enhancement 1. 검색 다양성 평가 하니스 & 베이스라인

## Status

done

## 목표

이후 Story 2~6의 효과를 **수치로 비교**할 수 있도록, 현재 검색의 다양성·관련성을 측정하는 평가 하니스를 만들고 베이스라인을 박제한다. 고도화 작업이 실제로 "더 많고 다채로운 문서"를 검색하게 만들었는지, 그 과정에서 관련성이 떨어지지 않았는지 가드한다.

이 story는 검색 로직을 바꾸지 않는다. **측정 도구만** 만든다. 측정할 지표·골든셋·프로토콜의 정의는 [rag-enhancement-evaluation-methodology.md](../rag-enhancement-evaluation-methodology.md)를 SSOT로 따른다. 전체 ablation·통계·포트폴리오 리포트는 [Story 7](./rag-enhancement-7-benchmark-ablation-report.md)이 이 하니스를 확장해 수행한다.

## 배경

`build_report_evidence_bundle()` / `search_concept_chunks()`(개념)와 `search_problem_bank()`(문제 뱅크)가 이미 동작한다. 다양성을 정량화하려면 대표 질의 세트에 대해 두 검색을 돌리고 결과의 문서 분포를 집계해야 한다.

## 구현 범위

- Tier A source coverage golden set을 읽고 검증한다. 최종 fixture는 `fastapi/tests/fixtures/rag/eval/queries.jsonl`이며, 카탈로그 distinct source 91개 각각에 정확히 1개 질의를 앵커링한다. 라벨 스키마·source tier·self-retrieval 방지 규칙은 [label-set-spec.md](../labeling/label-set-spec.md)를 재정의하지 않고 그대로 따른다.
- Tier C 비라벨 다양성 스윕을 별도 입력으로 생성/실행할 수 있게 한다. source heading 기반 150~300개 자동 질의이며 라벨 없이 다양성 지표만 산출한다.
- 개념 검색과 문제 뱅크 검색 각각에 대해 다양성 메트릭 계산:
  - 질의당 **distinct sourcePath 수** (top-k 및 evidence bundle 기준)
  - **source 집중도**: 최빈 source 비율, HHI(Herfindahl) 또는 normalized entropy
  - **필드 커버리지**: 결과에 등장한 distinct fieldHints 수
  - **폴더 커버리지**: distinct 상위 폴더(`sourcePath`의 첫 segment) 수
  - 이웃 확장의 **same-source 비율** (neighborhood 중 씨앗과 같은 source 비중)
- 관련성 회귀 가드: Tier A의 `relevantSourcePaths` 기준으로 Recall@k / Hit@k / MRR / source coverage를 산출한다. `relevanceGrades`가 존재하는 경우에만 nDCG를 보조 지표로 계산한다.
- 베이스라인 리포트 생성: 현재 코드 기준 메트릭을 `fastapi/data/rag/reports/rag-diversity-baseline.md`(+ `.json`)로 저장.
- 실제 Gemini 호출 없이도 돌 수 있게 fake embedder 주입 경로를 제공하되, 실제 sidecar(`chunk-embeddings.jsonl`)로 돌리는 CLI 모드도 제공.

## 주요 파일 경로

- 구현 후보: `fastapi/scripts/rag_diversity_eval.py`
- 평가 질의 fixture: `fastapi/tests/fixtures/rag/eval/queries.jsonl`
- 베이스라인 산출물: `fastapi/data/rag/reports/rag-diversity-baseline.md`, `fastapi/data/rag/reports/rag-diversity-baseline.json`
- 테스트 후보: `fastapi/tests/rag/test_diversity_eval.py`
- 참고(수정 금지): `fastapi/app/rag/concept_search.py`, `fastapi/app/rag/problem_bank_search.py`

## Acceptance Criteria

- Tier A fixture가 정확히 91개 record를 가지며, `sourcePath` 집합이 `fastapi/data/rag/catalog/chunks.jsonl`의 distinct source 집합과 중복·누락 없이 일치한다.
- Tier A record 검증은 `label-set-spec.md`의 필수 필드와 규칙을 따른다. Story 1 안에서 별도 라벨 스키마를 새로 정의하지 않는다.
- Tier C 비라벨 다양성 스윕이 라벨 없이 실행 가능하며 150~300개 자동 질의에 대해 다양성 지표를 산출한다.
- 개념 검색·문제 뱅크 검색 각각에 대해 distinct sourcePath, source 집중도, 필드/폴더 커버리지, catalog coverage, same-source 이웃 비율을 산출한다.
- Tier A 관련성 회귀 가드로 Recall@k / Hit@k / MRR / source coverage를 산출하고, nDCG는 `relevanceGrades`가 있을 때만 보조 지표로 산출한다.
- 같은 입력에서 메트릭이 deterministic하다.
- 베이스라인 리포트가 현재 코드 기준으로 생성되고 파일로 저장된다.
- 평가 하니스는 검색/추천/카탈로그 로직을 수정하지 않는다.
- fake embedder로 단위 테스트가 실제 API 호출 없이 통과한다.

## Tasks/Subtasks

- [x] Tier A 평가 fixture(`fastapi/tests/fixtures/rag/eval/queries.jsonl`) 로더와 validator를 구현한다.
- [x] `chunks.jsonl` distinct source 91개와 Tier A fixture의 `sourcePath` 집합이 완전 일치하는지 검증한다.
- [x] Tier C 비라벨 다양성 스윕 질의 생성/입력 경로를 추가한다.
- [x] 개념 검색·문제 뱅크 검색 결과를 받아 다양성/관련성 메트릭을 계산하는 순수 함수를 구현한다.
- [x] `fastapi/scripts/rag_diversity_eval.py` CLI로 baseline `.md`/`.json` 리포트를 생성한다.
- [x] fake embedder/소형 fixture 기반 단위 테스트를 추가해 실제 API 호출 없이 검증한다.
- [x] 검색·추천·카탈로그 로직을 변경하지 않았는지 회귀 테스트로 확인한다.

## 테스트 기준

- 소형 catalog fixture에서 distinct source 집합과 Tier A fixture `sourcePath`가 중복·누락 없이 일치해야 한다.
- Tier A fixture가 앵커 `sourcePath`를 `relevantSourcePaths`에 포함하지 않으면 validator가 실패해야 한다.
- Tier C 스윕은 라벨 필드 없이도 다양성 메트릭 산출까지 완료돼야 한다.
- 합성 catalog/문제 뱅크 fixture에서 메트릭 계산이 기대값과 일치하는지 검증한다(예: 모두 같은 source면 distinct=1, 집중도=최대).
- 결과가 두 source에 고르게 퍼지면 집중도가 낮아지는지 검증한다.
- 관련성 가드가 관련 source 미적중 질의를 회귀로 표시하는지 검증한다.
- 빈 catalog/빈 결과에서 0으로 안전하게 처리되는지 검증한다.

## 제외 범위

- 검색·이웃·추천·카탈로그 로직 변경 (Story 2~6)
- 문제 뱅크 임베딩 생성 (Story 5)
- API/SQS/Spring Boot callback
- pgvector

## 개발 메모

- 메트릭은 "관련성 vs 다양성" 두 축을 함께 보여줘야 한다. 다양성만 올리고 관련성이 떨어지면 실패다.
- 이 프로젝트의 균등 기준은 필드가 아니라 source다. 필드 분포는 코퍼스를 그대로 반영하고, framework 쏠림 점검용 보조 태그로만 해석한다.
- README나 짧은 문서도 Tier A 생성/검증 단계에서는 제외하지 않는다. 저가치 문서의 품질 영향은 `sourceTier`별 aggregate 분리로 다룬다.
- 베이스라인 숫자는 Story 2~6 PR에서 before/after로 인용한다. 리포트에 측정 시각·catalog 청크 수·model/dimension을 함께 기록한다.

## Dev Notes

- 이 story는 개선 구현이 아니라 측정 기준과 baseline 산출물을 만드는 준비 작업이다.
- 평가 방법론은 `../rag-enhancement-evaluation-methodology.md`를 SSOT로 따른다.
- 라벨셋 생성/검증 세부 규칙은 `../labeling/label-set-spec.md`를 SSOT로 따른다.
- 실제 Gemini API 호출은 단위 테스트에서 금지하고 fake embedder/fixture를 우선한다.
- Story 2~6의 ablation 비교를 위해 가능한 한 순수 함수와 CLI를 분리한다.

## Dev Agent Record

### Debug Log

- 2026-06-18: `python3 -m unittest tests.rag.test_diversity_eval` failed RED before harness existed (`ModuleNotFoundError: scripts.rag_diversity_eval`).
- 2026-06-18: Implemented `fastapi/scripts/rag_diversity_eval.py` with Tier A loading/validation, Tier C generation, pure diversity/relevance metrics, fake embedder mode, sidecar CLI mode, and report writers.
- 2026-06-18: `python3 -m unittest tests.rag.test_diversity_eval` passed (7 tests).
- 2026-06-18: `python3 scripts/rag_diversity_eval.py --validate-only` passed: 91 records, source full match, 0 duplicates, sorted=True.
- 2026-06-18: `python3 scripts/rag_diversity_eval.py --embedding-mode fake` generated baseline Markdown and JSON reports.
- 2026-06-18: `python3 -m unittest discover tests/rag` passed (63 tests); `python3 -m unittest discover tests/scoring` passed (45 tests).
- 2026-06-18: System Python full discovery initially failed due missing local dependencies; temporary venv full discovery passed after installing test dependencies and Python 3.14-compatible psycopg binary wrapper (`/tmp/commitgotchi-fastapi-venv/bin/python -m unittest discover tests`, 190 tests).

### Completion Notes

- Tier A final fixture is used as SSOT from `fastapi/tests/fixtures/rag/eval/queries.jsonl`; no label set regeneration, merge, or comparison was performed.
- Validator enforces record count, exact catalog source coverage, duplicate/missing/extra detection, sourcePath sorting, queryId slug rule, sourceTier, required label fields, anchor-in-relevant rule, and optional relevance grade bounds.
- Tier C unlabeled sweep can be generated deterministically from catalog headings (150-300 records) or supplied via CLI input path; generated records do not carry relevance labels.
- Diversity and relevance calculations are pure functions over normalized retrieval results and cover distinct sources, source concentration, field/folder/catalog coverage, same-source neighborhood ratio, Recall@k, Hit@k, MRR, source coverage, and optional nDCG.
- Baseline reports were generated in fake embedder mode, which does not call Gemini. `--embedding-mode sidecar` is available for sidecar/Gemini query embedding runs.
- Search, recommendation, neighborhood, and catalog implementation files were not modified.

## File List

- fastapi/scripts/rag_diversity_eval.py
- fastapi/tests/rag/test_diversity_eval.py
- fastapi/tests/fixtures/rag/eval/queries.jsonl
- fastapi/data/rag/reports/rag-diversity-baseline.md
- fastapi/data/rag/reports/rag-diversity-baseline.json
- fastapi/docs/rag-enhancement/rag-enhancement-sprint-status.yaml
- fastapi/docs/rag-enhancement/stories/rag-enhancement-1-diversity-eval-baseline.md

## Change Log

- 2026-06-18: Aligned Story 1 scope with source coverage golden set (91) and unlabeled diversity sweep SSOT.
- 2026-06-18: Added lightweight BMAD dev-story sections and marked Story 1 ready for development.
- 2026-06-18: Implemented RAG diversity evaluation harness, fake/sidecar CLI paths, unit tests, and baseline reports; marked story ready for review.
- 2026-06-18: BMAD code review found no blocking issues; marked Story 1 done.
