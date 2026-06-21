---
story_id: BE-2.3
story_key: be-2-3-character-read-update-delete
epic: BE-2
status: done
created: 2026-06-18
scope: springboot-only
previous_story: be-2-2-character-creation-and-first-activation
baseline_commit: 14a2069c7dc410b6f985dbb541ca9df1813fb35a
---

# Story BE-2.3: 캐릭터 조회, 수정, 삭제

Status: done

## Story

As a 사용자,
I want 내 캐릭터 목록과 상세를 보고 편집/삭제하고,
so that 나의 학습 분신을 안전하게 관리할 수 있다.

## 목적과 범위

- `springboot/` 내부에만 변경한다. Vue, FastAPI, `_bmad-output`은 참고만 한다.
- BE-2.1/BE-2.2에서 도입된 `characters` 정규화 테이블과 `LearningCharacter` aggregate를 계속 System of Record로 사용한다.
- 기존 Vue가 호출하는 `/api/game/state`, `PATCH /api/game/characters/{id}`, `DELETE /api/game/characters/{id}` 계약을 보존하면서, 조회/상세/수정/삭제를 정규화 캐릭터 도메인 규칙으로 고정한다.
- 상세 조회는 `GET /api/game/characters/{id}`로 제공한다. 성공 응답은 기존 mutation 흐름과 맞춰 `{ "state": ..., "item": ... }` 형태를 사용한다.
- 사용자 수정 가능 필드는 `name`, `keyword`, `personality`로 한정한다.
- 능력치, 전투력, 진화 상태, 감정, 상태 메시지, image status, sprite URL/meta, active 여부는 사용자 PATCH 요청으로 변경할 수 없다.
- 활성 캐릭터 삭제 정책은 "남은 캐릭터가 있으면 가장 최근 생성된 캐릭터를 자동 활성화하고, 남은 캐릭터가 없으면 활성 없음 상태"로 고정한다.
- 삭제와 활성 재지정은 하나의 트랜잭션에서 처리한다.
- 비범위: 명시적 활성 캐릭터 지정 정책 변경(BE-2.4), FastAPI 이미지 생성/재시도 구현(BE-2.5), Vue/FastAPI 수정, `_bmad-output` 수정, `game_states` 제거, 리포트/퀴즈/게시판 정규화.

## Acceptance Criteria

1. **내 캐릭터 목록 조회**
   - **Given** 인증된 사용자가 `GET /api/game/state`를 호출할 때
   - **When** 해당 사용자가 정규화 캐릭터를 보유하고 있으면
   - **Then** `state.characters`는 `characters` 테이블에서 온 projection만 포함한다.
   - **And** 다른 사용자의 캐릭터는 절대 포함되지 않는다.
   - **And** 각 character DTO는 최소 `id`, `name`, `keyword`, `personality`, `stats`, `battlePower`, `emotion`, `isEvolved`, `imageStatus`, `active`, `message`, `createdAt`을 포함한다.
   - **And** `stats`는 Vue 호환 키 `{ "algo", "cs", "db", "net", "fw" }`를 유지하고 `battlePower`는 다섯 능력치 합과 일치한다.

2. **내 캐릭터 상세 조회**
   - **Given** 인증된 사용자가 자기 캐릭터 id를 알고 있을 때
   - **When** `GET /api/game/characters/{id}`를 호출하면
   - **Then** 해당 캐릭터 projection이 `item`으로 반환된다.
   - **And** 응답의 `state.characters`는 최신 정규화 projection과 동일한 목록을 반환한다.
   - **And** 없는 id, 숫자로 파싱할 수 없는 id, 다른 사용자의 캐릭터 id는 존재 여부를 노출하지 않고 HTTP `404 NOT_FOUND` 공통 오류 응답으로 처리한다.

3. **수정 가능 필드 제한**
   - **Given** 사용자가 자기 캐릭터를 수정할 때
   - **When** `PATCH /api/game/characters/{id}`에 유효한 `name`, `keyword`, `personality`를 보낸다
   - **Then** 해당 세 필드만 변경된다.
   - **And** `stats`, `battlePower`, `isEvolved`, `emotion`, `message`, `imageStatus`, `spriteSheetUrl`, `spriteMeta`, `active`, `userId`, `id`, `createdAt`, `updatedAt`은 요청 body로 변경할 수 없다.
   - **And** unknown/system-owned field가 포함되면 무시하지 않고 HTTP `400 VALIDATION_FAILED`로 거부한다.
   - **And** blank 또는 길이 초과 입력은 HTTP `400 VALIDATION_FAILED` 공통 오류 응답을 반환하고 DB 값은 변경되지 않는다.

