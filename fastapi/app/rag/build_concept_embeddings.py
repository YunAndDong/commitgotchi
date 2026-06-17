from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from .concept_embeddings import (
    DEFAULT_EMBEDDING_DIMENSIONS,
    DEFAULT_EMBEDDING_MODEL,
    DEFAULT_EMBEDDINGS_PATH,
    EmbeddingClient,
    GeminiEmbeddingClient,
    GeminiEmbeddingSettings,
    build_document_embedding_input,
    make_concept_embedding_record,
    read_concept_embedding_jsonl,
    write_concept_embedding_jsonl,
)
from .concept_store import DEFAULT_CATALOG_PATH, read_concept_jsonl
from .schemas import ConceptChunkRecord, ConceptEmbeddingRecord


@dataclass(frozen=True)
class ConceptEmbeddingBuildIssue:
    reason: str
    chunk_id: str | None = None
    message: str = ""

    def to_dict(self) -> dict[str, Any]:
        payload: dict[str, Any] = {"reason": self.reason}
        if self.chunk_id is not None:
            payload["chunkId"] = self.chunk_id
        if self.message:
            payload["message"] = self.message
        return payload


@dataclass(frozen=True)
class ConceptEmbeddingBuildResult:
    records: tuple[ConceptEmbeddingRecord, ...] = field(default_factory=tuple)
    issues: tuple[ConceptEmbeddingBuildIssue, ...] = field(default_factory=tuple)
    reused_count: int = 0
    generated_count: int = 0
    failed_count: int = 0
    output_path: str = ""

    @property
    def summary(self) -> dict[str, Any]:
        return {
            "recordCount": len(self.records),
            "reusedCount": self.reused_count,
            "generatedCount": self.generated_count,
            "failedCount": self.failed_count,
            "issueCount": len(self.issues),
            "outputPath": self.output_path,
            "issues": [issue.to_dict() for issue in self.issues],
        }


def build_concept_embeddings(
    *,
    catalog_path: Path = DEFAULT_CATALOG_PATH,
    embeddings_path: Path = DEFAULT_EMBEDDINGS_PATH,
    client: EmbeddingClient | None = None,
    model: str | None = None,
    output_dimensionality: int | None = None,
) -> ConceptEmbeddingBuildResult:
    settings = (
        GeminiEmbeddingSettings.from_env()
        if client is None or model is None or output_dimensionality is None
        else None
    )
    resolved_model = model or (settings.model if settings else DEFAULT_EMBEDDING_MODEL)
    resolved_dimensionality = output_dimensionality or (
        settings.output_dimensionality if settings else DEFAULT_EMBEDDING_DIMENSIONS
    )

    catalog_result = read_concept_jsonl(catalog_path)
    chunks = catalog_result.records
    issues: list[ConceptEmbeddingBuildIssue] = [
        ConceptEmbeddingBuildIssue(
            reason=f"catalog_{issue.reason}",
            message=issue.message,
        )
        for issue in catalog_result.issues
    ]

    existing_result = read_concept_embedding_jsonl(embeddings_path)
    issues.extend(
        ConceptEmbeddingBuildIssue(
            reason=f"embedding_{issue.reason}",
            chunk_id=issue.chunk_id,
            message=issue.message,
        )
        for issue in existing_result.issues
    )
    existing = _latest_by_chunk_id(existing_result.records)
    embedder = client or GeminiEmbeddingClient(settings)

    records: list[ConceptEmbeddingRecord] = []
    reused_count = 0
    generated_count = 0
    failed_count = 0

    for chunk in chunks:
        reusable = _matching_record(
            chunk,
            existing.get(chunk.chunk_id),
            model=resolved_model,
            output_dimensionality=resolved_dimensionality,
            issues=issues,
        )
        if reusable is not None:
            records.append(reusable)
            reused_count += 1
            continue

        try:
            embedding = embedder.embed_document(build_document_embedding_input(chunk))
            record = make_concept_embedding_record(
                chunk,
                embedding=embedding,
                model=resolved_model,
                output_dimensionality=resolved_dimensionality,
            )
        except Exception as exc:
            failed_count += 1
            issues.append(
                ConceptEmbeddingBuildIssue(
                    reason="embedding_failed",
                    chunk_id=chunk.chunk_id,
                    message=str(exc),
                )
            )
            continue

        records.append(record)
        generated_count += 1

    records = sorted(
        records,
        key=lambda record: (
            _chunk_sort_key(chunks, record.chunk_id),
            record.source_path,
            record.chunk_id,
        ),
    )
    write_concept_embedding_jsonl(records, embeddings_path)
    return ConceptEmbeddingBuildResult(
        records=tuple(records),
        issues=tuple(issues),
        reused_count=reused_count,
        generated_count=generated_count,
        failed_count=failed_count,
        output_path=Path(embeddings_path).as_posix(),
    )


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Build Gemini embedding sidecar JSONL for concept chunks.",
    )
    parser.add_argument("--catalog", type=Path, default=DEFAULT_CATALOG_PATH)
    parser.add_argument("--out", type=Path, default=DEFAULT_EMBEDDINGS_PATH)
    parser.add_argument("--model", default=None)
    parser.add_argument("--dimensions", type=int, default=None)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        result = build_concept_embeddings(
            catalog_path=args.catalog,
            embeddings_path=args.out,
            model=args.model,
            output_dimensionality=args.dimensions,
        )
    except (FileNotFoundError, ValueError, RuntimeError) as exc:
        print(f"concept embedding build failed: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(result.summary, ensure_ascii=False, indent=2, sort_keys=True))
    return 1 if result.failed_count else 0


def _latest_by_chunk_id(
    records: tuple[ConceptEmbeddingRecord, ...],
) -> dict[str, ConceptEmbeddingRecord]:
    result: dict[str, ConceptEmbeddingRecord] = {}
    for record in records:
        result[record.chunk_id] = record
    return result


def _matching_record(
    chunk: ConceptChunkRecord,
    record: ConceptEmbeddingRecord | None,
    *,
    model: str,
    output_dimensionality: int,
    issues: list[ConceptEmbeddingBuildIssue],
) -> ConceptEmbeddingRecord | None:
    if record is None:
        return None
    if record.content_hash != chunk.content_hash:
        issues.append(
            ConceptEmbeddingBuildIssue(
                reason="stale_content_hash",
                chunk_id=chunk.chunk_id,
                message="embedding contentHash does not match concept chunk contentHash",
            )
        )
        return None
    if record.model != model:
        issues.append(
            ConceptEmbeddingBuildIssue(
                reason="stale_model",
                chunk_id=chunk.chunk_id,
                message=f"embedding model is {record.model}, expected {model}",
            )
        )
        return None
    if record.output_dimensionality != output_dimensionality or len(record.embedding) != output_dimensionality:
        issues.append(
            ConceptEmbeddingBuildIssue(
                reason="stale_output_dimensionality",
                chunk_id=chunk.chunk_id,
                message=(
                    "embedding outputDimensionality does not match expected "
                    f"{output_dimensionality}"
                ),
            )
        )
        return None
    return record


def _chunk_sort_key(chunks: tuple[ConceptChunkRecord, ...], chunk_id: str) -> int:
    for index, chunk in enumerate(chunks):
        if chunk.chunk_id == chunk_id:
            return index
    return len(chunks)


if __name__ == "__main__":
    raise SystemExit(main())
