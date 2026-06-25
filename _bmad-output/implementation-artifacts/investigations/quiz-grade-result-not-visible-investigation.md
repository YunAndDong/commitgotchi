# Investigation: Quiz Grade Result Not Visible

## Hand-off Brief

1. **What happened.** 브라우저에서 답변이 저장되어 textarea는 비활성화됐지만 배지는 `미제출`이고 결과/로딩/재시도 UI가 모두 비는 상태를 확인했다.
2. **Where the case stands.** 원인은 두 갈래로 확인됐다: 프론트 정규화가 `grading` 필드를 버려 채점 중 상태를 숨기고, 기존 DB에는 같은 `submissionId`가 다른 캐릭터 퀴즈에 묶인 잔여 상태가 있다.
3. **What's needed next.** 프론트 `normalizeQuiz()`에 `grading`을 보존하고, 이미 꼬인 로컬/운영 데이터는 `submitted=true, scored=false, grading=true` 장기 체류 및 cross-character shared submissionId를 정리한다.

## Case Info

| Field            | Value                                                                 |
| ---------------- | --------------------------------------------------------------------- |
| Ticket           | N/A                                                                   |
| Date opened      | 2026-06-25                                                            |
| Status           | Active                                                                |
| System           | macOS Darwin 25.5.0 arm64, repository workspace |
| Evidence sources | User report, source code, version control, local browser verification |

## Problem Statement

사용자 보고: "퀴즈 답변을 제출하면, 결과가 도착했다는 알림은 나오는데, 채점된 내용은 안보임."

## Evidence Inventory

| Source | Status | Notes |
| ------ | ------ | ----- |
| User report | Available | 알림 도착과 채점 내용 미표시가 동시에 관찰됨. |
| Source code | Available | `QuizView.vue`, `game.js`, `QuizGradeResultService.java`, 관련 테스트 추적 완료. |
| Runtime/browser reproduction | Available | 로컬 브라우저에서 답변 잠김 + `미제출` + 결과 UI 없음 재현. |
| Backend logs/API traces | Partial | FastAPI 콜백 `400` 실패 흔적과 DB 상태 확인. |

## Investigation Backlog

| # | Path to Explore | Priority | Status | Notes |
| - | --------------- | -------- | ------ | ----- |
| 1 | 퀴즈 제출 UI와 결과 렌더링 상태 추적 | High | Done | `normalizeQuiz()`가 `grading`을 누락. |
| 2 | 백엔드 채점 결과 생성/조회 API 추적 | High | Done | 현재 수정본은 characterId + submissionId/quizId 매칭 테스트 통과. |
| 3 | 알림/이벤트 처리 경로 추적 | Medium | Done | `quiz.graded` 이벤트가 알림과 snapshot 적용을 함께 수행. |
| 4 | 로컬 브라우저 재현 | Medium | Done | 캐시 무효화 URL에서 DOM 상태 확인. |

## Timeline of Events

| Time | Event | Source | Confidence |
| ---- | ----- | ------ | ---------- |
| 2026-06-25 01:12 KST | Investigation opened | Local clock | Confirmed |
| 2026-06-25 01:15 KST | Browser reproduced blank quiz result state | In-app browser DOM | Confirmed |
| 2026-06-25 01:16 KST | Backend grade-result contract test passed | Gradle test output | Confirmed |

## Confirmed Findings

### Finding 1: Frontend drops the backend `grading` field during quiz normalization

**Evidence:** `vue/src/stores/game.js:218`, `vue/src/stores/game.js:233`, `vue/src/stores/game.js:236`, `vue/src/stores/game.js:237`; built bundle `vue/dist/assets/index-DEZlrcuu.js` contains the same normalized quiz shape without `grading`.

**Detail:** The backend stores and returns `grading`, and `QuizView` already reads `q.grading`, but `normalizeQuiz()` never copies `raw.grading`. After a state refresh, `q.grading` becomes `undefined`.

### Finding 2: The quiz UI has no visible state for `submitted=true, scored=false, gradeFailed=false, grading=false/undefined`

**Evidence:** `vue/src/views/QuizView.vue:129`, `vue/src/views/QuizView.vue:132`, `vue/src/views/QuizView.vue:133`, `vue/src/views/QuizView.vue:147`, `vue/src/views/QuizView.vue:151`, `vue/src/views/QuizView.vue:159`, `vue/src/views/QuizView.vue:170`, `vue/src/views/QuizView.vue:172`, `vue/src/views/QuizView.vue:177`

