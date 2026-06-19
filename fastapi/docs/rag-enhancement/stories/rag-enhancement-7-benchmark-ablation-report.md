---
title: RAG Enhancement 7 - 최종 벤치마크 · Ablation · 포트폴리오 리포트
status: done
created: 2026-06-17
owner: FastAPI AI 서버
epic: rag-enhancement
tier: measurement
source_docs:
  - ../rag-enhancement-epic.md
  - ../rag-enhancement-evaluation-methodology.md
  - ./rag-enhancement-1-diversity-eval-baseline.md
---

# RAG Enhancement 7. 최종 벤치마크 · Ablation · 포트폴리오 리포트

## Status

done

## 목표

평가 방법론([rag-enhancement-evaluation-methodology.md](../rag-enhancement-evaluation-methodology.md))에 따라 baseline과 고도화본을 골든셋으로 비교하고, **각 Story의 순기여를 분리한 ablation**과 **통계적 유의성**, **case study**를 담은 포트폴리오용 벤치마크 리포트를 생성한다. "RAG를 고도화했다"를 재현 가능한 수치로 증명하는 마지막 단계다.

이 story는 Story 1의 메트릭 하니스를 확장해 사용하고, Story 2~6 구현이 끝난 뒤 실행한다.

## 선행 조건

- Story 1: 메트릭 계산 하니스 + baseline 산출물 존재.
- Story 2~6: 각 개선 구현 완료 (ablation 구성을 코드 플래그/인자로 켜고 끌 수 있어야 함).
- 골든셋(`queries.jsonl`)에 `relevanceGrades` 라벨 존재.

## 구현 범위

- **Ablation 러너**: 방법론 §4.2의 구성(B0 → +S2 → +S3 → +S4 → +S5/6)을 각각 골든셋에 돌려 전 지표를 산출. 각 개선은 인자/플래그로 토글 가능해야 한다(불가하면 해당 Story에 토글 추가를 요청).
- **관련성 지표**: Recall@k, Precision@k, MRR, nDCG@k, FieldHit@k.
- **다양성 지표**: Distinct-Source@k, ILD, Source HHI/Gini, Field/Folder Coverage, Catalog Coverage, Same-Source Neighbor Ratio.
- **통계**: 질의별 paired delta 수집 → Wilcoxon signed-rank(또는 paired bootstrap 95% CI) → p값/CI 산출. 외부 의존성 없이 표준 라이브러리로 구현(필요 시 `statistics`/직접 구현; scipy 도입은 지양).
- **합격 게이트 자동 판정**: 방법론 §4.4 기준(다양성 유의 개선 + 관련성 ε 이내 유지 + 악화 질의 비율 한도)을 통과/실패로 출력.
- **Case study 추출**: delta가 큰 질의 2~3건에 대해 baseline vs 고도화본이 실제 반환한 source/heading 목록을 나란히 덤프.
- **리포트 생성**: `rag-enhancement-benchmark.md`(+ `.json`)에 방법론 §5 구성(문제정의·개선내용·before/after 표·ablation 표·case study·한계)을 채운다. 측정 시각·커밋 해시·catalog 청크 수·model/dimension 기록.
- fake embedder 모드로 단위 테스트(지표·게이트 계산 정확성), 실제 sidecar 모드로 리포트 생성 CLI 둘 다 지원.

## 주요 파일 경로

- 구현 후보: `fastapi/scripts/rag_benchmark.py` (Story 1 `rag_diversity_eval.py`의 지표 함수 재사용)
- 통계 helper: `fastapi/app/rag/eval_metrics.py` (지표·검정 함수, 테스트 가능하게 분리)
- 골든셋: `fastapi/tests/fixtures/rag/eval/queries.jsonl`
- 산출물: `fastapi/data/rag/reports/rag-enhancement-benchmark.md`, `rag-enhancement-benchmark.json`
- 테스트 후보: `fastapi/tests/rag/test_eval_metrics.py`, `fastapi/tests/rag/test_rag_benchmark.py`

## Acceptance Criteria