4. **수정 소유권과 Not Found 계약**
   - **Given** 두 사용자가 각각 캐릭터를 보유하고 있을 때
   - **When** 한 사용자가 다른 사용자의 캐릭터를 상세 조회, 수정, 삭제하려고 하면
   - **Then** HTTP `404 NOT_FOUND` 공통 오류 응답을 반환한다.
   - **And** 응답/로그에는 JWT, Authorization header, stack trace, 내부 SQL 제약 상세가 포함되지 않는다.
   - **And** 기존처럼 200 응답에 `item: null`을 반환해서 실패를 숨기지 않는다.

5. **비활성 캐릭터 삭제**
   - **Given** 사용자가 활성 캐릭터와 비활성 캐릭터를 함께 보유하고 있을 때
   - **When** 비활성 캐릭터를 삭제하면
   - **Then** 삭제된 row는 `characters`에서 제거된다.
   - **And** 기존 활성 캐릭터는 그대로 active 상태를 유지한다.
   - **And** `GET /api/game/state`는 삭제된 캐릭터를 더 이상 반환하지 않는다.

6. **활성 캐릭터 삭제와 자동 재지정**
   - **Given** 사용자가 활성 캐릭터와 다른 남은 캐릭터를 보유하고 있을 때
   - **When** 활성 캐릭터를 삭제하면
   - **Then** 삭제와 재지정은 하나의 트랜잭션으로 처리된다.
   - **And** 남은 캐릭터 중 `createdAt DESC, id DESC` 기준 가장 최근 캐릭터가 자동 활성화된다.
   - **And** 최종 DB 상태와 `state.characters`에서 활성 캐릭터는 최대 1개다.
   - **And** `dailyReport.characterId`가 null이거나 삭제된 캐릭터를 가리키던 경우 새 활성 캐릭터 id로 동기화된다.

7. **마지막 캐릭터 삭제와 빈 상태**
   - **Given** 사용자가 마지막 남은 활성 캐릭터 하나만 보유하고 있을 때
   - **When** 해당 캐릭터를 삭제하면
   - **Then** 캐릭터 row는 제거된다.
   - **And** `state.characters`는 빈 배열이고 `state.dailyReport.characterId`는 `null`이다.
   - **And** 리포트, 퀴즈, 게시판 prototype compatibility data는 이 스토리에서 임의 삭제하지 않는다.

8. **기존 회귀 보존**
   - **Given** BE-2.2의 생성, 첫 활성화, 최대 3개 제한, 정규화 projection, 리포트/퀴즈 성장 bridge가 존재할 때
   - **When** BE-2.3이 완료되면
   - **Then** `POST /api/game/characters`, `GET /api/game/state`, report/quiz/daily mutation 회귀가 계속 통과한다.
   - **And** `V4__create_characters.sql`, `build.gradle`, 인증/CORS/Swagger 계약은 직접 필요가 없는 한 변경하지 않는다.

## Tasks / Subtasks

- [x] **Task 1: 상세 조회와 not-found 오류 계약을 추가한다** (AC: 1, 2, 4)
  - [x] `GameController`에 `GET /api/game/characters/{id}`를 추가하거나, 기존 facade 구조에 맞는 상세 조회 경로를 제공한다.
  - [x] `GameService` 또는 character application service에 owned character 조회 유스케이스를 추가한다.
  - [x] 숫자로 파싱할 수 없는 id, 없는 id, 다른 사용자 소유 id는 모두 `404 NOT_FOUND`로 처리한다.
  - [x] 실패 시 기존 200 + `item: null` 패턴을 character CRUD 경로에서는 사용하지 않는다.
  - [x] `state.characters`를 상세 응답에도 포함해 Vue-style state replacement 흐름을 유지한다.

- [x] **Task 2: 캐릭터 projection을 조회/상세 기준으로 보강한다** (AC: 1, 2, 8)
  - [x] `CharacterGameProjectionService.project(...)`에 `battlePower`를 추가한다.
  - [x] 기존 `stats.algo/cs/db/net/fw`, lower-case `emotion`, `imageStatus`, `active`, `message`, `createdAt` shape는 유지한다.
  - [x] `battlePower`는 `LearningCharacter.getBattlePower()`를 사용하되, 테스트에서 `stats` 합과 일치하는지 검증한다.
  - [x] `spriteSheetUrl`/`spriteMeta`는 BE-2.5 전에는 필수로 만들지 않는다. 추가한다면 nullable field로만 노출하고 Vue 호환을 깨지 않는다.

