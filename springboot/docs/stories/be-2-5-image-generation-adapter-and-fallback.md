---
story_id: BE-2.5
story_key: be-2-5-image-generation-adapter-and-fallback
epic: BE-2
status: done
created: 2026-06-18
scope: springboot-only
previous_story: be-2-4-active-character-selection
baseline_commit: 14a2069c7dc410b6f985dbb541ca9df1813fb35a
---

# Story BE-2.5: 이미지 생성 어댑터와 Fallback

Status: done

## Story

As a 사용자,
I want 이미지 생성 실패와 무관하게 캐릭터 생성이 완료되고,
so that AI 의존성 장애 때문에 학습 시작 흐름이 막히지 않게 할 수 있다.

## 목적과 범위

- `springboot/` 내부만 변경한다. Vue, FastAPI, `_bmad-output`은 참고만 한다.
- BE-2.1~BE-2.4에서 만든 `characters` 정규화 테이블, `LearningCharacter`, `CharacterImageStatus`, `CharacterCreationService`, `CharacterCommandService`, `CharacterGameProjectionService`, `/api/game/**` facade를 재사용한다.
- 이미지 생성 방식 충돌을 BE-2.5에서 고정한다. Spring Boot 구현은 아키텍처의 "FastAPI 동기 HTTP" 방향을 따르되, 캐릭터 생성 DB 트랜잭션은 외부 HTTP 장애 때문에 롤백되지 않아야 한다.
- 캐릭터는 생성 시 먼저 `PENDING`으로 저장된다. 이미지 처리 성공 시 `READY`, FastAPI `FAIL`/HTTP 오류/timeout/base-url 미설정/adapter disabled 시 `FALLBACK`으로 전이한다.
- 정상 생성 실패 경로에서는 `FAILED`로 끝내지 않는다. `FAILED`는 fallback URL/meta 자체가 설정 불가한 운영 설정 오류나 수동 재시도 실패를 표현하는 예외 상태로만 사용한다.
- `READY`와 `FALLBACK`은 DB 제약상 nonblank `sprite_sheet_url`이 필요하다. 빈 URL로 상태만 바꾸면 안 된다.
- `/api/game/characters/{id}/retry-image` 경로와 `{ state, item }` 응답 shape를 유지한다. BE-2.5부터 이 엔드포인트는 정식 이미지 재시도 계약이므로 malformed id, 없는 id, 타 사용자 id는 `404 NOT_FOUND`로 처리한다.
- 비범위: Vue 렌더링 변경, FastAPI 구현, 실제 S3 업로드, 리포트/퀴즈 정규화, `game_states` 제거, 신규 이미지 바이너리 제작.

## Acceptance Criteria

1. **이미지 상태 전이 고정**
   - **Given** 캐릭터 생성이 시작될 때
   - **When** 정규화 `characters` row가 처음 저장되면
   - **Then** 초기 상태는 `imageStatus=PENDING`, `spriteSheetUrl=null`, `spriteMeta=null`이다.
   - **And** 이미지 생성 성공 시 같은 캐릭터 row가 `READY`와 FastAPI 응답의 `spriteSheetUrl`, `spriteMeta`로 갱신된다.
   - **And** FastAPI 실패 응답, HTTP 예외, timeout, base-url 미설정, adapter disabled 중 하나가 발생하면 `FALLBACK`과 기본 sprite URL/meta가 저장된다.
   - **And** 캐릭터 생성 API 자체는 위 실패 때문에 5xx 또는 롤백으로 끝나지 않는다.

2. **FastAPI 포트/어댑터 격리**
   - **Given** Spring Boot가 이미지 생성을 요청할 때
   - **When** 구현을 추가하면
   - **Then** `character.image` 패키지 아래 포트 인터페이스와 FastAPI HTTP 어댑터를 둔다.
   - **And** application service는 인터페이스에만 의존하고 `RestClient`/HTTP DTO에 직접 의존하지 않는다.
   - **And** 테스트는 Fake 어댑터로 성공, FastAPI `FAIL`, 예외, timeout-equivalent 실패를 결정론적으로 검증한다.
   - **And** WebFlux/WebClient, Feign, Resilience4j 같은 새 의존성은 추가하지 않는다.

3. **FastAPI 요청/응답 계약**
   - **Given** 이미지 생성 어댑터가 enabled이고 base-url이 설정되어 있을 때
   - **When** Spring Boot가 `POST /api/ai/commitgotchi`를 호출하면
   - **Then** 요청 body는 최소 `userId`, `s3ObjectUrl`, `prompt`를 포함한다.
   - **And** `prompt`에는 `LearningCharacter.designKeyword`에서 온 간단한 키워드만 넣고, 전체 이미지 프롬프트 생성/관리는 FastAPI가 담당한다.
   - **And** `characterId`는 FastAPI 계약 필드로 보내지 않는다. 필요하면 `s3ObjectUrl` 경로에만 포함한다.
   - **And** 성공 응답 `status=OK`의 `spriteSheetUrl`이 blank/null이면 실패로 취급하고 fallback으로 간다.
   - **And** 실패 응답 `status=FAIL` 또는 알 수 없는 status는 생성 실패가 아니라 fallback 배정으로 흡수한다.

