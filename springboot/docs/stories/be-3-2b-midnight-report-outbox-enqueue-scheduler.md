---
story_id: BE-3.2b
story_key: be-3-2b-midnight-report-outbox-enqueue-scheduler
epic: BE-3
status: done
baseline_commit: ab379dc9b79959ec4a4e5afe0647d5b58277180c
created: 2026-06-23
scope: springboot-only
split_from: BE-3.2 일일 리포트 생성 요청 적재
depends_on:
  - BE-3.2a Report Outbox 기반 요청 스냅샷 정규화
---

# Story BE-3.2b: Asia/Seoul 자정 Report Outbox 적재 스케줄러

Status: done

## Story

As a 백엔드 개발자,
I want Asia/Seoul 기준 자정 이후 전날 학습 리포트를 outbox `PENDING` 요청으로 적재하고,
so that FastAPI 리포트 생성 작업이 매일 한 번, 재시작과 재실행에도 멱등하게 준비되게 할 수 있다.

## 목적과 범위

- 이 스토리는 자정 스케줄러와 대상 선정만 다룬다. SQS `SendMessage` 호출과 retry 상태 전이는 BE-3.2c 범위다.
- `springboot/` 내부만 변경한다.
- 스케줄러는 기본적으로 `Asia/Seoul` 날짜 경계를 사용한다. 프로젝트의 기존 `GameService.APP_ZONE = Asia/Seoul` 결정과 맞춘다.
- 로컬/테스트에서 수동 실행 가능한 application service를 먼저 만들고, `@Scheduled`는 얇게 둔다.

## Acceptance Criteria

1. **자정 스케줄 설정**
   - **Given** 애플리케이션이 스케줄러 활성 설정으로 실행될 때
   - **When** Asia/Seoul 기준 매일 `00:00` 이후가 되면
   - **Then** 전날 `targetDate`의 리포트 요청 적재 job이 실행된다.
   - **And** cron은 Spring 6-field cron 형식을 사용한다.
   - **And** timezone은 `Asia/Seoul`로 명시한다.

2. **대상 선정**
   - **Given** 전날 학습 리포트를 작성한 사용자/캐릭터가 있을 때
   - **When** job이 실행되면
   - **Then** 활성 캐릭터 또는 report에 고정된 character id가 존재하고 소유권이 맞는 건만 outbox 대상으로 선정한다.
   - **And** 활성 캐릭터가 없거나 캐릭터 row가 삭제된 상태는 실패로 전송하지 않고 skip/failure metric 또는 log로 남긴다.
   - **And** 리포트 미작성 사용자는 outbox row를 만들지 않는다.

3. **멱등 재실행**
   - **Given** 같은 `targetDate` job이 여러 번 실행되었을 때
   - **When** outbox 적재 service가 호출되면
   - **Then** 같은 `userId + characterId + targetDate` row는 하나만 유지된다.
   - **And** `PENDING` row는 최신 리포트 스냅샷으로 갱신될 수 있다.
   - **And** `SENT` row는 중복 전송 준비 상태로 되돌리지 않는다.

4. **테스트/로컬 제어**
   - **Given** `test` profile에서 애플리케이션이 실행될 때
   - **When** 테스트가 스케줄러 service를 직접 호출하면
   - **Then** 실제 시간이 자정이 아니어도 원하는 `targetDate`로 outbox 적재를 검증할 수 있다.
   - **And** 자동 스케줄러는 property로 끌 수 있다.

5. **관측성과 오류 처리**
   - **Given** job이 여러 사용자를 처리할 때
   - **When** 일부 사용자 리포트 스냅샷 생성이 실패하면
   - **Then** 나머지 대상 처리는 계속된다.
   - **And** log에는 targetDate, created/updated/skipped/failure count, traceId 또는 job id가 남는다.
   - **And** Authorization header, JWT, internal secret, 리포트 본문 전체를 로그에 남기지 않는다.

## Tasks / Subtasks

- [x] **Task 1: scheduler property와 configuration을 추가한다** (AC: 1, 4)
  - [x] 권장 prefix: `commitgotchi.report.scheduler.enabled`.
  - [x] 권장 cron property: `commitgotchi.report.scheduler.midnight-cron=0 0 0 * * *`.
  - [x] 권장 zone property: `commitgotchi.report.scheduler.zone=Asia/Seoul`.
  - [x] `@EnableScheduling`이 아직 없으면 Spring Boot configuration에 추가한다.

- [x] **Task 2: 자정 적재 application service를 만든다** (AC: 2, 3, 5)
  - [x] 권장 위치: `springboot/src/main/java/com/commitgotchi/report/application/ReportMidnightEnqueueService.java`.
  - [x] `enqueueForTargetDate(LocalDate targetDate)` 같은 수동 실행 가능한 메서드를 제공한다.
  - [x] 대상 조회 SQL은 report 저장 schema가 정규화되었는지(BE-3.1) 또는 compatibility JSON인지에 따라 하나만 선택하고 문서화한다.
  - [x] outbox 생성은 BE-3.2a의 service를 재사용한다.