- [x] **Task 3: 수정 요청 DTO와 검증을 명확히 한다** (AC: 3, 4)
  - [x] `springboot/src/main/java/com/commitgotchi/character/api/dto/CharacterUpdateRequest.java`를 추가한다.
  - [x] `name`, `keyword`, `personality`는 BE-2.2 생성 DTO와 동일하게 `@NotBlank`, `@Size(max=40/120/500)`로 검증한다.
  - [x] `GameController.updateCharacter(...)`는 `JsonNode` 대신 `@Valid @RequestBody CharacterUpdateRequest`를 받게 한다.
  - [x] `spring.jackson.deserialization.fail-on-unknown-properties=true` 기존 정책을 활용해 system-owned field를 `400 VALIDATION_FAILED`로 거부한다.
  - [x] partial update는 이 스토리 범위에서 지원하지 않는다. Vue가 이미 세 필드를 모두 전송하므로 API 계약은 세 필드 full editable update로 고정한다.

- [x] **Task 4: 수정 유스케이스를 정규화 aggregate 규칙으로 고정한다** (AC: 3, 4, 8)
  - [x] `CharacterCommandService.update(...)`는 `findByIdAndUserIdForUpdate`와 `LearningCharacter.rename/changeDesignKeyword/changePersonality`만 사용한다.
  - [x] 능력치, 전투력, 진화, 감정, 상태 메시지, image status, active 여부를 수정 경로에서 직접 변경하지 않는다.
  - [x] 수정 후 `GameService`는 `applyCharacterProjection(userId, state)`를 다시 적용하고 `game_states.state_json.characters`에는 캐릭터를 저장하지 않는다.
  - [x] 수정 성공 응답은 `{ state, item }` shape를 유지하고 `item`은 최신 projection이어야 한다.

- [x] **Task 5: 삭제와 활성 재지정 트랜잭션을 구현한다** (AC: 5, 6, 7)
  - [x] `CharacterCommandService.delete(...)`가 삭제 대상과 사용자 row를 같은 트랜잭션에서 잠근다.
  - [x] 삭제 대상이 비활성이면 삭제만 수행하고 기존 active row를 보존한다.
  - [x] 삭제 대상이 활성이고 남은 캐릭터가 있으면 `createdAt DESC, id DESC` 기준 첫 번째 남은 캐릭터를 active로 만든다.
  - [x] 삭제 대상이 마지막 캐릭터이면 활성 없음 상태를 허용한다.
  - [x] 재지정 시 PostgreSQL 부분 유니크 인덱스 충돌이 나지 않도록 삭제 flush와 새 active 지정 순서를 명확히 한다.
  - [x] `GameService.deleteCharacter(...)`는 새 활성 캐릭터가 생기면 `dailyReport.characterId`를 새 id로 동기화하고, 남은 캐릭터가 없으면 `clearActiveCharacter`를 유지한다.

- [x] **Task 6: 캐릭터 CRUD API 통합 테스트를 추가한다** (AC: 1~8)
  - [x] `springboot/src/test/java/com/commitgotchi/character/CharacterReadUpdateDeleteApiIntegrationTest.java`를 추가하거나 기존 character API 통합 테스트를 명확히 분리/확장한다.
  - [x] `PostgresIntegrationTest`, `@SpringBootTest`, `@AutoConfigureMockMvc`, 실제 JWT 로그인 fixture를 재사용한다.
  - [x] 목록 조회: 두 사용자 캐릭터가 섞이지 않고 `battlePower`와 `stats` 합이 일치함을 검증한다.
  - [x] 상세 조회: 자기 캐릭터는 `item`으로 반환되고, 없는 id/다른 사용자 id/non-numeric id는 `404 NOT_FOUND`임을 검증한다.
  - [x] 수정 성공: 세 editable field만 변경되고 능력치/전투력/진화/감정/image status/active는 유지됨을 DB와 응답 모두에서 검증한다.
  - [x] 수정 실패: blank, too-long, unknown/system-owned field가 `400 VALIDATION_FAILED`이고 DB 값이 유지됨을 검증한다.
  - [x] 비활성 삭제: 기존 활성 캐릭터가 유지되고 삭제 row가 제거됨을 검증한다.
  - [x] 활성 삭제: 남은 가장 최근 캐릭터가 자동 활성화되고 active count가 1임을 검증한다.
  - [x] 마지막 삭제: `state.characters=[]`, `dailyReport.characterId=null`, active count 0을 검증한다.
  - [x] 인증 없음: 상세/수정/삭제가 기존 `401 AUTH_ACCESS_TOKEN_MISSING` 계약을 반환함을 검증한다.

- [x] **Task 7: 전체 회귀를 검증한다** (AC: 8)
  - [x] `cd springboot && ./gradlew test`를 실행한다.
  - [x] 실패 시 인증/CORS/Swagger 테스트를 약화하지 않고 원인을 고친다.
  - [x] `build.gradle`과 `V4__create_characters.sql`은 이 스토리에서 변경하지 않는 것이 기대값이다.

### Review Findings