4. **Fallback sprite 계약**
   - **Given** 이미지 생성이 실패하거나 비활성화되어 있을 때
   - **When** fallback이 적용되면
   - **Then** `spriteSheetUrl`은 설정 가능한 nonblank URL이어야 한다.
   - **And** `spriteMeta`는 FastAPI 성공 응답과 같은 1행 3열 `frameMap` 구조를 가진 JSON 문자열이어야 한다.
   - **And** fallback meta는 top-level `joy/sad/angry` 매핑을 제공한다.
   - **And** fallback은 캐릭터별 능력치, active 여부, 감정, 진화 상태, 이름/키워드/성격을 변경하지 않는다.

5. **캐릭터 생성 흐름과 projection**
   - **Given** 인증된 사용자가 `POST /api/game/characters`를 호출할 때
   - **When** 이미지 성공 fake가 설정되어 있으면
   - **Then** 응답과 이후 `GET /api/game/state`의 해당 character는 `imageStatus=READY`, `spriteSheetUrl`, `spriteMeta`를 포함한다.
   - **When** 이미지 실패 fake 또는 adapter disabled가 설정되어 있으면
   - **Then** 응답과 이후 `GET /api/game/state`의 해당 character는 `imageStatus=FALLBACK`, fallback `spriteSheetUrl`, fallback `spriteMeta`를 포함한다.
   - **And** `CharacterGameProjectionService.project(...)`는 `spriteSheetUrl`과 `spriteMeta`를 additive field로 노출한다. 기존 `id`, `name`, `keyword`, `personality`, `stats`, `battlePower`, `emotion`, `isEvolved`, `imageStatus`, `active`, `message`, `createdAt` shape는 유지한다.

6. **재시도 엔드포인트**
   - **Given** 사용자가 자기 캐릭터의 이미지 생성을 재시도할 때
   - **When** `POST /api/game/characters/{id}/retry-image`를 호출하면
   - **Then** `PENDING`, `FAILED`, `FALLBACK` 상태는 이미지 어댑터를 다시 실행한다.
   - **And** 성공하면 `READY`, 실패하면 `FALLBACK`으로 저장한다.
   - **And** 이미 `READY`인 캐릭터는 멱등 성공으로 현재 projection을 반환하고 외부 HTTP를 다시 호출하지 않는다.
   - **And** malformed id, 없는 id, 타 사용자 id는 `404 NOT_FOUND`, 인증 없음은 `401 AUTH_ACCESS_TOKEN_MISSING`이다.
   - **And** 응답/로그에는 Authorization header, JWT, FastAPI raw stack trace, 내부 DB 제약 상세가 포함되지 않는다.

7. **트랜잭션과 장애 격리**
   - **Given** 캐릭터 생성과 이미지 생성이 함께 실행될 때
   - **When** FastAPI 호출이 느리거나 실패하면
   - **Then** 사용자 row lock, 활성 캐릭터 해제/지정, 보유 수 제한 트랜잭션을 장시간 붙잡지 않는다.
   - **And** 외부 HTTP 실패는 캐릭터 생성 롤백으로 전파하지 않는다.
   - **And** 이미지 상태 갱신은 캐릭터 소유권이 확인된 row에 대해 별도 application service transaction에서 수행한다.
   - **And** 동시 생성/활성화/삭제 테스트의 active count 불변식은 계속 통과해야 한다.

8. **기존 회귀 보존**
   - **Given** BE-2.1~BE-2.4 캐릭터 에픽 테스트가 있을 때
   - **When** BE-2.5가 완료되면
   - **Then** character package regression과 전체 Spring Boot 테스트가 통과한다.
   - **And** `game_states.state_json.characters`는 계속 캐릭터 SoR이 아니며, 새 sprite 필드를 JSON SoR에 저장하지 않는다.
   - **And** `V4__create_characters.sql`은 변경하지 않는 것이 기대값이다. 변경이 필요하면 DB 제약 통합 테스트를 함께 갱신한다.

## Tasks / Subtasks

- [x] **Task 1: 이미지 포트와 결과 모델을 추가한다** (AC: 2, 3, 4)
  - [x] `springboot/src/main/java/com/commitgotchi/character/image/CharacterImageClient.java`를 추가한다.
  - [x] 요청 모델은 `userId`, `characterId`, `designKeyword`, `s3ObjectUrl`, `prompt`를 내부적으로 표현하되, `prompt` 값은 짧은 디자인 키워드로 유지하고 FastAPI DTO에는 `userId`, `s3ObjectUrl`, `prompt`만 직렬화한다.
  - [x] 결과 모델은 `success(spriteSheetUrl, spriteMeta)`와 `failure(reason)`를 명확히 구분한다.
  - [x] `spriteMeta`는 `ObjectMapper`로 JSON 문자열화한다. 문자열 조립으로 JSON을 만들지 않는다.

