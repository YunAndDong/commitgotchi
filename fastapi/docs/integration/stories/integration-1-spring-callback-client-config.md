---
title: Integration 1 - Spring Callback Client and Integration Config
status: done
created: 2026-06-14
owner: FastAPI AI 서버
epic: integration-contracts
story_key: integration-1-spring-callback-client-config
source_docs:
  - ../integration-contracts-epic.md
  - ../integration-contracts-sprint-status.yaml
  - ../../report/stories/report-5-report-result-assembly-quiz-recommender.md
  - ../../../app/config.py
  - ../../../app/scoring/daily_report_service.py
  - ../../../app/scoring/quiz_grader.py
  - _bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md#4.2
  - _bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md#4.3
---

# Integration 1. Spring Callback Client and Integration Config

## Status

done

## Story

As a FastAPI AI 서버 개발자,
I want Spring Boot internal callback destination, auth, timeout, and payload adapter helpers to be centralized in env-backed config,
so that report SQS and quiz grading flows can call Spring Boot through one tested contract without hardcoded URLs, Spring DB access, or real-network unit tests.

## Goal

Spring Boot internal callback 호출에 필요한 config, URL builder, auth header builder, callback client, report payload wrapper, quiz grade-result sender helper를 만든다.

이번 story는 후속 Integration Story 2와 Story 3이 재사용할 공통 기반만 만든다. FastAPI endpoint, SQS consumer, Spring Boot DB 접근, 내부 AI 함수 재구현은 하지 않는다.

필수 env/config 후보:

```dotenv
SPRING_BOOT_INTERNAL_BASE_URL=http://localhost:8080
SPRING_INTERNAL_API_SECRET=
SPRING_REPORT_CALLBACK_PATH=/api/report
SPRING_QUIZ_GRADE_RESULT_PATH=/api/internal/quizzes/grade-result
SPRING_CALLBACK_TIMEOUT_SECONDS=10
```

## Background

Architecture는 Spring Boot를 System of Record, FastAPI를 Intelligence service로 정의한다. FastAPI는 Spring Boot DB에 직접 접근하지 않고, HTTP 요청 또는 SQS 메시지로 받은 입력을 처리한 뒤 Spring Boot Internal API callback으로 결과를 전달한다.

Report Story 5는 `generate_daily_report_result()`를 구현해 callback-ready 핵심 payload를 만든 상태다. 다만 이 payload에는 SQS metadata wrapper인 `requestId`, `userId`, `characterId`, `targetDate`가 없다. 이 story의 report wrapper helper가 그 wrapper만 얹는다.

Quiz grading 내부 함수 `grade_quiz_answer()`는 Spring-facing camelCase 결과를 반환한다. 다만 현재 결과에는 `userId`, `quizId`, `emotion`, `statusMessage`, callback 전송 책임이 없다. 이 story의 quiz helper는 grading 결과와 request metadata를 Spring Boot `grade-result` callback shape로 조립하고, 공통 callback client로 전송한다.

중요한 계약:

- FastAPI는 Spring Boot DB에 직접 접근하지 않는다.
- `POST /api/report`는 Spring Boot endpoint다. FastAPI에 만들지 않는다.
- FastAPI가 새로 만들 endpoint인 `POST /api/internal/quizzes/grade`는 Story 2 범위다. 이 story에서는 만들지 않는다.
- report 흐름의 SQS consumer와 SQS delete/retry/DLQ 처리는 Story 3 범위다. 이 story에서는 만들지 않는다.
- 캐릭터 생성 `POST /api/ai/commitgotchi`는 이번 epic/story 대상이 아니다.
- 모든 Spring callback destination은 config에서 생성한다. request body의 임의 `callbackUrl`을 그대로 호출하지 않는다.
- 모든 Spring callback sender 테스트는 mock/fake transport 기반이다. unit test에서 실제 Spring Boot 서버를 호출하지 않는다.

## Acceptance Criteria

