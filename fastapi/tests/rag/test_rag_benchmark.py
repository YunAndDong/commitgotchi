from __future__ import annotations

import unittest

from app.rag.concept_store import ConceptCatalogStore
from app.rag.problem_bank_store import ProblemBankStore
from app.rag.schemas import ConceptChunkRecord, ConceptNeighbors, ProblemRecord, ProblemRubric
from scripts.rag_benchmark import ablation_configs, evaluate_ablation_configs, render_markdown_report
from scripts.rag_diversity_eval import (
    EvalQuery,
    FakeEmbeddingClient,
    generate_fake_embedding_store,
    generate_fake_problem_embedding_store,
)


class RagBenchmarkTest(unittest.TestCase):
    def test_ablation_configs_are_cumulative_and_document_limitations(self) -> None:
        configs = ablation_configs()

        self.assertEqual(tuple(config.key for config in configs), ("B0", "S2", "S3", "S4", "S5_6"))
        self.assertIsNone(configs[0].max_per_source)
        self.assertEqual(configs[1].max_per_source, 2)
        self.assertEqual(configs[2].min_cross_source, 2)
        self.assertEqual(configs[3].max_subqueries, 3)
        self.assertTrue(configs[4].use_problem_hybrid)
        self.assertTrue(any("S3" in item for item in configs[2].limitations))

    def test_evaluate_ablation_configs_uses_public_toggles_and_fake_embeddings(self) -> None:
        catalog_store = ConceptCatalogStore(
            (
                _chunk("a1", "a.md", 0, "alpha beta core one", ("db",)),
                _chunk("a2", "a.md", 1, "alpha beta core two", ("db",)),
                _chunk("a3", "a.md", 2, "alpha beta core three", ("db",)),
                _chunk("b1", "b.md", 0, "alpha beta support", ("network",)),
            )
        )
        problem_store = ProblemBankStore(
            (
                _problem(1, "a", "alpha beta database", "alpha beta", "a.md", ("db",)),
                _problem(2, "b", "alpha beta network", "alpha beta", "b.md", ("network",)),
            )
        )
        queries = (
            EvalQuery(
                query_id="q-alpha",
                report_text="alpha beta가 헷갈린다.",
                expected_fields=("db",),
                expected_topics=("alpha",),
                source_path="a.md",
                source_tier="core",
                relevant_source_paths=("a.md",),
                relevance_grades={},
            ),
        )

        report = evaluate_ablation_configs(
            queries,
            catalog_store=catalog_store,
            problem_store=problem_store,
            concept_embedding_store=generate_fake_embedding_store(catalog_store),
            problem_embedding_store=generate_fake_problem_embedding_store(problem_store),
            client=FakeEmbeddingClient(),
            top_k=3,
            problem_top_k=2,
            max_neighborhood_chunks=2,
            bootstrap_resamples=100,
        )

        baseline_distinct = report["ablations"]["B0"]["tierA"]["conceptSearch"]["topK"]["diversity"]["aggregate"][
            "avgDistinctSourceCount"
        ]
        s2_distinct = report["ablations"]["S2"]["tierA"]["conceptSearch"]["topK"]["diversity"]["aggregate"][
            "avgDistinctSourceCount"
        ]

        self.assertLess(baseline_distinct, s2_distinct)
        self.assertFalse(report["metadata"]["callsGemini"])
        self.assertEqual(report["metadata"]["embeddingMode"], "fake")
        self.assertIn("gate", report)
        self.assertTrue(report["caseStudies"])

    def test_markdown_report_states_scope_and_limits(self) -> None:
        markdown = render_markdown_report(
            {
                "metadata": {
                    "generatedAt": "2026-06-19T00:00:00+00:00",
                    "gitCommit": "abc1234",
                    "embeddingMode": "fake",
                    "callsGemini": False,
                    "topK": 5,
                    "problemTopK": 3,
                    "catalogChunkCount": 4,
                    "catalogDistinctSourceCount": 2,
                    "problemCount": 2,
                    "problemDistinctSourceCount": 2,
                    "thresholds": {"relevanceEpsilon": 0.02, "maxWorsenedQueryRatio": 0.1},
                    "outputJsonPath": "data/rag/reports/rag-enhancement-benchmark.json",
                    "outputMarkdownPath": "data/rag/reports/rag-enhancement-benchmark.md",
                },
                "ablations": {
                    "B0": _minimal_ablation("B0", 1.0),
                    "S5_6": _minimal_ablation("S5_6", 2.0),
                },
                "comparisons": {
                    "finalVsBaseline": {
                        "conceptSearch.topK": {
                            "avgDistinctSourceCount": {"meanDelta": 1.0, "ciLow": 1.0, "ciHigh": 1.0},
                            "recallAtK": {"meanDelta": 0.0, "ciLow": 0.0, "ciHigh": 0.0},
                        }
                    }
                },
                "gate": {"passed": True, "checks": ["ok"], "failures": []},
                "caseStudies": [],
                "limitations": ["fake-hash embedding과 binary relevance 한계"],
            }
        )

        self.assertIn("답변 생성 품질 평가는 아니다", markdown)
        self.assertIn("fake-hash", markdown)
        self.assertIn("binary relevance", markdown)


def _minimal_ablation(key: str, distinct: float) -> dict:
    section = {
        "diversity": {
            "aggregate": {
                "avgDistinctSourceCount": distinct,
                "avgILD": 0.0,
                "avgSourceHHI": 1.0,
                "avgSourceGini": 0.0,
                "avgFieldCoverage": 1.0,
                "avgFolderCoverage": 1.0,
                "catalogCoverage": 0.5,
                "avgSameSourceNeighborRatio": 0.0,
            },
            "perQuery": [],
        },
        "relevance": {
            "aggregate": {
                "recallAtK": 1.0,
                "precisionAtK": 1.0,
                "mrr": 1.0,
                "ndcgAtK": 1.0,
                "fieldHitAtK": 1.0,
            },
            "perQuery": [],
        },
    }
    return {
        "key": key,
        "description": key,
        "tierA": {
            "conceptSearch": {"topK": section, "evidenceBundle": section},
            "problemBankSearch": {"topK": section},
        },
    }


def _chunk(
    chunk_id: str,
    source_path: str,
    chunk_index: int,
    text: str,
    field_hints: tuple[str, ...],
) -> ConceptChunkRecord:
    return ConceptChunkRecord(
        chunk_id=f"concept:sha256:{chunk_id}",
        source_path=source_path,
        source_root="/tmp/source",
        heading_path=("Fixture", source_path),
        heading_level=2,
        chunk_index=chunk_index,
        text=text,
        content_hash=f"sha256:{chunk_id}",
        field_hints=field_hints,
        neighbors=ConceptNeighbors(),
    )


def _problem(
    problem_id: int,
    key: str,
    question: str,
    model_answer: str,
    source_path: str,
    fields: tuple[str, ...],
) -> ProblemRecord:
    score_allocation = {field: 0 for field in ("db", "algorithm", "cs", "network", "framework")}
    for field in fields:
        score_allocation[field] = 5
    return ProblemRecord(
        problem_id=problem_id,
        source_key=f"sha256:{key}",
        question=question,
        model_answer=model_answer,
        difficulty="basic",
        primary_field=fields[0],
        fields=fields,
        score_allocation=score_allocation,
        source_path=source_path,
        heading_path=("Fixture", question),
        rubric=ProblemRubric(must_mention=("alpha", "beta")),
        content_hash=f"sha256:{key}:content",
    )


if __name__ == "__main__":
    unittest.main()
