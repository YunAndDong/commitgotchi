---
title: '도감 카탈로그 캐릭터와 캐러셀 lazy image loading'
type: 'feature'
created: '2026-06-25'
status: 'done'
baseline_commit: 'b24bc62f73580ed31eaa3828218f1a00afb76d4f'
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 현재 도감은 `/api/game/state`의 `state.characters`와 `user_character` 기반 보유 캐릭터 정보를 보여주며, 이름/점수/수집/리뷰 UI도 그 의미에 묶여 있다. 이제 도감은 사용자 보유 상태가 아니라 `characters` 원본 카탈로그 중 `id >= 4`인 캐릭터를 보여줘야 한다.

**Approach:** `/api/game/state`와 기존 내 캐릭터 기능은 그대로 두고, 도감 전용 `/api/codex` API와 프론트 도감 store를 새로 분리한다. 목록 API는 metadata만 반환하고, 캐러셀 현재 index 주변 id만 batch presign API로 요청해 S3 이미지를 lazy load한다.

## Boundaries & Constraints

**Always:** `characters.id >= 4`, `image_status IN ('READY','FALLBACK')`, `id ASC`를 도감 목록 기준으로 유지한다. 목록 응답에는 `spriteSheetUrl`을 넣지 않는다. URL batch API는 한 번에 최대 7개 id만 받고, 중복 id를 제거하며, `s3://` 값은 `CharacterImagePresignService.toDisplayUrl()`로 변환한다. 도감 화면에는 이름, 점수, 수집 완료, user review 정보를 표시하지 않고 `personality`, `designKeyword` 중심으로 표시한다.

**Ask First:** catalog rows에 name을 새로 추가하거나 user ownership, board post, review semantics를 다시 연결해야 하는 요구가 나오면 작업을 멈추고 확인한다. batch 제한을 7개보다 크게 늘리거나 initial page size를 크게 키우는 것도 확인한다.

**Never:** `/api/game/state` 응답 구조, `game.js`의 보유 캐릭터 projection, 선택/상세/리포트/퀴즈 플로우를 변경하지 않는다. S3 presigned URL을 metadata 목록에 포함하지 않는다. 캐러셀 진입 시 모든 캐릭터 이미지 URL을 한 번에 요청하지 않는다.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 목록 조회 | `GET /api/codex/characters?limit=12` | `items`는 `id >= 4`, READY/FALLBACK, id 오름차순 metadata이며 `nextCursor`, `hasMore`를 포함한다. | 잘못된 limit은 서버 clamp로 처리한다. |
| 다음 페이지 | `afterId=15&limit=12` | id 15보다 큰 다음 row만 반환한다. | 결과가 없으면 빈 items, `hasMore=false`. |
| 이미지 batch | `POST /api/codex/characters/sprite-urls` body `{ "ids": [4,5,6] }` | 각 id에 대해 `spriteSheetUrl`, `spriteMeta`, `imageStatus`, 가능하면 `expiresAt`를 반환한다. | 7개 초과는 400으로 거부한다. 존재하지 않거나 도감 대상이 아닌 id는 결과에서 제외한다. |
| 캐러셀 이동 | 현재 index 기준 좌우 2개 window | cache에 없는 주변 id만 batch 요청하고, 이미 loading 중인 id는 중복 요청하지 않는다. | API 실패 시 화면은 placeholder를 유지하고 깨지지 않는다. |

</frozen-after-approval>

## Code Map

- `springboot/src/main/java/com/commitgotchi/character/domain/LearningCharacterMapper.java` -- MyBatis character/catalog SQL mapper; catalog-only query 추가 지점.
- `springboot/src/main/java/com/commitgotchi/character/domain/LearningCharacterRepository.java` -- mapper 접근 facade; catalog read methods 추가.
- `springboot/src/main/java/com/commitgotchi/character/image/CharacterImagePresignService.java` -- S3 stored URL을 display URL로 변환하는 기존 helper.
- `springboot/src/main/java/com/commitgotchi/game/api/GameController.java` / `GameService.java` -- 기존 `/api/game/state`; 변경 금지 reference.
- `vue/src/api/client.js` -- authenticated API namespaces; `codex` namespace 추가.
- `vue/src/stores/game.js` -- 기존 user-character store; 도감에서 더 이상 import하지 않되 기존 기능 유지.
- `vue/src/views/CodexView.vue` -- 도감 UI; 새 codex store 기반 carousel 및 metadata 표시로 교체.

