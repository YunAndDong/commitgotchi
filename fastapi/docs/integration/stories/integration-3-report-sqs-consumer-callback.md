---
title: Integration 3 - Report SQS Consumer and Report Callback
status: backlog
created: 2026-06-14
owner: FastAPI AI 서버
epic: integration-contracts
story_key: integration-3-report-sqs-consumer-callback
source_docs:
  - ../integration-contracts-epic.md
  - ../integration-contracts-sprint-status.yaml
  - ./integration-1-spring-callback-client-config.md
  - ../../report/report-generation-epic.md
  - ../../report/stories/report-5-report-result-assembly-quiz-recommender.md
  - _bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md#4.1
  - _bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md#4.2
  - _bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md#8.1
---

# Integration 3. Report SQS Consumer and Report Callback

## Status

backlog

## Story

As a FastAPI AI 서버 개발자,
I want report request SQS messages to trigger `generate_daily_report_result()` and a Spring Boot `POST /api/report` callback,
so that daily reports and recommended quizzes are produced asynchronously without FastAPI touching Spring Boot DB.

## 목표

`REPORT_REQUEST_QUEUE_URL` 등 기존 SQS env를 사용해 report request message를 소비하고, 내부 `generate_daily_report_result()` 결과에 Spring Boot callback wrapper를 붙여 `POST /api/report`로 전송한다.

Spring Boot가 `200 OK`를 반환한 경우에만 SQS message를 삭제한다. `5xx` 또는 timeout이면 삭제하지 않아 SQS 재전달을 허용한다.

invalid schema, fallback result, DLQ 처리 정책을 명확히 문서화하고 구현한다.

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

## 구현 범위

이번 story에서 구현할 것은 report SQS consumer와 Spring Boot report callback orchestration이다.

1. SQS config
   - 기존 `.env.example`의 SQS env를 사용한다.
   - 중요 env:
     - `AWS_REGION`
     - `AWS_SQS_ENDPOINT`
     - `AWS_ACCESS_KEY_ID`
     - `AWS_SECRET_ACCESS_KEY`
     - `REPORT_REQUEST_QUEUE_URL`
     - `REPORT_REQUEST_DLQ_URL`
   - 이번 story 구현 시 `.env.example` 수정은 별도 변경으로 분리할 수 있다. 이 문서 작업에서는 수정하지 않는다.

2. Consumer shape
   - 권장 파일: `fastapi/app/integration/report_consumer.py`.
   - 단건 처리 함수와 polling loop를 분리한다.
   - tests에서는 fake SQS client와 fake callback client를 주입한다.
   - long polling, visibility timeout, max receive count는 infra/SQS redrive policy와 맞춘다.

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

4. Internal service call
   - valid message는 `generate_daily_report_result()`를 호출한다.
   - mapping:
     - `dailyReport.title` -> `report_title`
     - `dailyReport.content` -> `report_content`
     - `userMetadata` -> `user_metadata`
     - `characterMetadata.personality` -> `character_personality`
     - `characterMetadata` -> `character_metadata`
   - `generate_daily_report_result()` output에는 wrapper field가 없으므로 Story 1 wrapper helper를 사용한다.

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

6. Spring Boot callback
   - destination은 Story 1 config `SPRING_BOOT_INTERNAL_BASE_URL + SPRING_REPORT_CALLBACK_PATH`다.
   - FastAPI는 `POST /api/report`를 Spring Boot에 보낸다.
   - FastAPI에 `POST /api/report` endpoint를 만들지 않는다.

7. SQS delete/retry policy
   - Spring callback response가 `200 OK`이면 SQS message를 삭제한다.
   - Spring callback response body가 duplicate 처리 의미를 담아도 status가 `200`이면 삭제한다.
   - `5xx`, timeout, connection error이면 SQS message를 삭제하지 않는다.
   - non-retryable Spring `4xx`는 poison message로 분류한다. MVP에서는 삭제하지 않고 SQS redrive policy가 DLQ로 보내도록 둔다.
   - invalid schema는 callback을 만들 수 없으므로 삭제하지 않고 DLQ redrive에 맡긴다. explicit DLQ publish를 구현하는 경우에는 DLQ publish 성공 후 원본 삭제가 가능하지만, silent delete는 금지한다.

8. Fallback policy
   - valid message에서 AI/report generation이 실패하면 `generate_daily_report_result()` 또는 wrapper layer가 `FALLBACK` payload를 만든다.
   - fallback callback은 Spring Boot로 전송한다.
   - fallback callback도 Spring Boot가 `200`을 반환한 경우에만 SQS message를 삭제한다.

## 주요 파일 경로

구현 후보:

- `fastapi/app/config.py`
- `fastapi/app/integration/__init__.py`
- `fastapi/app/integration/report_consumer.py`
- `fastapi/app/integration/spring_client.py`
- `fastapi/app/integration/schemas.py`
- `fastapi/app/scoring/daily_report_service.py`

테스트 후보:

- `fastapi/tests/integration/test_report_consumer.py`
- `fastapi/tests/integration/test_report_callback_payloads.py`

재사용 대상:

- Story 1의 `build_report_callback_payload()`
- Story 1의 `SpringCallbackClient.post_report_result()`
- `fastapi/app/scoring/daily_report_service.py`의 `generate_daily_report_result()`
- `fastapi/app/scoring/schemas.py`의 `DailyReportResult`

수정하지 않아야 할 흐름:

- quiz grading endpoint
- quiz grade-result callback
- character generation endpoint/client
- Spring Boot DB 직접 접근
- FastAPI `POST /api/report` endpoint

## 권장 Public API 또는 Schema

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

권장 consumer API:

```python
def process_report_request_message(
    message_body: Mapping[str, Any] | str,
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
) -> None:
    ...
```

권장 process result:

```python
@dataclass(frozen=True)
class ReportMessageProcessResult:
    callback_attempted: bool
    callback_ok: bool
    delete_message: bool
    retryable: bool
    error: str | None = None
```

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

## 테스트 기준

필수 테스트:

- valid SQS message가 `generate_daily_report_result()` 호출로 이어지는지 검증한다.
- service input mapping이 `dailyReport.title/content`, `userMetadata`, `characterMetadata`를 보존하는지 검증한다.
- callback payload에 `requestId`, `userId`, `characterId`, `targetDate` wrapper가 포함되는지 검증한다.
- callback payload가 Story 5 result field를 보존하는지 검증한다.
- callback payload에 `gradings`, `submissionId`, quiz submission payload가 없는지 검증한다.
- callback `200 OK`일 때 fake SQS `delete_message`가 호출되는지 검증한다.
- callback `200 OK` + duplicate body일 때도 delete되는지 검증한다.
- callback `5xx`일 때 delete되지 않는지 검증한다.
- callback timeout/connection error일 때 delete되지 않는지 검증한다.
- callback `4xx`가 non-retryable poison result로 분류되지만 silent delete되지 않는지 검증한다.
- invalid schema는 report service와 callback client를 호출하지 않는지 검증한다.
- invalid schema는 delete되지 않고 DLQ/redrive 대상 result로 분류되는지 검증한다.
- service가 `FALLBACK` result를 반환해도 callback이 전송되는지 검증한다.
- fallback callback `200`일 때만 delete되는지 검증한다.
- Spring Boot DB 접근 import 또는 repository 호출이 없는지 확인한다.
- FastAPI `POST /api/report` endpoint가 생성되지 않았는지 확인한다.

권장 테스트 명령:

```bash
cd fastapi
python3 -m unittest tests.integration.test_report_consumer tests.integration.test_report_callback_payloads
```

관련 회귀 확인:

```bash
cd fastapi
python3 -m unittest tests.scoring.test_daily_report_service tests.integration.test_spring_client
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

## Dev Notes

- `.env.example`에는 `REPORT_REQUEST_QUEUE_URL`, `REPORT_REQUEST_DLQ_URL`, AWS region/endpoint/credential env가 이미 있다. 이 story 문서 작업에서는 값을 변경하지 않는다.
- SQS Standard queue는 at-least-once delivery이므로 Spring Boot는 `requestId` idempotency를 가져야 하고, FastAPI는 callback `200` 전에는 delete하지 않는다.
- invalid schema를 바로 delete하면 원인 분석이 어려운 silent drop이 된다. explicit DLQ publish를 구현하지 않는 한 delete하지 않고 redrive policy에 맡긴다.
- fallback result는 valid business result다. Spring Boot에 저장되어야 하므로 callback `200`을 받은 뒤 delete한다.
- `generate_daily_report_result()` output은 wrapper 없는 core payload다. wrapper helper를 통해 request metadata를 붙인다.
- report SQS message는 quiz submission이나 grading result를 종합하는 메시지가 아니다. report score와 quiz grading score는 별도 흐름에서 반영된다.
- FastAPI가 Spring Boot DB를 직접 조회해 characterId나 user profile을 보강하면 architecture 계약 위반이다. 필요한 데이터는 SQS message에 들어와야 한다.
- `POST /api/report`는 Spring Boot internal API다. FastAPI router에 추가하지 않는다.

## Tasks/Subtasks

- [ ] SQS settings/env loading 추가 또는 기존 settings와 연결 (AC: 1, 2)
- [ ] report request message schema 구현 (AC: 4)
- [ ] 단건 message 처리 함수 구현 (AC: 3)
- [ ] polling loop와 SQS receive/delete adapter 구현 (AC: 1, 3, 15)
- [ ] invalid schema classification 구현 (AC: 5, 6, 7)
- [ ] `generate_daily_report_result()` input mapping 구현 (AC: 8, 9)
- [ ] report callback wrapper 연결 (AC: 10, 11, 12)
- [ ] Spring report callback sender 연결 (AC: 13)
- [ ] callback status별 delete/retry policy 구현 (AC: 15, 16, 17, 18, 19)
- [ ] fallback callback 처리 구현 (AC: 20, 21)
- [ ] fake SQS/report service/callback client tests 작성 (AC: 25)
- [ ] Spring DB, FastAPI `/api/report`, quiz grading, character generation 범위 침범이 없는지 확인 (AC: 14, 22, 23, 24)

## Dev Agent Record

### Agent Model Used

TBD

### Debug Log References

TBD

### Completion Notes List

TBD

### File List

TBD

## Change Log

- 2026-06-14: Story context document created and left in backlog.
