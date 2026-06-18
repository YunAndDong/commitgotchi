---
title: RAG 검색 다양성 고도화 Epic
status: done
created: 2026-06-17
owner: FastAPI AI 서버
scope: fastapi/ 하위 내부 검색 품질 개선만. Spring Boot 인스턴스·§4 계약·출력 shape 불변
related_docs:
  - fastapi/docs/rag-enhancement/rag-enhancement-evaluation-methodology.md
  - fastapi/docs/rag-implementation-plan.md
  - fastapi/docs/report/report-generation-epic.md
  - fastapi/docs/report/stories/report-3-rag-search-and-source-neighborhood.md
  - fastapi/docs/quiz-bank/problem-bank-implementation.md
  - _bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md#4
---

# RAG 검색 다양성 고도화 Epic

## 1. 목적

현재 RAG는 동작하지만 **검색 결과가 특정 문서에 쏠린다.** 질의 하나가 같은 source 파일의 청크 여러 개로 채워지고, 이웃 확장이 그 집중을 더 키운다. 이 epic의 목표는 **§4 계약과 출력 shape를 1바이트도 바꾸지 않으면서**, 리포트 근거 보강과 추천 퀴즈 선택이 "더 많고 다채로운 문서"에서 나오도록 FastAPI 내부 검색 계층을 고도화하는 것이다.

핵심 한 줄: *관련성은 유지하되, 같은 문서 도배를 막고 검색이 닿는 문서·필드의 폭을 넓힌다.*

## 2. 현재 기준 (왜 쏠리는가)

`fastapi/app/rag/` 코드와 `fastapi/data/rag/catalog/` 데이터를 분석한 결과, 다양성을 죽이는 원인은 4가지다.

| # | 원인 | 위치 | 효과 |
| --- | --- | --- | --- |
| 1 | 다양성 제어 부재 | `concept_search.py` `search_concept_chunks` | `score = similarity*10 + field_overlap*0.01 + matched_terms*0.001`로 사실상 코사인 단독 정렬. top_k=5가 의미상 거의 같은 청크로 채워진다. per-source 상한도 MMR도 없다. |
| 2 | 같은-source 편향 이웃 확장 | `source_neighborhood.py` | 6개 reason 중 4개(`previous/next/parent/sibling/same_source_nearby`)가 전부 같은 source 파일에서만 뽑는다. 타 문서 reason(`same_folder_related`)은 우선순위 꼴찌라 evidence가 씨앗 문서 한 개로 굳는다. |
| 3 | 문제 뱅크 의미검색 부재 | `problem_bank_search.py` | 용어 빈도 매칭만 한다. 표현이 다른 의미적 매치를 놓치고, 용어가 많이 박힌 동일 문제만 반복 노출된다. |
| 4 | 필드 힌트 편향 | `concept_catalog.py` `infer_field_hints` | `framework` 448 vs `db` 96. 870청크 중 145개는 힌트가 비어 있어 `field_overlap` 신호가 framework로 쏠린다. (Tier 4 / 후속) |

데이터 규모 기준:

- 개념 카탈로그: 870 청크 / **91 source 파일** (`chunks.jsonl`), `gemini-embedding-2` 768차원 sidecar(`chunk-embeddings.jsonl`)
- 문제 뱅크: 798 문제 (`problems.jsonl`), **임베딩 없음**

## 3. 핵심 원칙 (계약 불변)

- **§4 계약 불변.** `recommendedQuizzes`, `problemId`, `question`, `modelAnswer`, `scoreAllocation`, `scoreDelta` 등 두 서버 간 출력 shape는 바꾸지 않는다. 이 epic은 그 shape를 **채우는 내부 검색의 질**만 개선한다.
- **Spring Boot 인스턴스를 건드리지 않는다.** 모든 산출물은 `fastapi/` 하위.
- **관련성 회귀 금지.** 다양성을 위해 관련성을 희생하지 않는다. 알려진 질의가 여전히 올바른 주제를 top 결과로 찾아야 한다(Story 1 평가 하니스로 가드).
- **결정성 유지.** 같은 입력·같은 catalog에서 검색 ranking은 deterministic해야 한다. 다양성 로직(MMR·쿼터·라운드로빈)도 tie-break까지 결정적으로 둔다.
- **기존 임베딩 재사용, 신규만 빌드.** 개념 임베딩(`gemini-embedding-2` 768)은 그대로 재사용한다. 문제 뱅크 임베딩만 **신규 생성**한다. model/dimension은 개념 쪽과 통일한다.
- **fallback 보존.** 임베딩 인덱스 없음·query embedding 실패 시 keyword fallback이 계속 동작해야 한다.

## 4. 범위 (Tier 1~3)

이 epic은 사용자 합의에 따라 Tier 1~3을 한 번에 다룬다(문제 뱅크를 건드리면 어차피 임베딩 신규 빌드가 필요하므로 작업을 쪼개지 않는다).

