from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from app.rag.build_problem_bank_embeddings import build_problem_bank_embeddings
from app.rag.problem_bank_store import FASTAPI_ROOT, read_problem_jsonl
from app.rag.problem_embeddings import (
    DEFAULT_PROBLEM_EMBEDDINGS_PATH,
    build_problem_embedding_input,
    make_problem_embedding_record,
    read_problem_embedding_jsonl,
)
from app.rag.schemas import ProblemRecord, ProblemRubric


FIXTURE_ROOT = FASTAPI_ROOT / "tests" / "fixtures" / "rag" / "problem_bank_store"
PROBLEMS_FIXTURE = FIXTURE_ROOT / "problems.jsonl"


class FakeEmbeddingClient:
    def __init__(self) -> None:
        self.document_inputs: list[str] = []

    def embed_document(self, text: str) -> tuple[float, ...]:
        self.document_inputs.append(text)
        seed = float(len(self.document_inputs))
        return (seed, 0.0, 0.0, 1.0)

    def embed_query(self, text: str) -> tuple[float, ...]:
        raise AssertionError("problem embedding build should not embed queries")


class ProblemBankEmbeddingsTest(unittest.TestCase):
    def test_default_sidecar_path_matches_problem_catalog_location(self) -> None:
        self.assertEqual(
            DEFAULT_PROBLEM_EMBEDDINGS_PATH.as_posix(),
            (FASTAPI_ROOT / "data" / "rag" / "catalog" / "problem-embeddings.jsonl").as_posix(),
        )

    def test_builds_problem_embedding_jsonl_with_required_record_fields(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            out_dir = Path(temp_dir)
            embeddings_path = out_dir / "problem-embeddings.jsonl"
            manifest_path = out_dir / "problem-embeddings-manifest.json"
            summary_path = out_dir / "problem-embeddings-summary.md"
            client = FakeEmbeddingClient()

            result = build_problem_bank_embeddings(
                catalog_path=PROBLEMS_FIXTURE,
                embeddings_path=embeddings_path,
                manifest_path=manifest_path,
                summary_path=summary_path,
                client=client,
                model="fake-embedding-model",
                output_dimensionality=4,
            )

            records = read_problem_embedding_jsonl(embeddings_path).records
            first_payload = json.loads(embeddings_path.read_text(encoding="utf-8").splitlines()[0])
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            summary = summary_path.read_text(encoding="utf-8")

        self.assertEqual(result.generated_count, 2)
        self.assertEqual(result.reused_count, 0)
        self.assertEqual(result.failed_count, 0)
        self.assertEqual([issue.reason for issue in result.issues], ["missing_embedding", "missing_embedding"])
        self.assertEqual(len(records), 2)
        self.assertEqual(manifest["recordCount"], 2)
        self.assertIn("Problem Embeddings Summary", summary)
        self.assertTrue(client.document_inputs)
        for key in (
            "problemId",
            "sourceKey",
            "contentHash",
            "model",
            "outputDimensionality",
            "sourcePath",
            "headingPath",
            "embedding",
        ):
            self.assertIn(key, first_payload)
        self.assertEqual(first_payload["model"], "fake-embedding-model")
        self.assertEqual(first_payload["outputDimensionality"], 4)

    def test_embedding_input_uses_meaningful_fields_without_confusion_terms(self) -> None:
        problem = read_problem_jsonl(PROBLEMS_FIXTURE).records[0]

        text = build_problem_embedding_input(problem)

        self.assertIn(problem.question, text)
        self.assertIn(problem.model_answer[:40], text)
        self.assertIn("Primary field: db", text)
        self.assertIn("Score allocation: db=10, framework=10", text)
        self.assertIn("Rubric must mention: N+1, Lazy Loading, fetch join", text)
        self.assertIn("Rubric optional mention: Batch Size, EntityGraph", text)
        self.assertIn("Heading: Database JPA N+1", text)
        self.assertNotIn("SQL Injection", text)
        self.assertNotIn("GEMINI_API_KEY", text)

    def test_reuses_matching_embeddings_and_regenerates_only_missing_or_stale(self) -> None:
        problems = tuple(_problem(index) for index in range(1, 6))
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            catalog_path = temp_path / "problems.jsonl"
            embeddings_path = temp_path / "problem-embeddings.jsonl"
            _write_problem_jsonl(catalog_path, problems)

            reusable = make_problem_embedding_record(
                problems[0],
                embedding=(1.0, 0.0, 0.0, 0.0),
                model="fake-embedding-model",
                output_dimensionality=4,
            ).to_json_dict()
            stale_hash = make_problem_embedding_record(
                problems[1],
                embedding=(2.0, 0.0, 0.0, 0.0),
                model="fake-embedding-model",
                output_dimensionality=4,
            ).to_json_dict()
            stale_hash["contentHash"] = "sha256:stale"
            stale_model = make_problem_embedding_record(
                problems[2],
                embedding=(3.0, 0.0, 0.0, 0.0),
                model="old-model",
                output_dimensionality=4,
            ).to_json_dict()
            stale_dimension = make_problem_embedding_record(
                problems[3],
                embedding=(4.0, 0.0, 0.0),
                model="fake-embedding-model",
                output_dimensionality=3,
            ).to_json_dict()
            embeddings_path.write_text(
                "".join(
                    json.dumps(row, ensure_ascii=False) + "\n"
                    for row in (reusable, stale_hash, stale_model, stale_dimension)
                ),
                encoding="utf-8",
            )
            client = FakeEmbeddingClient()

            result = build_problem_bank_embeddings(
                catalog_path=catalog_path,
                embeddings_path=embeddings_path,
                manifest_path=temp_path / "manifest.json",
                summary_path=temp_path / "summary.md",
                client=client,
                model="fake-embedding-model",
                output_dimensionality=4,
            )

        self.assertEqual(result.reused_count, 1)
        self.assertEqual(result.generated_count, 4)
        self.assertEqual(len(client.document_inputs), 4)
        self.assertEqual([record.problem_id for record in result.records], [1, 2, 3, 4, 5])
        reasons = [issue.reason for issue in result.issues]
        self.assertIn("stale_content_hash", reasons)
        self.assertIn("stale_model", reasons)
        self.assertIn("stale_output_dimensionality", reasons)
        self.assertIn("missing_embedding", reasons)


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


def _write_problem_jsonl(path: Path, problems: tuple[ProblemRecord, ...]) -> None:
    path.write_text(
        "".join(json.dumps(problem.to_json_dict(), ensure_ascii=False) + "\n" for problem in problems),
        encoding="utf-8",
    )


if __name__ == "__main__":
    unittest.main()
