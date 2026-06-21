---
story_id: BE-2.4
story_key: be-2-4-active-character-selection
epic: BE-2
status: done
created: 2026-06-18
scope: springboot-only
previous_story: be-2-3-character-read-update-delete
baseline_commit: 14a2069c7dc410b6f985dbb541ca9df1813fb35a
---

# Story BE-2.4: 활성 캐릭터 지정

Status: done

## Story

As a 사용자,
I want 보유 캐릭터 중 하나를 활성 캐릭터로 지정하고,
so that 이후 학습 점수와 대시보드 상태가 올바른 캐릭터를 기준으로 반영되게 할 수 있다.

## 목적과 범위

- `springboot/` 내부만 변경한다. Vue, FastAPI, `_bmad-output`은 참고만 한다.
- `PATCH /api/game/characters/{id}/active` 호환 엔드포인트를 BE-2.4의 정식 계약으로 고정한다.
- BE-2.1~BE-2.3에서 만든 `characters` 정규화 테이블, `LearningCharacter`, `LearningCharacterRepository`, `CharacterCommandService`, `CharacterGameProjectionService`, `/api/game/**` facade를 재사용한다.
- 새 활성 캐릭터 지정 시 기존 활성 캐릭터는 자동 해제되고, DB와 응답 projection 모두에서 사용자당 활성 캐릭터는 최대 1개만 남아야 한다.
- 이미 활성인 자기 캐릭터를 다시 활성화하는 요청은 멱등 성공으로 처리한다.
- 없는 id, 숫자로 파싱할 수 없는 id, 다른 사용자의 캐릭터 id는 존재 여부를 노출하지 않고 HTTP `404 NOT_FOUND` 공통 오류 응답으로 처리한다.
- 활성 지정 후 `/api/game/state`의 `state.characters`와 현재 daily report compatibility slot은 새 활성 캐릭터를 가리켜야 한다.
- 비범위: 캐릭터 생성 정책 변경, 삭제 후 자동 재지정 정책 변경, 이미지 생성/재시도 구현, 리포트/퀴즈 정규화 테이블, Vue/FastAPI 수정, `game_states` 제거.

## Acceptance Criteria

1. **활성 캐릭터 지정 성공**
   - **Given** 인증된 사용자가 캐릭터를 2개 이상 보유하고 하나가 active 상태일 때
   - **When** `PATCH /api/game/characters/{id}/active`로 자신의 비활성 캐릭터 id를 요청하면
   - **Then** 요청한 캐릭터가 `active=true`가 된다.
   - **And** 기존 활성 캐릭터는 `active=false`가 된다.
   - **And** 성공 응답은 기존 `GameMutationResponse` 형태인 `{ "state": ..., "item": ... }`를 유지한다.
   - **And** `item.id`는 새 활성 캐릭터 id이고 `item.active=true`다.
   - **And** `state.characters`에는 해당 사용자의 캐릭터만 포함되며 활성 캐릭터는 정확히 1개다.

2. **DB 불변식과 트랜잭션**
   - **Given** 활성 변경 요청이 처리될 때
   - **When** 기존 활성 해제와 신규 활성 지정이 수행되면
   - **Then** 하나의 application service transaction에서 완료된다.
   - **And** PostgreSQL 부분 유니크 인덱스 `uq_one_active_character_per_user` 위반이 발생하지 않도록 기존 active 해제 후 flush, 신규 active 지정 순서를 유지한다.
   - **And** 최종 DB 상태는 `SELECT count(*) FROM characters WHERE user_id=? AND is_active=true`가 항상 `1`이다.

3. **이미 활성인 캐릭터 재지정은 멱등 성공**
   - **Given** 사용자가 이미 active인 자기 캐릭터 id를 알고 있을 때
   - **When** 같은 id로 `PATCH /api/game/characters/{id}/active`를 다시 호출하면
   - **Then** HTTP 200과 `{ state, item }`을 반환한다.
   - **And** DB active count는 `1`로 유지된다.
   - **And** 능력치, 전투력, 진화 상태, 감정, 이미지 상태, 이름/키워드/성격은 변경되지 않는다.

