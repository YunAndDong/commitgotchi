---
title: Integration 2 - Quiz Grading Endpoint and Webhook
status: backlog
created: 2026-06-14
owner: FastAPI AI 서버
epic: integration-contracts
story_key: integration-2-quiz-grading-endpoint-webhook
source_docs:
  - ../integration-contracts-epic.md
  - ../integration-contracts-sprint-status.yaml
  - ./integration-1-spring-callback-client-config.md
  - ../../report/report-generation-epic.md
  - _bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md#4.3
  - _bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md#8.2
---

# Integration 2. Quiz Grading Endpoint and Webhook

## Status

backlog

## Story

As a FastAPI AI 서버 개발자,
I want Spring Boot quiz grading requests to be accepted through `POST /api/internal/quizzes/grade` and completed through a Spring Boot grade-result callback,
so that quiz submission storage remains in Spring Boot while FastAPI owns AI grading and feedback.

## 목표

Spring Boot가 quiz submission을 `GRADING` 상태로 저장한 뒤 FastAPI에 보낸 grading request를 수락하고, FastAPI 내부 `grade_quiz_answer()` 결과를 Spring Boot `POST /api/internal/quizzes/grade-result` callback으로 전송한다.

요청 수락 시 FastAPI는 `202 Accepted`를 즉시 반환한다. 채점은 background 또는 비동기 작업으로 수행한다.

채점 실패, LLM timeout, validation 후 내부 처리 실패가 발생하면 Spring Boot에는 `UNGRADED`, `scoreDelta=0` callback을 보낸다.

캐릭터 생성 endpoint는 이번 story에서 제외한다.

## 배경

Architecture의 흐름 B는 비동기 webhook이다.

1. 사용자가 Spring Boot public API에 quiz 답안을 제출한다.
2. Spring Boot가 submission을 저장하고 FastAPI `POST /api/internal/quizzes/grade`를 호출한다.
3. FastAPI는 요청을 수락한 뒤 AI 채점을 수행한다.
4. FastAPI가 Spring Boot `POST /api/internal/quizzes/grade-result`로 결과를 callback한다.
5. Spring Boot가 submission 상태, 점수, 캐릭터 stat, 감정/상태 메시지를 트랜잭션으로 반영한다.

기존 내부 함수 `grade_quiz_answer()`는 `submissionId`, `scoreAllocation`, `scoreDelta`, `feedback`, `confidence`를 포함한 camelCase 결과를 반환한다. 이 story는 그 내부 함수를 HTTP endpoint와 Spring Boot callback sender에 연결한다.

중요한 계약:

- FastAPI가 새로 만드는 endpoint는 `POST /api/internal/quizzes/grade`다.
- Spring Boot가 받는 callback endpoint는 `POST /api/internal/quizzes/grade-result`다.
- request body의 `callbackUrl`은 MVP에서 destination source of truth가 아니다.
- 실제 callback destination은 Story 1 config인 `SPRING_BOOT_INTERNAL_BASE_URL + SPRING_QUIZ_GRADE_RESULT_PATH`로 만든다.
- request body `callbackUrl`은 협업 계약 확인용으로 받을 수 있지만, SSRF를 피하기 위해 그대로 호출하지 않는다.
- FastAPI는 Spring Boot DB에 직접 접근하지 않는다.
- 캐릭터 생성 `POST /api/ai/commitgotchi`는 제외한다.

## 구현 범위

이번 story에서 구현할 것은 quiz grading 수락 endpoint와 grade-result callback orchestration이다.

1. FastAPI router
   - `POST /api/internal/quizzes/grade`를 추가한다.
   - 권장 파일: `fastapi/app/api/quiz_grading.py` 또는 기존 app 구조에 맞는 router 파일.
   - `fastapi/app/main.py`에 router를 include한다.
   - endpoint는 valid request를 받으면 `202 Accepted`를 반환한다.

