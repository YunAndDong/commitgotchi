---
story_id: BE-2.6
story_key: be-2-6-character-epic-contract-regression-and-be3-handoff
epic: BE-2
status: done
created: 2026-06-18
scope: springboot-only
previous_story: be-2-5-image-generation-adapter-and-fallback
baseline_commit: 14a2069c7dc410b6f985dbb541ca9df1813fb35a
---

# Story BE-2.6: 캐릭터 에픽 계약 회귀와 BE-3 인계 안정화

Status: done

## Story

As a 백엔드 개발자,
I want BE-2 캐릭터 도메인의 공개 API, projection, 테스트 게이트, 인계 문서를 한 번 더 고정하고,
so that BE-3 학습 리포트와 성장 루프가 안정적인 활성 캐릭터 기반 위에서 구현될 수 있다.

## 목적과 범위

- `springboot/` 내부만 변경한다. Vue, FastAPI, `_bmad-output`은 참고만 한다.
- BE-2.6은 원래 Spring Boot backend epic 초안에 없었지만, 사용자가 BE 2.6 작성을 명시했고 BE-2.5까지 구현 완료 기록이 있으므로 캐릭터 에픽 마감/인계 스토리로 정의한다.
- BE-2.1~BE-2.5가 만든 `characters` 정규화 테이블, `LearningCharacter`, `CharacterCommandService`, `CharacterCreationService`, `CharacterImageService`, `CharacterGameProjectionService`, `/api/game/**` facade를 재사용한다.
- 핵심 목표는 새 기능이 아니라 계약 고정이다: OpenAPI/문서, 통합 회귀 게이트, `game_states` persistence boundary, BE-3 인계 노트.
- 비범위: 새 리포트/퀴즈/게시판 정규화 테이블, SQS Producer, Internal API 콜백, FastAPI 구현, Vue 변경, 실제 S3 업로드, `game_states` 제거, 랭킹/대시보드 신규 API.

## Acceptance Criteria

1. **Spring Boot backend epic과 상세 스토리 정합성**
   - **Given** Spring Boot 전용 epic 문서에는 BE-2.1~BE-2.5만 있었을 때
   - **When** BE-2.6이 작성되면
   - **Then** `springboot/docs/springboot-backend-epics.md`에 BE-2.6 원천 항목이 추가된다.
   - **And** 상세 스토리 파일은 `springboot/docs/stories/be-2-6-character-epic-contract-regression-and-be3-handoff.md`에 존재한다.
   - **And** 루트 `_bmad-output`과 sprint status는 변경하지 않는다.

2. **캐릭터 API OpenAPI 계약 고정**
   - **Given** `local`, `dev`, `test` profile에서 springdoc이 활성화될 때
   - **When** `/v3/api-docs`를 조회하면
   - **Then** `/api/game/state`, `/api/game/characters`, `/api/game/characters/{id}`, `/api/game/characters/{id}/active`, `/api/game/characters/{id}/retry-image` 경로가 문서에 포함된다.
   - **And** 보호 API는 `bearerAuth` 보안 스키마를 요구한다.
   - **And** `@AuthenticationPrincipal AuthPrincipal`은 OpenAPI 사용자 입력 파라미터로 노출되지 않는다.
   - **And** `CharacterCreateRequest`와 `CharacterUpdateRequest`의 `name`, `keyword`, `personality` 검증/예시가 OpenAPI schema 또는 명시 문서에 반영된다.
   - **And** `prod` profile의 Swagger/OpenAPI 비활성 계약은 기존 테스트처럼 유지된다.

3. **BE-2 통합 회귀 게이트**
   - **Given** 인증된 사용자가 캐릭터를 0~3개 보유할 수 있을 때
   - **When** BE-2 회귀 테스트가 실행되면
   - **Then** 생성, 목록/상세 조회, 수정, 삭제, 활성 지정, 이미지 retry, `GET /api/game/state` projection이 한 흐름에서 검증된다.
   - **And** 타 사용자 캐릭터 id, malformed id, missing id는 `404 NOT_FOUND`로 숨겨지고 인증 누락은 `401 AUTH_ACCESS_TOKEN_MISSING`으로 처리된다.
   - **And** 오류 응답에는 Authorization header, Bearer token, stack trace, DB 제약 상세가 포함되지 않는다.
   - **And** 동시 생성/활성화 후에도 사용자별 활성 캐릭터는 최대 1개다.

