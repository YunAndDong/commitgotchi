---
title: Quiz Bank 문서
status: active
created: 2026-06-13
owner: FastAPI AI 서버
---

# Quiz Bank 문서

이 폴더는 FastAPI의 문제 은행, 퀴즈 추천, 퀴즈 채점 서비스 함수와 관련된 문서를 둔다.

## 문서 목록

- `problem-bank-implementation.md`
  - 현재 구현 기준 문서
  - 문제 은행 생성, 런타임 로드/search, 리포트 기반 추천 퀴즈 내부 함수 정리
- `problem-bank-epic.md`
  - 문제 은행 생성 epic/story 설계
  - 구현 완료 후에는 참고 문서로 본다.
- `quiz-grading-service-plan.md`
  - Gemini 기반 퀴즈 채점 내부 서비스 함수 계획
  - API/Spring 연동 없이 service layer 함수만 구현하는 기준

## 현재 완료 범위

- 문제 은행 생성 CLI
- `fastapi/data/rag/catalog/problems.jsonl` 생성
- 문제 은행 런타임 loader/search
- 일일 리포트 기반 `recommendedQuizzes[]` 내부 생성
- Gemini 기반 퀴즈 채점 서비스 함수 계획

## 다음 연결 지점

퀴즈 관련 서비스 함수가 모두 준비되면 리포트 오케스트레이터 또는 API/SQS consumer에서 다음 순서로 호출한다.

```text
리포트 분석
  -> 퀴즈 추천 함수
  -> Spring Boot 리포트 콜백 payload

퀴즈 답안 제출
  -> 퀴즈 채점 함수
  -> Spring Boot 채점 결과 webhook payload
```
