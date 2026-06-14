---
story: FE-4
status: draft
current_implementation: done(mock)
scope: frontend-only (vue/ 하위)
related_fr: [FR-3, FR-16]
related_files:
  - src/views/CharacterCreateView.vue
  - src/views/CreationCompleteView.vue
  - src/components/CgConfetti.vue
  - src/stores/game.js
---

# Story FE-4: 캐릭터 생성 흐름(폼 + 완료 컨페티)

Status: draft

## Story

As a 사용자,
I want 이름·디자인 키워드·성격을 입력해 캐릭터를 만들고 완료 연출을 보고,
so that 최대 3개까지 내 캐릭터를 가질 수 있다.

## 목적과 범위

- 생성 입력 폼(보강 표면) → 완료 화면 #11(컨페티·등장 pop) 연결. (Flow 2, UJ-2)
- 이미지 생성 실패 시에도 Fallback으로 생성 흐름 완료(FR-16, 흐름 C: PENDING→READY).
- 최대 3개 보유 제한 반영.

## 현재 구현 상태

- mock 기반 구현 존재.

## Acceptance Criteria

### AC1 — 생성 폼 입력·검증
- **Given** 사용자가 `/create` 생성 폼에 진입했을 때,
- **When** 이름·디자인 키워드·성격을 입력하고 제출하면,
- **Then** 필수값(이름 등)이 비어 있으면 제출이 차단되고, 유효하면 `createCharacter({name, keyword, personality})`가 호출된다.
- **And** 생성된 캐릭터는 초기 스탯 0(5스탯), `emotion='joy'`, `imageStatus='PENDING'`, 그리고 **자동으로 active**로 지정된다(기존 캐릭터는 비활성화 — 활성 단일성 유지).

### AC2 — 완료 화면 연출
- **Given** 캐릭터 생성이 성공한 직후,
- **When** 완료 화면 `/complete/:id`로 이동하면,
- **Then** `CgConfetti` 컨페티와 등장 pop 연출이 재생되고 새 캐릭터 정보가 표시된다(Flow 2, 화면 #11).
- **And** reduced-motion 사용자에게는 연출이 축소되어도 완료 사실과 캐릭터 정보는 전달된다(FE-1 AC3 연계).

### AC3 — 이미지 PENDING→READY / Fallback (흐름 C)
- **Given** 새 캐릭터의 `imageStatus`가 `PENDING`인 상태에서,
- **When** mock 비동기 이미지 생성이 완료되면(약 2.2s 후 `READY`),
- **Then** 플레이스홀더 스프라이트가 실제 스프라이트로 전환된다.
- **And** 이미지 생성이 지연/실패하더라도 생성 흐름 자체는 완료되며, 실패 시 Fallback 표현으로 흐름이 끊기지 않는다(FR-16, FE-10 상태 패턴 적용).

### AC4 — 3개 초과 차단
- **Given** 이미 `MAX_CHARACTERS(3)`개의 캐릭터를 보유한 사용자가,
- **When** 추가 생성을 시도하면,
- **Then** `createCharacter`가 에러("캐릭터는 최대 3개까지 만들 수 있어요.")를 던지고 생성되지 않으며, 폼은 이 한도 초과를 사용자에게 안내한다(가능하면 한도 도달 시 진입 자체를 비활성/안내).

## 검증

- 정상 생성 → 완료 화면 연출 → PENDING→READY 전환 경로 확인.
- 보유 3개 상태에서 추가 생성 차단·안내 확인.
- 신규 생성 시 활성 캐릭터가 항상 1개로 유지되는지 확인, `npm run build` 통과.
- 변경은 모두 `vue/` 하위 파일로 한정.
