---
title: '웹페이지 뷰포트 활성 커밋고치'
type: 'feature'
created: '2026-06-14'
status: 'done'
baseline_commit: 'f4ec9b7bec13e6c3137f41c7ed9bee16a615f257'
context:
  - '{project-root}/vue/EXTENSION.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 현재 활성 커밋고치는 Vue SPA 내부의 전역 마스코트로만 이동하며, 사용자가 실제로 탐색하는 일반 웹페이지에는 표시되지 않는다.

**Approach:** 팝업의 로그인·캐릭터 선택 흐름은 유지하되 활성 커밋고치의 최소 표시 정보를 `chrome.storage.local`에 동기화하고, Vue 번들에 의존하지 않는 Manifest V3 content script가 각 일반 웹페이지의 Shadow DOM 안에서 뷰포트 하단 이동 캐릭터를 렌더링한다.

## Boundaries & Constraints

**Always:** 모든 변경 파일은 `vue/` 하위에 둔다. 일반 웹페이지 UI는 Shadow DOM, `position: fixed`, `pointer-events: none`을 사용한다. 활성 선택 변경·캐릭터 생성/삭제·로그인/로그아웃 상태를 저장소에 반영한다. 상세·도감 등 의미 있는 정적 `CgSprite` 표시는 유지한다. 제한 URL과 Chrome API가 없는 웹 실행 환경에서 오류 없이 동작한다. `prefers-reduced-motion: reduce`에서는 횡이동을 멈춘다.

**Ask First:** 실제 이미지 API/에셋 도입, 캐릭터 데이터의 백엔드 영속화, `vue/` 밖 변경이 필요해지는 경우.

**Never:** 방문 페이지에서 Vue 앱 번들을 로드하거나 방문 페이지 DOM/CSS를 신뢰한다. `chrome://`, 확장 페이지 등 금지된 페이지에 강제 주입한다. 페이지 클릭·스크롤을 가로막는다. 기존 로그인·라우팅·캐릭터 선택 흐름을 제거한다.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 활성 커밋고치 선택 | 로그인 상태에서 유효한 캐릭터 활성화 | 최소 표시 정보가 `chrome.storage.local`에 저장되고 열린 일반 탭 표시가 갱신됨 | Chrome API가 없으면 SPA 동작만 유지 |
| 로그아웃 또는 활성 없음 | 로그아웃, 마지막 캐릭터 삭제 | 저장 정보가 제거되고 열린 탭의 캐릭터 UI가 제거됨 | 제한 URL 전송 실패는 무시 |
| 일반 페이지 진입 | 저장된 활성 커밋고치가 있는 새 탭/새로고침 | Shadow DOM 캐릭터가 뷰포트 하단에서 좌우 이동하며 방향 전환 시 반전됨 | 저장 정보가 없거나 잘못되면 렌더링하지 않음 |
| 모션 감소 환경 | `prefers-reduced-motion: reduce` | 이동 애니메이션을 중지하고 하단에 정적으로 표시 | N/A |

</frozen-after-approval>

## Code Map

- `vue/public/manifest.json` -- `storage` 권한과 일반 HTTP(S) 페이지용 MV3 content script 등록.
- `vue/public/content-script.js` -- 독립 Shadow DOM 렌더러, 저장소 변경 수신, 하단 이동 및 방향 반전.
- `vue/src/extension/activeGotchi.js` -- Vue SPA가 Chrome 저장소에 활성 캐릭터를 안전하게 기록/삭제하는 브리지.
- `vue/src/stores/game.js` -- 생성·선택·삭제 시 활성 커밋고치 저장소 동기화.
- `vue/src/stores/auth.js` -- 인증 복원/로그인 시 활성 캐릭터 게시, 로그아웃/인증 실패 시 제거.
- `vue/src/App.vue` -- SPA 내부 전역 이동 마스코트 제거.
- `vue/_tests/extension-storage.mjs` -- Chrome API 유무와 저장/삭제 직렬화 검증.
- `vue/EXTENSION.md` -- 구조, 수동 테스트, 제한 페이지 및 동기화 트러블슈팅 기록.

## Tasks & Acceptance

