# RAG Enhancement Benchmark

## Scope

이 benchmark는 검색 결과의 문서 커버리지/다양성/분포 개선 측정이며, 답변 생성 품질 평가는 아니다.
LLM generation, answer quality, Gemini answer generation 비교는 포함하지 않는다.

## Metadata

- Generated at: `2026-06-18T16:28:02.784195+00:00`
- Git commit: `be8f4e6`
- Embedding mode: `fake` (`callsGemini=False`)
- Catalog: 870 chunks / 91 sources
- Problem bank: 798 problems / 74 sources
- topK: 5, problemTopK: 3
- Output JSON: `data/rag/reports/rag-enhancement-benchmark.json`
- Output Markdown: `data/rag/reports/rag-enhancement-benchmark.md`

## Pass/Fail Gate

- Result: **FAIL**
- Thresholds: relevance epsilon `0.02`, max worsened query ratio `0.1`
- significant diversity improvements 1/2 required: avgILD
- relevance maintained within epsilon=0.02 1/2 required: ndcgAtK
- max worsened query ratio=0.1758
- Failures: significant diversity improvements 1/2 required: avgILD; relevance maintained within epsilon=0.02 1/2 required: ndcgAtK; max worsened query ratio=0.1758

## Headline Metrics

| Metric | B0 | Final | Delta | 95% CI |
| --- | ---: | ---: | ---: | --- |
| Distinct source | 4.2967 | 4.4505 | 0.1538 | [0.0000, 0.3077] |
| ILD | 0.2882 | 0.3563 | 0.0681 | [0.0605, 0.0777] |
| Source HHI | 0.2642 | 0.2440 | -0.0202 | [-0.0352, -0.0048] |
| Field coverage | 3.0989 | 3.0110 | -0.0879 | [-0.3077, 0.1429] |
| Recall@k | 0.1374 | 0.1099 | -0.0275 | [-0.0769, 0.0275] |
| nDCG@k | 0.0935 | 0.0778 | -0.0157 | [-0.0489, 0.0184] |

## Ablation

### Tier A Concept Top-K

| Rung | Distinct | ILD | HHI | Gini | Field | Folder | Catalog | Recall | Precision | MRR | nDCG | FieldHit |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| B0 | 4.2967 | 0.2882 | 0.2642 | 0.9564 | 3.0989 | 3.5165 | 0.3736 | 0.1374 | 0.0527 | 0.1060 | 0.0935 | 0.7692 |
| +S2 | 4.5604 | 0.3521 | 0.2352 | 0.9523 | 3.1099 | 3.6813 | 0.4725 | 0.1813 | 0.0681 | 0.1559 | 0.1294 | 0.7802 |
| +S3 | 4.5604 | 0.3521 | 0.2352 | 0.9523 | 3.1099 | 3.6813 | 0.4725 | 0.1813 | 0.0681 | 0.1559 | 0.1294 | 0.7802 |
| +S4 | 4.4505 | 0.3563 | 0.2440 | 0.9543 | 3.0110 | 3.7473 | 0.3846 | 0.1099 | 0.0418 | 0.0954 | 0.0778 | 0.7143 |
| +S5/6 | 4.4505 | 0.3563 | 0.2440 | 0.9543 | 3.0110 | 3.7473 | 0.3846 | 0.1099 | 0.0418 | 0.0954 | 0.0778 | 0.7143 |

### Tier A Evidence Bundle