2. Request schema validation
   - required field:
     - `submissionId`
     - `userId`
     - `characterId`
     - `quizId`
     - `question`
     - `modelAnswer`
     - `userAnswer`
     - `scoreAllocation`
   - optional field:
     - `problemId`
     - `characterMetadata`
     - `callbackUrl`
   - `scoreAllocation`은 `db`, `algorithm`, `cs`, `network`, `framework` 5개 field를 `0..10` 범위로 sanitize한다.

3. Immediate response
   - valid request는 `202 Accepted`와 아래 body를 반환한다.

```json
{
  "accepted": true,
  "submissionId": "quiz-submission-uuid"
}
```

4. Background grading orchestration
   - endpoint thread에서 실제 LLM/API 호출을 기다리지 않는다.
   - FastAPI `BackgroundTasks`, internal task runner, or async service로 분리한다.
   - orchestration 함수는 `grade_quiz_answer()`를 호출한다.
   - tests에서는 fake grader와 fake callback sender를 주입할 수 있어야 한다.

5. Success callback
   - `grade_quiz_answer()`가 `GRADED`를 반환하면 Spring Boot grade-result callback을 보낸다.
   - `scoreDelta[field]`는 `0..scoreAllocation[field]` 범위를 넘지 않아야 한다. 내부 함수의 clamp 결과를 신뢰하되 adapter에서 shape를 보존한다.
   - callback payload는 `submissionId`, `userId`, `quizId`, `status`, `scoreAllocation`, `scoreDelta`, `feedback`, `emotion`, `statusMessage`를 포함한다.

6. Failure callback
   - `grade_quiz_answer()`가 `UNGRADED`를 반환하거나 orchestration이 예외를 만나면 callback payload는 `status="UNGRADED"`다.
   - `scoreDelta`는 전 필드 0이다.
   - `feedback`은 null 또는 내부 함수의 failure feedback을 사용할 수 있다.
   - `emotion`은 null을 허용한다.
   - `statusMessage`는 사용자에게 저장은 되었지만 채점은 실패했음을 설명하는 보수적 메시지다.
   - `failedReason`은 enum/string으로 제한한다. 예: `LLM_TIMEOUT`, `GRADER_ERROR`, `CALLBACK_ERROR`.

7. `callbackUrl` 정책
   - MVP destination은 env/config가 source of truth다.
   - request body의 `callbackUrl`은 그대로 호출하지 않는다.
   - `callbackUrl`이 있으면 configured grade-result URL과 scheme/host/path가 일치하는지 검증할 수 있다.
   - mismatch가 있어도 MVP에서는 request를 202로 수락하고 configured destination으로 callback한다. mismatch는 warning/metric으로 남긴다.
   - strict reject가 필요하면 후속 config flag로 분리한다.

8. Callback retry policy
   - Story 1 sender가 retryable/non-retryable classification을 제공한다.
   - MVP endpoint는 callback 전송 실패 시 적어도 log/metric을 남긴다.
   - durable retry queue는 이번 story 범위 밖이다.

## 주요 파일 경로

구현 후보:

- `fastapi/app/main.py`
- `fastapi/app/api/__init__.py`
- `fastapi/app/api/quiz_grading.py`
- `fastapi/app/integration/spring_client.py`
- `fastapi/app/integration/schemas.py`
- `fastapi/app/scoring/quiz_grader.py`
- `fastapi/app/scoring/schemas.py`

테스트 후보:

- `fastapi/tests/integration/test_quiz_grading_endpoint.py`
- `fastapi/tests/integration/test_quiz_grading_webhook.py`

재사용 대상:

- `fastapi/app/scoring/quiz_grader.py`의 `grade_quiz_answer()`
- Story 1의 `SpringCallbackClient.post_quiz_grade_result()`
- Story 1의 Spring callback config

수정하지 않아야 할 흐름:

- `fastapi/app/scoring/daily_report_service.py`
- report SQS consumer
- report callback sender
- character generation endpoint/client
- Spring Boot DB 직접 접근

