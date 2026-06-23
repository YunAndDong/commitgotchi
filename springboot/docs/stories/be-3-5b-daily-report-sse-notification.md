---
story_id: BE-3.5b
story_key: be-3-5b-daily-report-sse-notification
epic: BE-3
status: done
baseline_commit: ab379dc9b79959ec4a4e5afe0647d5b58277180c
created: 2026-06-23
scope: springboot-only
split_from: BE-3.5 오전 9시 결과/SSE 알림
depends_on:
  - BE-3.5a 오전 9시 일일 리포트 결과 Read Model과 /api/game/state Projection
---

# Story BE-3.5b: 일일 리포트 결과 SSE 알림

Status: done

## Story

As a 사용자,
I want 일일 리포트 결과가 저장되고 캐릭터 성장 반영이 커밋된 뒤 SSE로 알림을 받고,
so that 오전 9시 전후 대시보드가 새 리포트, 점수 변화, 감정 변화를 새로고침 없이 보여줄 수 있다.

## 목적과 범위

- 이 스토리는 서버 측 SSE stream과 after-commit publish만 다룬다.
- 프런트 Vue 구독 구현은 범위 밖이지만, 이벤트 이름/payload 계약은 문서화한다.
- 기존 `CharacterEventService`와 `/api/game/characters/{id}/events`는 캐릭터 projection 전용이다. 리포트 결과 알림을 이 채널에 무리하게 끼워 넣지 말고, report/dashboard event 채널을 명확히 설계한다.
- push notification, mobile notification, WebSocket/STOMP broker는 범위 밖이다. Spring MVC `SseEmitter`를 사용한다.

## Acceptance Criteria

1. **인증된 SSE 구독 endpoint**
   - **Given** 사용자가 JWT로 인증되어 있을 때
   - **When** report event stream endpoint를 구독하면
   - **Then** `text/event-stream` 응답이 시작된다.
   - **And** 인증 누락은 `401`, cross-owner 구독은 `404` 또는 empty own-channel 정책으로 처리된다.
   - **And** endpoint는 `/api/game/**` compatibility namespace에 둔다.

2. **초기 snapshot 이벤트**
   - **Given** 사용자가 stream을 구독했을 때
   - **When** 연결이 시작되면
   - **Then** 현재 `dailyReport` projection 또는 dashboard summary snapshot을 한 번 보낸다.
   - **And** event name은 예: `report.snapshot`처럼 문서화된 이름을 사용한다.

3. **결과 저장 후 after-commit 이벤트**
   - **Given** BE-3.3/3.4가 FastAPI `POST /api/report` 콜백을 처리할 때
   - **When** report result 저장, idempotency marker, character growth write가 같은 트랜잭션에서 커밋되면
   - **Then** commit 이후에만 SSE `report.ready` 또는 `report.failed` 이벤트가 발행된다.
   - **And** transaction rollback 시 이벤트가 발행되지 않는다.
   - **And** duplicate callback은 중복 성장뿐 아니라 중복 ready 이벤트도 만들지 않는다.

4. **캐릭터 업데이트와 리포트 이벤트 정합성**
   - **Given** 리포트 결과로 캐릭터 stats/emotion/statusMessage가 바뀔 때
   - **When** SSE 이벤트가 발행되면
   - **Then** report event payload는 최신 dailyReport projection을 포함한다.
   - **And** 필요하면 기존 character event channel에도 `character.updated`를 발행한다.
   - **And** 이벤트 순서가 중요하면 report event payload 안에 최신 character projection 또는 version/eventId를 포함한다.

5. **Emitter lifecycle과 리소스 정리**
   - **Given** 클라이언트가 연결을 끊거나 timeout/error가 발생할 때
   - **When** emitter callback이 실행되면
   - **Then** emitter registry에서 제거된다.
   - **And** send 실패는 해당 emitter만 정리하고 다른 구독자에게 영향을 주지 않는다.

## Tasks / Subtasks