**Execution:**
- [x] `vue/public/manifest.json`, `vue/public/content-script.js` -- MV3 content script를 등록하고 방문 페이지와 격리된 하단 이동 커밋고치를 구현한다.
- [x] `vue/src/extension/activeGotchi.js`, `vue/src/stores/game.js`, `vue/src/stores/auth.js` -- 활성 캐릭터와 인증 상태 변화를 `chrome.storage.local`에 동기화한다.
- [x] `vue/src/App.vue` -- SPA 전역 `Mascot` 렌더링을 제거하되 정적 캐릭터 표시는 유지한다.
- [x] `vue/_tests/extension-storage.mjs`, `vue/package.json` -- 저장소 브리지 회귀 테스트를 추가한다.
- [x] `vue/EXTENSION.md` -- 설계, 제한 사항, 트러블슈팅 및 수동 검증 절차를 기록한다.

**Acceptance Criteria:**
- Given 확장 팝업에서 로그인하고 캐릭터를 활성화했을 때, when 일반 HTTP(S) 탭을 열거나 이미 열린 탭을 확인하면, then 같은 활성 커밋고치가 페이지 CSS와 충돌 없이 뷰포트 하단에 표시된다.
- Given 커밋고치가 표시된 페이지에서, when 이동 방향이 바뀌면, then 캐릭터가 좌우 반전되며 스크롤 후에도 뷰포트 하단에 남는다.
- Given 열린 일반 탭이 있을 때, when 활성 캐릭터를 바꾸거나 로그아웃하면, then 탭 새로고침 없이 가능한 즉시 표시가 변경되거나 제거된다.
- Given 확장을 제한 URL에서 사용하거나 웹 개발 서버로 SPA를 실행할 때, when Chrome API 호출 경로가 실행되면, then 처리되지 않은 오류 없이 기존 SPA 기능이 유지된다.
- Given SPA 내부의 인증된 화면을 볼 때, when 페이지를 탐색하면, then 기존 전역 이동 마스코트는 보이지 않고 상세 화면 등의 정적 `CgSprite`는 유지된다.

## Spec Change Log

## Design Notes

Content script는 `chrome.storage.onChanged`를 직접 구독하므로 열린 탭에 활성 변경이 즉시 반영된다. 저장 값은 렌더링에 필요한 `id`, `name`, `emotion`, `isEvolved`만 포함하며 인증 토큰이나 전체 게임 상태는 저장하지 않는다. 캐릭터 표현은 기존 `CgSprite`의 새싹 SVG를 독립 구현해 방문 페이지에서 Vue 번들 의존성을 없앤다.

## Verification

**Commands:**
- `cd vue && npm test` -- 기존 스토어 회귀 테스트와 확장 저장소 브리지 테스트 성공.
- `cd vue && npm run build` -- Manifest와 content script를 포함한 확장 번들 생성 성공.

**Manual checks (if no CLI):**
- 압축 해제 확장을 로드하고 일반 HTTP(S) 탭에서 선택 변경, 로그아웃, 새 탭, 새로고침, 스크롤, 모션 감소 설정을 확인한다.
- `chrome://extensions` 같은 제한 페이지에서 확장 오류가 발생하지 않는지 확인한다.

## Suggested Review Order

**방문 페이지 렌더링**

- 독립 content script가 소유 DOM을 격리하고 제거에도 복구한다.
  [`content-script.js:29`](../public/content-script.js#L29)

- Shadow DOM 안에서 하단 이동, 방향 반전, 모션 감소를 처리한다.
  [`content-script.js:101`](../public/content-script.js#L101)

- 초기 조회와 실시간 변경 이벤트의 순서 경합을 방지한다.
  [`content-script.js:189`](../public/content-script.js#L189)

**상태 동기화**

- 인증 수명주기로 게시를 제한하고 저장 작업 순서를 직렬화한다.
  [`activeGotchi.js:10`](../src/extension/activeGotchi.js#L10)

- 로그인·복원·로그아웃에서 활성 커밋고치 게시 여부를 결정한다.
  [`auth.js:40`](../src/stores/auth.js#L40)

- 캐릭터 선택과 상태 변경을 저장소에 반영한다.
  [`game.js:122`](../src/stores/game.js#L122)

**확장 연결과 회귀 방지**

- MV3 storage 권한과 HTTP(S) content script 범위를 선언한다.
  [`manifest.json:10`](../public/manifest.json#L10)

- SPA 내부 전역 이동 마스코트를 제거한다.
  [`App.vue:10`](../src/App.vue#L10)

- 저장 순서, 로그아웃 게이트, content script 경합을 실행 검증한다.
  [`extension-storage.mjs:49`](../_tests/extension-storage.mjs#L49)

- 설치, 수동 검증, 제한 페이지 트러블슈팅을 기록한다.
  [`EXTENSION.md:39`](../EXTENSION.md#L39)
