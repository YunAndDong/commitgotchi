from __future__ import annotations

import math
import unittest

from app.rag.eval_metrics import (
    aggregate_metric,
    bootstrap_paired_delta,
    evaluate_quality_gate,
    intra_list_diversity,
    relevance_metrics_at_k,
    source_gini,
    source_hhi,
    summarize_diversity_by_query,
)


class EvalMetricsTest(unittest.TestCase):
    def test_relevance_metrics_use_binary_sources_when_grades_are_empty(self) -> None:
        results = (
            {"itemId": "chunk-a", "sourcePath": "a.md", "fieldHints": ("db",)},
            {"itemId": "chunk-x", "sourcePath": "x.md", "fieldHints": ("cs",)},
            {"itemId": "chunk-b", "sourcePath": "b.md", "fieldHints": ("network",)},
        )

        metrics = relevance_metrics_at_k(
            results,
            relevant_source_paths=("a.md", "b.md"),
            relevance_grades={},
            expected_fields=("db",),
            k=3,
        )

        expected_binary_ndcg = (1.0 + 1.0 / math.log2(4)) / (1.0 + 1.0 / math.log2(3))
        self.assertEqual(metrics["recallAtK"], 1.0)
        self.assertAlmostEqual(metrics["precisionAtK"], 2 / 3)
        self.assertEqual(metrics["mrr"], 1.0)
        self.assertAlmostEqual(metrics["ndcgAtK"], expected_binary_ndcg)
        self.assertEqual(metrics["fieldHitAtK"], 1.0)

    def test_relevance_metrics_use_item_or_source_grades_when_present(self) -> None:
        results = (
            {"itemId": "chunk-b", "sourcePath": "b.md", "fieldHints": ("db",)},
            {"itemId": "chunk-a", "sourcePath": "a.md", "fieldHints": ("db",)},
        )

        metrics = relevance_metrics_at_k(
            results,
            relevant_source_paths=("a.md", "b.md"),
            relevance_grades={"chunk-a": 3, "b.md": 1},
            expected_fields=("db",),
            k=2,
        )

        expected_ndcg = ((1 / math.log2(2)) + ((2**3 - 1) / math.log2(3))) / (
            ((2**3 - 1) / math.log2(2)) + (1 / math.log2(3))
        )
        self.assertAlmostEqual(metrics["ndcgAtK"], expected_ndcg)

    def test_diversity_metrics_capture_concentration_gini_and_ild(self) -> None:
        concentrated = ("a.md", "a.md", "a.md", "a.md")
        spread = ("a.md", "a.md", "b.md", "b.md")

        self.assertEqual(source_hhi(concentrated), 1.0)
        self.assertLess(source_hhi(spread), source_hhi(concentrated))
        self.assertGreater(source_gini(concentrated, total_source_paths=("a.md", "b.md")), 0.0)
        self.assertEqual(source_gini(spread, total_source_paths=("a.md", "b.md")), 0.0)
        self.assertEqual(intra_list_diversity(((1.0, 0.0), (1.0, 0.0))), 0.0)
        self.assertEqual(intra_list_diversity(((1.0, 0.0), (0.0, 1.0))), 1.0)

    def test_query_diversity_summary_includes_neighbor_ratio_and_catalog_coverage(self) -> None:
        results = {
            "q1": (
                {"itemId": "a1", "sourcePath": "a.md", "fieldHints": ("db",)},
                {"itemId": "b1", "sourcePath": "b.md", "fieldHints": ("network",)},
            )
        }
        neighborhoods = {
            "q1": (
                {"itemId": "a2", "sourcePath": "a.md", "fieldHints": ("db",)},
                {"itemId": "c1", "sourcePath": "c.md", "fieldHints": ("cs",)},
            )
        }
        embeddings = {"a1": (1.0, 0.0), "b1": (0.0, 1.0)}

        summary = summarize_diversity_by_query(
            ("q1",),
            results,
            total_source_paths=("a.md", "b.md", "c.md"),
            embeddings_by_item_id=embeddings,
            neighborhoods_by_query=neighborhoods,
            seed_results_by_query=results,
        )

        aggregate = summary["aggregate"]
        self.assertEqual(aggregate["avgDistinctSourceCount"], 2.0)
        self.assertEqual(aggregate["avgILD"], 1.0)
        self.assertEqual(aggregate["catalogCoverage"], 2 / 3)
        self.assertEqual(aggregate["avgSameSourceNeighborRatio"], 0.5)

    def test_bootstrap_delta_and_gate_cover_pass_and_fail_paths(self) -> None:
        improved = bootstrap_paired_delta([1.0, 1.0, 1.0], [2.0, 2.0, 2.0], resamples=100, seed=7)
        unchanged = bootstrap_paired_delta([1.0, 1.0, 1.0], [1.0, 1.0, 1.0], resamples=100, seed=7)
        worse = bootstrap_paired_delta([1.0, 1.0, 1.0], [0.8, 0.8, 0.8], resamples=100, seed=7)

        self.assertEqual(improved["meanDelta"], 1.0)
        self.assertGreater(improved["ciLow"], 0.0)
        self.assertEqual(unchanged["meanDelta"], 0.0)
        self.assertLess(worse["meanDelta"], 0.0)

        passing_gate = evaluate_quality_gate(
            diversity_stats={
                "avgDistinctSourceCount": improved,
                "avgILD": improved,
                "avgFieldCoverage": unchanged,
            },
            relevance_stats={
                "recallAtK": unchanged,
                "ndcgAtK": unchanged,
            },
        )
        failing_gate = evaluate_quality_gate(
            diversity_stats={
                "avgDistinctSourceCount": improved,
                "avgILD": unchanged,
                "avgFieldCoverage": unchanged,
            },
            relevance_stats={
                "recallAtK": worse,
                "ndcgAtK": unchanged,
            },
        )

        self.assertTrue(passing_gate["passed"])
        self.assertFalse(failing_gate["passed"])
        self.assertEqual(aggregate_metric([{"value": 1.0}, {"value": 3.0}], "value"), 2.0)


if __name__ == "__main__":
    unittest.main()
