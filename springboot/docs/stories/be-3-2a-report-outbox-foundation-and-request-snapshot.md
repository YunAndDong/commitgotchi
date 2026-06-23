---
story_id: BE-3.2a
story_key: be-3-2a-report-outbox-foundation-and-request-snapshot
epic: BE-3
status: review
created: 2026-06-23
scope: springboot-only
split_from: BE-3.2 일일 리포트 생성 요청 적재
depends_on:
  - BE-3.1 일일 학습 리포트 저장
  - BE-2.7 프로토타입 성장 Bridge 회귀와 BE-3 착수 게이트
baseline_commit: ab379dc9b79959ec4a4e5afe0647d5b58277180c
---

# Story BE-3.2a: Report Outbox 기반 요청 스냅샷 정규화

Status: review

## Story

As a 백엔드 개발자,
I want 저장된 일일 학습 리포트를 `report_request_outbox` 요청 스냅샷으로 멱등하게 만들고,
so that 자정 스케줄러와 SQS dispatcher가 서버 재시작/재시도 상황에서도 같은 리포트를 중복 전송하지 않게 할 수 있다.

## 목적과 범위

- 이 스토리는 BE-3.2의 첫 구현 단위다. SQS 전송, 자정 cron, 결과 콜백 저장, SSE 알림은 포함하지 않는다.
- `springboot/` 내부만 변경한다. Vue, FastAPI, 루트 `_bmad-output`은 참고만 한다.
- 현재 `V5__create_report_request_outbox.sql`, `ReportRequestMessage`, `ReportRequestMessageBuilder`, `ReportRequestProducer`, `SqsReportRequestProducer` 초안은 재사용한다.
- 현재 `GameService.saveReport(...)`가 리포트 저장 직후 `reportRequestProducer.send(...)`를 호출하는 동작은 BE-3.2 전체 목표와 충돌한다. 이 스토리에서는 즉시 전송을 제거하고, 리포트 저장은 outbox 후보/스냅샷 생성까지만 책임지도록 경계를 고정한다.
- 현재 구현의 캐릭터 SoR은 `characters` 테이블과 `LearningCharacter`다. 아키텍처 문서의 `user_character` 명칭은 목표 모델이므로, 이 증분에서는 기존 `characters.id`를 user-owned character id로 사용한다.

## Acceptance Criteria

1. **Outbox schema와 실제 구현 명칭 정합성**
   - **Given** 현재 Flyway V5가 `report_request_outbox`를 생성할 때
   - **When** BE-3.2a가 완료되면
   - **Then** outbox는 현재 구현의 `characters(id)` FK를 기준으로 `character_id`를 저장한다.
   - **And** story/dev notes에 architecture 목표 모델의 `user_character_id`와 현재 구현의 `character_id` 명칭 차이가 명시된다.
   - **And** 이미 운영/테스트에 적용된 V5를 직접 수정할 수 없는 상황이면 V6 보정 migration을 추가하고, 미적용 개발 DB 기준이면 V5 수정 여부를 명확히 선택한다.

2. **요청 스냅샷 필드가 FastAPI 계약과 일치한다**
   - **Given** 사용자가 활성 캐릭터로 특정 날짜의 학습 리포트를 저장했을 때
   - **When** outbox 요청 스냅샷을 생성하면
   - **Then** `request_id`, `user_id`, `character_id`, `target_date`, `report_title`, `report_content`, `weekly_study_streak`, `focus`, score hint 5종, `status=PENDING`, `attempt_count=0`, `available_at`을 저장한다.
   - **And** `ReportRequestMessage` JSON은 FastAPI internal stat keys `db`, `algorithm`, `cs`, `network`, `framework`를 사용한다.
   - **And** Vue compatibility stat keys `algo/net/fw`를 FastAPI 메시지에 섞지 않는다.

