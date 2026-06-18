from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from app.rag.problem_bank_store import ProblemBankStore
from app.rag.problem_embedding_store import load_problem_embedding_store
from app.rag.schemas import ProblemRecord, ProblemRubric


class ProblemEmbeddingStoreTest(unittest.TestCase):
    def test_loads_matching_embeddings_and_collects_join_or_stale_issues(self) -> None:
        problems = tuple(_problem(index) for index in range(1, 6))
        rows = [
            _embedding_row(problems[0]),
            _embedding_row(problems[1], content_hash="sha256:stale"),
            _embedding_row(problems[2], model="old-model"),
            _embedding_row(problems[3], output_dimensionality=3, embedding=[1.0, 0.0, 0.0]),
            _embedding_row(_problem(999)),
        ]

        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "problem-embeddings.jsonl"
            path.write_text(
                "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows),
                encoding="utf-8",
            )

            embedding_store = load_problem_embedding_store(
                store=ProblemBankStore(problems),
                embeddings_path=path,
                expected_model="fake-embedding-model",
                expected_dimensionality=4,
            )

        self.assertEqual([item.problem.problem_id for item in embedding_store.items], [1])
        self.assertEqual(embedding_store.embedding_for(1, problems[0].source_key), (1.0, 0.0, 0.0, 0.0))
        reasons = [issue.reason for issue in embedding_store.issues]
        self.assertIn("stale_content_hash", reasons)
        self.assertIn("stale_model", reasons)
        self.assertIn("stale_output_dimensionality", reasons)
        self.assertIn("unknown_problem", reasons)
        self.assertIn("missing_embedding", reasons)

    def test_join_requires_matching_problem_id_and_source_key(self) -> None:
        problem = _problem(1)
        mismatched_row = _embedding_row(problem)
        mismatched_row["sourceKey"] = "sha256:different-source"

        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "problem-embeddings.jsonl"
            path.write_text(json.dumps(mismatched_row, ensure_ascii=False) + "\n", encoding="utf-8")

            embedding_store = load_problem_embedding_store(
                store=ProblemBankStore((problem,)),
                embeddings_path=path,
                expected_model="fake-embedding-model",
                expected_dimensionality=4,
            )

        self.assertEqual(embedding_store.items, ())
        self.assertIn("unknown_problem", [issue.reason for issue in embedding_store.issues])
        self.assertIn("missing_embedding", [issue.reason for issue in embedding_store.issues])

    def test_missing_or_empty_files_return_empty_store_without_crashing(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            missing_catalog = temp_path / "missing-problems.jsonl"
            missing_embeddings = temp_path / "missing-embeddings.jsonl"
            empty_catalog = temp_path / "empty-problems.jsonl"
            empty_embeddings = temp_path / "empty-embeddings.jsonl"
            empty_catalog.write_text("", encoding="utf-8")
            empty_embeddings.write_text("", encoding="utf-8")

            missing_store = load_problem_embedding_store(
                catalog_path=missing_catalog,
                embeddings_path=missing_embeddings,
                expected_model="fake-embedding-model",
                expected_dimensionality=4,
            )
            empty_store = load_problem_embedding_store(
                catalog_path=empty_catalog,
                embeddings_path=empty_embeddings,
                expected_model="fake-embedding-model",
                expected_dimensionality=4,
            )

        self.assertEqual(missing_store.items, ())
        self.assertEqual(missing_store.issues, ())
        self.assertEqual(empty_store.items, ())
        self.assertEqual(empty_store.issues, ())


def _problem(index: int) -> ProblemRecord:
    return ProblemRecord(
        problem_id=index,
        source_key=f"sha256:problem-{index}",
        question=f"JPA N+1 문제 {index}를 설명하시오.",
        model_answer="JPA N+1은 지연 로딩 관계를 반복 접근할 때 추가 쿼리가 발생하는 문제입니다.",
        difficulty="intermediate",
        primary_field="db",
        fields=("db", "framework"),
        score_allocation={
            "db": 7,
            "algorithm": 0,
            "cs": 0,
            "network": 0,
            "framework": 7,
        },
        source_path=f"fixtures/problem-{index}.md",
        heading_path=("Fixture", f"Problem {index}"),
        rubric=ProblemRubric(
            must_mention=("N+1", "Lazy Loading"),
            optional_mention=("fetch join",),
            must_not_confuse=("SQL Injection",),
        ),
        content_hash=f"sha256:problem-{index}-content",
    )


def _embedding_row(
    problem: ProblemRecord,
    *,
    content_hash: str | None = None,
    model: str = "fake-embedding-model",
    output_dimensionality: int = 4,
    embedding: list[float] | None = None,
) -> dict[str, object]:
    return {
        "problemId": problem.problem_id,
        "sourceKey": problem.source_key,
        "contentHash": content_hash or problem.content_hash,
        "model": model,
        "outputDimensionality": output_dimensionality,
        "sourcePath": problem.source_path,
        "headingPath": list(problem.heading_path),
        "embedding": embedding or [1.0, 0.0, 0.0, 0.0],
    }


if __name__ == "__main__":
    unittest.main()
