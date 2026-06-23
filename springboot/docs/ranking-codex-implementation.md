# Ranking / Codex Implementation Notes

## Scope

이 문서는 Vue에서 아직 로컬 계산/하드코딩에 의존하는 두 영역을 Spring Boot API로 제공하기 위한 백엔드 구현 내용을 정리한다.

1. 랭킹: `/ranking`
2. 도감/잠금 캐릭터 목록: `/codex`

현재 `/api/game/**`는 최종 정규화 API로 이동하기 전의 compatibility facade다. 따라서 이 문서의 API도 같은 namespace 아래에 두고, BE-3 이후 정규화 테이블이 준비되면 내부 구현만 교체한다.

## Current Gap

### Ranking

Vue `ranking()`은 하드코딩된 다른 사용자 목록과 현재 active character를 합쳐 정렬한다. Spring Boot에는 전체 랭킹을 반환하는 endpoint가 없다.

### Codex

Vue `codex()`는 사용자의 `state.characters`를 보유 항목으로 변환한 뒤 `lk1~lk4` 잠금 항목을 붙인다. Spring Boot에는 도감 entry, 잠금 상태, 도감 전용 리뷰 요약을 반환하는 endpoint가 없다.

## Endpoints

모든 endpoint는 `Authorization: Bearer <access-token>`을 요구한다.

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/game/ranking` | 전체 캐릭터 육아점수 랭킹 조회 |
| `GET` | `/api/game/codex` | 현재 사용자의 도감 보유/잠금 항목과 리뷰 요약 조회 |

`GameController`에 read-only handler를 추가한다.

```java
@GetMapping("/ranking")
public GameRankingResponse ranking(@AuthenticationPrincipal AuthPrincipal principal) {
    return gameService.ranking(principal.userId());
}

@GetMapping("/codex")
public GameCodexResponse codex(@AuthenticationPrincipal AuthPrincipal principal) {
    return gameService.codex(principal.userId());
}
```

## Response Contracts

### `GET /api/game/ranking`

```json
{
  "rows": [
    {
      "rank": 1,
      "characterId": 12,
      "name": "새싹이",
      "owner": "나",
      "score": 1030,
      "isEvolved": true,
      "emotion": "joy",
      "spriteSheetUrl": "/character-assets/default_image1.png",
      "spriteMeta": {},
      "me": true
    }
  ]
}
```

Rules:

- `score`는 `LearningCharacter.battlePower`를 사용한다.
- 정렬은 `battlePower DESC, updatedAt ASC, id ASC`로 고정한다.
- `rank`는 1부터 시작한다. 동점 공동 순위는 초기 구현에서 지원하지 않고 표시 순위만 부여한다.
- 현재 사용자의 캐릭터는 `me=true`, `owner="나"`로 반환한다.
- 다른 사용자는 개인정보 노출을 피하기 위해 `owner="user-{id}"` 또는 향후 닉네임 필드를 사용한다. 이메일 원문은 반환하지 않는다.
- `spriteSheetUrl`, `spriteMeta`, `emotion`, `isEvolved`는 기존 `CharacterGameProjectionService`와 같은 표기 규칙을 따른다.

### `GET /api/game/codex`

```json
{
  "entries": [
    {
      "id": "owned-12",
      "characterId": 12,
      "name": "새싹이",
      "personality": "칭찬은 많이...",
      "emotion": "joy",
      "isEvolved": false,
      "spriteSheetUrl": "/character-assets/default_image1.png",
      "spriteMeta": {},
      "owned": true,
      "rating": 4.8,
      "reviewCount": 3,
      "reviews": []
    },
    {
      "id": "locked-joy-baby",
      "name": "???",
      "emotion": "joy",
      "isEvolved": false,
      "owned": false,
      "lockReason": "아직 발견하지 못한 커밋고치"
    }
  ]
}
```

Rules:

- owned entry는 `LearningCharacterRepository.findAllByUserIdOrderByCreatedAtDesc(userId)`에서 만든다.
- locked entry는 정규화된 공유 캐릭터 catalog가 생기기 전까지 서버 상수로 둔다.
- locked entry id는 안정적인 문자열이어야 한다. 예: `locked-joy-baby`, `locked-sad-evolved`.
- owned entry는 기존 캐릭터 projection 필드에 `owned=true`, `rating`, `reviewCount`, `reviews`를 붙인다.
- reviews는 현재 compatibility data인 `game_states.state_json.boardPosts`에서 해당 `characterId` 또는 `name`으로 매칭한다.
- BE-3에서 `reviews` 테이블이 생기면 이 매칭 로직은 repository query로 교체한다.

## Service Design

### DTO package

`com.commitgotchi.game.api.dto`에 response DTO를 둔다.

- `GameRankingResponse(List<GameRankingRow> rows)`
- `GameRankingRow(...)`
- `GameCodexResponse(List<GameCodexEntry> entries)`
- `GameCodexEntry(...)`
- 필요 시 `GameCodexReview(...)`

현재 `GameMutationResponse`는 mutation + full state 교체용이므로 신규 read endpoint에는 별도 DTO를 사용한다.

### Repository additions

`LearningCharacterRepository`에 랭킹 조회용 method를 추가한다.

```java
@Query("""
        SELECT character
        FROM LearningCharacter character
        JOIN FETCH character.user user
        ORDER BY character.battlePower DESC, character.updatedAt ASC, character.id ASC
        """)