4. **소유권과 Not Found 계약**
   - **Given** 두 사용자가 각각 캐릭터를 보유하고 있을 때
   - **When** 한 사용자가 다른 사용자의 캐릭터를 활성화하려고 하면
   - **Then** HTTP `404 NOT_FOUND` 공통 오류 응답을 반환한다.
   - **And** 없는 id와 숫자로 파싱할 수 없는 id도 동일하게 `404 NOT_FOUND`다.
   - **And** 실패 응답/로그에는 JWT, Authorization header, stack trace, 내부 SQL 제약 상세가 포함되지 않는다.
   - **And** 기존 prototype 스타일의 200 응답 + `item: null`로 실패를 숨기지 않는다.

5. **인증 경계**
   - **Given** 인증되지 않은 요청이 `PATCH /api/game/characters/{id}/active`를 호출할 때
   - **Then** 기존 Spring Security 계약대로 HTTP `401 AUTH_ACCESS_TOKEN_MISSING` 공통 오류 응답을 반환한다.
   - **And** Controller 또는 service 테스트만으로 대체하지 않고 실제 Security filter chain을 통과하는 MockMvc 통합 테스트로 검증한다.

6. **Daily report compatibility slot 동기화**
   - **Given** 사용자가 오늘의 `state.dailyReport.characterId`가 비어 있거나 이전 활성 캐릭터를 가리키는 상태에서 활성 캐릭터를 변경할 때
   - **When** 활성 지정이 성공하면
   - **Then** 응답과 이후 `GET /api/game/state`의 `state.dailyReport.characterId`는 새 활성 캐릭터 id를 가리킨다.
   - **And** 이후 `POST /api/game/reports`, `POST /api/game/daily-report/deliver`가 active character projection과 충돌하지 않는다.
   - **And** 이미 저장된 historical `reports`, `quizzes`, `boardPosts` compatibility data를 일괄 재작성하지 않는다.

7. **동시 활성화 요청**
   - **Given** 사용자가 캐릭터 3개를 보유하고 있을 때
   - **When** 서로 다른 캐릭터 id에 대한 활성화 요청 2건 이상이 동시에 들어오면
   - **Then** 모든 성공/실패 응답 이후 최종 DB active count는 `1`이다.
   - **And** 최종 활성 id는 요청된 사용자 소유 캐릭터 중 하나다.
   - **And** `GET /api/game/state` projection에도 active character가 정확히 1개만 보인다.
   - **And** `DataIntegrityViolationException`, deadlock, 무한 대기, 500 응답이 없어야 한다.

8. **기존 회귀 보존**
   - **Given** BE-2.2 생성/첫 활성화, BE-2.3 조회/수정/삭제, report/quiz/daily mutation bridge가 존재할 때
   - **When** BE-2.4가 완료되면
   - **Then** `POST /api/game/characters`, `GET /api/game/state`, `GET/PATCH/DELETE /api/game/characters/{id}`, report/quiz/daily 회귀가 계속 통과한다.
   - **And** `build.gradle`, `V4__create_characters.sql`, 인증/CORS/Swagger 설정은 직접 필요가 없는 한 변경하지 않는다.

## Tasks / Subtasks

- [x] **Task 1: 활성 지정 실패 계약을 404로 고정한다** (AC: 4, 5, 8)
  - [x] `GameService.setActiveCharacter(...)`에서 malformed id를 `CharacterNotFoundException`으로 처리한다.
  - [x] 타 사용자 id와 없는 id도 `CharacterCommandService.activate(...)`의 empty result를 `CharacterNotFoundException`으로 변환한다.
  - [x] `retry-image` 같은 다른 prototype endpoint의 200/null 정책은 이 스토리에서 임의 변경하지 않는다.
  - [x] `GlobalExceptionHandler`의 `CharacterNotFoundException -> NOT_FOUND` 기존 매핑을 재사용한다.

- [x] **Task 2: 활성 변경 트랜잭션을 기존 도메인 서비스 위에서 보강한다** (AC: 1, 2, 3, 7)
  - [x] `CharacterCommandService.activate(userId, characterId)`를 계속 중심 유스케이스로 사용한다.
  - [x] 사용자 row lock(`UserRepository.findByIdForUpdate`)을 먼저 잡아 같은 사용자의 create/activate/delete 흐름을 직렬화한다.
  - [x] target character는 `findByIdAndUserIdForUpdate`로 소유권과 row lock을 함께 검증한다.
  - [x] 기존 active 캐릭터를 모두 `deactivate()`하고 `characterRepository.flush()` 후 target `activate()`를 저장한다.
  - [x] target이 이미 active여도 멱등 성공해야 하며, active count와 system-owned fields가 변하지 않아야 한다.
  - [x] raw SQL bulk update로 active flag를 직접 바꾸지 않는다. `LearningCharacter.activate/deactivate` 도메인 메서드를 사용한다.

