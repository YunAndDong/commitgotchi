---
story_id: BE-2.7
story_key: be-2-7-prototype-growth-bridge-regression-and-be3-preflight
epic: BE-2
status: done
created: 2026-06-18
scope: springboot-only
previous_story: be-2-6-character-epic-contract-regression-and-be3-handoff
baseline_commit: 14a2069c7dc410b6f985dbb541ca9df1813fb35a
---

# Story BE-2.7: 프로토타입 성장 Bridge 회귀와 BE-3 착수 게이트

Status: done

## Story

As a 백엔드 개발자,
I want `GameService`에 남아 있는 리포트/퀴즈/daily-report compatibility bridge를 정규화 캐릭터 SoR 기준으로 회귀 고정하고,
so that BE-3가 새 학습 리포트 스키마와 멱등 성장 루프를 만들 때 기존 `/api/game/**` 데모 흐름과 캐릭터 불변식이 깨지지 않게 할 수 있다.

## 목적과 범위

- `springboot/` 내부만 변경한다. Vue, FastAPI, 루트 `_bmad-output`은 참고만 한다.
- BE-2.1~BE-2.6의 `characters` 정규화 테이블, `LearningCharacter`, `CharacterCommandService`, `CharacterGameProjectionService`, image fallback/retry, OpenAPI/contract regression을 재사용한다.
- 이 스토리는 BE-3 구현이 아니다. 새 study log/report/quiz 정규화 테이블, SQS producer, FastAPI callback, Internal API, Vue 변경은 만들지 않는다.
- 현재 `GameService`에 남아 있는 `POST /api/game/reports`, `POST /api/game/quizzes/{id}/submit`, `POST /api/game/daily-report/deliver`는 BE-3 전까지의 compatibility bridge로 취급한다.
- compatibility JSON은 `reports`, `quizzes`, `dailyReport`, `notice`, `boardPosts`만 보존한다. `game_states.state_json.characters`는 계속 빈 배열 `[]`로 저장한다.
- 성장 반영은 `game_states` JSON mutation이 아니라 정규화 `characters` row와 도메인/application service를 통해서만 수행한다.
- 핵심 목표는 새 사용자 기능보다 안전망이다: 점수 중복 반영 금지, stale character reference 방어, active switch/delete 이후 bridge 동작, BE-3 handoff 문서 보강, focused regression gate.

## Acceptance Criteria

1. **Spring Boot backend epic과 상세 스토리 정합성**
   - **Given** Spring Boot 전용 epic 문서에 BE-2.7이 없을 때
   - **When** BE-2.7이 작성되면
   - **Then** `springboot/docs/springboot-backend-epics.md`에 BE-2.7 원천 항목이 추가된다.
   - **And** 상세 스토리 파일은 `springboot/docs/stories/be-2-7-prototype-growth-bridge-regression-and-be3-preflight.md`에 존재한다.
   - **And** 루트 `_bmad-output`과 sprint status는 변경하지 않는다.

2. **리포트 저장 bridge가 활성 캐릭터만 반응시킨다**
   - **Given** 인증된 사용자가 활성 캐릭터를 보유하고 있을 때
   - **When** `POST /api/game/reports`를 호출하면
   - **Then** report compatibility JSON은 `reports`와 `dailyReport`에 저장된다.
   - **And** 감정과 상태 메시지는 `CharacterCommandService.reactActive(...)` 또는 동등한 locked active lookup 기반 service를 통해 정규화 `characters` row에 반영된다.
   - **And** `game_states.state_json.characters`는 저장본에서 `[]`로 유지된다.
   - **And** 활성 캐릭터가 없으면 report body가 캐릭터 row를 만들거나 JSON 캐릭터를 되살리지 않고, 명확한 no-active behavior를 테스트로 고정한다.

