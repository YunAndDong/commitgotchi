# Investigation: Quiz Answer Sent To Wrong Character

## Hand-off Brief

1. **What happened.** The quiz page is character-context-free: entering it from any character detail still renders the globally active character's quiz list, so a user can believe they are answering character A while the submitted quiz belongs to active character B.
2. **Where the case stands.** Concluded at source level with medium confidence; runtime payload/browser storage would make the exact user incident deterministic.
3. **What's needed next.** Fix the quiz entry flow so character detail either activates that character before opening quizzes or routes to a character-scoped quiz page.

## Case Info

| Field            | Value                                                                 |
| ---------------- | --------------------------------------------------------------------- |
| Ticket           | N/A                                                                   |
| Date opened      | 2026-06-24                                                            |
| Status           | Active                                                                |
| System           | macOS / Chrome extension UI / local commitgotchi workspace            |
| Evidence sources | User screenshot, source code, version control, tests, local app state |

## Problem Statement

User reported in Korean: "퀴즈 답변을 작성했는데, 답변이 다른 캐릭터에게 발송됐음. 왜 이렇게 됐는지 원인 추적해줘."

## Evidence Inventory

| Source                | Status    | Notes                                                                 |
| --------------------- | --------- | --------------------------------------------------------------------- |
| User screenshot       | Available | Chrome extension is on `chrome-extension://.../index.html#/quiz`.     |
| Source code           | Available | Traced quiz UI, store, API client, backend routes, creation/deletion. |
| Runtime server logs   | Missing   | Not yet collected.                                                    |
| Browser local storage | Missing   | Not yet collected.                                                    |
| Database rows         | Missing   | Not yet collected.                                                    |
| Version control       | Partial   | Worktree has many modified files; do not assume committed baseline.   |
| Tests                 | Partial   | Store-level ownership tests exist; UI route-context case is uncovered. |

## Investigation Backlog

| # | Path to Explore                                | Priority | Status      | Notes                                           |
| - | ---------------------------------------------- | -------- | ----------- | ----------------------------------------------- |
| 1 | Quiz UI submit handler                         | High     | Done        | Submit uses `q.id` and `userAnswer`; no character context. |
| 2 | Frontend game/character store                  | High     | Done        | Quiz list is derived from globally active character.       |
| 3 | Backend quiz submission route/service          | High     | Done        | Server sends to `quiz.characterId`, not caller-selected UI context. |
| 4 | Tests covering selected vs active character    | Medium   | Done        | Existing tests do not cover character-detail-to-quiz route context. |
| 5 | Runtime logs/database rows for actual incident | Medium   | Open        | Needed for deterministic incident proof.        |

## Timeline of Events

| Time       | Event                                                              | Source          | Confidence |
| ---------- | ------------------------------------------------------------------ | --------------- | ---------- |
| 2026-06-24 | User observed quiz answers being sent to another character.        | User report     | Confirmed  |
| 2026-06-24 | User screenshot shows quiz answers entered on the `#/quiz` route. | User screenshot | Confirmed  |

## Confirmed Findings

### Finding 1: Character detail loses character context when opening the quiz page

**Evidence:** `vue/src/views/CharacterDetailView.vue:26`, `vue/src/views/CharacterDetailView.vue:195`, `vue/src/views/CharacterDetailView.vue:199`

**Detail:** Character detail resolves the viewed character from `route.params.id`, shows whether that viewed character is active, but its "퀴즈 풀기" link is a plain `/quiz` link. The viewed character id is not passed to the quiz route and the link does not activate the viewed character.

### Finding 2: The quiz page renders only the globally active character's recommended quizzes

**Evidence:** `vue/src/views/QuizView.vue:9`, `vue/src/views/QuizView.vue:15`, `vue/src/stores/game.js:422`, `vue/src/stores/game.js:428`

**Detail:** `QuizView` imports `activeCharacter` and `activeQuizzes`, and `activeQuizzes` filters quizzes by `q.characterId === activeCharacter.id`. The route has no character parameter (`vue/src/router/index.js:27`).

### Finding 3: Quiz submission carries no explicit character id from the UI

**Evidence:** `vue/src/views/QuizView.vue:42`, `vue/src/views/QuizView.vue:47`, `vue/src/stores/game.js:1162`, `vue/src/api/client.js:293`

**Detail:** Submitting a quiz sends only the quiz id in the URL and `{ userAnswer, fail }` in the body. The UI does not send "I intended character X."

### Finding 4: Backend submission sends the answer to the character stored on the quiz object

**Evidence:** `springboot/src/main/java/com/commitgotchi/game/application/GameService.java:252`, `springboot/src/main/java/com/commitgotchi/game/application/GameService.java:254`, `springboot/src/main/java/com/commitgotchi/game/application/GameService.java:299`, `springboot/src/main/java/com/commitgotchi/game/application/GameService.java:320`

**Detail:** The backend finds a quiz by id in stored game state, reads `quiz.characterId`, and includes that value in `QuizGradeRequestMessage`. The FastAPI client posts that message as-is (`springboot/src/main/java/com/commitgotchi/quiz/application/FastApiQuizGradeRequestClient.java:61`).

