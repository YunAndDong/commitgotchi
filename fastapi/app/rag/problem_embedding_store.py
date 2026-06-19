from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

from .concept_embeddings import DEFAULT_EMBEDDING_DIMENSIONS, DEFAULT_EMBEDDING_MODEL
from .problem_bank_store import (
    DEFAULT_CATALOG_PATH,
    ProblemBankStore,
    load_problem_bank_store,
)
from .problem_embeddings import (
    DEFAULT_PROBLEM_EMBEDDINGS_PATH,
    read_problem_embedding_jsonl,
)
from .schemas import ProblemEmbeddingRecord, ProblemRecord


@dataclass(frozen=True)
class ProblemEmbeddingStoreIssue:
    reason: str
    problem_id: int | None = None
    source_key: str | None = None
    message: str = ""


@dataclass(frozen=True)
class EmbeddedProblem:
    problem: ProblemRecord
    embedding: tuple[float, ...]


@dataclass(frozen=True)
class ProblemEmbeddingStore:
    items: tuple[EmbeddedProblem, ...] = field(default_factory=tuple)
    issues: tuple[ProblemEmbeddingStoreIssue, ...] = field(default_factory=tuple)
    expected_model: str | None = None
    expected_dimensionality: int | None = None
    _by_key: dict[tuple[int, str], EmbeddedProblem] = field(init=False, repr=False)
    _by_problem_id: dict[int, EmbeddedProblem] = field(init=False, repr=False)

    def __post_init__(self) -> None:
        items = tuple(self.items)
        object.__setattr__(self, "items", items)
        object.__setattr__(
            self,
            "_by_key",
            {(item.problem.problem_id, item.problem.source_key): item for item in items},
        )
        by_problem_id: dict[int, EmbeddedProblem] = {}
        for item in items:
            by_problem_id.setdefault(item.problem.problem_id, item)
        object.__setattr__(self, "_by_problem_id", by_problem_id)

    def __iter__(self):
        return iter(self.items)

    def __len__(self) -> int:
        return len(self.items)

    def get(self, problem_id: int, source_key: str | None = None) -> EmbeddedProblem | None:
        if source_key is not None:
            return self._by_key.get((problem_id, source_key))
        return self._by_problem_id.get(problem_id)

    def embedding_for(
        self,
        problem_id: int,
        source_key: str | None = None,
    ) -> tuple[float, ...] | None:
        item = self.get(problem_id, source_key)
        return None if item is None else item.embedding


def load_problem_embedding_store(
    *,
    store: ProblemBankStore | None = None,
    catalog_path: Path = DEFAULT_CATALOG_PATH,
    embeddings_path: Path = DEFAULT_PROBLEM_EMBEDDINGS_PATH,
    expected_model: str | None = None,
    expected_dimensionality: int | None = None,
) -> ProblemEmbeddingStore:
    problem_store = store if store is not None else load_problem_bank_store(catalog_path)
    model = expected_model or os.getenv("GEMINI_EMBEDDING_MODEL", DEFAULT_EMBEDDING_MODEL)
    dimensionality = expected_dimensionality or _expected_dimensionality_from_env()
    embedding_result = read_problem_embedding_jsonl(embeddings_path)

    issues: list[ProblemEmbeddingStoreIssue] = [
        ProblemEmbeddingStoreIssue(
            reason=issue.reason,
            problem_id=issue.problem_id,
            source_key=issue.source_key,
            message=issue.message,
        )
        for issue in embedding_result.issues
    ]
    records_by_key = _latest_by_problem_key(embedding_result.records)
    problem_keys = {
        (problem.problem_id, problem.source_key)
        for problem in problem_store.records
    }

    for record in embedding_result.records:
        if _record_key(record) not in problem_keys:
            issues.append(
                ProblemEmbeddingStoreIssue(
                    reason="unknown_problem",
                    problem_id=record.problem_id,
                    source_key=record.source_key,
                    message="embedding record does not have a matching problem",
                )
            )

    items: list[EmbeddedProblem] = []
    for problem in problem_store.records:
        record = records_by_key.get((problem.problem_id, problem.source_key))
        if record is None:
            issues.append(
                ProblemEmbeddingStoreIssue(
                    reason="missing_embedding",
                    problem_id=problem.problem_id,
                    source_key=problem.source_key,
                    message="problem does not have an embedding record",
                )
            )
            continue

        stale_reason = _stale_reason(
            problem,
            record,
            expected_model=model,
            expected_dimensionality=dimensionality,
        )
        if stale_reason is not None:
            issues.append(stale_reason)
            continue

        items.append(EmbeddedProblem(problem=problem, embedding=record.embedding))

    return ProblemEmbeddingStore(
        items=tuple(items),
        issues=tuple(issues),
        expected_model=model,
        expected_dimensionality=dimensionality,
    )


def _latest_by_problem_key(
    records: tuple[ProblemEmbeddingRecord, ...],
) -> dict[tuple[int, str], ProblemEmbeddingRecord]:
    result: dict[tuple[int, str], ProblemEmbeddingRecord] = {}
    for record in records:
        result[_record_key(record)] = record
    return result


def _record_key(record: ProblemEmbeddingRecord) -> tuple[int, str]:
    return record.problem_id, record.source_key


def _stale_reason(
    problem: ProblemRecord,
    record: ProblemEmbeddingRecord,
    *,
    expected_model: str,
    expected_dimensionality: int,
) -> ProblemEmbeddingStoreIssue | None:
    if record.content_hash != problem.content_hash:
        return ProblemEmbeddingStoreIssue(
            reason="stale_content_hash",
            problem_id=problem.problem_id,
            source_key=problem.source_key,
            message="embedding contentHash does not match problem contentHash",
        )
    if record.model != expected_model:
        return ProblemEmbeddingStoreIssue(
            reason="stale_model",
            problem_id=problem.problem_id,
            source_key=problem.source_key,
            message=f"embedding model is {record.model}, expected {expected_model}",
        )
    if record.output_dimensionality != expected_dimensionality or len(record.embedding) != expected_dimensionality:
        return ProblemEmbeddingStoreIssue(
            reason="stale_output_dimensionality",
            problem_id=problem.problem_id,
            source_key=problem.source_key,
            message=(
                "embedding outputDimensionality does not match expected "
                f"{expected_dimensionality}"
            ),
        )
    return None


def _expected_dimensionality_from_env() -> int:
    raw = os.getenv("GEMINI_EMBEDDING_DIMENSIONS", str(DEFAULT_EMBEDDING_DIMENSIONS))
    try:
        value = int(raw)
    except ValueError:
        return DEFAULT_EMBEDDING_DIMENSIONS
    return value if value > 0 else DEFAULT_EMBEDDING_DIMENSIONS
