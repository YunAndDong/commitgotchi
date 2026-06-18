---
story_id: BE-2.2
story_key: be-2-2-character-creation-and-first-activation
epic: BE-2
status: done
created: 2026-06-17
scope: springboot-only
previous_story: be-2-1-character-normalized-schema-and-domain-model
baseline_commit: 2d2db7ad402af43c4f62cc0df54da8dec3a94be6
---

# Story BE-2.2: 캐릭터 생성과 첫 활성화

Status: done

## Story

As a 사용자,
I want 이름, 키워드, 성격으로 캐릭터를 만들고,
so that 학습을 시작할 분신을 가질 수 있다.

## 목적과 범위

- `springboot/` 내부에만 변경을 만든다.
- BE-2.1에서 추가된 `characters` 정규화 테이블과 `LearningCharacter` aggregate를 System of Record로 사용한다.
- `POST /api/game/characters`를 정규화 캐릭터 생성 흐름에 연결하고, `GET /api/game/state`가 정규화 캐릭터 목록을 Vue 호환 DTO로 projection하게 한다.
- 첫 캐릭터는 자동 활성화한다.
- 두 번째 이후 생성 정책은 "새로 생성한 캐릭터가 활성화되고 기존 활성 캐릭터는 비활성화된다"로 고정한다. 이는 기존 `GameService` 프로토타입 동작과 Vue 기대 흐름을 유지하기 위한 BE-2.2 계약이다.
- 캐릭터 보유 한도는 사용자당 최대 3개다. 동시 생성 요청에서도 최종 보유 수가 3개를 넘지 않아야 한다.
- 이미지 생성 호출, fallback sprite 배정, retry-image 동작 구현은 BE-2.5 범위다. BE-2.2 생성 직후 `imageStatus`는 `PENDING`으로 둔다.
- 캐릭터 수정/삭제와 명시적 활성 변경은 BE-2.3/BE-2.4 범위다. 단, BE-2.2가 생성한 DB 캐릭터가 `/api/game/state`에서 보이고 기존 인증/오류/프런트 계약을 깨지 않아야 한다.
- 비범위: Vue/FastAPI 수정, `_bmad-output` 수정, 신규 이미지/퀴즈/리포트 도메인 테이블, `game_states` 제거.

## Acceptance Criteria

1. **정규화 캐릭터 생성 API**
   - **Given** 인증된 사용자가 보유 캐릭터가 0~2개일 때
   - **When** `POST /api/game/characters`에 유효한 `name`, `keyword`, `personality`를 보낸다
   - **Then** `characters` 테이블에 해당 사용자의 `LearningCharacter`가 생성된다.
   - **And** 요청은 `AuthPrincipal.userId()`의 사용자에게만 귀속되고 request body로 `userId`, `active`, 능력치, 전투력, 진화, 감정, image status를 지정할 수 없다.
   - **And** 성공 응답은 기존 `GameMutationResponse` 형태인 `{ "state": ..., "item": ... }`를 유지한다.

2. **첫 캐릭터 자동 활성화와 기본값**
   - **Given** 사용자가 캐릭터를 하나도 보유하지 않았을 때
   - **When** 캐릭터 생성이 성공하면
   - **Then** 생성된 캐릭터는 `active=true`다.
   - **And** 능력치 5종은 모두 0이고 `battlePower=0`, `isEvolved=false`, `emotion=JOY`, `imageStatus=PENDING`으로 시작한다.
   - **And** Vue projection에서는 `stats` 키가 `{ "algo":0, "cs":0, "db":0, "net":0, "fw":0 }`로 내려간다.

3. **두 번째 이후 생성 정책과 활성 단일성**
   - **Given** 사용자가 이미 활성 캐릭터를 보유하고 있을 때
   - **When** 새 캐릭터 생성이 성공하면
   - **Then** 새 캐릭터가 `active=true`가 되고 기존 활성 캐릭터는 `active=false`가 된다.
   - **And** 하나의 트랜잭션 안에서 기존 활성 해제와 새 활성 지정이 처리된다.
   - **And** PostgreSQL 부분 유니크 인덱스 `uq_one_active_character_per_user` 위반이 발생하지 않도록 flush 순서를 명시적으로 제어한다.
   - **And** 최종 DB 상태와 `/api/game/state` projection 모두 사용자당 활성 캐릭터가 최대 1개임을 보장한다.