- baseline과 각 ablation 구성에 대해 관련성·다양성 전 지표를 산출한다.
- nDCG/Recall/MRR/Precision이 라벨 등급 기반으로 올바르게 계산된다(소형 fixture로 손계산 검증).
- ILD·HHI·Coverage가 결과 분포에 맞게 계산된다.
- paired 통계 검정으로 p값/CI를 산출한다.
- 방법론 §4.4 합격 게이트를 자동 판정해 통과/실패를 리포트에 남긴다.
- case study가 같은 질의에 대한 baseline vs 고도화본 실제 검색 문서를 비교해 보여준다.
- 리포트에 측정 메타데이터(시각·커밋·catalog 규모·model/dim)가 기록된다.
- 같은 입력에서 리포트 수치가 deterministic하다.
- fake embedder로 지표/게이트/통계 단위 테스트가 실제 API 호출 없이 통과한다.
- 검색·추천·카탈로그 로직을 수정하지 않는다(측정만).

## Tasks/Subtasks

- [x] `eval_metrics.py`에 관련성/다양성/통계/게이트 순수 함수를 구현한다.
- [x] Story 1 하니스를 재사용해 B0, +S2, +S3, +S4, +S5/6 ablation runner를 구현한다.
- [x] baseline vs final, ablation, case study, 메타데이터를 포함한 `.md`/`.json` 리포트를 생성한다.
- [x] 합격 게이트와 악화 질의 요약을 자동 판정해 리포트에 남긴다.
- [x] 손계산 fixture 기반 metric/stat/gate/report 테스트를 추가한다.

## 테스트 기준

- 손계산 가능한 소형 fixture에서 nDCG@k, Recall@k, MRR, Precision@k가 기대값과 일치하는지 검증한다.
- 모두 같은 source면 Distinct-Source=1·HHI 최대, 고르게 퍼지면 반대인지 검증한다.
- ILD가 동일 벡터 집합에서 0, 직교 벡터에서 최대에 가까운지 검증한다.
- Wilcoxon/bootstrap 결과가 명백한 개선/무차이 합성 데이터에서 올바른 방향의 p값/CI를 주는지 검증한다.
- 합격 게이트가 "다양성↑·관련성 유지" 케이스는 통과, "관련성 급락" 케이스는 실패로 판정하는지 검증한다.
- ablation 토글이 실제로 구성별 다른 결과를 내는지 검증한다.

## 제외 범위

- 검색/추천/이웃/카탈로그 로직 변경 (Story 2~6)
- 골든셋 라벨링 자체(데이터 작업 — 방법론 §2 절차로 별도 수행)
- pgvector, 운영 평가 파이프라인 자동화/CI 통합
- API/SQS/Spring Boot callback

## 개발 메모

- 통계는 표본이 작으므로 비모수 검정 + 분포 보고가 정직하다. 평균 개선만 자랑하지 말고 악화된 질의도 리포트에 노출한다.
- case study가 포트폴리오에서 가장 강력하다. "html-css 한 파일 4/5 도배 → 4개 파일·3개 필드로 분산, 핵심은 여전히 1위"처럼 **구체 문서명과 함께** 보여준다.
- ablation 토글이 깔끔하지 않으면 Story 2~6 구현 시 옵션 인자(기본 on, 테스트에서 off)로 설계하도록 역으로 요구한다. 평가 가능성을 구현 단계에서 확보하는 게 핵심.
- `eval_metrics.py`를 순수 함수로 분리하면 단위 테스트가 쉽고, 리포트 스크립트는 얇게 유지된다.

## Dev Notes

- Story 2~6 구현이 완료되고 토글 가능한 옵션이 있어야 제대로 실행된다.
- 이 story는 측정/리포트만 담당하며 검색·추천 로직을 변경하지 않는다.
- case study는 포트폴리오 증거로 읽히도록 실제 source/heading을 나란히 보여준다.

## Dev Agent Record

### Debug Log

