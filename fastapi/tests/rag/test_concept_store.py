from __future__ import annotations

import json
import shutil
import tempfile
import unittest
from pathlib import Path

from app.rag.concept_catalog import FASTAPI_ROOT, build_concept_catalog
from app.rag.concept_store import (
    DEFAULT_CATALOG_PATH,
    ConceptCatalogStore,
    ConceptChunkDecodeError,
    load_concept_catalog_store,
    read_concept_jsonl,
)
from app.rag.schemas import ConceptChunkRecord, validate_concept_chunk_record


FIXTURE_ROOT = FASTAPI_ROOT / "tests" / "fixtures" / "rag" / "concept_catalog"


class ConceptCatalogStoreTest(unittest.TestCase):
    def test_reads_jsonl_and_validates_concept_records(self) -> None:
        with self._built_catalog_path() as catalog_path:
            result = read_concept_jsonl(catalog_path)

        self.assertEqual(result.issues, ())
        self.assertGreaterEqual(len(result.records), 5)
        self.assertTrue(all(isinstance(record, ConceptChunkRecord) for record in result.records))
        for record in result.records:
            self.assertEqual(validate_concept_chunk_record(record), [])

    def test_store_supports_chunk_lookup_source_lookup_and_field_filter(self) -> None:
        with self._built_catalog_path() as catalog_path:
            store = ConceptCatalogStore(read_concept_jsonl(catalog_path).records)

            first = store.records[0]
            self.assertIs(store.get(first.chunk_id), first)
            self.assertIsNone(store.get("concept:sha256:missing"))

            source_records = store.by_source_path(first.source_path)
            self.assertGreaterEqual(len(source_records), 1)
            self.assertEqual(
                [record.chunk_index for record in source_records],
                sorted(record.chunk_index for record in source_records),
            )

            db_records = store.filter_by_field_hint("db")
            self.assertTrue(any("Database" in record.heading_path for record in db_records))
            self.assertEqual(store.filter_by_field_hint("unknown"), ())

    def test_missing_and_empty_catalog_load_as_empty_store(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            missing_path = Path(temp_dir) / "missing.jsonl"
            empty_path = Path(temp_dir) / "empty.jsonl"
            empty_path.write_text("", encoding="utf-8")

            missing_result = read_concept_jsonl(missing_path)
            empty_result = read_concept_jsonl(empty_path)
            missing_store = load_concept_catalog_store(missing_path)

        self.assertEqual(missing_result.records, ())
        self.assertEqual(missing_result.issues, ())
        self.assertEqual(empty_result.records, ())
        self.assertEqual(empty_result.issues, ())
        self.assertEqual(len(missing_store), 0)

    def test_invalid_lines_are_reported_without_discarding_valid_records(self) -> None:
        with self._built_catalog_path() as catalog_path:
            valid_line = catalog_path.read_text(encoding="utf-8").splitlines()[0]
            invalid_path = catalog_path.parent / "invalid-chunks.jsonl"
            schema_payload = json.loads(valid_line)
            schema_payload.pop("text")
            invalid_path.write_text(
                "\n".join(
                    [
                        valid_line,
                        "{not json",
                        json.dumps(schema_payload, ensure_ascii=False),
                    ]
                )
                + "\n",
                encoding="utf-8",
            )

            result = read_concept_jsonl(invalid_path)

            self.assertEqual(len(result.records), 1)
            self.assertEqual(
                [issue.reason for issue in result.issues],
                ["json_decode_failed", "schema_validation_failed"],
            )
            self.assertEqual([issue.line_number for issue in result.issues], [2, 3])
            with self.assertRaises(ConceptChunkDecodeError):
                read_concept_jsonl(invalid_path, strict=True)

    def test_default_catalog_smoke_load_does_not_crash(self) -> None:
        store = load_concept_catalog_store(DEFAULT_CATALOG_PATH)

        self.assertIsInstance(store, ConceptCatalogStore)
        self.assertGreaterEqual(len(store), 0)

    def _built_catalog_path(self):
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