- [x] **Task 2: 설정과 fallback meta를 추가한다** (AC: 1, 4)
  - [x] `CharacterImageProperties` 또는 동등한 `@ConfigurationProperties(prefix = "commitgotchi.character.image")`를 추가한다.
  - [x] 설정 예: `enabled`, `base-url`, `fallback-sprite-sheet-url`, `s3-object-prefix`, `connect-timeout`, `read-timeout`.
  - [x] 기본 fallback URL은 nonblank 값으로 둔다. 예: `https://cdn.commitgotchi.local/sprites/fallback-default.png`.
  - [x] fallback meta는 FastAPI 성공 meta와 같은 1x3 구조를 중앙 factory에서 만든다.
  - [x] 설정값이 blank인 채 `FALLBACK`으로 저장되는 일이 없도록 validation 또는 application guard를 둔다.

- [x] **Task 3: FastAPI HTTP 어댑터를 구현한다** (AC: 2, 3, 7)
  - [x] `springboot/src/main/java/com/commitgotchi/character/image/FastApiCharacterImageClient.java`를 추가한다.
  - [x] 기존 `spring-boot-starter-web`에 포함된 `RestClient.Builder`를 주입받아 사용한다.
  - [x] adapter base URL과 timeout은 설정에서 가져온다.
  - [x] 2xx + `status=OK` + nonblank `spriteSheetUrl`만 성공으로 취급한다.
  - [x] 4xx/5xx, I/O 예외, JSON 매핑 실패, blank URL, `status=FAIL`, unknown status는 exception을 밖으로 던지기보다 `failure(reason)`로 변환한다.
  - [x] 로그는 reason code, character id, traceId 수준으로 제한하고 prompt 전문이나 토큰/헤더를 남기지 않는다.

- [x] **Task 4: 이미지 application service를 추가한다** (AC: 1, 4, 7)
  - [x] `CharacterImageService` 또는 동등한 application service를 추가한다.
  - [x] `generateOrFallback(userId, characterId)`는 ownership-aware locked lookup을 사용해 자기 캐릭터만 갱신한다.
  - [x] 성공 시 `LearningCharacter.markReady(spriteSheetUrl, spriteMeta)`를 호출한다.
  - [x] 실패 시 `LearningCharacter.markFallback(fallbackSpriteSheetUrl, fallbackSpriteMeta)`를 호출한다.
  - [x] fallback 설정 자체가 잘못되어 nonblank URL/meta를 만들 수 없을 때만 `markFailed()`를 사용한다.
  - [x] 이 service는 이름/키워드/성격, active, stats, battlePower, emotion, evolved를 변경하지 않는다.

- [x] **Task 5: 생성 흐름에 이미지 처리를 연결한다** (AC: 1, 5, 7, 8)
  - [x] `CharacterCreationService.create(...)`의 보유 수 제한, active 해제/flush/activate 저장 순서를 보존한다.
  - [x] 외부 HTTP를 사용자 row lock과 active uniqueness 전환 트랜잭션 안에서 오래 수행하지 않도록 트랜잭션 경계를 분리한다.
  - [x] 생성 직후 projection은 이미지 처리 결과를 반영해 `READY` 또는 `FALLBACK`을 반환하도록 `GameService.createCharacter(...)`를 갱신한다.
  - [x] adapter disabled 또는 base-url 미설정 local/test 프로필에서는 fallback으로 즉시 완료해 개발 환경이 FastAPI 없이 통과하게 한다.
  - [x] starter quiz, `dailyReport.characterId`, normalized character projection overlay는 BE-2.2~BE-2.4 동작을 유지한다.

- [x] **Task 6: retry-image를 정식 계약으로 보강한다** (AC: 6, 8)
  - [x] `CharacterCommandService.retryImage(...)`의 현재 `FAILED -> PENDING` 껍데기 구현을 이미지 service 호출 흐름으로 대체하거나 위임한다.
  - [x] `GameService.retryImage(...)`는 malformed id와 ownership 실패를 `CharacterNotFoundException`으로 변환한다.
  - [x] `READY`는 외부 호출 없이 멱등 성공한다.
  - [x] `PENDING`, `FAILED`, `FALLBACK`은 adapter 실행 후 `READY` 또는 `FALLBACK` projection을 반환한다.
  - [x] `state.characters`와 `item` 모두 최신 `spriteSheetUrl`, `spriteMeta`, `imageStatus`를 포함한다.

- [x] **Task 7: projection과 API 테스트를 추가한다** (AC: 5, 6, 8)
  - [x] 새 테스트 파일 `springboot/src/test/java/com/commitgotchi/character/CharacterImageGenerationApiIntegrationTest.java`를 추가한다.
  - [x] Fake success client로 생성 응답 `READY`, nonblank `spriteSheetUrl`, parseable `spriteMeta.frameMap`을 검증한다.
  - [x] Fake failure/disabled client로 생성 응답 `FALLBACK`, fallback URL/meta, 캐릭터 생성 성공, active count 1을 검증한다.
  - [x] retry success/failure/ready-no-op/malformed/cross-owner/unauthenticated 경계를 검증한다.
  - [x] `GET /api/game/state`가 sprite fields를 계속 반환하는지 검증한다.
  - [x] 기존 BE-2.2 생성 테스트의 `imageStatus=PENDING` 기대값은 BE-2.5의 확정 계약에 맞춰 갱신한다.

