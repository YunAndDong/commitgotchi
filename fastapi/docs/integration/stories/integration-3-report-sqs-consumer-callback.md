---
title: Integration 3 - Report SQS Consumer and Report Callback
status: done
created: 2026-06-14
updated: 2026-06-14
owner: FastAPI AI 서버
epic: integration-contracts
story_key: integration-3-report-sqs-consumer-callback
depends_on:
  - integration-1-spring-callback-client-config
  - integration-2-quiz-grading-endpoint-webhook
  - report-5-report-result-assembly-quiz-recommender
source_docs:
  - ../integration-contracts-epic.md
  - ../integration-contracts-sprint-status.yaml
  - ./integration-1-spring-callback-client-config.md
  - ./integration-2-quiz-grading-endpoint-webhook.md
  - ../../report/report-generation-epic.md
  - ../../report/stories/report-5-report-result-assembly-quiz-recommender.md
  - ../../../app/config.py
  - ../../../app/integration/spring_client.py
  - ../../../app/integration/spring_payloads.py
  - ../../../app/integration/schemas.py
  - ../../../app/scoring/daily_report_service.py
  - ../../../app/scoring/schemas.py
  - ../../../tests/integration/test_spring_client.py
  - ../../../tests/integration/test_spring_payloads.py
  - ../../../tests/scoring/test_daily_report_service.py
  - _bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md#4.1
  - _bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md#4.2
  - _bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md#4.6
  - _bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md#8.1
---

# Integration 3. Report SQS Consumer and Report Callback

## Status

done

## Story

As a FastAPI AI 서버 개발자,
I want report request SQS messages to trigger `generate_daily_report_result()` and a Spring Boot `POST /api/report` callback,
so that daily reports and recommended quizzes are produced asynchronously without FastAPI touching Spring Boot DB.

## Goal

`REPORT_REQUEST_QUEUE_URL` 등 deployment/local env에 있는 SQS 설정을 사용해 report request message를 소비하고, 내부 `generate_daily_report_result()` 결과에 Story 1의 report callback wrapper를 붙여 Spring Boot `POST /api/report`로 전송한다.

Spring Boot가 `200 OK`를 반환한 경우에만 SQS message를 삭제한다. `5xx` 또는 timeout이면 삭제하지 않아 SQS 재전달을 허용한다.

invalid schema, Spring `4xx`, fallback result, DLQ/redrive 처리 정책을 구현자가 착각하지 않도록 명확히 고정한다.

## 배경

Report Generation Epic은 내부 함수 중심으로 report 분석과 recommended quiz 조립을 완료했다. Story 5의 `generate_daily_report_result()`는 Spring Boot callback core payload와 호환되는 내부 결과를 반환하지만, SQS metadata wrapper와 callback 전송 책임은 후속 통합 계층에 남겨두었다.

Architecture의 흐름 A는 다음과 같다.

1. Spring Boot batch scheduler가 사용자별 report request message를 SQS에 적재한다.
2. FastAPI report SQS consumer가 message를 단건 소비한다.
3. FastAPI가 `generate_daily_report_result()`를 호출한다.
4. FastAPI가 Spring Boot `POST /api/report` callback을 전송한다.
5. Spring Boot가 저장/점수 반영을 완료하고 `200 OK`를 반환한다.
6. FastAPI는 그때만 SQS message를 삭제한다.

중요한 계약:

- report 흐름의 시작점은 HTTP endpoint가 아니라 SQS consumer다.
- Spring Boot `POST /api/report`는 FastAPI가 호출하는 Spring endpoint다. FastAPI에 만들지 않는다.
- FastAPI는 Spring Boot DB에 직접 접근하지 않는다.
- SQS message에는 quiz grading result, quiz submission, `gradings` 배열을 포함하지 않는다.
- report callback `scoreDelta`는 report analysis 결과만 반영한다.
- callback URL/base URL은 env/config로 관리한다.
- 실제 secret 값은 문서, 로그, fixture에 남기지 않는다.
- 캐릭터 생성 `POST /api/ai/commitgotchi`는 제외한다.

## Previous Story Intelligence

Integration Story 1은 최근 커밋 `8f5d504 feat(fastapi): add spring callback client config`에서 완료됐다. Story 3 구현자는 아래를 새로 만들지 말고 재사용한다.

- `fastapi/app/config.py`
  - `SPRING_BOOT_INTERNAL_BASE_URL`
  - `SPRING_INTERNAL_API_SECRET`
  - `SPRING_REPORT_CALLBACK_PATH`
  - `SPRING_QUIZ_GRADE_RESULT_PATH`
  - `SPRING_CALLBACK_TIMEOUT_SECONDS`
- `fastapi/app/integration/spring_client.py`
  - `SpringCallbackClient`
  - `SpringCallbackClient.post_report_result()`
  - `SpringCallbackClient.send_report_callback()`
  - `build_spring_callback_url()`
  - `build_internal_auth_headers()`
  - fake/mock transport가 가능한 `SpringCallbackTransport`
- `fastapi/app/integration/spring_payloads.py`
  - `build_report_callback_payload()`
  - `REPORT_RESULT_FIELDS`
  - `REPORT_FORBIDDEN_FIELDS`
  - report payload에서 `gradings`, `submissionId`, quiz submission/grading 관련 field를 제거하는 guardrail
- `fastapi/app/integration/schemas.py`
  - `SpringCallbackResponse`
  - `SpringCallbackResult`

Integration Story 2는 최근 커밋 `62545c1 feat(fastapi): add quiz grading endpoint webhook`에서 완료됐다. Story 3 구현자는 이 흐름을 건드리지 않는다.