3. **퀴즈 제출 bridge는 점수를 한 번만 반영한다**
   - **Given** starter quiz가 특정 character id를 참조하고 있을 때
   - **When** 사용자가 `POST /api/game/quizzes/{id}/submit`을 처음 성공 호출하면
   - **Then** `scored=true`, `deltaAmount`, `feedback`은 compatibility JSON에 저장된다.
   - **And** 정답/오답 점수 변화량은 `CharacterCommandService.applyScoreDelta(...)`와 `LearningCharacter.applyScoreDelta(...)`를 통해 해당 정규화 character row에 반영된다.
   - **And** `battlePower`는 다섯 능력치 합과 일치하고 `isEvolved`는 1,000점 이상에서 캐릭터당 최대 한 번만 true가 된다.
   - **When** 같은 quiz를 다시 제출하면
   - **Then** 기존 `scored=true` 상태를 반환하되 능력치, 전투력, 감정, 상태 메시지는 다시 증가하거나 변경되지 않는다.

4. **퀴즈 실패와 stale reference는 성장 성공으로 저장되지 않는다**
   - **Given** quiz grading fake failure path가 요청될 때
   - **When** `POST /api/game/quizzes/{id}/submit`에 `fail=true`를 보낸다
   - **Then** 답안은 사라지지 않고 `gradeFailed=true`, `scored=false` 상태로 조회된다.
   - **And** 정규화 캐릭터 능력치와 감정은 바뀌지 않는다.
   - **Given** quiz의 `characterId`가 삭제된 캐릭터나 존재하지 않는 row를 가리킬 때
   - **When** 제출을 처리하면
   - **Then** missing row에 대해 점수 반영 성공 표시(`scored=true`)를 저장하지 않는다.
   - **And** API는 민감정보, stack trace, DB 제약 상세를 노출하지 않는 명시적 fallback/no-op/error behavior를 가진다.

5. **daily-report delivery bridge는 점수를 한 번만 반영한다**
   - **Given** pending report가 있고 `scoreApplied=false`일 때
   - **When** `POST /api/game/daily-report/deliver` 성공 path를 호출하면
   - **Then** `dailyReport.status=ready`, summary, deltas, quizComment, nextRecommendation이 compatibility JSON에 저장된다.
   - **And** deltas는 `CharacterCommandService.applyScoreDeltas(...)`를 통해 정규화 character row에 원자적으로 반영된다.
   - **And** report의 `scoreApplied=true` 저장은 character growth write가 성공한 뒤에만 수행된다.
   - **When** 같은 pending/ready report를 다시 deliver하면
   - **Then** 능력치와 전투력은 다시 증가하지 않는다.

6. **daily-report 실패와 stale reference는 점수 반영을 막는다**
   - **Given** daily-report failure path가 요청될 때
   - **When** `POST /api/game/daily-report/deliver`에 `fail=true`를 보낸다
   - **Then** `dailyReport.status=failed`와 사용자 노출 가능 notice가 저장된다.
   - **And** `scoreApplied`는 true가 되지 않고 캐릭터 stats는 변하지 않는다.
   - **Given** pending report의 `characterId`가 삭제된 캐릭터를 가리킬 때
   - **When** delivery를 처리하면
   - **Then** 존재하는 active character로 동기화할 수 있는 경우에만 growth를 적용한다.
   - **And** 동기화할 active character가 없으면 점수 반영 성공 표시를 저장하지 않는다.

7. **active switch/delete 이후 compatibility reference repair**
   - **Given** 사용자가 pending report, starter quizzes, active character를 가지고 있을 때
   - **When** 활성 캐릭터를 변경하거나 active character를 삭제하면
   - **Then** `dailyReport.characterId`는 새 active id 또는 `null`로 동기화된다.
   - **And** starter quizzes가 삭제된 캐릭터를 참조하던 경우 replacement active가 있으면 그 id로 repair되고, 없으면 missing-row success로 처리하지 않는다.
   - **And** historical `reports`는 일괄 재작성하지 않는다. 기록용 `characterId`는 과거 참조로 남을 수 있지만, future growth target으로 사용하기 전에는 존재 여부를 검증한다.