4. **Projection과 persistence boundary 검증**
   - **Given** 캐릭터 SoR은 `characters` 테이블이고 `/api/game/**`는 Vue 호환 facade일 때
   - **When** 캐릭터 생성/수정/삭제/이미지 재시도/성장 bridge 후 `game_states.state_json`을 조회하면
   - **Then** `state_json.characters`는 빈 배열로 저장된다.
   - **And** API 응답의 `state.characters`는 항상 `CharacterGameProjectionService`가 만든 normalized projection에서 온다.
   - **And** `spriteSheetUrl`과 `spriteMeta`는 API 응답에는 보이지만 `game_states` 저장본에 복제되지 않는다.
   - **And** `dailyReport.characterId`, pending reports, starter quizzes는 기존 compatibility 데이터를 유지하되 캐릭터 SoR로 승격하지 않는다.

5. **BE-3 인계 안정화**
   - **Given** BE-3가 학습 리포트 저장과 성장 루프를 구현할 때
   - **When** dev agent가 BE-2.6 결과를 참고하면
   - **Then** 활성 캐릭터 조회/잠금 경로, 점수 반영 메서드, 능력치 키 매핑, 전투력 합계, 진화 임계값, 감정/상태 메시지 갱신 경계가 문서화되어 있다.
   - **And** `algo/cs/db/net/fw` API 키와 `stat_algorithm/stat_cs/stat_db/stat_network/stat_framework` DB 컬럼 매핑이 명확하다.
   - **And** BE-3는 `CharacterCommandService.applyScoreDeltas(...)` 또는 동등한 domain method를 재사용해야 하며 raw SQL이나 JSON mutation으로 캐릭터 능력치를 바꾸면 안 된다는 가드레일이 있다.
   - **And** BE-3는 새 study log/report schema를 만들 수 있지만 BE-2.6에서는 만들지 않는다.

6. **문서 산출물**
   - **Given** BE-2 캐릭터 도메인을 이어받는 개발자가 있을 때
   - **When** `springboot/docs/character-api-contract.md` 또는 동등한 Spring Boot 하위 문서를 열면
   - **Then** 공개 endpoint 목록, 요청/응답 shape, 오류 코드, image status matrix, projection/persistence boundary, BE-3 handoff notes, 회귀 테스트 명령이 정리되어 있다.
   - **And** 문서는 실제 코드와 불일치하는 `/api/characters` 같은 신규 경로를 정식 계약으로 소개하지 않는다.

7. **회귀 검증**
   - **Given** BE-2.6 변경이 완료되면
   - **When** 테스트를 실행하면
   - **Then** `./gradlew test --tests 'com.commitgotchi.character.*'`가 통과한다.
   - **And** `./gradlew test --tests 'com.commitgotchi.swagger.*'`가 통과한다.
   - **And** 최종적으로 `./gradlew test`가 통과한다.
   - **And** 실패를 통과시키기 위해 기존 인증/CORS/Swagger/캐릭터 테스트의 assertion을 약화하지 않는다.

## Tasks / Subtasks

- [x] **Task 1: BE-2.6 원천 항목과 상세 문서를 정렬한다** (AC: 1, 6)
  - [x] `springboot/docs/springboot-backend-epics.md`의 BE-2 섹션에 BE-2.6을 유지한다.
  - [x] `springboot/docs/character-api-contract.md`를 추가하거나 기존 Spring Boot 문서에 캐릭터 계약을 정리한다.
  - [x] 상세 스토리와 계약 문서 모두 `springboot/` 하위 경로만 참조 대상으로 삼되, 루트 planning artifacts는 "참고 출처"로만 링크한다.

- [x] **Task 2: 캐릭터 OpenAPI 계약을 고정한다** (AC: 2)
  - [x] 기존 `JwtOpenApiContractIntegrationTest`를 보강하거나 새 `CharacterOpenApiContractIntegrationTest`를 추가한다.
  - [x] `/v3/api-docs`에서 BE-2 공개 경로가 모두 보이는지 검증한다.
  - [x] `GameController`의 `@AuthenticationPrincipal AuthPrincipal`이 문서 파라미터로 새지 않는지 검증한다.
  - [x] 필요하면 `@Operation`, `@ApiResponses`, `@Parameter(hidden = true)`, `@SecurityRequirement(name = "bearerAuth")`, `@Schema`를 추가한다.
  - [x] Swagger/OpenAPI profile gating 테스트는 기존 prod/local/dev/test 정책을 유지한다.

