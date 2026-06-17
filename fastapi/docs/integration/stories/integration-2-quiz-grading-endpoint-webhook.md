---
title: Integration 2 - Quiz Grading Endpoint and Webhook
status: done
created: 2026-06-14
updated: 2026-06-14
owner: FastAPI AI 서버
epic: integration-contracts
story_key: integration-2-quiz-grading-endpoint-webhook
depends_on:
  - integration-1-spring-callback-client-config
source_docs:
  - ../integration-contracts-epic.md
  - ../integration-contracts-sprint-status.yaml
  - ./integration-1-spring-callback-client-config.md
  - ../../../app/config.py
  - ../../../app/main.py
  - ../../../app/integration/spring_client.py
  - ../../../app/integration/spring_payloads.py
  - ../../../app/integration/schemas.py
  - ../../../app/scoring/quiz_grader.py
  - ../../../app/scoring/policy.py
  - ../../../tests/integration/test_spring_client.py
  - ../../../tests/integration/test_spring_payloads.py
  - ../../report/report-quiz-scoring-plan.md
  - ../../quiz-bank/quiz-grading-service-plan.md
  - _bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md#4.3
  - _bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md#4.5
  - _bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md#8.2
---

# Integration 2. Quiz Grading Endpoint and Webhook

## Status

done

## Story

As a FastAPI AI 서버 개발자,
I want Spring Boot quiz grading requests to be accepted through `POST /api/internal/quizzes/grade` and completed through the configured Spring Boot grade-result callback,
so that Spring Boot remains the system of record while FastAPI owns AI grading, feedback, and safe callback orchestration.

## Goal

Spring Boot가 quiz submission을 `GRADING` 상태로 저장한 뒤 FastAPI에 보내는 채점 요청을 `202 Accepted`로 수락한다. FastAPI는 endpoint response path에서 LLM 채점을 기다리지 않고 background/orchestration 경로에서 `grade_quiz_answer()`를 호출한 뒤, Story 1에서 만든 Spring callback config/client/payload helper를 재사용해 Spring Boot `POST /api/internal/quizzes/grade-result`로 결과를 보낸다.

이번 story는 FastAPI internal quiz grading endpoint와 quiz grade-result webhook sender 연결만 다룬다. report SQS consumer, report callback, `POST /api/report`, Spring Boot DB 접근, 캐릭터 생성 endpoint는 만들지 않는다.

## Integration Context

Architecture의 흐름 B는 비동기 웹훅이다.

1. Vue가 Spring Boot public API에 quiz answer를 제출한다.
2. Spring Boot가 submission을 저장하고 FastAPI `POST /api/internal/quizzes/grade`를 호출한다.
3. FastAPI는 valid request를 `202 Accepted`로 수락한다.
4. FastAPI background/orchestration 경로가 `grade_quiz_answer()`를 호출한다.
5. FastAPI가 Spring Boot `POST /api/internal/quizzes/grade-result` callback으로 `GRADED` 또는 `UNGRADED` 결과를 보낸다.
6. Spring Boot가 submission 상태, score delta, character stat, emotion/status message를 트랜잭션으로 반영한다.

Core ownership:

- Spring Boot는 System of Record다.
- FastAPI는 Spring Boot DB에 직접 접근하지 않는다.
- FastAPI는 입력을 HTTP request로 받고, 출력은 HTTP response와 Spring Boot Internal API callback으로만 전달한다.
- `submissionId`는 흐름 B의 멱등 키다. FastAPI는 같은 `submissionId`로 callback payload를 만든다.
- report score와 quiz score는 서로 다른 출처다. quiz callback의 `scoreDelta`는 해당 quiz answer grading 결과만 반영한다.

## Previous Story Intelligence

Story 1은 최근 커밋 `8f5d504 feat(fastapi): add spring callback client config`에서 완료됐다. Story 2 구현자는 아래를 새로 만들지 말고 재사용해야 한다.

- `fastapi/app/config.py`
  - `SPRING_BOOT_INTERNAL_BASE_URL`
  - `SPRING_INTERNAL_API_SECRET`
  - `SPRING_REPORT_CALLBACK_PATH`
  - `SPRING_QUIZ_GRADE_RESULT_PATH`
  - `SPRING_CALLBACK_TIMEOUT_SECONDS`