- `fastapi/app/api/quiz_grading.py`와 `POST /api/internal/quizzes/grade`는 Story 2 범위다.
- quiz grade-result callback destination은 `SPRING_QUIZ_GRADE_RESULT_PATH`다.
- Story 3 report callback destination은 오직 `SPRING_REPORT_CALLBACK_PATH`다.
- report flow에 quiz `submissionId`, quiz submission payload, `gradings`, quiz grading result를 섞지 않는다.

Report Story 5는 `524f0fd feat: assemble daily report result`에서 완료됐고, `generate_daily_report_result()`는 wrapper 없는 core payload만 반환한다.

- output field는 `status`, `scoreDelta`, `emotion`, `statusMessage`, `dailyReport`, `nextRecommendation`, `recommendedQuizzes`다.
- `requestId`, `userId`, `characterId`, `targetDate`는 Story 3가 SQS message metadata로 감싼다.
- analyzer failure 또는 `FALLBACK`도 callback-ready core payload shape를 유지한다.
- `scoreDelta`는 report analysis 결과만 보존하고 recommender/quiz score를 더하지 않는다.

## Existing Code State

- `fastapi/app/config.py`는 `pydantic_settings.BaseSettings`와 `SettingsConfigDict(env_file=".env", extra="ignore")`를 사용한다. Spring callback config는 이미 있다. SQS config field는 아직 `Settings`에 없다.
- `fastapi/.env.example`에는 Spring callback placeholder만 있다. 실제 local `.env`에 SQS 값이 있을 수 있지만, 구현자는 실제 `.env` 값을 fixture, docs, logs, commit에 복사하지 않는다. 필요하면 `.env.example`에는 secret 없는 placeholder만 추가한다.
- `fastapi/requirements.txt`에는 현재 `boto3`가 없다. 실제 SQS adapter를 `boto3`로 구현한다면 dependency 추가와 fake-client tests를 함께 포함한다. core handler tests는 `boto3` 없이도 실행 가능해야 한다.
- `fastapi/app/integration/spring_client.py`의 `SpringCallbackClient._post()`는 `status_code == 200`일 때만 `ok=True`다. `5xx`, timeout, connection error는 `retryable=True`; `4xx`는 `retryable=False`.
- `fastapi/app/integration/spring_payloads.py`의 `build_report_callback_payload()`는 Story 5 core payload에 wrapper metadata를 붙이고 forbidden report fields를 제거한다.
- `fastapi/app/scoring/daily_report_service.py`의 `generate_daily_report_result()`는 `report_title`, `report_content`, `user_metadata`, `character_personality`, `character_metadata`를 받고 dict payload를 반환한다.
- `fastapi/tests/integration/test_spring_client.py`와 `fastapi/tests/integration/test_spring_payloads.py`는 fake transport/client, secret leak 방지, configured destination 검증 패턴을 제공한다.
- `fastapi/tests/scoring/test_daily_report_service.py`는 fake analyzer/recommender 주입과 fallback behavior를 검증한다.

## Scope

이번 story에서 구현할 것은 report SQS consumer와 Spring Boot report callback orchestration이다.

Implement:

- SQS settings/env loading.
- fake 주입 가능한 SQS client adapter.
- report request SQS message schema validation.
- 단건 message 처리 함수.
- durable worker/process가 호출할 polling loop.
- valid message에서 `generate_daily_report_result()` 호출.
- Story 1 `build_report_callback_payload()` 또는 `SpringCallbackClient.send_report_callback()` 재사용.
- Spring Boot report callback 전송.
- callback result별 SQS delete/retry/DLQ classification.
- fake/mock 기반 unit/integration tests.

Do not implement:

- FastAPI `POST /api/report`.
- Spring Boot DB 접근, ORM model, repository, SQL query.
- quiz grading endpoint 또는 grade-result callback 변경.
- character generation `POST /api/ai/commitgotchi`.
- report SQS message에 quiz grading result, quiz submission, `gradings` 포함.
- 실제 Spring Boot, 실제 AWS SQS, 실제 Gemini 호출 테스트.
- 운영 Terraform/CloudFormation redrive policy 변경.

1. SQS config
   - 기존 deployment/local env key를 사용한다.
   - 중요 env:
     - `AWS_REGION`
     - `AWS_SQS_ENDPOINT`
     - `AWS_ACCESS_KEY_ID`
     - `AWS_SECRET_ACCESS_KEY`
     - `REPORT_REQUEST_QUEUE_URL`
     - `REPORT_REQUEST_DLQ_URL`
   - `AWS_SQS_ENDPOINT`는 localstack 등 local/dev override용 optional 값이다.
   - `REPORT_REQUEST_DLQ_URL`은 MVP worker가 직접 publish하지 않더라도 config로 읽을 수 있게 둔다.
   - `.env.example`을 수정한다면 실제 `fastapi/.env` 값을 복사하지 말고 빈 placeholder만 추가한다.

2. Consumer shape
   - 권장 파일: `fastapi/app/integration/report_consumer.py`.
   - 단건 처리 함수와 polling loop를 분리한다.
   - 단건 처리 함수는 SQS delete를 직접 호출하지 않고 `delete_message` boolean/classification을 반환한다.
   - polling loop는 단건 처리 결과가 `delete_message=True`인 경우에만 `sqs_client.delete_message(QueueUrl=..., ReceiptHandle=...)`를 호출한다.
   - tests에서는 fake SQS client와 fake callback client를 주입한다.
   - long polling, visibility timeout, max receive count는 infra/SQS redrive policy와 맞춘다. Architecture assumption은 `maxReceiveCount = 3`이다.

