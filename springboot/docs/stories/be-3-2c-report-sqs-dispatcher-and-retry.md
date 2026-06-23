---
story_id: BE-3.2c
story_key: be-3-2c-report-sqs-dispatcher-and-retry
epic: BE-3
status: done
baseline_commit: ab379dc9b79959ec4a4e5afe0647d5b58277180c
created: 2026-06-23
scope: springboot-only
split_from: BE-3.2 일일 리포트 생성 요청 적재
depends_on:
  - BE-3.2a Report Outbox 기반 요청 스냅샷 정규화
  - BE-3.2b Asia/Seoul 자정 Report Outbox 적재 스케줄러
---

# Story BE-3.2c: Report Outbox SQS Dispatcher와 Retry 상태 전이

Status: done

## Story

As a 백엔드 개발자,
I want `PENDING` outbox row를 잠금 조회해 SQS에 전송하고 성공/실패 상태를 기록하며,
so that 자정 리포트 요청이 at-least-once 환경에서도 유실 없이 FastAPI로 전달되게 할 수 있다.

## 목적과 범위

- 이 스토리는 outbox row를 SQS로 내보내는 dispatcher와 retry 상태 전이를 다룬다.
- 자정 대상 선정은 BE-3.2b, 콜백 결과 저장/성장 반영은 BE-3.3/BE-3.4, 오전 9시 조회/SSE는 BE-3.5 범위다.
- 기존 `SqsReportRequestProducer`와 `ReportQueueProperties`를 재사용한다.
- 실제 AWS credentials가 없어도 unit/integration test는 Fake producer 또는 mocked `SqsClient`로 통과해야 한다.

## Acceptance Criteria

1. **Pending row 잠금 조회**
   - **Given** `report_request_outbox`에 `status=PENDING`이고 `available_at <= now`인 row가 있을 때
   - **When** dispatcher가 실행되면
   - **Then** row를 안정적인 순서(`available_at`, `id`)로 제한 개수만큼 조회한다.
   - **And** 동시 dispatcher 실행에서 같은 row가 이중 처리되지 않도록 DB lock 또는 상태 선점 전략을 사용한다.

2. **SQS message 전송**
   - **Given** pending row가 선택되었을 때
   - **When** dispatcher가 message를 구성하면
   - **Then** body는 `ReportRequestMessage` 계약과 동일한 JSON shape다.
   - **And** `requestId`는 outbox `request_id`와 동일하다.
   - **And** queue url/name resolution은 기존 `SqsReportRequestProducer`를 재사용한다.

3. **성공 상태 전이**
   - **Given** SQS `sendMessage`가 성공했을 때
   - **When** dispatcher transaction이 완료되면
   - **Then** 해당 row는 `status=SENT`, `sent_at=now`, `last_error=null`로 전환된다.
   - **And** 이미 `SENT`인 row는 다시 전송하지 않는다.

4. **실패와 재시도**
   - **Given** SQS 전송 또는 직렬화가 실패했을 때
   - **When** dispatcher가 예외를 처리하면
   - **Then** row는 다음 재시도 가능 상태를 기록한다.
   - **And** `attempt_count`가 증가하고 `last_error`는 500자 이내로 민감정보 없이 저장된다.
   - **And** 최대 재시도 횟수 미만이면 `status=PENDING`, `available_at`은 backoff 이후로 설정된다.
   - **And** 최대 재시도 횟수 이상이면 `status=FAILED`로 전환되어 자동 재시도 대상에서 제외된다.

5. **운영/로컬 설정**
   - **Given** `REPORT_REQUEST_QUEUE_ENABLED=false`일 때
   - **When** dispatcher가 실행되면
   - **Then** 운영 SQS 호출 없이 Fake/noop producer로 테스트 가능하다.
   - **And** 실제 운영 활성화에는 `REPORT_REQUEST_QUEUE_URL` 또는 `REPORT_REQUEST_QUEUE_NAME`이 필요하다.
   - **And** secret/access key 값을 로그에 남기지 않는다.

## Tasks / Subtasks

