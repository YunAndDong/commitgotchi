---
title: RAG 고도화 평가 방법론 (정형화 & 포트폴리오)
status: backlog
created: 2026-06-17
owner: FastAPI AI 서버
epic: rag-enhancement
related_docs:
  - ./rag-enhancement-epic.md
  - ./stories/rag-enhancement-1-diversity-eval-baseline.md
  - ./stories/rag-enhancement-7-benchmark-ablation-report.md
---

# RAG 고도화 평가 방법론

이 문서는 "RAG를 고도화했다"는 주장을 **재현 가능한 수치로 증명**하기 위한 평가 프레임을 정의한다. 목적은 두 가지다.

1. **회귀 가드**: 다양성을 올리는 과정에서 관련성이 깨지지 않았음을 자동으로 검증한다.
2. **포트폴리오 근거**: "기존 RAG의 쏠림 한계 → 무엇을 바꿔서 → 어떤 지표가 얼마나 개선됐는지"를 before/after 표와 case study로 제시한다.

> 이 문서는 *무엇을 어떻게 측정하는가(spec)*를 정의한다. 측정 코드와 베이스라인은 [Story 1](./stories/rag-enhancement-1-diversity-eval-baseline.md), 전체 ablation과 최종 리포트는 [Story 7](./stories/rag-enhancement-7-benchmark-ablation-report.md)이 담당한다.

---

## 1. 평가 대상과 가설

| 검색 경로 | 코드 | 개선 Story | 핵심 가설 |
| --- | --- | --- | --- |
| 개념 검색 | `concept_search.py` | 2, 4 | 같은 문서 도배를 줄이고 검색이 닿는 문서·필드 폭을 넓혀도 관련성은 유지된다 |
| 이웃 확장 | `source_neighborhood.py` | 3 | evidence가 씨앗 문서 한 개로 굳지 않고 타 문서 비중이 는다 |
| 문제 뱅크 추천 | `problem_bank_search.py`, `quiz_recommender.py` | 5, 6 | 키워드가 못 잡던 의미적 매치를 회수해 추천 다양성이 는다 |

**귀무가설(H0)**: 고도화 전후 다양성 지표에 유의미한 차이가 없다.
**대립가설(H1)**: 고도화 후 다양성 지표가 개선되고, 관련성 지표는 허용 오차 `ε` 이내로 유지된다.

---

## 2. 평가셋 구성 (3-tier)

이 프로젝트의 핵심 주장은 "**문서 다양성**(특정 문서만 반복되지 않고 많은 문서를 골고루)"이다. 따라서 평가셋도 문서 커버리지를 중심에 두고 3개 tier로 나눈다. 각 tier는 역할이 달라 상호 대체 관계가 아니다.

| Tier | 구성 | 규모 | 라벨 | 역할 (주 지표) |
| --- | --- | --- | --- | --- |
| **A. source coverage golden set** | **문서당 1질의**(distinct source 91개 각각 앵커) | **91** | 수작업 | **모든 문서가 검색에 닿나** — 문서 도달 회귀 가드. 주 지표 `Recall@k`·`Hit@k`·`MRR`·source coverage |
| **C. 비라벨 다양성 스윕** | source heading 자동 생성 | 150~300 | 자동(불필요) | **실제 질의 분포에서 다양성이 무너지지 않나** — `Distinct-Source`·`HHI`·`Catalog Coverage` 운영 감시 |
| **B. 자연질의 라벨셋** (선택·후속) | 현실적 사용자 질의, 필드/시나리오 균형 | 30~50 | 수작업 | **순위 품질** — `nDCG`·`Recall` 보강. A의 Recall/MRR로 1차 가드는 충족되므로 여력 시 추가 |

- **A는 필수**(수작업), **C는 사실상 필수지만 자동 생성이라 라벨링 부담 0**(Story 1 하니스에 포함). **B는 선택**(없어도 설계 성립, 포트폴리오 신뢰도 보강용).
- A는 "전체 평가셋"이 아니라 **문서 도달 점검용 셋**이다. A로 nDCG(순위 품질)까지 전부 증명하려 하지 말고, 그 부분은 B로 보강한다.
- A의 라벨 작성 표준은 [labeling/label-set-spec.md](./labeling/label-set-spec.md)를 SSOT로 따른다(문서당 1질의, source tier 태그, self-retrieval 방지 규칙 포함).

