from __future__ import annotations

import json
import shutil
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from app.rag.build_concept_catalog import build_concept_catalog
from app.rag.build_concept_embeddings import build_concept_embeddings
from app.rag.concept_embeddings import (
    GeminiEmbeddingSettings,
    build_document_embedding_input,
    read_concept_embedding_jsonl,
)
from app.rag.concept_store import read_concept_jsonl
from app.rag.concept_catalog import FASTAPI_ROOT


FIXTURE_ROOT = FASTAPI_ROOT / "tests" / "fixtures" / "rag" / "concept_catalog"


class FakeEmbeddingClient:
    def __init__(self) -> None:
        self.document_inputs: list[str] = []
        self.query_inputs: list[str] = []

    def embed_document(self, text: str) -> tuple[float, ...]:
        self.document_inputs.append(text)
        return _fake_vector(text)

    def embed_query(self, text: str) -> tuple[float, ...]:
        self.query_inputs.append(text)
        return _fake_vector(text)


class FixedEmbeddingClient:
    def __init__(self, vector: tuple[float, ...]) -> None:
        self.vector = vector

    def embed_document(self, text: str) -> tuple[float, ...]:
        return self.vector

    def embed_query(self, text: str) -> tuple[float, ...]:
        return self.vector


class ConceptEmbeddingsTest(unittest.TestCase):
    def test_settings_use_embedding_env_without_quiz_grader_model(self) -> None:
        with patch.dict(
            "os.environ",
            {
                "GEMINI_API_KEY": "test-key",
                "GEMINI_EMBEDDING_MODEL": "fake-embedding-model",
                "GEMINI_EMBEDDING_DIMENSIONS": "4",
                "GEMINI_QUIZ_GRADER_MODEL": "must-not-be-used",
            },
            clear=False,
        ):
            settings = GeminiEmbeddingSettings.from_env()

        self.assertEqual(settings.api_key, "test-key")
        self.assertEqual(settings.model, "fake-embedding-model")
        self.assertEqual(settings.output_dimensionality, 4)

    def test_builds_embedding_jsonl_with_required_record_fields(self) -> None:
        with _built_catalog_path() as catalog_path:
            embeddings_path = catalog_path.parent / "chunk-embeddings.jsonl"
            client = FakeEmbeddingClient()

            result = build_concept_embeddings(
                catalog_path=catalog_path,
                embeddings_path=embeddings_path,
                client=client,
                model="fake-embedding-model",
                output_dimensionality=4,
            )

            records = read_concept_embedding_jsonl(embeddings_path).records
            first_payload = json.loads(embeddings_path.read_text(encoding="utf-8").splitlines()[0])
            path_existed = embeddings_path.exists()

        self.assertTrue(path_existed)
        self.assertGreater(len(records), 0)
        self.assertEqual(result.generated_count, len(records))
        self.assertEqual(result.failed_count, 0)
        self.assertEqual(result.issues, ())
        self.assertTrue(client.document_inputs)
        for key in (
            "chunkId",
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

    def test_build_uses_embedding_env_for_default_metadata(self) -> None:
        with _built_catalog_path() as catalog_path:
            embeddings_path = catalog_path.parent / "chunk-embeddings.jsonl"
            client = FixedEmbeddingClient((0.1, 0.2, 0.3))

            with patch.dict(
                "os.environ",
                {
                    "GEMINI_EMBEDDING_MODEL": "env-embedding-model",
                    "GEMINI_EMBEDDING_DIMENSIONS": "3",
                },
                clear=False,
            ):
                result = build_concept_embeddings(
                    catalog_path=catalog_path,
                    embeddings_path=embeddings_path,
                    client=client,
                )

            first_record = result.records[0]

        self.assertEqual(first_record.model, "env-embedding-model")
        self.assertEqual(first_record.output_dimensionality, 3)

    def test_reuses_matching_existing_embeddings_and_regenerates_stale_records(self) -> None:
        with _built_catalog_path() as catalog_path:
            chunks = read_concept_jsonl(catalog_path).records
            embeddings_path = catalog_path.parent / "chunk-embeddings.jsonl"
            first_input = build_document_embedding_input(chunks[0])
            reusable = {
                "chunkId": chunks[0].chunk_id,
                "contentHash": chunks[0].content_hash,
                "model": "fake-embedding-model",
                "outputDimensionality": 4,
                "sourcePath": chunks[0].source_path,
                "headingPath": list(chunks[0].heading_path),
                "embedding": list(_fake_vector(first_input)),
            }
            stale = dict(reusable)
            stale["chunkId"] = chunks[1].chunk_id
            stale["contentHash"] = "sha256:stale"
            stale["sourcePath"] = chunks[1].source_path
            stale["headingPath"] = list(chunks[1].heading_path)
            embeddings_path.write_text(
                json.dumps(reusable, ensure_ascii=False)
                + "\n"
                + json.dumps(stale, ensure_ascii=False)
                + "\n",
                encoding="utf-8",
            )

            client = FakeEmbeddingClient()
            result = build_concept_embeddings(
                catalog_path=catalog_path,
                embeddings_path=embeddings_path,
                client=client,
                model="fake-embedding-model",
                output_dimensionality=4,
            )

        self.assertEqual(result.reused_count, 1)
        self.assertEqual(result.generated_count, len(chunks) - 1)
        self.assertTrue(any(issue.reason == "stale_content_hash" for issue in result.issues))


def _fake_vector(text: str) -> tuple[float, ...]:
    lowered = text.lower()
    if any(term in lowered for term in ("jpa", "lazy", "fetch", "n+1")):
        return (1.0, 0.0, 0.0, 0.0)
    if any(term in lowered for term in ("http", "oauth", "jwt", "network")):
        return (0.0, 1.0, 0.0, 0.0)
    if any(term in lowered for term in ("process", "thread", "프로세스", "스레드")):
        return (0.0, 0.0, 1.0, 0.0)
    return (0.0, 0.0, 0.0, 1.0)


def _built_catalog_path():
    temp_dir = tempfile.TemporaryDirectory(dir=FASTAPI_ROOT)
    source_root = Path(temp_dir.name) / "source"
    out_dir = Path(temp_dir.name) / "data" / "rag"
    shutil.copytree(FIXTURE_ROOT, source_root)
    build_concept_catalog(source_root, out_dir, allowed_source_root=source_root)
    return _TempCatalog(temp_dir, out_dir / "catalog" / "chunks.jsonl")


class _TempCatalog:
    def __init__(self, temp_dir: tempfile.TemporaryDirectory[str], catalog_path: Path):
        self.temp_dir = temp_dir
        self.catalog_path = catalog_path

    def __enter__(self) -> Path:
        return self.catalog_path

    def __exit__(self, exc_type, exc_value, traceback) -> None:
        self.temp_dir.cleanup()


if __name__ == "__main__":
    unittest.main()