- [x] **Task 8: 회귀 검증을 실행한다** (AC: 7, 8)
  - [x] `cd springboot && ./gradlew test --tests com.commitgotchi.character.CharacterImageGenerationApiIntegrationTest`
  - [x] `cd springboot && ./gradlew test --tests 'com.commitgotchi.character.*'`
  - [x] `cd springboot && ./gradlew test`
  - [x] 실패 시 인증/CORS/Swagger/캐릭터 활성화 테스트를 약화하지 않고 원인을 수정한다.

### Review Findings

- [x] [Review][Patch] Validate FastAPI sprite metadata before marking a character READY [springboot/src/main/java/com/commitgotchi/character/image/FastApiCharacterImageClient.java:86] — Fixed by rejecting `OK` responses whose `spriteMeta` lacks the required top-level `frameMap.joy/sad/angry` coordinate structure, with adapter unit coverage.

## Dev Notes

### Source Requirements

- Spring Boot backend epics의 BE-2.5는 이미지 생성 방식 결정, FastAPI 포트 격리, Fake 어댑터 검증, 실패 시 fallback 저장, `retry-image` 호환을 완료 기준으로 둔다.
- PRD FR-3은 캐릭터 생성 직후 이미지 생성을 시도하되, 이미지가 실패하거나 지연되어도 캐릭터 생성 자체는 성공해야 한다고 정의한다.
- PRD/Architecture는 캐릭터 이미지를 단일 이미지가 아니라 감정 3종의 1x3 투명 PNG 스프라이트시트로 정의한다. 진화는 같은 시트의 행 전환이 아니라 baby/evolved 시트 URL 전환으로 표현한다.
- Architecture §4.4는 Spring Boot -> FastAPI `POST /api/ai/commitgotchi` 요청 필드를 `userId`, `s3ObjectUrl`, `prompt`로 고정하고, 성공 응답의 `spriteSheetUrl`/`spriteMeta`를 Spring Boot가 저장한다고 정의한다.
- Architecture AD-12는 3프레임 1x3 스프라이트시트를 확정했고, AD-13은 이미지를 동기 HTTP 흐름으로 확정했다.
- Architecture §4.6은 이미지 흐름 C의 멱등 키를 `userId + s3ObjectUrl`로 둔다. BE-2.5에서는 같은 character retry가 같은 object URL을 재사용하게 해 중복 저장 위치를 만들지 않는다.

### Developer Context

- 인증 에픽은 완료되어 `AuthPrincipal`, JWT Access Token, Refresh Token rotation, 공통 오류 응답, CORS, Swagger 프로필 제어가 동작한다.
- BE-2.1은 `CharacterImageStatus` enum과 `LearningCharacter.markReady`, `markFallback`, `markFailed`, `markPending`을 추가했다.
- BE-2.1 review 결정은 중요하다: `READY`와 `FALLBACK`은 nonblank sprite URL이 필요하고, `FAILED`는 sprite URL/meta를 비운다.
- BE-2.2는 생성 직후 이미지를 호출하지 않았기 때문에 `PENDING`을 반환했다. BE-2.5는 이 기대값을 확정된 이미지 계약으로 갱신하는 첫 스토리다.
- BE-2.3은 `spriteSheetUrl`/`spriteMeta`를 BE-2.5 전에는 projection 필수로 만들지 않았다. 이제 additive field로 노출해야 한다.
- BE-2.4는 `retry-image`의 200/null 실패 정책을 일부러 건드리지 않았다. BE-2.5에서는 정식 이미지 재시도 API가 되므로 character CRUD와 같은 `404 NOT_FOUND` 계약으로 고정한다.
- 현재 `GameService.retryImage(...)`는 malformed id에서 200 + `item:null`을 반환할 수 있고, `CharacterCommandService.retryImage(...)`는 `FAILED`일 때 `markPending()`만 호출한다. 이 둘이 BE-2.5의 핵심 변경 지점이다.
- 현재 `CharacterCreationService.create(...)`는 사용자 row lock을 잡고 기존 active 해제/flush 후 새 active를 저장한다. 이 락 구간에 외부 HTTP를 오래 붙이면 생성/활성화 동시성 테스트가 불안정해질 수 있다.
- 현재 `CharacterGameProjectionService.project(...)`는 sprite fields를 반환하지 않는다.

### Current Files To Update

- `springboot/src/main/java/com/commitgotchi/character/domain/LearningCharacter.java`
  - 현재 역할: 캐릭터 aggregate와 image status 전이 메서드를 가진다.
  - 변경 목표: 가능하면 기존 `markReady`, `markFallback`, `markFailed`, `markPending`을 그대로 재사용한다. 새 setter를 열지 않는다.
  - 보존: 능력치/전투력/진화/감정/active 메서드와 DB 제약 정합성.

- `springboot/src/main/java/com/commitgotchi/character/application/CharacterCreationService.java`
  - 현재 역할: 캐릭터 생성, 최대 3개 제한, 새 캐릭터 active 정책, active uniqueness flush 순서를 담당한다.
  - 변경 목표: 이미지 처리 연결 시 외부 HTTP가 보유 수 제한과 active 전환 트랜잭션을 오래 붙잡지 않게 한다.
  - 보존: 사용자 row lock, 최대 3개 제한, 새 캐릭터 active 정책, 기존 active 해제 후 flush.