1. `fastapi/app/config.py`의 `Settings` 또는 동등 config에서 `SPRING_BOOT_INTERNAL_BASE_URL`, `SPRING_INTERNAL_API_SECRET`, `SPRING_REPORT_CALLBACK_PATH`, `SPRING_QUIZ_GRADE_RESULT_PATH`, `SPRING_CALLBACK_TIMEOUT_SECONDS`를 읽을 수 있다.
2. `SPRING_BOOT_INTERNAL_BASE_URL` 기본값은 `http://localhost:8080`이며 scheme/host/port만 허용한다. base URL path/query/fragment는 거부하거나 validation error로 처리한다.
3. `SPRING_REPORT_CALLBACK_PATH` 기본값은 `/api/report`다.
4. `SPRING_QUIZ_GRADE_RESULT_PATH` 기본값은 `/api/internal/quizzes/grade-result`다.
5. `SPRING_CALLBACK_TIMEOUT_SECONDS` 기본값은 `10`이고 0 이하 값은 validation error로 처리한다.
6. base URL과 callback path join은 trailing slash와 leading slash 조합에 안정적이며 중복 slash를 만들지 않는다.
7. callback path가 absolute URL이면 거부한다. destination host는 반드시 `SPRING_BOOT_INTERNAL_BASE_URL`에서만 온다.
8. internal auth header는 한 곳에 정의된다. 기본 계약은 `Authorization: Internal <SPRING_INTERNAL_API_SECRET>`이다.
9. `SPRING_INTERNAL_API_SECRET`이 비어 있으면 local/dev에서는 auth header를 생략할 수 있지만, helper는 secret 값을 log, exception, repr, test snapshot에 노출하지 않는다.
10. report callback sender는 `SPRING_BOOT_INTERNAL_BASE_URL + SPRING_REPORT_CALLBACK_PATH`로 JSON `POST`를 보낼 수 있다.
11. quiz grade-result sender는 `SPRING_BOOT_INTERNAL_BASE_URL + SPRING_QUIZ_GRADE_RESULT_PATH`로 JSON `POST`를 보낼 수 있다.
12. callback client는 `Content-Type: application/json`과 internal auth header를 일관되게 적용한다.
13. callback client는 timeout 값을 config에서 받은 초 단위 값으로 transport에 전달한다.
14. callback response `200 OK`는 success로 분류된다.
15. callback response `5xx`, timeout, connection error는 retryable failure로 분류된다.
16. callback response `4xx`는 non-retryable failure로 분류된다.
17. callback response `2xx` 중 `200`이 아닌 status는 contract mismatch로 보고 success가 아니다.
18. report callback payload wrapper는 `requestId`, `userId`, `characterId`, `targetDate`를 top-level에 포함한다.
19. report callback payload wrapper는 Story 5 result의 `status`, `scoreDelta`, `emotion`, `statusMessage`, `dailyReport`, `nextRecommendation`, `recommendedQuizzes`를 유지한다.
20. report callback payload wrapper는 source `report_result` mapping을 mutate하지 않는다.
21. report callback payload에는 quiz grading result, quiz submission, `gradings` 배열이 포함되지 않는다.
22. quiz grade-result payload helper는 `submissionId`, `userId`, `quizId`, `status`, `scoreAllocation`, `scoreDelta`, `feedback`을 포함한 payload를 만들 수 있다.
23. quiz grade-result payload helper는 `grade_quiz_answer()` 결과에 없는 `emotion`/`statusMessage`를 adapter 정책으로 보완할 수 있다.
24. quiz grading 실패 또는 `UNGRADED` 결과에서는 `scoreDelta`가 5필드 zero vector로 유지된다.
25. quiz grade-result sender는 request body의 `callbackUrl`을 destination으로 사용하지 않는다.
26. unit test는 실제 Spring Boot 서버를 호출하지 않고 fake/mock transport만 사용한다.
27. FastAPI에 `POST /api/report` endpoint를 만들지 않는다.
28. FastAPI는 Spring Boot DB에 직접 접근하는 코드를 추가하지 않는다.
29. 캐릭터 생성 `POST /api/ai/commitgotchi` 관련 설정, endpoint, client를 추가하지 않는다.

## Tasks / Subtasks

- [x] `Settings`에 Spring callback env field와 validation 추가 (AC: 1, 2, 3, 4, 5)
- [x] callback URL builder 구현 (AC: 6, 7)
- [x] internal auth header helper 구현 (AC: 8, 9, 12)
- [x] callback result model과 response classification 구현 (AC: 14, 15, 16, 17)
- [x] injectable fake/mock transport가 가능한 Spring callback client 구현 (AC: 10, 11, 12, 13, 26)
- [x] report callback payload wrapper helper 구현 (AC: 18, 19, 20, 21)
- [x] quiz grade-result payload helper 구현 (AC: 22, 23, 24, 25)
- [x] report callback sender method 구현 (AC: 10, 18, 19, 21)
- [x] quiz grade-result callback sender method 구현 (AC: 11, 22, 24, 25)
- [x] fake/mock transport unit tests 작성 (AC: 1-26)
- [x] FastAPI `POST /api/report`, Spring DB 접근, 캐릭터 생성 흐름이 추가되지 않았는지 확인 (AC: 27, 28, 29)