- [x] **Task 3: BE-2 end-to-end smoke regression을 추가한다** (AC: 3, 4, 7)
  - [x] 새 테스트 파일 예: `springboot/src/test/java/com/commitgotchi/character/CharacterEpicContractIntegrationTest.java`.
  - [x] 한 사용자로 3개 생성, 4번째 거부, 상세 조회, 수정, 활성 지정, retry-image, 삭제 후 재지정을 순서대로 검증한다.
  - [x] 다른 사용자 캐릭터 id에 대한 detail/update/delete/active/retry 접근이 모두 `404 NOT_FOUND`인지 검증한다.
  - [x] 인증 누락은 Spring Security filter chain을 통해 `401 AUTH_ACCESS_TOKEN_MISSING`이 되는지 검증한다.
  - [x] concurrent path가 필요한 경우 `Future.get(5, TimeUnit.SECONDS)`처럼 bounded wait를 사용한다.

- [x] **Task 4: projection/persistence boundary를 DB assertion으로 고정한다** (AC: 4)
  - [x] 캐릭터 생성 후 `SELECT state_json FROM game_states WHERE user_id=?`를 조회해 `characters`가 빈 배열로 저장되는지 확인한다.
  - [x] `GET /api/game/state`는 같은 사용자에게만 normalized characters를 projection하는지 확인한다.
  - [x] `spriteSheetUrl`/`spriteMeta`가 `characters` 테이블에는 남고 `game_states.state_json.characters`에는 복제되지 않는지 확인한다.
  - [x] `dailyReport.characterId`, pending report repair, starter quiz reference repair가 BE-2.3/BE-2.4와 동일하게 유지되는지 최소 smoke로 확인한다.

- [x] **Task 5: BE-3 handoff guardrails를 문서화한다** (AC: 5, 6)
  - [x] 활성 캐릭터 lookup은 `LearningCharacterRepository.findActiveByUserIdForUpdate(...)` 또는 ownership-aware locked lookup을 사용한다고 명시한다.
  - [x] 점수 반영은 `LearningCharacter.applyScoreDelta(...)`와 `CharacterCommandService.applyScoreDeltas(...)` 계열을 통해 수행한다고 명시한다.
  - [x] `battlePower`는 5개 능력치 합이며, `isEvolved`는 전투력 1,000 이상에서 캐릭터당 최대 1회 true가 된다고 명시한다.
  - [x] BE-3에서 리포트/퀴즈 정규화 테이블을 추가하더라도 `game_states.state_json.characters`를 캐릭터 SoR로 되돌리면 안 된다고 명시한다.

- [x] **Task 6: 전체 회귀를 실행한다** (AC: 7)
  - [x] `cd springboot && ./gradlew test --tests 'com.commitgotchi.character.*'`
  - [x] `cd springboot && ./gradlew test --tests 'com.commitgotchi.swagger.*'`
  - [x] `cd springboot && ./gradlew test`
  - [x] 테스트 실패 시 assertion 약화가 아니라 계약/구현 불일치를 수정한다.

### Review Findings

- [x] [Review][Patch] Starter quiz references can outlive deleted characters [springboot/src/main/java/com/commitgotchi/game/application/GameService.java:205] — Decision: repair/reassign stale starter quiz references to the replacement active character when deleting a character.
- [x] [Review][Patch] Backend epic document still describes BE-2 as pending and leaves resolved BE-2.6 API/schema decisions stale [springboot/docs/springboot-backend-epics.md:26]
- [x] [Review][Patch] Character contract omits or overgeneralizes response shapes for detail, delete, and full-payload PATCH semantics [springboot/docs/character-api-contract.md:25]
- [x] [Review][Patch] Retry-image contract omits the possible FAILED result when fallback URL or metadata is unavailable [springboot/docs/character-api-contract.md:124]
- [x] [Review][Patch] BE-3 idempotency handoff does not require the idempotency marker and character growth to commit atomically [springboot/docs/character-api-contract.md:151]
- [x] [Review][Patch] Sprite metadata coordinates do not specify row/column ordering [springboot/docs/character-api-contract.md:126]
- [x] [Review][Patch] Active deletion “latest” reassignment policy is not tied to `created_at DESC` ordering [springboot/docs/character-api-contract.md:21]
- [x] [Review][Patch] Quiz scoring can be marked successful if the character row disappears between projection and score update [springboot/src/main/java/com/commitgotchi/game/application/GameService.java:211]
- [x] [Review][Patch] Daily report delivery can mark scoreApplied when the character row disappears before score update [springboot/src/main/java/com/commitgotchi/game/application/GameService.java:252]
- [x] [Review][Patch] OpenAPI character schema/security/principal tests are under-asserted [springboot/src/test/java/com/commitgotchi/swagger/CharacterOpenApiContractIntegrationTest.java:31]
- [x] [Review][Patch] Character persistence-boundary tests allow missing `state_json.characters` and under-assert sprite metadata/daily delivery storage [springboot/src/test/java/com/commitgotchi/character/CharacterEpicContractIntegrationTest.java:328]
- [x] [Review][Patch] Character creation/update/image-client tests miss key contract assertions [springboot/src/test/java/com/commitgotchi/character/CharacterCreationApiIntegrationTest.java:88]
- [x] [Review][Patch] Repository lock coverage checks only one `ForUpdate` method [springboot/src/test/java/com/commitgotchi/character/domain/LearningCharacterRepositoryIntegrationTest.java:75]