**Detail:** In that state the badge falls through to `미제출`, the textarea is disabled because `q.submitted` is true, the submit button is hidden because `!q.submitted` is false, and neither loading nor result nor fallback blocks render.

### Finding 3: Backend marks async submissions as `grading=true`

**Evidence:** `springboot/src/main/java/com/commitgotchi/game/application/GameService.java:317`, `springboot/src/main/java/com/commitgotchi/game/application/GameService.java:321`, `springboot/src/main/java/com/commitgotchi/game/application/GameService.java:353`

**Detail:** When FastAPI grading is enabled, Spring Boot assigns a submission id, sets `grading=true`, saves the state, and returns that state to the frontend.

### Finding 4: Existing local DB has a cross-character shared submission artifact

**Evidence:** Local Postgres `game_states` row for a test user showed `q102` on character 9 as `scored=true` and `q105` on character 8 as `submitted=true, scored=false, grading=true`, both with the same submission id.

**Detail:** This explains the user-observed split where a result-arrival notice can exist while the currently viewed character's quiz remains ungraded/stuck.

### Finding 5: Current backend source has a targeted fix for quiz-result misapplication and its contract test passes

**Evidence:** `springboot/src/main/java/com/commitgotchi/quiz/application/QuizGradeResultService.java:172`, `springboot/src/main/java/com/commitgotchi/quiz/application/QuizGradeResultService.java:183`, `springboot/src/main/java/com/commitgotchi/quiz/application/QuizGradeResultService.java:200`; `./gradlew test --tests com.commitgotchi.quiz.QuizGradeResultContractIntegrationTest` passed on 2026-06-25 01:16 KST.

**Detail:** The current code first matches by `submissionId` within the requested `characterId`, then falls back to `quizId` only within the same character and with compatible stored submission id.

## Deduced Conclusions

### Deduction 1: The immediate blank UI is caused by dropping `grading` in the frontend state boundary

**Based on:** Findings 1, 2, and 3

**Reasoning:** Spring returns `grading=true`; `mutate()` applies `response.state`; `applyGameState()` maps quizzes through `normalizeQuiz()`; `normalizeQuiz()` omits `grading`; then `QuizView` sees a submitted but not scored quiz with no grading flag and renders no useful state.

**Conclusion:** Even when the backend is correctly waiting for FastAPI, the UI can look like "nothing happened" after submission.

### Deduction 2: The "result arrived" notice can be for a different quiz/character than the stuck quiz currently visible

**Based on:** Findings 4 and 5

**Reasoning:** The local DB contains two character-scoped quizzes sharing one submission id, where one is scored and another remains grading. The scored quiz can trigger study/character result notices while the visible character's quiz remains unscored.

**Conclusion:** The user's exact "notification arrived but graded content absent" report is consistent with a combined old data/backend matching artifact plus the frontend grading-state omission.

## Hypothesized Paths

### Hypothesis 1: Notification arrives but quiz result state is not refreshed or bound

**Status:** Confirmed

**Theory:** 제출 후 알림 이벤트는 성공적으로 표시되지만, 채점 결과 조회 또는 결과 상태 업데이트가 누락되어 화면에 채점 내용이 렌더링되지 않는다.

**Supporting indicators:** 사용자 보고상 "결과 도착" 알림과 "채점 내용 미표시"가 분리되어 있다.

**Would confirm:** 제출 후 결과 API 호출이 없거나, 응답을 받아도 `QuizView.vue` 상태에 반영되지 않는 코드 증거.

**Would refute:** 결과 API 호출과 상태 반영은 정상이고, 실제 원인이 CSS/조건부 렌더링/데이터 스키마 불일치임을 보여주는 증거.

**Resolution:** Confirmed by `normalizeQuiz()` omitting `grading`, the UI condition matrix, and browser DOM reproduction.

### Hypothesis 2: Result notification belongs to another character's scored quiz

**Status:** Confirmed for local DB state, possible for the user's observed session

**Theory:** A previous submission id/quiz match problem scored a different character's quiz, which produced a result-arrival notice, while the viewed character's quiz stayed in grading.