8. **projection/persistence boundary는 모든 bridge mutation 뒤에도 유지된다**
   - **Given** report save, quiz submit, daily-report deliver, active switch, delete, retry-image가 순서대로 실행될 때
   - **When** DB의 `game_states.state_json`을 직접 조회하면
   - **Then** `characters` field는 항상 배열이고 길이는 0이다.
   - **And** `spriteSheetUrl`, `spriteMeta`, stats, battlePower는 저장된 `game_states.state_json.characters`에 복제되지 않는다.
   - **And** API 응답의 `state.characters`는 항상 `CharacterGameProjectionService`가 정규화 `characters` row에서 overlay한 값이다.

9. **BE-3 handoff 문서 보강**
   - **Given** BE-3 dev agent가 이 스토리 이후 학습 리포트 정규화와 AI 결과 수신을 구현할 때
   - **When** `springboot/docs/character-api-contract.md` 또는 동등한 Spring Boot 문서를 읽으면
   - **Then** 다음 가드레일이 명시되어 있다: locked active lookup, idempotency marker와 growth write의 atomicity, `algo/cs/db/net/fw` API key와 DB column mapping, `applyScoreDelta(s)` 재사용, fallback score delta 정책, `game_states.state_json.characters` 금지.
   - **And** BE-3는 compatibility JSON을 새 System of Record로 확장하지 않고, 새 정규화 테이블을 별도 story에서 Flyway로 만든다는 경계가 분명하다.

10. **회귀 검증**
    - **Given** BE-2.7 변경이 완료되면
    - **When** 테스트를 실행하면
    - **Then** focused bridge regression 예: `./gradlew test --tests com.commitgotchi.character.CharacterPrototypeGrowthBridgeIntegrationTest`가 통과한다.
    - **And** `./gradlew test --tests 'com.commitgotchi.character.*'`가 통과한다.
    - **And** `./gradlew test`가 통과한다.
    - **And** 기존 BE-2.1~BE-2.6 테스트 assertion을 약화하거나 삭제하지 않는다.

## Tasks / Subtasks

- [x] **Task 1: BE-2.7 원천 항목과 상세 문서를 정렬한다** (AC: 1, 9)
  - [x] `springboot/docs/springboot-backend-epics.md`에 BE-2.7 항목을 유지한다.
  - [x] `springboot/docs/character-api-contract.md`에 prototype growth bridge와 BE-3 preflight guardrails를 보강한다.
  - [x] 문서에서 `/api/characters` 같은 신규 경로를 정식 계약으로 소개하지 않는다.
  - [x] 루트 `_bmad-output`, Vue, FastAPI 문서는 수정하지 않는다.

- [x] **Task 2: 리포트 저장 bridge 회귀를 고정한다** (AC: 2, 8)
  - [x] 새 테스트 파일 예: `springboot/src/test/java/com/commitgotchi/character/CharacterPrototypeGrowthBridgeIntegrationTest.java`.
  - [x] 활성 캐릭터가 있을 때 `POST /api/game/reports`가 report/dailyReport compatibility JSON을 저장하고 정규화 캐릭터 emotion/message만 바꾸는지 검증한다.
  - [x] 활성 캐릭터가 없을 때 no-active behavior를 명시적으로 검증한다.
  - [x] 모든 report save 후 `game_states.state_json.characters`가 `[]`임을 DB assertion으로 확인한다.

