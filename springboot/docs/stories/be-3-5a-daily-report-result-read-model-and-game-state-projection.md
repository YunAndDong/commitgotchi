---
story_id: BE-3.5a
story_key: be-3-5a-daily-report-result-read-model-and-game-state-projection
epic: BE-3
status: done
baseline_commit: ab379dc9b79959ec4a4e5afe0647d5b58277180c
created: 2026-06-23
scope: springboot-only
split_from: BE-3.5 일일 리포트 조회와 /api/game/state projection
depends_on:
  - BE-3.3 AI 일일 리포트 결과 수신
  - BE-3.4 점수 변화량, 전투력, 진화, 감정 반영
---

# Story BE-3.5a: 오전 9시 일일 리포트 결과 Read Model과 `/api/game/state` Projection

Status: done

## Story

As a 사용자,
I want 오전 9시에 대기/실패/완료 상태의 일일 리포트 결과와 추천 퀴즈를 조회하고,
so that 빈 화면 없이 어제 학습 분석, 점수 변화, 다음 학습 추천을 확인할 수 있다.

## 목적과 범위

- 이 스토리는 조회 API/read model/projection을 다룬다. SSE push는 BE-3.5b 범위다.
- `springboot/` 내부만 변경한다.
- BE-3.3/BE-3.4가 `report_results` 또는 동등한 멱등 결과 저장소를 만든 뒤 구현하는 것을 전제로 한다.
- 기존 `/api/game/state`의 compatibility shape는 유지한다. Vue가 기대하는 `dailyReport`, `reports`, `quizzes`, `characters`를 깨지 않는다.
- 현재 `GameService.deliverDailyReport(...)`는 fake delivery endpoint이며 정규화 결과 저장소가 아니다. 이 story에서는 정규화 결과를 읽어 projection하는 방향으로 이동한다.

## Acceptance Criteria

1. **일일 리포트 상태 모델**
   - **Given** 사용자가 오늘 대시보드/캐릭터 상세를 조회할 때
   - **When** 어제 리포트 결과가 아직 없으면
   - **Then** `dailyReport.status=pending`과 "내일/오늘 오전 9시 도착" 계열의 상태 안내를 반환한다.
   - **When** 결과가 실패/FALLBACK이면
   - **Then** `dailyReport.status=failed` 또는 `fallback`과 사용자 노출 가능한 안내, 0점 deltas를 반환한다.
   - **When** 결과가 성공이면
   - **Then** `dailyReport.status=ready`, summary/text, feedback, deltas, quizComment, nextRecommendation, recommended quiz refs를 반환한다.

2. **오전 9시 제품 문구와 API 정합성**
   - **Given** Asia/Seoul 기준 오전 9시 전후로 조회할 때
   - **When** 결과가 아직 없으면
   - **Then** API는 실제 데이터 상태를 거짓 완료로 꾸미지 않는다.
   - **And** 오전 9시 문구는 "제공 목표 시각"으로 표현하고, 실패/지연 상태도 명확히 반환한다.
   - **And** 빈 `dailyReport` 객체나 null-only 화면을 만들지 않는다.

3. **`/api/game/state` projection 유지**
   - **Given** BE-2.7의 projection/persistence boundary가 적용되어 있을 때
   - **When** `GET /api/game/state`를 호출하면
   - **Then** `state.characters`는 `CharacterGameProjectionService` 결과다.
   - **And** `game_states.state_json.characters`는 저장본에서 계속 `[]`다.
   - **And** `dailyReport`, `reports`, `quizzes` projection은 정규화 report/result/quiz 저장소에서 읽어 compatibility shape로 overlay된다.

4. **리포트/퀴즈 독립 목록**
   - **Given** 캐릭터 상세 화면이 리포트 기록과 퀴즈 기록을 따로 보여줄 때
   - **When** API/read model을 구성하면
   - **Then** 리포트 목록과 퀴즈 목록은 섞지 않는다.
   - **And** 기본 정렬은 최신 날짜 우선이다.
   - **And** pagination이 아직 구현되지 않은 경우에도 DTO와 service 경계는 후속 pagination 추가가 가능해야 한다.

