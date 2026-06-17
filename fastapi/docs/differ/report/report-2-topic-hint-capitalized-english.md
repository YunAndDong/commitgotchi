# Report 2 Deferred Note - Capitalized English Topic Hints

## Context

Story 2 currently extracts `topicHints` with several deterministic signals:

- explicit topic rules such as `JPA`, `OAuth/JWT`, `Docker`, and `Graph`
- slash and plus patterns such as `OAuth/JWT` and `JPA N+1`
- backtick-wrapped terms
- capitalized English terms

The capitalized English rule can over-collect ordinary sentence words. For example, an English sentence may produce weak topic hints such as `Today` or `Then`.

## Decision

Do not change this rule as part of the immediate Story 2 fix.

The primary users are expected to write Korean daily reports with mixed technical terms. In that context, the higher-value signals are Korean technical concepts, explicit aliases, acronyms, slash patterns, plus patterns, and headings. Trying to distinguish every English common word from every technical term now would add heuristic complexity without enough product evidence.

## Follow-Up Direction

When this becomes a retrieval-quality issue, prefer a conservative adjustment:

- keep explicit topic rules
- keep slash and plus patterns
- keep all-caps acronyms such as `HTTP`, `JWT`, `SQL`, `OS`, and `DB`
- keep backtick-wrapped terms
- consider dropping standalone Title Case words unless they appear in a curated topic rule
- strengthen Korean concept coverage through topic rules or field vocabulary alignment

The goal should be precision over recall. `topicHints` are retrieval helpers, not final labels, so ambiguous hints should be omitted rather than aggressively guessed.
