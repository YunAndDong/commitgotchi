---
title: FastAPI 통합 구현 README
status: active
created: 2026-06-16
owner: FastAPI AI 서버
---

# FastAPI 통합 구현 README

이 문서는 `fastapi/docs/integration`의 계약대로 현재 구현된 FastAPI 통합 계층과, Spring Boot/AWS/Gemini 없이 FastAPI 안에서만 확인하는 방법을 정리한다.

캐릭터 이미지 생성은 아직 HTTP endpoint가 없다. 현재 이미지 관련 코드는 로컬 생성/저장 어댑터와 스프라이트 서비스 단위에 머문다.

## 구현된 통합 계약

### 1. Spring callback config/client

FastAPI는 Spring Boot DB에 직접 접근하지 않고, env/config로 만든 Spring Boot internal callback URL에만 결과를 보낸다.

주요 env:

```dotenv
SPRING_BOOT_INTERNAL_BASE_URL=http://localhost:8080
SPRING_INTERNAL_API_SECRET=
SPRING_REPORT_CALLBACK_PATH=/api/report
SPRING_QUIZ_GRADE_RESULT_PATH=/api/internal/quizzes/grade-result
SPRING_CALLBACK_TIMEOUT_SECONDS=10
```

구현 파일:

- `fastapi/app/config.py`
- `fastapi/app/integration/spring_client.py`
- `fastapi/app/integration/spring_payloads.py`

### 2. Quiz grading endpoint

FastAPI가 받는 endpoint:

```http
POST /api/internal/quizzes/grade
```

성공적으로 수락되면 즉시 `202 Accepted`를 반환한다.

```json
{
  "accepted": true,
  "submissionId": "quiz-submission-uuid"
}
```

이후 background 작업에서 `grade_quiz_answer()`를 호출하고, Spring Boot의 `POST /api/internal/quizzes/grade-result`로 `GRADED` 또는 `UNGRADED` callback을 보낸다. 요청 body의 `callbackUrl`은 destination으로 쓰지 않고, 설정된 Spring Boot callback path만 사용한다.

### 3. Report SQS consumer

Report 흐름은 FastAPI HTTP endpoint가 아니라 SQS message에서 시작한다.

FastAPI worker는 `REPORT_REQUEST_QUEUE_URL`에서 message를 읽고, valid message에 대해 `generate_daily_report_result()`를 호출한 뒤 Spring Boot `POST /api/report`로 callback을 보낸다.

Spring Boot가 `200 OK`를 반환한 경우에만 SQS message를 삭제한다. `5xx`, timeout, connection error, invalid schema, Spring `4xx`는 삭제하지 않고 재전달/DLQ 정책에 맡긴다.

주요 env:

```dotenv
AWS_REGION=ap-northeast-2
AWS_SQS_ENDPOINT=
AWS_ACCESS_KEY_ID=
AWS_SECRET_ACCESS_KEY=
REPORT_REQUEST_QUEUE_URL=
REPORT_REQUEST_DLQ_URL=
```

구현 파일:

- `fastapi/app/integration/report_consumer.py`

## Swagger에서 확인하기

FastAPI Swagger:

```text
http://localhost:8000/docs
```

확인할 것:

- `GET /api/health`
- `POST /api/internal/quizzes/grade`

Swagger의 quiz endpoint는 실제 endpoint contract 확인용이다. Spring Boot를 띄우지 않은 상태에서는 background callback이 실패할 수 있으므로, callback payload까지 한 번에 보려면 아래 FastAPI-only preview script를 사용한다.

## FastAPI-only 통합 preview

Spring Boot, AWS SQS, Gemini 없이 report SQS consumer와 quiz grading callback payload를 한 번에 확인한다.

```bash
cd fastapi
.venv/bin/python scripts/integration_contract_preview.py
```

이 스크립트는 다음을 수행한다.

- sample report SQS message를 `process_report_request_message()`에 넣는다.
- sample report service result를 Spring callback payload로 감싼다.
- sample quiz grading request를 `process_quiz_grading_request()`에 넣는다.
- sample grader result를 Spring grade-result callback payload로 바꾼다.
- 실제 HTTP/AWS/Gemini 호출 대신 fake Spring transport에 기록된 callback URL/body를 JSON으로 출력한다.

출력에서 확인할 것:

- report callback URL은 `/api/report`다.
- quiz callback URL은 `/api/internal/quizzes/grade-result`다.
- request body의 `callbackUrl`은 사용되지 않는다.
- report payload에 `submissionId`, `gradings`, quiz submission 정보가 섞이지 않는다.
- quiz `scoreDelta`는 `scoreAllocation` 범위 안으로 clamp된다.

## Quiz grading 테스트 흐름

1. FastAPI 서버를 실행한다.
2. FastAPI Swagger `http://localhost:8000/docs`에서 `POST /api/internal/quizzes/grade`를 실행한다.
3. callback payload shape는 `scripts/integration_contract_preview.py` 또는 integration tests로 확인한다.

샘플 request:

```json
{
  "submissionId": "quiz-submission-local-1",
  "userId": 1,
  "characterId": 10,
  "quizId": 55,
  "problemId": 1,
  "question": "JPA N+1 문제란 무엇인가?",
  "modelAnswer": "연관 엔티티를 지연 로딩할 때 추가 쿼리가 반복해서 발생하는 문제다.",
  "userAnswer": "연관 데이터를 조회할 때 쿼리가 N번 더 나가는 문제입니다.",
  "scoreAllocation": {
    "db": 3,
    "algorithm": 0,
    "cs": 0,
    "network": 0,
    "framework": 2
  },
  "characterMetadata": {
    "personality": "칭찬은 짧게, 부족한 부분은 명확히 알려주는 성격",
    "currentStats": {
      "db": 120,
      "algorithm": 90,
      "cs": 80,
      "network": 60,
      "framework": 140
    }
  },
  "callbackUrl": "http://localhost:8080/api/internal/quizzes/grade-result"
}
```

`SPRING_INTERNAL_API_SECRET`을 설정했다면 FastAPI 요청에도 아래 header를 함께 보낸다.

```http
Authorization: Internal <SPRING_INTERNAL_API_SECRET>
```

## PR에서 강조할 점

- FastAPI에 `POST /api/report`를 만들지 않았다. 이 endpoint는 Spring Boot callback 수신 endpoint다.
- Report `scoreDelta`와 quiz grading `scoreDelta`는 서로 다른 callback에서만 반영된다.
- SQS message에는 quiz submission, quiz grading result, `gradings` 배열을 넣지 않는다.
- 캐릭터 이미지 생성 HTTP endpoint는 아직 없다.
- Spring Boot 코드는 이 PR에서 건드리지 않는다.