## Dev Notes

### Existing Code State

- `fastapi/app/config.py`는 `pydantic_settings.BaseSettings`와 `SettingsConfigDict(env_file=".env", extra="ignore")`를 사용한다. Spring callback env도 같은 `Settings` 계층에 추가한다.
- `fastapi/app/scoring/daily_report_service.py`의 `generate_daily_report_result()`는 `status`, `scoreDelta`, `emotion`, `statusMessage`, `dailyReport`, `nextRecommendation`, `recommendedQuizzes`를 반환한다. `requestId`, `userId`, `characterId`, `targetDate`는 의도적으로 포함하지 않는다.
- `fastapi/app/scoring/quiz_grader.py`의 `grade_quiz_answer()`는 `submissionId`, `status`, `scoreAllocation`, `scoreDelta`, `feedback`, `confidence`를 camelCase dict로 반환한다. `userId`, `quizId`, `emotion`, `statusMessage`는 caller/request metadata와 adapter policy로 붙여야 한다.
- `fastapi/app/scoring/schemas.py`는 dataclass + `to_dict()` 패턴을 사용한다. 새 callback result/payload model을 만든다면 이 패턴을 유지한다.
- 테스트는 `unittest` 기반이다. 새 테스트도 `python3 -m unittest ...`로 실행 가능해야 한다.
- 현재 `fastapi/requirements.txt`에는 별도 HTTP client dependency가 명시되어 있지 않다. 새 dependency를 도입한다면 구현 변경에 포함해 명시적으로 관리한다. dependency 도입 없이도 핵심 client는 transport protocol + fake transport로 테스트 가능해야 한다.

### Recommended File Structure

구현 후보:

- `fastapi/app/config.py`
- `fastapi/.env.example`
- `fastapi/app/integration/__init__.py`
- `fastapi/app/integration/spring_client.py`
- `fastapi/app/integration/spring_payloads.py`
- `fastapi/app/integration/schemas.py`

테스트 후보:

- `fastapi/tests/integration/__init__.py`
- `fastapi/tests/integration/test_spring_client.py`
- `fastapi/tests/integration/test_spring_payloads.py`

참고 대상:

- `fastapi/app/scoring/daily_report_service.py`
- `fastapi/app/scoring/quiz_grader.py`
- `fastapi/app/scoring/schemas.py`
- `fastapi/app/scoring/policy.py`
- `fastapi/app/rag/schemas.py`

이번 story 구현에서 만들지 않을 것:

- FastAPI `POST /api/report` endpoint
- FastAPI `POST /api/internal/quizzes/grade` endpoint
- SQS consumer, SQS delete/retry/DLQ 처리
- Spring Boot DB 접근 코드
- 캐릭터 생성 `POST /api/ai/commitgotchi` 관련 endpoint/client/config
- `generate_daily_report_result()` 내부 분석/추천 로직 변경
- `grade_quiz_answer()` 내부 채점 로직 변경

### Config Design

권장 settings field:

```python
class Settings(BaseSettings):
    spring_boot_internal_base_url: str = "http://localhost:8080"
    spring_internal_api_secret: SecretStr | None = None
    spring_report_callback_path: str = "/api/report"
    spring_quiz_grade_result_path: str = "/api/internal/quizzes/grade-result"
    spring_callback_timeout_seconds: float = 10.0
```

구현 지침:

- `pydantic.SecretStr`를 쓰면 secret repr 노출을 줄일 수 있다. header 생성 시에만 `get_secret_value()`를 사용한다.
- `SPRING_BOOT_INTERNAL_BASE_URL`은 path 없는 origin으로 normalize한다. 예: `http://localhost:8080/`은 `http://localhost:8080`으로 정규화 가능하지만 `http://localhost:8080/api`는 거부한다.
- callback path는 relative path만 허용한다. `/api/report`와 `api/report`는 모두 같은 destination으로 normalize해도 된다.
- timeout은 초 단위 float로 받고 `> 0`만 허용한다.
- `.env.example` 수정은 이번 문서 작업 범위가 아니었다. 실제 구현 story에서 env sample을 업데이트할지 여부는 별도 변경으로 다룬다.