3. Message schema validation
   - required top-level field:
     - `requestId`
     - `userId`
     - `targetDate`
     - `characterMetadata.characterId`
     - `dailyReport.content`
   - optional field:
     - `userMetadata`
     - `characterMetadata.name`
     - `characterMetadata.personality`
     - `characterMetadata.currentStats`
     - `dailyReport.title`
   - `requestId`는 callback에 그대로 반환하는 idempotency key다.
   - invalid schema는 AI 함수를 호출하지 않는다.
   - `userId`와 `characterMetadata.characterId`는 positive integer로 validation한다.
   - `requestId`, `targetDate`, `dailyReport.content`는 blank string을 거부한다.
   - unknown extra fields는 callback payload로 forward하지 않는다.
   - invalid schema error에는 raw body, secret, Authorization header, full report content를 넣지 않는다.

4. Internal service call
   - valid message는 `generate_daily_report_result()`를 호출한다.
   - mapping:
     - `dailyReport.title` -> `report_title`
     - `dailyReport.content` -> `report_content`
     - `userMetadata` -> `user_metadata`
     - `characterMetadata.personality` -> `character_personality`
     - `characterMetadata` -> `character_metadata`
   - `currentStats`는 `character_metadata` 안에 보존해 Story 4/5가 문안 context로 사용할 수 있게 한다.
   - `userMetadata.reportDirection.scoreDeltaHint`는 prompt/context 힌트일 뿐 final report `scoreDelta`를 직접 올리는 값이 아니다.
   - `generate_daily_report_result()` output에는 wrapper field가 없으므로 Story 1 wrapper helper를 사용한다.
   - service가 `status="FALLBACK"` payload를 반환해도 valid result로 보고 Spring callback을 전송한다.
   - service exception은 구현자가 fallback wrapper layer에서 safe `FALLBACK` core payload를 만들거나, `generate_daily_report_result()`의 existing fallback path가 보장되는 방식으로 처리한다. 어느 쪽이든 valid SQS message는 Spring callback 시도를 해야 한다.

5. Report callback payload wrapper
   - top-level wrapper:
     - `requestId`
     - `userId`
     - `characterId`
     - `targetDate`
   - payload body:
     - Story 5 result payload: `status`, `scoreDelta`, `emotion`, `statusMessage`, `dailyReport`, `nextRecommendation`, `recommendedQuizzes`
   - fallback result도 같은 wrapper를 사용한다.
   - quiz grading result, quiz submission, `gradings`는 포함하지 않는다.
   - wrapper helper는 `build_report_callback_payload()`를 우선 재사용한다.
   - `SpringCallbackClient.send_report_callback()`을 사용하면 wrapper와 report callback path 선택을 함께 재사용할 수 있다.
   - callback payload에는 SQS `ReceiptHandle`, raw message body, SQS attributes, exception text를 포함하지 않는다.

6. Spring Boot callback
   - destination은 Story 1 config `SPRING_BOOT_INTERNAL_BASE_URL + SPRING_REPORT_CALLBACK_PATH`다.
   - FastAPI는 `POST /api/report`를 Spring Boot에 보낸다.
   - FastAPI에 `POST /api/report` endpoint를 만들지 않는다.
   - request body나 SQS message에 callback URL이 있더라도 destination으로 사용하지 않는다.
   - `SPRING_QUIZ_GRADE_RESULT_PATH`는 이 story에서 사용하지 않는다.

7. SQS delete/retry policy
   - Spring callback response가 `200 OK`이면 SQS message를 삭제한다.
   - Spring callback response body가 duplicate 처리 의미를 담아도 status가 `200`이면 삭제한다.
   - `5xx`, timeout, connection error이면 SQS message를 삭제하지 않는다.
   - non-retryable Spring `4xx`는 poison message로 분류한다. MVP에서는 삭제하지 않고 SQS redrive policy가 DLQ로 보내도록 둔다.
   - invalid schema는 callback을 만들 수 없으므로 삭제하지 않고 DLQ redrive에 맡긴다. explicit DLQ publish를 구현하는 경우에는 DLQ publish 성공 후 원본 삭제가 가능하지만, silent delete는 금지한다.
   - `delete_message=True`는 callback `ok=True`일 때만 가능하다.
   - callback `4xx`는 `retryable=False`, `delete_message=False`, `poison=True` 또는 동등 classification으로 표현한다.
   - invalid schema는 `callback_attempted=False`, `delete_message=False`, `poison=True` 또는 동등 classification으로 표현한다.

8. Fallback policy
   - valid message에서 AI/report generation이 실패하면 `generate_daily_report_result()` 또는 wrapper layer가 `FALLBACK` payload를 만든다.
   - fallback callback은 Spring Boot로 전송한다.
   - fallback callback도 Spring Boot가 `200`을 반환한 경우에만 SQS message를 삭제한다.

## Recommended File Structure

주요 구현 후보:

- `fastapi/app/config.py`
- `fastapi/.env.example`
- `fastapi/app/integration/__init__.py`
- `fastapi/app/integration/report_consumer.py`

재사용/회귀 확인 파일. Story 3 구현 중 기본적으로 새로 만들거나 재작성하지 않는다. 필요한 경우에는 최소 수정하고 기존 tests를 반드시 유지한다.

- `fastapi/app/integration/spring_client.py`
- `fastapi/app/integration/spring_payloads.py`
- `fastapi/app/integration/schemas.py`
- `fastapi/app/scoring/daily_report_service.py`

주요 테스트 후보:

- `fastapi/tests/integration/test_report_consumer.py`

회귀/확장 테스트 후보:

- `fastapi/tests/integration/test_report_callback_payloads.py` (새 report consumer 전용 payload regression이 필요할 때만)
- `fastapi/tests/integration/test_spring_client.py`
- `fastapi/tests/integration/test_spring_payloads.py`
- `fastapi/tests/scoring/test_daily_report_service.py`

재사용 대상:

- Story 1의 `build_report_callback_payload()`
- Story 1의 `SpringCallbackClient.post_report_result()`
- Story 1의 `SpringCallbackClient.send_report_callback()`
- `fastapi/app/scoring/daily_report_service.py`의 `generate_daily_report_result()`
- `fastapi/app/scoring/schemas.py`의 `DailyReportResult`

