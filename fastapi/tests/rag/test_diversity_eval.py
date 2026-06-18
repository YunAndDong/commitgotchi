from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from app.rag.concept_store import ConceptCatalogStore, FASTAPI_ROOT
from app.rag.embedding_store import ConceptEmbeddingStore
from app.rag.problem_bank_store import ProblemBankStore
from app.rag.schemas import ConceptChunkRecord, ConceptNeighbors, ProblemRecord, ProblemRubric

from scripts.rag_diversity_eval import (
    FakeEmbeddingClient,
    RetrievedItem,
    calculate_diversity_metrics,
    calculate_relevance_metrics,
    evaluate_concept_queries,
    generate_fake_embedding_store,
    generate_tier_c_queries,
    load_tier_a_queries,
    validate_tier_a_fixture,
)


class DiversityEvalTest(unittest.TestCase):
    def test_final_tier_a_fixture_matches_catalog_sources(self) -> None:
        queries = load_tier_a_queries(FASTAPI_ROOT / "tests" / "fixtures" / "rag" / "eval" / "queries.jsonl")
        summary = validate_tier_a_fixture(
            queries,
            _load_catalog_source_paths(FASTAPI_ROOT / "data" / "rag" / "catalog" / "chunks.jsonl"),
        )

        self.assertEqual(summary.record_count, 91)
        self.assertEqual(summary.catalog_source_count, 91)
        self.assertEqual(summary.missing_source_paths, ())
        self.assertEqual(summary.extra_source_paths, ())
        self.assertEqual(summary.duplicate_source_paths, ())
        self.assertTrue(summary.is_sorted)

    def test_tier_a_validator_rejects_missing_anchor_relevance(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "queries.jsonl"
            path.write_text(
                json.dumps(
                    {
                        "queryId": "q-a-md",
                        "sourcePath": "a.md",
                        "sourceTier": "core",
                        "reportText": "A 문서가 답할 수 있는 질문이다.",
                        "expectedTopics": ["A"],
                        "expectedFields": ["cs"],
                        "relevantSourcePaths": ["b.md"],
                        "relevanceGrades": {},
                        "notes": "",
                    },
                    ensure_ascii=False,
                )
                + "\n",
                encoding="utf-8",
            )

            queries = load_tier_a_queries(path)
            with self.assertRaisesRegex(ValueError, "relevantSourcePaths"):
                validate_tier_a_fixture(queries, ("a.md",), expected_count=1)

    def test_tier_c_sweep_is_label_free_and_deterministic(self) -> None:
        store = ConceptCatalogStore(
            (
                _chunk("a-0", "01-cs/a.md", ("CS", "Process"), 0, "process thread memory", ("cs",)),
                _chunk("a-1", "01-cs/a.md", ("CS", "Thread"), 1, "thread scheduling", ("cs",)),
                _chunk("b-0", "02-db/b.md", ("DB", "Index"), 0, "index btree query", ("db",)),
            )
        )

        first = generate_tier_c_queries(store.records, min_queries=4, max_queries=5)
        second = generate_tier_c_queries(store.records, min_queries=4, max_queries=5)

        self.assertEqual(first, second)
        self.assertGreaterEqual(len(first), 4)
        self.assertLessEqual(len(first), 5)
        for query in first:
            self.assertEqual(query.relevant_source_paths, ())
            self.assertEqual(query.relevance_grades, {})

    def test_diversity_metrics_capture_source_concentration(self) -> None:
        concentrated = {
            "q1": (
                RetrievedItem("q1", 1, "a1", "a.md", ("cs",)),
                RetrievedItem("q1", 2, "a2", "a.md", ("cs",)),
            )
        }
        spread = {
            "q1": (
                RetrievedItem("q1", 1, "a1", "a.md", ("cs",)),
                RetrievedItem("q1", 2, "b1", "b.md", ("db",)),
            )
        }

        concentrated_metrics = calculate_diversity_metrics(["q1"], concentrated, total_source_paths=("a.md", "b.md"))
        spread_metrics = calculate_diversity_metrics(["q1"], spread, total_source_paths=("a.md", "b.md"))

        self.assertEqual(concentrated_metrics["aggregate"]["avgDistinctSourceCount"], 1.0)
        self.assertEqual(concentrated_metrics["aggregate"]["avgMostCommonSourceRatio"], 1.0)
        self.assertEqual(concentrated_metrics["aggregate"]["avgSourceHHI"], 1.0)
        self.assertEqual(spread_metrics["aggregate"]["avgDistinctSourceCount"], 2.0)
        self.assertLess(spread_metrics["aggregate"]["avgSourceHHI"], concentrated_metrics["aggregate"]["avgSourceHHI"])
        self.assertEqual(spread_metrics["aggregate"]["catalogCoverage"], 1.0)

    def test_relevance_guard_marks_missed_relevant_source(self) -> None:
        queries = (
            _query("q-hit", "hit.md"),
            _query("q-miss", "miss.md"),
        )
        results = {
            "q-hit": (RetrievedItem("q-hit", 1, "hit-1", "hit.md", ("cs",)),),
            "q-miss": (RetrievedItem("q-miss", 1, "other-1", "other.md", ("cs",)),),
        }

        metrics = calculate_relevance_metrics(queries, results, k=3)

        self.assertEqual(metrics["aggregate"]["hitRateAtK"], 0.5)
        self.assertEqual(metrics["aggregate"]["recallAtK"], 0.5)
        self.assertEqual(metrics["aggregate"]["sourceCoverage"], 0.5)
        self.assertEqual(metrics["missedQueryIds"], ["q-miss"])
        self.assertNotIn("ndcgAtK", metrics["aggregate"])

    def test_empty_results_are_zero_safe(self) -> None:
        diversity = calculate_diversity_metrics(["q-empty"], {"q-empty": ()}, total_source_paths=("a.md",))
        relevance = calculate_relevance_metrics((_query("q-empty", "a.md"),), {"q-empty": ()}, k=5)

        self.assertEqual(diversity["aggregate"]["avgResultCount"], 0.0)
        self.assertEqual(diversity["aggregate"]["catalogCoverage"], 0.0)
        self.assertEqual(relevance["aggregate"]["hitRateAtK"], 0.0)
        self.assertEqual(relevance["aggregate"]["mrr"], 0.0)

    def test_fake_embedder_evaluates_concept_queries_without_api(self) -> None:
        store = ConceptCatalogStore(
            (
                _chunk("jpa", "03-framework/jpa.md", ("JPA",), 0, "JPA N+1 lazy loading fetch join", ("db", "framework")),
                _chunk("http", "02-network/http.md", ("HTTP",), 0, "HTTP status code REST API", ("network",)),
            )
        )
        embedding_store = generate_fake_embedding_store(store)

        results = evaluate_concept_queries(
            (_query("q-jpa", "03-framework/jpa.md", report_text="JPA N+1 fetch join이 헷갈린다.", fields=("db", "framework")),),
            store=store,
            embedding_store=embedding_store,
            client=FakeEmbeddingClient(),
            top_k=1,
            max_neighborhood_chunks=0,
        )

        self.assertEqual(results.top_k["q-jpa"][0].source_path, "03-framework/jpa.md")
        self.assertEqual(results.bundle["q-jpa"][0].source_path, "03-framework/jpa.md")


def _load_catalog_source_paths(path: Path) -> tuple[str, ...]:
    source_paths: set[str] = set()
    with path.open("r", encoding="utf-8") as file:
        for line in file:
            if line.strip():
                source_paths.add(json.loads(line)["sourcePath"])
    return tuple(sorted(source_paths))


def _chunk(
    suffix: str,
    source_path: str,
    heading_path: tuple[str, ...],
    index: int,
    text: str,
    field_hints: tuple[str, ...],
) -> ConceptChunkRecord:
    return ConceptChunkRecord(
        chunk_id=f"concept:sha256:{suffix}",
        source_path=source_path,
        source_root="/tmp/source",
        heading_path=heading_path,
        heading_level=len(heading_path),
        chunk_index=index,
        text=text,
        content_hash=f"sha256:{suffix}",
        field_hints=field_hints,
        neighbors=ConceptNeighbors(),
    )


def _query(
    query_id: str,
    source_path: str,
    *,
    report_text: str = "면접 공부 중 관련 개념이 헷갈린다.",
    fields: tuple[str, ...] = ("cs",),
):
    from scripts.rag_diversity_eval import EvalQuery

    return EvalQuery(
        query_id=query_id,
        report_text=report_text,
        expected_topics=("fixture",),
        expected_fields=fields,
        source_path=source_path,
        source_tier="core",
        relevant_source_paths=(source_path,),
        relevance_grades={},
        notes="",
    )


if __name__ == "__main__":
    unittest.main()
