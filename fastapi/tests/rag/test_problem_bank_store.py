from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from app.rag.problem_bank_store import (
    DEFAULT_CATALOG_PATH,
    FASTAPI_ROOT,
    ProblemBankStore,
    load_problem_bank_store,
    read_problem_jsonl,
)
from app.rag.schemas import ProblemRecord, validate_problem_record


FIXTURE_ROOT = FASTAPI_ROOT / "tests" / "fixtures" / "rag" / "problem_bank_store"
PROBLEMS_FIXTURE = FIXTURE_ROOT / "problems.jsonl"
INVALID_FIXTURE = FIXTURE_ROOT / "invalid-problems.jsonl"


class ProblemBankStoreTest(unittest.TestCase):
    def test_reads_jsonl_and_validates_problem_records(self) -> None:
        result = read_problem_jsonl(PROBLEMS_FIXTURE)

        self.assertEqual(result.issues, ())
        self.assertEqual(len(result.records), 2)
        self.assertTrue(all(isinstance(record, ProblemRecord) for record in result.records))
        for record in result.records:
            self.assertEqual(validate_problem_record(record), [])

    def test_store_supports_problem_id_lookup_and_field_difficulty_filter(self) -> None:
        store = ProblemBankStore(read_problem_jsonl(PROBLEMS_FIXTURE).records)

        n_plus_one = store.get(101)
        self.assertIsNotNone(n_plus_one)
        self.assertIn("N+1", n_plus_one.question)
        self.assertIsNone(store.get(999))

        self.assertEqual([problem.problem_id for problem in store.filter(field="framework")], [101])
        self.assertEqual([problem.problem_id for problem in store.filter(difficulty="basic")], [202])
        self.assertEqual(
            [problem.problem_id for problem in store.filter(field="network", difficulty="basic")],
            [202],
        )
        self.assertEqual(store.filter(field="unknown"), ())
        self.assertEqual(store.filter(difficulty="expert"), ())

    def test_missing_catalog_loads_as_empty_store(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            missing_path = Path(temp_dir) / "missing.jsonl"

            result = read_problem_jsonl(missing_path)
            store = load_problem_bank_store(missing_path)

        self.assertEqual(result.records, ())
        self.assertEqual(result.issues, ())
        self.assertEqual(len(store), 0)

    def test_invalid_lines_are_reported_without_discarding_valid_records(self) -> None:
        result = read_problem_jsonl(INVALID_FIXTURE)

        self.assertEqual([record.problem_id for record in result.records], [303])
        self.assertEqual([issue.reason for issue in result.issues], [
            "json_decode_failed",
            "schema_validation_failed",
        ])
        self.assertEqual([issue.line_number for issue in result.issues], [2, 3])

    def test_default_catalog_smoke_load_does_not_crash(self) -> None:
        store = load_problem_bank_store(DEFAULT_CATALOG_PATH)

        self.assertIsInstance(store, ProblemBankStore)
        self.assertGreaterEqual(len(store), 0)


if __name__ == "__main__":
    unittest.main()