- [x] **Task 3: 퀴즈 제출 bridge의 단일 반영과 stale reference 방어를 고정한다** (AC: 3, 4, 8)
  - [x] 성공 제출이 `CharacterCommandService.applyScoreDelta(...)`를 통해 stats/battlePower를 바꾸는지 API 응답과 DB row로 검증한다.
  - [x] 같은 quiz 재제출이 stats를 재증가시키지 않는지 검증한다.
  - [x] `fail=true` path가 `gradeFailed=true`, `scored=false`로 남고 stats를 바꾸지 않는지 검증한다.
  - [x] quiz `characterId`가 삭제된 row를 가리키는 상태를 만든 뒤, missing row에 대해 `scored=true`가 저장되지 않는지 검증한다.
  - [x] 필요한 경우 `GameService.submitQuiz(...)`에서 score marker 저장 순서를 growth 성공 이후로 옮긴다.

- [x] **Task 4: daily-report delivery bridge의 단일 반영과 stale reference 방어를 고정한다** (AC: 5, 6, 8)
  - [x] success delivery가 `CharacterCommandService.applyScoreDeltas(...)`를 통해 `algo`, `net` 또는 현재 compatibility deltas를 정규화 row에 반영하는지 검증한다.
  - [x] 재호출 시 `scoreApplied=true`가 중복 성장을 막는지 검증한다.
  - [x] `fail=true` path가 점수를 반영하지 않고 `scoreApplied=true`를 만들지 않는지 검증한다.
  - [x] pending report의 character reference가 삭제된 상태에서 active replacement가 있으면 그 row로 동기화되고, 없으면 growth success marker를 저장하지 않는지 검증한다.
  - [x] 필요한 경우 `GameService.deliverDailyReport(...)`에서 report marker 저장 순서를 growth 성공 이후로 옮긴다.

- [x] **Task 5: active switch/delete 이후 compatibility repair를 보강한다** (AC: 7, 8)
  - [x] `PATCH /api/game/characters/{id}/active` 후 `dailyReport.characterId`가 새 active id로 바뀌는 기존 BE-2.4 계약을 bridge regression에도 포함한다.
  - [x] active character delete 후 starter quizzes와 dailyReport가 replacement active 또는 null로 repair되는지 검증한다.
  - [x] historical reports는 기록으로 보존하되 future growth target으로 쓰기 전에 normalized row 존재 여부를 확인한다는 테스트/문서 가드레일을 추가한다.
  - [x] `syncPendingReportsAfterDeletedReference`, `syncStarterQuizzesAfterDeletedReference`, `syncReportCharacterToExistingOrActive` 계열 helper가 있으면 새 behavior에 맞게 정리하고 이름을 명확히 한다.

- [x] **Task 6: projection/persistence boundary를 bridge suite에 통합한다** (AC: 8, 10)
  - [x] report save, quiz submit, daily delivery, active switch, delete, retry-image를 한 흐름으로 실행하는 smoke를 만든다.
  - [x] 각 mutation 후 `SELECT state_json FROM game_states WHERE user_id=?`로 `characters=[]`를 확인한다.
  - [x] API 응답의 `state.characters`에는 최신 stats, battlePower, emotion, image fields가 보이지만 저장본에는 복제되지 않는지 확인한다.
  - [x] existing `CharacterEpicContractIntegrationTest`와 중복이 심하면 공통 private helper만 공유하고 assertion은 약화하지 않는다.

- [x] **Task 7: 회귀 검증을 실행한다** (AC: 10)
  - [x] `cd springboot && ./gradlew test --tests com.commitgotchi.character.CharacterPrototypeGrowthBridgeIntegrationTest`
  - [x] `cd springboot && ./gradlew test --tests 'com.commitgotchi.character.*'`
  - [x] `cd springboot && ./gradlew test`
  - [x] 실패 시 기존 인증/CORS/Swagger/캐릭터 테스트의 assertion을 약화하지 않고 원인을 수정한다.

### Review Findings

- [x] [Review][Patch] Concurrent quiz submissions can apply the same quiz score more than once [springboot/src/main/java/com/commitgotchi/game/application/GameService.java:181]
- [x] [Review][Patch] Concurrent daily-report deliveries can apply the same report deltas more than once [springboot/src/main/java/com/commitgotchi/game/application/GameService.java:243]
- [x] [Review][Patch] Daily-report delivery can use a stale projected active character during concurrent active switches [springboot/src/main/java/com/commitgotchi/game/application/GameService.java:682]