List<LearningCharacter> findRankingRows();
```

초기 데이터가 작다는 전제에서는 전체 조회 후 Java에서 상위 N개를 자를 수 있다. 사용자가 늘어나면 `Pageable`을 받는 endpoint로 확장한다.

### Projection reuse

Ranking과 Codex는 기존 `CharacterGameProjectionService.project(character)`를 재사용한다.

주의:

- projection의 `id`는 Vue game state에서 쓰는 캐릭터 id다.
- ranking 응답은 `id` 대신 `characterId`를 명시해 화면 row identity와 구분한다.
- stat key mapping은 기존 `algo`, `cs`, `db`, `net`, `fw`를 유지한다.

### Review summary bridge

현재 게시글/리뷰는 `game_states.state_json.boardPosts` compatibility JSON에 들어 있다. 도감 endpoint는 첫 구현에서 다음 순서로 리뷰를 모은다.

1. `loadOrCreate(userId)`로 현재 사용자의 game state를 읽는다.
2. `boardPosts` 배열을 순회한다.
3. `post.characterId == character.id` 또는 `post.name == character.name`인 post의 `reviews`를 합친다.
4. `rating`, `reviewCount`, `reviews`를 계산한다.

이 방식은 현재 Vue 동작을 서버로 옮기는 compatibility bridge다. 전역 공개 리뷰가 필요하면 BE-3에서 `reviews` 정규화 테이블을 추가해야 한다.

## Validation And Security

- Ranking/codex는 read-only endpoint라 `@Transactional(readOnly = true)`를 사용한다.
- 응답에 사용자의 이메일, password hash, refresh token, Authorization 원문을 포함하지 않는다.
- 다른 사용자 캐릭터를 랭킹에 노출할 때는 캐릭터 표시 정보와 익명 owner handle만 반환한다.
- Codex는 현재 사용자 소유 캐릭터만 owned로 반환한다.
- locked entry에는 실제 미소유 캐릭터의 내부 id를 노출하지 않는다.

## Tests

권장 테스트:

- `GET /api/game/ranking`은 battlePower 내림차순으로 row를 반환한다.
- 현재 사용자의 캐릭터에는 `me=true`, `owner="나"`가 붙는다.
- 다른 사용자의 이메일은 ranking 응답에 포함되지 않는다.
- `GET /api/game/codex`는 보유 캐릭터와 locked entry를 함께 반환한다.
- codex owned entry는 기존 board/review compatibility JSON에서 `reviewCount`와 `rating`을 계산한다.
- 인증 없이 호출하면 `401`을 반환한다.

## Vue Handoff

Vue는 아래 client wrapper를 기대한다.

```js
game.ranking()
game.codex()
```

응답 shape가 바뀌면 `vue/RANKING_CODEX_INTEGRATION.md`도 같이 갱신한다.