**Supporting indicators:** Local DB has the same submission id on two character quizzes, one scored and one still grading.

**Would confirm:** User's production/local state shows the same cross-character shared submission id or mismatched scored quiz.

**Would refute:** User state has only one quiz with that submission id and it is the viewed quiz.

**Resolution:** Confirmed in the inspected local DB; current backend tests indicate the source-level fix is already present in the working tree.

## Missing Evidence

| Gap | Impact | How to Obtain |
| --- | ------ | ------------- |
| Production/user exact row | 실제 사용자 데이터가 로컬 재현과 같은지 확정 | 해당 계정의 `game_states.state_json`에서 visible quiz id/submissionId 확인 |

## Source Code Trace

| Element | Detail |
| ------- | ------ |
| Error origin | `vue/src/stores/game.js:218` `normalizeQuiz()` omits `grading` |
| Trigger | 퀴즈 답변 제출 |
| Condition | `submitted=true`, `scored=false`, `gradeFailed=false`, backend `grading=true`, frontend `q.grading` missing |
| Related files | `vue/src/views/QuizView.vue`, `vue/src/stores/game.js`, `springboot/src/main/java/com/commitgotchi/game/application/GameService.java`, `springboot/src/main/java/com/commitgotchi/quiz/application/QuizGradeResultService.java` |

## Conclusion

**Confidence:** High

직접 UI 공백의 확정 원인은 프론트 상태 정규화에서 `grading`을 보존하지 않는 것이다. "결과 도착" 알림과 현재 화면의 결과 부재가 동시에 나타나는 현상은 로컬 DB에서 확인된 cross-character shared submission 잔여 상태와 결합하면 설명된다. 현재 작업트리의 백엔드 수정은 이 misapply 경로를 막는 계약 테스트를 통과했다.

## Recommended Next Steps

### Fix direction

1. `normalizeQuiz()`에 `grading: !!raw?.grading`을 추가한다.
2. 제출 후 장시간 `grading=true`인 퀴즈에는 프론트에서 계속 로딩/대기 상태를 보여주거나, 백엔드에서 콜백 실패 시 `gradeFailed=true`로 전환하는 복구 경로를 둔다.
3. 기존 데이터에서 같은 `submissionId`가 여러 캐릭터 퀴즈에 걸린 row를 정리한다.

### Diagnostic

운영/로컬에서 `state_json.quizzes` 중 `submitted=true AND scored=false AND grading=true`가 오래 남은 항목과, 같은 `submissionId`가 여러 캐릭터에 존재하는 항목을 조회한다.

## Reproduction Plan

1. 로컬 앱을 캐시 무효화 URL로 연다.
2. `#/quiz?characterId=...`에서 `submitted=true, scored=false, grading=true`인 퀴즈를 표시한다.
3. 현재 빌드에서는 textarea가 비활성화되고 배지는 `미제출`, 결과/로딩 UI는 없음이 보인다.
4. `normalizeQuiz()`가 `grading`을 보존하면 같은 상태에서 `답변 생성 중...` 로딩이 보여야 한다.

## Side Findings

- Browser note: 최초 `http://localhost:5173/#/quiz`는 캐시된 옛 asset hash를 요청해 빈 DOM이었다. `?codexCacheBust=202606250115`로 재접속하자 앱이 렌더링됐다.
- FastAPI logs include `quiz_grading_callback_failed ... status_code=400` for some recent submissions, which can leave Spring state stuck at `grading=true` unless a failure recovery path flips it.

## Follow-up: 2026-06-25

### New Evidence

- `vue/src/stores/game.js:218`-`243`: quiz normalization omits `grading`.
- `vue/src/views/QuizView.vue:42`-`43`: UI already expects `q.grading`.
- `springboot/src/main/java/com/commitgotchi/game/application/GameService.java:317`-`324`: async submission sets `submissionId`, `quizId`, and `grading=true`.
- Browser DOM on `http://localhost:5173/?codexCacheBust=202606250115#/quiz`: first quiz had answer text and disabled textarea, no buttons, badge text `미제출`.
- Local DB test user: current visible quiz `q105` is `submitted=true, scored=false, grading=true`; another character quiz `q102` is `scored=true` with the same submission id.
- `./gradlew test --tests com.commitgotchi.quiz.QuizGradeResultContractIntegrationTest`: passed.