- `fastapi/app/integration/spring_client.py`
  - `SpringCallbackClient`
  - `SpringCallbackClient.post_quiz_grade_result()`
  - `SpringCallbackClient.send_quiz_grade_result()`
  - `build_spring_callback_url()`
  - `build_internal_auth_headers()`
  - fake/mock transport가 가능한 `SpringCallbackTransport`
- `fastapi/app/integration/spring_payloads.py`
  - `build_quiz_grade_result_callback_payload()`
  - `DEFAULT_*` adapter fallback constants
  - `UNGRADED` 또는 `failed_reason`이 있으면 5필드 zero vector를 강제하는 payload guardrail
- `fastapi/app/integration/schemas.py`
  - `SpringCallbackResponse`
  - `SpringCallbackResult`
- `fastapi/tests/integration/test_spring_client.py`
  - fake transport pattern
  - configured destination만 사용하는 callback sender test
  - secret leak 방지 test pattern
- `fastapi/tests/integration/test_spring_payloads.py`
  - quiz callback payload shape, clamp, `UNGRADED` zero vector test pattern

Do not duplicate Story 1 config, URL building, auth header construction, Spring transport, or quiz callback payload adapter in the endpoint layer.

## Existing Code State

- `fastapi/app/main.py` currently exposes only `GET /api/health`; no `app/api` package exists yet.
- `fastapi/app/scoring/quiz_grader.py` exposes `grade_quiz_answer()` with injectable model client support. It accepts both Pythonic and Spring-style parameter names and returns a Spring-facing camelCase dict:
  - `submissionId`
  - `status`
  - `scoreAllocation`
  - `scoreDelta`
  - `feedback`
  - `confidence`
- `grade_quiz_answer()` catches model/parsing failures and returns `status="UNGRADED"` with `zero_score_vector()`.
- `fastapi/app/scoring/policy.py` owns the 5 score fields through `SCORE_FIELDS` and provides:
  - `sanitize_score_allocation()`
  - `clamp_score_delta()`
  - `zero_score_vector()`
- `build_quiz_grade_result_callback_payload()` adds `userId`, `quizId`, `emotion`, `statusMessage`, optional `failedReason`, clamps `GRADED` score delta, removes request-only fields, and forces `UNGRADED`/failure score delta to:

```json
{
  "db": 0,
  "algorithm": 0,
  "cs": 0,
  "network": 0,
  "framework": 0
}
```

## Scope

Implement:

- FastAPI `POST /api/internal/quizzes/grade`.
- Request/response schema validation for the quiz grading request.
- Internal auth guard for the incoming FastAPI endpoint when `SPRING_INTERNAL_API_SECRET` is configured.
- Immediate `202 Accepted` response with `{ "accepted": true, "submissionId": "..." }`.
- Background/orchestration path that calls `grade_quiz_answer()`.
- Spring Boot grade-result callback using Story 1 `SpringCallbackClient` and `build_quiz_grade_result_callback_payload()`.
- `GRADED` success callback mapping.
- `UNGRADED` fallback callback mapping for grader failure, grader `UNGRADED`, and orchestration exception.
- `callbackUrl` validation/ignore policy with observable mismatch signal.
- Fake/mock based endpoint/orchestration tests.

Do not implement:

- FastAPI `POST /api/report`.
- report SQS consumer, SQS delete/retry/DLQ handling, or report callback orchestration. Those are Integration Story 3.
- Spring Boot public quiz submission API.
- Spring Boot DB access, ORM model, repository, or query.
- New quiz generation/recommendation behavior.
- `generate_daily_report_result()` call path.
- Character generation `POST /api/ai/commitgotchi`.
- Real Spring Boot server calls in unit/integration tests.
- Real Gemini API calls in endpoint/webhook tests.
- Duplicate Spring callback config/client/payload helper code.

## Recommended File Structure

Preferred implementation files:

- `fastapi/app/api/__init__.py` (new if package is needed)
- `fastapi/app/api/quiz_grading.py`
- `fastapi/app/main.py`

Optional extraction if `quiz_grading.py` grows too large:

- `fastapi/app/integration/quiz_grading_workflow.py`

Reuse without replacing:

- `fastapi/app/config.py`
- `fastapi/app/integration/spring_client.py`
- `fastapi/app/integration/spring_payloads.py`
- `fastapi/app/integration/schemas.py`
- `fastapi/app/scoring/quiz_grader.py`
- `fastapi/app/scoring/policy.py`

Preferred tests:

- `fastapi/tests/integration/test_quiz_grading_endpoint.py`
- `fastapi/tests/integration/test_quiz_grading_webhook.py`

Keep Story 1 regression tests:

- `fastapi/tests/integration/test_spring_client.py`
- `fastapi/tests/integration/test_spring_payloads.py`

## API Contract

### Incoming Request

FastAPI receives:

```http
POST /api/internal/quizzes/grade
Authorization: Internal <SPRING_INTERNAL_API_SECRET>
Content-Type: application/json
```

Request body:

```json
{
  "submissionId": "quiz-submission-uuid",
  "userId": 1,
  "characterId": 10,
  "quizId": 55,
  "problemId": 1,
  "question": "JPA N+1 문제란 무엇인가?",
  "modelAnswer": "연관 엔티티를 지연 로딩할 때 ...",
  "userAnswer": "쿼리가 N번 더 나가는 문제",
  "scoreAllocation": {
    "db": 0,
    "algorithm": 3,
    "cs": 0,
    "network": 0,
    "framework": 0
  },
  "characterMetadata": {
    "personality": "칭찬을 많이 하지만 틀린 부분은 명확하게 지적하는 성격",
    "currentStats": {
      "db": 120,
      "algorithm": 200,
      "cs": 80,
      "network": 60,
      "framework": 140
    }
  },
  "callbackUrl": "https://spring.example.com/api/internal/quizzes/grade-result"
}
```

Required fields:

- `submissionId`
- `userId`
- `characterId`
- `quizId`
- `question`
- `modelAnswer`
- `userAnswer`
- `scoreAllocation`

Optional fields:

- `problemId`
- `characterMetadata`
- `callbackUrl`
- `difficulty`
- `sourcePath`
- `rubric`

Validation rules:

- `submissionId`, `question`, `modelAnswer`, and `userAnswer` are strings. Empty `userAnswer` is valid and should still be graded by `grade_quiz_answer()`, which returns `GRADED` with zero score.
- `userId`, `characterId`, and `quizId` are positive integers.
- `scoreAllocation` is sanitized to exactly the 5 fields `db`, `algorithm`, `cs`, `network`, `framework`.
- Each score allocation field is clamped or validated to `0..10` using the existing scoring policy. Prefer reusing `sanitize_score_allocation()` rather than duplicating score field logic.
- Unknown extra request fields should either be ignored or rejected consistently with the chosen Pydantic model policy; do not forward unknown fields into the Spring callback payload.

### Accepted Response

Valid request response:

```http
HTTP/1.1 202 Accepted
Content-Type: application/json
```

```json
{
  "accepted": true,
  "submissionId": "quiz-submission-uuid"
}
```

Invalid request response:

- Use FastAPI/Pydantic project-standard validation behavior, normally `422`.
- Auth failure, when secret is configured, should return a generic `401` or `403` without echoing expected token, received token, or full Authorization header.

### Success Callback

FastAPI sends to configured Story 1 destination:

```http
POST {SPRING_BOOT_INTERNAL_BASE_URL}{SPRING_QUIZ_GRADE_RESULT_PATH}
Authorization: Internal <SPRING_INTERNAL_API_SECRET>
Content-Type: application/json
```

Example payload:

```json
{
  "submissionId": "quiz-submission-uuid",
  "userId": 1,
  "quizId": 55,
  "status": "GRADED",
  "scoreAllocation": {
    "db": 0,
    "algorithm": 3,
    "cs": 0,
    "network": 0,
    "framework": 0
  },
  "scoreDelta": {
    "db": 0,
    "algorithm": 2,
    "cs": 0,
    "network": 0,
    "framework": 0
  },
  "feedback": "원인은 맞췄으나 해결책이 빠졌습니다.",
  "emotion": "JOY",
  "statusMessage": "좋아요, 핵심은 잡았어요!"
}
```

### Failure Callback

For `grade_quiz_answer()` returning `UNGRADED`, grader exception, orchestration exception, or other internal failure before grading result assembly:

```json
{
  "submissionId": "quiz-submission-uuid",
  "userId": 1,
  "quizId": 55,
  "status": "UNGRADED",
  "scoreAllocation": {
    "db": 0,
    "algorithm": 3,
    "cs": 0,
    "network": 0,
    "framework": 0
  },
  "scoreDelta": {
    "db": 0,
    "algorithm": 0,
    "cs": 0,
    "network": 0,
    "framework": 0
  },
  "feedback": "Gemini 호출 또는 구조화 출력 변환에 실패하여 채점하지 않았습니다.",
  "emotion": "SAD",
  "statusMessage": "AI가 잠깐 쉬는 중이에요. 답안은 저장됐어요.",
  "failedReason": "GRADER_ERROR"
}
```