### Spring Callback Client Design

권장 model/API:

```python
@dataclass(frozen=True)
class SpringCallbackResult:
    status_code: int | None
    ok: bool
    retryable: bool
    body: dict[str, Any] | None = None
    error: str | None = None


class SpringCallbackTransport(Protocol):
    def post_json(
        self,
        url: str,
        *,
        headers: Mapping[str, str],
        payload: Mapping[str, Any],
        timeout_seconds: float,
    ) -> SpringCallbackResult:
        ...


def build_spring_callback_url(*, base_url: str, path: str) -> str:
    ...


def build_internal_auth_headers(secret: SecretStr | str | None) -> dict[str, str]:
    ...
```

권장 `SpringCallbackClient`:

```python
class SpringCallbackClient:
    def post_report_result(self, payload: Mapping[str, Any]) -> SpringCallbackResult:
        ...

    def post_quiz_grade_result(self, payload: Mapping[str, Any]) -> SpringCallbackResult:
        ...
```

구현 지침:

- client constructor는 `settings`와 `transport`를 주입받을 수 있어야 한다.
- unit test는 항상 fake transport를 주입한다.
- production transport는 stdlib 또는 명시적으로 추가한 HTTP client dependency를 사용할 수 있다. 어떤 선택이든 client core와 payload helper test는 real network에 의존하지 않는다.
- `ok=True`는 `status_code == 200`일 때만 설정한다.
- `5xx`, timeout, connection error는 `retryable=True`.
- `4xx`와 non-200 2xx는 `retryable=False`, `ok=False`.
- `error`에는 secret, full authorization header, request body 전체를 넣지 않는다.

### Auth Header Contract

기본 internal auth contract:

```http
Authorization: Internal <SPRING_INTERNAL_API_SECRET>
```

구현 지침:

- header name과 prefix는 module constant로 둔다.
- secret이 비어 있으면 header를 생략한다. 이 동작은 local/dev 편의를 위한 것이며 production enforcement는 deployment/runtime profile에서 다룬다.
- secret 값은 `repr`, exception, debug log, assertion diff에 직접 나타나지 않게 한다.
- Spring Boot와 header contract가 변경되면 이 helper 하나만 바꾸면 되도록 sender별 중복 구현을 금지한다.

### Report Callback Payload Wrapper

권장 helper:

```python
def build_report_callback_payload(
    *,
    request_id: str,
    user_id: int,
    character_id: int,
    target_date: str,
    report_result: Mapping[str, Any],
) -> dict[str, Any]:
    ...
```

권장 output shape:

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

구현 지침:

- `report_result`는 `generate_daily_report_result()` output을 받는다.
- wrapper helper는 input mapping을 mutate하지 않는다.
- wrapper helper는 report result의 필수 field가 없으면 명확한 validation error를 낸다.
- forbidden field guardrail: `gradings`, `submissionId`, quiz submission payload, quiz grading result를 report callback payload에 포함하지 않는다.
- `scoreDelta`는 report analysis 결과만 유지한다. quiz `scoreAllocation`이나 quiz grading 결과를 더하지 않는다.

### Quiz Grade-Result Callback Helper

권장 helper:

```python
def build_quiz_grade_result_callback_payload(
    *,
    user_id: int,
    quiz_id: int,
    grading_result: Mapping[str, Any],
    emotion: str | None = None,
    status_message: str | None = None,
    failed_reason: str | None = None,
) -> dict[str, Any]:
    ...
```

권장 success output shape:

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

권장 fallback output shape:

```json
{
  "submissionId": "quiz-submission-uuid",
  "userId": 1,
  "quizId": 55,
  "status": "UNGRADED",
  "scoreAllocation": { "db": 0, "algorithm": 3, "cs": 0, "network": 0, "framework": 0 },
  "scoreDelta": { "db": 0, "algorithm": 0, "cs": 0, "network": 0, "framework": 0 },
  "feedback": "Gemini 호출 또는 구조화 출력 변환에 실패하여 채점하지 않았습니다.",
  "emotion": "SAD",
  "statusMessage": "AI가 잠깐 쉬는 중이에요. 답안은 저장됐어요.",
  "failedReason": "LLM_TIMEOUT"
}
```

구현 지침:

- `grading_result`는 `grade_quiz_answer()` output을 받는다.
- `scoreAllocation`과 `scoreDelta`는 `db`, `algorithm`, `cs`, `network`, `framework` 5필드 shape를 유지한다.
- `status == "UNGRADED"`이면 `scoreDelta`는 zero vector로 강제한다.
- `scoreDelta`는 `scoreAllocation`을 초과하지 않아야 한다. `grade_quiz_answer()`가 이미 clamp하지만 helper test에서 한 번 더 guardrail로 고정한다.
- request body의 `callbackUrl`은 이 sender의 destination으로 사용하지 않는다. destination은 config path에서만 온다.
- 이 helper는 grading payload adapter다. `grade_quiz_answer()` 내부 prompt/model logic을 바꾸지 않는다.

### Testing Requirements

필수 테스트:

- settings 기본값과 env override를 검증한다.
- invalid base URL path/query/fragment를 거부하는지 검증한다.
- timeout 0 이하 값을 거부하는지 검증한다.
- base URL/path join helper가 slash 조합을 안정적으로 처리하는지 검증한다.
- absolute callback path가 거부되는지 검증한다.
- auth secret이 설정된 경우 `Authorization: Internal <secret>` header가 fake transport request에 포함되는지 검증한다.
- auth secret이 비어 있으면 auth header가 생략되는지 검증한다.
- secret 값이 result `error`, exception text, repr에 노출되지 않는지 검증한다.
- report callback sender가 `/api/report`로 POST하는지 fake transport로 검증한다.
- quiz grade-result sender가 `/api/internal/quizzes/grade-result`로 POST하는지 fake transport로 검증한다.
- timeout value가 fake transport에 전달되는지 검증한다.
- `200`, non-200 `2xx`, `4xx`, `5xx`, timeout/connection error classification을 검증한다.
- report callback wrapper가 wrapper field와 Story 5 result field를 모두 유지하는지 검증한다.
- report callback wrapper가 source mapping을 mutate하지 않는지 검증한다.
- report callback wrapper에 `gradings`, `submissionId`, quiz submission payload가 섞이지 않는지 검증한다.
- quiz grade-result payload helper가 `grade_quiz_answer()` output과 `userId`/`quizId` metadata를 합쳐 Spring Boot callback shape를 만드는지 검증한다.
- `UNGRADED` quiz payload에서 `scoreDelta`가 zero vector인지 검증한다.
- request body의 `callbackUrl`이 sender destination으로 사용되지 않는지 검증한다.
- unit test가 real network를 사용하지 않는지 fake/mock transport로 고정한다.

권장 테스트 명령:

```bash
cd fastapi
python3 -m unittest tests.integration.test_spring_client tests.integration.test_spring_payloads
```

관련 회귀 확인:

```bash
cd fastapi
python3 -m unittest tests.scoring.test_daily_report_service tests.scoring.test_quiz_grader
```

## Project Structure Notes

- integration client는 `fastapi/app/integration` 아래에 둔다. scoring/RAG 내부 함수와 API/SQS orchestration 사이의 outbound adapter 책임이기 때문이다.
- `fastapi/app/scoring/daily_report_service.py`는 report 핵심 payload 조립까지만 유지한다. wrapper metadata를 이 service에 추가하지 않는다.
- `fastapi/app/scoring/quiz_grader.py`는 채점 결과 생성까지만 유지한다. callback URL/path/auth/transport 책임을 이 파일에 넣지 않는다.
- API router와 SQS consumer는 후속 story에서 공통 client/helper를 호출한다.
- Spring Boot callback endpoint ownership을 혼동하지 않는다. `POST /api/report`와 `POST /api/internal/quizzes/grade-result`는 Spring Boot가 받는 endpoint다.
- FastAPI가 이번 epic에서 받는 endpoint는 Story 2의 `POST /api/internal/quizzes/grade`뿐이며, 이 story에서는 구현하지 않는다.

## References

- Integration epic and Story 1 scope: `fastapi/docs/integration/integration-contracts-epic.md#Story-1-Spring-Callback-Client-and-Integration-Config`
- Integration sprint status: `fastapi/docs/integration/integration-contracts-sprint-status.yaml`
- Report Story 5 completion and payload boundary: `fastapi/docs/report/stories/report-5-report-result-assembly-quiz-recommender.md`
- Existing config pattern: `fastapi/app/config.py`
- Existing report result service: `fastapi/app/scoring/daily_report_service.py`
- Existing quiz grader output contract: `fastapi/app/scoring/quiz_grader.py`
- Existing scoring schemas: `fastapi/app/scoring/schemas.py`
- Architecture report callback contract: `_bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md#4.2`
- Architecture quiz grading webhook contract: `_bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md#4.3`