## Dev Notes

### Source Requirements

- Spring Boot backend epics의 BE-2.7은 사용자의 직접 요청으로 추가된 BE-2 마감 전 preflight 스토리다.
- PRD FR-8은 사용자가 활성 캐릭터 기준으로 그날의 학습 리포트를 작성/저장할 수 있어야 한다고 정의한다.
- PRD FR-11, FR-21은 점수 변화량이 활성 캐릭터 능력치에 일 단위로 한 번만 누적되고 전투력은 다섯 능력치 합과 일치해야 한다고 정의한다.
- PRD FR-16은 AI 단일 실패가 리포트/퀴즈/캐릭터 흐름을 중단하지 않아야 한다고 정의한다.
- PRD FR-22, FR-23은 전투력 1,000점 이상에서 캐릭터당 최대 한 번 진화하고 학습/채점 결과가 감정과 상태 메시지에 반영되어야 한다고 정의한다.
- `springboot/docs/character-api-contract.md`는 `characters` table이 SoR이고 `game_states.state_json.characters`는 persisted compatibility placeholder라고 고정한다.
- BE-2.6 review는 quiz scoring과 daily report delivery가 character row disappearance를 성공으로 저장하면 안 된다고 이미 지적했다. BE-2.7은 이 behavior를 focused regression으로 잠근다.

### Developer Context

- 인증 에픽은 완료되어 `AuthPrincipal`, JWT Access Token, Refresh Token rotation, 공통 오류 응답, CORS, Swagger profile gating이 동작한다.
- BE-2.1은 `characters` 테이블, `LearningCharacter`, image status enum, partial unique index, stat/battle power DB 제약을 만들었다.
- BE-2.2는 `POST /api/game/characters`와 `GET /api/game/state`를 normalized character projection에 연결하고, 새 캐릭터가 active가 되는 정책을 고정했다.
- BE-2.3은 상세 조회/수정/삭제와 not-found 계약, editable/system-owned field 경계, active 삭제 후 재지정, daily report repair를 고정했다.
- BE-2.4는 `PATCH /api/game/characters/{id}/active`를 정식 계약으로 만들고, explicit active switch가 daily report slot을 교체하도록 했다.
- BE-2.5는 image adapter/fallback/retry 계약을 구현했다. local/test 기본값은 FastAPI 없이 `FALLBACK` sprite를 저장한다.
- BE-2.6은 `/api/game/**` 캐릭터 계약, OpenAPI, projection/persistence boundary, BE-3 handoff guardrails를 문서와 regression으로 고정했다.
- 현재 `GameService`는 BE-3~BE-6 prototype bridge를 계속 품고 있다. BE-2.7은 이 prototype을 정규화 도메인으로 완전히 전환하지 않고, BE-3 착수 전 깨지면 위험한 성장 bridge만 회귀 고정한다.

### Current Files To Update

- `springboot/docs/springboot-backend-epics.md`
  - 현재 역할: Spring Boot 전용 backend epic/story SSOT.
  - 변경 목표: BE-2.7 원천 항목과 권장 진행 순서를 유지한다.
  - 보존: `springboot/` only 원칙, BE-3~BE-7 기존 계획.

- `springboot/docs/character-api-contract.md`
  - 현재 역할: BE-2 캐릭터 API, image status, projection/persistence boundary, BE-3 handoff guardrails.
  - 변경 목표: prototype report/quiz/daily-report bridge가 BE-3 전까지 어떤 경계에서 동작하는지 추가한다.
  - 보존: 실제 공개 경로는 `/api/game/**`이며 신규 `/api/characters`를 정식화하지 않는다.

