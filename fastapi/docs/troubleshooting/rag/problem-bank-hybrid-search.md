# Problem Bank Hybrid Search Troubleshooting

## 증상

문제 뱅크 하이브리드 검색에서 vector similarity가 아주 작은 양수여도 vector-only 후보가 union 후보에 들어갈 수 있었다. 그 결과 `"오늘 점심으로 김치찌개를 먹었다"` 같은 무관한 리포트에도 클라우드, Git, Docker 같은 CS 문제가 추천될 수 있었다.

## 원인

- vector lane에 최소 유사도 threshold가 없어서 `similarity > 0`이면 keyword score가 낮거나 0인 문제도 후보에 포함됐다.
- 일부 problem embedding만 dimension mismatch 또는 zero norm인 경우, 해당 벡터는 실패해도 다른 벡터가 성공하면 hybrid ranking을 계속 진행했다.
- 이 때문에 Story 6의 keyword-only fallback 동일성, 즉 score/order/matched_terms 보존이 일부 invalid embedding 상황에서 완전히 보장되지 않았다.

## 해결

- vector-only 후보에는 `DEFAULT_VECTOR_SIMILARITY_THRESHOLD` 이상의 similarity가 있을 때만 union 후보에 포함되도록 했다.
- keyword 후보는 기존 `min_score` 기준을 그대로 유지한다.
- `ProblemEmbeddingStore.embedding_for(problem_id, source_key)`가 embedding을 반환했는데 cosine 계산이 `None`이면 dimension mismatch 또는 zero norm으로 보고 keyword-only fallback을 반환한다.
- embedding이 아예 없는 문제는 stale/missing sidecar 대응 범위로 보고 crash 없이 건너뛴다.

## 재발 방지

- hybrid enabled 상태에서도 무관 입력이 `[]`를 반환하는 recommender 회귀 테스트를 추가했다.
- 일부 embedding만 dimension mismatch인 경우 keyword-only baseline과 동일한지 검증하는 테스트를 추가했다.
- 일부 embedding만 zero norm인 경우 keyword-only baseline과 동일한지 검증하는 테스트를 추가했다.
- threshold 미만 vector-only 후보가 추가되지 않는지 검증하는 테스트를 추가했다.