- [x] [Review][Patch] Pending reports can restore a deleted character id into `dailyReport` [springboot/src/main/java/com/commitgotchi/game/application/GameService.java:230]
- [x] [Review][Patch] Board/review mutation responses can omit the normalized character projection [springboot/src/main/java/com/commitgotchi/game/application/GameService.java:303]
- [x] [Review][Patch] Concurrent creation regression test can wait indefinitely if a request stalls [springboot/src/test/java/com/commitgotchi/character/CharacterCreationApiIntegrationTest.java:279]

## Dev Notes

### Source Requirements

- PRD FR-4는 보유 캐릭터 목록과 개별 캐릭터의 능력치, 전투력, 감정, 진화 상태, 이미지를 조회할 수 있어야 한다고 정의한다.
- PRD FR-5는 사용자가 편집 가능한 속성만 수정할 수 있고 능력치, 전투력, 진화 상태는 직접 수정할 수 없다고 정의한다.
- PRD FR-6은 캐릭터 삭제 후에도 활성 캐릭터 단일성 규칙이 유지되어야 한다고 정의한다.
- PRD FR-21은 전투력이 항상 다섯 능력치 합과 일치해야 한다고 정의한다.
- Spring Boot backend epics의 BE-2.3은 목록/상세/수정/삭제 API 통합 테스트를 완료 기준으로 둔다.

### Developer Context

- 인증 에픽은 완료되어 `AuthPrincipal`, JWT Access Token, Refresh Token rotation, 공통 오류 응답, CORS, Swagger 프로필 제어가 동작한다.
- BE-2.1은 `characters` 정규화 테이블, `LearningCharacter`, `CharacterEmotion`, `CharacterImageStatus`, `LearningCharacterRepository`, PostgreSQL partial unique index `uq_one_active_character_per_user`를 도입했다.
- BE-2.2는 `POST /api/game/characters`와 `GET /api/game/state`를 정규화 캐릭터 projection에 연결했다. 캐릭터 SoR은 더 이상 `game_states.state_json.characters`가 아니다.
- 현재 Vue는 `GET /api/game/state`, `POST /api/game/characters`, `PATCH /api/game/characters/{id}`, `PATCH /api/game/characters/{id}/active`, `POST /api/game/characters/{id}/retry-image`, `DELETE /api/game/characters/{id}`를 호출한다. `vue/`는 이번 범위에서 수정하지 않는다.
- 현재 `GameService.stringifyForPersistence(...)`는 `characters`를 빈 배열로 저장한다. 이 정책을 유지해야 한다. `game_states.state_json.characters`에 정규화 캐릭터를 다시 복제하지 않는다.
- `LearningCharacter`는 편집 가능한 필드 메서드와 시스템 전용 메서드를 이미 분리한다. 수정 API는 `rename`, `changeDesignKeyword`, `changePersonality`만 호출해야 한다.
- `READY`와 `FALLBACK` image status는 DB 제약상 nonblank `sprite_sheet_url`이 필요하다. BE-2.3은 image status를 바꾸지 않는다.

### Current Workspace Observation

- 이 워킹트리에는 BE-2.1, BE-2.2, BE-2.3으로 보이는 Spring Boot 변경이 이미 uncommitted 상태로 존재한다.
- dev-story는 기존 변경을 사용자 작업으로 보고 되돌리지 않는다.
- 이미 존재하는 파일이 이 스토리의 기대 파일과 겹치면 새로 만들기보다 요구사항과 테스트에 맞는지 검증하고 부족한 부분만 수정한다.
- 이 스토리 문서의 체크박스는 구현 완료 표시가 아니다. dev-story가 실제 코드와 테스트로 완료 여부를 판단한다.

### Current Files To Update

- `springboot/src/main/java/com/commitgotchi/game/api/GameController.java`
  - 현재 역할: `/api/game/state`, 생성, 수정, 활성화, retry-image, 삭제, 리포트/퀴즈/게시판 facade를 제공한다.
  - 변경 목표: 상세 조회 경로를 제공하고, 수정 요청은 `CharacterUpdateRequest` DTO + `@Valid`로 받는다.
  - 보존: `/api/game` base path, `@AuthenticationPrincipal AuthPrincipal`, 기존 생성/활성화/retry/report/quiz/board 경로.

- `springboot/src/main/java/com/commitgotchi/game/application/GameService.java`
  - 현재 역할: state load 후 정규화 projection을 overlay한다. compatibility data는 `game_states`에 유지한다.
  - 변경 목표: character CRUD 경로에서는 not-found를 공통 `404 NOT_FOUND`로 처리한다. 삭제 성공 후 새 active가 있으면 dailyReport를 새 active id로 동기화한다.
  - 보존: `GameMutationResponse(state,item)` shape, `game_states`의 reports/quizzes/dailyReport/boardPosts compatibility data, `stringifyForPersistence`의 characters 비저장 정책.

