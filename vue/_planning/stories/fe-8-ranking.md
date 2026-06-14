---
story: FE-8
status: done
current_implementation: done(mock)
scope: frontend-only (vue/ 하위)
related_fr: [FR-18]
related_files:
  - src/views/RankingView.vue
  - src/stores/game.js
---

# Story FE-8: 랭킹(포디움 TOP3 + 내 순위)

Status: done

## Story

As a 사용자,
I want 육아점수 기준 랭킹과 내 캐릭터 순위를 보고,
so that 다른 사용자 대비 내 성장 위치를 확인한다.

## 목적과 범위

- 포디움 TOP3 + 리스트, 내 캐릭터 하이라이트. (EXPERIENCE #7)
- 정렬 기준은 **육아점수**(라벨 통일, "전투력/육아점수" 혼용 금지).

## 현재 구현 상태

- mock 기반 구현 존재.

## Acceptance Criteria

### AC1 — TOP3 포디움 + 리스트
- **Given** 사용자가 랭킹 `/ranking`에 진입했을 때,
- **When** `ranking()`이 행 목록을 반환하면,
- **Then** 상위 3개가 포디움(1·2·3위)으로, 나머지는 리스트로 표시되고 각 행에 순위·이름·소유자·점수·진화 여부·감정 칩이 보인다(EXPERIENCE #7).

### AC2 — 내 순위 하이라이트
- **Given** 활성 캐릭터를 보유한 사용자가,
- **When** 랭킹이 렌더되면,
- **Then** 내 캐릭터 행(`me:true`, 소유자 "나")이 시각적으로 하이라이트되고, 포디움 밖 순위여도 내 위치를 확인할 수 있다.
- **And** 활성 캐릭터가 없으면 내 행 없이 타 사용자 랭킹만 표시된다.

### AC3 — 육아점수 기준 정렬
- **Given** 랭킹 데이터가 구성될 때,
- **When** 정렬이 적용되면,
- **Then** 모든 행이 **육아점수**(내 캐릭터는 `nurtureScore`) 내림차순으로 정렬되고 순위가 1부터 부여된다.
- **And** 화면 라벨은 "육아점수"로 통일한다("전투력/육아점수" 혼용 금지 — 값은 일치하더라도 표기 단일화).

## 검증

- TOP3 포디움 + 리스트 렌더, 내 행 하이라이트 확인.
- 육아점수 내림차순 정렬·순위 부여 확인, 라벨 단일성("육아점수") 확인.
- 활성 캐릭터 유무 두 경로 확인, `npm run build` 통과. 변경은 `vue/` 하위로 한정.

### Review Findings

- [x] [Review][Patch] 포디움·목록 행에 감정 칩과 진화 여부 표시 (`src/views/RankingView.vue`)