- [x] **Task 3: 얇은 scheduled adapter를 추가한다** (AC: 1, 4)
  - [x] 권장 위치: `springboot/src/main/java/com/commitgotchi/report/scheduler/ReportMidnightScheduler.java`.
  - [x] `@Scheduled(cron = "${...}", zone = "${...}")` 형태로 설정한다.
  - [x] scheduled method는 날짜 계산과 service 호출만 수행한다.

- [x] **Task 4: integration tests를 추가한다** (AC: 2, 3, 4)
  - [x] 권장 테스트: `springboot/src/test/java/com/commitgotchi/report/ReportMidnightEnqueueServiceIntegrationTest.java`.
  - [x] 같은 날짜 job 2회 실행 시 outbox 중복 없음.
  - [x] 리포트 미작성/캐릭터 없음은 outbox 미생성.
  - [x] `SENT` row는 다시 `PENDING`으로 내려가지 않음.

- [x] **Task 5: regression commands를 실행한다** (AC: 1~5)
  - [x] `./gradlew test --tests com.commitgotchi.report.ReportMidnightEnqueueServiceIntegrationTest`
  - [x] `./gradlew test --tests com.commitgotchi.report.*`

### Review Findings

- [x] [Review][Patch] Candidate query can abort the entire batch before per-user failure handling [springboot/src/main/java/com/commitgotchi/report/application/ReportMidnightEnqueueService.java:51]
- [x] [Review][Patch] Outbox status race can produce incorrect updated counts after SENT protection [springboot/src/main/java/com/commitgotchi/report/application/ReportMidnightEnqueueService.java:111]

## Dev Notes

### Source Requirements

- PRD FR-9는 "매일 자정 이후" 사용자별 AI 일일 레포트 생성 요청을 비동기 큐에 적재하라고 요구한다.
- UX EXPERIENCE는 학습 리포트가 자정 배치 후 다음날 오전 9시에 도착한다고 고정한다.
- Architecture §8.1은 스케줄러가 매일 자정 이후 대상 사용자를 조회하고 사용자별로 SQS 요청을 준비하는 흐름을 정의한다.

### Existing Code To Reuse

- `ReportRequestOutboxService` from BE-3.2a: outbox row 생성/갱신의 단일 경계.
- `LearningCharacterRepository.findActiveByUserIdForUpdate(...)`: active character 확인이 필요한 경우 재사용.
- `GameService`의 `todayDate()`와 `APP_ZONE`은 private이므로 그대로 호출하지 말고 report module의 clock/zone helper로 추출한다.

### Scheduling Guardrails

- cron 문자열은 Spring의 6-field 형식이다. `0 0 0 * * *`는 초/분/시/일/월/요일 순서다.
- `@Scheduled` method에 무거운 도메인 로직을 넣지 않는다. 테스트 가능한 service로 위임한다.
- 서버가 여러 인스턴스로 뜨는 운영 환경은 아직 확정되지 않았다. 이 스토리에서는 DB unique/upsert로 중복 생성을 막고, 멀티 인스턴스 distributed lock은 필요 시 후속 운영 story로 분리한다.
- targetDate 계산은 `LocalDate.now(zone).minusDays(1)`을 기본으로 한다. 테스트에서는 method parameter로 날짜를 주입한다.

### Latest Technical Information

- Spring Framework scheduling 문서는 cron 기반 `@Scheduled`와 `zone` 속성을 제공한다. Source: https://docs.spring.io/spring-framework/reference/integration/scheduling.html
- 현재 프로젝트는 Spring Boot `3.3.5`를 사용하므로 스케줄러 구현을 위해 새 dependency를 추가하지 않는다.

### References

- `springboot/docs/springboot-backend-epics.md` — BE-3.2 split implementation list.
- `springboot/docs/stories/be-3-2a-report-outbox-foundation-and-request-snapshot.md` — outbox row creation dependency.
- `_bmad-output/planning-artifacts/prds/prd-commitgotchi-2026-06-07/prd.md` — FR-9 and FR-12 timing.
- `_bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md` — §8.1 scheduler sequence and AD-31.
- `_bmad-output/planning-artifacts/ux-designs/ux-commitgotchi-2026-06-07/EXPERIENCE.md` — pending copy and next-morning result timing.
- `springboot/src/main/java/com/commitgotchi/game/application/GameService.java` — current `Asia/Seoul` date behavior to extract, not duplicate blindly.

## Dev Agent Record

### Agent Model Used

GPT-5 Codex

### Debug Log References