- **Tier 1 — 다양성 직접 주입:** 개념 검색 MMR·per-source 상한, 이웃 확장 재균형.
- **Tier 2 — 검색 풀 확대:** 필드/주제 멀티쿼리 + 쿼터 병합.
- **Tier 3 — 문제 뱅크 의미검색화:** 문제 뱅크 임베딩 신규 빌드 + 키워드·벡터 하이브리드 검색·추천 다양화.

**후속(이 epic 제외):** Tier 4 필드 힌트 재균형(`FIELD_KEYWORDS` 보강, 개념 카탈로그 재생성 + 개념 임베딩 재빌드)은 별도 후속으로 남긴다. 단, Story 4·Story 6에서 필드 신호에 과의존하지 않도록 설계해 Tier 4 없이도 효과가 나게 한다.

## 5. Story 목록

평가를 **측정 → 개선 → 검증**으로 감싼다. 개선(Story 2~6)이 실제로 효과가 있었는지 정형적으로 증명하는 측정 Story(1, 7)와 방법론 문서가 짝을 이룬다.

권장 구현 순서:

1. **검색 다양성 평가 하니스 & 베이스라인** — 고도화 전/후 비교 기준 확보 (측정 먼저)
2. **개념 검색 MMR 재정렬 + per-source 상한** (Tier 1)
3. **source-neighborhood 다양성 재균형** (Tier 1)
4. **필드/주제 멀티쿼리 검색 & 쿼터 병합** (Tier 2)
5. **문제 뱅크 임베딩 인덱스 신규 구축** (Tier 3)
6. **문제 뱅크 하이브리드 검색 & 추천 다양화** (Tier 3)
7. **최종 벤치마크 · Ablation · 포트폴리오 리포트** — 개선 효과 정형 증명 (측정 마지막)

| Story | 파일 | Tier |
| --- | --- | --- |
| — | `rag-enhancement-evaluation-methodology.md` (방법론 spec) | 측정 |
| 1 | `stories/rag-enhancement-1-diversity-eval-baseline.md` | 측정 |
| 2 | `stories/rag-enhancement-2-concept-search-mmr-source-cap.md` | 1 |
| 3 | `stories/rag-enhancement-3-source-neighborhood-rebalance.md` | 1 |
| 4 | `stories/rag-enhancement-4-field-multiquery-quota-merge.md` | 2 |
| 5 | `stories/rag-enhancement-5-problem-bank-embeddings.md` | 3 |
| 6 | `stories/rag-enhancement-6-problem-bank-hybrid-search.md` | 3 |
| 7 | `stories/rag-enhancement-7-benchmark-ablation-report.md` | 측정 |

## 6. Epic 완료 기준

- 평가 방법론(골든셋·표준 IR 지표·다양성 지표·ablation·통계·합격 게이트)이 정의되고, Story 1·7로 baseline과 최종을 정형 비교한다.
- Story 7의 합격 게이트(다양성 유의 개선 + 관련성 ε 이내 유지 + 악화 질의 비율 한도)를 자동 판정하고, 포트폴리오용 before/after 리포트가 생성된다.
- Story 1의 평가 하니스로 고도화 전/후 다양성 지표(질의당 distinct sourcePath 수, source 집중도, 필드/폴더 커버리지)와 관련성 회귀를 비교할 수 있다.
- 개념 검색이 같은 source 파일로 도배되지 않는다(per-source 상한 + MMR). 동일 질의에서 등장 파일 수가 베이스라인 대비 증가한다.
- source-neighborhood가 씨앗 문서 한 개로 굳지 않고 타 문서 컨텍스트를 cap 안에서 안정적으로 포함한다.
- 멀티쿼리 병합으로 db·network 등 소외 필드가 검색 결과에서 대표성을 갖는다.
- 문제 뱅크가 키워드+벡터 하이브리드로 검색되어 의미적 매치를 회수하고, 추천 퀴즈가 더 다양한 source·주제에서 나온다.
- 위 모든 변경에도 §4 계약 출력 shape와 기존 테스트가 깨지지 않는다.
- 관련성 회귀가 없다(평가 하니스의 known-query 체크 통과).

## 7. Closure Notes

- 2026-06-19: Story 1~7 are complete, and this epic is closed for the agreed A안 scope: source coverage/diversity/distribution measurement, not final answer generation quality evaluation.
- Story 7 primary Tier A concept top-k gate is **FAIL** and remains documented in `fastapi/data/rag/reports/rag-enhancement-benchmark.md`: ILD is the only statistically significant diversity improvement, Recall@k decreases from 0.1374 to 0.1099, and worsened query ratio is 0.1758.
- Evidence bundle distribution improved strongly: distinct source 4.2967 → 7.4505, catalog coverage 0.3736 → 0.8352, source HHI 0.2732 → 0.1608, same-source neighbor ratio 1.0000 → 0.5000.
- Real embedding runs and graded relevance labels remain follow-up work, not blockers for this measurement/reporting closure.