4. **최대 3개 제한과 동시성**
   - **Given** 사용자가 이미 캐릭터 3개를 보유했을 때
   - **When** 추가 생성을 요청하면
   - **Then** HTTP `400`과 공통 오류 응답 형식으로 거부된다.
   - **And** 오류 코드는 명시적 도메인 코드 `CHARACTER_LIMIT_EXCEEDED`를 추가하거나, 기존 오류 계약을 유지해야 한다면 `VALIDATION_FAILED`를 사용하되 테스트가 이를 고정한다.
   - **And** 사용자가 캐릭터 2개를 보유한 상태에서 동시 생성 요청 2건이 들어와도 최종 보유 수는 3개이고 성공은 최대 1건이다.

5. **입력값 검증과 공통 오류 응답**
   - **Given** 요청의 `name`, `keyword`, `personality` 중 하나가 blank이거나 길이 제한을 초과할 때
   - **When** 생성 API를 호출하면
   - **Then** HTTP `400`과 공통 오류 응답 형식(`status`, `code`, `message`, `timestamp`, `traceId`)으로 응답한다.
   - **And** 길이 제한은 현재 스키마와 aggregate에 맞춰 `name <= 40`, `keyword <= 120`, `personality <= 500`으로 고정한다.
   - **And** unknown JSON field는 현재 Jackson 설정처럼 거부한다.
   - **And** 실패 응답과 로그에는 JWT, Authorization 헤더, 비밀번호, 내부 stack trace가 포함되지 않는다.

6. **인증과 소유권 경계**
   - **Given** 인증되지 않은 요청이 `GET /api/game/state` 또는 `POST /api/game/characters`를 호출할 때
   - **Then** 기존 Spring Security 공통 `401` 응답을 반환한다.
   - **Given** 두 사용자가 각각 캐릭터를 보유할 때
   - **When** 각 사용자가 `/api/game/state`를 조회하면
   - **Then** 자신의 캐릭터만 응답에 포함되고 다른 사용자의 캐릭터는 노출되지 않는다.
   - **And** 생성 API는 다른 사용자 리소스 id를 입력받지 않으므로 소유권 없는 접근을 구조적으로 허용하지 않는다.

7. **Vue 호환 `/api/game/state` projection**
   - **Given** 사용자가 정규화 캐릭터를 보유할 때
   - **When** `GET /api/game/state`를 호출하면
   - **Then** 응답은 기존 `GameStateResponse` 형태인 `{ "state": ... }`를 유지한다.
   - **And** `state.characters`는 `characters` 테이블에서 온 projection으로 채워진다.
   - **And** 각 character DTO는 최소 `id`, `name`, `keyword`, `personality`, `stats`, `emotion`, `isEvolved`, `imageStatus`, `active`, `message`, `createdAt`을 포함한다.
   - **And** enum mapping은 `JOY|SAD|ANGRY -> joy|sad|angry`, `stat_algorithm|stat_network|stat_framework -> algo|net|fw`로 내려간다.
   - **And** `reports`, `quizzes`, `dailyReport`, `notice`, `boardPosts`, `nextId`는 BE-2.2에서 아직 정규화하지 않으므로 기존 `game_states.state_json` 호환 데이터를 유지할 수 있다.
   - **And** `game_states.state_json`의 `characters` 배열은 더 이상 캐릭터 System of Record로 사용하지 않는다.

8. **기존 회귀 보존**
   - **Given** 기존 인증, CORS, Swagger, health, game prototype 회귀 테스트가 있을 때
   - **When** `./gradlew test`가 실행되면
   - **Then** 기존 테스트가 계속 통과한다.
   - **And** `build.gradle`에 새 의존성을 추가하지 않는다.
   - **And** `V4__create_characters.sql`은 수정하지 않는 것이 기대값이다. 스키마 변경이 정말 필요하면 BE-2.1 완료 내용과 DB 제약 테스트를 함께 갱신해야 한다.

## Tasks / Subtasks

- [x] **Task 1: 캐릭터 생성 유스케이스를 정규화 도메인 위에 추가한다** (AC: 1, 2, 3, 4, 5, 6)
  - [x] `springboot/src/main/java/com/commitgotchi/character/application/CharacterCreationService.java` 또는 동등한 application service를 추가한다.
  - [x] `UserRepository`로 현재 `AuthPrincipal.userId()`의 `User`를 조회하고, 존재하지 않으면 인증/권한 계약에 맞는 공통 오류로 실패한다.
  - [x] `LearningCharacter.create(user, name, keyword, personality)`를 재사용한다. 새 aggregate나 별도 캐릭터 엔티티를 만들지 않는다.
  - [x] 생성 가능 여부는 `LearningCharacterRepository.countByUserId(userId)` 또는 잠금 조회 결과 크기로 판단한다.
  - [x] 최대 3개 초과는 명시적 예외로 처리하고 `GlobalExceptionHandler`가 공통 오류 응답으로 변환하게 한다.
  - [x] request body의 `active`, `stats`, `battlePower`, `emotion`, `imageStatus`, `isEvolved`, `userId`는 무시하지 말고 DTO unknown-field/validation 정책으로 거부한다.