### Finding 5: New characters automatically become active

**Evidence:** `springboot/src/main/java/com/commitgotchi/character/application/CharacterCreationService.java:39`, `springboot/src/main/java/com/commitgotchi/character/application/CharacterCreationService.java:57`, `springboot/src/test/java/com/commitgotchi/character/CharacterCreationApiIntegrationTest.java:122`

**Detail:** Creating a new character deactivates existing active characters and activates the new one. A user can later open an older, inactive character's detail page while a newer character remains globally active.

### Finding 6: Deleting a character can reassign its unscored quizzes to the active character

**Evidence:** `springboot/src/main/java/com/commitgotchi/game/application/GameService.java:191`, `springboot/src/main/java/com/commitgotchi/game/application/GameService.java:206`, `springboot/src/main/java/com/commitgotchi/game/application/GameService.java:856`

**Detail:** On character deletion, unscored quizzes whose `characterId` equals the deleted character are rewritten to the active character id. This is a separate route that can also make a quiz appear under a different character after deletion.

## Deduced Conclusions

### Deduction 1: The most likely source of the observed mismatch is route-context loss, not FastAPI misdelivery

**Based on:** Findings 1, 2, 3, and 4

**Reasoning:** The character detail page can be opened for character A, but the quiz page always uses globally active character B. The submit request then uses the quiz id from the displayed active-character quiz; the backend sends the answer to the quiz's stored `characterId`, which is B. Therefore, the system behaves consistently with its code, but the UI entry point makes the user's intended target ambiguous/wrong.

**Conclusion:** If the user clicked "퀴즈 풀기" from a non-active character detail, the answer would be submitted to the active character's quiz, i.e. "another character" from the user's perspective.

### Deduction 2: Backend submission does not currently validate the caller's intended character context

**Based on:** Findings 3 and 4

**Reasoning:** Since the request contains no route/query/body character id, the backend cannot detect "I was on character A's page but submitted B's quiz." It can only trust the quiz id and the persisted quiz `characterId`.

**Conclusion:** A frontend context bug reaches the backend silently.

## Hypothesized Paths

### Hypothesis 1: Quiz submission uses stale or global active character state

**Status:** Confirmed

**Theory:** The quiz page may submit using a globally active character rather than the character the user believes is selected.

**Supporting indicators:** The symptom involves a cross-character identity mismatch.

**Would confirm:** Code or payload evidence showing quiz submit resolves `characterId` from stale/global state.

**Would refute:** Code and runtime payload evidence showing submit always uses the intended current character id.

**Resolution:** Confirmed at source level by `CharacterDetailView` linking to `/quiz` without character context and `activeQuizzes` filtering by global `activeCharacter`.

### Hypothesis 2: Deleted-character cleanup reassigned old unscored quizzes

**Status:** Open

**Theory:** If the original character was deleted before submission, unscored quizzes were intentionally reassigned to the replacement active character.

**Supporting indicators:** Deletion code explicitly rewrites unscored quiz `characterId`.

**Would confirm:** Incident timeline includes deleting the original character before answering, and stored quiz row/state shows reassignment.

**Would refute:** No deletion occurred before the incident.

**Resolution:** Source-level alternate path identified; runtime history needed to confirm or refute for this incident.

## Missing Evidence

| Gap                      | Impact                                      | How to Obtain                                      |
| ------------------------ | ------------------------------------------- | -------------------------------------------------- |
| Exact submit HTTP payload | Confirms which quiz id was submitted. | Browser devtools/network logs or backend logs.     |
| Stored character state    | Confirms active/selected character at time. | Browser local storage or extension storage export. |
| Persisted quiz rows       | Confirms destination character in database. | Query local/prod database for the incident rows.   |
| User click path           | Distinguishes route-context bug from deletion reassignment. | User history or browser navigation timeline.       |

## Source Code Trace

| Element       | Detail  |
| ------------- | ------- |
| Error origin  | `vue/src/views/CharacterDetailView.vue:199` routes to `/quiz` without the viewed character id. |
| Trigger       | User clicks "퀴즈 풀기" from a character detail page. |
| Condition     | Viewed character differs from the globally active character, or original unscored quiz was reassigned after deletion. |
| Related files | `vue/src/views/QuizView.vue`, `vue/src/stores/game.js`, `vue/src/api/client.js`, `springboot/src/main/java/com/commitgotchi/game/application/GameService.java`, `springboot/src/main/java/com/commitgotchi/report/application/ReportCallbackService.java` |

## Conclusion

**Confidence:** Medium

The source-level root cause is that quiz navigation and rendering are based on global active-character state, while the character detail page gives the user a per-character mental model. The backend then correctly sends the submitted quiz to the quiz's persisted `characterId`; the missing guard is that the frontend never carries or validates the intended character context.

## Recommended Next Steps

### Fix direction

Make the quiz flow character-scoped. Recommended options:

1. Route from character detail to a character-scoped quiz page, e.g. `/character/:id/quiz` or `/quiz?characterId=:id`, and filter quizzes by that id.
2. Or make the "퀴즈 풀기" button first activate the viewed character and await state refresh before routing to `/quiz`.
3. Add a visible target character indicator on the quiz page and block submission if the quiz's `characterId` does not match the intended route character.

### Diagnostic

Collect submit payload, active character id, viewed character id, and persisted quiz object for the incident. If the user deleted a character before submission, inspect the deletion timeline because unscored quizzes are reassigned during delete.

## Reproduction Plan

1. Create character A.
2. Create character B, which becomes active automatically.
3. Navigate to character A detail; it shows "비활성 캐릭터".
4. Click "퀴즈 풀기"; the app opens `/quiz`, which renders `activeQuizzes` for B.
5. Submit an answer; backend sends the quiz to B because the quiz object's `characterId` is B.

## Side Findings

- Existing store tests verify that submitting a seeded quiz preserves that quiz's `characterId` even if active character changes (`vue/_tests/fe6-9-stores.mjs:67`). This does not cover the UI route context that causes the wrong quiz list to be shown.
- Character deletion intentionally rewrites unscored quizzes away from the deleted character (`springboot/src/main/java/com/commitgotchi/game/application/GameService.java:856`). This may be desired cleanup, but it can look like "the quiz moved" unless the UI explains it.

## Follow-up: 2026-06-24

### New Evidence

- `vue/src/views/CharacterSelectView.vue:35`-`39`: character select flow already calls `setActive(character.id)` before redirecting.
- `vue/src/views/QuizView.vue:9`-`15`: quiz page still reads `activeCharacter` and `activeQuizzes`; it has no route-level target character.
- `vue/src/stores/game.js:546`-`550`: `activeQuizzes` filters today's recommended quizzes by `activeCharacter.value.id`.
- `vue/src/stores/game.js:1298`-`1305` and `vue/src/api/client.js:297`-`298`: quiz submit sends only `quizId` and answer; demo quiz creation sends no `characterId`.
- `springboot/src/main/java/com/commitgotchi/game/application/GameService.java:280`-`287`: demo quiz creation targets `activeCharacterId(currentState)`.
- `springboot/src/main/java/com/commitgotchi/game/application/GameService.java:299`-`324`: submit-to-FastAPI uses `quiz.characterId` for the grading request.
- `fastapi/app/integration/spring_payloads.py:100`-`110`: FastAPI quiz grade-result callback payload omits `characterId`.
- `springboot/src/main/java/com/commitgotchi/quiz/application/QuizGradeResultService.java:180`-`190`: grade-result callback finds the quiz by `submissionId`, falling back to `quizId`.
- `vue/src/stores/game.js:1036`-`1051` and `springboot/src/main/java/com/commitgotchi/report/application/ReportCallbackService.java:240`-`250`: demo/problem-bank quizzes can reuse low/non-unique `quizId` values such as `1`, `2`, or repeated problem ids across characters.

### Additional Findings

- Character selection itself is not the missing call path; the select screen already activates the clicked character.
- The quiz screen remains globally active-character scoped. Any entry path that reaches `/quiz` before the intended character is active will render and submit the active character's quiz list.
- The demo quiz creation endpoint is also active-character scoped. If active state is stale when "퀴즈 만들기" is clicked, newly created quizzes are stored under the stale active character.
- The async callback path is mostly safe when `submissionId` round-trips correctly, because Spring reads the matched quiz's stored `characterId`. However, FastAPI does not include `characterId` in the callback, and Spring's fallback match by `quizId` can target the wrong quiz if `submissionId` is missing, mismatched, or generated differently by the grader/result adapter.

### Updated Hypotheses

- Hypothesis 1 remains confirmed for route/context loss: `/quiz` is not character-scoped and depends on global active state.
- Hypothesis 3 added: quiz creation can attach quizzes to the wrong character when active state is stale because both frontend and backend demo creation have no explicit character target.
- Hypothesis 4 added: callback can update a wrong quiz if `submissionId` does not match and duplicate `quizId` exists across characters. This is a lower-probability but source-supported path.

### Backlog Changes

- Add runtime capture of `/api/game/quizzes/{id}/submit` response fields: `item.id`, `item.characterId`, `item.submissionId`, `item.quizId`.
- Add runtime capture of FastAPI callback payload: `submissionId`, `quizId`, and whether `characterId` is absent.
- Add state inspection before submit: active character id, visible route, visible quiz ids, each visible quiz's `characterId`.
- Add regression test with two characters each having a quiz with the same `quizId`, then post a grade-result with a mismatched submission id to prove/refute wrong fallback matching.

### Updated Conclusion

The flow is now narrowed to two high-probability source causes and one callback fallback risk. First, `/quiz` and quiz creation still rely on global active-character state rather than an explicit target character. Second, if the Chrome extension is still running an old bundle, the recent detail-page active-sync change is not participating at all; seeing "비활성 캐릭터" after entering a detail page would confirm that. Third, the grade-result callback omits `characterId` and can fall back to non-unique `quizId` matching if `submissionId` does not match.
