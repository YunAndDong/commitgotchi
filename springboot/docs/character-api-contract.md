# Character API Contract

## Scope

이 문서는 Spring Boot BE-2 캐릭터 도메인의 공개 계약을 고정한다. 실제 공개 경로는 Vue 호환 facade인 `/api/game/**`이며, 별도 `/api/characters` 경로는 정식 계약이 아니다.

캐릭터의 System of Record는 PostgreSQL `characters` 테이블이다. `game_states.state_json.characters`는 저장 시 항상 빈 배열 `[]`로 유지하고, API 응답에서만 `CharacterGameProjectionService`가 정규화 row를 overlay한다.

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
    "rows": 2,
    "frameMap": {
      "baby": {
        "joy": [0, 0],
        "sad": [0, 1],
        "angry": [0, 2]
      },
      "mature": {
        "joy": [1, 0],
        "sad": [1, 1],
        "angry": [1, 2]
      }
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

- `characters` table stores `name`, `design_keyword`, `personality`, stats, `battle_power`, `emotion`, `status_message`, `is_evolved`, `image_status`, `sprite_sheet_url`, `sprite_meta`, `is_active`.
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

The public API keys remain `db`, `algo`, `cs`, `net`, `fw`. BE-3 must map API/report keys to the service method deliberately instead of relying on positional guesses.

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