3. **requestId 멱등성**
   - **Given** 같은 `userId + characterId + targetDate` 조합의 리포트가 다시 저장되었을 때
   - **When** outbox 스냅샷을 생성/갱신하면
   - **Then** 같은 `request_id`가 유지된다.
   - **And** 같은 조합의 outbox row가 중복 insert되지 않는다.
   - **And** 아직 전송 전(`PENDING`)이면 최신 리포트 제목/본문/태그 기반 힌트로 row를 갱신할 수 있다.
   - **And** 이미 `SENT`인 요청의 재작성 정책은 이 스토리에서 명확히 고정한다: 기본값은 기존 `request_id`를 중복 전송하지 않고 후속 보정은 별도 story로 미룬다.

4. **리포트 저장과 외부 전송 분리**
   - **Given** `POST /api/game/reports` 또는 BE-3.1의 정규화 리포트 저장 API가 호출될 때
   - **When** 저장이 성공하면
   - **Then** DB 트랜잭션 안에서 report/outbox 상태만 변경한다.
   - **And** 이 요청 경로에서 SQS producer를 직접 호출하지 않는다.
   - **And** producer 장애가 리포트 저장 성공을 롤백하거나 사용자 흐름을 막지 않는다.

5. **Outbox repository/service 테스트**
   - **Given** PostgreSQL + Flyway 통합 테스트가 실행될 때
   - **When** 같은 날짜 리포트를 두 번 저장하고 outbox를 생성하면
   - **Then** row는 하나이고 최신 스냅샷과 같은 `request_id`가 검증된다.
   - **And** `weekly_study_streak`는 7자리 `0/1` 문자열이고 DB CHECK를 통과한다.
   - **And** score hint는 각 필드 `0..10` 범위로 잘린다.

## Tasks / Subtasks

- [x] **Task 1: outbox schema 상태를 실제 코드 기준으로 확정한다** (AC: 1)
  - [x] `springboot/src/main/resources/db/migration/V5__create_report_request_outbox.sql`과 적용 여부를 확인한다.
  - [x] 현재 구현에서 `characters`가 user-owned character table임을 story/dev notes와 테스트명에 반영한다.
  - [x] 필요한 경우 V6 migration으로 `report_request_outbox` 제약/컬럼을 보정한다.

- [x] **Task 2: outbox domain/repository를 추가한다** (AC: 2, 3, 5)
  - [x] 권장 위치: `springboot/src/main/java/com/commitgotchi/report/domain/ReportRequestOutbox.java`.
  - [x] 권장 위치: `springboot/src/main/java/com/commitgotchi/report/domain/ReportRequestOutboxMapper.java`.
  - [x] 권장 위치: `springboot/src/main/java/com/commitgotchi/report/domain/ReportRequestOutboxRepository.java`.
  - [x] MyBatis를 사용한다. 이 프로젝트는 현재 JPA repository가 아니라 mapper-backed repository 패턴이다.
  - [x] `request_id`와 `(user_id, character_id, target_date)` unique constraint 충돌을 정상 멱등 upsert로 처리한다.

- [x] **Task 3: 요청 스냅샷 생성 service를 추가한다** (AC: 2, 3)
  - [x] 권장 위치: `springboot/src/main/java/com/commitgotchi/report/application/ReportRequestOutboxService.java`.
  - [x] 기존 `ReportRequestMessageBuilder`의 stat key/order 검증을 재사용한다.
  - [x] `LearningCharacterRepository.findByIdAndUserIdForUpdate(...)` 또는 active locked lookup으로 character snapshot을 만든다.
  - [x] `GameService` private helper에 갇힌 `weeklyStudyStreak`, `scoreDeltaHint`, `reportFocus` 로직은 report module로 옮기거나 중복 없이 추출한다.

- [x] **Task 4: 리포트 저장 경로에서 즉시 SQS 전송을 제거한다** (AC: 4)
  - [x] `GameService.saveReport(...)`의 `reportRequestProducer.send(...)` 직접 호출을 제거한다.
  - [x] 리포트 저장 후 사용자 notice는 유지한다: "리포트 저장됨 - 자정에 분석돼요. 내일 오전 9시 도착."
  - [x] 저장 응답의 `dailyReport.status=pending`, report `status=analyzing` compatibility shape는 BE-3.5 전까지 깨지지 않게 유지한다.