Required failure invariant:

- `status="UNGRADED"` or any failure fallback must always use the 5-field zero vector for `scoreDelta`.
- Do not send partial score on failure.
- Do not include exception text, secret values, full request bodies, or Authorization headers in `failedReason`, callback payload, logs, test snapshots, or repr output.

## Callback URL Policy

The request body may contain `callbackUrl` for Spring/FastAPI contract visibility, but it is not the source of truth.

Required behavior:

- Never call request body `callbackUrl`.
- Always send callback through Story 1 config path:
  - `SPRING_BOOT_INTERNAL_BASE_URL`
  - `SPRING_QUIZ_GRADE_RESULT_PATH`
- If `callbackUrl` is present, compare it to the configured destination if doing so is cheap and deterministic.
- If mismatch occurs, still accept the request and still use the configured destination.
- Record an observable warning/metric that includes `submissionId` and mismatch category, but not secrets or full request body.
- Do not add a strict reject mode in this story unless it is a small config flag with tests and defaults to permissive configured-destination behavior.

Reason:

- Calling arbitrary request body URLs creates SSRF risk.
- Story 1 already guarantees callback path cannot be an absolute URL and destination host comes from env-backed config.

## Internal Auth Guardrail

Use the same internal contract Story 1 established:

```http
Authorization: Internal <SPRING_INTERNAL_API_SECRET>
```

Required behavior:

- If `settings.spring_internal_api_secret` is configured, `POST /api/internal/quizzes/grade` must require a matching header.
- If the secret is blank/None, local/dev may allow the request without auth, matching Story 1's local-dev behavior.
- Use constant-time comparison such as `hmac.compare_digest()` when comparing configured and received token values.
- Auth error messages must be generic.
- Never log or return the expected secret, received token, full Authorization header, or `SecretStr.get_secret_value()`.
- Tests must include a wrong-token case and assert the token value is absent from response body, exception text, repr output, and any captured log/snapshot used by the test.

## Orchestration Design

Recommended public helpers:

```python
def accept_quiz_grading_request(
    request: QuizGradingRequest,
    *,
    background_tasks: BackgroundTasks,
    grader: Callable[..., Mapping[str, Any]] = grade_quiz_answer,
    callback_client: SpringCallbackClient | None = None,
) -> QuizGradingAccepted:
    ...


def process_quiz_grading_request(
    request: QuizGradingRequest,
    *,
    grader: Callable[..., Mapping[str, Any]],
    callback_client: SpringCallbackClient,
) -> SpringCallbackResult:
    ...
```

Implementation requirements:

- The route handler schedules work and returns `202`; it must not perform Gemini/LLM work inline before returning.
- Keep the orchestration function pure enough to unit test directly with fake grader and fake callback client.
- Pass these fields to `grade_quiz_answer()`:
  - `submissionId`
  - `problemId`
  - `question`
  - `modelAnswer`
  - `userAnswer`
  - `scoreAllocation`
  - optional `difficulty`
  - optional `sourcePath`
  - optional `rubric`
- Do not pass `userId`, `characterId`, `quizId`, `callbackUrl`, or auth header into `grade_quiz_answer()` unless the scoring function later explicitly supports them.
- Use `SpringCallbackClient.send_quiz_grade_result()` or `post_quiz_grade_result()` plus `build_quiz_grade_result_callback_payload()`. Prefer `send_quiz_grade_result()` unless tests need lower-level payload control.
- Do not manually rebuild callback destination URL in the endpoint layer.
- Do not copy Story 1 fallback constants into the endpoint layer.
- If `SpringCallbackResult.ok` is false, log/metric a generic callback failure with `submissionId`, `status_code`, `retryable`, and safe error category. Do not alter the already returned `202`.
- Durable retry queue is outside this story. A small bounded in-process retry may be added only if it stays fake-transport testable and does not introduce SQS or DB state.

Note on `BackgroundTasks` tests:

- FastAPI/Starlette `TestClient` may execute background tasks before returning control to the test process. Do not use that behavior as proof that production waits for grading.
- Prove non-inline behavior with dependency injection/spies where the route schedules work instead of directly invoking the grader in the response path, and unit test `process_quiz_grading_request()` separately.

## Acceptance Criteria

1. FastAPI exposes `POST /api/internal/quizzes/grade`.
2. `fastapi/app/main.py` includes the quiz grading router without breaking `GET /api/health`.
3. Valid request returns HTTP `202 Accepted`.
4. Accepted response body contains `accepted=true` and the same `submissionId`.
5. Invalid request schema returns FastAPI/Pydantic validation error, normally `422`.
6. If `SPRING_INTERNAL_API_SECRET` is configured, missing or invalid `Authorization: Internal <token>` is rejected with generic `401` or `403`.
7. Auth failure response/log/test output does not include the configured secret, received token, or full Authorization header.
8. If `SPRING_INTERNAL_API_SECRET` is blank/None, local/dev requests can be accepted without auth.
9. Endpoint response path does not wait for Gemini/LLM grading.
10. Background/orchestration path calls `grade_quiz_answer()`.
11. Grader call includes `submissionId`, `problemId`, `question`, `modelAnswer`, `userAnswer`, and sanitized `scoreAllocation`.
12. `scoreAllocation` is normalized to exactly `db`, `algorithm`, `cs`, `network`, `framework`.
13. `scoreAllocation` fields are constrained to `0..10`.
14. Successful `GRADED` result sends Spring Boot grade-result callback.
15. Success callback status is `GRADED`.
16. Success callback `scoreDelta[field]` is clamped to `0..scoreAllocation[field]`.
17. Callback payload includes `submissionId`, `userId`, `quizId`, `status`, `scoreAllocation`, `scoreDelta`, `feedback`, `emotion`, and `statusMessage`.
18. `grade_quiz_answer()` returning `UNGRADED` sends Spring Boot callback.
19. Grader/orchestration exception sends Spring Boot callback when enough request metadata exists to do so.
20. `UNGRADED` callback status is `UNGRADED`.
21. `UNGRADED` callback `scoreDelta` is exactly the 5-field zero vector.
22. Any failure fallback callback `scoreDelta` is exactly the 5-field zero vector.
23. Failure payload may include `failedReason`, but it must be a safe category string, not raw exception text.
24. Request body `callbackUrl` is never used as callback destination.
25. Callback destination always comes from Story 1 config: `SPRING_BOOT_INTERNAL_BASE_URL + SPRING_QUIZ_GRADE_RESULT_PATH`.
26. Mismatched `callbackUrl` does not reject MVP request and does not change destination.
27. Mismatched `callbackUrl` produces an observable warning/metric without secret or full request body leakage.
28. Callback sender tests use fake/mock transport or fake callback client; unit tests never call a real Spring Boot server.
29. Endpoint/webhook tests use fake grader/model client; unit tests never call real Gemini.
30. Callback sender failure after accepted request does not roll back or alter the accepted response.
31. Callback failure handling does not expose secret/token, full Authorization header, or raw request body in logs/errors.
32. FastAPI does not access Spring Boot DB, tables, repositories, or ORM models.
33. FastAPI does not create `POST /api/report`.
34. FastAPI does not create report SQS consumer or report callback orchestration.
35. FastAPI does not create or modify character generation `POST /api/ai/commitgotchi`.
36. Existing Story 1 tests continue to pass.
37. Existing `tests.scoring.test_quiz_grader` continues to pass.

## Tasks / Subtasks

- [x] Create quiz grading request/response schemas in the API layer (AC: 3, 4, 5, 11, 12, 13)
- [x] Add internal auth dependency/helper for `Authorization: Internal <secret>` with no secret leakage (AC: 6, 7, 8, 31)
- [x] Add `POST /api/internal/quizzes/grade` router (AC: 1)
- [x] Include router in `fastapi/app/main.py` while preserving health endpoint (AC: 2)
- [x] Implement accepted response path with `202 Accepted` and background scheduling (AC: 3, 4, 9)
- [x] Implement testable `process_quiz_grading_request()` orchestration with injected grader and callback client (AC: 10, 11, 28, 29)
- [x] Map `GRADED` results through Story 1 callback sender/payload helper (AC: 14, 15, 16, 17, 25)
- [x] Map `UNGRADED` grader results through Story 1 callback sender/payload helper (AC: 18, 20, 21)
- [x] Map orchestration exceptions to safe `UNGRADED` callback payloads when metadata is available (AC: 19, 22, 23)
- [x] Implement `callbackUrl` configured-destination policy and safe mismatch signal (AC: 24, 25, 26, 27)
- [x] Handle `SpringCallbackResult.ok == False` without mutating accepted response or leaking secrets (AC: 30, 31)
- [x] Add fake/mock endpoint and webhook orchestration tests (AC: 1-31)
- [x] Run Story 1 and quiz grader regression tests (AC: 36, 37)
- [x] Guardrail check with `rg`: no FastAPI `POST /api/report`, no report SQS consumer, no Spring DB access, no character generation changes (AC: 32, 33, 34, 35)

