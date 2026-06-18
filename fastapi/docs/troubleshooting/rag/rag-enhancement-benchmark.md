# RAG Enhancement Benchmark Troubleshooting

## 증상/질문

Story 7 benchmark 숫자는 나오지만, 이것이 "실제 답변 품질 개선"까지 뜻하는지 혼동될 수 있다.

## 원인

이번 benchmark는 A안으로 진행했다. 즉, RAG가 최종 답변 문장을 얼마나 잘 생성하는지 평가하지 않고, 검색 단계에서 어떤 문서/source를 얼마나 골고루, 안정적으로, 적절히 끌어오는지 측정한다.

현재 baseline/enhanced 모두 fake-hash embedding 기반이다.

- `embeddingMode`: `fake`
- `callsGemini`: `false`
- dimension: `32`

또한 Tier A source-coverage golden set의 `relevanceGrades`가 비어 있다. 따라서 Recall/MRR/nDCG는 실제 graded relevance가 아니라 `relevantSourcePaths` 앵커 기반 binary relevance로 계산된다.

## 이번에 시도한 것

`scripts/rag_benchmark.py`로 Story 2~6 개선을 ablation 형태로 비교했다.

- B0: MMR/source cap 없음, multi-query 없음, problem bank keyword-only
- +S2: concept MMR + per-source cap
- +S3: source-neighborhood rebalance
- +S4: field/topic multi-query merge
- +S5/6: problem-bank fake embedding store/client 기반 hybrid search

측정 지표는 source coverage, distinct source, ILD, source HHI/Gini, field/folder/catalog coverage, same-source neighbor ratio와 Recall@k, Precision@k, MRR, binary nDCG@k, FieldHit@k다. 통계는 paired delta와 bootstrap 95% CI로 계산했다.

## 개선 결과

Primary top-k gate는 FAIL이다.

- Concept top-k distinct source: 4.2967 → 4.4505
- Concept top-k ILD: 0.2882 → 0.3563
- Concept top-k source HHI: 0.2642 → 0.2440
- Concept top-k Recall@k: 0.1374 → 0.1099
- Concept top-k nDCG@k: 0.0935 → 0.0778
- Gate failure: significant diversity metric은 ILD 1개뿐이고, Recall@k 하락폭이 epsilon 0.02를 넘었으며, worsened query ratio가 0.1758로 기준 0.10을 넘었다.

반면 evidence bundle 기준 분포 개선은 크다.

- Evidence bundle distinct source: 4.2967 → 7.4505
- Evidence bundle catalog coverage: 0.3736 → 0.8352
- Evidence bundle source HHI: 0.2732 → 0.1608
- Evidence bundle same-source neighbor ratio: 1.0000 → 0.5000
- Evidence bundle Recall@k: 0.1374 → 0.4084

Problem bank top-k는 distinct source 2.4176 → 2.4615, MRR 0.0824 → 0.0989로 소폭 개선됐지만, catalog coverage는 0.6351 → 0.6081로 낮아졌다.

## 해석

방어 가능한 결론은 "검색 결과 분포와 evidence bundle의 source 다양성은 개선됐다"이다. 특히 Story 3의 neighborhood rebalance는 같은 source 이웃 쏠림을 줄이고 bundle coverage를 크게 넓혔다.

하지만 이 benchmark만으로 실제 의미 검색 정확도나 답변 생성 품질이 개선됐다고 주장하면 안 된다. fake-hash embedding은 실제 semantic embedding이 아니고, `relevanceGrades`가 비어 있어 graded nDCG를 계산하지 못한다. 후속 작업은 real embedding 캐시 런과 graded relevance 라벨링을 포함해야 한다.

## 재현 방법

```bash
cd fastapi
python3 scripts/rag_benchmark.py
```

산출물:

- `data/rag/reports/rag-enhancement-benchmark.md`
- `data/rag/reports/rag-enhancement-benchmark.json`

관련 테스트:

```bash
cd fastapi
python3 -m unittest tests.rag.test_eval_metrics
python3 -m unittest tests.rag.test_rag_benchmark
python3 -m unittest tests.rag.test_diversity_eval
python3 -m unittest tests.rag.test_problem_bank_search
python3 -m unittest tests.rag.test_quiz_recommender
python3 -m unittest tests.scoring.test_daily_report_service
```