- [x] **Task 3: `/api/game/state`와 daily report compatibility slot을 새 active로 동기화한다** (AC: 1, 6, 8)
  - [x] 활성 지정 성공 후 `state.characters`는 `CharacterGameProjectionService.projectCharacters(userId)`로 다시 overlay한다.
  - [x] 활성 지정 성공 후 `state.dailyReport.characterId`는 새 활성 캐릭터 id로 교체한다. 현재 `syncDailyReportCharacter`처럼 null일 때만 채우는 동작이면 BE-2.4 계약을 만족하지 못한다.
  - [x] completed/historical `reports`, `quizzes`, `boardPosts` 배열은 bulk rewrite하지 않는다.
  - [x] 이후 `saveReport`와 `deliverDailyReport`가 새 active 기준으로 동작하는지 integration test로 연결 확인한다.
  - [x] `game_states.state_json.characters`에는 계속 빈 배열을 저장한다. 정규화 projection을 JSON SoR로 복제하지 않는다.

- [x] **Task 4: 활성 지정 API 통합 테스트를 추가한다** (AC: 1~8)
  - [x] 새 테스트 파일 `springboot/src/test/java/com/commitgotchi/character/CharacterActivationApiIntegrationTest.java`를 추가하거나, 기존 character API 테스트를 명확한 activation 섹션으로 확장한다.
  - [x] `PostgresIntegrationTest`, `@SpringBootTest`, `@AutoConfigureMockMvc`, `AdminTestFixture`의 실제 JWT 로그인 fixture를 재사용한다.
  - [x] 성공: 3개 캐릭터 중 비활성 캐릭터를 active로 만들고 `item`, `state.characters`, DB active count/id를 검증한다.
  - [x] 멱등: 이미 active인 id를 다시 호출해 200, active count 1, system-owned fields 불변을 검증한다.
  - [x] 실패: malformed id, missing id, cross-owner id가 모두 `404 NOT_FOUND`이고 응답에 Authorization/header/stack trace가 없음을 검증한다.
  - [x] 인증 없음: `401 AUTH_ACCESS_TOKEN_MISSING`을 검증한다.
  - [x] daily report: 활성 전환 후 `state.dailyReport.characterId`와 이후 `GET /api/game/state`가 새 active id를 반환하는지 검증한다.

- [x] **Task 5: 동시 활성화 회귀 테스트를 추가한다** (AC: 2, 7)
  - [x] 캐릭터 3개를 만든 뒤 서로 다른 id로 `PATCH /api/game/characters/{id}/active` 요청 2건 이상을 `ExecutorService`와 `CountDownLatch`로 동시에 시작한다.
  - [x] `Future.get(5, TimeUnit.SECONDS)` 같은 timeout을 사용해 테스트가 무한 대기하지 않게 한다.
  - [x] 모든 응답이 2xx 또는 명시적 공통 오류 응답인지 확인하고, 500이 없음을 검증한다.
  - [x] 최종 `SELECT count(*) ... is_active=true`가 1이고, 최종 active id가 해당 사용자 소유 캐릭터 중 하나인지 검증한다.
  - [x] 최종 `GET /api/game/state`의 active projection도 정확히 1개인지 검증한다.

- [x] **Task 6: 전체 회귀를 검증한다** (AC: 8)
  - [x] 우선 activation 테스트만 실행한다: `cd springboot && ./gradlew test --tests com.commitgotchi.character.CharacterActivationApiIntegrationTest`.
  - [x] 이어서 character 에픽 회귀를 실행한다: `cd springboot && ./gradlew test --tests com.commitgotchi.character.*`.
  - [x] 최종 전체 회귀를 실행한다: `cd springboot && ./gradlew test`.
  - [x] 실패 시 인증/CORS/Swagger 테스트를 약화하지 말고 원인을 수정한다.

### Review Findings