5. **소유권과 보안**
   - **Given** 인증 사용자가 조회 API를 호출할 때
   - **When** 다른 사용자의 report/result/quiz id를 요청하거나 projection 대상에 섞일 수 있는 데이터가 있을 때
   - **Then** cross-owner 데이터는 노출되지 않는다.
   - **And** internal callback payload, secret, raw AI prompt, stack trace는 응답에 포함하지 않는다.

## Tasks / Subtasks

- [x] **Task 1: report result read model을 확정한다** (AC: 1, 2)
  - [x] BE-3.3/3.4의 저장소 이름과 컬럼을 확인한다.
  - [x] 권장 DTO: `DailyReportProjection`, `ReportHistoryItem`, `RecommendedQuizProjection`.
  - [x] fallback/failed/success 상태 enum을 정한다. FastAPI status와 public projection status를 혼동하지 않는다.

- [x] **Task 2: `/api/game/state` overlay를 정규화 저장소 기반으로 바꾼다** (AC: 3)
  - [x] `GameService.state(...)` 또는 별도 `GameStateProjectionService`에서 report projection을 합성한다.
  - [x] `game_states.state_json` compatibility 데이터를 읽더라도 새 결과의 SoR로 확장하지 않는다.
  - [x] 저장본 `characters=[]` regression을 유지한다.

- [x] **Task 3: 조회 endpoint가 필요하면 추가한다** (AC: 1, 4, 5)
  - [x] 기존 Vue가 `/api/game/state`만으로 충분하면 endpoint 추가를 미룬다.
  - [x] 별도 조회가 필요하면 `/api/game/reports` 또는 `/api/game/reports/{date}`처럼 `/api/game/**` compatibility namespace를 사용한다.
  - [x] 신규 `/api/reports/**` 정식 경로는 별도 ADR/마이그레이션 story 없이 도입하지 않는다.

- [x] **Task 4: tests를 추가한다** (AC: 1~5)
  - [x] 권장 테스트: `DailyReportProjectionIntegrationTest`.
  - [x] pending/fallback/ready 상태별 JSON shape 검증.
  - [x] 오전 9시 전후 문구/상태 검증은 Clock 주입으로 결정론적으로 수행.
  - [x] cross-owner 결과 미노출 검증.
  - [x] `game_states.state_json.characters=[]` 저장 경계 검증.

- [x] **Task 5: regression commands를 실행한다**
  - [x] `./gradlew test --tests com.commitgotchi.report.*`
  - [x] `./gradlew test --tests com.commitgotchi.character.*`
  - [x] 필요 시 `./gradlew test`

## Dev Notes

### Source Requirements

- PRD FR-12는 사용자가 매일 오전 9시까지 자신의 AI 일일 리포트를 확인할 수 있어야 하며, 결과 미생성/실패 상태도 빈 화면이 아니라 상태 안내로 보여야 한다.
- UX EXPERIENCE는 pending copy, 레포트 도착 상태, fallback copy를 명시한다.
- Architecture §5.4/AD-14는 캐릭터 상세의 리포트/퀴즈 독립 pagination을 확정한다.
- BE-2.7은 compatibility JSON을 새 SoR로 확장하지 말고 정규화 테이블을 별도 story에서 만들라고 고정했다.

### Existing Code To Reuse

- `GameService.state(...)`
  - 현재 역할: `game_states` JSON에 normalized character projection을 overlay한다.
  - 변경 목표: report/result/quiz read model도 같은 facade 응답에 안전하게 합성한다.

- `GameService.deliverDailyReport(...)`
  - 현재 역할: fake/demo delivery bridge.
  - 변경 목표: BE-3 정규화 결과가 생기면 fake path를 테스트 전용으로 축소하거나 제거한다.
  - 보존: scoreApplied 중복 방지와 stale character 방어에서 배운 guardrail.

