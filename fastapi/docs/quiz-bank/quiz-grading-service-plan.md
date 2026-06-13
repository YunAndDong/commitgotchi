---
title: Gemini 기반 퀴즈 채점 서비스 함수 계획
status: planned
created: 2026-06-13
owner: FastAPI AI 서버
scope: 내부 서비스 함수만 구현
---

# Gemini 기반 퀴즈 채점 서비스 함수 계획

## 1. 목적

추천 퀴즈 생성 함수는 이미 FastAPI 내부 서비스 함수로 구현되어 있다. 다음 단계는 사용자가 제출한 퀴즈 답안을 채점하는 내부 서비스 함수 `grade_quiz_answer()`를 구현하는 것이다.

이번 작업은 API 엔드포인트, Spring Boot webhook, SQS 연결을 만들지 않는다. 나중에 오케스트레이터나 엔드포인트가 호출할 수 있는 순수 서비스 함수만 만든다.

## 2. 현재 위치

완료된 퀴즈 관련 내부 함수:

- `fastapi/app/rag/quiz_recommender.py`
  - 리포트 텍스트 또는 분석 결과에서 추천 퀴즈 후보를 생성한다.
- `fastapi/app/rag/daily_report_recommender.py`
  - 일일 리포트 텍스트를 분석하고 `recommendedQuizzes[]`를 만든다.

다음에 추가할 함수:

- `fastapi/app/rag/quiz_grader.py`
  - `question`, `modelAnswer`, `userAnswer`, `scoreAllocation`을 받아 `scoreDelta`, `feedback`, `confidence`, `status`를 반환한다.

## 3. 모델 선택

퀴즈 채점은 짧은 입력에 대해 구조화된 JSON을 안정적으로 반환하면 된다. 큰 추론 모델보다 작고 빠른 Gemini 모델을 우선 사용한다.

2026-06-13 기준 공식 Gemini API 모델 문서에서 `Gemini 3.1 Flash-Lite`는 Gemini 3 계열의 안정 모델로 표시되어 있고, `Gemini 2.5 Flash-Lite`는 2.5 계열에서 가장 빠르고 비용 효율적인 모델로 설명되어 있다.

권장 기본값:

```text
GEMINI_QUIZ_GRADER_MODEL=gemini-3.1-flash-lite
```

보수적 fallback 후보:

```text
GEMINI_QUIZ_GRADER_MODEL=gemini-2.5-flash-lite
```

구현 시 모델명은 코드에 고정하지 않고 환경변수로 둔다.

```text
GEMINI_API_KEY=...
GEMINI_QUIZ_GRADER_MODEL=gemini-3.1-flash-lite
```

## 4. 입력 계약

`grade_quiz_answer()` 입력:

```json
{
  "problemId": 123,
  "question": "N+1 문제란 무엇이며, 어떻게 해결하나요?",
  "modelAnswer": "N+1 문제는 ...",
  "userAnswer": "N+1은 지연 로딩 때문에 쿼리가 많이 나가는 문제이고 fetch join으로 줄일 수 있습니다.",
  "scoreAllocation": {
    "db": 10,
    "algorithm": 0,
    "cs": 0,
    "network": 0,
    "framework": 10
  }
}
```

필수 입력:

- `question`
- `modelAnswer`
- `userAnswer`
- `scoreAllocation`

선택 입력:

- `problemId`
- `rubric`
- `sourcePath`

## 5. 출력 계약

정상 채점:

```json
{
  "status": "GRADED",
  "scoreDelta": {
    "db": 7,
    "algorithm": 0,
    "cs": 0,
    "network": 0,
    "framework": 6
  },
  "feedback": "N+1 발생 원인과 fetch join 해결책은 언급했지만 BatchSize, EntityGraph, 페이징 한계 설명이 부족합니다.",
  "confidence": 0.82
}
```

채점 실패:

```json
{
  "status": "UNGRADED",
  "scoreDelta": {
    "db": 0,
    "algorithm": 0,
    "cs": 0,
    "network": 0,
    "framework": 0
  },
  "feedback": "채점을 완료하지 못했습니다.",
  "confidence": 0.0
}
```

