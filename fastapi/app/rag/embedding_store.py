from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

from .concept_embeddings import (
    DEFAULT_EMBEDDING_DIMENSIONS,
    DEFAULT_EMBEDDING_MODEL,
    DEFAULT_EMBEDDINGS_PATH,
    read_concept_embedding_jsonl,
)
from .concept_store import ConceptCatalogStore, load_concept_catalog_store
from .schemas import ConceptChunkRecord, ConceptEmbeddingRecord


@dataclass(frozen=True)
class ConceptEmbeddingStoreIssue:
    reason: str
    chunk_id: str | None = None
    message: str = ""


@dataclass(frozen=True)
class EmbeddedConceptChunk:
    chunk: ConceptChunkRecord
    embedding: tuple[float, ...]


@dataclass(frozen=True)
class ConceptEmbeddingStore:
    items: tuple[EmbeddedConceptChunk, ...] = field(default_factory=tuple)
    issues: tuple[ConceptEmbeddingStoreIssue, ...] = field(default_factory=tuple)
    expected_model: str | None = None
    expected_dimensionality: int | None = None
    _by_chunk_id: dict[str, EmbeddedConceptChunk] = field(init=False, repr=False)

    def __post_init__(self) -> None:
        items = tuple(self.items)
        object.__setattr__(self, "items", items)
        object.__setattr__(
            self,
            "_by_chunk_id",
            {item.chunk.chunk_id: item for item in items},
        )

    def __iter__(self):
        return iter(self.items)

    def __len__(self) -> int:
        return len(self.items)

    def get(self, chunk_id: str) -> EmbeddedConceptChunk | None:
        return self._by_chunk_id.get(chunk_id)

    def embedding_for(self, chunk_id: str) -> tuple[float, ...] | None:
        item = self.get(chunk_id)
        return None if item is None else item.embedding


def load_concept_embedding_store(
    *,
    store: ConceptCatalogStore | None = None,
    embeddings_path: Path = DEFAULT_EMBEDDINGS_PATH,
    expected_model: str | None = None,
    expected_dimensionality: int | None = None,
) -> ConceptEmbeddingStore:
    catalog_store = store if store is not None else load_concept_catalog_store()
    model = expected_model or os.getenv("GEMINI_EMBEDDING_MODEL", DEFAULT_EMBEDDING_MODEL)
    dimensionality = expected_dimensionality or _expected_dimensionality_from_env()
    embedding_result = read_concept_embedding_jsonl(embeddings_path)

    issues: list[ConceptEmbeddingStoreIssue] = [
        ConceptEmbeddingStoreIssue(
            reason=issue.reason,
            chunk_id=issue.chunk_id,
            message=issue.message,
        )
        for issue in embedding_result.issues
    ]
    records_by_chunk_id = _latest_by_chunk_id(embedding_result.records)
    chunk_ids = {chunk.chunk_id for chunk in catalog_store.records}

    for record in embedding_result.records:
        if record.chunk_id not in chunk_ids:
            issues.append(
                ConceptEmbeddingStoreIssue(
                    reason="unknown_chunk",
                    chunk_id=record.chunk_id,
                    message="embedding record does not have a matching concept chunk",
                )
            )

    items: list[EmbeddedConceptChunk] = []
    for chunk in catalog_store.records:
        record = records_by_chunk_id.get(chunk.chunk_id)
        if record is None:
            issues.append(
                ConceptEmbeddingStoreIssue(
                    reason="missing_embedding",
                    chunk_id=chunk.chunk_id,
                    message="concept chunk does not have an embedding record",
                )
            )
            continue

        stale_reason = _stale_reason(
            chunk,
            record,
            expected_model=model,
            expected_dimensionality=dimensionality,
        )
        if stale_reason is not None:
            issues.append(stale_reason)
            continue

        items.append(EmbeddedConceptChunk(chunk=chunk, embedding=record.embedding))

    return ConceptEmbeddingStore(
        items=tuple(items),
        issues=tuple(issues),
        expected_model=model,
        expected_dimensionality=dimensionality,
    )


def _latest_by_chunk_id(
    records: tuple[ConceptEmbeddingRecord, ...],
) -> dict[str, ConceptEmbeddingRecord]:
    result: dict[str, ConceptEmbeddingRecord] = {}
    for record in records:
        result[record.chunk_id] = record
    return result


def _stale_reason(
    chunk: ConceptChunkRecord,
    record: ConceptEmbeddingRecord,
    *,
    expected_model: str,
    expected_dimensionality: int,
) -> ConceptEmbeddingStoreIssue | None:
    if record.content_hash != chunk.content_hash:
        return ConceptEmbeddingStoreIssue(
            reason="stale_content_hash",
            chunk_id=chunk.chunk_id,
            message="embedding contentHash does not match concept chunk contentHash",
        )
    if record.model != expected_model:
        return ConceptEmbeddingStoreIssue(
            reason="stale_model",
            chunk_id=chunk.chunk_id,
            message=f"embedding model is {record.model}, expected {expected_model}",
        )
    if record.output_dimensionality != expected_dimensionality or len(record.embedding) != expected_dimensionality:
        return ConceptEmbeddingStoreIssue(
            reason="stale_output_dimensionality",
            chunk_id=chunk.chunk_id,
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