### Additional Findings

- The deployed/container app initially appeared blank due to cached stale asset references (`index-DwMNiLS7.js`) falling back to `index.html`; cache-busted reload used the current build.
- FastAPI callback failures with HTTP 400 are present for some submissions; those leave `grading=true` rows unless separately recovered.

### Updated Hypotheses

- Hypothesis 1 confirmed: frontend drops `grading`, making submitted-but-unscored quizzes render as an empty/misleading state.
- Hypothesis 2 confirmed for local DB state: result notice can be caused by a different scored quiz/character while the viewed quiz remains grading.

### Backlog Changes

- Add frontend regression coverage for `normalizeQuiz({ grading: true })`.
- Add data repair/query for duplicate submission ids across character ids.
- Add failure recovery for FastAPI callback 400/non-retryable paths if Spring has already accepted the grading request.

### Updated Conclusion

The immediate user-facing defect is frontend state loss of `grading`. The confusing "result arrived" part is explained by a separate stale/cross-character submission artifact in local data; current backend source has tests for that class of bug, but existing stuck rows still need cleanup.

## Follow-up: 2026-06-25 #2

### New Evidence

- Docker FastAPI logs around 2026-06-25 01:23-01:24 KST show `POST /api/internal/quizzes/grade` returning `202 Accepted`, followed by `quiz_grading_callback_failed ... status_code=400 retryable=False category=CALLBACK_NON_RETRYABLE_FAILURE` for two local quiz submissions.
- Local Postgres `game_states` for a test user still contains those submissions as `submitted=true`, `grading=true`, `scored=false`, `gradeFailed=false`.
- Running FastAPI container `commitgotchi-fastapi` was created from an older local image at `2026-06-24T06:52:39Z`; running Spring container was created later at `2026-06-24T16:03:39Z`.
- Running FastAPI container source `/app/app/integration/spring_payloads.py` builds quiz grade-result callback payload with `submissionId`, `userId`, `quizId`, `status`, `scoreAllocation`, `scoreDelta`, `feedback`, `emotion`, and `statusMessage`, but no `characterId`.
- Workspace source `fastapi/app/integration/spring_payloads.py:69`-`116` includes `character_id` in the builder signature and writes `"characterId": character_id`; the running container is stale relative to the workspace.
- Spring source `springboot/src/main/java/com/commitgotchi/quiz/api/dto/QuizGradeResultRequest.java:20`-`22` requires `characterId` as `@NotNull @Positive Long characterId`.
- Non-mutating callback probe with `characterId` returned `200 {"accepted":true,"duplicate":false}`.
- Non-mutating callback probe without `characterId` returned `400 {"code":"VALIDATION_FAILED",...}`.

### Additional Findings

- The Spring `AccessDeniedException` stack trace at `2026-06-25 01:24:31 KST` is not the primary quiz callback failure. The FastAPI callback failures at `01:23:32` and `01:24:39` are silent 400 validation responses from Spring, while the noisy AccessDenied trace is a separate `/error` handling/security side effect.
- The immediate user symptom now has a backend root cause in addition to the frontend display issue: Spring accepts the initial grading request, but the result callback is rejected before it can flip `grading=false`.

### Updated Hypotheses

- Hypothesis 3: FastAPI callback payload is missing `characterId`.

**Status:** Confirmed

**Resolution:** Running container code omits `characterId`, Spring DTO requires it, and a probe without `characterId` reproduces the exact 400 validation response.

### Backlog Changes

- Rebuild/redeploy `commitgotchi-fastapi:local` from the current workspace source so the callback includes `characterId`.
- After redeploy, retry or repair stuck quizzes where `submitted=true`, `grading=true`, `scored=false`, `gradeFailed=false`.
- Add callback failure logging that records Spring response body/code and the payload schema version without sensitive values, so future 400s do not require container source diffing.

### Updated Conclusion

The Docker-log root cause is a stale FastAPI container image. It sends quiz grade-result callbacks without `characterId`; Spring requires `characterId`, returns `VALIDATION_FAILED` 400, and FastAPI classifies that as a non-retryable callback failure. The rejected callback leaves the submitted quiz stuck in `grading=true`, so the answer/result never appears.
