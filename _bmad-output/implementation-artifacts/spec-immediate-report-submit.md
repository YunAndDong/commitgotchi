---
title: 'Immediate Report Submit'
type: 'feature'
created: '2026-06-24'
status: 'done'
baseline_commit: '3bc528e14c967a20bfaf70479e93d8b441466414'
context: []
---

<frozen-after-approval reason="human-owned intent - do not modify unless human renegotiates">

## Intent

**Problem:** 시연에서 "즉시 제출" 버튼을 누르면 리포트가 worker 분석 요청까지 즉시 제출되고, 답변 생성이 끝난 뒤 사용자가 곧바로 결과를 볼 수 있어야 한다. 현재 코드는 버튼과 outbox dispatch 일부가 있지만 프론트가 결과 생성 완료를 기다리지 못하고, API 이름도 버튼 의도와 직접 대응하지 않는다.

**Approach:** 기존 `/api/game/daily-report/deliver`의 pending outbox dispatch 로직을 재사용하되, 버튼 전용 `/api/game/reports/run-now` API를 추가한다. Vue store는 API 호출 뒤 report SSE 또는 짧은 폴링으로 현재 `requestId`의 `dailyReport`가 ready/fallback/failed가 될 때까지 기다린 다음 결과 화면으로 이동한다.

## Boundaries & Constraints

**Always:** 기존 dirty worktree 변경을 보존한다. 리포트 저장, outbox upsert, SQS dispatch, callback projection 계약은 유지한다. 큐가 꺼져 있거나 worker가 늦는 상황은 사용자에게 실패/대기 상태로 표현한다.

**Ask First:** 운영 보안 모델을 바꾸거나 debug 토큰 컨트롤러를 제거해야 하는 경우. worker/FastAPI 응답 포맷을 바꿔야 하는 경우.

**Never:** 결과를 프론트에서 가짜로 생성하지 않는다. callback 없이 점수를 중복 반영하지 않는다. 전체 리포트/퀴즈 흐름을 대규모로 재작성하지 않는다.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| HAPPY_PATH | 로그인 사용자, 활성 캐릭터, 오늘 작성 리포트, report queue enabled, worker callback succeeds | "즉시 제출"은 저장 후 `POST /api/game/reports/run-now`를 호출하고, SSE/polling으로 ready 상태를 받은 뒤 `/report/result`로 이동한다 | N/A |
| QUEUE_DISABLED_OR_SENT | outbox가 없거나 이미 보냈거나 큐가 꺼짐 | API는 기존 state와 notice를 반환하고 화면은 pending/notice를 유지한다 | 제한 시간 내 결과가 없으면 사용자가 결과 화면에서 대기 상태를 볼 수 있게 이동한다 |
| CALLBACK_FAILED | callback이 fallback/failed projection을 만든다 | 결과 대기는 terminal 상태로 종료되고 결과 화면은 fallback/failed 상태를 표시한다 | N/A |

</frozen-after-approval>

## Code Map

- `springboot/src/main/java/com/commitgotchi/game/api/GameController.java` -- authenticated `/api/game/**` routes.
- `springboot/src/main/java/com/commitgotchi/game/application/GameService.java` -- report save and pending outbox dispatch logic.
- `vue/src/api/client.js` -- Spring API client wrappers.
- `vue/src/stores/game.js` -- reactive game state, SSE handling, report actions.
- `vue/src/views/ReportWriteView.vue` -- "즉시 제출" button workflow.
- `vue/_tests/fe6-9-stores.mjs` -- store-level report submission regression coverage.
- `springboot/src/test/java/com/commitgotchi/report/ReportOutboxDispatcherIntegrationTest.java` -- outbox dispatch coverage already includes request-specific dispatch.

## Tasks & Acceptance

**Execution:**
- [x] `springboot/src/main/java/com/commitgotchi/game/api/GameController.java` -- add authenticated run-now endpoint -- gives the demo button a clear API contract.
- [x] `springboot/src/main/java/com/commitgotchi/game/application/GameService.java` -- expose a named service method delegating to current dispatch logic -- avoids duplicating outbox behavior.
- [x] `vue/src/api/client.js` -- add `game.runReportNow` wrapper -- lets the store call the explicit endpoint.
- [x] `vue/src/stores/game.js` -- add result-ready waiting around run-now using SSE plus polling -- prevents routing before generated answers arrive.
- [x] `vue/src/views/ReportWriteView.vue` -- await run-now completion and show generation wording -- matches demo expectations.
- [x] `vue/_tests/fe6-9-stores.mjs` -- cover mock run-now readiness -- guards store API shape.

**Acceptance Criteria:**
- Given a report was saved and worker callback succeeds, when the user clicks "즉시 제출", then the UI waits for generated report data and opens `/report/result` with ready/fallback/failed data available.
- Given report queue or worker completion is unavailable, when "즉시 제출" times out, then the user is sent to the result screen where the existing pending/fallback state explains the wait instead of failing silently.

## Spec Change Log

## Verification

**Commands:**
- `npm run build` from `vue` -- expected: frontend compiles.
- `node vue/_tests/fe6-9-stores.mjs` -- expected: store report tests pass.
- `./gradlew test --tests com.commitgotchi.report.ReportOutboxDispatcherIntegrationTest` from `springboot` -- expected: request-specific dispatch still passes.

## Suggested Review Order

**Backend Entry And Dispatch**

- Button-facing API names the immediate-submit contract.
  [`GameController.java:140`](../../springboot/src/main/java/com/commitgotchi/game/api/GameController.java#L140)

- Existing dispatch path remains the single source of behavior.
  [`GameService.java:356`](../../springboot/src/main/java/com/commitgotchi/game/application/GameService.java#L356)

- Named service alias keeps controller intent explicit.
  [`GameService.java:412`](../../springboot/src/main/java/com/commitgotchi/game/application/GameService.java#L412)

**Frontend Result Readiness**

- API wrapper points store calls at the explicit endpoint.
  [`client.js:284`](../../vue/src/api/client.js#L284)

- SSE plus polling resolves once generated data is terminal.
  [`game.js:584`](../../vue/src/stores/game.js#L584)

- Public action triggers run-now and waits for the matching request.
  [`game.js:1094`](../../vue/src/stores/game.js#L1094)

- Button waits through submission and answer generation.
  [`ReportWriteView.vue:68`](../../vue/src/views/ReportWriteView.vue#L68)

**Tests**

- API regression verifies run-now sends the pending request.
  [`ReportOutboxDispatcherIntegrationTest.java:143`](../../springboot/src/test/java/com/commitgotchi/report/ReportOutboxDispatcherIntegrationTest.java#L143)

- Store regression covers mock immediate readiness.
  [`fe6-9-stores.mjs:35`](../../vue/_tests/fe6-9-stores.mjs#L35)