- `springboot/src/main/java/com/commitgotchi/character/application/CharacterCommandService.java`
  - 현재 역할: update/activate/delete/retryImage/react/applyScoreDelta 흐름을 제공한다.
  - 변경 목표: 삭제 결과가 removed character와 optional new active character를 함께 표현하게 한다. 트랜잭션은 하나여야 한다.
  - 보존: `findByIdAndUserIdForUpdate` ownership check, `UserRepository.findByIdForUpdate` 기반 사용자 단위 직렬화, `LearningCharacter` 도메인 메서드 사용.

- `springboot/src/main/java/com/commitgotchi/character/application/CharacterGameProjectionService.java`
  - 현재 역할: Vue shape으로 id/name/keyword/personality/stats/emotion/isEvolved/imageStatus/active/message/createdAt을 만든다.
  - 변경 목표: additive `battlePower`를 추가한다.
  - 보존: stat key mapping과 lower-case emotion mapping.

- `springboot/src/main/java/com/commitgotchi/character/domain/LearningCharacterRepository.java`
  - 현재 역할: 사용자별 정렬 목록, count, owned lookup, active lookup, pessimistic lock lookup을 제공한다.
  - 변경 목표: 삭제 후 자동 재지정에 필요한 "남은 캐릭터를 생성일 역순으로 잠금 조회" 메서드를 추가할 수 있다.
  - 보존: ownership을 드러내는 `...UserId...` 네이밍과 `createdAt DESC, id DESC` deterministic ordering.

- `springboot/src/main/java/com/commitgotchi/common/error/ErrorCode.java` 및 `GlobalExceptionHandler.java`
  - 현재 역할: 인증/인가/검증/공통 오류 코드를 매핑한다.
  - 변경 목표: character not-found 전용 예외 또는 공통 domain not-found 예외를 `NOT_FOUND`로 매핑한다.
  - 보존: 기존 인증/인가/검증/character limit 오류 코드와 민감정보 비노출 계약.

### Expected New Files

```text
springboot/src/main/java/com/commitgotchi/character/api/dto/
└── CharacterUpdateRequest.java

springboot/src/main/java/com/commitgotchi/character/application/
├── CharacterNotFoundException.java
└── CharacterDeletionResult.java

springboot/src/test/java/com/commitgotchi/character/
└── CharacterReadUpdateDeleteApiIntegrationTest.java
```

위 파일명은 권장안이다. 기존 프로젝트 관례에 맞게 더 나은 이름을 선택해도 되지만, API -> application -> domain/repository 의존 방향은 유지한다.

### Suggested API Contract

`GET /api/game/characters/{id}`

Success:

```json
{
  "state": {
    "characters": [
      {
        "id": 2,
        "name": "커밋 몬스터",
        "keyword": "초록 학습 슬라임",
        "personality": "다정하지만 정확한 성격",
        "stats": { "algo": 12, "cs": 0, "db": 0, "net": 1, "fw": 0 },
        "battlePower": 13,
        "emotion": "joy",
        "isEvolved": false,
        "imageStatus": "PENDING",
        "active": true,
        "message": "Ready to learn",
        "createdAt": "2026-06-18T02:30:00Z"
      }
    ]
  },
  "item": {
    "id": 2,
    "name": "커밋 몬스터",
    "keyword": "초록 학습 슬라임",
    "personality": "다정하지만 정확한 성격",
    "stats": { "algo": 12, "cs": 0, "db": 0, "net": 1, "fw": 0 },
    "battlePower": 13,
    "emotion": "joy",
    "isEvolved": false,
    "imageStatus": "PENDING",
    "active": true,
    "message": "Ready to learn",
    "createdAt": "2026-06-18T02:30:00Z"
  }
}
```

`PATCH /api/game/characters/{id}`

Request:

```json
{
  "name": "새 이름",
  "keyword": "새 디자인 키워드",
  "personality": "새 성격"
}
```

Failure examples:

- 다른 사용자 id, 없는 id, non-numeric id: `404 NOT_FOUND`
- blank/too-long/system-owned field 포함: `400 VALIDATION_FAILED`
- 인증 없음: 기존 security 계약의 `401 AUTH_ACCESS_TOKEN_MISSING`

### Active Delete Policy

- 삭제 대상이 inactive: 삭제만 수행한다. 기존 active는 유지한다.
- 삭제 대상이 active이고 남은 캐릭터가 있음: `createdAt DESC, id DESC` 기준 첫 번째 남은 캐릭터를 active로 만든다.
- 삭제 대상이 active이고 남은 캐릭터가 없음: active 없음 상태를 허용하고 `dailyReport.characterId=null`로 둔다.
- 삭제와 재지정은 같은 transaction에서 끝난다. 중간 상태가 외부 응답으로 노출되면 안 된다.
- BE-2.4의 명시적 활성 지정 endpoint는 이 정책과 충돌하지 않아야 한다.