- [x] **Task 2: 생성 트랜잭션과 활성 단일성을 안전하게 구현한다** (AC: 2, 3, 4)
  - [x] 같은 사용자의 동시 생성을 직렬화하기 위해 `UserRepository`에 `@Lock(PESSIMISTIC_WRITE)` 기반 `findByIdForUpdate`를 추가하거나, `LearningCharacterRepository`에 사용자별 캐릭터 잠금 조회 메서드를 추가한다.
  - [x] 생성 정책은 "새 캐릭터가 활성화된다"로 구현한다.
  - [x] 기존 활성 캐릭터가 있으면 먼저 `deactivate()`하고 repository `flush()`로 부분 유니크 인덱스 충돌을 피한 뒤 새 캐릭터를 `activate()`하여 저장한다.
  - [x] 기존 활성 캐릭터가 없으면 새 캐릭터만 `activate()`한다.
  - [x] 트랜잭션 종료 후 DB에는 `is_active=true`인 캐릭터가 사용자당 최대 1개만 남아야 한다.

- [x] **Task 3: Vue 호환 캐릭터 projection을 만든다** (AC: 2, 7)
  - [x] `springboot/src/main/java/com/commitgotchi/character/application/CharacterGameProjectionService.java` 또는 동등한 projection 컴포넌트를 추가한다.
  - [x] `LearningCharacter`를 기존 Vue `normalizeCharacter`가 읽는 shape으로 변환한다.
  - [x] `id`는 DB `Long`을 그대로 숫자로 내려도 된다. Vue는 `String(id)` 비교를 사용하므로 숫자 id를 허용한다.
  - [x] `keyword`는 `designKeyword`, `message`는 `statusMessage`, `createdAt`은 ISO-8601 문자열로 매핑한다.
  - [x] `stats`는 DB 표준 5종을 프런트 키로 매핑한다: `statAlgorithm -> algo`, `statCs -> cs`, `statDb -> db`, `statNetwork -> net`, `statFramework -> fw`.
  - [x] `emotion`은 lower-case로 매핑한다.
  - [x] `imageStatus`는 BE-2.2 생성 직후 `PENDING`을 반환한다. BE-2.5 전에는 `READY`나 `FALLBACK`을 꾸며내지 않는다.

- [x] **Task 4: 기존 `/api/game/**` facade를 정규화 projection과 연결한다** (AC: 1, 7, 8)
  - [x] `GameController` 경로는 유지한다. 필요하면 DTO 타입만 명확히 한다.
  - [x] `GameService.state(userId)`는 기존 `game_states` JSON을 로드하되 `state.characters`를 정규화 캐릭터 projection으로 덮어쓴 뒤 반환한다.
  - [x] `GameService.createCharacter(userId, request)`는 정규화 생성 service를 호출하고, 반환된 character projection을 `item`으로 둔다.
  - [x] 기존 `reports`, `quizzes`, `dailyReport`, `notice`, `boardPosts`, `nextId` 호환은 유지한다.
  - [x] 현재 데모용 starter quiz를 유지해야 한다면 `game_states.quizzes`에만 임시로 저장하고, normalized character id를 `characterId`로 사용한다. 이 데이터는 BE-4 전까지의 compatibility artifact이며 캐릭터 System of Record가 아니다.
  - [x] `game_states.state_json.characters`에 새 캐릭터를 복제해서 장기 SoR처럼 쓰지 않는다.

- [x] **Task 5: 오류 계약을 도메인 상황에 맞게 확장한다** (AC: 4, 5, 6)
  - [x] `CHARACTER_LIMIT_EXCEEDED`를 추가한다면 `ErrorCode`, domain exception, `GlobalExceptionHandler` test를 함께 추가한다.
  - [x] 입력 검증 실패는 `VALIDATION_FAILED`를 유지한다.
  - [x] DB 부분 유니크 충돌이 예외로 노출되는 경우 내부 stack trace 대신 공통 오류 응답으로 변환한다.
  - [x] 응답 메시지는 사용자에게 노출 가능한 수준으로만 둔다.