수정하지 않아야 할 흐름:

- quiz grading endpoint
- quiz grade-result callback
- character generation endpoint/client
- Spring Boot DB 직접 접근
- FastAPI `POST /api/report` endpoint

## API / Message Contract

### Incoming SQS Message

권장 SQS message:

```json
{
  "requestId": "report-request-uuid",
  "userId": 1,
  "targetDate": "2026-06-06",
  "userMetadata": {
    "weeklyStudyStreak": "0100011",
    "reportDirection": {
      "scoreDeltaHint": {
        "db": 0,
        "algorithm": 3,
        "cs": 0,
        "network": 1,
        "framework": 0
      },
      "focus": "알고리즘과 네트워크 학습 증가분을 중심으로 코멘트"
    }
  },
  "characterMetadata": {
    "characterId": 10,
    "name": "커밋 몬스터",
    "personality": "칭찬을 많이 하지만 틀린 부분은 명확하게 지적하는 성격",
    "currentStats": {
      "db": 120,
      "algorithm": 200,
      "cs": 80,
      "network": 60,
      "framework": 140
    }
  },
  "dailyReport": {
    "title": "오늘 학습 기록",
    "content": "Spring JPA의 N+1 문제와 해결 방법을 공부했다."
  }
}
```

검증 규칙:

- `requestId`: 필수 non-empty string. Spring callback idempotency key다.
- `userId`: 필수 positive integer.
- `targetDate`: 필수 non-empty string. Spring 계약과 맞는다면 strict ISO `YYYY-MM-DD` parsing을 적용할 수 있다.
- `characterMetadata.characterId`: 필수 positive integer.
- `dailyReport.content`: 필수 non-empty string. logs/errors에는 full content를 넣지 않는다.
- `dailyReport.title`: optional string.
- `userMetadata`: optional mapping.
- `characterMetadata.name`, `characterMetadata.personality`, `characterMetadata.currentStats`: optional.
- quiz grading result, quiz submission, quiz grading `scoreAllocation`, `submissionId`, `gradings` array는 이 message에 속하지 않는다.

### AWS SQS Envelope

polling loop는 AWS SQS message envelope를 받고, sanitized `Body`만 단건 handler에 넘긴다.

```json
{
  "MessageId": "fake-message-id",
  "ReceiptHandle": "fake-receipt-handle",
  "Body": "{\"requestId\":\"report-request-uuid\", ...}",
  "Attributes": {},
  "MessageAttributes": {}
}
```

Envelope 규칙:

- `ReceiptHandle`은 polling loop가 `delete_message`를 호출할 때만 사용한다.
- `ReceiptHandle`, raw `Body`, SQS attributes, queue URL, AWS credential values, callback auth headers를 callback payload, logs, errors, committed fixtures에 복사하지 않는다.
- handler는 테스트 편의를 위해 `Mapping[str, Any]`, `str`, `bytes` body input을 받을 수 있지만, production polling은 SQS `Body` field를 parse해야 한다.
- Message attributes는 future story에서 별도 계약을 추가하지 않는 한 MVP에서 무시한다.

### Settings Contract

아래 field가 아직 없다면 `fastapi/app/config.py`에 추가한다.

```python
aws_region: str = "ap-northeast-2"
aws_sqs_endpoint: str | None = None
aws_access_key_id: str | None = None
aws_secret_access_key: SecretStr | None = None
report_request_queue_url: str | None = None
report_request_dlq_url: str | None = None
```

Settings 규칙:

- Env name은 uppercase를 유지한다: `AWS_REGION`, `AWS_SQS_ENDPOINT`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `REPORT_REQUEST_QUEUE_URL`, `REPORT_REQUEST_DLQ_URL`.
- `REPORT_REQUEST_QUEUE_URL`은 real worker process 실행에는 필수다. 다만 기존 API/tests의 global `Settings()` import가 깨지지 않도록 module import 시점이 아니라 worker factory/entrypoint에서 requiredness를 검증한다.
- `REPORT_REQUEST_DLQ_URL`은 redrive가 SQS 소유이므로 MVP에서는 optional이다. 이 story 완료 조건에 direct DLQ publish를 포함하지 않는다.
- `AWS_SECRET_ACCESS_KEY`를 settings에 저장한다면 `SecretStr` 또는 동등한 non-leaking representation을 사용한다.
- `.env.example`에는 placeholder key를 추가할 수 있지만, `fastapi/.env` 값을 복사하지 않는다.

## Callback Contract

권장 callback payload:

```json
{
  "requestId": "report-request-uuid",
  "userId": 1,
  "characterId": 10,
  "targetDate": "2026-06-06",
  "status": "SUCCESS",
  "scoreDelta": {
    "db": 0,
    "algorithm": 3,
    "cs": 0,
    "network": 1,
    "framework": 0
  },
  "emotion": "JOY",
  "statusMessage": "오늘 학습 기록이 알찼어요!",
  "dailyReport": {
    "text": "오늘 학습은 JPA 영속성 영역에 집중되었습니다.",
    "feedback": "학습 강점: 문제 정의. 약점: 즉시 로딩 트레이드오프."
  },
  "nextRecommendation": {
    "topics": ["JPA 페치 조인", "@BatchSize"],
    "rationale": "N+1 원인은 이해했으니 해결 도구로 확장"
  },
  "recommendedQuizzes": []
}
```

Callback 규칙:

- Destination은 항상 `SPRING_BOOT_INTERNAL_BASE_URL + SPRING_REPORT_CALLBACK_PATH`다.
- Story 1 `build_report_callback_payload()` 또는 `SpringCallbackClient.send_report_callback()`을 사용한다.
- Top-level `scoreDelta`는 report analysis result에서만 복사한다.
- `recommendedQuizzes[*].scoreAllocation`은 future quiz maximum allocation snapshot이지 report score가 아니다. 이 값을 `scoreDelta`에 더하지 않는다.
- Callback payload에는 raw SQS body, receipt handle, SQS attributes, full Authorization header, internal secret, full exception text를 포함하지 않는다.
- `dailyReport`/`nextRecommendation`은 현재 `generate_daily_report_result()` output을 따른다. Architecture 예시가 fallback에서 `null`을 보여주더라도 Spring 계약이 명시적으로 바뀌지 않는 한 현재 service의 callback-ready object shape를 우선한다.

## Recommended Public API

권장 consumer API:

```python
def process_report_request_message(
    message_body: Mapping[str, Any] | str | bytes,
    *,
    report_service: Callable[..., Mapping[str, Any]] = generate_daily_report_result,
    callback_client: SpringCallbackClient,
) -> ReportMessageProcessResult:
    ...


def poll_report_request_queue(
    *,
    sqs_client: Any,
    queue_url: str,
    callback_client: SpringCallbackClient,
    report_service: Callable[..., Mapping[str, Any]] = generate_daily_report_result,
    wait_time_seconds: int = 20,
    max_number_of_messages: int = 1,
) -> None:
    ...
```

Production SQS adapter는 `boto3.client("sqs")`를 감싼 얇은 wrapper일 수 있다. 단, core handler tests는 `boto3`, AWS credentials, LocalStack, network access 없이 실행 가능해야 한다.

권장 process result:

```python
@dataclass(frozen=True)
class ReportMessageProcessResult:
    callback_attempted: bool
    callback_ok: bool
    delete_message: bool
    retryable: bool
    poison: bool = False
    request_id: str | None = None
    error: str | None = None
```

권장 분류:

| Case | callback_attempted | callback_ok | delete_message | retryable | poison |
|------|--------------------|--------------|----------------|-----------|--------|
| valid + callback `200` | true | true | true | false | false |
| valid + callback `200` duplicate body | true | true | true | false | false |
| valid + callback `5xx` | true | false | false | true | false |
| valid + timeout/connection error | true | false | false | true | false |
| valid + Spring `4xx` | true | false | false | false | true |
| invalid schema | false | false | false | false | true |
| valid + fallback payload + callback `200` | true | true | true | false | false |

## Durable Worker / Process Design

- 단건 message handler는 unit test가 쉽도록 충분히 pure/deterministic하게 유지한다.
- SQS polling loop는 작게 유지한다. 한 건 또는 작은 batch를 receive하고, 단건 handler를 호출한 뒤 handler result가 `delete_message=True`일 때만 delete한다.
- Worker는 별도 process entrypoint, scheduler, Procfile command, container command, future CLI로 실행할 수 있다. FastAPI HTTP startup이나 `POST /api/report` route에 의존하지 않는다.
- 권장 future entrypoint 후보는 `fastapi/app/integration/report_worker.py` 또는 `report_consumer.py` 옆의 작은 module-level `main()`이다. 이 story에서 구현한다면 thin wrapper로 두고 injected fake client로 테스트한다.
- MVP clarity를 위해 long polling default는 `WaitTimeSeconds=20`, `MaxNumberOfMessages=1`을 권장한다. Batch receive를 추가하더라도 각 message delete decision은 독립적이어야 한다.
- Visibility timeout은 MVP에서 infra-owned다. Code가 override config를 허용할 수는 있지만, tests는 real timeout behavior에 의존하지 않는다.
- Worker는 at-least-once delivery에 안전해야 한다. FastAPI는 local idempotency를 저장하지 않고, Spring Boot가 `requestId` idempotency를 소유한다.
- 이 story에서 FastAPI durable DB state를 추가하지 않는다.
- Report worker를 FastAPI HTTP request background task로 실행하지 않는다. 별도 long-running consumer/process 책임이다.
- Tests에는 `max_messages`, `run_once`, injected stop predicate 같은 bounded loop option을 제공해 test가 hang되지 않게 한다.
- `receive_message`는 report request queue만 대상으로 하며, queue/message metadata에서 callback destination을 추론하지 않는다.
- Batch를 받는 경우에도 각 message를 독립 처리한다. 한 message의 callback 실패가 다른 message delete로 이어지면 안 된다.

## SQS Delete / Retry / DLQ Policy

- Spring callback result `ok=True`일 때만 delete한다. Story 1 기준으로 `ok=True`는 현재 `status_code == 200`이다.
- Spring `200 OK` response body가 duplicate 의미를 담아도 delete한다. Spring Boot가 idempotent completion을 승인한 상태이기 때문이다.
- Spring `5xx`, timeout, connection error에서는 delete하지 않는다. SQS visibility timeout 만료로 redelivery를 허용한다.
- Spring `4xx`는 callback contract 관점에서 poison/non-retryable로 분류하지만, MVP에서는 silent delete하지 않고 `delete_message`를 호출하지 않는다. SQS redrive가 max receive count 이후 DLQ로 보내게 둔다.
- Invalid schema는 poison이고 callback을 만들 수 없다. MVP에서는 silent delete하지 않고 `delete_message`를 호출하지 않는다. SQS redrive가 max receive count 이후 DLQ로 보내게 둔다.
- Direct DLQ publish는 optional이며 MVP 완료 조건이 아니다. 구현한다면 명시적이고 테스트되어야 하며, DLQ publish 성공 후에만 source message를 delete할 수 있다. Message를 조용히 버리지 않는다.
- Process result는 real SQS 없이도 tests에서 delete/no-delete decision을 볼 수 있어야 한다.

리포트 생성 실패 경계:

- Default production flow는 `generate_daily_report_result()`를 호출한다. 이 함수의 analyzer/recommender failure path는 callback-ready `FALLBACK` payload를 반환하므로, 반환된 `FALLBACK`은 valid result로 보고 Spring callback을 전송한다.
- Injected 또는 future `report_service`가 payload 반환 전에 예상치 못한 exception을 던진다면 두 정책 중 하나를 선택한다. 첫째, raw exception text 없이 safe callback-ready `FALLBACK` payload로 변환하고 normal callback/delete policy를 따른다. 둘째, callback 없이 retryable/no-delete로 분류한다.
- 선택한 branch는 implementation notes에 기록하고 fake tests로 고정한다. Spring이 ack하지 않은 service exception에 대해서는 절대 SQS message를 delete하지 않는다.

## Logging / Security Guardrails

- Never log or expose `AWS_SECRET_ACCESS_KEY`, `SPRING_INTERNAL_API_SECRET`, full `Authorization` header, raw SQS body, full report content, callback payload containing user report content, or full exception repr if it may include secrets/body content.
- Logs may include sanitized correlation fields: `requestId`, `userId`, `characterId`, `targetDate`, high-level status, callback status code, classification.
- Error strings in `ReportMessageProcessResult.error` must be generic, e.g. `invalid report request schema`, `spring callback server error`, `spring callback timeout`.
- Test fixtures must use obviously fake values and must not copy from local `.env`.
- Test fixtures는 짧은 synthetic report text를 포함할 수 있다. 그러나 real user report, full raw SQS body, report content를 포함한 full callback payload, local secret/token 값을 snapshot으로 남기지 않는다.
- Do not store local `.env` values in docs, snapshots, committed tests, or comments.

## Acceptance Criteria

1. report SQS consumer는 `REPORT_REQUEST_QUEUE_URL`을 사용한다.
2. SQS client 설정은 `AWS_REGION`, optional `AWS_SQS_ENDPOINT`, AWS credential env와 호환된다.
3. 단건 message 처리 함수와 polling loop가 분리되어 테스트 가능하다.
4. valid SQS message schema는 `requestId`, `userId`, `targetDate`, `characterMetadata.characterId`, `dailyReport.content`를 요구한다.
5. invalid schema message는 `generate_daily_report_result()`를 호출하지 않는다.
6. invalid schema message는 Spring callback을 호출하지 않는다.
7. invalid schema message는 silent delete하지 않고 DLQ/redrive 정책으로 격리된다.
8. valid message는 `generate_daily_report_result()`를 호출한다.
9. `dailyReport.title`, `dailyReport.content`, `userMetadata`, `characterMetadata`가 service input으로 전달된다.
10. callback payload는 `requestId`, `userId`, `characterId`, `targetDate` wrapper를 포함한다.
11. callback payload는 Story 5 result payload를 포함한다.
12. callback payload에는 quiz grading result, quiz submission, `gradings` 배열이 포함되지 않는다.
13. callback destination은 `SPRING_BOOT_INTERNAL_BASE_URL + SPRING_REPORT_CALLBACK_PATH`다.
14. FastAPI에 `POST /api/report` endpoint를 만들지 않는다.
15. Spring callback response가 `200 OK`이면 SQS `delete_message`가 호출된다.
16. Spring callback response가 `200 OK`와 duplicate body를 반환해도 SQS message가 삭제된다.
17. Spring callback response가 `5xx`이면 SQS message를 삭제하지 않는다.
18. Spring callback timeout/connection error이면 SQS message를 삭제하지 않는다.
19. Spring callback `4xx`는 non-retryable poison result로 분류하되 silent delete하지 않는다.
20. `generate_daily_report_result()`가 `FALLBACK` payload를 반환해도 Spring callback을 시도한다.
21. fallback callback도 Spring Boot가 `200`을 반환한 경우에만 SQS message를 삭제한다.
22. consumer와 callback orchestration은 Spring Boot DB에 직접 접근하지 않는다.
23. quiz grading endpoint 또는 grade-result callback을 수정하지 않는다.
24. 캐릭터 생성 `POST /api/ai/commitgotchi` 흐름을 만들거나 수정하지 않는다.
25. unit test는 fake SQS client, fake report service, fake callback client만 사용한다.
26. `SpringCallbackClient`, `build_report_callback_payload()`, Story 1 config/path/auth helper를 재사용하고 중복 구현하지 않는다.
27. report callback path로 `SPRING_QUIZ_GRADE_RESULT_PATH` 또는 request body callback URL을 사용하지 않는다.
28. secret/token, raw SQS body, full Authorization header, user report content가 logs/errors/fixtures에 노출되지 않는다.
29. service exception 또는 AI failure가 safe `FALLBACK` payload로 전환되는 valid message에서는 callback을 시도한다.
30. Spring callback `4xx`와 invalid schema poison message는 MVP에서 delete하지 않고 SQS redrive/DLQ에 맡긴다는 정책이 code/test/doc에 일치한다.
31. polling loop는 SQS envelope의 `Body`만 handler에 넘기고, `ReceiptHandle`은 delete 용도로만 사용한다.
32. polling loop는 `delete_message=True`일 때만 `QueueUrl`과 `ReceiptHandle`로 SQS delete를 호출한다.
33. report worker는 FastAPI HTTP route/background task가 아니라 별도 long-running process/entrypoint로 실행 가능하다.
34. existing `Settings()` import, Story 1 tests, Story 2 endpoint tests는 `REPORT_REQUEST_QUEUE_URL`이 없어도 깨지지 않는다.

## Test Requirements

필수 테스트:

- valid SQS message가 `generate_daily_report_result()` 호출로 이어지는지 검증한다.
- service input mapping이 `dailyReport.title/content`, `userMetadata`, `characterMetadata`를 보존하는지 검증한다.
- callback payload에 `requestId`, `userId`, `characterId`, `targetDate` wrapper가 포함되는지 검증한다.
- callback payload가 Story 5 result field를 보존하는지 검증한다.
- callback payload에 `gradings`, `submissionId`, quiz submission payload가 없는지 검증한다.
- callback destination이 `SPRING_BOOT_INTERNAL_BASE_URL + SPRING_REPORT_CALLBACK_PATH`인지 검증한다.
- request body나 SQS body에 callback URL 비슷한 값이 있어도 destination으로 쓰지 않는지 검증한다.
- callback `200 OK`일 때 fake SQS `delete_message`가 호출되는지 검증한다.
- callback `200 OK` + duplicate body일 때도 delete되는지 검증한다.
- callback `5xx`일 때 delete되지 않는지 검증한다.
- callback timeout/connection error일 때 delete되지 않는지 검증한다.
- callback `4xx`가 non-retryable poison result로 분류되지만 silent delete되지 않는지 검증한다.
- invalid schema는 report service와 callback client를 호출하지 않는지 검증한다.
- invalid schema는 delete되지 않고 DLQ/redrive 대상 result로 분류되는지 검증한다.
- service가 `FALLBACK` result를 반환해도 callback이 전송되는지 검증한다.
- service가 exception을 던져도 wrapper layer가 safe fallback을 만들도록 구현했다면 callback이 전송되는지 검증한다. 구현이 `generate_daily_report_result()` 자체 fallback에만 의존한다면 그 경계를 명시하고 fake service exception path를 delete하지 않는 poison/retry classification으로 고정한다.
- fallback callback `200`일 때만 delete되는지 검증한다.
- SQS envelope에서 `Body`만 handler로 전달되고 `ReceiptHandle`은 callback payload/log/result에 섞이지 않는지 검증한다.
- fake polling loop가 `delete_message=True`일 때만 `ReceiptHandle`로 `delete_message`를 호출하는지 검증한다.
- `receive_message` empty response가 no-op인지 검증한다.
- `REPORT_REQUEST_QUEUE_URL`이 없는 환경에서도 existing API/config regression tests가 import 단계에서 실패하지 않는지 검증한다.
- worker entrypoint/factory가 real worker 실행 시 queue URL 누락을 safe config error로 보고하되 secret/env 값을 노출하지 않는지 검증한다.
- Spring Boot DB 접근 import 또는 repository 호출이 없는지 확인한다.
- FastAPI `POST /api/report` endpoint가 생성되지 않았는지 확인한다.
- quiz grading endpoint tests가 regression으로 계속 통과하는지 확인한다.
- secret/token/raw body/full report content가 error/result/log fixture에 포함되지 않는지 검증한다.

권장 테스트 명령:

```bash
cd fastapi
python3 -m unittest tests.integration.test_report_consumer tests.integration.test_report_callback_payloads
```

관련 회귀 확인:

```bash
cd fastapi
python3 -m unittest tests.scoring.test_daily_report_service tests.integration.test_spring_client tests.integration.test_spring_payloads
python3 -m unittest tests.integration.test_quiz_grading_endpoint tests.integration.test_quiz_grading_webhook
```

## 제외 범위

이번 story에서 하지 않는다.

- FastAPI `POST /api/report` endpoint 생성
- Spring Boot DB 접근
- Spring Boot report 저장 로직
- Spring Boot SQS producer 구현
- quiz grading endpoint 구현
- quiz grade-result callback 구현
- report SQS message에 quiz grading result, quiz submission, `gradings` 포함
- 신규 quiz 생성
- 캐릭터 생성 `POST /api/ai/commitgotchi`
- 실제 AWS SQS 또는 실제 Spring Boot 서버를 unit test에서 호출
- 운영 DLQ redrive policy Terraform/infra 변경

## Dev Guardrails

- `.env.example`에는 현재 SQS placeholder가 없을 수 있다. 구현 시 추가한다면 sanitized placeholder만 추가하고 local `.env` 값을 복사하지 않는다.
- SQS Standard queue는 at-least-once delivery이므로 Spring Boot는 `requestId` idempotency를 가져야 하고, FastAPI는 callback `200` 전에는 delete하지 않는다.
- invalid schema를 바로 delete하면 원인 분석이 어려운 silent drop이 된다. explicit DLQ publish를 구현하지 않는 한 delete하지 않고 redrive policy에 맡긴다.
- fallback result는 valid business result다. Spring Boot에 저장되어야 하므로 callback `200`을 받은 뒤 delete한다.
- `generate_daily_report_result()` output은 wrapper 없는 core payload다. wrapper helper를 통해 request metadata를 붙인다.
- report SQS message는 quiz submission이나 grading result를 종합하는 메시지가 아니다. report score와 quiz grading score는 별도 흐름에서 반영된다.
- FastAPI가 Spring Boot DB를 직접 조회해 characterId나 user profile을 보강하면 architecture 계약 위반이다. 필요한 데이터는 SQS message에 들어와야 한다.
- `POST /api/report`는 Spring Boot internal API다. FastAPI router에 추가하지 않는다.
- `boto3`를 도입한다면 dependency 추가를 명시하고, core tests는 fake client로 유지한다.
- `fastapi/app/api/quiz_grading.py`, `fastapi/app/main.py`의 quiz grading route registration은 Story 2 회귀 대상이다. Story 3 구현 중 의도 없이 바꾸지 않는다.
- report callback helper가 forbidden fields를 제거하더라도 consumer layer에서 quiz/grading payload를 넣지 않는 테스트를 별도로 둔다. helper에만 의존해서 잘못된 input을 숨기지 않는다.

## Completion Definition

Story 3는 아래가 모두 만족될 때 implementation complete로 본다.