- [x] [Review][Patch] Pending pre-switch reports can restore the previous active character into `dailyReport` [springboot/src/main/java/com/commitgotchi/game/application/GameService.java:226]
- [x] [Review][Patch] Concurrent activation test accepts unverified 4xx responses [springboot/src/test/java/com/commitgotchi/character/CharacterActivationApiIntegrationTest.java:220]

## Dev Notes

### Source Requirements

- PRD FR-7은 사용자가 보유 캐릭터 중 하나를 활성 캐릭터로 지정할 수 있고, 새 활성 지정 시 기존 활성은 자동 해제되어야 한다고 정의한다.
- PRD 용어집은 보유 캐릭터가 있을 때 활성 캐릭터가 현재 학습 점수 반영 대상이라고 정의한다.
- Spring Boot backend epics의 BE-2.4는 `/api/game/characters/{id}/active` 호환 엔드포인트와 도메인 서비스 테스트를 완료 기준으로 둔다.
- Architecture §5.2는 활성 단일성을 PostgreSQL partial unique index로 강제하고, 새 활성 지정은 기존 활성 해제 후 신규 활성 지정 순서의 단일 트랜잭션으로 수행하라고 명시한다.

### Developer Context

- 인증 에픽은 완료되어 `AuthPrincipal`, JWT Access Token, Refresh Token rotation, 공통 오류 응답, CORS, Swagger 프로필 제어가 동작한다.
- BE-2.1은 `characters` 정규화 테이블, `LearningCharacter`, `CharacterEmotion`, `CharacterImageStatus`, `LearningCharacterRepository`, PostgreSQL partial unique index `uq_one_active_character_per_user`를 도입했다.
- BE-2.2는 `POST /api/game/characters`와 `GET /api/game/state`를 정규화 캐릭터 projection에 연결했고, 생성 정책을 "새로 생성한 캐릭터가 active가 된다"로 고정했다.
- BE-2.3은 상세 조회, 수정, 삭제의 not-found 계약을 `404 NOT_FOUND`로 고정했고, 활성 삭제 시 최신 남은 캐릭터 자동 활성화와 daily report 동기화를 구현했다.
- 현재 활성 endpoint는 이미 `GameController.setActiveCharacter(...)`, `GameService.setActiveCharacter(...)`, `CharacterCommandService.activate(...)`로 존재한다. 이 스토리는 새 endpoint를 만들기보다 실패 계약, daily report 동기화, 동시성 검증을 보강한다.
- 현재 `GameService.setActiveCharacter(...)`는 malformed/missing/cross-owner id에서 200 + `item: null`을 반환할 수 있다. BE-2.4에서는 character CRUD의 최신 계약과 맞춰 `404 NOT_FOUND`로 바꿔야 한다.
- 현재 `GameService.setActiveCharacter(...)`는 `syncDailyReportCharacter`를 호출해 `dailyReport.characterId`가 null일 때만 채운다. 활성 전환 후 이후 학습 점수가 새 대상에 반영되려면 성공 시 새 active id로 교체해야 한다.

### Current Files To Update

- `springboot/src/main/java/com/commitgotchi/game/api/GameController.java`
  - 현재 역할: `/api/game/characters/{id}/active`를 이미 노출하고 `GameService`에 위임한다.
  - 변경 목표: 경로와 HTTP method는 유지한다. 특별한 이유가 없으면 Controller 변경은 필요하지 않다.
  - 보존: `/api/game` base path, `@AuthenticationPrincipal AuthPrincipal`, 기존 create/read/update/delete/report/quiz/board 경로.

- `springboot/src/main/java/com/commitgotchi/game/application/GameService.java`
  - 현재 역할: state load 후 정규화 projection을 overlay하고, 활성 지정 성공 시 `GameMutationResponse(state,item)`을 반환한다.
  - 변경 목표: `setActiveCharacter`에서 malformed/missing/cross-owner id를 `CharacterNotFoundException`으로 변환한다. 활성 지정 성공 시 daily report compatibility slot을 새 active id로 교체한다.
  - 보존: `GameMutationResponse(state,item)` shape, `game_states`의 reports/quizzes/dailyReport/boardPosts compatibility data, `stringifyForPersistence`의 characters 비저장 정책.

