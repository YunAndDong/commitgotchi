from __future__ import annotations

import math
import unittest

from app.rag.problem_bank_search import (
    DEFAULT_VECTOR_SIMILARITY_THRESHOLD,
    ProblemBankSearcher,
    problem_to_recommendation_snapshot,
    recommend_problem_snapshots,
    search_problem_bank,
)
from app.rag.problem_embedding_store import EmbeddedProblem, ProblemEmbeddingStore
from app.rag.problem_bank_store import FASTAPI_ROOT, ProblemBankStore, read_problem_jsonl
from app.rag.schemas import ProblemRecord, ProblemRubric


FIXTURE_ROOT = FASTAPI_ROOT / "tests" / "fixtures" / "rag" / "problem_bank_store"
PROBLEMS_FIXTURE = FIXTURE_ROOT / "problems.jsonl"


class ProblemBankSearchTest(unittest.TestCase):
    def setUp(self) -> None:
        self.store = ProblemBankStore(read_problem_jsonl(PROBLEMS_FIXTURE).records)

    def test_keyword_search_returns_nearby_problem_candidates(self) -> None:
        searcher = ProblemBankSearcher(self.store)

        hits = searcher.search(
            "오늘 JPA N+1 lazy loading과 fetch join 해결 방법을 공부했다.",
            field="db",
            difficulty="advanced",
            limit=2,
        )

        self.assertGreaterEqual(len(hits), 1)
        self.assertEqual(hits[0].problem.problem_id, 101)
        self.assertIn("n+1", hits[0].matched_terms)
        self.assertGreater(hits[0].score, 1.0)

    def test_field_and_difficulty_filter_can_find_rest_problem(self) -> None:
        searcher = ProblemBankSearcher(self.store)

        hits = searcher.search(
            "HTTP URI 상태 코드와 REST API 설계 원칙",
            field="network",
            difficulty="basic",
        )

        self.assertEqual([hit.problem.problem_id for hit in hits], [202])

    def test_recommendation_snapshot_contains_quiz_contract_fields(self) -> None:
        hit = ProblemBankSearcher(self.store).search("JPA N+1 fetch join", limit=1)[0]

        snapshot = problem_to_recommendation_snapshot(hit.problem, match_score=hit.score)

        self.assertEqual(snapshot["problemId"], 101)
        self.assertIn("N+1", snapshot["question"])
        self.assertIn("fetch join", snapshot["modelAnswer"])
        self.assertEqual(snapshot["scoreAllocation"]["db"], 10)
        self.assertEqual(snapshot["difficulty"], "advanced")
        self.assertEqual(snapshot["sourcePath"], "fixtures/database/qna-jpa.md")
        self.assertIn("matchScore", snapshot)

    def test_no_candidates_returns_empty_array(self) -> None:
        searcher = ProblemBankSearcher(self.store)

        self.assertEqual(searcher.search("그래프 최단 거리 다익스트라", min_score=99), [])
        self.assertEqual(
            recommend_problem_snapshots(
                "그래프 최단 거리 다익스트라",
                store=self.store,
                min_score=99,
            ),
            [],
        )

    def test_explicit_empty_store_does_not_fall_back_to_default_catalog(self) -> None:
        empty_store = ProblemBankStore()

        self.assertEqual(search_problem_bank("JPA N+1 fetch join", store=empty_store), [])

    def test_missing_embedding_components_preserve_keyword_only_results(self) -> None:
        query = "오늘 JPA N+1 lazy loading과 fetch join 해결 방법을 공부했다."
        baseline = ProblemBankSearcher(self.store).search(
            query,
            field="db",
            difficulty="advanced",
            limit=3,
        )
        embedding_store = _embedding_store(self.store.records[:2])

        without_store = ProblemBankSearcher(
            self.store,
            client=_FakeEmbeddingClient((1.0, 0.0)),
        ).search(query, field="db", difficulty="advanced", limit=3)
        without_client = ProblemBankSearcher(
            self.store,
            embedding_store=embedding_store,
        ).search(query, field="db", difficulty="advanced", limit=3)
        empty_store = ProblemBankSearcher(
            self.store,
            embedding_store=ProblemEmbeddingStore(),
            client=_FakeEmbeddingClient((1.0, 0.0)),
        ).search(query, field="db", difficulty="advanced", limit=3)

        self.assertEqual(_hit_signature(without_store), _hit_signature(baseline))
        self.assertEqual(_hit_signature(without_client), _hit_signature(baseline))
        self.assertEqual(_hit_signature(empty_store), _hit_signature(baseline))

    def test_query_embedding_failure_preserves_keyword_only_results(self) -> None:
        query = "HTTP URI 상태 코드와 REST API 설계 원칙"
        baseline = ProblemBankSearcher(self.store).search(query, limit=3)

        hits = ProblemBankSearcher(
            self.store,
            embedding_store=_embedding_store(self.store.records),
            client=_FailingEmbeddingClient(),
        ).search(query, limit=3)

        self.assertEqual(_hit_signature(hits), _hit_signature(baseline))

    def test_dimension_mismatch_preserves_keyword_only_results(self) -> None:
        query = "HTTP URI 상태 코드와 REST API 설계 원칙"
        baseline = ProblemBankSearcher(self.store).search(query, limit=3)

        hits = ProblemBankSearcher(
            self.store,
            embedding_store=_embedding_store(self.store.records, dimensions=2),
            client=_FakeEmbeddingClient((1.0, 0.0, 0.0)),
        ).search(query, limit=3)

        self.assertEqual(_hit_signature(hits), _hit_signature(baseline))

    def test_partial_dimension_mismatch_preserves_keyword_only_results(self) -> None:
        query = "HTTP URI 상태 코드와 REST API 설계 원칙"
        baseline = ProblemBankSearcher(self.store).search(query, limit=3)
        records = tuple(self.store.records)

        hits = ProblemBankSearcher(
            self.store,
            embedding_store=_embedding_store(
                records,
                vectors={
                    records[0].problem_id: (1.0, 0.0),
                    records[1].problem_id: (1.0, 0.0, 0.0),
                },
                default=(1.0, 0.0),
            ),
            client=_FakeEmbeddingClient((1.0, 0.0)),
        ).search(query, limit=3)

        self.assertEqual(_hit_signature(hits), _hit_signature(baseline))

    def test_partial_zero_norm_preserves_keyword_only_results(self) -> None:
        query = "HTTP URI 상태 코드와 REST API 설계 원칙"
        baseline = ProblemBankSearcher(self.store).search(query, limit=3)
        records = tuple(self.store.records)

        hits = ProblemBankSearcher(
            self.store,
            embedding_store=_embedding_store(
                records,
                vectors={
                    records[0].problem_id: (1.0, 0.0),
                    records[1].problem_id: (0.0, 0.0),
                },
                default=(1.0, 0.0),
            ),
            client=_FakeEmbeddingClient((1.0, 0.0)),
        ).search(query, limit=3)

        self.assertEqual(_hit_signature(hits), _hit_signature(baseline))

    def test_vector_only_candidate_below_threshold_is_excluded(self) -> None:
        records = tuple(self.store.records)
        semantic_problem = next(problem for problem in records if problem.problem_id == 202)
        below_threshold = DEFAULT_VECTOR_SIMILARITY_THRESHOLD - 0.05
        embedding_store = _embedding_store(
            records,
            vectors={
                semantic_problem.problem_id: (
                    below_threshold,
                    math.sqrt(1.0 - below_threshold * below_threshold),
                )
            },
            default=(0.0, 1.0),
        )

        hits = search_problem_bank(
            "semantic sparse alpha",
            store=self.store,
            embedding_store=embedding_store,
            client=_FakeEmbeddingClient((1.0, 0.0)),
            min_score=99.0,
            limit=1,
        )

        self.assertEqual(hits, [])

    def test_hybrid_search_recalls_vector_only_problem(self) -> None:
        records = tuple(self.store.records)
        semantic_problem = next(problem for problem in records if problem.problem_id == 202)
        embedding_store = _embedding_store(
            records,
            vectors={semantic_problem.problem_id: (1.0, 0.0)},
            default=(0.0, 1.0),
        )

        hits = search_problem_bank(
            "semantic sparse alpha",
            store=self.store,
            embedding_store=embedding_store,
            client=_FakeEmbeddingClient((1.0, 0.0)),
            min_score=99.0,
            limit=1,
        )

        self.assertEqual([hit.problem.problem_id for hit in hits], [202])
        self.assertEqual(hits[0].matched_terms, ())
        self.assertAlmostEqual(hits[0].score, 0.35)

    def test_hybrid_weights_change_ranking_predictably(self) -> None:
        records = tuple(self.store.records)
        semantic_problem = next(problem for problem in records if problem.problem_id == 202)
        embedding_store = _embedding_store(
            records,
            vectors={semantic_problem.problem_id: (1.0, 0.0)},
            default=(0.0, 1.0),
        )

        keyword_first = search_problem_bank(
            "JPA N+1 fetch join",
            store=self.store,
            embedding_store=embedding_store,
            client=_FakeEmbeddingClient((1.0, 0.0)),
            keyword_weight=1.0,
            vector_weight=0.0,
            min_score=1.0,
            limit=2,
        )
        vector_first = search_problem_bank(
            "JPA N+1 fetch join",
            store=self.store,
            embedding_store=embedding_store,
            client=_FakeEmbeddingClient((1.0, 0.0)),
            keyword_weight=0.0,
            vector_weight=1.0,
            min_score=1.0,
            limit=2,
        )

        self.assertEqual(keyword_first[0].problem.problem_id, 101)
        self.assertEqual(vector_first[0].problem.problem_id, 202)

    def test_hybrid_tie_break_is_deterministic(self) -> None:
        store = ProblemBankStore(
            (
                _problem(2, "b-source", "Alpha topic", "Alpha answer"),
                _problem(1, "c-source", "Beta topic", "Beta answer"),
                _problem(1, "a-source", "Gamma topic", "Gamma answer"),
            )
        )
        embedding_store = _embedding_store(store.records, default=(1.0, 0.0))

        hits = search_problem_bank(
            "semantic sparse theta",
            store=store,
            embedding_store=embedding_store,
            client=_FakeEmbeddingClient((1.0, 0.0)),
            min_score=99.0,
            limit=3,
        )

        self.assertEqual(
            [(hit.problem.problem_id, hit.problem.source_key) for hit in hits],
            [(1, "sha256:a-source"), (1, "sha256:c-source"), (2, "sha256:b-source")],
        )