- `report_consumer.py` 또는 동등 module에 fake-injectable single-message handler와 polling loop가 있다.
- valid SQS message가 `generate_daily_report_result()`를 호출하고 Spring `POST /api/report` callback을 전송한다.
- `200 OK`에서만 SQS delete가 일어난다.
- `5xx`, timeout, connection error, Spring `4xx`, invalid schema는 delete하지 않는다.
- fallback result도 Spring callback으로 전송되며 `200 OK`에서만 delete된다.
- FastAPI `POST /api/report`, Spring DB 접근, quiz grading flow 변경, character generation 변경이 없다.
- fake/mock tests와 Story 1/2/Report 5 regression tests가 통과한다.
- story file의 Dev Agent Record에 실행한 테스트, 구현 파일, guardrail 확인 결과가 기록된다.

## Tasks/Subtasks

- [x] SQS settings/env loading 추가 또는 기존 `Settings`와 연결하되 global import 안전성 유지 (AC: 1, 2, 34)
- [x] `.env.example`에 SQS placeholder가 필요하면 secret 없는 값으로만 추가 (AC: 2, 28)
- [x] report request message schema/model 구현 (AC: 4)
- [x] safe SQS envelope/JSON body parsing과 invalid schema classification 구현 (AC: 5, 6, 7, 28, 30, 31)
- [x] `ReportMessageProcessResult` 또는 동등 result model 구현 (AC: 3, 15-21, 30)
- [x] 단건 message 처리 함수 구현 (AC: 3)
- [x] polling loop와 SQS receive/delete adapter 구현 (AC: 1, 3, 15, 31, 32, 33)
- [x] fake SQS client 주입 경계 구현 (AC: 25)
- [x] `generate_daily_report_result()` input mapping 구현 (AC: 8, 9)
- [x] valid message service exception/fallback policy 구현 (AC: 20, 21, 29)
- [x] Story 1 report callback wrapper/helper 연결 (AC: 10, 11, 12, 26)
- [x] `SpringCallbackClient.send_report_callback()` 또는 `post_report_result()` 연결 (AC: 13, 26, 27)
- [x] callback status별 delete/retry/poison policy 구현 (AC: 15, 16, 17, 18, 19, 30)
- [x] fake SQS/report service/callback client tests 작성 (AC: 25)
- [x] secret/raw body/receipt handle/full content leak 방지 tests 작성 (AC: 28, 31)
- [x] worker config missing-queue-url와 bounded loop tests 작성 (AC: 33, 34)
- [x] Story 1/2/Report 5 regression tests 실행 (AC: 23, 26, 34)
- [x] `rg` guardrail 확인: FastAPI `/api/report`, Spring DB 접근, quiz grading 변경, character generation 변경 없음 (AC: 14, 22, 23, 24)
- [x] Dev Agent Record에 implementation notes, test commands, File List 기록

## Dev Agent Record

### Agent Model Used

GPT-5 Codex

### Debug Log References

- RED: `./.venv/bin/python -m unittest tests.integration.test_report_consumer` failed with missing SQS settings/report consumer module, confirming new tests covered the missing story surface.
- GREEN: `./.venv/bin/python -m unittest tests.integration.test_report_consumer` passed 16 tests.
- Regression: `./.venv/bin/python -m unittest tests.scoring.test_daily_report_service tests.integration.test_spring_client tests.integration.test_spring_payloads` passed 25 tests.
- Regression: `./.venv/bin/python -m unittest tests.integration.test_quiz_grading_endpoint tests.integration.test_quiz_grading_webhook` passed 13 tests.
- Full suite: `./.venv/bin/python -m unittest discover` passed 148 tests.
- Guardrail `rg`/git checks found no FastAPI `POST /api/report` route, no report consumer Spring DB access, no report consumer quiz callback path use, and no diffs in Story 2 quiz grading route/client files.

### Completion Notes List

- Added optional SQS env loading to `Settings`, keeping `REPORT_REQUEST_QUEUE_URL` optional at import time and required only at worker execution time.
- Added sanitized `.env.example` SQS placeholders and lazy boto3 SQS client creation using region, optional endpoint, and optional fake/local credentials.
- Implemented `report_consumer.py` with fake-injectable message parsing, pydantic schema validation, service input mapping, safe service-exception fallback payload, Spring report callback dispatch, delete/retry/poison classification, bounded polling loop, and worker helpers.
- Reused `SpringCallbackClient.send_report_callback()` and Story 1 payload/path/auth helpers; report callback destination remains `SPRING_BOOT_INTERNAL_BASE_URL + SPRING_REPORT_CALLBACK_PATH`.
- Enforced callback `200 OK` as the only delete path; `5xx`, timeout, connection error, Spring `4xx`, invalid schema, and fallback callback failures do not delete.
- Added fake/mock tests for valid/fallback/invalid/service-exception flows, SQS delete boundaries, wrapper payload fields, forbidden quiz/grading field removal, missing queue URL safety, and security leak guardrails.

### File List

- fastapi/.env.example
- fastapi/app/config.py
- fastapi/app/integration/report_consumer.py
- fastapi/requirements.txt
- fastapi/tests/integration/test_report_consumer.py
- fastapi/docs/integration/integration-contracts-sprint-status.yaml
- fastapi/docs/integration/stories/integration-3-report-sqs-consumer-callback.md

## Change Log

- 2026-06-14: Story context document created and left in backlog.
- 2026-06-14: BMAD create-story refinement applied; Story 1/2/Report 5 reuse, SQS consumer contract, Spring report callback destination, delete/retry/DLQ policy, poison-message handling, fake/mock test requirements, security guardrails, and completion definition added. Status set to ready-for-dev.
- 2026-06-14: Tightened ready-for-dev guidance for primary vs reuse files, SQS envelope handling, worker/process boundaries, settings import safety, no-delete poison policy, and report generation failure classification.
- 2026-06-14: Implemented report SQS consumer/callback orchestration with fake/mock tests and moved status to review.