- `CharacterGameProjectionService`
  - 현재 역할: character projection SSOT.
  - 보존: stats key `algo/cs/db/net/fw`, emotion lowercase, sprite fields.

### Projection Guardrails

- `dailyReport.status`는 실제 저장 상태를 반영한다. 오전 9시가 되었다는 이유만으로 결과를 ready로 위조하지 않는다.
- Fallback은 실패가 아니라 "흐름 유지" 상태다. 점수 변화량은 0이고 사용자는 리포트 저장 자체가 보존되었음을 볼 수 있어야 한다.
- 리포트 결과의 점수 반영은 BE-3.4에서 이미 끝난 상태여야 한다. 조회 projection에서 캐릭터 stats를 변경하지 않는다.
- 추천 퀴즈는 BE-4 정규화 story 전이라면 최소 projection만 제공하고, 채점/제출 구현을 이 story에 끌어오지 않는다.

### Latest Technical Information

- 이 스토리는 새 외부 library나 protocol을 도입하지 않는다. 현재 Spring Boot `3.3.5`, Java 17, MyBatis 기반 repository 패턴을 유지한다.

### References

- `springboot/docs/springboot-backend-epics.md` — BE-3.5 acceptance criteria and split implementation list.
- `springboot/docs/stories/be-2-7-prototype-growth-bridge-regression-and-be3-preflight.md` — projection/persistence boundary and growth marker guardrails.
- `_bmad-output/planning-artifacts/prds/prd-commitgotchi-2026-06-07/prd.md` — FR-10, FR-11, FR-12, FR-13, FR-16.
- `_bmad-output/planning-artifacts/ux-designs/ux-commitgotchi-2026-06-07/EXPERIENCE.md` — AI report result, pending, ready, fallback UX states.
- `_bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md` — §4.2, §5.3, §8.1, AD-14.
- `springboot/docs/character-api-contract.md` — current `/api/game/**` compatibility response shape.
- `springboot/src/main/java/com/commitgotchi/game/application/GameService.java` — current `dailyReport`, `reports`, `quizzes` compatibility projection.

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
- Added daily report projection overlay for pending/ready/fallback/failed API states.
- Connected `/api/game/state` to report/read-model overlay while preserving `characters=[]` persistence boundary.
- Report callback now records compatibility report results, recommended quiz refs, and duplicate markers by `requestId`.

### File List

- `springboot/src/main/java/com/commitgotchi/report/application/DailyReportProjectionService.java`
- `springboot/src/main/java/com/commitgotchi/game/application/GameService.java`
- `springboot/src/main/java/com/commitgotchi/report/application/ReportCallbackService.java`
- `springboot/src/test/java/com/commitgotchi/report/DailyReportProjectionIntegrationTest.java`
- `springboot/src/test/java/com/commitgotchi/user/DatabaseMigrationIntegrationTest.java`

## Suggested Review Order

**Read Model Overlay**

- Daily report status, message, deltas, lists, and sorting are centralized here.
  [`DailyReportProjectionService.java:25`](../../src/main/java/com/commitgotchi/report/application/DailyReportProjectionService.java#L25)

- `/api/game/state` applies character and report projections together.
  [`GameService.java:522`](../../src/main/java/com/commitgotchi/game/application/GameService.java#L522)

**Callback Result Source**

- Callback persistence updates dailyReport, reports, quizzes, and duplicate markers.
  [`ReportCallbackService.java:79`](../../src/main/java/com/commitgotchi/report/application/ReportCallbackService.java#L79)

**Tests**

- Ready/fallback projection and persisted `characters=[]` boundary are verified here.
  [`DailyReportProjectionIntegrationTest.java:57`](../../src/test/java/com/commitgotchi/report/DailyReportProjectionIntegrationTest.java#L57)