- [x] **Task 6: API 통합 테스트를 추가한다** (AC: 1~8)
  - [x] `springboot/src/test/java/com/commitgotchi/character/CharacterCreationApiIntegrationTest.java` 또는 동등한 테스트를 추가한다.
  - [x] `PostgresIntegrationTest`, `@SpringBootTest`, `@AutoConfigureMockMvc`, 실제 JWT 로그인 흐름 또는 기존 테스트 fixture를 재사용한다.
  - [x] 첫 생성 성공: `201` 또는 기존 facade 정책의 성공 status, `item.active=true`, 0 stats, `imageStatus=PENDING`, `state.characters[0]` projection을 검증한다.
  - [x] 두 번째 생성 성공: 두 번째 character active, 첫 번째 inactive, DB active count 1을 검증한다.
  - [x] 네 번째 생성 거부: HTTP 400과 공통 오류 응답 코드를 검증한다.
  - [x] 동시 생성: 2개 보유 상태에서 두 요청 중 최대 1개만 성공하고 최종 count 3, active count 1을 검증한다.
  - [x] 인증 없음: `GET /api/game/state`, `POST /api/game/characters`가 `401`을 반환한다.
  - [x] 소유권 분리: 두 사용자 각각의 state에 자기 character만 나타난다.
  - [x] blank/too-long/unknown fields가 `400 VALIDATION_FAILED`를 반환한다.

- [x] **Task 7: 전체 회귀를 검증한다** (AC: 8)
  - [x] `cd springboot && ./gradlew test`를 실행한다.
  - [x] 실패 시 기존 인증/CORS/Swagger 테스트를 약화하지 말고 원인을 고친다.
  - [x] 새 테스트가 Testcontainers PostgreSQL 16과 Flyway V1~V4 위에서 동작하는지 확인한다.

### Review Findings

- [x] [Review][Decision Resolved] Legacy JSON 캐릭터 마이그레이션/초기화 정책 필요 — 결정: BE-2.2에서는 prototype reset을 수용하고 `game_states.state_json.characters`에만 있던 기존 JSON 캐릭터는 마이그레이션하지 않는다. `GameService.state()`가 `state.characters`를 정규화 테이블 projection으로 덮어쓰는 동작은 이 결정에 따라 허용한다.
- [x] [Review][Patch] 기존 캐릭터 mutation endpoint가 `game_states.state_json.characters`만 수정해 DB projection 조회 시 변경이 되돌아간다 [springboot/src/main/java/com/commitgotchi/game/application/GameService.java:69]
- [x] [Review][Patch] report/quiz/daily 흐름이 projected character stats/message만 수정하고 정규화 `characters` row에는 반영하지 않는다 [springboot/src/main/java/com/commitgotchi/game/application/GameService.java:124]
- [x] [Review][Patch] normalized projection 이후 set-active/edit/delete/report/quiz 회귀를 고정하는 통합 테스트가 없다 [springboot/src/test/java/com/commitgotchi/character/CharacterCreationApiIntegrationTest.java:60]

## Dev Notes

### Developer Context

- 인증 에픽은 완료되어 `AuthPrincipal`, JWT Access Token, Refresh Token cookie rotation, 공통 오류 응답, CORS, Swagger 프로필 제어가 동작한다.
- BE-2.1은 완료 상태이며 `V4__create_characters.sql`, `LearningCharacter`, `CharacterEmotion`, `CharacterImageStatus`, `LearningCharacterRepository`와 PostgreSQL 통합 테스트를 추가했다.
- 현재 `LearningCharacter.create(...)`는 능력치 0, `battlePower=0`, `emotion=JOY`, `imageStatus=PENDING`, `isActive=false`로 시작한다. BE-2.2는 생성 유스케이스에서 `activate()`를 호출해야 한다.
- `READY`와 `FALLBACK` image status는 DB 제약상 nonblank `sprite_sheet_url`이 필요하다. BE-2.2에서 이미지 호출을 하지 않으므로 생성 직후 `PENDING`이 올바른 상태다.
- 기존 `GameController`는 `/api/game/**` 전체를 제공하고 `GameService`는 `game_states.state_json`에 캐릭터/리포트/퀴즈/게시판을 저장한다. BE-2.2는 이 facade를 유지하되 캐릭터 SoR만 정규화 테이블로 전환한다.
- Vue API client는 `POST /api/game/characters`와 `GET /api/game/state`를 호출한다. Store는 `response.state`로 전체 상태를 갱신하고 `response.item`을 mutation 결과로 받는다.
- Vue store의 stat key는 `algo`, `cs`, `db`, `net`, `fw`다. DB/PRD 표준은 `algorithm`, `cs`, `db`, `network`, `framework`이므로 projection에서 변환한다.
- 현재 `GameService.createCharacter`는 JSON prototype에서 새 캐릭터를 항상 활성화하고 기존 캐릭터를 비활성화한다. BE-2.2도 이 정책을 유지한다.
- `game_states`는 prototype compatibility store다. 새 핵심 캐릭터 규칙, 보유 한도, 활성 단일성의 진실은 `characters` 테이블과 service transaction이어야 한다.

### Current Files To Update

