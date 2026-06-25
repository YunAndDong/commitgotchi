---
title: 'Character-scoped quiz flow'
type: 'bugfix'
created: '2026-06-24'
status: 'draft'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/investigations/quiz-answer-wrong-character-investigation.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 캐릭터 상세 화면에서 "퀴즈 풀기"를 누르면 사용자는 해당 캐릭터의 퀴즈라고 기대하지만, 현재 `/quiz` 화면은 전역 활성 캐릭터의 퀴즈만 보여준다. 그 결과 비활성 캐릭터 상세에서 진입한 사용자가 다른 활성 캐릭터에게 답변을 제출할 수 있다.

**Approach:** 캐릭터 상세의 퀴즈 링크가 캐릭터 ID를 명시적으로 전달하도록 하고, 퀴즈 화면은 route query의 `characterId`가 있으면 그 캐릭터를 대상 컨텍스트로 사용한다. 데모 퀴즈 생성도 같은 대상 캐릭터로 생성되게 하며, 제출 직전에는 화면 컨텍스트와 퀴즈의 `characterId`가 다르면 요청을 보내지 않는다.

## Boundaries & Constraints

**Always:** 기존 `/quiz` 직접 진입은 유지하고, 이 경우에는 지금처럼 활성 캐릭터 기준으로 동작해야 한다. 캐릭터 상세에서 진입한 `/quiz?characterId=...`는 활성 캐릭터가 달라도 query 캐릭터의 오늘 추천 퀴즈만 보여야 한다. 서버는 데모 퀴즈 생성 요청에 `characterId`가 있으면 소유권 검증 후 그 캐릭터로 생성하고, 없으면 기존처럼 활성 캐릭터를 사용해야 한다.

**Ask First:** 퀴즈를 캐릭터별 별도 path(`/character/:id/quiz`)로 바꾸거나, 캐릭터 상세 진입 시 자동으로 활성 캐릭터를 변경하는 UX로 방향을 바꿔야 한다면 먼저 확인한다. 저장된 기존 퀴즈의 `characterId`를 일괄 변경하거나 삭제해야 한다면 먼저 확인한다.

**Never:** FastAPI 채점 계약을 바꾸지 않는다. 제출 API의 기존 URL을 깨지 않는다. 캐릭터 삭제 시 미채점 퀴즈를 재할당하는 기존 정책은 이번 수정 범위에서 변경하지 않는다.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Character detail entry | A는 비활성, B는 활성, 사용자가 A 상세에서 퀴즈 풀기 클릭 | `/quiz?characterId=A`에서 A의 오늘 추천 퀴즈만 표시되고 제출도 A 퀴즈만 허용 | A가 없으면 빈 상태 또는 기본 fallback으로 렌더링하며 잘못된 제출 요청은 보내지 않음 |
| Direct quiz entry | 사용자가 대시보드나 주소창으로 `/quiz` 진입 | 기존처럼 활성 캐릭터의 오늘 추천 퀴즈 표시 | 활성 캐릭터가 없으면 퀴즈 생성 버튼 비활성 |
| Demo quiz creation | `/quiz?characterId=A`에서 퀴즈가 없어 "퀴즈 만들기" 클릭 | 백엔드가 A 소유권을 확인하고 A의 추천 퀴즈 생성 | 유효하지 않은 캐릭터면 생성하지 않고 기존 상태 유지 |
| Submit guard | 화면 대상 캐릭터와 퀴즈 `characterId`가 불일치 | 제출 API 호출 없음, 화면 상태 보존 | 사용자 입력 답안은 지우지 않음 |

</frozen-after-approval>

## Code Map