## Dev Notes

### Source Requirements

- Spring Boot backend epics의 BE-2.6은 사용자의 직접 요청으로 추가된 캐릭터 에픽 마감 스토리다.
- PRD §4.2 FR-3~FR-7은 캐릭터 생성, 조회, 수정, 삭제, 활성 지정과 사용자당 최대 3개/활성 최대 1개를 요구한다.
- PRD §4.9 FR-21~FR-23은 능력치, 전투력, 진화, 감정, 상태 메시지를 학습/퀴즈 결과로만 갱신해야 한다고 요구한다.
- Architecture §5.2는 활성 캐릭터 단일성을 PostgreSQL partial unique index로 보장하라고 고정한다.
- Architecture §5.3은 리포트/퀴즈 점수 반영이 동일한 캐릭터 성장 규칙을 공유해야 하며 중복 반영을 막아야 한다고 정의한다.
- Architecture §6은 Spring Boot 실제 루트를 `springboot/`로 고정하고 `character/`, `character/image/`, `studylog/`, `quiz/`, `report/` 모듈 경계를 제시한다.
- Architecture §7.6은 sprite sheet가 1x3 frame map이며 진화는 baby/evolved sprite sheet URL 전환이라고 정의한다.

### Developer Context

- 인증 에픽은 완료되어 `AuthPrincipal`, JWT Access Token, Refresh Token rotation, 공통 오류 응답, CORS, Swagger profile gating이 동작한다.
- BE-2.1은 `characters` 테이블, `LearningCharacter`, image status enum, partial unique index, stat/battle power DB 제약을 만들었다.
- BE-2.2는 `POST /api/game/characters`와 `GET /api/game/state`를 normalized character projection에 연결하고, 새 캐릭터가 active가 되는 정책을 고정했다.
- BE-2.3은 상세 조회/수정/삭제와 not-found 계약, editable/system-owned field 경계, active 삭제 후 재지정, daily report repair를 고정했다.
- BE-2.4는 `PATCH /api/game/characters/{id}/active`를 정식 계약으로 만들고, explicit active switch가 daily report slot을 교체하도록 했다.
- BE-2.5는 image adapter/fallback/retry 계약을 구현하고 status를 `done`으로 옮겼다. `READY`/`FALLBACK`은 nonblank `spriteSheetUrl`이 있어야 한다.
- 현재 `GameService`는 BE-3~BE-6의 프로토타입 bridge도 품고 있다. BE-2.6은 이 코드를 정규화 도메인으로 전환하지 않고, 캐릭터 계약과 인계 문서만 단단히 한다.

### Current Files To Update

- `springboot/docs/springboot-backend-epics.md`
  - 현재 역할: Spring Boot 전용 backend epic/story SSOT.
  - 변경 목표: BE-2.6 원천 항목을 유지하고 BE-2 closure/handoff intent를 명확히 한다.
  - 보존: `springboot/` only 원칙, BE-3~BE-7 기존 계획.

- `springboot/docs/character-api-contract.md` (NEW)
  - 현재 역할: 없음.
  - 변경 목표: BE-2 캐릭터 API, response shape, 오류, projection/persistence boundary, BE-3 handoff를 한 문서로 정리한다.
  - 보존: 실제 공개 경로는 `/api/game/**`이며 신규 `/api/characters`를 정식화하지 않는다.

- `springboot/src/main/java/com/commitgotchi/game/api/GameController.java`
  - 현재 역할: `/api/game/**` facade controller. 캐릭터, 리포트, 퀴즈, 게시판 prototype endpoints가 함께 있다.
  - 변경 목표: 필요 시 OpenAPI annotations를 추가한다.
  - 보존: HTTP method/path, `@AuthenticationPrincipal AuthPrincipal`, `@Valid` DTO validation.