- `springboot/src/main/java/com/commitgotchi/game/application/GameService.java`
  - 현재 역할: `/api/game/**` facade. 정규화 character projection을 overlay하고, `reports`, `quizzes`, `dailyReport`, `boardPosts` compatibility JSON을 저장한다.
  - 변경 목표: report/quiz/daily bridge에서 missing character row를 성공으로 마킹하지 않고, score marker 저장 순서를 growth write 성공 이후로 고정한다.
  - 보존: `GameMutationResponse(state,item)` shape, `Asia/Seoul` date behavior, `stringifyForPersistence(...)`의 characters 비저장 정책.

- `springboot/src/main/java/com/commitgotchi/character/application/CharacterCommandService.java`
  - 현재 역할: update/find/activate/delete/react/applyScoreDelta(s) application service.
  - 변경 목표: 가능하면 기존 메서드를 재사용한다. BE-2.7에서 새 growth service를 만들 필요가 생기면 이 service 위에 얇게 둔다.
  - 보존: `findByIdAndUserIdForUpdate`, `findActiveByUserIdForUpdate`, `LearningCharacter.applyScoreDelta(...)`, `LearningCharacter.react(...)` 경계.

- `springboot/src/main/java/com/commitgotchi/character/application/CharacterGameProjectionService.java`
  - 현재 역할: Vue-compatible character DTO를 만든다.
  - 변경 목표: 없음이 기대값이다. bridge regression에서 이 projection이 응답 전용 overlay임을 검증한다.
  - 보존: stat key mapping `algo/cs/db/net/fw`, lower-case emotion, sprite fields.

- `springboot/src/test/java/com/commitgotchi/character/CharacterEpicContractIntegrationTest.java`
  - 현재 역할: BE-2 end-to-end smoke와 persistence-boundary regression.
  - 변경 목표: 중복이 작으면 BE-2.7 assertion 일부를 추가할 수 있지만, 권장안은 별도 bridge suite를 둔다.
  - 보존: create/read/update/delete/active/retry/OpenAPI 성격의 기존 assertion.

### Expected New Files

```text
springboot/src/test/java/com/commitgotchi/character/
└── CharacterPrototypeGrowthBridgeIntegrationTest.java
```

구현 중 더 명확한 이름을 선택해도 된다. 다만 테스트 목적은 파일명에서 report/quiz/daily bridge와 normalized character growth boundary가 드러나야 한다.

### Implementation Guardrails

- 새 DB migration을 만들지 않는다. BE-3에서 study report/quiz schema를 설계할 때 별도 story로 Flyway를 추가한다.
- `game_states.state_json.characters`를 되살리지 않는다. 저장 시 `[]` 유지가 BE-2.7의 핵심 regression이다.
- 점수 반영은 raw SQL, JSON mutation, projection object mutation으로 처리하지 않는다.
- `quiz.scored=true`와 `report.scoreApplied=true`는 정규화 character row 성장 write가 성공한 뒤 저장한다.
- failure path는 사용자 흐름을 중단하지 않되, "성공적으로 점수 반영됨"으로 거짓 저장하면 안 된다.
- stale compatibility reference는 historical record로 남을 수 있지만, future growth target으로 쓰기 전에는 normalized row 존재 여부를 확인한다.
- `build.gradle` dependency upgrade는 기대값이 아니다. Spring Boot `3.3.5`, Java 17, springdoc `2.6.0` 현재 조합을 유지한다.
- tests는 `PostgresIntegrationTest`, 실제 Flyway, 실제 Spring Security filter chain, MockMvc를 사용한다. H2나 mock repository로 대체하지 않는다.
- concurrency test를 추가한다면 `Future.get(5, TimeUnit.SECONDS)` 같은 bounded wait를 사용한다.

### Suggested Bridge Regression Outline