- `vue/src/views/CharacterDetailView.vue` -- 캐릭터 상세의 퀴즈 진입 링크가 현재 캐릭터 ID를 전달해야 한다.
- `vue/src/views/QuizView.vue` -- route query를 읽어 대상 캐릭터/퀴즈 목록/생성/제출 guard를 적용해야 한다.
- `vue/src/stores/game.js` -- 캐릭터 ID 기준 퀴즈 selector와 데모 퀴즈 생성 함수 인자 처리가 필요하다.
- `vue/src/api/client.js` -- 데모 퀴즈 생성 body에 선택적 `characterId`를 전달할 수 있어야 한다.
- `springboot/src/main/java/com/commitgotchi/game/api/GameController.java` -- `/api/game/quizzes/demo`가 선택적 request body를 받을 수 있어야 한다.
- `springboot/src/main/java/com/commitgotchi/game/application/GameService.java` -- 데모 퀴즈 생성 대상 캐릭터를 body 우선, 없으면 active fallback으로 해석해야 한다.
- `vue/_tests/fe6-9-stores.mjs` -- 캐릭터별 퀴즈 selector/생성 인자 동작을 프론트 스토어 수준에서 검증한다.
- `springboot/src/test/java/com/commitgotchi/report/DailyReportProjectionIntegrationTest.java` 또는 quiz 관련 integration test -- 서버 데모 퀴즈가 body `characterId`를 존중하는지 검증한다.

## Tasks & Acceptance

**Execution:**
- [ ] `vue/src/views/CharacterDetailView.vue` -- "퀴즈 풀기" RouterLink를 현재 캐릭터 ID query가 포함된 route object로 변경 -- 캐릭터 상세에서 진입한 대상 컨텍스트를 보존한다.
- [ ] `vue/src/stores/game.js` -- `quizzesForCharacter`, `quizCharacterByRouteId` 성격의 최소 helper/export를 추가하고 `createDemoQuizzes(characterId?)`를 지원 -- 기존 활성 캐릭터 흐름과 scoped 흐름을 함께 지원한다.
- [ ] `vue/src/views/QuizView.vue` -- `useRoute` 기반 target character를 계산하고, target quizzes를 렌더링하며, 생성/제출 guard를 적용 -- 잘못된 캐릭터로 제출되는 UI 경로를 차단한다.
- [ ] `vue/src/api/client.js`, `GameController.java`, `GameService.java` -- 데모 퀴즈 생성 요청의 선택적 `characterId`를 서버까지 전달하고 검증 -- 빈 scoped 퀴즈 화면에서 만든 퀴즈가 대상 캐릭터에 귀속된다.
- [ ] 프론트/백엔드 테스트 -- character-scoped 퀴즈 생성과 제출 guard 시나리오를 검증 -- 회귀를 막는다.

**Acceptance Criteria:**
- Given A 캐릭터 상세 화면이고 B가 활성 캐릭터일 때, when 사용자가 "퀴즈 풀기"를 누르면, then 퀴즈 화면은 A의 퀴즈만 보여주고 B의 퀴즈는 표시하지 않는다.
- Given `/quiz`로 직접 진입했을 때, when 활성 캐릭터가 존재하면, then 기존처럼 활성 캐릭터의 퀴즈가 표시된다.
- Given `/quiz?characterId=A`에서 퀴즈 만들기를 눌렀을 때, when A가 현재 사용자 소유 캐릭터이면, then 생성된 퀴즈의 `characterId`는 A이다.
- Given 화면 대상 캐릭터와 퀴즈 `characterId`가 다른 상태가 만들어졌을 때, when 제출 버튼을 눌러도, then 제출 API 호출이 발생하지 않고 답안 입력은 보존된다.

## Spec Change Log

## Design Notes

route query 방식은 기존 `/quiz` 라우트와 브라우저 hash history 구조를 유지하면서 캐릭터 상세에서만 컨텍스트를 추가할 수 있다. 자동 활성화 방식은 사용자가 의도하지 않은 전역 active 변경을 만들 수 있으므로 이번 수정에서는 피한다.

## Verification

**Commands:**
- `node vue/_tests/fe6-9-stores.mjs` -- expected: 프론트 스토어 회귀 테스트 통과
- `./gradlew test --tests "*DailyReportProjectionIntegrationTest" --tests "*QuizSubmitFastApiIntegrationTest"` -- expected: 관련 Spring Boot 통합 테스트 통과
