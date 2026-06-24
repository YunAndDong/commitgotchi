# Investigation: Extension report-saved notice repeats on popup open

## Hand-off Brief

1. **What happened.** The Chrome extension popup repeatedly shows "리포트 저장됨 - 자정에 분석돼요. 내일 오전 9시 도착." after reopening because the backend persists that text in `state.notice`.
2. **Where the case stands.** Root cause is confirmed in source: the frontend clears the toast only in the in-memory Vue store, while popup reopen reloads `/api/game/state` and restores the persisted notice.
3. **What's needed next.** Verify the implemented local-only report submission notice behavior in frontend and backend tests.

## Case Info

| Field | Value |
| --- | --- |
| Ticket | N/A |
| Date opened | 2026-06-23 |
| Status | Concluded |
| System | Chrome extension popup, Vue frontend, Spring Boot game state API |
| Evidence sources | User screenshot, Vue store/App source, Spring Boot GameService/GameController source |

## Problem Statement

User reports that whenever the extension popup is opened, the toast text "리포트 저장됨 - 자정에 분석돼요. 내일 오전 9시 도착." appears repeatedly.

## Evidence Inventory

| Source | Status | Notes |
| --- | --- | --- |
| User screenshot | Available | Shows the exact toast in the extension popup. |
| Frontend toast rendering | Available | `vue/src/App.vue:11` watches `gameState.notice`; `vue/src/App.vue:37` renders it. |
| Frontend clear behavior | Available | `vue/src/stores/game.js:830` only sets local reactive state to null. |
| Backend notice creation | Available | `springboot/src/main/java/com/commitgotchi/game/application/GameService.java:205` writes the report-saved notice into persisted state. |
| State reload path | Available | `vue/src/stores/game.js:315` loads `/api/game/state`; `vue/src/stores/game.js:299` copies `next.notice` into the store. |
| Server clear endpoint | Missing | `vue/src/api/client.js:258` lists game endpoints and has no notice acknowledgement/clear call. |

## Confirmed Findings

### Finding 1: Report save persists the notice in game state

**Evidence:** `springboot/src/main/java/com/commitgotchi/game/application/GameService.java:205`

**Detail:** `saveReport` writes the exact user-visible message into `state.notice`, then saves the state at `springboot/src/main/java/com/commitgotchi/game/application/GameService.java:206`.

### Finding 2: Popup/app state reload restores persisted notice

**Evidence:** `vue/src/stores/game.js:315`, `vue/src/stores/game.js:299`

**Detail:** `loadGameState` fetches the server state and `applyGameState` assigns `state.notice = next.notice ?? null`.

### Finding 3: Toast dismissal is local-only

**Evidence:** `vue/src/App.vue:14`, `vue/src/App.vue:15`, `vue/src/stores/game.js:830`

**Detail:** The timer and click call `clearNotice`, but `clearNotice` only mutates the in-memory singleton. When the extension popup is closed, that memory disappears.

### Finding 4: There is no frontend API for persisted notice dismissal

**Evidence:** `vue/src/api/client.js:258`

**Detail:** The game API client exposes state, character, report, quiz, board, and review calls, but no endpoint to acknowledge or clear `notice`.

## Deduced Conclusions

### Deduction 1: Reopening the extension creates a fresh frontend memory state

**Based on:** Findings 2 and 3.

**Reasoning:** Chrome extension popups are short-lived UI instances. The user can dismiss the toast in one instance, but the next popup instance fetches the server state again and repopulates the same persisted `notice`.

**Conclusion:** The repeated popup is not caused by multiple timers. It is caused by a persisted one-shot message being used as durable game state without a persisted acknowledgement.

## Hypothesized Paths

### Hypothesis 1: The backend stores one-shot notices in durable game state

**Status:** Confirmed

**Theory:** A transient UI toast is stored in `GameState.stateJson`, so it survives popup close/reopen.

**Supporting indicators:** The exact message is written in `GameService.saveReport`; `stringifyForPersistence` only strips `characters`, not `notice`.

**Would confirm:** Find `state.put("notice", "...")` followed by persisted save.

**Would refute:** Find a server-side read/ack clear that executes when the toast is dismissed.

**Resolution:** Confirmed by `GameService.saveReport`, `save`, and absence of a frontend clear API.

## Source Code Trace

| Element | Detail |
| --- | --- |
| Error origin | `springboot/src/main/java/com/commitgotchi/game/application/GameService.java:205` writes the notice. |
| Trigger | User saves a report through `vue/src/stores/game.js:809` -> `vue/src/api/client.js:265` -> `GameController.saveReport`. |
| Condition | The user closes/reopens the extension popup after local `clearNotice()` ran, while backend `state.notice` remains unchanged. |
| Related files | `vue/src/App.vue`, `vue/src/stores/game.js`, `vue/src/api/client.js`, `springboot/src/main/java/com/commitgotchi/game/api/GameController.java`. |

## Conclusion

**Confidence:** High

The root cause is confirmed: `notice` is persisted server-side as part of game state, but dismissal is only client-local. Reopening the extension popup reloads the persisted state and shows the same notice again.

## Recommended Next Steps

### Fix direction

Implemented local-only display for the report submission notice: the backend clears `state.notice` during report save, and the Vue store sets the report-submitted toast only after the `saveReport()` action succeeds.

### Diagnostic

Save a report, dismiss the toast, reopen the extension popup, and inspect `/api/game/state`. If `state.notice` still contains the report-saved message after dismissal, the behavior is reproduced.

## Reproduction Plan

1. Log in and save a report.
2. Observe the toast.
3. Let the 2 second timer clear it or click the close button.
4. Close and reopen the Chrome extension popup.
5. Expected current behavior: the same report-saved toast appears again.
6. Expected fixed behavior: once dismissed, the same notice does not reappear on popup reopen.

## Follow-up: 2026-06-23

### New Evidence

- `vue/src/stores/game.js` now defines `REPORT_SUBMITTED_NOTICE`, filters the same message out of server-loaded state, and sets it locally only after `saveReport()` succeeds.
- `springboot/src/main/java/com/commitgotchi/game/application/GameService.java` now clears `state.notice` when saving a report, so the report submission message is not persisted in game state.

### Updated Conclusion

The requested behavior is implemented at the contract boundary: the report submission toast is tied to the frontend submit action instead of persisted server state.
