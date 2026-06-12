from __future__ import annotations

from collections import Counter
from dataclasses import dataclass
from typing import Any, Iterable

from .problem_bank_store import ProblemBankStore, load_problem_bank_store
from .schemas import ProblemRecord
from .text import clean_markdown, extract_terms, infer_fields, term_counter


@dataclass(frozen=True)
class ProblemSearchHit:
    problem: ProblemRecord
    score: float
    matched_terms: tuple[str, ...]


class ProblemBankSearcher:
    def __init__(self, store: ProblemBankStore):
        self.store = store
        self._problem_terms = {
            problem.source_key: term_counter(_searchable_text(problem))
            for problem in store.records
        }

    def search(
        self,
        query: str,
        *,
        limit: int = 3,
        min_score: float = 1.0,
        field: str | None = None,
        difficulty: str | None = None,
    ) -> list[ProblemSearchHit]:
        if limit <= 0:
            return []

        query_terms = extract_terms(query)
        if not query_terms:
            return []

        query_fields = set(infer_fields(query))
        hits: list[ProblemSearchHit] = []
        for problem in self.store.filter(field=field, difficulty=difficulty):
            score, matched_terms = _score_problem(
                query_terms,
                self._problem_terms.get(problem.source_key, Counter()),
                _searchable_text(problem),
            )
            matching_fields = query_fields.intersection(problem.fields)
            if matching_fields:
                score += 1.0 + (0.5 * len(matching_fields))
            if problem.primary_field in query_fields:
                score += 0.5
            if score >= min_score:
                hits.append(
                    ProblemSearchHit(
                        problem=problem,
                        score=score,
                        matched_terms=tuple(sorted(set(matched_terms))),
                    )
                )

        return sorted(
            hits,
            key=lambda hit: (-hit.score, hit.problem.difficulty, hit.problem.problem_id),
        )[:limit]


def search_problem_bank(
    query: str,
    *,
    store: ProblemBankStore | None = None,
    limit: int = 3,
    min_score: float = 1.0,
    field: str | None = None,
    difficulty: str | None = None,
) -> list[ProblemSearchHit]:
    searcher = ProblemBankSearcher(store if store is not None else load_problem_bank_store())
    return searcher.search(
        query,
        limit=limit,
        min_score=min_score,
        field=field,
        difficulty=difficulty,
    )


def recommend_problem_snapshots(
    query: str,
    *,
    store: ProblemBankStore | None = None,
    limit: int = 3,
    min_score: float = 1.0,
    field: str | None = None,
    difficulty: str | None = None,
) -> list[dict[str, Any]]:
    hits = search_problem_bank(
        query,
        store=store,
        limit=limit,
        min_score=min_score,
        field=field,
        difficulty=difficulty,
    )
    return recommendation_snapshots(hits)


def recommendation_snapshots(hits: Iterable[ProblemSearchHit]) -> list[dict[str, Any]]:
    return [
        problem_to_recommendation_snapshot(hit.problem, match_score=hit.score)
        for hit in hits
    ]


def problem_to_recommendation_snapshot(
    problem: ProblemRecord,
    *,
    match_score: float | None = None,
) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "problemId": problem.problem_id,
        "question": problem.question,
        "modelAnswer": problem.model_answer,
        "scoreAllocation": dict(problem.score_allocation),
        "difficulty": problem.difficulty,
        "sourcePath": problem.source_path,
    }
    if match_score is not None:
        payload["matchScore"] = round(match_score, 3)
    return payload


def _score_problem(
    query_terms: list[str],
    target_terms: Counter[str],
    target_text: str,
) -> tuple[float, list[str]]:
    if not query_terms:
        return 0.0, []

    score = 0.0
    matched_terms: list[str] = []
    lowered_target = target_text.lower()
    for term in query_terms:
        count = target_terms.get(term, 0)
        if count:
            score += min(count, 4)
            matched_terms.append(term)
        elif term in lowered_target:
            score += 0.5
            matched_terms.append(term)

    unique_matches = len(set(matched_terms))
    if unique_matches >= 2:
        score += unique_matches * 0.75
    return score, matched_terms


def _searchable_text(problem: ProblemRecord) -> str:
    rubric = problem.rubric
    return clean_markdown(
        " ".join(
            (
                problem.question,
                problem.model_answer,
                problem.difficulty,
                problem.primary_field,
                " ".join(problem.fields),
                problem.source_path,
                " ".join(problem.heading_path),
                " ".join(rubric.must_mention),
                " ".join(rubric.optional_mention),
                " ".join(rubric.must_not_confuse),
            )
        )
    )