## 권장 Public API 또는 Schema

요청:

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
    "currentStats": { "db": 120, "algorithm": 200, "cs": 80, "network": 60, "framework": 140 }
  },
  "callbackUrl": "https://spring.example.com/api/internal/quizzes/grade-result"
}
```

수락 응답:

```json
{
  "accepted": true,
  "submissionId": "quiz-submission-uuid"
}
```

성공 callback:

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

실패 callback:

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
  "feedback": null,
  "emotion": null,
  "statusMessage": "AI가 잠깐 쉬는 중입니다. 답안은 저장됐고, 잠시 후 다시 채점할 수 있어요.",
  "failedReason": "GRADER_ERROR"
}
```

권장 orchestration helper:

```python
def accept_quiz_grading_request(
    request: QuizGradingRequest,
    *,
    grader: Callable[..., Mapping[str, Any]] = grade_quiz_answer,
    callback_client: SpringCallbackClient,
) -> QuizGradingAccepted:
    ...


def process_quiz_grading_request(
    request: QuizGradingRequest,
    *,
    grader: Callable[..., Mapping[str, Any]],
    callback_client: SpringCallbackClient,
) -> None:
    ...
```

## Acceptance Criteria

1. FastAPI에 `POST /api/internal/quizzes/grade` endpoint가 추가된다.
2. valid request는 `202 Accepted`를 반환한다.
3. accepted response body는 `accepted=true`와 동일 `submissionId`를 포함한다.
4. endpoint는 request validation 실패 시 `422` 또는 project-standard validation error를 반환한다.
5. endpoint는 request를 수락한 뒤 endpoint response path에서 LLM 채점을 기다리지 않는다.
6. background/orchestration 경로는 `grade_quiz_answer()`를 호출한다.
7. `grade_quiz_answer()` 호출에는 `submissionId`, `problemId`, `question`, `modelAnswer`, `userAnswer`, `scoreAllocation`이 전달된다.
8. `scoreAllocation`은 5개 score field를 유지한다.
9. grading 성공 시 Spring Boot grade-result callback이 전송된다.
10. grading 성공 callback status는 `GRADED`다.
11. grading 성공 callback의 `scoreDelta[field]`는 `0..scoreAllocation[field]` 범위다.
12. grading 실패 또는 exception 시 Spring Boot callback이 전송된다.
13. grading 실패 callback status는 `UNGRADED`다.
14. grading 실패 callback `scoreDelta`는 전 필드 0이다.
15. callback payload에는 `submissionId`, `userId`, `quizId`, `status`, `scoreAllocation`, `scoreDelta`가 포함된다.
16. request body의 `callbackUrl`은 callback destination으로 직접 사용하지 않는다.
17. callback destination은 Story 1 config `SPRING_BOOT_INTERNAL_BASE_URL + SPRING_QUIZ_GRADE_RESULT_PATH`다.
18. `callbackUrl`이 configured destination과 불일치해도 MVP에서는 configured destination을 사용한다.
19. `callbackUrl` mismatch는 warning/metric 또는 equivalent observable signal로 남긴다.
20. endpoint와 callback orchestration은 Spring Boot DB에 직접 접근하지 않는다.
21. report SQS consumer나 report callback sender를 만들지 않는다.
22. 캐릭터 생성 `POST /api/ai/commitgotchi` endpoint를 만들거나 수정하지 않는다.
23. unit test는 fake grader와 fake callback sender를 사용해 real Gemini/Spring Boot 호출 없이 통과한다.
24. callback sender failure는 endpoint accepted response를 되돌리지 않는다.

## 테스트 기준

필수 테스트:

- valid request가 `202 Accepted`와 `{accepted: true, submissionId}`를 반환하는지 검증한다.
- invalid request가 validation error를 반환하는지 검증한다.
- endpoint response가 fake grader 완료를 기다리지 않는 구조인지 검증한다.
- fake grader가 `GRADED`를 반환하면 fake callback sender가 성공 payload를 받는지 검증한다.
- fake grader가 `UNGRADED`를 반환하면 fake callback sender가 `UNGRADED`, 전 필드 0 `scoreDelta` payload를 받는지 검증한다.
- fake grader가 exception을 던져도 fake callback sender가 `UNGRADED` fallback payload를 받는지 검증한다.
- callback payload의 `scoreDelta`가 `scoreAllocation`을 넘지 않는지 검증한다.
- request body의 `callbackUrl`이 악성 외부 URL이어도 sender destination으로 사용되지 않는지 검증한다.
- configured destination과 `callbackUrl` mismatch가 observable warning/metric으로 남는지 검증한다.
- Spring Boot DB 접근 import 또는 repository 호출이 없는지 확인한다.
- report SQS/report callback/character generation 파일이 생성 또는 수정되지 않았는지 확인한다.

권장 테스트 명령:

```bash
cd fastapi
python3 -m unittest tests.integration.test_quiz_grading_endpoint tests.integration.test_quiz_grading_webhook
```

관련 회귀 확인:

```bash
cd fastapi
python3 -m unittest tests.scoring.test_quiz_grader tests.integration.test_spring_client
```

## 제외 범위

이번 story에서 하지 않는다.

- Spring Boot public quiz submission API 구현
- Spring Boot DB 접근
- durable callback retry queue 구현
- report SQS consumer
- Spring Boot `POST /api/report` callback
- `generate_daily_report_result()` 호출
- 신규 quiz 생성 또는 추천
- 캐릭터 생성 `POST /api/ai/commitgotchi`
- 실제 Spring Boot 서버 호출 테스트
- 실제 Gemini API 호출을 endpoint unit test에서 수행

## Dev Notes

- `grade_quiz_answer()`는 이미 Spring-facing camelCase dict를 반환한다. endpoint layer는 이 결과를 Spring Boot grade-result callback shape로 보강하는 adapter 역할을 한다.
- `grade_quiz_answer()` fallback은 `status="UNGRADED"`와 zero score vector를 반환한다. orchestration exception도 같은 contract로 맞춘다.
- `characterMetadata`는 현재 `grade_quiz_answer()` 입력에 직접 쓰이지 않을 수 있다. request schema에는 보존하고, 향후 prompt/context 확장 여지를 둔다.
- `callbackUrl`을 그대로 호출하면 SSRF 위험이 있다. MVP에서는 env/config destination만 사용한다.
- `BackgroundTasks`는 process restart 시 durable하지 않다. MVP 수락 가능 여부는 팀 합의가 필요하다. durable retry/worker가 필요하면 후속 story로 분리한다.
- callback sender failure를 어떻게 재시도할지는 Story 1 result classification 위에 후속 정책을 얹는다. 이번 story는 accepted response 후 callback attempt와 failure visibility까지를 기본 범위로 둔다.
- FastAPI `POST /api/report`를 만들면 architecture 계약 위반이다.

## Tasks/Subtasks

- [ ] quiz grading request/response schema 구현 (AC: 2, 3, 4, 8)
- [ ] `POST /api/internal/quizzes/grade` router 추가 (AC: 1)
- [ ] `main.py`에 router include (AC: 1)
- [ ] accepted response `202` 처리 구현 (AC: 2, 3, 5)
- [ ] background/orchestration service 구현 (AC: 5, 6, 7)
- [ ] `grade_quiz_answer()` 성공 결과 callback mapping 구현 (AC: 9, 10, 11, 15)
- [ ] `UNGRADED` fallback callback mapping 구현 (AC: 12, 13, 14, 15)
- [ ] env/config destination 사용 연결 (AC: 16, 17, 18)
- [ ] callbackUrl validation/ignore warning 구현 (AC: 18, 19)
- [ ] fake grader/fake callback sender endpoint tests 작성 (AC: 23, 24)
- [ ] Spring DB/report/character generation 범위 침범이 없는지 확인 (AC: 20, 21, 22)

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