- `springboot/src/main/java/com/commitgotchi/character/application/CharacterCommandService.java`
  - 현재 역할: update/find/activate/delete/retry/react/score delta application service.
  - 변경 목표: `retryImage`를 실제 이미지 service 흐름에 맞게 재정의하거나 최소한 ownership-aware lookup helper로 축소한다.
  - 보존: 소유권을 드러내는 lookup, system-owned field 보호, active/delete transaction behavior.

- `springboot/src/main/java/com/commitgotchi/character/application/CharacterGameProjectionService.java`
  - 현재 역할: Vue-compatible character DTO를 만든다.
  - 변경 목표: `spriteSheetUrl`과 `spriteMeta`를 nullable/additive field로 추가한다. `spriteMeta`는 JSON object로 내려도 되고 문자열로 내려도 되지만, 한 계약으로 테스트를 고정한다. 권장: JSON object.
  - 보존: 기존 fields, stat key mapping, lower-case emotion.

- `springboot/src/main/java/com/commitgotchi/game/application/GameService.java`
  - 현재 역할: `/api/game/**` facade, state load/save, character projection overlay, retry-image endpoint.
  - 변경 목표: create/retry 응답에 최신 이미지 projection을 반영하고, retry 실패 계약을 404로 보강한다.
  - 보존: `GameMutationResponse(state,item)`, starter quiz, `dailyReport.characterId`, `game_states.state_json.characters` 비저장 정책.

- `springboot/src/main/java/com/commitgotchi/game/api/GameController.java`
  - 현재 역할: `/api/game/characters/{id}/retry-image` 경로를 이미 제공한다.
  - 변경 목표: 경로와 HTTP method는 유지한다. 특별한 이유가 없으면 Controller 변경은 필요하지 않다.
  - 보존: `/api/game` base path와 `@AuthenticationPrincipal AuthPrincipal`.

- `springboot/src/main/resources/application.yml`
  - 현재 역할: DB/JPA/Flyway/Springdoc/auth/cors/jwt 설정.
  - 변경 목표: `commitgotchi.character.image.*` 설정 기본값을 추가한다.
  - 보존: 기존 profile split, Swagger prod 비활성, JWT 설정.

### Expected New Files

```text
springboot/src/main/java/com/commitgotchi/character/image/
├── CharacterImageClient.java
├── CharacterImageGenerationRequest.java
├── CharacterImageGenerationResult.java
├── CharacterImageProperties.java
├── CharacterSpriteMetaFactory.java
└── FastApiCharacterImageClient.java

springboot/src/main/java/com/commitgotchi/character/application/
└── CharacterImageService.java

springboot/src/test/java/com/commitgotchi/character/
└── CharacterImageGenerationApiIntegrationTest.java
```

파일명은 구현 관례에 맞게 조정 가능하다. 다만 `character.image` 패키지로 외부 HTTP DTO와 fallback meta 생성 책임을 격리한다. 전체 이미지 프롬프트 생성 책임은 FastAPI에 둔다.

### Suggested API Contract

`POST /api/game/characters`

Success with image ready:

```json
{
  "state": {
    "characters": [
      {
        "id": 3,
        "name": "커밋 몬스터",
        "keyword": "초록 학습 슬라임",
        "personality": "다정하지만 정확한 성격",
        "stats": { "algo": 0, "cs": 0, "db": 0, "net": 0, "fw": 0 },
        "battlePower": 0,
        "emotion": "joy",
        "isEvolved": false,
        "imageStatus": "READY",
        "spriteSheetUrl": "https://cdn.example.com/sprites/users/1/commitgotchi-3.png",
        "spriteMeta": {
          "columns": 3,
          "rows": 1,
          "frameMap": {
            "joy": [0, 0],
            "sad": [0, 1],
            "angry": [0, 2]
          },
          "transparent": true
        },
        "active": true,
        "message": "Ready to learn",
        "createdAt": "2026-06-18T02:30:00Z"
      }
    ]
  },
  "item": {
    "id": 3,
    "imageStatus": "READY",
    "spriteSheetUrl": "https://cdn.example.com/sprites/users/1/commitgotchi-3.png"
  }
}
```

`POST /api/game/characters/{id}/retry-image`

- `READY`: HTTP 200, current `{ state, item }`, no external client call.
- `PENDING|FAILED|FALLBACK`: execute image client, save `READY` or `FALLBACK`, return current `{ state, item }`.
- malformed/missing/cross-owner: HTTP 404, `code=NOT_FOUND`.
- unauthenticated: HTTP 401, `code=AUTH_ACCESS_TOKEN_MISSING`.

### Implementation Guardrails

