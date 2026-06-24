# Investigation: Character Double Create In Deployment

## Hand-off Brief

1. **What happened.** Deployment can create two characters because duplicate `POST /api/game/characters` submissions are accepted as two legitimate create commands.
2. **Where the case stands.** Concluded with medium confidence from source evidence; production logs/database samples would raise this to high confidence.
3. **What's needed next.** Add frontend in-flight submit locking and backend idempotency for character creation.

## Case Info

| Field            | Value                                                                 |
| ---------------- | --------------------------------------------------------------------- |
| Ticket           | N/A                                                                   |
| Date opened      | 2026-06-24                                                            |
| Status           | Active                                                                |
| System           | commitgotchi local/deployed Spring Boot + Vue + FastAPI architecture  |
| Evidence sources | User report, source code, deployment config, tests, version control   |

## Problem Statement

The user reports that local and deployed environments behave differently during character creation, and in deployment there are cases where creating a character results in two created characters.

## Evidence Inventory

| Source | Status | Notes |
| ------ | ------ | ----- |
| User report | Available | Deployment sometimes creates two characters; local differs. |
| Source code | Available | Vue submit path, API client, Spring controller/service/repository/migrations reviewed. |
| Deployment config | Available | `docker-compose.prod.yml` and nginx config reviewed. |
| Runtime prod logs/database | Missing | Needed to confirm exact duplicate request timing and database rows. |

## Investigation Backlog

| # | Path to Explore | Priority | Status | Notes |
| - | --------------- | -------- | ------ | ----- |
| 1 | Vue character creation submit path | High | Done | No in-flight guard; submit button remains enabled while request is pending. |
| 2 | Spring character creation transaction | High | Done | One request performs one insert, but each duplicate request creates another row. |
| 3 | Deployment proxy/retry behavior | Medium | Done | No nginx POST retry evidence found. |
| 4 | Tests and migrations | Medium | Done | Active uniqueness exists; request idempotency does not. |

## Timeline of Events

| Time | Event | Source | Confidence |
| ---- | ----- | ------ | ---------- |
| 2026-06-24 | Investigation opened from user report. | User message | Confirmed |
| 2026-06-24 | Static source trace found missing frontend submit lock and missing backend idempotency for create. | Source code | Confirmed |

## Confirmed Findings

### Finding 1: Character creation has a single backend route and one API client call path

**Evidence:** `springboot/src/main/java/com/commitgotchi/game/api/GameController.java:57`, `vue/src/api/client.js:260`

**Detail:** Vue calls `POST /api/game/characters`, and Spring routes that request to `GameService.createCharacter`.

### Finding 2: The create screen has no pending-submit guard

**Evidence:** `vue/src/views/CharacterCreateView.vue:24`, `vue/src/views/CharacterCreateView.vue:97`

**Detail:** `submit()` awaits `createCharacter(...)`, but there is no `saving`/`submitting` flag. The submit button is disabled only when the user is already at the character limit.

### Finding 3: The frontend HTTP client has a fixed 15 second timeout

**Evidence:** `vue/src/api/client.js:17`, `vue/src/api/client.js:87`, `vue/src/api/client.js:91`

**Detail:** Every request uses an `AbortController` timeout. A slow deployed create path can leave the UI in an error/pending state while the backend may already have created the character.

### Finding 4: Backend creation inserts a new character for every accepted request

**Evidence:** `springboot/src/main/java/com/commitgotchi/character/application/CharacterCreationService.java:29`, `springboot/src/main/java/com/commitgotchi/character/application/CharacterCreationService.java:31`, `springboot/src/main/java/com/commitgotchi/character/application/CharacterCreationService.java:51`, `springboot/src/main/java/com/commitgotchi/character/application/CharacterCreationService.java:58`, `springboot/src/main/java/com/commitgotchi/character/domain/LearningCharacterMapper.java:162`

**Detail:** The service locks the user row, checks the count, deactivates prior active characters, creates a new domain object, and inserts it. There is no request idempotency key or duplicate-intent lookup.

### Finding 5: Database constraints prevent multiple active characters, not duplicate creation

**Evidence:** `springboot/src/main/resources/db/migration/V4__create_characters.sql:54`, `springboot/src/main/resources/db/migration/V6__add_soft_delete_to_users_and_characters.sql:9`

**Detail:** The partial unique index allows only one active character per user. It does not prevent two rows with the same user/name/keyword/personality from being inserted by two requests.

### Finding 6: Production enables the character image integration path, and nginx does not show POST replay config

**Evidence:** `docker-compose.prod.yml:59`, `docker-compose.prod.yml:60`, `nginx/api-only.conf:34`, `nginx/api-only.conf:35`

**Detail:** Production defaults character image generation to enabled and routes `/api/**` to Spring Boot. No nginx `proxy_next_upstream` or equivalent POST retry configuration was found in the checked config.

## Deduced Conclusions

### Deduction 1: A single backend request is unlikely to create two character rows

**Based on:** Findings 1 and 4.

**Reasoning:** The controller delegates once to `GameService.createCharacter`; `CharacterCreationService.create` contains one new-character insert; image generation updates the created character rather than creating another one.

**Conclusion:** The observed double creation is more likely two accepted create requests than one request double-inserting.

### Deduction 2: Deployment makes duplicate submission easier to trigger