- `springboot/src/main/java/com/commitgotchi/game/application/GameService.java`
  - 현재: `game_states.state_json`을 로드/저장하고 `createCharacter`가 JSON 배열에 캐릭터를 추가한다. 새 캐릭터는 string id(`c101` 등), `READY|FAILED` 이미지 상태, starter quizzes를 함께 만든다.
  - 변경: 캐릭터 생성과 `state.characters` 응답을 정규화 character service/projection으로 위임한다. 나머지 prototype data(`reports`, `quizzes`, `dailyReport`, `boardPosts`)는 보존한다.
  - 보존: `GameMutationResponse(state, item)` shape, `/api/game/**` 경로, 기존 report/quiz/board 동작, `Asia/Seoul` date behavior.

- `springboot/src/main/java/com/commitgotchi/game/api/GameController.java`
  - 현재: `JsonNode` request body를 받아 `GameService`에 위임한다.
  - 변경: 경로는 그대로 두고, 가능하면 `@Valid` DTO로 `POST /characters` 입력을 명확히 한다. 다른 endpoint는 BE-2.3 이후까지 불필요하게 변경하지 않는다.
  - 보존: `@AuthenticationPrincipal AuthPrincipal principal` 사용과 `/api/game` base path.

- `springboot/src/main/java/com/commitgotchi/character/domain/LearningCharacterRepository.java`
  - 현재: 사용자별 목록, count, `findByIdAndUserId`, `findByIdAndUserIdForUpdate`가 있다.
  - 변경: 생성 동시성을 위해 사용자별 캐릭터 잠금 조회 또는 활성 캐릭터 잠금 조회 메서드를 추가할 수 있다.
  - 보존: ownership을 드러내는 `...UserId...` 메서드 네이밍과 deterministic ordering.

- `springboot/src/main/java/com/commitgotchi/user/domain/UserRepository.java`
  - 현재: `existsByEmail`, `findByEmail`만 있다.
  - 변경: 사용자 단위 생성 직렬화를 택한다면 `findByIdForUpdate`를 추가한다.
  - 보존: 인증 경로의 이메일 조회/중복 검사 동작.

- `springboot/src/main/java/com/commitgotchi/common/error/ErrorCode.java` 및 `GlobalExceptionHandler.java`
  - 현재: 인증/검증/공통 오류 코드가 있고 `IllegalArgumentException`은 `VALIDATION_FAILED`로 매핑된다.
  - 변경: 캐릭터 3개 제한을 명시 코드로 표현하려면 `CHARACTER_LIMIT_EXCEEDED`와 handler를 추가한다.
  - 보존: 기존 인증 오류 코드와 민감정보 비노출 계약.

- `springboot/src/main/resources/db/migration/V4__create_characters.sql`
  - 현재: BE-2.1 완료 스키마. 활성 캐릭터 부분 유니크 인덱스, image status, sprite 제약, 능력치/전투력 CHECK가 있다.
  - 변경: 없음이 기대값이다.
  - 보존: DB가 강제하는 active uniqueness와 `PENDING` 기본값.

### Expected New Files

```text
springboot/src/main/java/com/commitgotchi/character/api/dto/
└── CharacterCreateRequest.java

springboot/src/main/java/com/commitgotchi/character/application/
├── CharacterCreationService.java
├── CharacterGameProjectionService.java
├── CharacterLimitExceededException.java
└── CharacterGameDto.java             # record 또는 내부 projection DTO, 이름은 구현 관례에 맞춰 조정 가능

springboot/src/test/java/com/commitgotchi/character/
└── CharacterCreationApiIntegrationTest.java
```

위 파일명은 권장안이다. 기존 프로젝트 관례에 맞게 더 나은 이름을 선택해도 되지만, API → application → domain/repository 의존 방향은 유지한다.

### Suggested API Contract

`POST /api/game/characters`

Request:

```json
{
  "name": "커밋 몬스터",
  "keyword": "작고 둥근 초록 슬라임",
  "personality": "칭찬을 많이 하지만 틀린 부분은 명확하게 지적하는 성격"
}
```

Success response shape:

```json
{
  "state": {
    "nextId": 100,
    "characters": [
      {
        "id": 1,
        "name": "커밋 몬스터",
        "keyword": "작고 둥근 초록 슬라임",
        "personality": "칭찬을 많이 하지만 틀린 부분은 명확하게 지적하는 성격",
        "stats": { "algo": 0, "cs": 0, "db": 0, "net": 0, "fw": 0 },
        "emotion": "joy",
        "isEvolved": false,
        "imageStatus": "PENDING",
        "active": true,
        "message": "Ready to learn",
        "createdAt": "2026-06-17T02:30:00Z"
      }
    ],
    "reports": [],
    "quizzes": [],
    "dailyReport": {
      "status": "pending",
      "date": "2026-06-17",
      "characterId": 1,
      "summary": null,
      "deltas": null,
      "quizComment": null,
      "nextRecommendation": null
    },
    "notice": null,
    "boardPosts": []
  },
  "item": {
    "id": 1,
    "name": "커밋 몬스터",
    "keyword": "작고 둥근 초록 슬라임",
    "personality": "칭찬을 많이 하지만 틀린 부분은 명확하게 지적하는 성격",
    "stats": { "algo": 0, "cs": 0, "db": 0, "net": 0, "fw": 0 },
    "emotion": "joy",
    "isEvolved": false,
    "imageStatus": "PENDING",
    "active": true,
    "message": "Ready to learn",
    "createdAt": "2026-06-17T02:30:00Z"
  }
}
```

Notes:
- Existing `GameMutationResponse` has no explicit status code annotation. Keep current success status unless the team decides to add `201 Created` with tests.
- `message` currently comes from `LearningCharacter.statusMessage`. If BE-2.2 changes the default to a Korean message, update `LearningCharacterTest` and ensure DB nonblank constraints still pass.
- `dailyReport.characterId` should follow the active character when the user had no active dailyReport character. Preserve existing `syncDailyReportCharacter` intent.

### Implementation Guardrails

- Do not create a second character table or duplicate aggregate.
- Do not use `game_states.state_json.characters` to enforce max count or active uniqueness.
- Do not let clients set system-owned fields. Unknown fields should fail because `spring.jackson.deserialization.fail-on-unknown-properties=true`.
- Do not call FastAPI or S3 in BE-2.2. Image generation and fallback sprite assignment are BE-2.5.
- Do not set `READY`/`FALLBACK` without a sprite URL; V4 DB constraints will reject it.
- Be careful with JPA flush order around the partial unique index. Deactivate existing active rows and flush before inserting/activating the new active row.
- Keep `build.gradle` unchanged unless a test compile failure proves an already intended dependency is missing. The current stack already has Web, JPA, Validation, Security, Flyway, PostgreSQL, springdoc, Testcontainers.
- Use real PostgreSQL Testcontainers for DB/constraint/concurrency behavior. H2 cannot validate the partial unique index and jsonb constraints faithfully.

### Architecture Compliance

- Spring Boot owns PostgreSQL. FastAPI must not read or write this DB.
- Public API remains protected by JWT via existing `SecurityConfig`; Controller uses `@AuthenticationPrincipal AuthPrincipal`.
- API layer must not expose repositories directly.
- Application service owns transaction boundaries for create/activate.
- Domain aggregate owns editable fields and system fields. BE-2.2 should call domain methods rather than changing fields through reflection or raw SQL.
- `ddl-auto=validate` remains. Schema changes must be Flyway-first, but no new migration is expected for BE-2.2.
- Partial unique index remains the DB-level invariant for active character uniqueness.

### Library and Framework Requirements

- Project remains pinned to `springboot/build.gradle`: Spring Boot `3.3.5`, Java 17, Spring Data JPA, Spring Security, Flyway, PostgreSQL, Testcontainers.
- Do not upgrade to current Spring Boot/Spring Data/Spring Security major lines in this story. Current official docs show newer stable lines, but this repo is intentionally dependency-managed by the existing Gradle plugin.
- Spring Data JPA supports `@Lock` on repository query methods and redeclared CRUD methods; use it for user/character row locking where needed.
- PostgreSQL partial unique indexes are the correct primitive for "only one active character per user" because uniqueness can be enforced only for rows matching `is_active=true`.
- Spring Security MockMvc support is available through the existing `spring-security-test` dependency; prefer existing real-login JWT helpers/patterns in integration tests if already established.

### Testing Requirements

- Run `cd springboot && ./gradlew test`.
- API tests should use `MockMvc` over the real Spring Security filter chain. Avoid bypassing authentication by calling services directly for API contract tests.
- Repository/DB tests should extend `PostgresIntegrationTest`.
- Include DB assertions for `SELECT count(*) FROM characters WHERE user_id=?` and `is_active=true`.
- Include response assertions for frontend shape: `$.state.characters[0].stats.algo`, `$.item.imageStatus`, `$.item.active`, `$.state.dailyReport.characterId`.
- Include negative tests for blank/too-long fields and max-count.
- Include a two-user isolation test.
- Include a race test for two concurrent create requests from the same user when count is 2.
- Existing auth/CORS/Swagger tests are regression guards. Do not weaken them.

### Previous Story Intelligence