| Rung | Distinct | ILD | HHI | Gini | Field | Folder | Catalog | Recall | Precision | MRR | nDCG | FieldHit |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| B0 | 4.2967 | 0.4059 | 0.2732 | 0.9597 | 3.8901 | 3.5165 | 0.3736 | 0.1374 | 0.0203 | 0.1060 | 0.0935 | 0.8132 |
| +S2 | 4.5604 | 0.4250 | 0.2433 | 0.9561 | 3.9560 | 3.6813 | 0.4725 | 0.1813 | 0.0262 | 0.1559 | 0.1294 | 0.8352 |
| +S3 | 7.2088 | 0.4674 | 0.1647 | 0.9358 | 3.9560 | 3.9670 | 0.8462 | 0.4780 | 0.0676 | 0.1846 | 0.2386 | 0.8352 |
| +S4 | 7.4505 | 0.4771 | 0.1608 | 0.9341 | 3.7912 | 4.1319 | 0.8352 | 0.4084 | 0.0566 | 0.1258 | 0.1838 | 0.7802 |
| +S5/6 | 7.4505 | 0.4771 | 0.1608 | 0.9341 | 3.7912 | 4.1319 | 0.8352 | 0.4084 | 0.0566 | 0.1258 | 0.1838 | 0.7802 |

### Tier A Problem Bank Top-K

| Rung | Distinct | ILD | HHI | Gini | Field | Folder | Catalog | Recall | Precision | MRR | nDCG | FieldHit |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| B0 | 2.4176 | 0.1733 | 0.4872 | 0.9690 | 2.8681 | 1.2967 | 0.6351 | 0.0824 | 0.0549 | 0.0824 | 0.0634 | 0.8242 |
| +S2 | 2.4176 | 0.1733 | 0.4872 | 0.9690 | 2.8681 | 1.2967 | 0.6351 | 0.0824 | 0.0549 | 0.0824 | 0.0634 | 0.8242 |
| +S3 | 2.4176 | 0.1733 | 0.4872 | 0.9690 | 2.8681 | 1.2967 | 0.6351 | 0.0824 | 0.0549 | 0.0824 | 0.0634 | 0.8242 |
| +S4 | 2.4176 | 0.1733 | 0.4872 | 0.9690 | 2.8681 | 1.2967 | 0.6351 | 0.0824 | 0.0549 | 0.0824 | 0.0634 | 0.8242 |
| +S5/6 | 2.4615 | 0.1725 | 0.4750 | 0.9683 | 2.7582 | 1.2747 | 0.6081 | 0.0879 | 0.0586 | 0.0989 | 0.0727 | 0.8242 |

### Tier C Diversity Sweep

| Rung | Distinct | ILD | HHI | Gini | Field | Folder | Catalog |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| B0 | 4.1333 | 0.2830 | 0.2789 | 0.9587 | 3.2733 | 3.2467 | 0.4286 |
| +S2 | 4.4200 | 0.3267 | 0.2464 | 0.9545 | 3.3133 | 3.4400 | 0.4286 |
| +S3 | 4.4200 | 0.3267 | 0.2464 | 0.9545 | 3.3133 | 3.4400 | 0.4286 |
| +S4 | 4.4067 | 0.3351 | 0.2475 | 0.9549 | 3.1800 | 3.5467 | 0.4396 |
| +S5/6 | 4.4067 | 0.3351 | 0.2475 | 0.9549 | 3.1800 | 3.5467 | 0.4396 |

## Case Studies

### q-01-computer-science-fundamentals-operating-system-05-virtual-memory-md

- Query: 실제 물리 메모리보다 큰 프로그램이 어떻게 돌아가는지, 메모리가 부족할 때 어떤 데이터를 먼저 내보낼지 정하는 기준이 궁금하다.
- Distinct sources: B0 `3` → final `5`
- B0:
  - 1. `01-computer-science-fundamentals/operating-system/qna-os.md` — 운영체제 면접 질문 & 답변 > Q15. CPU 스케줄링 알고리즘을 설명해주세요. ⭐⭐⭐ > 꼬리 질문 대비
  - 2. `README.md` — CS 면접 대비 학습 자료 > 학습 로드맵 > 주니어 개발자 (2 4년)
  - 3. `01-computer-science-fundamentals/operating-system/qna-os.md` — 운영체제 면접 질문 & 답변 > Q17. 파일 시스템이란 무엇인가요? ⭐⭐ > 꼬리 질문 대비
  - 4. `03-frontend-engineering/react-architecture/qna-react.md` — React 면접 질문 & 답변 > Q5. useMemo와 useCallback의 차이점과 사용 시점은? ⭐⭐⭐ > 핵심 답변
  - 5. `README.md` — CS 면접 대비 학습 자료 > 학습 진행 체크리스트 > 11. Cloud Engineering (클라우드 엔지니어링)
