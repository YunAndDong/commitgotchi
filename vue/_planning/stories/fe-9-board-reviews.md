---
story: FE-9
status: done
current_implementation: done(mock)
scope: frontend-only (vue/ 하위)
related_fr: [FR-19, FR-20]
related_files:
  - src/views/BoardView.vue
  - src/views/BoardDetailView.vue
  - src/stores/game.js
---

# Story FE-9: 공유 게시판 + 리뷰

Status: done

## Story

As a 사용자,
I want 내 캐릭터 공유 게시글을 작성·조회·수정·삭제하고 게시글에 리뷰를 남기고,
so that 다른 사용자와 캐릭터를 공유하고 피드백을 주고받는다.

## 목적과 범위

- 게시판 #8: 카드 상하 2분할(위=캐릭터/아래=설명+최신 리뷰), 1행 3열 + 페이지네이션.
- 상세 #9: 좌 캐릭터 상세 / 우 리뷰(평점 분포 + 페이지네이션).
- 게시글 CRUD(FR-19), 리뷰 CRUD(FR-20) — 본인 글/리뷰만 수정·삭제.

## 현재 구현 상태

- mock 기반 구현 존재.

## Acceptance Criteria

### AC1 — 게시글 목록·상세 조회
- **Given** 사용자가 게시판 `/board`에 진입했을 때,
- **When** `board()`가 게시글 목록을 반환하면,
- **Then** 카드가 상하 2분할(위=캐릭터 스프라이트/감정/진화, 아래=설명 + 최신 리뷰)로 1행 3열 그리드 + 페이지네이션으로 표시된다(화면 #8).
- **And** 카드를 선택하면 상세 `/board/:id`로 이동해 좌측 캐릭터 상세 / 우측 리뷰 영역(`boardPost(id)`)이 표시된다(화면 #9).

### AC2 — 게시글 작성·수정·삭제(본인만)
- **Given** 로그인 사용자가,
- **When** 캐릭터 공유 게시글을 작성·수정·삭제하면,
- **Then** 게시글 CRUD가 동작하고, **본인 소유 글만** 수정·삭제 컨트롤이 노출/허용된다(타인 글은 조회만, FR-19).
- **Note(현 구현 갭):** 현재 mock 스토어에는 게시글 작성/수정/삭제 액션이 없고 읽기(`board`/`boardPost`)만 존재 — 본 AC 충족을 위해 `vue/` 안에서 게시글 CRUD 액션·UI를 보강해야 한다.

### AC3 — 리뷰 작성·수정·삭제(본인만)
- **Given** 게시글 상세를 보는 사용자가,
- **When** 평점(별점)과 텍스트로 리뷰를 남기면,
- **Then** `addReview(postId, stars, text)`로 리뷰가 목록 맨 위에 추가되고 게시글 평균 평점(`rating`)이 재계산된다.
- **And** **본인 리뷰만** 수정·삭제할 수 있다(FR-20).
- **Note(현 구현 갭):** 현재 mock에는 리뷰 추가만 있고 수정/삭제 액션이 없음 — 보강 필요.

### AC4 — 평점 분포 표시
- **Given** 리뷰가 있는 게시글 상세에서,
- **When** 리뷰 영역이 렌더되면,
- **Then** 평균 평점과 별점 분포(1~5점 분포)가 표시되고 리뷰 리스트는 페이지네이션된다. 리뷰가 0개면 빈 상태 카피를 보여준다.

## 검증

- 목록(3열+페이저)→상세(좌 캐릭터/우 리뷰) 네비게이션 확인.
- 리뷰 추가 시 평균 평점 재계산·분포 갱신 확인.
- 본인 글/리뷰에만 수정·삭제 노출되는 소유권 규칙 확인(보강 후).
- `npm run build` 통과. 게시글/리뷰 CRUD 보강은 모두 `vue/` 하위로 한정.

### Review Findings

- [x] [Review][Patch] 게시글 작성·수정·삭제와 본인 소유권 검사 구현 (`src/stores/game.js`, `src/views/BoardView.vue`)
- [x] [Review][Patch] 리뷰 수정·삭제와 본인 소유권 검사 구현 (`src/stores/game.js`, `src/views/BoardDetailView.vue`)
- [x] [Review][Patch] 게시글·리뷰 변경 시 페이지 번호 범위 보정 (`src/views/BoardView.vue`, `src/views/BoardDetailView.vue`)