- `springboot/src/main/java/com/commitgotchi/character/application/CharacterCommandService.java`
  - 현재 역할: update/activate/delete/retryImage/react/applyScoreDelta 흐름을 제공한다.
  - 변경 목표: `activate(...)`가 사용자 row lock, ownership-aware target lock, 기존 active 해제/flush, target active/save 순서를 명확히 유지하는지 확인하고 부족하면 보강한다.
  - 보존: `findByIdAndUserIdForUpdate` ownership check, `UserRepository.findByIdForUpdate` 기반 사용자 단위 직렬화, `LearningCharacter.activate/deactivate` 도메인 메서드 사용.

- `springboot/src/main/java/com/commitgotchi/character/domain/LearningCharacterRepository.java`
  - 현재 역할: 사용자별 정렬 목록, count, owned lookup, active lookup, pessimistic lock lookup을 제공한다.
  - 변경 목표: 기존 메서드로 충분하면 변경하지 않는다. 동시성 테스트에서 잠금 범위가 부족하면 `findAllByUserIdForUpdateOrderByCreatedAtDesc`를 active 변경에도 사용한다.
  - 보존: ownership을 드러내는 `...UserId...` 네이밍과 `createdAt DESC, id DESC` deterministic ordering.

- `springboot/src/test/java/com/commitgotchi/character/CharacterActivationApiIntegrationTest.java`
  - 현재 상태: 없음.
  - 변경 목표: BE-2.4의 성공, 멱등, 실패, 인증, daily report, 동시성 계약을 한 파일에서 읽히게 고정한다.
  - 보존: `CharacterCreationApiIntegrationTest`와 `CharacterReadUpdateDeleteApiIntegrationTest`의 기존 회귀를 약화하지 않는다.

### Expected New Files

```text
springboot/src/test/java/com/commitgotchi/character/
└── CharacterActivationApiIntegrationTest.java
```

구현이 작은 경우 기존 `CharacterCreationApiIntegrationTest`의 활성화 테스트를 보강할 수도 있다. 다만 BE-2.4는 독립 스토리이므로 새 파일이 리뷰와 회귀 추적에 더 명확하다.

### Suggested API Contract

`PATCH /api/game/characters/{id}/active`

Success:

```json
{
  "state": {
    "characters": [
      {
        "id": 3,
        "name": "새 활성 캐릭터",
        "keyword": "초록 학습 슬라임",
        "personality": "다정하지만 정확한 성격",
        "stats": { "algo": 0, "cs": 0, "db": 0, "net": 0, "fw": 0 },
        "battlePower": 0,
        "emotion": "joy",
        "isEvolved": false,
        "imageStatus": "PENDING",
        "active": true,
        "message": "Ready to learn",
        "createdAt": "2026-06-18T02:30:00Z"
      }
    ],
    "dailyReport": {
      "status": "pending",
      "date": "2026-06-18",
      "characterId": 3
    }
  },
  "item": {
    "id": 3,
    "active": true
  }
}
```

Failure examples:

- 다른 사용자 id, 없는 id, non-numeric id: HTTP `404`, `code=NOT_FOUND`
- 인증 없음: HTTP `401`, `code=AUTH_ACCESS_TOKEN_MISSING`
- 부분 유니크 인덱스 충돌이 예외로 새면 안 된다. 발생한다면 구현 순서/락이 잘못된 것이다.

### Implementation Guardrails

- 새 character table, 새 aggregate, `game_states.state_json.characters` 기반 active 상태를 만들지 않는다.
- `PATCH /api/game/characters/{id}/active`를 request body 기반 API로 바꾸지 않는다. 현재 Vue 호환 경로는 path id만 사용한다.
- active 변경 실패를 200/null로 반환하지 않는다. character read/update/delete와 같은 `404 NOT_FOUND` 계약을 사용한다.
- target 캐릭터 소유권 검사를 `findById` 후 user 비교로 흩뜨리지 않는다. `findByIdAndUserIdForUpdate`를 사용한다.
- active 변경에서 능력치, 전투력, 진화, 감정, image status, 이름/키워드/성격을 변경하지 않는다.
- active 변경은 report/quiz 점수를 즉시 이동하지 않는다. 점수 반영은 이후 요청이 바라보는 active/current character 기준으로 처리한다.
- active 변경 성공 시 daily report compatibility slot은 새 active id로 맞춘다. historical reports/quizzes/boardPosts는 이 스토리에서 일괄 재작성하지 않는다.
- `build.gradle`에 새 의존성을 추가하지 않는다. 현재 Web/JPA/Validation/Security/Flyway/PostgreSQL/Testcontainers로 충분하다.
- `V4__create_characters.sql`은 변경하지 않는 것이 기대값이다. active uniqueness는 이미 `uq_one_active_character_per_user`로 존재한다.
- 동시성 테스트는 timeout을 둔다. 이전 BE-2.3 review에서 concurrent test 무한 대기 리스크를 이미 한 번 고쳤다.