## 6. 점수 정책

- `scoreDelta[field]`는 반드시 `0..scoreAllocation[field]` 범위 안에 있어야 한다.
- `scoreAllocation`은 5개 필드 `db`, `algorithm`, `cs`, `network`, `framework`를 모두 포함해야 한다.
- 총합 10점 cap은 없다.
- 필드별 독립 점수 정책을 유지한다.
- 관련 없는 필드는 0점이다.
- 빈 답변은 `GRADED` 상태로 처리하되 `scoreDelta`는 전부 0점으로 둔다.
- 모델 출력이 범위를 벗어나면 서버 코드에서 clamp한다.

## 7. 구현 구조

권장 파일:

- `fastapi/app/rag/quiz_grader.py`
- `fastapi/tests/rag/test_quiz_grader.py`

권장 public 함수:

```python
def grade_quiz_answer(
    *,
    question: str,
    model_answer: str,
    user_answer: str,
    score_allocation: dict[str, int],
    problem_id: int | None = None,
    rubric: dict[str, list[str]] | None = None,
) -> dict[str, Any]:
    ...
```

내부 구성:

- 입력 validation
- 빈 답변 fast path
- Gemini client 호출
- JSON 응답 parsing
- `scoreDelta` clamp
- 실패 시 `UNGRADED` fallback

## 8. Gemini 프롬프트 원칙

프롬프트는 짧고 구조화한다.

요구사항:

- 문제와 모범답안 기준으로만 채점한다.
- 사용자가 언급한 내용만 인정한다.
- `scoreAllocation`을 넘지 않는다.
- 총합 10점으로 재분배하지 않는다.
- JSON만 반환한다.

모델 출력 목표:

```json
{
  "scoreDelta": {
    "db": 7,
    "algorithm": 0,
    "cs": 0,
    "network": 0,
    "framework": 6
  },
  "feedback": "...",
  "confidence": 0.82
}
```

서버는 모델 출력을 신뢰하지 않고 한 번 더 검증한다.

## 9. Fallback

Gemini API 호출 실패, JSON parsing 실패, schema validation 실패 시:

- `status = "UNGRADED"`
- `scoreDelta`는 전 필드 0
- `confidence = 0.0`
- `feedback`은 짧은 실패 메시지

선택적으로 heuristic fallback을 둘 수 있지만, 첫 구현에서는 안전하게 `UNGRADED`로 두는 편이 낫다. 임의 점수 상승을 피하기 위해서다.

## 10. 테스트 기준

필수 테스트:

- 완전한 답변은 관련 필드에서 높은 점수를 받는다.
- 부분 답변은 `scoreAllocation`보다 낮은 부분 점수를 받는다.
- 빈 답변은 `GRADED`와 전 필드 0점을 반환한다.
- 모델 출력이 `scoreAllocation`을 넘으면 clamp된다.
- `scoreAllocation` 총합이 10을 넘어도 정상 동작한다.
- 잘못된 `scoreAllocation`은 안전하게 실패한다.
- Gemini client 실패 시 `UNGRADED`와 전 필드 0점을 반환한다.

테스트에서는 실제 Gemini API를 호출하지 않는다. fake client 또는 monkeypatch로 모델 응답을 주입한다.

## 11. 제외 범위

이번 story에서 하지 않는 것:

- FastAPI API 엔드포인트
- Spring Boot webhook 연결
- SQS consumer 연결
- 문제 추천 로직 수정
- RAG embedding
- pgvector 저장
- 벡터 검색
- 리포트 점수화 전체 파이프라인

## 12. 다음 연결 지점

퀴즈 채점 함수가 완료되면 이후 오케스트레이터 또는 엔드포인트에서 다음처럼 호출한다.

```text
Spring Boot quiz submission payload
  question/modelAnswer/userAnswer/scoreAllocation
        ↓
grade_quiz_answer()
        ↓
scoreDelta/feedback/confidence/status
        ↓
Spring Boot grade-result webhook
```
