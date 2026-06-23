# Character API Contract

## Scope

이 문서는 Spring Boot BE-2 캐릭터 도메인의 임시 Vue 호환 facade를 설명한다. 최종 SSOT는 `_bmad-output/planning-artifacts/architecture/architecture-commitgotchi-2026-06-07/architecture.md`이며, 충돌 시 architecture.md가 우선한다. `/api/game/**`는 최종 `/api/characters/**` 계약으로 이동하기 전의 compatibility layer다.

목표 캐릭터 모델은 architecture.md의 `characters` 공유 템플릿 + `user_character` 유저 인스턴스 분리다. 현재 BE-2 compatibility 구현은 정규화 전환 중이며, `game_states.state_json.characters`는 저장 시 항상 빈 배열 `[]`로 유지하고 API 응답에서만 `CharacterGameProjectionService`가 정규화 row를 overlay한다.

## Public Endpoints

모든 캐릭터 엔드포인트는 `Authorization: Bearer <access-token>`을 요구한다.

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/game/state` | 현재 게임 상태와 정규화 캐릭터 projection 조회 |
| `POST` | `/api/game/characters` | 캐릭터 생성, 최대 3개 제한, 새 캐릭터 active 지정 |
| `GET` | `/api/game/characters/{id}` | 자기 캐릭터 상세 조회 |
| `PATCH` | `/api/game/characters/{id}` | 자기 캐릭터의 `name`, `keyword`, `personality` 수정 |
| `PATCH` | `/api/game/characters/{id}/active` | 자기 캐릭터 active 지정, 기존 active 자동 해제 |
| `POST` | `/api/game/characters/{id}/retry-image` | 이미지 생성 재시도 또는 `READY` no-op |
| `DELETE` | `/api/game/characters/{id}` | 자기 캐릭터 삭제, active 삭제 시 `created_at DESC` 기준 최신 남은 캐릭터 재지정 |

## Request Shape

`CharacterCreateRequest`와 `CharacterUpdateRequest`는 같은 사용자 입력 필드를 받는다. `PATCH /api/game/characters/{id}`는 부분 patch가 아니라 사용자 편집 가능 필드 전체를 다시 보내는 full editable-field update이다.

```json
{
  "name": "Commit Buddy",
  "keyword": "green study slime",
  "personality": "Kind but precise"
}
```

검증 계약:

| Field | Validation | Notes |
| --- | --- | --- |
| `name` | required, nonblank, max 40 chars | 표시 이름 |
| `keyword` | required, nonblank, max 120 chars | 이미지/디자인 키워드 |
| `personality` | required, nonblank, max 500 chars | 성격 설명 |

클라이언트는 `active`, stats, `battlePower`, `imageStatus`, `spriteSheetUrl`, `spriteMeta`, `emotion`, `message`, `isEvolved`를 보낼 수 없다. 알 수 없는 필드는 Jackson `fail-on-unknown-properties` 정책에 따라 `VALIDATION_FAILED`로 처리한다.

## Response Shape

`GET /api/game/state`는 `{ "state": ... }`이다. `GET /api/game/characters/{id}`와 mutation 응답은 `{ "state": ..., "item": ... }`이다. 삭제 응답의 `item`은 삭제된 캐릭터의 마지막 projection snapshot이고, `state.characters`에서는 해당 캐릭터가 제거된다.

캐릭터 projection 필드:

```json
{
  "id": 1,
  "name": "Commit Buddy",
  "keyword": "green study slime",
  "personality": "Kind but precise",
  "stats": {
    "algo": 0,
    "cs": 0,
    "db": 0,
    "net": 0,
    "fw": 0
  },
  "battlePower": 0,
  "emotion": "joy",
  "isEvolved": false,
  "imageStatus": "FALLBACK",
  "spriteSheetUrl": "https://cdn.commitgotchi.local/sprites/fallback-default.png",
  "spriteMeta": {
    "columns": 3,
    "rows": 1,
    "frameMap": {
      "joy": [0, 0],
      "sad": [0, 1],
      "angry": [0, 2]
    }
  },
  "active": true,
  "message": "Ready to learn",
  "createdAt": "2026-06-18T00:00:00Z"
}
```

Stats API keys map to DB columns as follows:

| API key | DB column | Domain delta |
| --- | --- | --- |
| `algo` | `stat_algorithm` | algorithm delta |
| `cs` | `stat_cs` | CS delta |
| `db` | `stat_db` | DB delta |
| `net` | `stat_network` | network delta |
| `fw` | `stat_framework` | framework delta |

`battlePower` is the sum of the five stat columns. `isEvolved` changes from `false` to `true` once per character when `battlePower >= 1000`.

## Error Contract

| Condition | HTTP | Code |
| --- | --- | --- |
| Missing access token | `401` | `AUTH_ACCESS_TOKEN_MISSING` |
| Invalid/expired access token | `401` | `AUTH_ACCESS_TOKEN_INVALID` / `AUTH_ACCESS_TOKEN_EXPIRED` |
| Blank, too long, or unknown request fields | `400` | `VALIDATION_FAILED` |
| More than 3 characters | `400` | `CHARACTER_LIMIT_EXCEEDED` |
| Missing, malformed, or cross-owner character id | `404` | `NOT_FOUND` |

오류 응답과 로그에는 Authorization header, Bearer token, stack trace, raw DB constraint detail을 노출하지 않는다.

## Image Status Matrix

| Status | Sprite fields | API behavior |
| --- | --- | --- |
| `PENDING` | `spriteSheetUrl=null`, `spriteMeta=null` | 생성 초기 또는 재시도 전 내부 상태 |
| `READY` | nonblank `spriteSheetUrl`, valid `spriteMeta` | 실제 image client 성공 결과 |
| `FALLBACK` | nonblank fallback `spriteSheetUrl`, valid fallback `spriteMeta` | local/test disabled, FastAPI failure, timeout, invalid response |
| `FAILED` | `spriteSheetUrl=null`, `spriteMeta=null` | fallback 설정 자체가 불가능한 예외 상태 |

`POST /api/game/characters/{id}/retry-image`는 `READY`에서 외부 호출 없이 현재 projection을 반환한다. `PENDING`, `FAILED`, `FALLBACK`에서는 image client를 다시 실행하고 보통 `READY` 또는 `FALLBACK` projection을 반환한다. fallback URL 또는 metadata 설정 자체가 비어 있으면 `FAILED` projection을 반환할 수 있다.

`spriteMeta.frameMap`은 `baby`와 `mature` 행, `joy`, `sad`, `angry` 열 좌표를 가져야 한다. 좌표 배열 순서는 `[row, column]`이다.

## Projection And Persistence Boundary

저장 경계:

- 목표 architecture에서 `characters`는 공유 템플릿(`personality`, `design_keyword`, `sprite_sheet_url`, `sprite_meta`, `image_status`)이고, `user_character`는 유저 인스턴스(`name`, stats, `battle_power`, `emotion`, `status_message`, `is_evolved`, `is_active`)다.
- 현재 BE-2 compatibility layer는 이 분리를 완료하기 전까지 정규화 캐릭터 projection으로 `/api/game/**` 응답을 유지한다.
- `game_states.state_json.characters` is persisted as `[]`.
- `/api/game/state` overlays normalized characters at response time.
- `spriteSheetUrl` and `spriteMeta` appear in API responses but are not copied into `game_states.state_json.characters`.
- `dailyReport.characterId`, pending reports, starter quizzes, and board compatibility JSON may keep character references, but those JSON fields are not the character SoR.
- When a character is deleted, pending reports and unscored starter quizzes that reference it are repaired to the active replacement character when one exists, or to `null` when no character remains.

## Prototype Growth Bridge Guardrails

BE-2.7 기준 `POST /api/game/reports`, `POST /api/game/quizzes/{id}/submit`, `POST /api/game/daily-report/deliver`는 BE-3 정규화 리포트/퀴즈 구현 전까지의 compatibility bridge이다. 이 bridge는 `/api/game/**` 응답 shape를 유지하되, 캐릭터 성장의 SoR을 절대 `game_states.state_json.characters`로 되돌리지 않는다.

Bridge mutation 규칙:

- Report save는 compatibility JSON의 `reports`와 `dailyReport` slot을 갱신할 수 있지만, 캐릭터 감정과 상태 메시지는 locked active lookup 뒤 `CharacterCommandService.reactActive(...)` 경계를 통해서만 갱신한다.
- Quiz submit은 compatibility JSON의 `scored`, `deltaAmount`, `feedback`, `gradeFailed` marker를 유지하되, `scored=true` 저장은 normalized character growth write가 성공한 뒤에만 수행한다.
- Daily report delivery는 compatibility JSON의 `status`, `summary`, `deltas`, `quizComment`, `nextRecommendation`, `scoreApplied` marker를 유지하되, `scoreApplied=true` 저장은 `CharacterCommandService.applyScoreDeltas(...)` 성공 뒤에만 수행한다.
- `fail=true` 또는 fake AI failure path는 사용자에게 조회 가능한 실패 marker를 남길 수 있지만, `scored=true` 또는 `scoreApplied=true`를 저장하지 않는다.
- Stale `characterId`는 historical compatibility record로 남을 수 있지만, future growth target으로 쓰기 전에는 ownership-aware normalized row 존재 여부를 확인한다.
- Replacement active character가 있으면 pending future target만 repair한다. Historical `reports` entries는 일괄 재작성하지 않는다.
- Replacement active character가 없거나 referenced row가 없으면 growth no-op/failure marker를 저장하고, missing row에 대한 success marker는 저장하지 않는다.

## Internal AI Callback Contracts

Internal callbacks require:

```http
Authorization: Internal <SPRING_INTERNAL_API_SECRET>
```

`Authorization` 원문과 secret 값은 로그, 오류 응답, 테스트 snapshot에 남기지 않는다. Bearer JWT 보호 API는 계속 `Authorization: Bearer <access-token>`만 사용자 인증으로 인정한다.

### `POST /api/report`

FastAPI 리포트 결과 콜백은 strict JSON DTO로 받는다. 허용 필드는 `requestId`, `userId`, `characterId`, `targetDate`, `status`, `scoreDelta`, `statusMessage`, `dailyReport`, `nextRecommendation`, `recommendedQuizzes`, 선택적 `failedStages`뿐이다. `emotion` 또는 그 밖의 unknown field는 `400 VALIDATION_FAILED`다.

`scoreDelta`와 `recommendedQuizzes[].scoreAllocation`은 FastAPI internal stat keys만 허용한다:

```json
{ "db": 1, "algorithm": 2, "cs": 3, "network": 4, "framework": 5 }
```

Spring Boot는 이 값을 `CharacterCommandService.applyScoreDeltas(userId, characterId, db, algorithm, cs, network, framework, emotion, statusMessage)`의 DB-domain 순서로 명시 매핑한다. 리포트 콜백의 `statusMessage`는 사용할 수 있지만, 감정 결정과 저장은 Spring Boot 책임이다. FastAPI는 리포트 콜백에 `emotion`을 보내지 않는다.

현재 증분은 `report_results` 멱등 저장소가 없으므로 valid callback에 `200 OK {"duplicate":false}`를 반환하는 DTO/endpoint/validation 계약까지만 고정한다. TODO: BE-3 report schema 도입 시 `requestId` unique 멱등 마커와 character growth write를 같은 트랜잭션으로 묶고, 이미 처리된 `requestId`에는 `200 OK {"duplicate":true}`를 반환한다.

### `ReportRequestMessage`

Spring Boot가 FastAPI로 보낼 리포트 요청 메시지는 `characterMetadata.emotion`을 `JOY | ANGRY | SAD` uppercase로 포함한다. `characterMetadata.currentStats`와 `userMetadata.reportDirection.scoreDeltaHint`는 FastAPI internal stat keys `db`, `algorithm`, `cs`, `network`, `framework`만 사용한다.

### `POST /api/internal/quizzes/grade-result`

정식 BE-3 퀴즈 채점 webhook endpoint다. 현재 `/api/game/quizzes/{id}/submit`은 keyword 기반 동기 채점 compatibility bridge로 유지하지만, 두 경로 모두 캐릭터 성장은 `CharacterCommandService` 경계를 통해 반영한다.

허용 필드는 `submissionId`, `userId`, 선택적 `characterId`, `quizId`, `status`, `scoreAllocation`, `scoreDelta`, `feedback`, `emotion`, `statusMessage`, 선택적 `failedReason`이다. `scoreAllocation`과 `scoreDelta`는 FastAPI internal stat keys `db`, `algorithm`, `cs`, `network`, `framework`를 사용하고, `scoreDelta[field] <= scoreAllocation[field]`여야 한다. `status=UNGRADED`는 `scoreDelta` 합이 0이어야 한다.

Architecture §4.3에 맞춰 FastAPI는 `emotion`/`statusMessage`를 webhook에 포함한다. Spring Boot는 해당 값을 수락하고 성장 반영 트랜잭션에서 캐릭터 감정과 상태 메시지 갱신에 사용한다.

`characterId`가 있으면 Spring Boot는 해당 사용자의 소유 캐릭터에 성장 결과를 반영한다. `characterId`가 없으면 BE-3 handoff fallback으로 현재 active 캐릭터를 대상으로 삼고, 성장 write가 성공한 경우에만 해당 캐릭터 SSE 채널에 최신 projection 이벤트를 발행한다.

## BE-3 Handoff Guardrails

BE-3 학습 리포트와 성장 루프는 캐릭터 stats를 JSON mutation이나 raw SQL로 바꾸면 안 된다.

Required reuse points:

- Active lookup with lock: `LearningCharacterRepository.findActiveByUserIdForUpdate(...)`, or an equivalent ownership-aware locked lookup.
- Score application: `CharacterCommandService.applyScoreDelta(...)` for one stat or `CharacterCommandService.applyScoreDeltas(...)` for multiple stats.
- Domain stat mutation: `LearningCharacter.applyScoreDelta(...)`.
- Emotion/status update: `LearningCharacter.react(...)` through application service boundaries.

Recommended BE-3 write flow:

1. Resolve the active character with an ownership-aware locked lookup.
2. Persist the study report or quiz result with an idempotency key owned by the new BE-3 schema in the same transaction that applies growth.
3. Apply score deltas through `CharacterCommandService.applyScoreDelta(...)` or `CharacterCommandService.applyScoreDeltas(...)` before committing the idempotency marker.
4. Let `LearningCharacter.applyScoreDelta(...)` recalculate `battlePower` and trigger one-way evolution.
5. Let `LearningCharacter.react(...)` update emotion and status message inside the same application boundary.

`CharacterCommandService.applyScoreDeltas(...)` currently accepts deltas in DB-domain order:

```text
dbDelta, algorithmDelta, csDelta, networkDelta, frameworkDelta
```

The public Vue compatibility API keys remain `db`, `algo`, `cs`, `net`, `fw`. FastAPI internal report/quiz contract keys are `db`, `algorithm`, `cs`, `network`, `framework`. BE-3 must map every contract key to the service method deliberately instead of relying on positional guesses.

Growth rules:

- API stat keys are `algo`, `cs`, `db`, `net`, `fw`.
- DB columns are `stat_algorithm`, `stat_cs`, `stat_db`, `stat_network`, `stat_framework`.
- `battlePower` equals the sum of all five stats.
- `isEvolved` becomes true at battle power 1000 or higher and does not toggle back.
- BE-3 may add study log/report schemas, but must not restore `game_states.state_json.characters` as a character source of record.
- BE-3 must not mutate `game_states.state_json.characters` to apply growth. That array is a persisted compatibility placeholder and must remain `[]`.
- The BE-3 idempotency marker and character growth write must be atomic; a retry must not be swallowed as duplicate if the character growth write did not commit.

## Regression Commands

Run from `springboot/`:

```bash
./gradlew test --tests 'com.commitgotchi.character.*'
./gradlew test --tests 'com.commitgotchi.swagger.*'
./gradlew test
```

Regression tests must keep existing auth, Swagger profile gating, ownership, not-found hiding, image fallback/retry, active uniqueness, and projection boundary assertions strong.