- 새 character table이나 image table을 만들지 않는다. BE-2.5는 existing `characters.sprite_sheet_url`와 `characters.sprite_meta`를 사용한다.
- `READY`/`FALLBACK` 상태를 URL 없이 저장하지 않는다. `V4__create_characters.sql`의 CHECK 제약이 막아야 하며, application test도 막아야 한다.
- FastAPI HTTP DTO를 `GameService`나 `CharacterCreationService`에 직접 흘리지 않는다. 포트/어댑터 뒤에 둔다.
- FastAPI raw error body, prompt 전문, Authorization header, JWT를 로그/오류 응답에 넣지 않는다.
- 외부 HTTP 장애를 `GlobalExceptionHandler`까지 올려 사용자 5xx로 만들지 않는다. 이미지 실패는 fallback 상태라는 도메인 결과다.
- `game_states.state_json.characters`에 sprite URL/meta를 복제하지 않는다. 매 응답은 normalized projection overlay에서 온다.
- `build.gradle`에 새 HTTP client 의존성을 추가하지 않는다. Spring Boot `3.3.5` + starter-web의 `RestClient`로 충분하다.
- FastAPI가 아직 없거나 로컬에서 꺼져 있어도 테스트와 dev 실행이 가능해야 한다. adapter disabled/base-url unset은 fallback으로 동작한다.
- retry-image가 `READY`를 매번 재생성하면 불필요한 비용과 비결정성을 만든다. `READY`는 no-op가 기본이다.

### Architecture Compliance

- Spring Boot가 PostgreSQL System of Record를 소유한다. FastAPI는 DB를 읽거나 쓰지 않는다.
- FastAPI는 이미지 생성 계산과 저장 대상 URL에 대한 작업만 수행하고, Spring Boot는 결과 URL/meta만 저장한다.
- 이미지는 3프레임 1x3 sprite sheet이며, 진화는 baby/evolved sprite sheet URL 전환으로 표현된다.
- API layer는 repository나 HTTP client를 직접 다루지 않는다.
- Application service가 transaction boundary를 가진다.
- `ddl-auto=validate` 정책을 유지한다.
- 기존 `/api/game/**` facade는 Vue 호환 계층이다. 이미지 상태의 진실은 `characters.image_status`, `sprite_sheet_url`, `sprite_meta`다.

### Library and Framework Requirements

- 프로젝트 버전을 유지한다: Spring Boot `3.3.5`, Java 17, Spring Data JPA, Spring Security, Flyway, PostgreSQL, Testcontainers.
- Spring Boot 3.3 공식 문서는 `RestClient.Builder` bean을 Boot가 미리 구성하므로 컴포넌트에 주입해 `RestClient`를 만들 것을 권장한다.
- Spring Boot 3.3 공식 문서는 WebFlux가 없는 non-reactive app에서는 blocking `RestClient`를 사용할 수 있다고 설명한다. 이 프로젝트는 WebFlux를 쓰지 않으므로 `RestClient`가 맞다.
- Spring Boot 공식 문서는 `@ConfigurationProperties`가 type-safe external configuration과 relaxed binding을 제공한다고 설명한다. 이미지 base-url, timeout, fallback URL은 `@Value` 난립보다 properties 클래스로 묶는다.
- Spring Framework 공식 문서는 `@TransactionalEventListener`가 기본적으로 commit phase에 묶이고 `AFTER_COMMIT` 등 phase를 지정할 수 있다고 설명한다. 트랜잭션 이후 처리가 필요하면 이 패턴을 사용하되, 테스트 결정성을 해치지 않게 설계한다.
- 최신 Spring major line으로 업그레이드하지 않는다. 이 저장소는 Gradle plugin과 dependency management가 관리하는 현재 버전을 기준으로 한다.

### Testing Requirements

- Run: `cd springboot && ./gradlew test --tests com.commitgotchi.character.CharacterImageGenerationApiIntegrationTest`.
- Run: `cd springboot && ./gradlew test --tests 'com.commitgotchi.character.*'`.
- Run: `cd springboot && ./gradlew test`.
- API tests should use `MockMvc` through the real Spring Security filter chain.
- DB assertions:
  - `SELECT image_status, sprite_sheet_url, sprite_meta FROM characters WHERE id=?`
  - `READY` and `FALLBACK` have nonblank `sprite_sheet_url`
  - `FAILED` has null sprite fields only when fallback configuration cannot be applied
  - active count remains 1 after create/retry
- Response assertions:
  - `$.item.imageStatus`
  - `$.item.spriteSheetUrl`
  - `$.item.spriteMeta.frameMap.joy[0]`
  - `$.state.characters[?(@.id == <id>)].spriteSheetUrl`
  - retry failure `$.code` for `NOT_FOUND` and `AUTH_ACCESS_TOKEN_MISSING`
- Existing auth/CORS/Swagger, BE-2.2 creation, BE-2.3 read/update/delete, BE-2.4 activation, report/quiz/daily bridge tests are regression guards. Do not weaken them.

### Previous Story Intelligence

- BE-2.1 established `LearningCharacter` as canonical aggregate and `characters` as normalized SoR.
- BE-2.1 added DB constraints for image status: `READY` and `FALLBACK` require nonblank sprite URL; invalid image status is rejected.
- BE-2.1 added system image methods. Use `markReady`, `markFallback`, `markFailed`, `markPending` rather than direct field mutation.
- BE-2.2 connected `POST /api/game/characters` and `GET /api/game/state` to normalized projection and kept `game_states` as compatibility storage only.
- BE-2.2 intentionally did not call FastAPI or S3; generated characters stayed `PENDING`.
- BE-2.3 added `battlePower` projection and warned that `spriteSheetUrl`/`spriteMeta` should only become additive fields in BE-2.5.
- BE-2.3 fixed character not-found behavior to `404 NOT_FOUND` for read/update/delete.
- BE-2.4 fixed activation not-found behavior but left `retry-image` untouched. BE-2.5 owns this gap.
- BE-2.4 concurrent tests require bounded waits; do the same if retry/create image tests use async or concurrent execution.