**공통 record 스키마** (위치: 1차 산출 `fastapi/docs/rag-enhancement/labeling/queries.*.jsonl` → 머지 후 `fastapi/tests/fixtures/rag/eval/queries.jsonl`):

```json
{
  "queryId": "q-db-tx-01",
  "reportText": "트랜잭션 격리수준과 MVCC를 정리했다. 팬텀 리드가 왜 생기는지...",
  "expectedFields": ["db"],
  "expectedTopics": ["트랜잭션", "격리수준", "MVCC"],
  "relevantSourcePaths": [
    "01-computer-science-fundamentals/.../qna-database.md"
  ],
  "relevanceGrades": {
    "concept:sha256:...": 3,
    "concept:sha256:...": 1
  }
}
```

- `relevantSourcePaths` / `relevanceGrades`: 어떤 chunk/문제가 관련 있는지 사람이 라벨링. `relevanceGrades`는 nDCG용 등급(0=무관, 1=약함, 2=관련, 3=핵심).
- 1인 작업이라 inter-annotator 합의는 불가하므로, **라벨 기준 rubric**(무엇을 핵심/약함으로 볼지)을 fixture 상단 주석에 명시해 재현성을 확보한다.
- 라벨링 부담을 줄이려면 1차로 현재 검색 top-k를 풀로 뽑아 사람이 등급만 매기는 pooling 방식을 쓴다(표준 TREC 관행). 단 baseline에 유리하게 편향되지 않도록, 고도화본 결과도 풀에 합쳐 라벨링한다.

---

## 3. 지표 정의

### 3.1 관련성 (회귀 가드 — 떨어지면 안 됨)

| 지표 | 정의 | 용도 |
| --- | --- | --- |
| **Recall@k** | top-k 안에 든 관련 항목 / 전체 관련 항목 | 핵심 문서를 놓치지 않는지 |
| **Precision@k** | top-k 중 관련 항목 비율 | 잡음이 늘지 않았는지 |
| **MRR** | 첫 관련 항목 순위의 역수 평균 | 핵심을 위에 두는지 |
| **nDCG@k** | 등급가중 누적이득 / 이상정렬 | 등급 기반 종합 품질 |
| **FieldHit@k** | 기대 필드가 top-k 결과 필드에 포함되는 비율 | 주제 정합 |

### 3.2 다양성 (개선 목표 — 올라가야 함)

| 지표 | 정의 | 무엇을 잡나 |
| --- | --- | --- |
| **Distinct-Source@k** | top-k 결과의 distinct sourcePath 수 (질의 평균) | 같은 문서 도배 |
| **ILD (Intra-List Diversity)** | top-k 결과 임베딩 간 평균 `1 − cosine` | 의미적 "다채로움" (임베딩 직접 활용) |
| **Source HHI / Gini** | 결과 source 빈도의 집중도 | 쏠림 정도 (낮을수록 분산) |
| **Field Coverage** | 결과에 등장한 distinct fieldHints 수 | 소외 필드 대표성 |
| **Folder Coverage** | distinct 상위 폴더 수 | 카테고리 폭 |
| **Catalog Coverage@전체질의** | 전 질의에서 적어도 한 번 검색된 distinct source 수 / 전체 91 source | 인덱스 활용률 |
| **Same-Source Neighbor Ratio** | neighborhood 중 씨앗과 같은 source 비중 | 이웃 확장 편향 (Story 3) |

### 3.3 관련성·다양성 결합 (선택, 고급)

- **α-nDCG@k**: 이미 본 주제의 중복 이득을 `α`로 감가해 "관련하면서도 새로운" 결과를 보상. 다양성과 관련성을 한 숫자로 보고 싶을 때.

---

## 4. 실험 프로토콜

### 4.1 통제 변수 (고정)

