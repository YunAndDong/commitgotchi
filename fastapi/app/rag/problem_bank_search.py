from __future__ import annotations

from collections import Counter
from dataclasses import dataclass
from typing import Any, Iterable

from .concept_embeddings import EmbeddingClient
from .concept_search import cosine_similarity
from .problem_embedding_store import ProblemEmbeddingStore
from .problem_embeddings import build_problem_query_embedding_input
from .problem_bank_store import ProblemBankStore, load_problem_bank_store
from .schemas import ProblemRecord
from .text import clean_markdown, extract_terms, infer_fields, term_counter


DEFAULT_KEYWORD_WEIGHT = 0.65
DEFAULT_VECTOR_WEIGHT = 0.35
DEFAULT_VECTOR_SIMILARITY_THRESHOLD = 0.75


@dataclass(frozen=True)
class ProblemSearchHit:
    problem: ProblemRecord
    score: float
    matched_terms: tuple[str, ...]


@dataclass(frozen=True)
class _KeywordCandidate:
    problem: ProblemRecord
    raw_score: float
    matched_terms: tuple[str, ...]


class ProblemBankSearcher:
    def __init__(
        self,
        store: ProblemBankStore,
        *,
        embedding_store: ProblemEmbeddingStore | None = None,
        client: EmbeddingClient | None = None,
        keyword_weight: float = DEFAULT_KEYWORD_WEIGHT,
        vector_weight: float = DEFAULT_VECTOR_WEIGHT,
        vector_similarity_threshold: float = DEFAULT_VECTOR_SIMILARITY_THRESHOLD,
    ):
        self.store = store
        self.embedding_store = embedding_store
        self.client = client
        self.keyword_weight = keyword_weight
        self.vector_weight = vector_weight
        self.vector_similarity_threshold = vector_similarity_threshold
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
        embedding_store: ProblemEmbeddingStore | None = None,
        client: EmbeddingClient | None = None,
        keyword_weight: float | None = None,
        vector_weight: float | None = None,
        vector_similarity_threshold: float | None = None,
    ) -> list[ProblemSearchHit]:
        if limit <= 0:
            return []

        query_terms = extract_terms(query)
        if not query_terms:
            return []

        index = embedding_store if embedding_store is not None else self.embedding_store
        embedder = client if client is not None else self.client
        if index is None or embedder is None or len(index) == 0:
            return self._keyword_search(
                query_terms,
                query=query,
                limit=limit,
                min_score=min_score,
                field=field,
                difficulty=difficulty,
            )

        return self._hybrid_search(
            query,
            query_terms,
            embedding_store=index,
            client=embedder,
            keyword_weight=self.keyword_weight if keyword_weight is None else keyword_weight,
            vector_weight=self.vector_weight if vector_weight is None else vector_weight,
            vector_similarity_threshold=(
                self.vector_similarity_threshold
                if vector_similarity_threshold is None
                else vector_similarity_threshold
            ),
            limit=limit,
            min_score=min_score,
            field=field,
            difficulty=difficulty,
        )

    def _keyword_search(
        self,
        query_terms: list[str],
        *,
        query: str,
        limit: int,
        min_score: float,
        field: str | None,
        difficulty: str | None,
    ) -> list[ProblemSearchHit]:
        hits: list[ProblemSearchHit] = []
        for candidate in self._raw_keyword_scores(
            query_terms,
            query=query,
            field=field,
            difficulty=difficulty,
        ).values():
            if candidate.raw_score >= min_score:
                hits.append(
                    ProblemSearchHit(
                        problem=candidate.problem,
                        score=candidate.raw_score,
                        matched_terms=candidate.matched_terms,
                    )
                )

        return sorted(
            hits,
            key=lambda hit: (-hit.score, hit.problem.difficulty, hit.problem.problem_id),
        )[:limit]

    def _hybrid_search(
        self,
        query: str,
        query_terms: list[str],
        *,
        embedding_store: ProblemEmbeddingStore,
        client: EmbeddingClient,
        keyword_weight: float,
        vector_weight: float,
        vector_similarity_threshold: float,
        limit: int,
        min_score: float,
        field: str | None,
        difficulty: str | None,
    ) -> list[ProblemSearchHit]:
        try:
            query_embedding = client.embed_query(
                build_problem_query_embedding_input(
                    query,
                    field=field,
                    difficulty=difficulty,
                )
            )
        except Exception:
            return self._keyword_search(
                query_terms,
                query=query,
                limit=limit,
                min_score=min_score,
                field=field,
                difficulty=difficulty,
            )

        keyword_candidates = self._raw_keyword_scores(
            query_terms,
            query=query,
            field=field,
            difficulty=difficulty,
        )
        candidate_keys = {
            key
            for key, candidate in keyword_candidates.items()
            if candidate.raw_score >= min_score
        }
        vector_scores: dict[tuple[int, str], float] = {}
        resolved_vector_threshold = _clamp_01(vector_similarity_threshold)

        for key, candidate in keyword_candidates.items():
            problem_id, source_key = key
            problem_embedding = embedding_store.embedding_for(problem_id, source_key)
            if problem_embedding is None:
                continue
            similarity = cosine_similarity(query_embedding, problem_embedding)
            if similarity is None:
                return self._keyword_search(
                    query_terms,
                    query=query,
                    limit=limit,
                    min_score=min_score,
                    field=field,
                    difficulty=difficulty,
                )
            normalized_vector = _normalize_vector_similarity(similarity)
            if normalized_vector <= 0.0:
                continue
            vector_scores[key] = normalized_vector
            if (
                candidate.raw_score < min_score
                and normalized_vector < resolved_vector_threshold
            ):
                continue
            candidate_keys.add(key)

        if not vector_scores:
            return self._keyword_search(
                query_terms,
                query=query,
                limit=limit,
                min_score=min_score,
                field=field,
                difficulty=difficulty,
            )

        keyword_max = max(
            (keyword_candidates[key].raw_score for key in candidate_keys),
            default=0.0,
        )
        resolved_keyword_weight, resolved_vector_weight = _normalize_weights(
            keyword_weight,
            vector_weight,
        )
        hits = [
            ProblemSearchHit(
                problem=keyword_candidates[key].problem,
                score=_hybrid_score(
                    _normalize_keyword_score(keyword_candidates[key].raw_score, keyword_max),
                    vector_scores.get(key, 0.0),
                    keyword_weight=resolved_keyword_weight,
                    vector_weight=resolved_vector_weight,
                ),
                matched_terms=keyword_candidates[key].matched_terms,
            )
            for key in candidate_keys
        ]
        return sorted(
            hits,
            key=lambda hit: (
                -hit.score,
                hit.problem.difficulty,
                hit.problem.problem_id,
                hit.problem.source_key,
            ),
        )[:limit]

    def _raw_keyword_scores(
        self,
        query_terms: list[str],
        *,
        query: str,
        field: str | None,
        difficulty: str | None,
    ) -> dict[tuple[int, str], _KeywordCandidate]:
        query_fields = set(infer_fields(query))
        candidates: dict[tuple[int, str], _KeywordCandidate] = {}
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
            candidates[(problem.problem_id, problem.source_key)] = _KeywordCandidate(
                problem=problem,
                raw_score=score,
                matched_terms=tuple(sorted(set(matched_terms))),
            )
        return candidates