### Architecture Compliance

- Spring Boot가 PostgreSQL System of Record를 소유한다. FastAPI는 이 DB를 읽거나 쓰지 않는다.
- API layer는 repository를 직접 노출하지 않는다.
- Application service가 transaction boundary를 가진다.
- Domain aggregate가 active flag 변경 메서드(`activate`, `deactivate`)를 가진다.
- 활성 캐릭터 최대 1개 불변식은 PostgreSQL partial unique index와 service transaction 양쪽에서 보호한다.
- `ddl-auto=validate` 정책을 유지한다.
- 기존 `/api/game/**` facade는 Vue 호환 계층이다. 활성 캐릭터의 진실은 `characters.is_active`다.

### Library and Framework Requirements

- 프로젝트 버전을 유지한다: Spring Boot `3.3.5`, Java 17, Spring Data JPA, Spring Security, Flyway, PostgreSQL, Testcontainers.
- 공식 Spring Data JPA current 문서는 repository query method와 redeclared CRUD method에 `@Lock`을 사용할 수 있음을 설명한다. 이 스토리는 기존 `@Lock(PESSIMISTIC_WRITE)` 패턴을 유지한다.
- 공식 PostgreSQL current 문서는 partial unique index가 predicate를 만족하는 row subset에만 uniqueness를 강제할 수 있음을 설명한다. `uq_one_active_character_per_user`가 이 요구사항의 DB primitive다.
- 공식 Spring Security current 문서는 MockMvc 통합 테스트 지원을 제공한다. 활성 endpoint의 인증/인가 계약은 기존 `spring-security-test`와 `MockMvc` 패턴으로 검증한다.
- 최신 Spring/Spring Security major line으로 업그레이드하지 않는다. 이 저장소는 Gradle plugin과 dependency management가 관리하는 현재 버전을 기준으로 한다.

### Testing Requirements

- Run: `cd springboot && ./gradlew test --tests com.commitgotchi.character.CharacterActivationApiIntegrationTest`.
- Run: `cd springboot && ./gradlew test --tests com.commitgotchi.character.*`.
- Run: `cd springboot && ./gradlew test`.
- API tests should use `MockMvc` through the real Spring Security filter chain. Service direct calls alone are not enough for this story.
- DB assertions:
  - `SELECT count(*) FROM characters WHERE user_id=? AND is_active=true`
  - `SELECT id FROM characters WHERE user_id=? AND is_active=true`
  - non-target character active flag is false after switch
  - system-owned columns are unchanged on idempotent activation
- Response assertions:
  - `$.item.id`, `$.item.active`
  - exactly one `$.state.characters[?(@.active == true)]`
  - `$.state.dailyReport.characterId`
  - failure `$.code` for `NOT_FOUND` and `AUTH_ACCESS_TOKEN_MISSING`
- Existing auth/CORS/Swagger, BE-2.2 creation, BE-2.3 read/update/delete, report/quiz/daily bridge tests are regression guards. Do not weaken them.

### Previous Story Intelligence

- BE-2.1 established `LearningCharacter` as canonical aggregate and `characters` as normalized SoR.
- BE-2.1 added DB constraints for blank text, nonnegative stats, battle power sum, valid emotion/image status, and active uniqueness.
- BE-2.2 connected `POST /api/game/characters` and `GET /api/game/state` to normalized projection and introduced `CharacterCreationService`, `CharacterGameProjectionService`, `CharacterCommandService`, `CharacterLimitExceededException`, and `UserRepository.findByIdForUpdate`.
- BE-2.2 kept the compatibility facade shape `{ state, item }` and treated `game_states` as non-character compatibility storage.
- BE-2.3 hardened read/update/delete not-found behavior to `404 NOT_FOUND`, added `CharacterNotFoundException`, and proved that character CRUD must not return 200/null for ownership failures.
- BE-2.3 deletion logic already solved dailyReport/pending report repair after active deletion. BE-2.4 should apply the same care to explicit active switch without rewriting historical data.
- BE-2.3 review fixed a concurrent test timeout risk. Use bounded `Future.get(...)` for activation race tests.

