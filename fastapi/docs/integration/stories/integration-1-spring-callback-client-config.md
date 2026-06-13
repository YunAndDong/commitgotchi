---
title: Integration 1 - Spring Callback Client and Integration Config
status: ready-for-dev
created: 2026-06-14
owner: FastAPI AI 서버
epic: integration-contracts
story_key: integration-1-spring-callback-client-config
source_docs:
  - ../integration-contracts-epic.md
  - ../integration-contracts-sprint-status.yaml
  - ../../report/report-generation-epic.md
  - ../../report/stories/report-5-report-result-assembly-quiz-recommender.md
  - _bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md#4.2
  - _bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md#4.3
---

# Integration 1. Spring Callback Client and Integration Config

## Status

ready-for-dev

## Story

As a FastAPI AI 서버 개발자,
I want Spring Boot internal callback destination, auth, timeout, and payload wrapper helpers to be centralized in env-backed config,
so that report SQS and quiz grading flows can call Spring Boot without hardcoded URLs or real-network unit tests.

## 목표

Spring Boot internal callback 호출에 필요한 설정과 helper를 FastAPI 통합 계층에 추가한다.

이번 story는 endpoint나 SQS consumer를 만들지 않는다. 후속 Story 2와 Story 3이 재사용할 공통 기반만 만든다.

필수 env 후보:

```dotenv
SPRING_BOOT_INTERNAL_BASE_URL=http://localhost:8080
SPRING_INTERNAL_API_SECRET=
SPRING_REPORT_CALLBACK_PATH=/api/report
SPRING_QUIZ_GRADE_RESULT_PATH=/api/internal/quizzes/grade-result
SPRING_CALLBACK_TIMEOUT_SECONDS=10
```

## 배경

Architecture는 Spring Boot가 System of Record이고 FastAPI가 Intelligence service라고 정의한다. FastAPI는 Spring Boot DB에 직접 접근하지 않고, SQS 또는 HTTP 요청으로 받은 입력을 처리한 뒤 Spring Boot Internal API callback으로 결과를 돌려준다.

Report Generation Story 5는 `generate_daily_report_result()`가 callback-ready 핵심 payload를 만들도록 구현했다. 다만 이 payload에는 `requestId`, `userId`, `characterId`, `targetDate` wrapper와 실제 Spring Boot callback 전송 책임이 없다.

Quiz grading 내부 함수인 `grade_quiz_answer()`도 Spring-facing camelCase 결과를 만들지만, Spring Boot `grade-result` endpoint로 전송하는 client 계층은 아직 없다.

이 story는 두 흐름의 공통 client/config를 먼저 만든다.

중요한 계약:

- Spring Boot base URL과 callback path는 코드에 하드코딩하지 않는다.
- internal auth secret은 env로 관리하고 문서나 로그에 실제 값을 남기지 않는다.
- unit test는 fake/mock transport만 사용한다.
- Spring Boot `POST /api/report`는 FastAPI endpoint가 아니다. FastAPI callback client가 호출할 Spring endpoint다.
- FastAPI는 Spring Boot DB에 직접 접근하지 않는다.
- 캐릭터 생성 `POST /api/ai/commitgotchi`는 이번 story 대상이 아니다.

## 구현 범위

이번 story에서 구현할 것은 공통 Spring callback client와 config다.

1. Settings 확장
   - `fastapi/app/config.py`의 `Settings`에 Spring callback env를 추가한다.
   - `SPRING_BOOT_INTERNAL_BASE_URL`은 trailing slash 유무와 관계없이 안정적으로 처리한다.
   - `SPRING_REPORT_CALLBACK_PATH` 기본값은 `/api/report`다.
   - `SPRING_QUIZ_GRADE_RESULT_PATH` 기본값은 `/api/internal/quizzes/grade-result`다.
   - `SPRING_CALLBACK_TIMEOUT_SECONDS` 기본값은 `10`이고 0 이하 값은 validation error 또는 안전 기본값으로 처리한다.
   - `.env.example`은 이번 작업에서는 수정하지 않는다. 실제 key 추가는 후속 구현자가 별도 변경으로 처리한다.