### Implementation Guardrails

- 새 character 테이블, 새 aggregate, `game_states.state_json.characters` 기반 CRUD를 만들지 않는다.
- `JsonNode`로 PATCH를 계속 받지 않는다. system-owned field를 무시하면 사용자 직접 수정 금지 요구가 테스트로 보호되지 않는다.
- character CRUD 실패를 200/null로 반환하지 않는다. not-found와 ownership 실패는 `404 NOT_FOUND`로 통일한다.
- update path에서 `LearningCharacter.applyScoreDelta`, `react`, `activate`, image status methods를 호출하지 않는다.
- delete path에서 raw SQL로 active flag를 바꾸지 않는다. 필요한 Repository method를 추가하고 aggregate method를 사용한다.
- active 재지정은 부분 유니크 인덱스를 존중해야 한다. 삭제 flush 후 새 active 지정 또는 동등하게 안전한 flush 순서를 명시한다.
- `build.gradle`에 새 의존성을 추가하지 않는다. 현재 Web/JPA/Validation/Security/Flyway/PostgreSQL/Testcontainers로 충분하다.
- `V4__create_characters.sql`은 변경하지 않는 것이 기대값이다. 스키마 변경이 정말 필요하면 BE-2.1의 DB 제약 테스트까지 함께 갱신해야 한다.
- Vue `normalizeCharacter`는 현재 `stats` 기반으로 전투력을 계산한다. `battlePower` 추가는 additive여야 하며 기존 `stats` shape를 바꾸면 안 된다.

### Architecture Compliance

- Spring Boot가 PostgreSQL System of Record를 소유한다. FastAPI는 이 DB를 읽거나 쓰지 않는다.
- API layer는 repository를 직접 노출하지 않는다.
- Application service가 transaction boundary를 가진다.
- Domain aggregate가 편집 가능 필드와 시스템 전용 필드를 분리한다.
- 활성 캐릭터 최대 1개 불변식은 PostgreSQL partial unique index와 service transaction 양쪽에서 보호한다.
- `ddl-auto=validate` 정책을 유지한다.
- 기존 `/api/game/**` facade는 Vue 호환 계층이다. 신규 핵심 캐릭터 규칙은 `characters`와 character application service에 둔다.

### Library and Framework Requirements

- 프로젝트 버전을 유지한다: Spring Boot `3.3.5`, Java 17, Spring Data JPA, Spring Security, Flyway, PostgreSQL, Testcontainers.
- 공식 Spring Data JPA current 문서는 repository query method와 redeclared CRUD method에 `@Lock`을 사용할 수 있음을 설명한다. 이 스토리의 row lock 전략은 기존 `@Lock(PESSIMISTIC_WRITE)` 패턴으로 충분하다.
- 공식 PostgreSQL current 문서는 predicate를 만족하는 subset에 partial index를 적용할 수 있고, 그 subset에 unique 제약을 둘 수 있음을 설명한다. `uq_one_active_character_per_user`는 이 불변식에 맞는 DB primitive다.
- 공식 Spring Security current 문서는 MockMvc 통합 테스트 지원을 유지한다. API 계약 테스트는 기존 `spring-security-test`와 `MockMvc` 패턴을 따른다.
- 최신 Spring/Spring Security major line으로 업그레이드하지 않는다. 이 저장소는 Gradle plugin과 dependency management가 관리하는 현재 버전을 기준으로 한다.

### Testing Requirements

- Run: `cd springboot && ./gradlew test`.
- API tests should use `MockMvc` through the real Spring Security filter chain. Service direct calls alone are not enough for this story.
- DB/Repository behavior should use PostgreSQL Testcontainers via `PostgresIntegrationTest`.
- Include DB assertions:
  - `SELECT count(*) FROM characters WHERE user_id=?`
  - `SELECT count(*) FROM characters WHERE user_id=? AND is_active=true`
  - deleted id no longer exists
  - updated row keeps system-owned columns unchanged
- Include response assertions:
  - `$.state.characters[*]` only contains owned characters
  - `$.item.battlePower` equals stats sum
  - `$.item.active` and `$.state.dailyReport.characterId` after active deletion
  - `$.code` for `NOT_FOUND`, `VALIDATION_FAILED`, `AUTH_ACCESS_TOKEN_MISSING`
- Existing auth/CORS/Swagger and BE-2.2 creation/growth bridge tests are regression guards. Do not weaken them.

### Previous Story Intelligence