```text
CharacterPrototypeGrowthBridgeIntegrationTest
├── saveReportUpdatesNormalizedEmotionButPersistsNoCharacters()
├── submitQuizAppliesScoreOnceAndNeverMutatesStoredCharacters()
├── submitQuizFailureAndStaleCharacterDoNotMarkScored()
├── deliverDailyReportAppliesDeltasOnceAndMarksAppliedAfterGrowth()
├── deliverDailyReportFailureAndMissingCharacterDoNotMarkScoreApplied()
└── activeSwitchAndDeleteRepairFutureGrowthTargetsWithoutRewritingHistory()
```

### Latest Technical Information

- The project currently pins Spring Boot `3.3.5`, Java 17, and `org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0`; BE-2.7 should not perform dependency maintenance.
- Spring Data JPA official docs describe `@Lock` on repository query methods for selecting a lock mode. Existing `LearningCharacterRepository` methods already use `PESSIMISTIC_WRITE`; reuse that model for active/growth consistency. Source: <https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html>
- Spring Framework official docs describe `RestClient` as a synchronous fluent HTTP client and note `RestTemplate` is deprecated in Spring Framework 7. BE-2.7 does not need new HTTP clients because image work is already behind `CharacterImageClient`. Source: <https://docs.spring.io/spring-framework/reference/integration/rest-clients.html>
- springdoc official docs say `@Parameter(hidden = true)` is a workaround for hiding `@AuthenticationPrincipal`. Preserve the BE-2.6 OpenAPI convention if bridge endpoints are documented later. Source: <https://springdoc.org/>

### References

- [springboot/docs/springboot-backend-epics.md](../springboot-backend-epics.md) - BE-2.7 source story and springboot-only principles.
- [springboot/docs/character-api-contract.md](../character-api-contract.md) - BE-2 public contract and BE-3 handoff guardrails.
- [springboot/docs/stories/be-2-6-character-epic-contract-regression-and-be3-handoff.md](be-2-6-character-epic-contract-regression-and-be3-handoff.md) - previous story and review findings.
- [springboot/src/main/java/com/commitgotchi/game/application/GameService.java](../../src/main/java/com/commitgotchi/game/application/GameService.java) - report/quiz/daily compatibility bridge and persistence boundary.
- [springboot/src/main/java/com/commitgotchi/character/application/CharacterCommandService.java](../../src/main/java/com/commitgotchi/character/application/CharacterCommandService.java) - locked character mutation/growth service.
- [springboot/src/main/java/com/commitgotchi/character/domain/LearningCharacter.java](../../src/main/java/com/commitgotchi/character/domain/LearningCharacter.java) - stat, battlePower, evolution, image, reaction domain rules.
- [springboot/src/main/java/com/commitgotchi/character/domain/LearningCharacterRepository.java](../../src/main/java/com/commitgotchi/character/domain/LearningCharacterRepository.java) - ownership-aware and locked lookups.
- [springboot/src/main/java/com/commitgotchi/character/application/CharacterGameProjectionService.java](../../src/main/java/com/commitgotchi/character/application/CharacterGameProjectionService.java) - response-only normalized projection.
- [springboot/src/test/java/com/commitgotchi/character/CharacterEpicContractIntegrationTest.java](../../src/test/java/com/commitgotchi/character/CharacterEpicContractIntegrationTest.java) - existing BE-2 smoke and persistence-boundary gate.
- [_bmad-output/planning-artifacts/prds/prd-commitgotchi-2026-06-07/prd.md](../../../_bmad-output/planning-artifacts/prds/prd-commitgotchi-2026-06-07/prd.md) - FR-8, FR-11, FR-16, FR-21~23 reference only.
- [_bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md](../../../_bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md) - transactional growth and integration patterns reference only.

## Dev Agent Record

### Agent Model Used

GPT-5 Codex dev-story implementation agent

### Debug Log References