## Dev Agent Record

### Agent Model Used

GPT-5 Codex

### Implementation Plan

- `Settings`에 Spring callback origin/path/auth/timeout config를 추가하고 pydantic validator로 origin-only base URL, relative callback path, positive timeout을 강제한다.
- `app.integration` outbound adapter 계층을 추가해 URL builder, internal auth header helper, callback result classification, injectable transport 기반 `SpringCallbackClient`를 분리한다.
- report/quiz callback payload helper를 scoring 내부 함수 밖에 두어 wrapper metadata와 Spring-facing adapter 정책만 담당하게 한다.
- fake transport unittest로 destination, headers, timeout, classification, payload guardrail을 검증하고 기존 scoring 회귀 테스트를 유지한다.

### Debug Log References

- PASS: `cd fastapi && python3 -m unittest tests.scoring.test_daily_report_service tests.scoring.test_quiz_grader` 실행, 15개 테스트 통과.
- PASS: `cd fastapi && .venv/bin/python -m unittest tests.integration.test_spring_client tests.integration.test_spring_payloads` 실행, 18개 테스트 통과.
- PASS: `cd fastapi && .venv/bin/python -m compileall app tests/integration` 실행, Python compile 검증 통과.
- PASS: `cd fastapi && .venv/bin/python -m unittest discover -s tests` 실행, 119개 테스트 통과.
- INFO: `cd fastapi && .venv/bin/python -m pip install -r requirements.txt`는 Python 3.14 환경에서 `psycopg-binary==3.2.3` wheel 미제공으로 실패했다. 신규 integration test 실행에는 필요한 `pydantic-settings==2.6.1`만 별도 설치해 사용했다.
- Guardrail: `rg`로 FastAPI `POST /api/report`, `POST /api/internal/quizzes/grade`, 캐릭터 생성 endpoint, Spring DB 직접 접근 추가가 없는지 확인했다. `callbackUrl`은 destination 미사용 테스트 입력에만 존재한다.

### Completion Notes List

- Spring callback env config를 `Settings`에 추가하고 base URL path/query/fragment 거부, callback path absolute URL 거부, timeout positive validation, blank secret normalization을 구현했다.
- `SpringCallbackClient`는 config 기반 report/quiz destination만 사용하며 `Content-Type: application/json`과 `Authorization: Internal <secret>` helper를 한 곳에서 적용한다.
- callback result model과 classification을 추가해 `200`만 success, non-200 `2xx`는 contract mismatch, `4xx`는 non-retryable, `5xx`/timeout/connection error는 retryable로 분류한다.
- report callback payload wrapper는 Story 5 result field와 wrapper metadata를 조립하고 source mapping mutation 및 quiz grading/submission field 혼입을 방지한다.
- quiz grade-result payload helper는 `grade_quiz_answer()` 결과에 `userId`, `quizId`, adapter emotion/statusMessage를 붙이고 `UNGRADED`/failure에서 5필드 zero vector를 강제한다.
- 실제 Spring Boot 서버나 real network 없이 fake transport 기반 integration unittest를 추가했다.
- FastAPI endpoint, SQS consumer/delete/retry/DLQ, Spring Boot DB 접근, 캐릭터 생성 관련 설정/endpoint/client는 추가하지 않았다.

### File List

- `fastapi/app/config.py`
- `fastapi/app/integration/__init__.py`
- `fastapi/app/integration/schemas.py`
- `fastapi/app/integration/spring_client.py`
- `fastapi/app/integration/spring_payloads.py`
- `fastapi/tests/integration/__init__.py`
- `fastapi/tests/integration/test_spring_client.py`
- `fastapi/tests/integration/test_spring_payloads.py`
- `fastapi/docs/integration/stories/integration-1-spring-callback-client-config.md`
- `fastapi/docs/integration/integration-contracts-sprint-status.yaml`

## Change Log

- 2026-06-14: Story context document created and marked ready-for-dev.
- 2026-06-14: BMAD create-story refinement applied for config validation, internal auth header contract, callback client transport abstraction, report wrapper helper, quiz grade-result helper, and fake/mock transport test guardrails.
- 2026-06-14: Implemented Spring callback config/client, payload adapters, fake transport tests, guardrail verification, and marked story ready for review.