class _FakeEmbeddingClient:
    def __init__(self, query_embedding: tuple[float, ...]):
        self.query_embedding = query_embedding

    def embed_document(self, text: str) -> tuple[float, ...]:
        return self.query_embedding

    def embed_query(self, text: str) -> tuple[float, ...]:
        return self.query_embedding


class _FailingEmbeddingClient:
    def embed_document(self, text: str) -> tuple[float, ...]:
        raise RuntimeError("fake embedder failure")

    def embed_query(self, text: str) -> tuple[float, ...]:
        raise RuntimeError("fake embedder failure")


def _embedding_store(
    records: tuple[ProblemRecord, ...],
    *,
    vectors: dict[int, tuple[float, ...]] | None = None,
    default: tuple[float, ...] | None = None,
    dimensions: int = 2,
) -> ProblemEmbeddingStore:
    fallback = default if default is not None else tuple(1.0 if index == 0 else 0.0 for index in range(dimensions))
    return ProblemEmbeddingStore(
        items=tuple(
            EmbeddedProblem(
                problem=problem,
                embedding=(vectors or {}).get(problem.problem_id, fallback),
            )
            for problem in records
        ),
        expected_model="fake",
        expected_dimensionality=len(fallback),
    )


def _hit_signature(hits):
    return tuple(
        (hit.problem.problem_id, hit.score, hit.matched_terms)
        for hit in hits
    )


def _problem(
    problem_id: int,
    source_suffix: str,
    question: str,
    model_answer: str,
    *,
    difficulty: str = "intermediate",
) -> ProblemRecord:
    return ProblemRecord(
        problem_id=problem_id,
        source_key=f"sha256:{source_suffix}",
        question=question,
        model_answer=model_answer,
        difficulty=difficulty,
        primary_field="cs",
        fields=("cs",),
        score_allocation={
            "db": 0,
            "algorithm": 0,
            "cs": 7,
            "network": 0,
            "framework": 0,
        },
        source_path=f"fixtures/{source_suffix}.md",
        heading_path=("Fixture", question),
        rubric=ProblemRubric(),
        content_hash=f"sha256:{source_suffix}-content",
    )


if __name__ == "__main__":
    unittest.main()