- [x] **Task 1: report SSE 계약을 문서화하고 endpoint를 추가한다** (AC: 1, 2)
  - [x] 권장 endpoint: `GET /api/game/reports/events`.
  - [x] 필요 시 active character별 stream이 더 낫다면 `GET /api/game/characters/{id}/report-events`를 선택하고 이유를 문서화한다.
  - [x] event names: `report.snapshot`, `report.ready`, `report.failed`, optional `report.pending`.

- [x] **Task 2: ReportEventService를 추가한다** (AC: 2, 3, 5)
  - [x] 권장 위치: `springboot/src/main/java/com/commitgotchi/report/application/ReportEventService.java`.
  - [x] 기존 `CharacterEventService`의 emitter registry, timeout, cleanup, `TransactionSynchronizationManager.afterCommit` 패턴을 재사용한다.
  - [x] 복붙이 커지면 작은 공통 helper를 추출하되, 불필요한 generic abstraction으로 번지지 않게 한다.

- [x] **Task 3: callback 처리 후 publish를 연결한다** (AC: 3, 4)
  - [x] `ReportCallbackService.handle(...)` 또는 BE-3.3/3.4 결과 service에서 duplicate 여부를 판정한다.
  - [x] 새로 처리된 SUCCESS/FALLBACK 결과에만 report event를 예약한다.
  - [x] growth가 적용된 경우 기존 `CharacterEventService.publishCharacterUpdatedAfterCommit(...)`도 호출한다.

- [x] **Task 4: tests를 추가한다** (AC: 1~5)
  - [x] 권장 테스트: `ReportEventStreamIntegrationTest`.
  - [x] stream starts as `text/event-stream`.
  - [x] unauthorized request is rejected.
  - [x] callback commit 후 event 발행 service가 호출되는 단위 테스트.
  - [x] rollback/duplicate path는 event 미발행.
  - [x] emitter timeout/error cleanup.

- [x] **Task 5: regression commands를 실행한다**
  - [x] `./gradlew test --tests com.commitgotchi.report.ReportEventStreamIntegrationTest`
  - [x] `./gradlew test --tests com.commitgotchi.report.*`
  - [x] `./gradlew test --tests com.commitgotchi.character.CharacterEventStreamIntegrationTest`

## Dev Notes

### Source Requirements

- 사용자는 "오전 9시 결과/SSE 알림"을 명시했다. BE-3.5a가 조회 projection, BE-3.5b가 SSE 알림이다.
- UX는 레포트 도착 시 대시보드에서 알림, 점수 반영, 감정 갱신, 게이지 상승이 재생되어야 한다고 설명한다.
- PRD FR-12는 결과 미생성/실패도 명확히 표시하라고 요구한다. SSE는 조회 API를 대체하지 않고 변경 알림/최신 projection 전달 역할이다.

### Existing Code To Reuse

- `springboot/src/main/java/com/commitgotchi/character/application/CharacterEventService.java`
  - 현재 역할: `SseEmitter` registry, snapshot, `character.updated`, after-commit publish.
  - 재사용 패턴: timeout cleanup, `CopyOnWriteArraySet`, event id, send 실패 시 emitter complete/remove.

- `springboot/src/main/java/com/commitgotchi/game/api/GameController.java`
  - 현재 역할: `/api/game/characters/{id}/events` endpoint 보유.
  - 변경 목표: report event endpoint 추가 또는 별도 controller 분리.

- `springboot/src/main/java/com/commitgotchi/report/application/ReportCallbackService.java`
  - 현재 역할: callback을 받아 character growth를 바로 반영하는 placeholder.
  - 변경 목표: BE-3.3/3.4에서 durable idempotency가 생긴 뒤 새 처리 건에만 report event publish.

### SSE Guardrails

