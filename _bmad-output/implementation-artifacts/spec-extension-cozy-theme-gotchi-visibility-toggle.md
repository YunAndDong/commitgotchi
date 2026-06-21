---
title: '확장 프로그램 cozy 테마 고정 및 웹페이지 커밋고치 표시 토글'
type: 'feature'
created: '2026-06-14'
status: 'done'
baseline_commit: 'a5dee36830f3aa870a9d56da20d92366f2877969'
context:
  - '{project-root}/vue/EXTENSION.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 확장 프로그램 팝업에서 사용자가 테마를 바꿀 수 있고, 방문 중인 웹페이지 하단의 활성 커밋고치를 일시적으로 숨길 방법이 없다.

**Approach:** 앱 테마를 항상 `cozy`로 고정하고 기존 테마 스위처 위치를 확장 팝업 전용 표시 토글로 교체한다. 토글 값은 Chrome 로컬 저장소에 보존하고 콘텐츠 스크립트가 즉시 반영한다.

## Boundaries & Constraints

**Always:** 기존 `commitgotchi.activeGotchi` 스냅샷은 유지하며 별도 boolean 저장 키로 표시 여부를 제어한다. 표시 설정이 없으면 기존 동작 호환을 위해 기본값은 보임이다. 현재 작업 트리의 콘텐츠 스크립트 이동 동기화 변경을 보존한다. 토글은 확장 프로그램 팝업에서만 표시한다.

**Ask First:** 표시 설정의 기본값을 숨김으로 바꾸거나, 일반 웹 개발 화면에도 토글을 노출해야 하는 경우.

**Never:** 숨김 동작을 위해 활성 커밋고치 데이터를 삭제하지 않는다. 방문 페이지 DOM/CSS에 전역 스타일을 주입하지 않는다. 콘텐츠 스크립트의 Shadow DOM 격리 또는 클릭 통과 동작을 약화하지 않는다.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 기본 표시 | visibility 저장 값 없음, 활성 커밋고치 있음 | 방문 페이지 하단에 커밋고치 표시, 토글 켜짐 | N/A |
| 표시 끄기 | 팝업 토글을 끔 | 모든 열린 탭에서 렌더링 호스트 제거, 활성 스냅샷 유지 | 저장 실패 시 기존 상태 유지 |
| 표시 다시 켜기 | 팝업 토글을 켬, 활성 스냅샷 있음 | 모든 열린 탭에서 커밋고치 즉시 복원 | 유효한 스냅샷이 없으면 표시하지 않음 |
| 웹 환경 | Chrome storage API 없음 | 표시 토글을 노출하지 않음 | 앱은 정상 동작 |

</frozen-after-approval>

## Code Map

- `vue/src/main.js` -- 앱 시작 시 `cozy` 테마를 강제하는 진입점
- `vue/src/components/AppNav.vue` -- 기존 테마 스위처 위치에 확장 팝업 전용 토글 배치
- `vue/src/components/GotchiVisibilityToggle.vue` -- 표시 설정을 조회·변경하는 접근 가능한 토글 UI
- `vue/src/extension/activeGotchi.js` -- Chrome 저장소 키와 표시 설정 API
- `vue/public/content-script.js` -- 활성 커밋고치와 표시 설정을 함께 구독해 렌더링 제어
- `vue/_tests/extension-storage.mjs` -- 저장소 및 콘텐츠 스크립트 표시 계약 검증
- `vue/EXTENSION.md` -- 팝업 토글 사용법과 저장 키 문서화

## Tasks & Acceptance

**Execution:**
- [x] `vue/src/main.js`, `vue/src/components/AppNav.vue` -- 테마를 `cozy`로 고정하고 ThemeSwitcher를 확장 팝업 전용 표시 토글로 교체한다.
- [x] `vue/src/components/GotchiVisibilityToggle.vue`, `vue/src/extension/activeGotchi.js` -- 표시 설정을 안전하게 읽고 쓰며 상태와 접근성 레이블을 반영한다.
- [x] `vue/public/content-script.js` -- 표시 설정 초기값과 변경 이벤트를 활성 스냅샷 렌더링에 결합한다.
- [x] `vue/_tests/extension-storage.mjs`, `vue/EXTENSION.md` -- 기본 표시, 숨김, 재표시, 저장 실패 계약을 검증하고 문서화한다.

**Acceptance Criteria:**
- Given 확장 프로그램 팝업을 열었을 때, when 상단 내비를 확인하면, then 테마 변경 버튼 없이 웹페이지 커밋고치 표시 토글이 보이고 테마는 `cozy`이다.
- Given 일반 웹 개발 화면을 열었을 때, when 상단 내비를 확인하면, then 확장 전용 표시 토글은 보이지 않고 테마는 `cozy`이다.
- Given 열린 HTTP(S) 탭들에 활성 커밋고치가 보일 때, when 팝업 토글을 끄거나 켜면, then 열린 탭들이 새로고침 없이 모두 숨김 또는 표시 상태를 반영한다.
- Given 표시를 껐다 다시 켰을 때, when 활성 커밋고치 스냅샷이 유효하면, then 기존 캐릭터가 다시 표시된다.

## Spec Change Log

## Verification

**Commands:**
- `cd vue && npm test` -- 확장 저장소, 콘텐츠 스크립트 및 기존 프런트엔드 계약 통과
- `cd vue && npm run build` -- 확장 프로그램 배포 번들 생성 성공

**Manual checks (if no CLI):**
- 압축 해제된 `vue/dist` 확장을 다시 로드한 후 팝업의 토글을 껐다 켜며 열린 웹페이지의 커밋고치가 즉시 숨김/복원되는지 확인한다.

## Suggested Review Order

**팝업 진입점과 토글 UI**

- 기존 테마 스위처 위치를 확장 팝업 전용 표시 토글로 교체한다.
  [`AppNav.vue:39`](../../vue/src/components/AppNav.vue#L39)

- 초기 조회와 저장 이벤트 경쟁을 막으며 접근 가능한 switch 상태를 제공한다.
  [`GotchiVisibilityToggle.vue:13`](../../vue/src/components/GotchiVisibilityToggle.vue#L13)

- 앱 부팅마다 `cozy` 테마를 강제하고 이전 선택값을 제거한다.
  [`main.js:7`](../../vue/src/main.js#L7)

**저장소와 방문 페이지 반영**

- 활성 데이터와 독립된 표시 설정 키를 안전하게 읽고 쓴다.
  [`activeGotchi.js:43`](../../vue/src/extension/activeGotchi.js#L43)

- 숨김 시 활성 스냅샷은 유지하고 렌더링 호스트만 제거한다.
  [`content-script.js:148`](../../vue/public/content-script.js#L148)

- 초기 조회와 두 저장 키 변경을 독립된 revision으로 동기화한다.
  [`content-script.js:225`](../../vue/public/content-script.js#L225)

**검증과 문서**

- 기본 표시, 숨김, 복원, 새 탭, 초기 조회 경쟁 조건을 검증한다.
  [`extension-storage.mjs:167`](../../vue/_tests/extension-storage.mjs#L167)

- 팝업 토글의 저장 키와 수동 검증 절차를 문서화한다.
  [`EXTENSION.md:42`](../../vue/EXTENSION.md#L42)