### Git Intelligence Summary

- Recent commit `2d2db7a chore: save frontend progress` introduced the Vue-compatible `/api/game/**` prototype layer.
- Current HEAD is `14a2069c7dc410b6f985dbb541ca9df1813fb35a`.
- Working tree already contains uncommitted Spring Boot backend story work for BE-2.1~BE-2.4. Do not revert or normalize unrelated files.
- Current `springboot/` status shows character domain, `GameService`, `GameController`, common error handling, and character tests as uncommitted additions/modifications. BE-2.5 must work with that state.

### Latest Technical Information

- Spring Boot 3.3 official docs recommend injecting the auto-configured `RestClient.Builder`; this preserves Boot's message converters and request factory configuration.
- Spring Boot current official docs say `RestClient.Builder` is stateful and can be cloned when creating multiple clients. BE-2.5 should build one image client with narrow local customization.
- Spring Boot external config docs recommend `@ConfigurationProperties` for type-safe relaxed binding and validation. Use it for image adapter settings.
- Spring Framework current docs support transaction-bound events with `@TransactionalEventListener` and default commit-phase behavior. This is useful if the implementation chooses post-commit image handling instead of calling HTTP inside the create transaction.

### References

- [springboot/docs/springboot-backend-epics.md](../springboot-backend-epics.md) - BE-2.5 source story and springboot-only principles.
- [springboot/docs/stories/be-2-1-character-normalized-schema-and-domain-model.md](be-2-1-character-normalized-schema-and-domain-model.md) - image status enum, DB constraints, aggregate methods.
- [springboot/docs/stories/be-2-2-character-creation-and-first-activation.md](be-2-2-character-creation-and-first-activation.md) - creation flow and prior `PENDING` boundary.
- [springboot/docs/stories/be-2-3-character-read-update-delete.md](be-2-3-character-read-update-delete.md) - projection and not-found contract.
- [springboot/docs/stories/be-2-4-active-character-selection.md](be-2-4-active-character-selection.md) - activation transaction and retry-image gap.
- [springboot/src/main/java/com/commitgotchi/character/domain/LearningCharacter.java](../../src/main/java/com/commitgotchi/character/domain/LearningCharacter.java) - image status aggregate methods.
- [springboot/src/main/java/com/commitgotchi/character/domain/CharacterImageStatus.java](../../src/main/java/com/commitgotchi/character/domain/CharacterImageStatus.java) - `PENDING`, `READY`, `FALLBACK`, `FAILED`.
- [springboot/src/main/java/com/commitgotchi/character/application/CharacterCreationService.java](../../src/main/java/com/commitgotchi/character/application/CharacterCreationService.java) - creation transaction to preserve.
- [springboot/src/main/java/com/commitgotchi/character/application/CharacterCommandService.java](../../src/main/java/com/commitgotchi/character/application/CharacterCommandService.java) - current retry shell.
- [springboot/src/main/java/com/commitgotchi/character/application/CharacterGameProjectionService.java](../../src/main/java/com/commitgotchi/character/application/CharacterGameProjectionService.java) - projection to extend.
- [springboot/src/main/java/com/commitgotchi/game/application/GameService.java](../../src/main/java/com/commitgotchi/game/application/GameService.java) - `/api/game/**` compatibility facade.
- [springboot/src/main/resources/db/migration/V4__create_characters.sql](../../src/main/resources/db/migration/V4__create_characters.sql) - sprite URL/image status DB constraints.
- [_bmad-output/planning-artifacts/prds/prd-commitgotchi-2026-06-07/prd.md](../../../_bmad-output/planning-artifacts/prds/prd-commitgotchi-2026-06-07/prd.md) - FR-3, FR-4, FR-16, FR-21~23.
- [_bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md](../../../_bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md) - image flow, sprite layout, source tree, AD-12/AD-13.
- [Spring Boot 3.3 REST Clients](https://docs.spring.io/spring-boot/3.3/reference/io/rest-client.html)
- [Spring Boot Externalized Configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html)
- [Spring Framework Transaction-bound Events](https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html)

## Dev Agent Record

### Agent Model Used

GPT-5 Codex create-story context engine

### Debug Log References

- Loaded BMad config from `_bmad/bmm/config.yaml`: communication/document language Korean; planning artifacts under `_bmad-output/planning-artifacts`; implementation artifacts under `_bmad-output/implementation-artifacts`.
- Workflow activation resolved with no prepend/append steps and persistent fact glob `**/project-context.md`; no project-context file was found.
- No `sprint-status.yaml` exists under the repository.
- User explicitly selected BE 2.5 and constrained writes to `/springboot`; this story was written under `springboot/docs/stories/` and sprint status outside `springboot/` was not modified.
- Discovered inputs: Spring Boot backend epics, root PRD, root architecture, root epics, previous BE-2.1/BE-2.2/BE-2.3/BE-2.4 stories, current Spring Boot code, and official Spring docs for RestClient/config/transaction-bound events.
- Dev workflow resumed with BE-2.5 story file inside `springboot/`; no sprint-status file was modified.
- RED: `./gradlew test --tests com.commitgotchi.character.CharacterImageGenerationApiIntegrationTest` failed at compile because `com.commitgotchi.character.image` port/result/request types did not exist.
- GREEN/REFACTOR: Added image port, properties, storage/meta factories, FastAPI `RestClient` adapter, image application service, creation/retry wiring, projection sprite fields, and API regression tests.
- Validation passed: `./gradlew test --tests com.commitgotchi.character.CharacterImageGenerationApiIntegrationTest`.
- Validation passed: `./gradlew test --tests 'com.commitgotchi.character.*'`.
- Validation passed: `./gradlew test`.

### Completion Notes List

- Ultimate context engine analysis completed - comprehensive developer guide created.
- Status set to `ready-for-dev`.
- Story resolves the image-flow conflict by using Spring Boot's synchronous FastAPI adapter direction with mandatory fallback and no creation rollback.
- Story intentionally scopes implementation to Spring Boot adapter, fallback state, projection, retry endpoint, and tests.
- Key implementation risks captured: URL-less `READY/FALLBACK`, external HTTP inside active-character lock transaction, retry-image 200/null hidden failures, and projection missing sprite fields.
- Implemented `character.image` port and FastAPI adapter so application services depend on `CharacterImageClient`, not HTTP DTOs.
- Added type-safe `commitgotchi.character.image.*` settings with default disabled mode, nonblank fallback URL, S3 object prefix, and connect/read timeouts.
- Added centralized 1x3 fallback sprite metadata factory using `ObjectMapper` JSON serialization.
- Added `CharacterImageService.generateOrFallback(...)` with pre-call ownership lookup, external call outside the create transaction, and REQUIRES_NEW locked row update for `READY`/`FALLBACK`/rare `FAILED`.
- Wired `POST /api/game/characters` so responses and subsequent state projection return `READY` on fake success or `FALLBACK` when disabled/failure occurs, without rolling back character creation.
- Hardened `POST /api/game/characters/{id}/retry-image`: malformed, missing, and cross-owner IDs now return `404 NOT_FOUND`; `READY` is no-op; `PENDING`/`FAILED`/`FALLBACK` rerun image processing.
- Extended character projection with additive `spriteSheetUrl` and parsed-object `spriteMeta` fields while preserving existing character fields.
- Added `CharacterImageGenerationApiIntegrationTest` covering fake success, FastAPI-style failure, client exception, timeout-equivalent failure, retry READY no-op, retry fallback-to-ready, malformed/missing/cross-owner 404, unauthenticated 401, state projection, and DB image columns.
- Added `FastApiCharacterImageClientTest` covering valid, scalar, and incomplete FastAPI `spriteMeta` responses at the adapter boundary.
- Updated existing character API expectations from pre-BE-2.5 `PENDING` to the fallback contract used when the adapter is disabled.

### File List

- `springboot/docs/stories/be-2-5-image-generation-adapter-and-fallback.md`
- `springboot/src/main/java/com/commitgotchi/character/application/CharacterCommandService.java`
- `springboot/src/main/java/com/commitgotchi/character/application/CharacterGameProjectionService.java`
- `springboot/src/main/java/com/commitgotchi/character/application/CharacterImageService.java`
- `springboot/src/main/java/com/commitgotchi/character/image/CharacterImageClient.java`
- `springboot/src/main/java/com/commitgotchi/character/image/CharacterImageGenerationRequest.java`
- `springboot/src/main/java/com/commitgotchi/character/image/CharacterImageGenerationResult.java`
- `springboot/src/main/java/com/commitgotchi/character/image/CharacterImageProperties.java`
- `springboot/src/main/java/com/commitgotchi/character/image/CharacterSpriteMetaFactory.java`
- `springboot/src/main/java/com/commitgotchi/character/image/FastApiCharacterImageClient.java`
- `springboot/src/main/java/com/commitgotchi/game/application/GameService.java`
- `springboot/src/main/resources/application.yml`
- `springboot/src/test/java/com/commitgotchi/character/CharacterCreationApiIntegrationTest.java`
- `springboot/src/test/java/com/commitgotchi/character/CharacterImageGenerationApiIntegrationTest.java`
- `springboot/src/test/java/com/commitgotchi/character/CharacterReadUpdateDeleteApiIntegrationTest.java`
- `springboot/src/test/java/com/commitgotchi/character/image/FastApiCharacterImageClientTest.java`

## Change Log

- 2026-06-18: BE-2.5 story written as `ready-for-dev` under `springboot/docs/stories/`, respecting the springboot-only write constraint.
- 2026-06-18: BE-2.5 image generation adapter, fallback handling, projection, retry contract, and regression tests implemented; status moved to `review`.
- 2026-06-18: Code review patch applied for FastAPI sprite metadata validation; status moved to `done`.