- `springboot/src/main/java/com/commitgotchi/character/api/dto/CharacterCreateRequest.java`
  - 현재 역할: 캐릭터 생성 입력 DTO.
  - 변경 목표: 필요 시 `@Schema` example/description을 보강한다.
  - 보존: `@NotBlank`, `@Size(max=40/120/500)` 검증과 field names `name`, `keyword`, `personality`.

- `springboot/src/main/java/com/commitgotchi/character/api/dto/CharacterUpdateRequest.java`
  - 현재 역할: 캐릭터 수정 입력 DTO.
  - 변경 목표: 필요 시 OpenAPI schema 보강.
  - 보존: 사용자 editable field만 받는 구조. `active`, stats, image status, sprite fields를 받지 않는다.

- `springboot/src/test/java/com/commitgotchi/swagger/JwtOpenApiContractIntegrationTest.java`
  - 현재 역할: 인증 OpenAPI 계약 검증.
  - 변경 목표: 보강하거나 캐릭터 전용 OpenAPI 테스트와 책임을 나눈다.
  - 보존: JWT/Refresh Token secret이 docs에 노출되지 않는 assertion.

- `springboot/src/test/java/com/commitgotchi/character/CharacterEpicContractIntegrationTest.java` (NEW)
  - 현재 역할: 없음.
  - 변경 목표: BE-2 cross-story smoke를 한 테스트에서 검증한다.
  - 보존: 기존 `CharacterCreationApiIntegrationTest`, `CharacterReadUpdateDeleteApiIntegrationTest`, `CharacterActivationApiIntegrationTest`, `CharacterImageGenerationApiIntegrationTest`의 세부 coverage를 대체하지 않는다.

### File Structure Requirements

```text
springboot/docs/
├── springboot-backend-epics.md
├── character-api-contract.md
└── stories/
    └── be-2-6-character-epic-contract-regression-and-be3-handoff.md

springboot/src/test/java/com/commitgotchi/character/
└── CharacterEpicContractIntegrationTest.java

springboot/src/test/java/com/commitgotchi/swagger/
└── CharacterOpenApiContractIntegrationTest.java
```

파일명은 구현 중 더 명확한 이름으로 바꿀 수 있다. 다만 새 테스트는 기존 세부 테스트를 제거하거나 느슨하게 만들지 않는다.

### Suggested Contract Document Outline

```markdown
# Character API Contract

## Public Endpoints
- GET /api/game/state
- POST /api/game/characters
- GET /api/game/characters/{id}
- PATCH /api/game/characters/{id}
- PATCH /api/game/characters/{id}/active
- POST /api/game/characters/{id}/retry-image
- DELETE /api/game/characters/{id}

## Character Projection
- id, name, keyword, personality
- stats: algo, cs, db, net, fw
- battlePower
- emotion
- isEvolved
- imageStatus
- spriteSheetUrl
- spriteMeta
- active
- message
- createdAt

## Persistence Boundary
- characters table is SoR.
- game_states.state_json.characters is persisted as [].
- /api/game/state overlays normalized character projection at response time.
```

### Implementation Guardrails

- BE-2.6는 새 domain feature를 만들지 않는다. 실제 기능 개발은 BE-3 이후 스토리에 둔다.
- `game_states.state_json.characters`를 되살려 캐릭터를 저장하지 않는다.
- `CharacterGameProjectionService`가 projection의 단일 중심이다. Controller나 tests에서 projection JSON을 수동 복제하지 않는다.
- `LearningCharacter` setter를 열지 않는다. 도메인 method와 application service를 통해 변경한다.
- `build.gradle` dependency upgrade는 기대값이 아니다.
- springdoc 최신 major/minor로 올리지 않는다. Spring Boot `3.3.5`와 springdoc `2.6.0` 현재 조합을 유지한다.
- OpenAPI 보강은 문서/annotation/test 수준에서 한다. API 경로를 `/api/characters`로 옮기지 않는다.
- `@AuthenticationPrincipal`이 OpenAPI parameter로 노출되면 `@Parameter(hidden = true)`나 springdoc ignored type 설정으로 숨긴다.
- character package tests가 느려지더라도 bounded concurrency wait를 사용한다. 무한 대기 테스트를 만들지 않는다.

### Testing Requirements

