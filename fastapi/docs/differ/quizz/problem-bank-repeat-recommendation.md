# Quizz Deferred Note - Previously Solved Problem Re-Recommendation

## Context

The current quiz recommendation flow is problem-bank based. FastAPI recommends existing quiz problems from `fastapi/data/rag/catalog/problems.jsonl`; it does not generate a new quiz at runtime.

This is good for stable grading and predictable payloads, but it creates one important product risk: the same problem can be recommended again after the user has already solved it.

## Problem

The current recommendation input is mainly based on report text, detected topics, fields, and problem-bank search score. It does not yet receive or apply user-specific solved-problem history.

Because of that, a strong match in the problem bank may be returned repeatedly:

- the user studies `JPA N+1`
- the recommender finds the best existing `JPA N+1` problem
- the user solves that problem
- a later report again mentions `JPA N+1`
- the same problem may be recommended again

## Why It Matters

Repeated recommendations can make the daily report flow feel stale even when the search result is technically relevant. It also weakens the learning loop because the user may expect the next quiz to cover a nearby or slightly deeper concept after solving the first one.

## Deferred Decision

Do not solve this inside Story 3 retrieval.

Story 3 is about concept evidence retrieval and source-neighborhood expansion. The solved-problem issue belongs to the final quiz recommendation assembly layer, where user identity and quiz history can be considered.

## Follow-Up Direction

When wiring the final report result and quiz recommendation flow:

- pass solved `problemId` values, solved `sourceKey` values, or recent quiz history into the recommender
- filter out already solved problems before choosing the final recommendation
- if all strong candidates were already solved, either return `recommendedQuizzes: []` or pick the next best unsolved nearby problem
- keep the final product contract as at most one recommended quiz per daily report if that is the agreed contract
- document the fallback behavior so Spring Boot and UI can explain why no quiz was recommended

The important distinction is: problem-bank search can produce multiple relevant candidates, but final quiz recommendation should be user-aware before selecting the one quiz to show.