def search_problem_bank(
    query: str,
    *,
    store: ProblemBankStore | None = None,
    embedding_store: ProblemEmbeddingStore | None = None,
    client: EmbeddingClient | None = None,
    keyword_weight: float = DEFAULT_KEYWORD_WEIGHT,
    vector_weight: float = DEFAULT_VECTOR_WEIGHT,
    vector_similarity_threshold: float = DEFAULT_VECTOR_SIMILARITY_THRESHOLD,
    limit: int = 3,
    min_score: float = 1.0,
    field: str | None = None,
    difficulty: str | None = None,
) -> list[ProblemSearchHit]:
    searcher = ProblemBankSearcher(
        store if store is not None else load_problem_bank_store(),
        embedding_store=embedding_store,
        client=client,
        keyword_weight=keyword_weight,
        vector_weight=vector_weight,
        vector_similarity_threshold=vector_similarity_threshold,
    )
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
    embedding_store: ProblemEmbeddingStore | None = None,
    client: EmbeddingClient | None = None,
    keyword_weight: float = DEFAULT_KEYWORD_WEIGHT,
    vector_weight: float = DEFAULT_VECTOR_WEIGHT,
    vector_similarity_threshold: float = DEFAULT_VECTOR_SIMILARITY_THRESHOLD,
    limit: int = 3,
    min_score: float = 1.0,
    field: str | None = None,
    difficulty: str | None = None,
) -> list[dict[str, Any]]:
    hits = search_problem_bank(
        query,
        store=store,
        embedding_store=embedding_store,
        client=client,
        keyword_weight=keyword_weight,
        vector_weight=vector_weight,
        vector_similarity_threshold=vector_similarity_threshold,
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


def _normalize_keyword_score(raw_score: float, max_raw_score: float) -> float:
    if max_raw_score <= 0.0:
        return 0.0
    return _clamp_01(raw_score / max_raw_score)


def _normalize_vector_similarity(similarity: float) -> float:
    return _clamp_01(similarity)


def _hybrid_score(
    normalized_keyword: float,
    normalized_vector: float,
    *,
    keyword_weight: float,
    vector_weight: float,
) -> float:
    return (
        keyword_weight * _clamp_01(normalized_keyword)
        + vector_weight * _clamp_01(normalized_vector)
    )


def _normalize_weights(
    keyword_weight: float,
    vector_weight: float,
) -> tuple[float, float]:
    keyword = max(float(keyword_weight), 0.0)
    vector = max(float(vector_weight), 0.0)
    total = keyword + vector
    if total <= 0.0:
        return DEFAULT_KEYWORD_WEIGHT, DEFAULT_VECTOR_WEIGHT
    return keyword / total, vector / total


def _clamp_01(value: float) -> float:
    return min(max(float(value), 0.0), 1.0)


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