2. Internal auth header 설계
   - 권장 header: `X-Internal-Api-Secret: <SPRING_INTERNAL_API_SECRET>`.
   - 필요하면 `Authorization: Internal <token>` 형식도 Spring Boot와 합의할 수 있지만, 한 story 안에서는 하나의 방식을 고정한다.
   - secret이 비어 있으면 local/dev에서는 header를 생략할 수 있으나, production profile에서는 실패시키는 정책을 권장한다.
   - secret 값은 exception message, debug log, test snapshot에 남기지 않는다.

3. URL join helper
   - base URL과 callback path를 안전하게 결합한다.
   - path가 `/`로 시작하든 아니든 결과 URL이 중복 slash 없이 만들어진다.
   - callback path가 absolute URL이면 허용하지 않는다. destination host는 `SPRING_BOOT_INTERNAL_BASE_URL`에서만 온다.

4. Spring callback HTTP client
   - 권장 구현 파일: `fastapi/app/integration/spring_client.py`.
   - 권장 transport: `httpx.Client` 또는 `httpx.AsyncClient`.
   - timeout은 `SPRING_CALLBACK_TIMEOUT_SECONDS`를 사용한다.
   - callback sender는 response status와 body를 작은 result object로 반환한다.
   - `200 OK`는 success로 분류한다.
   - timeout, connection error, `5xx`는 retryable failure로 분류한다.
   - `4xx`는 non-retryable failure로 분류한다.

5. Report callback payload wrapper helper
   - Story 3에서 SQS message metadata와 Story 5 result payload를 합칠 수 있도록 helper를 둔다.
   - wrapper는 `requestId`, `userId`, `characterId`, `targetDate`를 top-level에 붙인다.
   - Story 5 result의 `status`, `scoreDelta`, `emotion`, `statusMessage`, `dailyReport`, `nextRecommendation`, `recommendedQuizzes`는 그대로 유지한다.
   - quiz grading result, quiz submission, `gradings` 배열은 포함하지 않는다.

6. Quiz grade-result callback sender helper
   - Story 2에서 `grade_quiz_answer()` 결과를 Spring Boot callback shape로 보낼 수 있게 한다.
   - destination은 `SPRING_BOOT_INTERNAL_BASE_URL + SPRING_QUIZ_GRADE_RESULT_PATH`다.
   - request body의 `callbackUrl`은 이 story의 sender destination으로 사용하지 않는다. 후속 Story 2에서 검증/무시 정책만 적용한다.

7. Tests
   - 실제 Spring Boot 서버를 띄우지 않는다.
   - `httpx.MockTransport`, fake client, monkeypatch 중 하나를 사용한다.
   - timeout과 status classification을 fake transport로 검증한다.

## 주요 파일 경로

구현 후보:

- `fastapi/app/config.py`
- `fastapi/app/integration/__init__.py`
- `fastapi/app/integration/spring_client.py`
- `fastapi/app/integration/schemas.py`

테스트 후보:

- `fastapi/tests/integration/__init__.py`
- `fastapi/tests/integration/test_spring_client.py`
- `fastapi/tests/integration/test_spring_callback_payloads.py`

참고 대상:

- `fastapi/app/scoring/daily_report_service.py`
- `fastapi/app/scoring/quiz_grader.py`
- `fastapi/app/scoring/schemas.py`
- `fastapi/.env.example`

수정하지 않아야 할 파일:

- `fastapi/docs/report/report-generation-epic.md`
- `fastapi/docs/report/report-generation-sprint-status.yaml`
- `fastapi/.env.example`
- app endpoint/router 구현 파일
- SQS consumer 구현 파일

## 권장 Public API 또는 Schema

권장 settings field:

```python
class Settings(BaseSettings):
    spring_boot_internal_base_url: str = "http://localhost:8080"
    spring_internal_api_secret: str | None = None
    spring_report_callback_path: str = "/api/report"
    spring_quiz_grade_result_path: str = "/api/internal/quizzes/grade-result"
    spring_callback_timeout_seconds: float = 10.0
```

권장 callback result shape:

```python
@dataclass(frozen=True)
class SpringCallbackResponse:
    status_code: int | None
    ok: bool
    retryable: bool
    body: dict[str, Any] | None = None
    error: str | None = None
```

권장 helper:

```python
def build_spring_callback_url(*, base_url: str, path: str) -> str:
    ...


def build_report_callback_payload(
    *,
    request_id: str,
    user_id: int,
    character_id: int,
    target_date: str,
    report_result: Mapping[str, Any],
) -> dict[str, Any]:
    ...


class SpringCallbackClient:
    def post_report_result(self, payload: Mapping[str, Any]) -> SpringCallbackResponse:
        ...

    def post_quiz_grade_result(self, payload: Mapping[str, Any]) -> SpringCallbackResponse:
        ...
```

권장 report callback payload:

```json
{
  "requestId": "report-request-uuid",
  "userId": 1,
  "characterId": 10,
  "targetDate": "2026-06-06",
  "status": "SUCCESS",
  "scoreDelta": { "db": 0, "algorithm": 3, "cs": 0, "network": 1, "framework": 0 },
  "emotion": "JOY",
  "statusMessage": "오늘 학습 기록이 알찼어요!",
  "dailyReport": {
    "text": "오늘 학습은 ...",
    "feedback": "학습 강점: ..."
  },
  "nextRecommendation": {
    "topics": ["JPA 페치 조인"],
    "rationale": "N+1 원인은 이해했으니 해결 도구로 확장"
  },
  "recommendedQuizzes": []
}
```

권장 quiz grade-result payload:

```json
{
  "submissionId": "quiz-submission-uuid",
  "userId": 1,
  "quizId": 55,
  "status": "GRADED",
  "scoreAllocation": { "db": 0, "algorithm": 3, "cs": 0, "network": 0, "framework": 0 },
  "scoreDelta": { "db": 0, "algorithm": 2, "cs": 0, "network": 0, "framework": 0 },
  "feedback": "원인은 맞췄으나 해결책이 빠졌습니다.",
  "emotion": "JOY",
  "statusMessage": "좋아요, 핵심은 잡았어요!"
}
```

## Acceptance Criteria

1. `Settings` 또는 equivalent config에서 Spring Boot base URL, report callback path, quiz grade-result path, internal secret, timeout을 읽을 수 있다.
2. 모든 Spring callback destination은 config에서 생성되며 코드에 full URL이 하드코딩되지 않는다.
3. `SPRING_REPORT_CALLBACK_PATH` 기본값은 `/api/report`다.
4. `SPRING_QUIZ_GRADE_RESULT_PATH` 기본값은 `/api/internal/quizzes/grade-result`다.
5. `SPRING_CALLBACK_TIMEOUT_SECONDS` 기본값은 `10`이다.
6. base URL과 path join은 trailing slash와 leading slash 조합에 안정적이다.
7. callback path가 absolute URL이면 거부한다.
8. internal auth header 이름과 값 주입 방식이 한 곳에 정의된다.
9. secret 값은 log, exception, test snapshot에 노출되지 않는다.
10. report callback sender는 `SPRING_BOOT_INTERNAL_BASE_URL + SPRING_REPORT_CALLBACK_PATH`로 POST한다.
11. quiz grade-result sender는 `SPRING_BOOT_INTERNAL_BASE_URL + SPRING_QUIZ_GRADE_RESULT_PATH`로 POST한다.
12. report callback payload wrapper는 `requestId`, `userId`, `characterId`, `targetDate`를 top-level에 포함한다.
13. report callback payload wrapper는 Story 5 result의 `status`, `scoreDelta`, `emotion`, `statusMessage`, `dailyReport`, `nextRecommendation`, `recommendedQuizzes`를 유지한다.
14. report callback payload에는 quiz grading result, quiz submission, `gradings` 배열이 포함되지 않는다.
15. quiz grade-result sender는 `submissionId`, `userId`, `quizId`, `status`, `scoreAllocation`, `scoreDelta`를 포함한 payload를 전송할 수 있다.
16. callback response `200`은 success로 분류된다.
17. callback response `5xx`와 timeout은 retryable failure로 분류된다.
18. callback response `4xx`는 non-retryable failure로 분류된다.
19. unit test는 실제 Spring Boot 서버를 호출하지 않고 fake/mock transport만 사용한다.
20. FastAPI에 `POST /api/report` endpoint를 만들지 않는다.
21. FastAPI는 Spring Boot DB에 직접 접근하는 코드를 추가하지 않는다.
22. 캐릭터 생성 `POST /api/ai/commitgotchi` 관련 설정이나 client를 추가하지 않는다.