- [x] **Task 1: outbox claim/query 메서드를 추가한다** (AC: 1)
  - [x] 권장 mapper query: `FOR UPDATE SKIP LOCKED` 또는 `UPDATE ... WHERE id IN (...) RETURNING ...`.
  - [x] PostgreSQL 기준으로 테스트한다. H2 대체 금지.
  - [x] batch size property를 둔다. 기본값은 작게 시작한다(예: 20).

- [x] **Task 2: dispatcher service를 추가한다** (AC: 1~4)
  - [x] 권장 위치: `springboot/src/main/java/com/commitgotchi/report/application/ReportOutboxDispatcher.java`.
  - [x] `dispatchAvailable(Instant now)`처럼 테스트 가능한 method를 제공한다.
  - [x] row별 실패가 전체 batch를 중단하지 않게 처리한다.
  - [x] 성공/실패 결과 count를 반환해 log/test에서 검증할 수 있게 한다.

- [x] **Task 3: retry/backoff 정책을 구현한다** (AC: 4)
  - [x] 권장 property: `commitgotchi.report.dispatcher.max-attempts=3`.
  - [x] 권장 property: `commitgotchi.report.dispatcher.retry-delay=PT5M`.
  - [x] `FAILED` row 재처리는 수동 운영 action이나 후속 story로 남긴다.

- [x] **Task 4: dispatcher trigger를 추가한다** (AC: 5)
  - [x] BE-3.2b scheduler 후 같은 job에서 dispatcher를 호출할지, 별도 fixed-delay dispatcher로 둘지 선택해 문서화한다.
  - [x] MVP 권장: 별도 `@Scheduled(fixedDelayString=...)` dispatcher로 `PENDING`을 주기적으로 비운다.
  - [x] `enabled=false`에서는 dispatcher bean이 no-op이거나 schedule이 비활성화된다.

- [x] **Task 5: tests를 추가/보강한다** (AC: 1~5)
  - [x] 권장 테스트: `ReportOutboxDispatcherIntegrationTest`.
  - [x] success: row가 `SENT`가 되고 message JSON이 맞음.
  - [x] failure: `attempt_count`, `available_at`, `last_error`, `FAILED` 전환 검증.
  - [x] concurrency: 두 dispatcher가 같은 row를 동시에 SENT로 만들지 않음.
  - [x] 기존 `SqsReportRequestProducerTest`는 queue url/name resolution coverage로 유지한다.

## Dev Notes

### Source Requirements

- AWS SQS Standard는 중복 전달 가능성이 있으므로 requestId 멱등이 필수다.
- Architecture §4.1은 SQS batch API 사용 여부와 무관하게 outbox의 `request_id`가 멱등 키라고 고정한다.
- Architecture §4.2는 FastAPI가 Spring Boot 콜백에서 `200 OK`를 받은 경우에만 SQS 메시지를 삭제한다고 고정한다. 이 dispatcher는 Spring Boot -> SQS 전송까지만 책임진다.

### Existing Code To Reuse

- `springboot/src/main/java/com/commitgotchi/report/sqs/SqsReportRequestProducer.java`
  - 현재 역할: `SqsClient.sendMessage(...)` 호출, queue url/name resolve, JSON 직렬화.
  - 보존: queue resolution과 exception wrapping.

- `springboot/src/main/java/com/commitgotchi/report/sqs/ReportQueueProperties.java`
  - 현재 역할: region, endpoint, credentials, queue name/url property normalization.
  - 보존: `.strip()` 기반 normalize, trailing slash 제거.

- `springboot/src/main/java/com/commitgotchi/report/application/ReportRequestPublishException.java`
  - 현재 역할: producer 실패 exception.
  - 변경 목표: dispatcher가 이 exception을 잡아 outbox 실패 상태로 기록한다.

### Anti-patterns To Avoid

- dispatcher가 `reports`나 `game_states`에서 message를 즉석 재구성하지 않는다. outbox row의 스냅샷이 SQS payload의 기준이다.
- SQS 성공 전에 outbox를 `SENT`로 바꾸지 않는다.
- SQS 실패를 삼키고 row 상태를 그대로 두지 않는다. 재시도 가능 시간이 기록되어야 한다.
- `REPORT_REQUEST_QUEUE_ACCESS_KEY_ID`, secret, session token, Authorization header를 log/error에 쓰지 않는다.
- `FAILED`를 자동으로 무한 재시도하지 않는다.

