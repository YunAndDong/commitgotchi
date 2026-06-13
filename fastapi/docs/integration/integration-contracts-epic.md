---
title: FastAPI Integration Contracts Epic
status: done
created: 2026-06-14
updated: 2026-06-14
owner: FastAPI AI 서버
scope: FastAPI/Spring Boot/SQS 통합 계약 계층, 내부 AI 함수 재구현 제외, 캐릭터 생성 제외
aliases:
  - FastAPI Integration Contracts
  - Server Integration
related_docs:
  - fastapi/docs/report/report-generation-epic.md
  - fastapi/docs/report/report-generation-sprint-status.yaml
  - fastapi/docs/report/stories/report-4-gemini-report-analysis-score-delta.md
  - fastapi/docs/report/stories/report-5-report-result-assembly-quiz-recommender.md
  - _bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md#4
---

# FastAPI Integration Contracts Epic

## 1. 목적

FastAPI 내부에 이미 구현된 report/quiz 함수들을 Spring Boot/FastAPI/SQS 통합 계약에 연결한다.

이 epic은 내부 AI 품질, RAG 검색, report 분석, quiz 채점 모델 자체를 다시 구현하지 않는다. 대신 다음 외부 통합 계층만 다룬다.

- Spring Boot callback client와 integration config
- FastAPI quiz grading 수락 endpoint
- Spring Boot quiz grade-result callback
- report request SQS consumer
- Spring Boot report callback payload wrapper

캐릭터 생성 흐름인 `POST /api/ai/commitgotchi`는 이번 epic에서 제외한다.

## 2. 현재 기준

이미 구현되어 있다고 보는 내부 함수와 payload 조립 계층:

- `fastapi/app/scoring/quiz_grader.py`의 `grade_quiz_answer()`
- `fastapi/app/scoring/report_analyzer.py`의 `analyze_daily_report()`
- `fastapi/app/scoring/daily_report_service.py`의 `generate_daily_report_result()`
- `fastapi/app/rag/quiz_recommender.py`의 `build_recommended_quizzes()` / `recommend_quizzes()`

이번 epic에서 구현 완료된 통합 계층:

- Spring Boot internal callback client
- Spring Boot base URL/path/internal auth/timeout env config
- `POST /api/internal/quizzes/grade` FastAPI endpoint
- quiz grade-result callback sender
- report request SQS consumer
- report callback wrapper와 sender
- SQS delete/retry/DLQ 처리

## 3. 핵심 원칙

- FastAPI는 Spring Boot DB에 직접 접근하지 않는다.
- Spring Boot가 System of Record다. FastAPI는 입력을 HTTP 요청 또는 SQS 메시지로 받고, 출력은 HTTP 응답 또는 Spring Boot Internal API callback으로만 전달한다.
- report callback의 `POST /api/report`는 Spring Boot가 받는 endpoint다. FastAPI에 만들지 않는다.
- FastAPI가 새로 만드는 endpoint는 quiz grading 요청 수락 endpoint인 `POST /api/internal/quizzes/grade`다.
- report 흐름은 SQS consumer가 시작점이다.
- callback URL과 Spring Boot base URL은 코드에 하드코딩하지 않고 env/config로 관리한다.
- SQS 설정은 이미 `.env`에 있다고 가정한다. 문서에는 env key만 적고 실제 secret 값은 쓰지 않는다.
- report SQS 메시지에는 quiz grading result, quiz submission, `gradings` 배열을 포함하지 않는다.
- `scoreDelta` 이중계상을 피한다. report callback의 `scoreDelta`는 report analysis 결과만, quiz grade-result callback의 `scoreDelta`는 quiz grading 결과만 반영한다.
- 모든 Spring callback sender 테스트는 mock/fake transport 기반으로 한다. unit test에서 실제 Spring Boot 서버를 호출하지 않는다.
- 캐릭터 생성 `POST /api/ai/commitgotchi`는 이번 epic의 일부가 아니다.

## 4. 통합 대상 계약

### 4.1 Spring Callback Config

권장 env key:

```dotenv
SPRING_BOOT_INTERNAL_BASE_URL=http://localhost:8080
SPRING_INTERNAL_API_SECRET=
SPRING_REPORT_CALLBACK_PATH=/api/report
SPRING_QUIZ_GRADE_RESULT_PATH=/api/internal/quizzes/grade-result
SPRING_CALLBACK_TIMEOUT_SECONDS=10
```

권장 원칙:

- `SPRING_BOOT_INTERNAL_BASE_URL`은 scheme/host/port만 담고 path를 포함하지 않는다.
- callback path는 개별 env/config로 분리한다.
- internal auth secret은 로그에 남기지 않는다.
- callback destination은 config에서 만든다. request body의 임의 URL을 그대로 호출하지 않는다.
- timeout 기본값은 10초다.

### 4.2 Quiz Grading HTTP Contract

FastAPI가 받는 endpoint:

```http
POST /api/internal/quizzes/grade
```

즉시 응답:

```json
{
  "accepted": true,
  "submissionId": "quiz-submission-uuid"
}
```

HTTP status는 `202 Accepted`다.

Spring Boot가 받는 callback:

```http
POST /api/internal/quizzes/grade-result
```

FastAPI는 내부에서 `grade_quiz_answer()`를 호출하고, 성공하면 `GRADED`, 실패하면 `UNGRADED`와 `scoreDelta=0`을 callback으로 전송한다.