**Based on:** Findings 2, 3, and 6.

**Reasoning:** The UI keeps the submit button enabled while the network request is pending. Deployment adds public network/proxy/image-generation latency, so the vulnerable window is larger than a fast local path.

**Conclusion:** Double click, repeated Enter, popup/user retry after slow response, or client timeout after a committed create can result in two backend create requests.

### Deduction 3: The backend intentionally serializes concurrent creates but still treats both as valid

**Based on:** Finding 4 and `springboot/src/main/java/com/commitgotchi/user/domain/UserMapper.java:35`.

**Reasoning:** `FOR UPDATE` serializes requests for a user, so request B waits for request A, then sees the newly created character, deactivates it, and inserts another character if the max count is not exceeded.

**Conclusion:** The backend protects the "one active" invariant, not the "one user action creates one character" invariant.

## Hypothesized Paths

### Hypothesis 1: The create request is submitted twice from the deployed frontend

**Status:** Confirmed

**Theory:** The user-visible symptom may be caused by duplicate POSTs from the browser, likely from form submit/click behavior or a retry path.

**Supporting indicators:** The create form has no pending-submit guard, the request can remain pending long enough in deployment for repeated submission, and backend accepts every create POST as a new command.

**Would confirm:** Browser/network logs or code showing two POSTs from one user action.

**Would refute:** Verified single POST per action with duplicate database inserts from one backend request.

**Resolution:** Confirmed as a valid root-cause mechanism by code; exact production occurrence still requires logs.

### Hypothesis 2: The backend creation path is non-idempotent and accepts duplicate requests

**Status:** Confirmed

**Theory:** Even if duplicate POSTs are accidental, the backend does not appear to require an idempotency key or unique creation token.

**Supporting indicators:** Initial scan found a plain `POST /api/game/characters`.

**Would confirm:** Mapper/service code inserts a new row on each call without request dedupe.

**Would refute:** A unique constraint or application-level dedupe blocks duplicate submissions.

**Resolution:** Confirmed by service and migration review.

### Hypothesis 3: Deployment proxy/runtime retries POST requests

**Status:** Refuted

**Theory:** Nginx, container restart, or client timeout behavior could replay the POST in deployed conditions.

**Supporting indicators:** The issue is reported in deployment rather than local.

**Would confirm:** Proxy retry config or logs showing replayed POSTs.

**Would refute:** Deployment config has no POST retry path and frontend/backend evidence explains duplication.

**Resolution:** No POST replay behavior was found in the nginx config reviewed. Runtime logs would be needed to rule out every external retry source, but current repo evidence does not support this path.

## Missing Evidence

| Gap | Impact | How to Obtain |
| --- | ------ | ------------- |
| Production access logs for `POST /api/game/characters` | Raises confidence from medium to high by proving two POSTs per incident. | Inspect nginx/Spring logs with request IDs and timestamps. |
| Production database duplicate row examples | Confirms whether duplicate rows share same user/name/keyword and near-identical timestamps. | Query `characters` table for same user/name within a short time window. |

## Source Code Trace

| Element | Detail |
| ------- | ------ |
| Error origin | `vue/src/views/CharacterCreateView.vue:24`, `springboot/src/main/java/com/commitgotchi/character/application/CharacterCreationService.java:58` |
| Trigger | Browser submits character creation form, calls `POST /api/game/characters`. |
| Condition | Duplicate submit/retry reaches backend before UI navigates or after frontend timeout/error; backend lacks idempotency. |
| Related files | `vue/src/views/CharacterCreateView.vue`, `vue/src/stores/game.js`, `vue/src/api/client.js`, `springboot/src/main/java/com/commitgotchi/game/api/GameController.java`, `springboot/src/main/java/com/commitgotchi/game/application/GameService.java`, `springboot/src/main/java/com/commitgotchi/character/application/CharacterCreationService.java` |

## Conclusion

**Confidence:** Medium

The root cause mechanism is confirmed in code: character creation is non-idempotent and the frontend does not block duplicate submissions while a create request is pending. Deployment likely exposes the issue because the request remains pending longer due to network/proxy/image-generation latency. A single backend request double-inserting is unlikely from the reviewed source.

## Recommended Next Steps

### Fix direction

1. Add a `saving`/`submitting` guard in `CharacterCreateView.vue` so repeated clicks or Enter presses are ignored while create is in flight.
2. Add backend idempotency for `POST /api/game/characters`, for example a client-generated request id stored with a uniqueness constraint or a short-window duplicate-intent guard.
3. Consider decoupling image generation from the synchronous create response so the create command returns quickly with `PENDING`, then image completion arrives asynchronously.

### Diagnostic

Check production logs/database:

- Access logs: count `POST /api/game/characters` per user around duplicate incidents.
- DB: find rows with same `user_id`, `name`, `design_keyword`, and close `created_at`.
- Browser devtools: reproduce with a double-click or repeated Enter on deployed build and verify two POSTs.

## Reproduction Plan

1. Build/run with backend latency on character creation.
2. Open create screen.
3. Double click `분신 생성하기` or press Enter twice before navigation.
4. Expected current behavior: two `POST /api/game/characters` requests can create two rows if the user has fewer than two existing characters.
5. Expected fixed behavior: second submit is ignored or backend returns the original created character for the same request id.

## Side Findings