- 이벤트는 DB commit 이후에만 발행한다. `TransactionSynchronizationManager.registerSynchronization(... afterCommit ...)` 패턴을 사용한다.
- duplicate callback에는 `duplicate=true` 응답만 하고 같은 ready event를 다시 보내지 않는다.
- SSE payload에는 secret, Authorization header, raw callback body 전체, stack trace를 포함하지 않는다.
- 서버 재시작 중 놓친 이벤트는 SSE로 보장하지 않는다. 클라이언트는 항상 `GET /api/game/state` 또는 BE-3.5a 조회 API로 최신 상태를 복구할 수 있어야 한다.
- WebSocket/STOMP를 도입하지 않는다. 현재 Spring MVC stack에서는 `SseEmitter`가 충분하다.

### Latest Technical Information

- Spring `SseEmitter`는 custom timeout 생성자와 `send(SseEmitter.event().name(...).id(...).data(...))` event builder를 제공한다. 기존 `CharacterEventService`가 이 패턴을 이미 사용한다. Source: https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/servlet/mvc/method/annotation/SseEmitter.html

### References

- `springboot/docs/springboot-backend-epics.md` — BE-3.5 split implementation list.
- `springboot/docs/stories/be-3-5a-daily-report-result-read-model-and-game-state-projection.md` — read model dependency and latest projection source.
- `_bmad-output/planning-artifacts/prds/prd-commitgotchi-2026-06-07/prd.md` — FR-12 and FR-16.
- `_bmad-output/planning-artifacts/ux-designs/ux-commitgotchi-2026-06-07/EXPERIENCE.md` — morning report arrival and dashboard animation behavior.
- `springboot/src/main/java/com/commitgotchi/character/application/CharacterEventService.java` — existing SSE lifecycle and after-commit pattern.
- `springboot/src/main/java/com/commitgotchi/game/api/GameController.java` — existing `/api/game/characters/{id}/events` endpoint style.
- `springboot/src/main/java/com/commitgotchi/quiz/application/QuizGradeResultService.java` — existing after-commit character event publication after AI result.

## Dev Agent Record

### Agent Model Used

GPT-5 Codex

### Debug Log References

- `./gradlew testClasses`
- `./gradlew test --tests 'com.commitgotchi.report.*'`
- `./gradlew test --tests 'com.commitgotchi.character.*'`
- `./gradlew test`

### Completion Notes List

- BMad create-story analysis completed on 2026-06-23.
- Persistent fact glob `**/project-context.md` matched no project context file.
- Ultimate context engine analysis completed - comprehensive developer guide created.
- Added `/api/game/reports/events` as a user-scoped report SSE channel.
- Added snapshot, ready, and failed report events with after-commit publication.
- Duplicate report callback now returns `duplicate=true` without repeated growth or report event publication.

### File List

- `springboot/src/main/java/com/commitgotchi/report/application/ReportEventService.java`
- `springboot/src/main/java/com/commitgotchi/game/api/GameController.java`
- `springboot/src/main/java/com/commitgotchi/report/application/ReportCallbackService.java`
- `springboot/src/test/java/com/commitgotchi/report/ReportEventStreamIntegrationTest.java`
- `springboot/src/test/java/com/commitgotchi/report/ReportCallbackEventPublicationTest.java`

## Suggested Review Order

**SSE Channel**

- User-scoped emitter registry sends initial report snapshots.
  [`ReportEventService.java:45`](../../src/main/java/com/commitgotchi/report/application/ReportEventService.java#L45)

- `/api/game/reports/events` exposes the authenticated report stream.
  [`GameController.java:86`](../../src/main/java/com/commitgotchi/game/api/GameController.java#L86)

**After-Commit Publishing**

- Callback schedules report ready/failed events only after accepted processing.
  [`ReportCallbackService.java:72`](../../src/main/java/com/commitgotchi/report/application/ReportCallbackService.java#L72)

- Event publication itself is guarded by transaction after-commit hooks.
  [`ReportEventService.java:65`](../../src/main/java/com/commitgotchi/report/application/ReportEventService.java#L65)

**Tests**

- SSE startup/auth and duplicate-event suppression are covered here.
  [`ReportEventStreamIntegrationTest.java:38`](../../src/test/java/com/commitgotchi/report/ReportEventStreamIntegrationTest.java#L38)
