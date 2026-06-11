from __future__ import annotations

import unittest

from app.rag.schemas import (
    SCORE_FIELDS,
    ProblemRecord,
    ProblemRubric,
    validate_problem_record,
    validate_score_allocation,
)
from app.rag.text import score_allocation_for_fields


class ProblemSchemaTest(unittest.TestCase):
    def test_score_allocation_allows_independent_tens(self) -> None:
        allocation = {field_name: 10 for field_name in SCORE_FIELDS}

        self.assertEqual(validate_score_allocation(allocation), [])
        self.assertEqual(sum(allocation.values()), 50)

    def test_score_allocation_rejects_missing_extra_and_out_of_range_values(self) -> None:
        invalid = {
            "db": -1,
            "algorithm": 11,
            "cs": 0,
            "network": 0,
            "extra": 3,
        }

        errors = validate_score_allocation(invalid)

        self.assertTrue(any("missing score fields" in error for error in errors))
        self.assertTrue(any("unknown score fields" in error for error in errors))
        self.assertTrue(any("db score" in error for error in errors))
        self.assertTrue(any("algorithm score" in error for error in errors))

    def test_difficulty_controls_related_field_max_without_total_cap(self) -> None:
        allocation = score_allocation_for_fields(["db", "framework"], "advanced")

        self.assertEqual(allocation["db"], 10)
        self.assertEqual(allocation["framework"], 10)
        self.assertEqual(sum(allocation.values()), 20)

    def test_valid_problem_record_passes_validation(self) -> None:
        problem = ProblemRecord(
            problem_id=123,
            source_key="sha256:abc",
            question="N+1 문제란 무엇인가요?",
            model_answer="N+1 문제는 연관 엔티티 조회 시 추가 쿼리가 반복 발생하는 문제입니다.",
            difficulty="advanced",
            primary_field="db",
            fields=("db", "framework"),
            score_allocation={
                "db": 10,
                "algorithm": 0,
                "cs": 0,
                "network": 0,
                "framework": 10,
            },
            source_path="qna-database.md",
            heading_path=("Database", "N+1 문제란 무엇인가요?"),
            rubric=ProblemRubric(must_mention=("N+1", "fetch join")),
            content_hash="sha256:def",
        )

        self.assertEqual(validate_problem_record(problem), [])


if __name__ == "__main__":
    unittest.main()
