from __future__ import annotations

import json
import shutil
import tempfile
import unittest
from pathlib import Path

from app.rag.concept_catalog import FASTAPI_ROOT, build_concept_catalog
from app.rag.concept_store import concept_chunk_record_from_dict
from app.rag.schemas import validate_concept_chunk_record


FIXTURE_ROOT = FASTAPI_ROOT / "tests" / "fixtures" / "rag" / "concept_catalog"


class ConceptCatalogPipelineTest(unittest.TestCase):
    def test_builds_jsonl_catalog_and_reports_from_markdown_fixtures(self) -> None:
        with self._source_copy() as source_root:
            out_dir = source_root.parent / "data" / "rag"

            result = build_concept_catalog(
                source_root,
                out_dir,
                allowed_source_root=source_root,
            )

            chunks_path = out_dir / "catalog" / "chunks.jsonl"
            skipped_path = out_dir / "reports" / "concept-catalog-skipped.jsonl"
            rejected_path = out_dir / "reports" / "concept-catalog-rejected.jsonl"
            summary_path = out_dir / "reports" / "concept-catalog-summary.json"
            manifest_path = out_dir / "manifests" / "concept-catalog-discovery.json"

            self.assertTrue(chunks_path.exists())
            self.assertTrue(skipped_path.exists())
            self.assertTrue(rejected_path.exists())
            self.assertTrue(summary_path.exists())
            self.assertTrue(manifest_path.exists())
            self.assertGreaterEqual(result.summary["storedChunkCount"], 5)
            self.assertEqual(result.summary["rejectedCount"], 0)

            chunks = [
                concept_chunk_record_from_dict(json.loads(line))
                for line in chunks_path.read_text(encoding="utf-8").splitlines()
            ]
            for chunk in chunks:
                self.assertEqual(validate_concept_chunk_record(chunk), [])
                self.assertEqual(
                    set(chunk.to_json_dict()).issuperset(
                        {
                            "chunkId",
                            "sourcePath",
                            "headingPath",
                            "chunkIndex",
                            "text",
                            "contentHash",
                            "fieldHints",
                            "neighbors",
                        }
                    ),
                    True,
                )
                self.assertFalse(Path(chunk.source_path).is_absolute())
                self.assertTrue(chunk.chunk_id.startswith("concept:sha256:"))
                self.assertTrue(chunk.content_hash.startswith("sha256:"))

            skipped = [
                json.loads(line)
                for line in skipped_path.read_text(encoding="utf-8").splitlines()
            ]
            skipped_reasons = {item["reason"] for item in skipped}
            self.assertIn("unsupported_file_type", skipped_reasons)
            self.assertIn("empty_file", skipped_reasons)
            self.assertIn("chunk_too_short", skipped_reasons)
            self.assertTrue(
                any(
                    item["reason"] == "chunk_too_short"
                    and item["sourcePath"] == "short.md"
                    and item["headingPath"] == ["Tiny"]
                    for item in skipped
                )
            )

    def test_heading_tree_parent_neighbors_and_code_fence_handling(self) -> None:
        with self._source_copy() as source_root:
            result = build_concept_catalog(
                source_root,
                source_root.parent / "data" / "rag",
                allowed_source_root=source_root,
            )

            by_heading = {chunk.heading_path: chunk for chunk in result.chunks}
            isolation = by_heading[("Database", "Transaction", "Isolation Level")]
            index = by_heading[("Database", "Transaction", "Index")]

            self.assertEqual(
                isolation.neighbors.parent_heading_path,
                ("Database", "Transaction"),
            )
            self.assertIn(("Database", "Transaction", "Index"), isolation.neighbors.sibling_heading_paths)
            self.assertNotIn("# This is not a real heading", " ".join(" > ".join(path) for path in by_heading))

            db_chunks = [
                chunk
                for chunk in result.chunks
                if chunk.source_path == "01-db/transaction.md"
            ]
            self.assertGreaterEqual(len(db_chunks), 3)
            for previous, current in zip(db_chunks, db_chunks[1:]):
                self.assertEqual(previous.neighbors.next_chunk_id, current.chunk_id)
                self.assertEqual(current.neighbors.previous_chunk_id, previous.chunk_id)

            self.assertEqual(index.neighbors.previous_chunk_id, isolation.chunk_id)

    def test_no_heading_and_markdown_constructs_keep_substantive_text(self) -> None:
        with self._source_copy() as source_root:
            result = build_concept_catalog(
                source_root,
                source_root.parent / "data" / "rag",
                allowed_source_root=source_root,
            )

            fallback = next(chunk for chunk in result.chunks if chunk.source_path == "no-heading.md")
            self.assertEqual(fallback.heading_path, ("no heading",))
            self.assertIn("operating system process", fallback.text)

            network = next(chunk for chunk in result.chunks if chunk.source_path == "02-network/http.md")
            self.assertIn("GET", network.text)
            self.assertIn("REST constraints", network.text)
            self.assertIn("Cache-Control", network.text)
            self.assertIn("network", network.field_hints)

    def test_repeated_build_is_deterministic_and_body_change_updates_only_content_hash(self) -> None:
        with self._source_copy() as source_root:
            out_a = source_root.parent / "data-a" / "rag"
            out_b = source_root.parent / "data-b" / "rag"
            first = build_concept_catalog(source_root, out_a, allowed_source_root=source_root)
            second = build_concept_catalog(source_root, out_b, allowed_source_root=source_root)

            self.assertEqual(
                (out_a / "catalog" / "chunks.jsonl").read_text(encoding="utf-8"),
                (out_b / "catalog" / "chunks.jsonl").read_text(encoding="utf-8"),
            )
            self.assertEqual(
                [(chunk.source_path, chunk.chunk_index, chunk.chunk_id) for chunk in first.chunks],
                [(chunk.source_path, chunk.chunk_index, chunk.chunk_id) for chunk in second.chunks],
            )

            target_path = source_root / "01-db" / "transaction.md"
            original_text = target_path.read_text(encoding="utf-8")
            target_path.write_text(
                original_text.replace(
                    "Serializable isolation is safer but may reduce concurrency in relational database systems.",
                    "Serializable isolation is safer but may reduce concurrency in relational database systems. Snapshot isolation changes read visibility for concurrent transactions.",
                ),
                encoding="utf-8",
            )
            changed = build_concept_catalog(
                source_root,
                source_root.parent / "data-c" / "rag",
                allowed_source_root=source_root,
            )

            before = next(
                chunk
                for chunk in first.chunks
                if chunk.heading_path == ("Database", "Transaction", "Isolation Level")
            )
            after = next(
                chunk
                for chunk in changed.chunks
                if chunk.heading_path == ("Database", "Transaction", "Isolation Level")
            )
            self.assertEqual(before.chunk_id, after.chunk_id)
            self.assertEqual(before.chunk_index, after.chunk_index)
            self.assertNotEqual(before.content_hash, after.content_hash)

    def test_source_root_outside_allowed_root_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory(dir=FASTAPI_ROOT) as temp_dir:
            allowed_root = Path(temp_dir) / "allowed"
            outside_root = Path(temp_dir) / "outside"
            allowed_root.mkdir()
            outside_root.mkdir()

            with self.assertRaises(ValueError):
                build_concept_catalog(
                    outside_root,
                    Path(temp_dir) / "out" / "rag",
                    allowed_source_root=allowed_root,
                    dry_run=True,
                )

    def _source_copy(self):
        temp_dir = tempfile.TemporaryDirectory(dir=FASTAPI_ROOT)
        source_root = Path(temp_dir.name) / "source"
        shutil.copytree(FIXTURE_ROOT, source_root)
        return _TempSource(temp_dir, source_root)


class _TempSource:
    def __init__(self, temp_dir: tempfile.TemporaryDirectory[str], source_root: Path):
        self.temp_dir = temp_dir
        self.source_root = source_root

    def __enter__(self) -> Path:
        return self.source_root

    def __exit__(self, exc_type, exc_value, traceback) -> None:
        self.temp_dir.cleanup()


if __name__ == "__main__":
    unittest.main()