## Test Requirements

Required tests:

- Valid request returns `202` and `{ "accepted": true, "submissionId": ... }`.
- Invalid request returns validation error.
- Missing/invalid internal auth is rejected when secret is configured.
- Blank/None internal secret allows local/dev request if that policy is implemented.
- Auth failure output does not include the expected secret, received token, or Authorization header.
- Route schedules background/orchestration work instead of calling the grader inline.
- `process_quiz_grading_request()` passes the expected fields to fake grader.
- Fake grader `GRADED` result produces fake callback payload with `GRADED`, same `submissionId`, `userId`, `quizId`, sanitized `scoreAllocation`, clamped `scoreDelta`, `feedback`, `emotion`, `statusMessage`.
- Fake grader `UNGRADED` result produces callback payload with `UNGRADED` and exact 5-field zero vector.
- Fake grader exception produces callback payload with `UNGRADED`, exact 5-field zero vector, and safe `failedReason`.
- Malicious or mismatched request `callbackUrl` is not used as sender destination.
- `callbackUrl` mismatch emits safe warning/metric.
- Callback client failure does not change already accepted response behavior and does not leak secret/token.
- No test calls real Spring Boot.
- No endpoint/webhook test calls real Gemini.
- Existing Story 1 fake transport tests still pass.
- Existing quiz grader tests still pass.

Recommended commands:

```bash
cd fastapi
.venv/bin/python -m unittest \
  tests.integration.test_quiz_grading_endpoint \
  tests.integration.test_quiz_grading_webhook \
  tests.integration.test_spring_client \
  tests.integration.test_spring_payloads \
  tests.scoring.test_quiz_grader
```

Fallback if `.venv` is not available:

```bash
cd fastapi
python3 -m unittest \
  tests.integration.test_quiz_grading_endpoint \
  tests.integration.test_quiz_grading_webhook \
  tests.integration.test_spring_client \
  tests.integration.test_spring_payloads \
  tests.scoring.test_quiz_grader
```

Guardrail verification:

```bash
rg -n "post\\(['\"]/?api/report|/api/report|report_consumer|ReceiveMessage|boto3|sqlalchemy|quiz_submissions|POST /api/ai/commitgotchi|callbackUrl" fastapi/app fastapi/tests
```

Interpretation:

- `/api/report` should appear only as Story 1 callback config/path usage, not as a FastAPI route.
- `callbackUrl` may appear in request schema/tests, but sender destination must remain config-based.
- SQLAlchemy imports in existing `app/main.py`/`app/db.py` health DB code are pre-existing and not Spring Boot DB access. New quiz grading code must not import DB/repository modules.

## Dev Agent Guardrails

- Reuse Story 1. Do not write a second callback client.
- Reuse scoring policy. Do not hardcode a second score field list unless it imports from the existing policy/schema source.
- Keep `grade_quiz_answer()` focused on grading. Do not put callback URL, auth, or Spring transport concerns into `fastapi/app/scoring/quiz_grader.py`.
- Keep the API layer thin: validate, auth-check, schedule, return accepted.
- Keep callback payload assembly in Story 1 helper or a tiny wrapper around it.
- Do not log raw request bodies. `userAnswer` can contain user-written content and auth header can contain a secret.
- Use safe error categories such as `GRADER_ERROR`, `GRADER_UNGRADED`, `CALLBACK_RETRYABLE_FAILURE`, `CALLBACK_NON_RETRYABLE_FAILURE`. Do not serialize exception messages into API responses or callback payload.
- If callback fails, expose operational signal only with safe metadata: `submissionId`, `status_code`, `retryable`, and sanitized category.
- Do not add SQS, DB persistence, or durable retry state in this story.
- Do not update report flow files unless required by import path/package setup, and then keep changes mechanical.