- BE-2.1 established `LearningCharacter` as the canonical character aggregate and `characters` as the normalized System of Record.
- BE-2.1 review fixed image status constraints: `READY` and `FALLBACK` require nonblank sprite URL; `FAILED` clears sprite fields. BE-2.2 should keep new characters `PENDING`.
- BE-2.1 added `countByUserId`, deterministic user character listing, and `findByIdAndUserIdForUpdate`. Reuse or extend these methods instead of adding ownership-unsafe queries.
- BE-2.1 kept `GameController`, `GameService`, `GameState`, `V3__create_game_states.sql`, and `build.gradle` behavior intact. BE-2.2 should evolve the facade incrementally.
- Recent git history added `/api/game/**` as frontend-compatible prototype state. Treat it as a compatibility facade, not final domain design.

### Git Intelligence Summary

- `2d2db7a chore: save frontend progress` added `GameController`, `GameService`, `GameMutationResponse`, `GameStateResponse`, `GameState`, `GameStateRepository`, and `V3__create_game_states.sql`. This is the compatibility layer BE-2.2 must preserve.
- `f4ec9b7 fix(springboot): 크롬 확장 로그인 실패(CORS 403) 해결 및 트러블슈팅 문서화` changed Spring Boot security/CORS behavior. BE-2.2 should not touch CORS unless a direct test failure requires it.
- The working tree already contains uncommitted BE-2.1 files under `springboot/`; do not revert them.

### Latest Technical Information

- Official Spring Boot documentation currently presents newer stable documentation, but this project is pinned to Spring Boot `3.3.5`; stay on the project version and dependency management.
- Official Spring Data JPA documentation confirms `@Lock` can be declared on repository query methods and redeclared CRUD methods. This supports the locking strategy without adding libraries.
- PostgreSQL current documentation continues to document partial unique indexes as a way to enforce uniqueness only among rows satisfying a predicate. This matches `uq_one_active_character_per_user`.
- Spring Security current documentation keeps MockMvc integration as the supported testing path; this aligns with the existing `spring-security-test` and `MockMvc` integration tests.

### References