## 테스트 기준

필수 테스트:

- settings가 env 값과 기본값을 올바르게 읽는지 검증한다.
- base URL/path join helper가 slash 조합을 안정적으로 처리하는지 검증한다.
- absolute callback path가 거부되는지 검증한다.
- auth secret이 설정된 경우 request header에 포함되는지 fake transport로 검증한다.
- auth secret 값이 exception/log 문자열에 노출되지 않는지 검증한다.
- report callback sender가 `/api/report`로 POST하는지 검증한다.
- quiz grade-result sender가 `/api/internal/quizzes/grade-result`로 POST하는지 검증한다.
- `200`, `4xx`, `5xx`, timeout classification을 검증한다.
- report callback payload wrapper가 wrapper field와 Story 5 result field를 모두 유지하는지 검증한다.
- report callback payload wrapper에 `gradings`, `submissionId`가 섞이지 않는지 검증한다.
- unit test가 real network를 사용하지 않는지 fake/mock transport로 고정한다.

권장 테스트 명령:

```bash
cd fastapi
python3 -m unittest tests.integration.test_spring_client tests.integration.test_spring_callback_payloads
```

관련 회귀 확인:

```bash
cd fastapi
python3 -m unittest tests.scoring.test_daily_report_service tests.scoring.test_quiz_grader
```

## 제외 범위

이번 story에서 하지 않는다.

- FastAPI `POST /api/internal/quizzes/grade` endpoint 구현
- report SQS consumer 구현
- SQS delete/retry/DLQ 처리
- 실제 Spring Boot 서버 호출 테스트
- `generate_daily_report_result()` 내부 로직 변경
- `grade_quiz_answer()` 내부 로직 변경
- `.env.example` 수정
- Spring Boot DB 접근
- FastAPI `POST /api/report` endpoint 생성
- 캐릭터 생성 `POST /api/ai/commitgotchi` 관련 구현

## Dev Notes

- 기존 `fastapi/app/config.py`는 `pydantic_settings.BaseSettings`를 사용하고 `extra="ignore"`로 `.env`를 읽는다. Spring callback env도 같은 settings 계층에 넣는 편이 일관적이다.
- existing `.env.example`에는 SQS env가 이미 있다. 이번 story 문서 작업에서는 `.env.example`을 수정하지 않는다.
- `httpx.MockTransport`를 쓰면 실제 네트워크 없이 request URL, header, body, timeout branch를 검증하기 쉽다.
- Spring Boot callback client는 Story 2/3에서 공유될 것이므로 endpoint나 SQS consumer 내부에 흩어두지 않는다.
- report callback wrapper는 Story 5의 `DailyReportResult.to_dict()` output을 그대로 받아 wrapper field만 얹는 adapter가 가장 안전하다.
- quiz grade-result callback은 `grade_quiz_answer()` 결과에 없는 `emotion`/`statusMessage`를 adapter 정책으로 보완할 수 있다. 이 값은 Spring Boot와 UX 계약에 맞게 보수적 기본값을 둔다.
- callback body는 camelCase를 유지한다.
- Secret은 fixture에 실제 값을 넣지 말고 `"test-secret"` 같은 dummy string만 사용한다.

## Tasks/Subtasks

- [ ] `Settings`에 Spring callback env field 추가 (AC: 1, 3, 4, 5)
- [ ] base URL/path join helper 구현 (AC: 2, 6, 7)
- [ ] internal auth header helper 구현 (AC: 8, 9)
- [ ] `SpringCallbackResponse` 또는 equivalent result model 구현 (AC: 16, 17, 18)
- [ ] Spring callback client 구현 (AC: 10, 11)
- [ ] report callback payload wrapper helper 구현 (AC: 12, 13, 14)
- [ ] quiz grade-result payload/sender helper 구현 (AC: 15)
- [ ] fake/mock transport unit tests 작성 (AC: 19)
- [ ] FastAPI `POST /api/report` endpoint와 Spring DB 접근이 추가되지 않았는지 확인 (AC: 20, 21)
- [ ] 캐릭터 생성 흐름을 건드리지 않았는지 확인 (AC: 22)

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

- 2026-06-14: Story context document created and marked ready-for-dev.