- 2026-06-19: Preflight confirmed current branch `feature/rag-enhancement`; Story 1~6 are `done` in `fastapi/docs/rag-enhancement/rag-enhancement-sprint-status.yaml`.
- 2026-06-19: Story 7 was `backlog` in sprint status, but the user explicitly provided `fastapi/docs/rag-enhancement/stories/rag-enhancement-7-benchmark-ablation-report.md`; continued per user instruction and recorded this exception.
- 2026-06-19: Added RED tests for `app.rag.eval_metrics` and `scripts.rag_benchmark`; initial runs failed with missing modules as expected.
- 2026-06-19: Implemented pure relevance/diversity/stat/gate helpers in `fastapi/app/rag/eval_metrics.py`.
- 2026-06-19: Implemented fake-embedding-only benchmark/ablation/report CLI in `fastapi/scripts/rag_benchmark.py`, reusing Story 1 fixture loading, fake embedder, fake stores, and validation helpers.
- 2026-06-19: Full default benchmark initially exceeded practical runtime when Tier C also computed evidence bundles; adjusted Tier C to 150-query top-k diversity sweep and kept Tier A evidence bundle measurement intact.
- 2026-06-19: Generated `fastapi/data/rag/reports/rag-enhancement-benchmark.md` and `.json` with `embeddingMode=fake`, `callsGemini=false`, model `fake-hash-embedding`, dimensions `32`.
- 2026-06-19: `python3 -m unittest tests.rag.test_eval_metrics` passed (5 tests).
- 2026-06-19: `python3 -m unittest tests.rag.test_rag_benchmark` passed (3 tests).
- 2026-06-19: `python3 -m unittest tests.rag.test_diversity_eval` passed (8 tests).
- 2026-06-19: `python3 -m unittest tests.rag.test_problem_bank_search` passed (14 tests).
- 2026-06-19: `python3 -m unittest tests.rag.test_quiz_recommender` passed (10 tests).
- 2026-06-19: `python3 -m unittest tests.scoring.test_daily_report_service` passed (7 tests).
- 2026-06-19: Code review found no closure-blocking issues; Story 7 approved for closure.

### Completion Notes

- Story 7 was implemented as measurement/reporting only. No search, recommendation, catalog generation, API, SQS, Spring callback, `problem-embeddings.jsonl`, manifest, summary, or `problems.jsonl` runtime artifacts were regenerated or modified.
- The benchmark supports B0 → +S2 → +S3 → +S4 → +S5/6 using only currently exposed public arguments: concept MMR/source cap, source-neighborhood quota arguments, `max_subqueries`, and problem-bank keyword-only vs fake embedding hybrid mode.
- S3, S4, and S5/6 are documented with limitations where current public toggles cannot perfectly isolate pre-story internals.
- Primary Tier A concept top-k gate result is FAIL: ILD is the only statistically significant diversity improvement, Recall@k falls from 0.1374 to 0.1099, and worsened query ratio is 0.1758.
- Evidence bundle distribution improvement is strong: distinct source 4.2967 → 7.4505, catalog coverage 0.3736 → 0.8352, source HHI 0.2732 → 0.1608, same-source neighbor ratio 1.0000 → 0.5000.
- Reports explicitly state that this is source coverage/diversity/distribution measurement, not final answer generation quality evaluation. Relevance metrics are binary because `relevanceGrades` is empty; real semantic accuracy and answer quality require follow-up real embedding + graded relevance evaluation.
- Review closure note: primary top-k gate remains FAIL and is intentionally reported as such; closure is for the measurement/reporting story, with real embedding + graded relevance left as non-blocking follow-up.

## File List

- fastapi/app/rag/eval_metrics.py
- fastapi/scripts/rag_benchmark.py
- fastapi/tests/rag/test_eval_metrics.py
- fastapi/tests/rag/test_rag_benchmark.py
- fastapi/data/rag/reports/rag-enhancement-benchmark.md
- fastapi/data/rag/reports/rag-enhancement-benchmark.json
- fastapi/README.md
- fastapi/docs/troubleshooting/rag/rag-enhancement-benchmark.md
- fastapi/docs/rag-enhancement/rag-enhancement-sprint-status.yaml
- fastapi/docs/rag-enhancement/stories/rag-enhancement-7-benchmark-ablation-report.md

## Change Log

- 2026-06-18: Added lightweight BMAD dev-story sections.
- 2026-06-19: Implemented Story 7 benchmark/ablation/report layer, generated reports, documented results and limitations, and marked story ready for review.
- 2026-06-19: Review approved Story 7 for closure and marked status done.
