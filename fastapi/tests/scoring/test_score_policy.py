from __future__ import annotations

import math
import unittest

from app.scoring.policy import (
    SCORE_FIELDS,
    active_score_fields,
    clamp_confidence,
    clamp_score_delta,
    sanitize_score_allocation,
    zero_score_vector,
)


class ScorePolicyTest(unittest.TestCase):
    def test_score_allocation_always_contains_all_score_fields(self) -> None:
        allocation = sanitize_score_allocation({"db": 7, "unknown": 9})

        self.assertEqual(tuple(allocation), SCORE_FIELDS)
        self.assertEqual(allocation["db"], 7)
        self.assertEqual(allocation["algorithm"], 0)
        self.assertNotIn("unknown", allocation)

    def test_score_allocation_is_clamped_per_field_without_total_cap(self) -> None:
        allocation = sanitize_score_allocation(
            {
                "db": 99,
                "algorithm": 10,
                "cs": "10",
                "network": 10.8,
                "framework": -3,
            }
        )

        self.assertEqual(
            allocation,
            {
                "db": 10,
                "algorithm": 10,
                "cs": 10,
                "network": 10,
                "framework": 0,
            },
        )

    def test_total_fifty_point_vector_is_valid(self) -> None:
        allocation = sanitize_score_allocation(
            {field_name: 10 for field_name in SCORE_FIELDS}
        )
        delta = clamp_score_delta(
            {field_name: 10 for field_name in SCORE_FIELDS},
            allocation,
        )

        self.assertEqual(sum(allocation.values()), 50)
        self.assertEqual(sum(delta.values()), 50)

    def test_score_delta_never_exceeds_sanitized_allocation(self) -> None:
        allocation = {
            "db": 7,
            "algorithm": 3,
            "cs": 0,
            "network": 12,
            "framework": -1,
        }
        delta = clamp_score_delta(
            {
                "db": 99,
                "algorithm": 4,
                "cs": 5,
                "network": 99,
                "framework": 10,
                "unknown": 10,
            },
            allocation,
        )

        self.assertEqual(
            delta,
            {
                "db": 7,
                "algorithm": 3,
                "cs": 0,
                "network": 10,
                "framework": 0,
            },
        )

    def test_zero_vector_and_active_fields_are_policy_ordered(self) -> None:
        self.assertEqual(tuple(zero_score_vector()), SCORE_FIELDS)
        self.assertEqual(active_score_fields({"db": 1, "framework": 10}), ["db", "framework"])

    def test_confidence_is_clamped_to_zero_to_one(self) -> None:
        self.assertEqual(clamp_confidence(1.5), 1.0)
        self.assertEqual(clamp_confidence(-0.1), 0.0)
        self.assertEqual(clamp_confidence("0.75"), 0.75)
        self.assertEqual(clamp_confidence(math.nan), 0.0)


if __name__ == "__main__":
    unittest.main()
