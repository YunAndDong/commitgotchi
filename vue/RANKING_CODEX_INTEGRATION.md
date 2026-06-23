# Ranking / Codex Spring Boot Integration Notes

## 범위

이 문서는 현재 Vue 화면 중 Spring Boot와 완전히 연결되지 않은 두 영역을 실제 API 기반으로 전환하기 위한 프론트엔드 구현 내용을 정리한다.

1. `/ranking` 랭킹 화면
2. `/codex` 도감/잠금 캐릭터 목록

단순 UI 조작 버튼(캐러셀 이동, 페이지네이션, 별점 선택, 미리보기 감정 변경)은 백엔드 연결 대상이 아니다.

## 현재 상태

### 랭킹

- 화면: `src/views/RankingView.vue`
- 현재 데이터 경로: `ranking()` in `src/stores/game.js`
- 문제:
  - `ranking()`이 `others` 하드코딩 목록과 현재 활성 캐릭터를 합쳐 반환한다.
  - 전체 사용자 기준 랭킹 API 호출이 없다.

### 도감

- 화면: `src/views/CodexView.vue`
- 현재 데이터 경로:
  - `codex()` in `src/stores/game.js`
  - `board()` / `deleteReview()` in `src/stores/game.js`
- 문제:
  - 보유 캐릭터는 `state.characters`에서 계산하지만, 잠긴 항목 `lk1~lk4`는 하드코딩이다.
  - 도감 컬렉션/잠금 상태가 서버 계약으로 고정되어 있지 않다.
  - 리뷰 삭제는 이미 Spring Boot mutation으로 이어질 수 있지만, 도감 목록 자체는 서버 응답이 아니다.

## 목표 API

Spring Boot 쪽 구현 문서와 맞춘다.

```http
GET /api/game/ranking
GET /api/game/codex
```

`api/client.js`에 다음 래퍼를 추가한다.

```js
ranking: () => authed('GET', '/api/game/ranking'),
codex: () => authed('GET', '/api/game/codex'),
```

응답 형태는 화면에서 그대로 쓰기 쉽게 유지한다.

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

## Vue 구현 작업

### 1. API client

`src/api/client.js`의 `game` export에 아래 항목을 추가한다.

- `ranking()`
- `codex()`

기존 `authed()` 흐름을 사용하므로 access token 부착, 401 refresh retry, cookie 기반 refresh rotation은 추가 구현하지 않는다.

### 2. Store state

`src/stores/game.js`에 서버 조회 결과를 보관할 state를 추가한다.

- `rankingRows: []`
- `codexEntries: []`
- `rankingStatus: 'idle' | 'loading' | 'ready' | 'error'`
- `codexStatus: 'idle' | 'loading' | 'ready' | 'error'`
- `rankingError`, `codexError`

기존 `ranking()` / `codex()` 함수는 화면 import를 유지하기 위해 이름을 보존한다.

권장 형태:

```js
export async function loadRanking() {
  state.rankingStatus = 'loading'
  const response = await gameApi.ranking()
  state.rankingRows = normalizeRankingRows(response.rows)
  state.rankingStatus = 'ready'
}

export function ranking() {
  return state.rankingRows
}
```

`codex()`도 같은 방식으로 서버 `entries`를 반환한다.

### 3. 초기 로딩 시점

둘 다 전역 `loadGameState()`에 묶기보다는 화면 진입 시 로딩한다.

- `RankingView.vue`: `onMounted(loadRanking)`
- `CodexView.vue`: `onMounted(loadCodex)`

이유:

- 대시보드 진입마다 랭킹/도감까지 불필요하게 조회하지 않는다.
- 랭킹/도감 API 실패가 핵심 게임 상태 조회를 막지 않는다.

### 4. 화면 상태

`RankingView.vue`:

- loading: 작은 `CgState` 또는 `cg-card muted`로 표시
- error: 재시도 버튼 `loadRanking()`
- empty: "아직 랭킹에 표시할 커밋고치가 없어요."

`CodexView.vue`:

- loading: 도감 카드 위치에 skeleton 또는 `CgState`
- error: 재시도 버튼 `loadCodex()`
- empty: 보유 캐릭터가 없으면 기존 생성 유도 흐름 유지

### 5. mutation 후 갱신

도감 리뷰는 현재 `deleteReview()`, `addReview()`, `updateReview()`가 `/api/game/board-posts/**` mutation을 호출한다.

도감 화면에서 리뷰 mutation 후에는 둘 중 하나로 동기화한다.

- 간단한 방식: mutation 성공 후 `await loadCodex()`
- 낙관적 방식: 기존 `state.boardPosts` 반영 후 `codexEntries`의 해당 리뷰만 갱신

초기 구현은 간단한 방식이 안전하다.

### 6. 기존 mock fallback 정책

`mockMode`는 데모 로그인/오프라인 모드에서만 유지한다.

- 인증 사용자가 Spring Boot로 bootstrap된 상태: `ranking()` / `codex()`는 서버 응답만 사용
- demo mode: 기존 하드코딩 랭킹/잠금 도감 fallback 사용 가능

이를 위해 기존 `ranking()`과 `codex()`의 하드코딩 본문은 `localRanking()` / `localCodex()`로 분리한다.

## 수용 기준

- `/ranking` 진입 시 `GET /api/game/ranking`이 1회 호출된다.
- `/ranking` 화면의 TOP3와 목록은 서버 `rows` 순서를 따른다.
- `/codex` 진입 시 `GET /api/game/codex`가 1회 호출된다.
- `/codex`의 보유/잠금 항목 수는 서버 `entries` 기준으로 표시된다.
- 도감에서 내 리뷰 삭제 후 서버 상태와 화면 리뷰 수가 다시 일치한다.
- demo login에서는 Spring Boot 없이도 기존 시연 화면이 동작한다.