- BE-2.1 established `LearningCharacter` as canonical aggregate and `characters` as normalized SoR.
- BE-2.1 added DB constraints for blank text, nonnegative stats, battle power sum, valid emotion/image status, and active uniqueness.
- BE-2.1 review fixed image status constraints: `READY` and `FALLBACK` require nonblank sprite URL, `FAILED` clears sprite fields.
- BE-2.2 connected `POST /api/game/characters` and `GET /api/game/state` to normalized projection.
- BE-2.2 added `CharacterCreationService`, `CharacterGameProjectionService`, `CharacterCommandService`, `CharacterLimitExceededException`, and `UserRepository.findByIdForUpdate`.
- BE-2.2 added integration coverage for creation, owner isolation, max count, concurrent create, and report/quiz/daily mutations persisting normalized character state.
- BE-2.2 left update/delete behavior partially implemented as compatibility bridge. BE-2.3 should harden it rather than reimplement the whole facade.

### Git Intelligence Summary

- Recent commit `2d2db7a chore: save frontend progress` introduced the Vue-compatible `/api/game/**` prototype layer.
- Current HEAD is `14a2069c7dc410b6f985dbb541ca9df1813fb35a`.
- Working tree already contains uncommitted Spring Boot backend story work for BE-2.1/BE-2.2/BE-2.3. Do not revert or normalize unrelated files.
- Current `springboot/` status shows BE-2.3-related files as uncommitted additions/modifications; this story must work with that state.

### Latest Technical Information

- Spring Data JPA current official docs still support `@Lock` on repository query methods and redeclared CRUD methods, matching the existing `findByIdAndUserIdForUpdate` pattern.
- PostgreSQL current docs still document partial unique indexes as enforcing uniqueness only for rows satisfying the predicate, matching active character uniqueness.
- Spring Security current docs still support MockMvc integration for servlet security tests, matching current integration test strategy.
- Project dependency versions are intentionally pinned by `springboot/build.gradle`; no latest-major upgrade belongs in BE-2.3.

### References