## Completion Definition

This story is ready for review when:

- The endpoint exists and returns `202` for valid requests.
- The endpoint schedules grading/callback work without inline Gemini work.
- `GRADED` and `UNGRADED` callbacks are sent through Story 1 config/client/helper.
- `callbackUrl` cannot redirect the callback destination.
- `UNGRADED` and all failure fallbacks use exact 5-field zero score delta.
- Secret/token values do not appear in errors, reprs, logs, callback payloads, or test snapshots.
- Tests cover fake grader, fake/mock callback transport/client, auth guard, callback URL guardrail, and Story 1 regressions.
- Guardrail search confirms no FastAPI `POST /api/report`, no report SQS consumer, no Spring Boot DB access, and no character generation endpoint changes.

## Dev Agent Record

### Agent Model Used

GPT-5 Codex

### Implementation Plan

- RED: endpoint/webhook 테스트를 먼저 추가해 `app.api.quiz_grading` 부재 실패를 확인했다.
- GREEN: `app/api/quiz_grading.py`에 Pydantic request/accepted response schema, internal auth dependency, router, background scheduling helper, `process_quiz_grading_request()` orchestration을 구현했다.
- REFACTOR: callback URL mismatch warning은 safe metadata만 남기고, callback failure logging도 `submissionId`, `status_code`, `retryable`, safe category만 남기도록 정리했다.

### Debug Log References

- `.venv/bin/python -m unittest tests.integration.test_quiz_grading_endpoint tests.integration.test_quiz_grading_webhook` - 최초 RED: `app.api` 없음으로 실패, 구현 후 13 tests OK.
- `.venv/bin/python -m unittest tests.integration.test_quiz_grading_endpoint tests.integration.test_quiz_grading_webhook tests.integration.test_spring_client tests.integration.test_spring_payloads tests.scoring.test_quiz_grader` - 39 tests OK.
- `.venv/bin/python -m unittest discover` - 132 tests OK.
- `rg -n "post\\(['\"]/?api/report|/api/report|report_consumer|ReceiveMessage|boto3|sqlalchemy|quiz_submissions|POST /api/ai/commitgotchi|callbackUrl" fastapi/app fastapi/tests` - 허용된 Story 1 config/test `/api/report`, 기존 health DB import, callbackUrl schema/tests만 확인.

### Completion Notes List

- FastAPI `POST /api/internal/quizzes/grade` endpoint가 valid request에 `202 Accepted`와 `{accepted: true, submissionId}`를 반환한다.
- endpoint response path는 grader를 inline 호출하지 않고 `BackgroundTasks`에 orchestration을 등록한다.
- orchestration은 fake 주입 가능한 `process_quiz_grading_request()`로 분리했고, `grade_quiz_answer()`에 request metadata/auth/callbackUrl 없이 grading 필드만 전달한다.
- `GRADED`, `UNGRADED`, grader exception fallback 모두 Story 1 `send_quiz_grade_result()`/payload helper를 통해 Spring callback payload로 매핑한다.
- request `callbackUrl`은 destination으로 쓰지 않고 config destination과 비교해 safe mismatch warning만 남긴다.
- callback failure는 accepted response를 변경하지 않으며 secret/token/raw body를 로그나 payload에 싣지 않는다.

### File List

- fastapi/app/api/__init__.py
- fastapi/app/api/quiz_grading.py
- fastapi/app/main.py
- fastapi/tests/integration/test_quiz_grading_endpoint.py
- fastapi/tests/integration/test_quiz_grading_webhook.py
- fastapi/docs/integration/integration-contracts-sprint-status.yaml
- fastapi/docs/integration/stories/integration-2-quiz-grading-endpoint-webhook.md

## Change Log

- 2026-06-14: Story context document created and left in backlog.
- 2026-06-14: BMAD create-story refinement applied; Story 1 reuse, FastAPI internal endpoint/webhook scope, auth/secret guardrails, callbackUrl policy, zero-vector failure invariant, fake/mock test requirements, and explicit out-of-scope report/SQS/DB/character work added. Status set to ready-for-dev.
- 2026-06-14: Implemented FastAPI quiz grading endpoint, internal auth guard, background orchestration, Story 1 callback reuse, callbackUrl guardrail, failure zero-vector mapping, fake endpoint/webhook tests, and regression/guardrail verification. Status set to review.