## Tasks & Acceptance

**Execution:**
- [x] `springboot/src/main/java/com/commitgotchi/character/domain/CodexCharacterProjection.java` -- catalog metadata/url projection model 추가.
- [x] `springboot/src/main/java/com/commitgotchi/character/domain/LearningCharacterMapper.java` and `LearningCharacterRepository.java` -- `characters` table only list/byIds queries 추가.
- [x] `springboot/src/main/java/com/commitgotchi/codex/api/*` and `springboot/src/main/java/com/commitgotchi/codex/application/*` -- `/api/codex/characters` and `/api/codex/characters/sprite-urls` 구현, validation 및 presign 처리.
- [x] `vue/src/api/client.js` -- `codex.listCharacters({ afterId, limit })`, `codex.spriteUrls(ids)` 추가.
- [x] `vue/src/stores/codex.js` -- 분리된 metadata pagination state, image cache, loading-id dedupe, visible window loading 구현.
- [x] `vue/src/views/CodexView.vue` -- `game.codex()`, board/review imports 제거; `personality`/`designKeyword` UI와 placeholder sprite 상태 구현.
- [x] Backend/API and frontend build checks -- field names and lazy-load request shape 검증.

**Acceptance Criteria:**
- Given 도감 화면에 처음 진입했을 때, when metadata가 로드되면, then 첫 page의 catalog metadata만 렌더링되고 sprite URL은 현재 index 주변 최대 5개 id만 요청된다.
- Given 사용자가 캐러셀을 넘길 때, when 새 index 주변 이미지가 cache에 없으면, then 최대 5개 window 중 미캐시/미로딩 id만 batch 요청된다.
- Given `items.length - 4` 이상으로 접근할 때, when 다음 페이지가 있으면, then 다음 metadata page를 prefetch하고 기존 item 뒤에 id ASC로 append한다.
- Given 기존 선택/상세/리포트/퀴즈 화면이 `/api/game/state`를 사용할 때, when 도감 변경 후에도, then `state.characters` 구조와 user-character 기반 기능은 유지된다.

## Spec Change Log

## Design Notes

도감 metadata와 image URL을 분리해 presigned URL 수명과 화면 rendering을 분리한다. 프론트 cache key는 catalog `id`이며, `spriteMeta`는 metadata 응답에도 들어오지만 URL batch 응답이 최신 값을 덮어쓸 수 있다.

## Verification

**Commands:**
- `./gradlew testClasses` from `springboot` -- expected: Java compile/test classes generation succeeds.
- `npm run build` from `vue` -- expected: Vite production build succeeds.

## Suggested Review Order

**API Boundary**

- Start at the new codex entry point and response split.
  [`CodexController.java:18`](../../springboot/src/main/java/com/commitgotchi/codex/api/CodexController.java#L18)

- Check pagination, batch limit, presign, and URL omission policy.
  [`CodexCharacterService.java:49`](../../springboot/src/main/java/com/commitgotchi/codex/application/CodexCharacterService.java#L49)

- Verify request validation caps sprite URL batches at seven.
  [`CodexSpriteUrlsRequest.java:8`](../../springboot/src/main/java/com/commitgotchi/codex/api/dto/CodexSpriteUrlsRequest.java#L8)

**Catalog Query**

- Confirm catalog reads use characters only, id floor, status filter, and ASC order.
  [`LearningCharacterMapper.java:188`](../../springboot/src/main/java/com/commitgotchi/character/domain/LearningCharacterMapper.java#L188)

**Frontend Data Flow**

- Review the new authenticated codex namespace.
  [`client.js:290`](../../vue/src/api/client.js#L290)

- Check metadata pagination and image cache separation.
  [`codex.js:85`](../../vue/src/stores/codex.js#L85)

- Confirm carousel image requests use idx plus/minus two only.
  [`codex.js:125`](../../vue/src/stores/codex.js#L125)

**Codex UI**

- Verify the view no longer imports user-character game codex/reviews.
  [`CodexView.vue:6`](../../vue/src/views/CodexView.vue#L6)

- Check placeholder sprites and lazy URL binding.
  [`CodexView.vue:171`](../../vue/src/views/CodexView.vue#L171)

- Confirm only personality/design keyword are displayed as catalog content.
  [`CodexView.vue:192`](../../vue/src/views/CodexView.vue#L192)