### Git Intelligence Summary

- Recent commit `2d2db7a chore: save frontend progress` introduced the Vue-compatible `/api/game/**` prototype layer.
- Current HEAD is `14a2069c7dc410b6f985dbb541ca9df1813fb35a`.
- Working tree already contains uncommitted Spring Boot backend story work for BE-2.1/BE-2.2/BE-2.3. Do not revert or normalize unrelated files.
- Current `springboot/` status shows BE-2.4-relevant files already modified by previous stories: `GameController`, `GameService`, `CharacterCommandService`, `LearningCharacterRepository`, common error handling, and character tests.

### Latest Technical Information

- Spring Data JPA current official docs continue to support `@Lock` on repository query methods and redeclared CRUD methods, matching the existing pessimistic lock strategy.
- PostgreSQL current official docs continue to describe partial unique indexes as enforcing uniqueness among only the rows satisfying the predicate; this directly matches active-character uniqueness.
- Spring Security current official docs continue to provide Spring MVC Test / MockMvc integration; keep using the existing real-filter-chain MockMvc style.
- Project dependency versions are intentionally pinned by `springboot/build.gradle`; no latest-major upgrade belongs in BE-2.4.

### References

- [springboot/docs/springboot-backend-epics.md](../springboot-backend-epics.md) - BE-2.4 source story and springboot-only principles.
- [springboot/docs/stories/be-2-1-character-normalized-schema-and-domain-model.md](be-2-1-character-normalized-schema-and-domain-model.md) - schema/domain foundation and active uniqueness.
- [springboot/docs/stories/be-2-2-character-creation-and-first-activation.md](be-2-2-character-creation-and-first-activation.md) - creation/first activation and projection context.
- [springboot/docs/stories/be-2-3-character-read-update-delete.md](be-2-3-character-read-update-delete.md) - previous story implementation context and not-found/daily report learnings.
- [springboot/src/main/java/com/commitgotchi/game/api/GameController.java](../../src/main/java/com/commitgotchi/game/api/GameController.java) - current Vue-facing game routes.
- [springboot/src/main/java/com/commitgotchi/game/application/GameService.java](../../src/main/java/com/commitgotchi/game/application/GameService.java) - current `/api/game/**` compatibility facade.
- [springboot/src/main/java/com/commitgotchi/character/application/CharacterCommandService.java](../../src/main/java/com/commitgotchi/character/application/CharacterCommandService.java) - current activation transaction.
- [springboot/src/main/java/com/commitgotchi/character/application/CharacterGameProjectionService.java](../../src/main/java/com/commitgotchi/character/application/CharacterGameProjectionService.java) - current Vue projection.
- [springboot/src/main/java/com/commitgotchi/character/domain/LearningCharacter.java](../../src/main/java/com/commitgotchi/character/domain/LearningCharacter.java) - aggregate active/system-owned methods.
- [springboot/src/main/java/com/commitgotchi/character/domain/LearningCharacterRepository.java](../../src/main/java/com/commitgotchi/character/domain/LearningCharacterRepository.java) - ownership-aware repository methods.
- [springboot/src/main/resources/db/migration/V4__create_characters.sql](../../src/main/resources/db/migration/V4__create_characters.sql) - active uniqueness and character constraints.
- [springboot/src/test/java/com/commitgotchi/character/CharacterCreationApiIntegrationTest.java](../../src/test/java/com/commitgotchi/character/CharacterCreationApiIntegrationTest.java) - existing active switch smoke coverage and concurrency style.
- [springboot/src/test/java/com/commitgotchi/character/CharacterReadUpdateDeleteApiIntegrationTest.java](../../src/test/java/com/commitgotchi/character/CharacterReadUpdateDeleteApiIntegrationTest.java) - BE-2.3 not-found and dailyReport repair patterns.
- [_bmad-output/planning-artifacts/prds/prd-commitgotchi-2026-06-07/prd.md](../../../_bmad-output/planning-artifacts/prds/prd-commitgotchi-2026-06-07/prd.md) - FR-7 active character selection.
- [_bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md](../../../_bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md) - stack, source tree, active uniqueness.
- [Spring Data JPA Locking Reference](https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html)
- [PostgreSQL Partial Indexes](https://www.postgresql.org/docs/current/indexes-partial.html)
- [Spring Security MockMvc Testing Reference](https://docs.spring.io/spring-security/reference/servlet/test/mockmvc/index.html)

## Dev Agent Record

### Agent Model Used

GPT-5 Codex

### Implementation Plan

- Added activation-focused MockMvc integration coverage before implementation, including success, idempotency, not-found/auth boundaries, daily report compatibility, and concurrent activation.
- Hardened `GameService.setActiveCharacter(...)` to parse path ids through the same `CharacterNotFoundException` path used by character CRUD, then return the existing `{ state, item }` shape after successful domain activation.
- Reused `CharacterCommandService.activate(...)` as the application transaction center and strengthened active switching with user row lock, ownership-aware target lock, locked owned-character scan, deactivate/flush before target activation, and idempotent no-op behavior for already active targets.
- Replaced the daily report compatibility slot with the newly active character id after activation while preserving historical `reports`, `quizzes`, and `boardPosts` arrays.

### Debug Log References

- Loaded BMad config from `_bmad/bmm/config.yaml`: communication/document language Korean; planning artifacts under `_bmad-output/planning-artifacts`; implementation artifacts under `_bmad-output/implementation-artifacts`.
- Workflow activation resolved with no prepend/append steps and persistent fact glob `**/project-context.md`; no project-context file was found.
- No `sprint-status.yaml` exists under the repository.
- User explicitly selected BE 2.4 and constrained writes to `/springboot`; this story was written under `springboot/docs/stories/` and sprint status outside `springboot/` was not modified.
- Discovered inputs: Spring Boot backend epics, root PRD, root architecture, root epics, previous BE-2.1/BE-2.2/BE-2.3 stories, current Spring Boot code.
- 2026-06-18: Dev workflow activated for BE-2.4; no sprint-status file found, story Status set to `in-progress`.
- 2026-06-18: Red test run `./gradlew test --tests com.commitgotchi.character.CharacterActivationApiIntegrationTest` failed as expected on 404 contract, daily report replacement, and concurrent activation 500 leakage.
- 2026-06-18: Green test run `./gradlew test --tests com.commitgotchi.character.CharacterActivationApiIntegrationTest` passed after service and transaction fixes.
- 2026-06-18: Character epic regression `./gradlew test --tests 'com.commitgotchi.character.*'` passed. Initial unquoted zsh command failed before Gradle execution because `*` was treated as shell glob.
- 2026-06-18: Full Spring Boot regression `./gradlew test` passed.

### Completion Notes List

- Ultimate context engine analysis completed - comprehensive developer guide created.
- Status set to `ready-for-dev`.
- Story intentionally scopes implementation to active character selection contract hardening and tests.
- Existing activation code path was found and should be reused rather than recreated.
- Key implementation risks captured: 200/null hidden failures, stale `dailyReport.characterId`, partial unique index flush ordering, and unbounded concurrent tests.
- BE-2.4 implementation completed: malformed, missing, and cross-owner activation ids now use the common `404 NOT_FOUND` response via `CharacterNotFoundException`.
- Activation success now returns the normalized projection with exactly one active owned character and replaces `state.dailyReport.characterId` with the newly active character id.
- Activation transaction now keeps create/activate/delete user flows serialized with user row locking and uses locked character rows plus deactivate/flush/activate ordering without raw SQL bulk updates.
- Idempotent activation of an already active character preserves stable character fields and leaves DB active count at 1.
- Added `CharacterActivationApiIntegrationTest` covering success, idempotency, failure privacy, unauthenticated access, daily report bridge behavior, and concurrent activation.
- Validations passed: activation test, character package regression, and full Spring Boot test suite.

### File List

- `springboot/docs/stories/be-2-4-active-character-selection.md`
- `springboot/src/main/java/com/commitgotchi/game/application/GameService.java`
- `springboot/src/main/java/com/commitgotchi/character/application/CharacterCommandService.java`
- `springboot/src/test/java/com/commitgotchi/character/CharacterActivationApiIntegrationTest.java`

## Change Log

- 2026-06-18: BE-2.4 story written as `ready-for-dev` under `springboot/docs/stories/`, respecting the springboot-only write constraint.
- 2026-06-18: Implemented BE-2.4 active character selection hardening and moved story to `review`.
