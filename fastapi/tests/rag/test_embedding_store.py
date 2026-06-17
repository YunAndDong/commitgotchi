from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from app.rag.concept_store import ConceptCatalogStore
from app.rag.embedding_store import load_concept_embedding_store
from app.rag.schemas import ConceptChunkRecord, ConceptNeighbors


class EmbeddingStoreTest(unittest.TestCase):
    def test_loads_matching_embeddings_and_collects_stale_issues(self) -> None:
        chunks = (
            _chunk("a", index=0),
            _chunk("b", index=1),
            _chunk("c", index=2),
            _chunk("d", index=3),
            _chunk("e", index=4),
        )
        store = ConceptCatalogStore(chunks)
        rows = [
            _embedding_row(chunks[0]),
            _embedding_row(chunks[1], content_hash="sha256:stale"),
            _embedding_row(chunks[2], model="old-model"),
            _embedding_row(chunks[3], output_dimensionality=3, embedding=[1.0, 0.0, 0.0]),
            _embedding_row(_chunk("unknown", index=99)),
        ]

        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "chunk-embeddings.jsonl"
            path.write_text(
                "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows),
                encoding="utf-8",
            )

            embedding_store = load_concept_embedding_store(
                store=store,
                embeddings_path=path,
                expected_model="fake-embedding-model",
                expected_dimensionality=4,
            )

        self.assertEqual([item.chunk.chunk_id for item in embedding_store.items], [chunks[0].chunk_id])
        self.assertEqual(embedding_store.embedding_for(chunks[0].chunk_id), (1.0, 0.0, 0.0, 0.0))
        reasons = [issue.reason for issue in embedding_store.issues]
        self.assertIn("stale_content_hash", reasons)
        self.assertIn("stale_model", reasons)
        self.assertIn("stale_output_dimensionality", reasons)
        self.assertIn("unknown_chunk", reasons)
        self.assertIn("missing_embedding", reasons)

    def test_missing_embedding_file_loads_empty_store_without_crashing(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            embedding_store = load_concept_embedding_store(
                store=ConceptCatalogStore((_chunk("a"),)),
                embeddings_path=Path(temp_dir) / "missing.jsonl",
                expected_model="fake-embedding-model",
                expected_dimensionality=4,
            )

        self.assertEqual(embedding_store.items, ())
        self.assertTrue(any(issue.reason == "missing_embedding" for issue in embedding_store.issues))


def _chunk(suffix: str, *, index: int = 0) -> ConceptChunkRecord:
    return ConceptChunkRecord(
        chunk_id=f"concept:sha256:{suffix}",
        source_path="03-framework/jpa.md",
        source_root="/tmp/source",
        heading_path=("Spring Framework", f"Heading {suffix}"),
        heading_level=2,
        chunk_index=index,
        text=f"JPA Lazy Loading fetch join N+1 concept {suffix}",
        content_hash=f"sha256:{suffix}",
        field_hints=("db", "framework"),
        neighbors=ConceptNeighbors(),
    )


def _embedding_row(
    chunk: ConceptChunkRecord,
    *,
    content_hash: str | None = None,
    model: str = "fake-embedding-model",
    output_dimensionality: int = 4,
    embedding: list[float] | None = None,
) -> dict[str, object]:
    return {
        "chunkId": chunk.chunk_id,
        "contentHash": content_hash or chunk.content_hash,
        "model": model,
        "outputDimensionality": output_dimensionality,
        "sourcePath": chunk.source_path,
        "headingPath": list(chunk.heading_path),
        "embedding": embedding or [1.0, 0.0, 0.0, 0.0],
    }


if __name__ == "__main__":
    unittest.main()