### Latest Technical Information

- AWS SDK for Java 2.x 공식 예제는 `SqsClient.sendMessage`에 `SendMessageRequest`를 넘기는 방식으로 단일 메시지를 보낸다. Source: https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-sqs-messages.html
- AWS SQS Standard queue는 at-least-once delivery라 같은 메시지가 중복 처리될 수 있으므로 애플리케이션은 멱등해야 한다. Source: https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/standard-queues-at-least-once-delivery.html

### References

- `springboot/docs/springboot-backend-epics.md` — BE-3.2 split implementation list.
- `springboot/docs/stories/be-3-2a-report-outbox-foundation-and-request-snapshot.md` — outbox snapshot dependency.
- `springboot/docs/stories/be-3-2b-midnight-report-outbox-enqueue-scheduler.md` — producer-ready PENDING row dependency.
- `_bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md` — §4.1, §4.2, §4.6, AD-31.
- `springboot/src/main/java/com/commitgotchi/report/sqs/SqsReportRequestProducer.java` — existing SQS send adapter.
- `springboot/src/main/java/com/commitgotchi/report/sqs/ReportQueueProperties.java` — queue configuration and secret-bearing properties.
- `springboot/src/test/java/com/commitgotchi/report/SqsReportRequestProducerTest.java` — existing queue URL/name and JSON shape coverage.

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
- Implemented full outbox payload snapshot fields for character metadata/current stats.
- Added `FOR UPDATE SKIP LOCKED` dispatcher claim, SENT/retry/FAILED transitions, fixed-delay scheduler, and dispatcher properties.
- Added success, retry/final failure, and concurrent dispatcher integration coverage.

### File List

- `springboot/src/main/resources/db/migration/V5__create_report_request_outbox.sql`
- `springboot/src/main/java/com/commitgotchi/report/domain/ReportRequestOutbox.java`
- `springboot/src/main/java/com/commitgotchi/report/domain/ReportRequestOutboxMapper.java`
- `springboot/src/main/java/com/commitgotchi/report/domain/ReportRequestOutboxRepository.java`
- `springboot/src/main/java/com/commitgotchi/report/application/ReportRequestOutboxService.java`
- `springboot/src/main/java/com/commitgotchi/report/application/ReportDispatcherProperties.java`
- `springboot/src/main/java/com/commitgotchi/report/application/ReportOutboxDispatcher.java`
- `springboot/src/main/java/com/commitgotchi/report/scheduler/ReportOutboxDispatcherScheduler.java`
- `springboot/src/main/resources/application.yml`
- `springboot/src/test/java/com/commitgotchi/report/ReportOutboxDispatcherIntegrationTest.java`
- `springboot/src/test/java/com/commitgotchi/report/ReportMidnightEnqueueServiceIntegrationTest.java`
- `springboot/src/test/java/com/commitgotchi/user/DatabaseMigrationIntegrationTest.java`

## Suggested Review Order

**Dispatcher Flow**

- Batch claim, row-by-row send, and retry state machine start here.
  [`ReportOutboxDispatcher.java:34`](../../src/main/java/com/commitgotchi/report/application/ReportOutboxDispatcher.java#L34)

- PostgreSQL locking and status updates define the concurrency contract.
  [`ReportRequestOutboxMapper.java:112`](../../src/main/java/com/commitgotchi/report/domain/ReportRequestOutboxMapper.java#L112)

- Full payload snapshot keeps dispatcher independent from game state.
  [`V5__create_report_request_outbox.sql:11`](../../src/main/resources/db/migration/V5__create_report_request_outbox.sql#L11)

**Scheduling And Config**

- Dispatcher schedule is opt-in and delegates to the service.
  [`ReportOutboxDispatcherScheduler.java:16`](../../src/main/java/com/commitgotchi/report/scheduler/ReportOutboxDispatcherScheduler.java#L16)

- Queue and dispatcher env settings live under report config.
  [`application.yml:48`](../../src/main/resources/application.yml#L48)

**Tests**

- Success, retry, final failure, and lock concurrency are covered here.
  [`ReportOutboxDispatcherIntegrationTest.java:67`](../../src/test/java/com/commitgotchi/report/ReportOutboxDispatcherIntegrationTest.java#L67)