- `./gradlew test --tests com.commitgotchi.report.ReportSchedulerConfigurationTest` (RED: `@EnableScheduling` 누락으로 실패 확인)
- `./gradlew test --tests com.commitgotchi.report.ReportSchedulerConfigurationTest` (GREEN: Task 1 통과)
- `./gradlew test --tests com.commitgotchi.report.ReportMidnightEnqueueServiceIntegrationTest` (RED: service/result 타입 부재로 실패 확인)
- `./gradlew test --tests com.commitgotchi.report.ReportMidnightEnqueueServiceIntegrationTest` (GREEN: Task 2 통합 테스트 통과)
- `./gradlew test --tests com.commitgotchi.report.scheduler.ReportMidnightSchedulerTest` (RED: scheduled adapter 부재로 실패 확인)
- `./gradlew test --tests com.commitgotchi.report.scheduler.ReportMidnightSchedulerTest` (GREEN: Task 3 통과)
- `./gradlew test --tests com.commitgotchi.report.ReportMidnightEnqueueServiceIntegrationTest` (Task 4 재검증 중 scheduler 생성자 선택 문제 발견)
- `./gradlew test --tests com.commitgotchi.report.scheduler.ReportMidnightSchedulerTest` (scheduler 생성자 수정 후 통과)
- `./gradlew test --tests com.commitgotchi.report.ReportMidnightEnqueueServiceIntegrationTest` (Task 4 통합 테스트 통과)
- `./gradlew test --tests com.commitgotchi.report.ReportMidnightEnqueueServiceIntegrationTest` (Task 5 regression 통과)
- `./gradlew test --tests 'com.commitgotchi.report.*'` (Task 5 report regression 통과)
- `./gradlew test` (초기 전체 regression: Testcontainers PostgreSQL `too many clients`로 실패)
- `./gradlew test` (테스트 Hikari pool 크기 조정 후 전체 regression 통과)

- `./gradlew test --tests com.commitgotchi.report.ReportMidnightEnqueueServiceIntegrationTest --tests com.commitgotchi.report.scheduler.ReportMidnightSchedulerTest --tests com.commitgotchi.report.ReportSchedulerConfigurationTest` (review patch 검증 통과)
- `./gradlew test --tests 'com.commitgotchi.report.*'` (review patch report regression 통과)

### Completion Notes List

- BMad create-story analysis completed on 2026-06-23.
- Persistent fact glob `**/project-context.md` matched no project context file.
- Ultimate context engine analysis completed - comprehensive developer guide created.
- Task 1 완료: `@EnableScheduling`을 애플리케이션 설정에 추가하고 `commitgotchi.report.scheduler.enabled`, `midnight-cron`, `zone` 기본 property를 추가했다.
- Task 2 완료: `game_states.state_json` compatibility JSON의 `reports` 배열을 대상 조회 source로 선택하고, 수동 실행 가능한 `ReportMidnightEnqueueService.enqueueForTargetDate(LocalDate)`를 추가했다.
- Task 2 완료: report 고정 character id 또는 active character를 소유권 검증 후 사용하고, 기존 `ReportRequestOutboxService`를 통해 `PENDING` snapshot만 생성/갱신하도록 했다.
- Task 3 완료: `ReportMidnightScheduler`를 추가해 configured cron/zone으로 Asia/Seoul 기준 전날 `targetDate`만 계산하고 service로 위임하도록 했다.
- Task 4 완료: 자정 적재 service 통합 테스트로 멱등 재실행, 리포트 미작성/캐릭터 없음 skip, active character fallback, `SENT` row 보호, 일부 후보 실패 후 계속 처리를 검증했다.
- Task 5 완료: 스토리에 지정된 report regression commands를 실행했고 모두 통과했다.
- 완료 게이트: 전체 `./gradlew test`에서 Spring context cache가 여러 Hikari pool을 유지하며 Testcontainers PostgreSQL client 한도에 닿는 문제가 있어, 테스트 전용 pool size를 낮춘 뒤 전체 suite 통과를 확인했다.

- Review patch 완료: malformed `game_states.state_json` row가 있어도 다른 후보 처리를 계속하도록 후보 조회를 방어하고, outbox 저장 후 실제 `PENDING` 상태를 확인해 `SENT` race 카운트를 보정했다.

### File List

- `springboot/docs/stories/be-3-2b-midnight-report-outbox-enqueue-scheduler.md`
- `springboot/src/main/java/com/commitgotchi/CommitgotchiApplication.java`
- `springboot/src/main/java/com/commitgotchi/report/application/ReportMidnightEnqueueResult.java`
- `springboot/src/main/java/com/commitgotchi/report/application/ReportMidnightEnqueueService.java`
- `springboot/src/main/java/com/commitgotchi/report/domain/ReportEnqueueCandidate.java`
- `springboot/src/main/java/com/commitgotchi/report/domain/ReportEnqueueCandidateMapper.java`
- `springboot/src/main/java/com/commitgotchi/report/domain/ReportEnqueueCandidateRepository.java`
- `springboot/src/main/java/com/commitgotchi/report/scheduler/ReportMidnightScheduler.java`
- `springboot/src/main/resources/application.yml`
- `springboot/src/test/java/com/commitgotchi/report/ReportMidnightEnqueueServiceIntegrationTest.java`
- `springboot/src/test/java/com/commitgotchi/report/ReportSchedulerConfigurationTest.java`
- `springboot/src/test/java/com/commitgotchi/report/scheduler/ReportMidnightSchedulerTest.java`
- `springboot/src/test/java/com/commitgotchi/support/PostgresIntegrationTest.java`

## Change Log

- 2026-06-23: BE-3.2b Asia/Seoul 자정 Report Outbox 적재 스케줄러 구현 완료, 상태 `review`.
- 2026-06-23: Code review patch findings 2건 수정 완료, 상태 `done`.