- [springboot/docs/springboot-backend-epics.md](../springboot-backend-epics.md) - BE-2.2 source story, springboot-only principles, Definition of Done.
- [springboot/docs/stories/be-2-1-character-normalized-schema-and-domain-model.md](be-2-1-character-normalized-schema-and-domain-model.md) - previous story implementation context and review learnings.
- [springboot/src/main/java/com/commitgotchi/character/domain/LearningCharacter.java](../../src/main/java/com/commitgotchi/character/domain/LearningCharacter.java) - existing aggregate to reuse.
- [springboot/src/main/java/com/commitgotchi/character/domain/LearningCharacterRepository.java](../../src/main/java/com/commitgotchi/character/domain/LearningCharacterRepository.java) - existing ownership-aware repository methods.
- [springboot/src/main/resources/db/migration/V4__create_characters.sql](../../src/main/resources/db/migration/V4__create_characters.sql) - DB constraints for characters.
- [springboot/src/main/java/com/commitgotchi/game/application/GameService.java](../../src/main/java/com/commitgotchi/game/application/GameService.java) - current `/api/game/**` compatibility facade.
- [springboot/src/main/java/com/commitgotchi/game/api/GameController.java](../../src/main/java/com/commitgotchi/game/api/GameController.java) - current Vue-facing game endpoints.
- [springboot/src/main/java/com/commitgotchi/common/error/ErrorCode.java](../../src/main/java/com/commitgotchi/common/error/ErrorCode.java) - common error code extension point.
- [springboot/src/test/java/com/commitgotchi/support/PostgresIntegrationTest.java](../../src/test/java/com/commitgotchi/support/PostgresIntegrationTest.java) - PostgreSQL Testcontainers base.
- [_bmad-output/planning-artifacts/prds/prd-commitgotchi-2026-06-07/prd.md](../../../_bmad-output/planning-artifacts/prds/prd-commitgotchi-2026-06-07/prd.md) - FR-3~7, FR-16, FR-21~23.
- [_bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md](../../../_bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md) - stack, data model, active uniqueness, source tree, image conflict context.
- [vue/src/api/client.js](../../../vue/src/api/client.js) - current frontend API paths.
- [vue/src/stores/game.js](../../../vue/src/stores/game.js) - current frontend state normalization contract.
- [Spring Boot SQL Databases Reference](https://docs.spring.io/spring-boot/reference/data/sql.html)
- [Spring Data JPA Locking Reference](https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html)
- [PostgreSQL Partial Indexes](https://www.postgresql.org/docs/current/indexes-partial.html)
- [Spring Security MockMvc Testing Reference](https://docs.spring.io/spring-security/reference/servlet/test/mockmvc/index.html)

## Dev Agent Record

### Agent Model Used

GPT-5 Codex dev-story implementation agent

### Debug Log References

- Loaded BMad config from `_bmad/bmm/config.yaml`: communication/document language Korean; planning artifacts under `_bmad-output/planning-artifacts`; implementation artifacts under `_bmad-output/implementation-artifacts`.
- Workflow activation resolved with no prepend/append steps and persistent fact glob `**/project-context.md`; no project-context file was found.
- No `sprint-status.yaml` exists under `_bmad-output/implementation-artifacts`.
- User explicitly selected BE 2.2 and constrained writes to `/springboot`; this story was written under `springboot/docs/stories/` and sprint status outside `springboot/` was not modified.
- Discovered inputs: Spring Boot backend epics, root PRD and addendum, root architecture, root epics, Vue client/store, previous BE-2.1 story, current Spring Boot code.
- RED: Added `CharacterCreationApiIntegrationTest`; initial run failed against prototype JSON character behavior (`READY`, string ids, no normalized persistence, no `CHARACTER_LIMIT_EXCEEDED`).
- GREEN: Implemented DTO validation, normalized creation service, user-row pessimistic lock, Vue-compatible projection, facade integration, and common error mapping.
- Validation: `./gradlew test --tests com.commitgotchi.character.CharacterCreationApiIntegrationTest` passed.
- Regression: `./gradlew test` passed.
- Final DoD: `./gradlew clean test` passed.

### Implementation Plan

- RED: API integration tests first covered first create, second create activation switch, fourth create rejection, concurrent create, unauthenticated access, owner isolation, and invalid/unknown fields.
- GREEN: `CharacterCreationService` reuses `LearningCharacter.create(...)`, locks the owning `User`, enforces max 3, deactivates existing active characters, flushes, then activates and persists the new character.
- REFACTOR: `CharacterGameProjectionService` centralizes frontend DTO shape, while `GameService` keeps `game_states` for compatibility fields and overlays normalized `state.characters`.

### Completion Notes List

- Task 1 완료: `/api/game/characters` 요청 DTO와 `CharacterCreationService`를 추가해 정규화 `characters` 테이블에 `LearningCharacter`를 생성한다.
- Task 2 완료: `UserRepository.findByIdForUpdate` 기반 사용자 단위 직렬화, 기존 active 해제 후 flush, 새 캐릭터 active 저장으로 활성 단일성을 보장한다.
- Task 3 완료: `CharacterGameProjectionService`가 DB Long id, Vue stat key(`algo/cs/db/net/fw`), lower-case emotion, `imageStatus=PENDING`, `active`, `message`, `createdAt`을 projection한다.
- Task 4 완료: `GameController` 경로와 `GameMutationResponse(state,item)` shape는 유지하고, `GameService.state/createCharacter`만 정규화 projection과 연결했다.
- Task 5 완료: `CHARACTER_LIMIT_EXCEEDED`와 `CharacterLimitExceededException` handler를 추가하고 validation/unknown-field 실패는 `VALIDATION_FAILED`를 유지했다.
- Task 6 완료: `CharacterCreationApiIntegrationTest`로 AC 1~8의 API/DB/동시성/소유권/검증 흐름을 고정했다.
- Task 7 완료: `./gradlew test`와 `./gradlew clean test` 통과. `build.gradle`과 `V4__create_characters.sql`은 수정하지 않았다.

### File List

- `springboot/docs/stories/be-2-2-character-creation-and-first-activation.md`
- `springboot/src/main/java/com/commitgotchi/character/api/dto/CharacterCreateRequest.java`
- `springboot/src/main/java/com/commitgotchi/character/application/CharacterCreationService.java`
- `springboot/src/main/java/com/commitgotchi/character/application/CharacterGameProjectionService.java`
- `springboot/src/main/java/com/commitgotchi/character/application/CharacterLimitExceededException.java`
- `springboot/src/main/java/com/commitgotchi/common/error/ErrorCode.java`
- `springboot/src/main/java/com/commitgotchi/common/error/GlobalExceptionHandler.java`
- `springboot/src/main/java/com/commitgotchi/game/api/GameController.java`
- `springboot/src/main/java/com/commitgotchi/game/application/GameService.java`
- `springboot/src/main/java/com/commitgotchi/user/domain/UserRepository.java`
- `springboot/src/test/java/com/commitgotchi/character/CharacterCreationApiIntegrationTest.java`

### Change Log

- 2026-06-17: BE-2.2 story created as ready-for-dev under `springboot/docs/stories/`.
- 2026-06-17: BE-2.2 캐릭터 생성/첫 활성화, 최대 3개 제한, 정규화 projection, 공통 오류, API 통합 테스트를 구현하고 상태를 `review`로 변경.