- [springboot/docs/springboot-backend-epics.md](../springboot-backend-epics.md) - BE-2.3 source story and springboot-only principles.
- [springboot/docs/stories/be-2-1-character-normalized-schema-and-domain-model.md](be-2-1-character-normalized-schema-and-domain-model.md) - schema/domain foundation and review learnings.
- [springboot/docs/stories/be-2-2-character-creation-and-first-activation.md](be-2-2-character-creation-and-first-activation.md) - previous story implementation context.
- [springboot/src/main/java/com/commitgotchi/character/domain/LearningCharacter.java](../../src/main/java/com/commitgotchi/character/domain/LearningCharacter.java) - aggregate methods and system-owned field boundaries.
- [springboot/src/main/java/com/commitgotchi/character/domain/LearningCharacterRepository.java](../../src/main/java/com/commitgotchi/character/domain/LearningCharacterRepository.java) - ownership-aware repository methods.
- [springboot/src/main/java/com/commitgotchi/character/application/CharacterCommandService.java](../../src/main/java/com/commitgotchi/character/application/CharacterCommandService.java) - current update/delete bridge.
- [springboot/src/main/java/com/commitgotchi/character/application/CharacterGameProjectionService.java](../../src/main/java/com/commitgotchi/character/application/CharacterGameProjectionService.java) - current Vue projection.
- [springboot/src/main/java/com/commitgotchi/game/application/GameService.java](../../src/main/java/com/commitgotchi/game/application/GameService.java) - current `/api/game/**` compatibility facade.
- [springboot/src/main/java/com/commitgotchi/game/api/GameController.java](../../src/main/java/com/commitgotchi/game/api/GameController.java) - Vue-facing game routes.
- [springboot/src/main/resources/db/migration/V4__create_characters.sql](../../src/main/resources/db/migration/V4__create_characters.sql) - active uniqueness and character constraints.
- [springboot/src/test/java/com/commitgotchi/character/CharacterCreationApiIntegrationTest.java](../../src/test/java/com/commitgotchi/character/CharacterCreationApiIntegrationTest.java) - existing BE-2.2 API regression style.
- [springboot/src/test/java/com/commitgotchi/support/PostgresIntegrationTest.java](../../src/test/java/com/commitgotchi/support/PostgresIntegrationTest.java) - PostgreSQL Testcontainers base.
- [_bmad-output/planning-artifacts/prds/prd-commitgotchi-2026-06-07/prd.md](../../../_bmad-output/planning-artifacts/prds/prd-commitgotchi-2026-06-07/prd.md) - FR-4, FR-5, FR-6, FR-7, FR-21.
- [_bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md](../../../_bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md) - stack, data model, active uniqueness, source tree.
- [vue/src/api/client.js](../../../vue/src/api/client.js) - current frontend API paths.
- [vue/src/stores/game.js](../../../vue/src/stores/game.js) - current frontend state normalization contract.
- [Spring Data JPA Locking Reference](https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html)
- [PostgreSQL Partial Indexes](https://www.postgresql.org/docs/current/indexes-partial.html)
- [Spring Security MockMvc Testing Reference](https://docs.spring.io/spring-security/reference/servlet/test/mockmvc/index.html)

## Dev Agent Record

### Agent Model Used

GPT-5 Codex

### Debug Log References

- `./gradlew test --tests com.commitgotchi.character.CharacterReadUpdateDeleteApiIntegrationTest --tests com.commitgotchi.character.CharacterCreationApiIntegrationTest` - 통과
- `./gradlew test` - 통과

### Implementation Plan

- `/api/game/characters/{id}` 상세 조회는 `GameController` -> `GameService` -> `CharacterCommandService.findOwned(...)` 흐름으로 두고, 파싱 실패/미존재/타 사용자 소유를 `CharacterNotFoundException`으로 통일한다.
- 수정은 `CharacterUpdateRequest`와 Bean Validation/Jackson unknown-property 실패 정책으로 editable full update만 허용하고, aggregate의 `rename/changeDesignKeyword/changePersonality`만 호출한다.
- 삭제는 사용자 row와 대상 character row를 잠근 뒤 삭제 flush 후 남은 최신 character를 active로 재지정하고, `dailyReport.characterId`를 결과 상태에 맞춰 동기화한다.
- 조회 projection은 `characters` 정규화 테이블에서만 만들고 `battlePower`를 additive field로 노출하며, `game_states.state_json.characters` 비저장 정책은 유지한다.

### Completion Notes List

- `GET /api/game/characters/{id}`가 `{ state, item }` shape로 owned character 상세와 최신 `state.characters` projection을 반환한다.
- character CRUD not-found 계약을 `404 NOT_FOUND` 공통 오류 응답으로 고정했고, malformed id와 cross-owner id도 존재 여부를 노출하지 않는다.
- `CharacterUpdateRequest` 기반 full update 검증을 추가해 `name`, `keyword`, `personality`만 변경 가능하게 했고 unknown/system-owned field는 `400 VALIDATION_FAILED`로 거부된다.
- projection에 `battlePower`를 추가하고 `stats.algo/cs/db/net/fw`, lower-case emotion, image status, active, message, createdAt shape를 유지했다.
- 삭제는 비활성 삭제, 활성 삭제 후 최신 남은 character 자동 활성화, 마지막 character 삭제 후 빈 상태와 `dailyReport.characterId=null`을 모두 처리한다.
- 삭제 후 pending report가 삭제된 character id를 다시 `dailyReport`에 복원하지 않도록 report delivery와 삭제 응답 상태를 보강했다.
- board/review mutation 응답도 최신 normalized character projection을 포함하도록 보강했다.
- BE-2.3 전용 통합 테스트와 전체 Spring Boot 회귀 테스트가 통과했다.

### File List

- `springboot/docs/stories/be-2-3-character-read-update-delete.md`
- `springboot/src/main/java/com/commitgotchi/character/api/dto/CharacterUpdateRequest.java`
- `springboot/src/main/java/com/commitgotchi/character/application/CharacterCommandService.java`
- `springboot/src/main/java/com/commitgotchi/character/application/CharacterDeletionResult.java`
- `springboot/src/main/java/com/commitgotchi/character/application/CharacterGameProjectionService.java`
- `springboot/src/main/java/com/commitgotchi/character/application/CharacterNotFoundException.java`
- `springboot/src/main/java/com/commitgotchi/character/domain/LearningCharacterRepository.java`
- `springboot/src/main/java/com/commitgotchi/common/error/ErrorCode.java`
- `springboot/src/main/java/com/commitgotchi/common/error/GlobalExceptionHandler.java`
- `springboot/src/main/java/com/commitgotchi/game/api/GameController.java`
- `springboot/src/main/java/com/commitgotchi/game/application/GameService.java`
- `springboot/src/main/java/com/commitgotchi/user/domain/UserRepository.java`
- `springboot/src/test/java/com/commitgotchi/character/CharacterCreationApiIntegrationTest.java`
- `springboot/src/test/java/com/commitgotchi/character/CharacterReadUpdateDeleteApiIntegrationTest.java`

## Change Log

- 2026-06-18: BE-2.3 story written as `ready-for-dev` under `springboot/docs/stories/`, respecting the springboot-only write constraint.
- 2026-06-18: Implemented and verified BE-2.3 character read/update/delete contracts; status moved to `review`.
- 2026-06-18: Code review patches applied and verified; status moved to `done`.