- Final:
  - 1. `99-practical-interview/qna-frontend-companies.md` — 프론트엔드 실전 면접 질문 모음 > A기업 (비대면) > CS 질문
  - 2. `03-frontend-engineering/javascript-deep-dive/qna-javascript.md` — JavaScript 면접 질문 & 답변 > Q21. REST API란 무엇인가요? ⭐⭐ > 꼬리 질문 대비
  - 3. `02-backend-engineering/database/qna-database.md` — 데이터베이스 & JPA 면접 질문 & 답변 > Q16. SQL JOIN의 종류와 차이점을 설명해주세요. ⭐⭐⭐ > 면접관이 주목하는 포인트
  - 4. `01-computer-science-fundamentals/operating-system/qna-os.md` — 운영체제 면접 질문 & 답변 > Q15. CPU 스케줄링 알고리즘을 설명해주세요. ⭐⭐⭐ > 꼬리 질문 대비
  - 5. `09-software-engineering/qna-software-engineering.md` — 소프트웨어 공학 면접 질문 & 답변 > Q9. 테스트 코드를 작성해야 하는 이유는? ⭐⭐ > 꼬리 질문 대비

### q-03-frontend-engineering-readme-md

- Query: 프론트엔드 면접을 준비하는데 브라우저 동작, 자바스크립트, 리액트 같은 주제를 어떤 순서로 잡아야 할지 큰 그림이 필요하다.
- Distinct sources: B0 `3` → final `5`
- B0:
  - 1. `01-computer-science-fundamentals/network/qna-network.md` — 네트워크 면접 질문 & 답변 > Q5. REST API란 무엇인가요? ⭐⭐⭐ > 꼬리 질문 대비
  - 2. `03-frontend-engineering/javascript-deep-dive/qna-javascript.md` — JavaScript 면접 질문 & 답변 > Q4. 이벤트 루프(Event Loop)를 설명해주세요. ⭐⭐⭐ > 꼬리 질문 대비
  - 3. `03-frontend-engineering/javascript-deep-dive/qna-javascript.md` — JavaScript 면접 질문 & 답변 > Q9. JavaScript의 데이터 타입에 대해 설명해주세요. ⭐ > 꼬리 질문 대비
  - 4. `01-computer-science-fundamentals/operating-system/qna-os.md` — 운영체제 면접 질문 & 답변 > Q15. CPU 스케줄링 알고리즘을 설명해주세요. ⭐⭐⭐ > 꼬리 질문 대비
  - 5. `01-computer-science-fundamentals/network/qna-network.md` — 네트워크 면접 질문 & 답변 > Q13. SSE(Server Sent Events)란 무엇인가요? ⭐⭐ > 꼬리 질문 대비
- Final:
  - 1. `01-computer-science-fundamentals/network/qna-network.md` — 네트워크 면접 질문 & 답변 > Q5. REST API란 무엇인가요? ⭐⭐⭐ > 꼬리 질문 대비
  - 2. `01-computer-science-fundamentals/operating-system/qna-os.md` — 운영체제 면접 질문 & 답변 > Q15. CPU 스케줄링 알고리즘을 설명해주세요. ⭐⭐⭐ > 꼬리 질문 대비
  - 3. `03-frontend-engineering/javascript-deep-dive/qna-javascript.md` — JavaScript 면접 질문 & 답변 > Q9. JavaScript의 데이터 타입에 대해 설명해주세요. ⭐ > 꼬리 질문 대비
  - 4. `07-security/qna-security.md` — 보안 면접 질문 & 답변 > Q7. 해시 함수의 특징을 설명해주세요. ⭐⭐ > 해시 함수의 특성
  - 5. `03-frontend-engineering/browser-fundamentals/qna-browser.md` — 브라우저 면접 질문 & 답변 > Q8. CORS의 동작 원리를 설명해주세요. ⭐⭐⭐ > 꼬리 질문 대비