- [x] **Task 5: focused regression을 추가한다** (AC: 1~5)
  - [x] 권장 테스트: `springboot/src/test/java/com/commitgotchi/report/ReportRequestOutboxIntegrationTest.java`.
  - [x] 기존 `SqsReportRequestProducerTest`는 유지한다. 이 스토리는 producer 단위 테스트를 삭제하지 않는다.
  - [x] `./gradlew test --tests com.commitgotchi.report.ReportRequestOutboxIntegrationTest`
  - [x] `./gradlew test --tests com.commitgotchi.report.*`

## Dev Notes

### Source Requirements

- Spring Boot backend epic BE-3.2는 대상 날짜, 사용자, 활성 캐릭터, 학습 리포트로 멱등 `requestId`를 생성하고 이미 요청한 리포트를 중복 적재하지 않아야 한다.
- PRD FR-9는 매일 자정 이후 사용자별 AI 일일 레포트 생성 요청을 비동기 큐에 적재하라고 요구한다.
- Architecture §4.1은 Spring Boot가 `report_request_outbox`에 `PENDING` 요청을 먼저 기록하고, 성공한 행만 `SENT`로 전환한다고 고정한다.
- Architecture AD-31은 자정 SQS 적재가 `report_request_outbox`를 통해 수행되어야 한다고 확정한다.
- BE-2.7은 `game_states.state_json.characters`를 캐릭터 SoR로 되살리지 말라고 고정했다.

### Existing Code To Reuse

- `springboot/src/main/resources/db/migration/V5__create_report_request_outbox.sql`
  - 현재 역할: outbox 테이블, unique key, pending index, 상태 CHECK를 이미 제공한다.
  - 주의: score hint가 5개 정수 컬럼으로 펼쳐져 있다. architecture의 `jsonb score_delta_hint`와 다르므로 구현은 현재 migration과 테스트를 기준으로 맞춘다.

- `springboot/src/main/java/com/commitgotchi/report/application/ReportRequestMessage.java`
  - 현재 역할: SQS message DTO.
  - 보존: `targetDate`, `userMetadata`, `characterMetadata`, `dailyReport` shape.

- `springboot/src/main/java/com/commitgotchi/report/application/ReportRequestMessageBuilder.java`
  - 현재 역할: requestId/user/character/report 입력을 FastAPI message shape로 변환한다.
  - 보존: uppercase `CharacterEmotion.name()`, FastAPI stat keys.

- `springboot/src/main/java/com/commitgotchi/game/application/GameService.java`
  - 현재 역할: `/api/game/**` compatibility facade. 리포트 저장 직후 producer를 호출한다.
  - 변경 목표: producer 직접 호출 제거, outbox service 호출 또는 BE-3.1 정규화 리포트 저장 service와 연결.
  - 보존: `dailyReport`, `reports`, `notice`, projection/persistence boundary.

### Anti-patterns To Avoid

- `GameService.saveReport(...)` 안에서 SQS 전송까지 수행하지 않는다.
- outbox row 없이 `reports`를 매번 스캔해 "보낸 것 같은지" 추론하지 않는다.
- `request_id`를 매 호출 UUID로 만들지 않는다. 같은 `userId + characterId + targetDate`는 같은 id가 되어야 retry와 callback 멱등이 이어진다.
- 캐릭터 stats나 감정은 outbox 생성 중 변경하지 않는다.
- real AWS/SQS가 없으면 테스트를 건너뛰는 방식으로 구현하지 않는다. 이 스토리는 DB outbox만으로 검증 가능해야 한다.

### Latest Technical Information