- OpenAPI:
  - `$.paths['/api/game/state'].get`
  - `$.paths['/api/game/characters'].post`
  - `$.paths['/api/game/characters/{id}'].get`
  - `$.paths['/api/game/characters/{id}'].patch`
  - `$.paths['/api/game/characters/{id}/active'].patch`
  - `$.paths['/api/game/characters/{id}/retry-image'].post`
  - `$.components.securitySchemes.bearerAuth`
  - no raw `AuthPrincipal` parameter exposed
- API smoke:
  - create 3 characters, reject 4th
  - update editable fields only
  - activate inactive character
  - retry image READY no-op and fallback path
  - delete active character and reassign latest remaining character
  - `GET /api/game/state` reflects normalized rows
- DB assertions:
  - `SELECT count(*) FROM characters WHERE user_id=? AND is_active=true`
  - `SELECT state_json FROM game_states WHERE user_id=?`
  - `SELECT image_status, sprite_sheet_url, sprite_meta FROM characters WHERE id=?`
- Commands:
  - `cd springboot && ./gradlew test --tests 'com.commitgotchi.character.*'`
  - `cd springboot && ./gradlew test --tests 'com.commitgotchi.swagger.*'`
  - `cd springboot && ./gradlew test`

### Previous Story Intelligence

- BE-2.1 review fixed image status constraints: `READY` and `FALLBACK` require nonblank sprite URL; `FAILED` clears sprite fields.
- BE-2.2 review decision allowed prototype reset for legacy JSON characters and made normalized projection the source for API responses.
- BE-2.3 fixed not-found behavior and daily report/pending report repair after delete. BE-2.6 should preserve those edge cases in smoke coverage.
- BE-2.4 introduced bounded concurrency testing for active switching. Reuse the bounded pattern.
- BE-2.5 moved image generation behind `CharacterImageClient` and made fallback the normal local/test outcome when adapter is disabled. BE-2.6 should not depend on real FastAPI.
- BE-2.5 review fixed FastAPI sprite metadata validation. Contract docs should mention required top-level `frameMap.joy/sad/angry` shape.

### Git Intelligence Summary

- Recent commit `2d2db7a chore: save frontend progress` introduced the Vue-compatible `/api/game/**` prototype layer that BE-2 must preserve.
- Current HEAD is `14a2069c7dc410b6f985dbb541ca9df1813fb35a`.
- Working tree already contains uncommitted Spring Boot backend work for BE-2.1~BE-2.5. Do not revert or normalize unrelated files.
- Current `springboot/` status shows `GameService`, `GameController`, common error handling, image settings, character package, migrations, and tests as active uncommitted work.

### Latest Technical Information

- The project pins Spring Boot `3.3.5`, Java 17, and `org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0`; keep this compatibility line.
- springdoc official docs state springdoc supports Spring Boot v3 and Java 17/Jakarta EE 9, and the current project already uses the WebMvc UI starter.
- springdoc official docs say it can help ignore `@AuthenticationPrincipal`, and `@Parameter(hidden = true)` is a documented workaround when that principal appears in generated docs.
- springdoc official docs list current `2.8.x` releases, but BE-2.6 should not upgrade because this story is contract hardening, not dependency maintenance.

### References

