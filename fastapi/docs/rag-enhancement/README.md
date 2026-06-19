# RAG 검색 다양성 고도화

현재 RAG가 특정 문서에 쏠려 검색되는 문제를 해소하고, 리포트 근거·추천 퀴즈가 더 많고 다채로운 문서에서 나오도록 FastAPI 내부 검색 계층을 고도화하는 계획 폴더다.

§4 계약과 두 서버 간 출력 shape는 불변이며, 모든 작업은 `fastapi/` 하위에서만 이뤄진다.

## 문서

- [`rag-enhancement-epic.md`](./rag-enhancement-epic.md) — 목적, 쏠림 원인 분석, 원칙, Story 목록, 완료 기준
- [`rag-enhancement-evaluation-methodology.md`](./rag-enhancement-evaluation-methodology.md) — **개선을 정형 증명하는 평가 프레임** (골든셋·IR 지표·다양성 지표·ablation·통계·합격 게이트·포트폴리오 리포트)
- [`rag-enhancement-sprint-status.yaml`](./rag-enhancement-sprint-status.yaml) — Story 상태 트래킹
- `stories/` — Story 1~7 상세

## 범위 요약

평가를 **측정 → 개선 → 검증**으로 감싼다.

| Tier | 내용 | Story |
| --- | --- | --- |
| 측정 | 다양성 평가 하니스 & 베이스라인 | 1 |
| 1 | 개념 검색 MMR + per-source 상한 | 2 |
| 1 | source-neighborhood 다양성 재균형 | 3 |
| 2 | 필드/주제 멀티쿼리 + 쿼터 병합 | 4 |
| 3 | 문제 뱅크 임베딩 신규 빌드 | 5 |
| 3 | 문제 뱅크 하이브리드 검색 & 추천 다양화 | 6 |
| 측정 | 최종 벤치마크 · ablation · 포트폴리오 리포트 | 7 |

개선 효과는 방법론 문서(spec) + Story 1(baseline) + Story 7(ablation·통계·리포트)로 정형 증명한다.

후속(이 폴더 범위 밖): Tier 4 필드 힌트 재균형 + 개념 임베딩 재빌드.