### q-05-ai-engineering-llm-integration-qna-llm-md

- Query: LLM에게 원하는 답을 잘 끌어내려면 지시를 어떻게 줘야 하는지, 출력의 무작위성을 조절하는 설정값들이 무엇을 의미하는지 헷갈린다.
- Distinct sources: B0 `3` → final `5`
- B0:
  - 1. `03-frontend-engineering/nextjs-rendering/qna-nextjs.md` — Next.js 면접 질문 & 답변 > Q2. 하이드레이션(Hydration)이란 무엇인가요? ⭐⭐⭐ > 하이드레이션 문제점
  - 2. `02-backend-engineering/spring-framework/qna-spring.md` — Spring Framework 면접 질문 & 답변 > Q2. Spring AOP의 동작 원리를 설명해주세요. ⭐⭐⭐ > 프록시 방식
  - 3. `02-backend-engineering/spring-framework/qna-spring.md` — Spring Framework 면접 질문 & 답변 > Q5. Spring과 Spring Boot의 차이점은 무엇인가요? ⭐⭐⭐ > 주요 차이점
  - 4. `05-ai-engineering/rag-pipeline/qna-rag.md` — RAG 면접 질문 & 답변 > Q4. 하이브리드 검색(Hybrid Search)이란? ⭐⭐⭐ > Dense vs Sparse Retrieval
  - 5. `02-backend-engineering/spring-framework/qna-spring.md` — Spring Framework 면접 질문 & 답변 > Q13. Spring에서 CORS 에러를 해결하는 방법은? ⭐⭐ > 선택 기준
- Final:
  - 1. `03-frontend-engineering/nextjs-rendering/qna-nextjs.md` — Next.js 면접 질문 & 답변 > Q2. 하이드레이션(Hydration)이란 무엇인가요? ⭐⭐⭐ > 하이드레이션 문제점
  - 2. `01-computer-science-fundamentals/computer-architecture/qna-computer-architecture.md` — 컴퓨터 구조 면접 질문 & 답변 > Q2. 캐시 메모리란 무엇이며, 어떻게 동작하나요? ⭐⭐⭐ > 캐시 미스(Cache Miss) 3종류
  - 3. `09-software-engineering/qna-software-engineering.md` — 소프트웨어 공학 면접 질문 & 답변 > Q6. KISS 원칙이란? ⭐⭐ > 관련 원칙들
  - 4. `01-computer-science-fundamentals/network/qna-network.md` — 네트워크 면접 질문 & 답변 > Q5. REST API란 무엇인가요? ⭐⭐⭐ > 꼬리 질문 대비
  - 5. `05-ai-engineering/rag-pipeline/qna-rag.md` — RAG 면접 질문 & 답변 > Q4. 하이브리드 검색(Hybrid Search)이란? ⭐⭐⭐ > Dense vs Sparse Retrieval


## Limitations

- Actual Gemini API calls are intentionally disabled; all benchmark runs use deterministic fake-hash embeddings.
- This report measures retrieval/source distribution, not final answer sentence generation quality.
- S3 is measured through public max_same_source_neighbors/min_cross_source arguments; the exact pre-story implementation is not resurrected.
- S4 is separated by max_subqueries=0 vs 3; field quota merge internals are not split into finer-grained toggles.
- Story 5 and 6 are measured together because embeddings and hybrid ranking form one exposed problem-bank toggle.
- 현재 source-coverage golden set의 `relevanceGrades`는 비어 있어 relevance 지표는 `relevantSourcePaths` 앵커 기반 binary relevance로 계산한다.
- nDCG는 binary relevance 기준이며 graded nDCG와 실제 의미 정확도 평가는 후속 real embedding + graded relevance 작업이 필요하다.
- fake-hash embedding은 결정적 분포 비교에는 유용하지만 실제 의미 검색 정확도나 답변 생성 품질 개선을 강하게 주장하는 근거는 아니다.