- 동일 catalog 스냅샷(`chunks.jsonl` 청크 수·해시 기록), 동일 embedding model/dimension(`gemini-embedding-2` / 768).
- 동일 골든셋, 동일 `k`(예: k=5, 이웃 포함 번들은 별도 표기).
- 결정성 보장(seed 불필요한 deterministic 정렬). query embedding은 캐싱해 재실행 시 동일.
- 측정 시각·코드 커밋 해시를 리포트에 기록.

### 4.2 Ablation (각 Story 기여 분리)

누적 방식으로 각 단계의 순기여를 분리한다.

| 구성 | 설명 |
| --- | --- |
| **B0 Baseline** | 현재 코드 (고도화 전) |
| **+S2** | B0 + 개념 MMR·per-source 상한 |
| **+S3** | +S2 + 이웃 재균형 |
| **+S4** | +S3 + 멀티쿼리 병합 |
| **+S5/6** | +S4 + 문제 뱅크 하이브리드 |

- 각 구성은 동일 골든셋에 대해 전 지표를 산출한다.
- Story 2/3/4는 개념·이웃 지표, Story 5/6은 문제 추천 지표가 주 평가 대상(경로가 다르므로 표를 분리).

### 4.3 통계 비교

- 질의별로 지표를 산출해 **paired delta**(고도화 − baseline)를 모은다.
- 표본이 작고 정규성을 가정하기 어려우므로 **Wilcoxon signed-rank test**(또는 paired bootstrap 95% CI)로 유의성을 본다. p값/CI를 리포트에 표기.
- 평균만이 아니라 분포(평균 ± 표준편차, 악화된 질의 수)를 함께 보고한다.

### 4.4 합격 기준 (정형 게이트)

고도화가 "성공"으로 인정되려면:

1. 다양성 지표(Distinct-Source@k, ILD, Field Coverage 중 최소 2개)가 통계적으로 유의하게 개선.
2. 관련성 지표(Recall@k, nDCG@k)가 baseline 대비 `ε`(예: 절대 2%p) 이상 하락하지 않음.
3. 악화된 질의가 전체의 일정 비율(예: 10%) 이하.

이 기준은 Story 7에서 자동 판정하고 통과/실패를 리포트에 남긴다.

---

## 5. 포트폴리오 산출물

Story 7이 생성하는 최종 리포트(`fastapi/data/rag/reports/rag-enhancement-benchmark.md`)는 다음을 포함한다.

1. **문제 정의**: 기존 RAG의 쏠림 한계(원인 4가지, epic §2 인용) + 실제 baseline 수치.
2. **개선 내용**: Story 2~6 한 줄 요약과 각 기법(MMR·per-source cap·이웃 재균형·멀티쿼리·하이브리드)이 어떤 한계를 겨냥했는지.
3. **Before/After 표**: 전 지표 baseline vs 최종, delta, p값/CI.
4. **Ablation 표**: 각 Story의 순기여.
5. **Case Study 2~3건**: 같은 질의에 대해 baseline과 고도화본이 **실제로 어떤 문서를 검색했는지** 나란히 제시(가장 설득력 있는 증거). 예: "B0는 html-css 한 파일에서 4/5 반환 → 고도화본은 4개 파일·3개 필드로 분산, 핵심 문서는 여전히 1위".
6. **한계와 다음 단계**: Tier 4(필드 힌트 재균형), pgvector 이전, 골든셋 확대 등.

---

## 6. 측정의 한계 (정직하게 명시)

- 골든셋이 작고 1인 라벨링이라 절대 수치보다 **상대 비교(전후 delta)**에 무게를 둔다.
- pooling 라벨링은 풀에 없는 관련 문서를 놓칠 수 있다(Recall 상한 편향). 그래서 baseline·고도화본 결과를 모두 풀에 넣는다.
- ILD/임베딩 기반 지표는 임베딩 공간 품질에 의존한다. model/dimension을 고정해 비교 일관성을 확보한다.
- 다양성은 그 자체가 목적이 아니라 "관련성 유지 하에서의 다양성"이다. 합격 기준이 두 축을 함께 강제한다.
