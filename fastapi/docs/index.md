---
title: FastAPI AI 서버 문서 인덱스
status: active
created: 2026-06-13
owner: FastAPI AI 서버
---

# FastAPI AI 서버 문서 인덱스

이 폴더는 FastAPI AI 서버의 문제 은행, 퀴즈 추천, 퀴즈 채점, RAG 관련 문서를 관리한다.

## 1. 현재 구현 기준 문서

### 1.1 문제 은행 구현 현황

- 문서: `fastapi/docs/quiz-bank/problem-bank-implementation.md`
- 상태: implemented
- 내용:
  - 정적 문제 은행 생성 CLI
  - `problems.jsonl` 산출물
  - 문제 은행 런타임 loader/search
  - 일일 리포트 기반 `recommendedQuizzes[]` 내부 서비스 함수
- 현재 FastAPI 문제 추천 구현을 이해할 때 가장 먼저 읽는다.

### 1.2 퀴즈 채점 서비스 함수 계획

- 문서: `fastapi/docs/quiz-bank/quiz-grading-service-plan.md`
- 상태: planned
- 내용:
  - Gemini 기반 퀴즈 답안 채점 내부 서비스 함수 설계
  - 입력/출력 계약
  - 점수 clamp 정책
  - fallback 정책
  - API/Spring 연동 제외 범위
- 다음 구현 story를 시작할 때 읽는다.

## 2. 기존 계획 문서

### 2.1 문제 은행 Epic

- 문서: `fastapi/docs/quiz-bank/problem-bank-epic.md`
- 상태: draft / partially implemented
- 내용:
  - 문제 은행 생성 epic
  - 5개 story 단위 설계
  - RAG/pgvector/API 제외 범위
- 구현 완료 후에는 `quiz-bank/problem-bank-implementation.md`를 기준 문서로 본다.

### 2.2 리포트·퀴즈·정량화 구현 계획

- 문서: `fastapi/docs/report/report-quiz-scoring-plan.md`
- 상태: draft
- 내용:
  - 리포트 점수화
  - 퀴즈 추천
  - 퀴즈 채점
  - 5개 능력치 정량화 정책
- 퀴즈 채점 함수 구현 시 점수 정책과 clamp 기준을 확인한다.

### 2.3 RAG 구축 계획

- 문서: `fastapi/docs/rag-implementation-plan.md`
- 상태: draft
- 내용:
  - 문제 은행과 개념 청크 분리
  - RAG/검색 확장 방향
  - 향후 embedding, vector store, retriever 계획
- 현재 구현은 아직 embedding/pgvector까지 가지 않았다.

## 3. 권장 읽는 순서

현재 상태 파악:

1. `fastapi/docs/quiz-bank/problem-bank-implementation.md`
2. `fastapi/docs/index.md`

다음 퀴즈 채점 작업:

1. `fastapi/docs/quiz-bank/problem-bank-implementation.md`
2. `fastapi/docs/quiz-bank/quiz-grading-service-plan.md`
3. 필요 시 `fastapi/docs/report/report-quiz-scoring-plan.md`

다음 리포트 epic 작성:

1. `fastapi/docs/report/README.md`
2. `fastapi/docs/report/report-quiz-scoring-plan.md`
3. 필요 시 `fastapi/docs/quiz-bank/problem-bank-implementation.md`

향후 RAG/embedding 작업:

1. `fastapi/docs/quiz-bank/problem-bank-implementation.md`
2. `fastapi/docs/rag-implementation-plan.md`

## 4. 현재 구현 완료 범위

완료:

- 문제 은행 생성
- 문제 은행 파일 저장
- 문제 은행 런타임 로드/search
- 일일 리포트 기반 추천 퀴즈 내부 함수
- 추천 결과 스냅샷 생성

미완료:

- 퀴즈 답안 채점 함수
- FastAPI API 엔드포인트
- Spring Boot 콜백/SQS 연결
- LLM 기반 리포트 분석
- RAG embedding
- pgvector 저장
- 벡터 검색