### 4.3 Report SQS and Callback Contract

report 흐름 시작점은 SQS message다.

FastAPI는 SQS message를 validation한 뒤 `generate_daily_report_result()`를 호출한다. 그 결과에 `requestId`, `userId`, `characterId`, `targetDate` wrapper를 붙여 Spring Boot의 `POST /api/report`로 전송한다.

Spring Boot callback 응답 처리 원칙:

| Spring Boot 응답 | FastAPI 동작 |
|------------------|--------------|
| `200 OK` | SQS message 삭제 |
| `200 OK` + duplicate 의미 | SQS message 삭제 |
| `5xx` / timeout | 삭제하지 않음. SQS 재전달 |
| invalid schema / non-retryable poison message | DLQ 정책으로 격리. silent delete 금지 |

핵심 규칙은 Spring Boot가 200을 응답한 경우에만 SQS message를 삭제하는 것이다.

## 5. Story 목록

권장 구현 순서:

1. Spring Callback Client and Integration Config
2. Quiz Grading Endpoint and Webhook
3. Report SQS Consumer and Report Callback

---

## Story 1. Spring Callback Client and Integration Config

### 목표

Spring Boot internal callback 호출에 필요한 base URL, callback path, auth secret, timeout을 env/config로 관리하고, report/quiz callback sender가 공유할 client/helper를 설계한다.

### 구현 범위

- `SPRING_BOOT_INTERNAL_BASE_URL`
- `SPRING_INTERNAL_API_SECRET`
- `SPRING_REPORT_CALLBACK_PATH`
- `SPRING_QUIZ_GRADE_RESULT_PATH`
- `SPRING_CALLBACK_TIMEOUT_SECONDS`
- internal auth header 설계
- timeout 설정
- report callback payload wrapper helper 설계
- quiz grade-result callback sender helper 설계
- fake/mock transport 기반 테스트

### 제외 범위

- FastAPI endpoint 구현
- SQS consumer 구현
- 실제 Spring Boot 서버 호출 테스트
- report/quiz 내부 AI 함수 변경
- 캐릭터 생성 callback/client

---

## Story 2. Quiz Grading Endpoint and Webhook

### 목표

Spring Boot가 quiz submission을 저장한 뒤 FastAPI에 보낸 grading 요청을 `POST /api/internal/quizzes/grade`에서 `202 Accepted`로 수락하고, 내부 `grade_quiz_answer()` 결과를 Spring Boot grade-result callback으로 전송한다.

### 구현 범위

- FastAPI `POST /api/internal/quizzes/grade`
- request schema validation
- 즉시 `202 Accepted` 응답
- background 또는 비동기 작업으로 `grade_quiz_answer()` 호출
- 성공 callback `GRADED`
- 실패 callback `UNGRADED`, `scoreDelta=0`
- request body의 `callbackUrl` 검증/무시 정책 문서화

### 제외 범위

- 캐릭터 생성 endpoint
- Spring Boot DB 접근
- report SQS consumer
- report callback
- 새로운 quiz 생성

---

## Story 3. Report SQS Consumer and Report Callback

### 목표

`REPORT_REQUEST_QUEUE_URL` 등 기존 SQS env를 사용해 report request message를 소비하고, `generate_daily_report_result()` 결과를 Spring Boot `POST /api/report` callback으로 전달한다.

### 구현 범위

- SQS message receive loop 또는 단건 처리 함수
- report request message schema validation
- `generate_daily_report_result()` 호출
- `requestId`, `userId`, `characterId`, `targetDate` wrapper 조립
- Story 5 result payload 연결
- Spring Boot `POST /api/report` callback
- Spring callback 200일 때만 SQS delete
- 5xx/timeout은 delete하지 않고 재전달
- invalid schema/fallback/DLQ 처리 정책

### 제외 범위

- quiz grading endpoint
- quiz grading result, quiz submission, `gradings`를 report SQS message에 포함하는 것
- Spring Boot DB 접근
- 캐릭터 생성 흐름

## 6. Epic 완료 기준

- Spring Boot callback destination과 auth secret이 env/config로 관리된다.
- callback URL/base URL/path가 코드에 하드코딩되지 않는다.
- report callback wrapper helper가 Story 5 result payload에 `requestId`, `userId`, `characterId`, `targetDate`를 정확히 붙인다.
- quiz grade-result callback sender가 `grade_quiz_answer()` output을 Spring Boot contract에 맞게 보낸다.
- `POST /api/internal/quizzes/grade`는 request를 수락하면 `202 Accepted`를 반환한다.
- quiz grading 실패 시 Spring Boot에는 `UNGRADED`, `scoreDelta=0` callback이 전송된다.
- report SQS consumer는 valid message에 대해 `generate_daily_report_result()`를 호출한다.
- report callback이 Spring Boot `200 OK`를 반환한 경우에만 SQS message를 삭제한다.
- 5xx/timeout에서는 SQS message를 삭제하지 않는다.
- invalid schema와 poison message 처리 정책이 DLQ 관점에서 명확하다.
- FastAPI는 Spring Boot DB에 직접 접근하지 않는다.
- `POST /api/report` endpoint를 FastAPI에 만들지 않는다.
- 캐릭터 생성 `POST /api/ai/commitgotchi`는 이번 epic에서 다루지 않는다.