- 현재 프로젝트는 Spring Boot `3.3.5`, Java 17, AWS SDK v2 BOM `2.32.22`, MyBatis Spring Boot `3.0.4`를 사용한다. 이 스토리에서 dependency upgrade는 하지 않는다.
- AWS SQS Standard queue는 중복 전달 가능성이 있으므로 수신/콜백 경로가 멱등이어야 한다. Source: https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/standard-queues-at-least-once-delivery.html
- AWS SDK for Java 2.x의 SQS 예제는 `SqsClient.sendMessage(SendMessageRequest.builder().queueUrl(...).messageBody(...).build())` 패턴을 사용한다. 기존 `SqsReportRequestProducer`가 이 패턴을 따른다. Source: https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-sqs-messages.html

### References

- `springboot/docs/springboot-backend-epics.md` — BE-3.2 acceptance criteria and split implementation list.
- `springboot/docs/stories/be-2-7-prototype-growth-bridge-regression-and-be3-preflight.md` — BE-3 preflight guardrails.
- `_bmad-output/planning-artifacts/prds/prd-commitgotchi-2026-06-07/prd.md` — FR-8, FR-9, FR-12, FR-16.
- `_bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md` — §4.1, §4.6, §5.3, §8.1, AD-31.
- `springboot/docs/character-api-contract.md` — current character SoR, stat key mapping, report request handoff.
- `springboot/src/main/resources/db/migration/V5__create_report_request_outbox.sql` — existing outbox schema.
- `springboot/src/main/java/com/commitgotchi/game/application/GameService.java` — current immediate producer call to remove.

## Dev Agent Record

### Agent Model Used

GPT-5 Codex

### Debug Log References

- 2026-06-23: bmad-dev-story activation completed; no project-context.md matched and no sprint-status.yaml exists, so story file tracks progress.
- 2026-06-23: Captured baseline commit ab379dc9b79959ec4a4e5afe0647d5b58277180c and moved story to in-progress.
- 2026-06-23: RED confirmed with `./gradlew test --tests com.commitgotchi.report.ReportRequestOutboxIntegrationTest`; new outbox assertions failed before implementation.
- 2026-06-23: GREEN confirmed with focused outbox test, report package regression, and full `./gradlew test`.

### Completion Notes List

- BMad create-story analysis completed on 2026-06-23.
- Persistent fact glob `**/project-context.md` matched no project context file.
- Ultimate context engine analysis completed - comprehensive developer guide created.
- V5 already matches the current implementation model: `report_request_outbox.character_id` references `characters(id)`, so no V6 correction migration was added.
- Added mapper-backed outbox domain/repository with idempotent pending upsert keyed by `(user_id, character_id, target_date)` and deterministic `request_id`.
- Added `ReportRequestOutboxService` to create request snapshots, reuse `ReportRequestMessageBuilder`, lock the owned character row, and move weekly streak / score hint / focus calculation out of `GameService`.
- Removed the direct `ReportRequestProducer.send(...)` call from `GameService.saveReport(...)`; report saving now updates DB report/outbox state only while preserving pending/analyzing response compatibility and the existing notice.
- Added PostgreSQL + Flyway integration coverage for pending row refresh, `SENT` no-rewrite policy, score hint clamping, 7-day streak shape, and no producer interaction.

### File List

- springboot/docs/stories/be-3-2a-report-outbox-foundation-and-request-snapshot.md
- springboot/src/main/java/com/commitgotchi/game/application/GameService.java
- springboot/src/main/java/com/commitgotchi/report/application/ReportRequestOutboxService.java
- springboot/src/main/java/com/commitgotchi/report/domain/ReportRequestOutbox.java
- springboot/src/main/java/com/commitgotchi/report/domain/ReportRequestOutboxMapper.java
- springboot/src/main/java/com/commitgotchi/report/domain/ReportRequestOutboxRepository.java
- springboot/src/test/java/com/commitgotchi/report/ReportRequestOutboxIntegrationTest.java

### Change Log

- 2026-06-23: Implemented BE-3.2a Report Outbox request snapshot normalization and marked story ready for review.
