from __future__ import annotations

import unittest

from app.rag.problem_bank_search import (
    ProblemBankSearcher,
    problem_to_recommendation_snapshot,
    recommend_problem_snapshots,
    search_problem_bank,
)
from app.rag.problem_bank_store import FASTAPI_ROOT, ProblemBankStore, read_problem_jsonl


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


if __name__ == "__main__":
    unittest.main()
