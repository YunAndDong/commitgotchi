---
title: Report 문서
status: planned
created: 2026-06-13
owner: FastAPI AI 서버
---

# Report 문서

이 폴더는 FastAPI의 일일 리포트 분석, 리포트 점수화, 다음 학습 추천, 리포트 결과 payload 관련 문서를 둔다.

## 현재 상태

아직 리포트 epic은 작성 전이다. 다음 컨텍스트에서 이 폴더 아래에 리포트 작성/분석 epic 문서를 추가한다.

## 참고 문서

- `report-quiz-scoring-plan.md`
  - 리포트 점수화와 퀴즈 채점의 공통 점수 정책 초안
  - 리포트 분석의 `scoreDelta`, feedback, next recommendation 기준을 볼 때 참고한다.
- `../quiz-bank/problem-bank-implementation.md`
  - 리포트 기반 추천 퀴즈 함수가 이미 구현된 상태를 확인할 때 참고한다.
- `../rag-implementation-plan.md`
  - 향후 리포트 RAG/개념 청크 확장을 설계할 때 참고한다.

## 다음 epic 후보

권장 문서 위치:

```text
fastapi/docs/report/report-generation-epic.md
```

권장 범위:

- Gemini 기반 리포트 분석 서비스 함수
- 리포트 `scoreDelta` 산정과 clamp
- `nextRecommendation` 생성
- 기존 퀴즈 추천 함수 연결
- API/SQS/Spring Boot 콜백은 후속 story로 분리