- Loaded BMad config from `_bmad/bmm/config.yaml`: communication/document language Korean; planning artifacts under `_bmad-output/planning-artifacts`; implementation artifacts under `_bmad-output/implementation-artifacts`.
- Workflow activation resolved with no prepend/append steps and persistent fact glob `**/project-context.md`; no project-context file was found.
- User explicitly selected BE 2.7 and constrained writes to `/springboot`; this story was written under `springboot/docs/stories/` and sprint status outside `springboot/` was not modified.
- BE-2.7 was not present in the Spring Boot backend epic document, so a new BE-2.7 source entry was added under `springboot/docs/springboot-backend-epics.md`.
- Discovered inputs: Spring Boot backend epics, root PRD, root architecture, root epics, previous BE-2.1~BE-2.6 stories, current Spring Boot code/tests, and official Spring/Spring Data/springdoc docs.
- 2026-06-18: Dev workflow activated for BE-2.7; no sprint status exists, story progress is tracked inside `springboot/docs/stories/be-2-7-prototype-growth-bridge-regression-and-be3-preflight.md`.
- 2026-06-18: Task 1 docs check completed with `rg`; `/api/characters` remains documented as not official, and prototype growth bridge guardrails were added to `springboot/docs/character-api-contract.md`.
- 2026-06-18: RED confirmed with `./gradlew test --tests com.commitgotchi.character.CharacterPrototypeGrowthBridgeIntegrationTest`; stale quiz and stale daily-report tests failed against the pre-fix implementation.
- 2026-06-18: GREEN confirmed after `GameService` marker-order fix; focused suite, `com.commitgotchi.character.*`, and full `./gradlew test` all passed.

### Completion Notes List

- Ultimate context engine analysis completed - comprehensive developer guide created.
- Status set to `ready-for-dev`.
- Story intentionally creates a BE-3 preflight safety gate, not a new BE-3 domain implementation.
- Key risks captured: double score application, stale compatibility references, false `scored`/`scoreApplied` success markers, `game_states.state_json.characters` regression, raw SQL/JSON stat mutation, and accidental dependency/schema expansion.
- Sprint status was not updated because the only writable scope requested by the user is `/springboot`.
- Task 1 complete: Spring Boot backend epic status reflects BE-2.7 review, and the character API contract now documents BE-2.7 bridge idempotency, stale reference, failure marker, and BE-3 handoff guardrails.
- Added `CharacterPrototypeGrowthBridgeIntegrationTest` covering report save/no-active behavior, quiz success/idempotency/failure/stale reference, daily-report success/idempotency/failure/stale reference, active switch/delete repair, retry-image, and `game_states.state_json.characters=[]` persistence assertions.
- Updated `GameService.submitQuiz(...)` so `scored=true` is stored only after normalized character growth succeeds; stale quiz references now remain `scored=false`, `gradeFailed=true`.
- Updated `GameService.deliverDailyReport(...)` so `scoreApplied=true` is stored only after normalized character growth succeeds; missing character/no-active delivery now returns `dailyReport.status=failed` without a success marker.
- Regression validation passed: focused BE-2.7 suite, all `com.commitgotchi.character.*` tests, and full Spring Boot test suite.
- Code review patch findings resolved: `game_states` write paths now use a pessimistic write lookup for compatibility JSON mutations, and concurrent quiz/daily-report submissions are covered by focused regression tests.

### File List

- `springboot/docs/springboot-backend-epics.md`
- `springboot/docs/character-api-contract.md`
- `springboot/docs/stories/be-2-7-prototype-growth-bridge-regression-and-be3-preflight.md`
- `springboot/src/main/java/com/commitgotchi/game/application/GameService.java`
- `springboot/src/test/java/com/commitgotchi/character/CharacterPrototypeGrowthBridgeIntegrationTest.java`

## Change Log

- 2026-06-18: BE-2.7 story written as `ready-for-dev` under `springboot/docs/stories/`, respecting the springboot-only write constraint.
- 2026-06-18: BE-2.7 implemented and marked `review`; prototype growth bridge regressions added and stale success markers fixed in Spring Boot only.