- [springboot/docs/springboot-backend-epics.md](../springboot-backend-epics.md) - BE-2.6 source story and springboot-only principles.
- [springboot/docs/stories/be-2-1-character-normalized-schema-and-domain-model.md](be-2-1-character-normalized-schema-and-domain-model.md) - normalized character schema, DB constraints, aggregate.
- [springboot/docs/stories/be-2-2-character-creation-and-first-activation.md](be-2-2-character-creation-and-first-activation.md) - creation and first activation.
- [springboot/docs/stories/be-2-3-character-read-update-delete.md](be-2-3-character-read-update-delete.md) - read/update/delete and projection repair.
- [springboot/docs/stories/be-2-4-active-character-selection.md](be-2-4-active-character-selection.md) - active switch and daily report slot behavior.
- [springboot/docs/stories/be-2-5-image-generation-adapter-and-fallback.md](be-2-5-image-generation-adapter-and-fallback.md) - image adapter, fallback, retry contract.
- [springboot/src/main/java/com/commitgotchi/game/api/GameController.java](../../src/main/java/com/commitgotchi/game/api/GameController.java) - BE-2 public facade endpoints.
- [springboot/src/main/java/com/commitgotchi/game/application/GameService.java](../../src/main/java/com/commitgotchi/game/application/GameService.java) - projection overlay and prototype bridge.
- [springboot/src/main/java/com/commitgotchi/character/application/CharacterGameProjectionService.java](../../src/main/java/com/commitgotchi/character/application/CharacterGameProjectionService.java) - character API projection.
- [springboot/src/main/java/com/commitgotchi/character/application/CharacterCommandService.java](../../src/main/java/com/commitgotchi/character/application/CharacterCommandService.java) - character update/activate/delete/growth service.
- [springboot/src/main/resources/db/migration/V4__create_characters.sql](../../src/main/resources/db/migration/V4__create_characters.sql) - DB constraints and indexes.
- [_bmad-output/planning-artifacts/prds/prd-commitgotchi-2026-06-07/prd.md](../../../_bmad-output/planning-artifacts/prds/prd-commitgotchi-2026-06-07/prd.md) - FR-3~7, FR-16, FR-21~23.
- [_bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md](../../../_bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md) - character constraints, source tree, image/sprite/growth contracts.
- [springdoc-openapi official docs](https://springdoc.org/) - WebMvc starter, Spring Security support, `@AuthenticationPrincipal` hiding guidance.

## Dev Agent Record

### Agent Model Used

GPT-5 Codex create-story context engine

### Debug Log References

- Loaded BMad config from `_bmad/bmm/config.yaml`: communication/document language Korean; planning artifacts under `_bmad-output/planning-artifacts`; implementation artifacts under `_bmad-output/implementation-artifacts`.
- Workflow activation resolved with no prepend/append steps and persistent fact glob `**/project-context.md`; no project-context file was found.
- No `sprint-status.yaml` exists under the repository.
- User explicitly selected BE 2.6 and constrained writes to `/springboot`; this story was written under `springboot/docs/stories/` and sprint status outside `springboot/` was not modified.
- BE-2.6 was not present in the initial Spring Boot backend epic document, so a new BE-2.6 source entry was added under `springboot/docs/springboot-backend-epics.md`.
- Discovered inputs: Spring Boot backend epics, root PRD, root architecture, root epics, previous BE-2.1/BE-2.2/BE-2.3/BE-2.4/BE-2.5 stories, current Spring Boot code/tests, and official springdoc docs.
- Dev workflow resumed with BE-2.6 story file inside `springboot/`; no sprint-status file exists and no files outside `/springboot` will be modified.
- RED: `./gradlew test --tests com.commitgotchi.swagger.CharacterOpenApiContractIntegrationTest` failed because BE-2 character operations had no operation-level `bearerAuth` requirement and DTO schema examples were absent.
- GREEN/REFACTOR: Added OpenAPI security/principal hiding and DTO schema metadata, then added BE-2 epic smoke and persistence-boundary regression tests.
- Validation passed: `./gradlew test --tests 'com.commitgotchi.character.*'`.
- Validation passed: `./gradlew test --tests 'com.commitgotchi.swagger.*'`.
- Validation passed: `./gradlew test`.
- Review docs chunk patch validation passed: `./gradlew test --tests com.commitgotchi.character.CharacterReadUpdateDeleteApiIntegrationTest`.
- Review docs chunk patch validation passed: `./gradlew test --tests 'com.commitgotchi.character.*'`.
- Review docs chunk patch validation passed: `./gradlew test --tests 'com.commitgotchi.swagger.*'`.
- Review domain/application chunk patch added stale-row guards for quiz scoring and daily report delivery.
- Review domain/application chunk patch validation passed: `./gradlew test --tests 'com.commitgotchi.character.*'`.
- Review tests chunk patch validation passed: `./gradlew test --tests com.commitgotchi.swagger.CharacterOpenApiContractIntegrationTest`.
- Review tests chunk patch validation passed: targeted character/image/repository test selection.
- Final review validation passed: `./gradlew test --tests 'com.commitgotchi.character.*'`.
- Final review validation passed: `./gradlew test --tests 'com.commitgotchi.swagger.*'`.
- Final review validation passed: `./gradlew test`.

### Completion Notes List

- Ultimate context engine analysis completed - comprehensive developer guide created.
- Status set to `ready-for-dev`.
- Story intentionally closes BE-2 with contract hardening, not new feature expansion.
- Story captures the missing source-story issue: BE-2.6 was user-requested and inferred as a Spring Boot character epic closure/handoff story.
- Key risks captured: accidental `/api/characters` path invention, `game_states` character persistence regression, OpenAPI leaking `AuthPrincipal`, weakening existing character tests, and BE-3 mutating character stats through JSON/raw SQL.
- Sprint status was not updated because the only writable scope requested by the user is `/springboot`.
- Added `springboot/docs/character-api-contract.md` with public `/api/game/**` endpoints, request/response shape, error codes, image status matrix, projection/persistence boundary, BE-3 handoff guardrails, and regression commands.
- Added `CharacterOpenApiContractIntegrationTest` and OpenAPI annotations/schema examples so BE-2 `/api/game/**` character endpoints require `bearerAuth`, hide `AuthPrincipal`, and document validation constraints.
- Validation passed: `./gradlew test --tests com.commitgotchi.swagger.CharacterOpenApiContractIntegrationTest`.
- Validation passed: `./gradlew test --tests 'com.commitgotchi.swagger.*'`.
- Added `CharacterEpicContractIntegrationTest` as an end-to-end BE-2 smoke gate for create/list/detail/update/activate/retry/delete, ownership hiding, unauthenticated security, and bounded concurrent activation.
- Validation passed: `./gradlew test --tests com.commitgotchi.character.CharacterEpicContractIntegrationTest`.
- Extended `CharacterEpicContractIntegrationTest` with DB assertions proving `game_states.state_json.characters` stays `[]`, sprite fields remain in normalized `characters`, API responses project sprite metadata, and daily report/pending report references survive repair.
- Validation passed: `./gradlew test --tests com.commitgotchi.character.CharacterEpicContractIntegrationTest`.
- Expanded BE-3 handoff guardrails in `character-api-contract.md`, including locked active lookup, `applyScoreDelta(s)` reuse, stat key/DB column mapping, battle power/evolution rules, emotion/message boundary, and the no raw SQL/JSON mutation rule.
- Completed BE-2.6 contract hardening and moved story status to `review`.
- Applied docs chunk review findings: starter quizzes referencing a deleted character are repaired to the replacement active character, backend epic status/schema/API decisions were refreshed, response/retry/sprite/idempotency/deletion-order contracts were clarified.
- Applied domain/application chunk review findings: quiz scoring and daily report delivery now fail instead of persisting a scored/applied marker if the normalized character row disappears between projection and score update.
- Applied tests chunk review findings: strengthened OpenAPI schema/security/principal assertions, local/dev OpenAPI smoke, persistence-boundary checks, daily-report delivery storage checks, sprite metadata DB assertion, creation/update system-owned coverage, FastAPI outbound request shape, and repository lock reflection.
- Final review pass resolved all patch findings and moved story status to `done`.

### File List

- `springboot/docs/character-api-contract.md`
- `springboot/docs/springboot-backend-epics.md`
- `springboot/docs/stories/be-2-6-character-epic-contract-regression-and-be3-handoff.md`
- `springboot/src/main/java/com/commitgotchi/character/api/dto/CharacterCreateRequest.java`
- `springboot/src/main/java/com/commitgotchi/character/api/dto/CharacterUpdateRequest.java`
- `springboot/src/main/java/com/commitgotchi/game/api/GameController.java`
- `springboot/src/main/java/com/commitgotchi/game/application/GameService.java`
- `springboot/src/test/java/com/commitgotchi/character/CharacterCreationApiIntegrationTest.java`
- `springboot/src/test/java/com/commitgotchi/character/CharacterEpicContractIntegrationTest.java`
- `springboot/src/test/java/com/commitgotchi/character/CharacterReadUpdateDeleteApiIntegrationTest.java`
- `springboot/src/test/java/com/commitgotchi/character/domain/LearningCharacterRepositoryIntegrationTest.java`
- `springboot/src/test/java/com/commitgotchi/character/image/FastApiCharacterImageClientTest.java`
- `springboot/src/test/java/com/commitgotchi/swagger/CharacterOpenApiContractIntegrationTest.java`
- `springboot/src/test/java/com/commitgotchi/swagger/DevSwaggerEnabledIntegrationTest.java`
- `springboot/src/test/java/com/commitgotchi/swagger/LocalSwaggerEnabledIntegrationTest.java`

## Change Log

- 2026-06-18: BE-2.6 story written as `ready-for-dev` under `springboot/docs/stories/`, respecting the springboot-only write constraint.
- 2026-06-18: BE-2.6 character contract docs, OpenAPI contract tests/annotations, BE-2 epic smoke regression, projection persistence-boundary assertions, and BE-3 handoff guardrails implemented; status moved to `review`.
- 2026-06-18: BMad code review patches applied across docs, domain/application guards, and regression tests; final character, swagger, and full test suites passed; status moved to `done`.
