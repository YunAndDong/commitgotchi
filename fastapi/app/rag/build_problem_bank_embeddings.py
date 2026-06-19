from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from .concept_embeddings import (
    DEFAULT_EMBEDDING_DIMENSIONS,
    DEFAULT_EMBEDDING_MODEL,
    EmbeddingClient,
    GeminiEmbeddingClient,
    GeminiEmbeddingSettings,
)
from .problem_bank_store import DEFAULT_CATALOG_PATH, read_problem_jsonl
from .problem_embeddings import (
    DEFAULT_PROBLEM_EMBEDDINGS_PATH,
    build_problem_embedding_input,
    make_problem_embedding_record,
    read_problem_embedding_jsonl,
    write_problem_embedding_jsonl,
)
from .schemas import ProblemEmbeddingRecord, ProblemRecord


DEFAULT_PROBLEM_EMBEDDINGS_MANIFEST_PATH = (
    DEFAULT_PROBLEM_EMBEDDINGS_PATH.parents[1]
    / "manifests"
    / "problem-embeddings-manifest.json"
)
DEFAULT_PROBLEM_EMBEDDINGS_SUMMARY_PATH = (
    DEFAULT_PROBLEM_EMBEDDINGS_PATH.parents[1]
    / "reports"
    / "problem-embeddings-summary.md"
)


@dataclass(frozen=True)
class ProblemEmbeddingBuildIssue:
    reason: str
    problem_id: int | None = None
    source_key: str | None = None
    message: str = ""

    def to_dict(self) -> dict[str, Any]:
        payload: dict[str, Any] = {"reason": self.reason}
        if self.problem_id is not None:
            payload["problemId"] = self.problem_id
        if self.source_key is not None:
            payload["sourceKey"] = self.source_key
        if self.message:
            payload["message"] = self.message
        return payload


@dataclass(frozen=True)
class ProblemEmbeddingBuildResult:
    records: tuple[ProblemEmbeddingRecord, ...] = field(default_factory=tuple)
    issues: tuple[ProblemEmbeddingBuildIssue, ...] = field(default_factory=tuple)
    reused_count: int = 0
    generated_count: int = 0
    failed_count: int = 0
    output_path: str = ""
    manifest_path: str = ""
    summary_path: str = ""
    model: str = DEFAULT_EMBEDDING_MODEL
    output_dimensionality: int = DEFAULT_EMBEDDING_DIMENSIONS

    @property
    def summary(self) -> dict[str, Any]:
        return {
            "recordCount": len(self.records),
            "reusedCount": self.reused_count,
            "generatedCount": self.generated_count,
            "failedCount": self.failed_count,
            "issueCount": len(self.issues),
            "outputPath": self.output_path,
            "manifestPath": self.manifest_path,
            "summaryPath": self.summary_path,
            "model": self.model,
            "outputDimensionality": self.output_dimensionality,
            "issues": [issue.to_dict() for issue in self.issues],
        }


def build_problem_bank_embeddings(
    *,
    catalog_path: Path = DEFAULT_CATALOG_PATH,
    embeddings_path: Path = DEFAULT_PROBLEM_EMBEDDINGS_PATH,
    manifest_path: Path = DEFAULT_PROBLEM_EMBEDDINGS_MANIFEST_PATH,
    summary_path: Path = DEFAULT_PROBLEM_EMBEDDINGS_SUMMARY_PATH,
    client: EmbeddingClient | None = None,
    model: str | None = None,
    output_dimensionality: int | None = None,
) -> ProblemEmbeddingBuildResult:
    settings = (
        GeminiEmbeddingSettings.from_env()
        if client is None or model is None or output_dimensionality is None
        else None
    )
    resolved_model = model or (settings.model if settings else DEFAULT_EMBEDDING_MODEL)
    resolved_dimensionality = output_dimensionality or (
        settings.output_dimensionality if settings else DEFAULT_EMBEDDING_DIMENSIONS
    )

    problem_result = read_problem_jsonl(catalog_path)
    problems = problem_result.records
    issues: list[ProblemEmbeddingBuildIssue] = [
        ProblemEmbeddingBuildIssue(
            reason=f"catalog_{issue.reason}",
            message=issue.message,
        )
        for issue in problem_result.issues
    ]

    existing_result = read_problem_embedding_jsonl(embeddings_path)
    issues.extend(
        ProblemEmbeddingBuildIssue(
            reason=f"embedding_{issue.reason}",
            problem_id=issue.problem_id,
            source_key=issue.source_key,
            message=issue.message,
        )
        for issue in existing_result.issues
    )
    existing = _latest_by_problem_key(existing_result.records)
    embedder = client or GeminiEmbeddingClient(settings)

    known_keys = {_problem_key(problem) for problem in problems}
    for record in existing_result.records:
        if _record_key(record) not in known_keys:
            issues.append(
                ProblemEmbeddingBuildIssue(
                    reason="unknown_problem",
                    problem_id=record.problem_id,
                    source_key=record.source_key,
                    message="embedding record does not have a matching problem",
                )
            )

    records: list[ProblemEmbeddingRecord] = []
    reused_count = 0
    generated_count = 0
    failed_count = 0

    for problem in problems:
        reusable = _matching_record(
            problem,
            existing.get(_problem_key(problem)),
            model=resolved_model,
            output_dimensionality=resolved_dimensionality,
            issues=issues,
        )
        if reusable is not None:
            records.append(reusable)
            reused_count += 1
            continue

        try:
            embedding = embedder.embed_document(build_problem_embedding_input(problem))
            record = make_problem_embedding_record(
                problem,
                embedding=embedding,
                model=resolved_model,
                output_dimensionality=resolved_dimensionality,
            )
        except Exception as exc:
            failed_count += 1
            issues.append(
                ProblemEmbeddingBuildIssue(
                    reason="embedding_failed",
                    problem_id=problem.problem_id,
                    source_key=problem.source_key,
                    message=str(exc),
                )
            )
            continue

        records.append(record)
        generated_count += 1

    records = sorted(
        records,
        key=lambda record: (
            _problem_sort_key(problems, record.problem_id, record.source_key),
            record.source_path,
            record.problem_id,
            record.source_key,
        ),
    )
    write_problem_embedding_jsonl(records, embeddings_path)
    result = ProblemEmbeddingBuildResult(
        records=tuple(records),
        issues=tuple(issues),
        reused_count=reused_count,
        generated_count=generated_count,
        failed_count=failed_count,
        output_path=Path(embeddings_path).as_posix(),
        manifest_path=Path(manifest_path).as_posix(),
        summary_path=Path(summary_path).as_posix(),
        model=resolved_model,
        output_dimensionality=resolved_dimensionality,
    )
    write_problem_embedding_reports(
        result,
        catalog_path=catalog_path,
        manifest_path=manifest_path,
        summary_path=summary_path,
    )
    return result


def write_problem_embedding_reports(
    result: ProblemEmbeddingBuildResult,
    *,
    catalog_path: Path,
    manifest_path: Path = DEFAULT_PROBLEM_EMBEDDINGS_MANIFEST_PATH,
    summary_path: Path = DEFAULT_PROBLEM_EMBEDDINGS_SUMMARY_PATH,
) -> None:
    manifest = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "catalogPath": Path(catalog_path).as_posix(),
        "embeddingsPath": result.output_path,
        "recordCount": len(result.records),
        "reusedCount": result.reused_count,
        "generatedCount": result.generated_count,
        "failedCount": result.failed_count,
        "issueCount": len(result.issues),
        "model": result.model,
        "outputDimensionality": result.output_dimensionality,
        "issues": [issue.to_dict() for issue in result.issues],
    }
    manifest_file = Path(manifest_path).expanduser()
    summary_file = Path(summary_path).expanduser()
    manifest_file.parent.mkdir(parents=True, exist_ok=True)
    summary_file.parent.mkdir(parents=True, exist_ok=True)
    manifest_file.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    summary_file.write_text(_summary_markdown(manifest), encoding="utf-8")


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Build Gemini embedding sidecar JSONL for problem bank records.",
    )
    parser.add_argument("--catalog", type=Path, default=DEFAULT_CATALOG_PATH)
    parser.add_argument("--out", type=Path, default=DEFAULT_PROBLEM_EMBEDDINGS_PATH)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_PROBLEM_EMBEDDINGS_MANIFEST_PATH)
    parser.add_argument("--summary", type=Path, default=DEFAULT_PROBLEM_EMBEDDINGS_SUMMARY_PATH)
    parser.add_argument("--model", default=None)
    parser.add_argument("--dimensions", type=int, default=None)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        result = build_problem_bank_embeddings(
            catalog_path=args.catalog,
            embeddings_path=args.out,
            manifest_path=args.manifest,
            summary_path=args.summary,
            model=args.model,
            output_dimensionality=args.dimensions,
        )
    except (FileNotFoundError, ValueError, RuntimeError) as exc:
        print(f"problem embedding build failed: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(result.summary, ensure_ascii=False, indent=2, sort_keys=True))
    return 1 if result.failed_count else 0


def _latest_by_problem_key(
    records: tuple[ProblemEmbeddingRecord, ...],
) -> dict[tuple[int, str], ProblemEmbeddingRecord]:
    result: dict[tuple[int, str], ProblemEmbeddingRecord] = {}
    for record in records:
        result[_record_key(record)] = record
    return result


def _matching_record(
    problem: ProblemRecord,
    record: ProblemEmbeddingRecord | None,
    *,
    model: str,
    output_dimensionality: int,
    issues: list[ProblemEmbeddingBuildIssue],
) -> ProblemEmbeddingRecord | None:
    if record is None:
        issues.append(
            ProblemEmbeddingBuildIssue(
                reason="missing_embedding",
                problem_id=problem.problem_id,
                source_key=problem.source_key,
                message="problem does not have an embedding record",
            )
        )
        return None
    if record.content_hash != problem.content_hash:
        issues.append(
            ProblemEmbeddingBuildIssue(
                reason="stale_content_hash",
                problem_id=problem.problem_id,
                source_key=problem.source_key,
                message="embedding contentHash does not match problem contentHash",
            )
        )
        return None
    if record.model != model:
        issues.append(
            ProblemEmbeddingBuildIssue(
                reason="stale_model",
                problem_id=problem.problem_id,
                source_key=problem.source_key,
                message=f"embedding model is {record.model}, expected {model}",
            )
        )
        return None
    if record.output_dimensionality != output_dimensionality or len(record.embedding) != output_dimensionality:
        issues.append(
            ProblemEmbeddingBuildIssue(
                reason="stale_output_dimensionality",
                problem_id=problem.problem_id,
                source_key=problem.source_key,
                message=(
                    "embedding outputDimensionality does not match expected "
                    f"{output_dimensionality}"
                ),
            )
        )
        return None
    return record


def _problem_key(problem: ProblemRecord) -> tuple[int, str]:
    return problem.problem_id, problem.source_key


def _record_key(record: ProblemEmbeddingRecord) -> tuple[int, str]:
    return record.problem_id, record.source_key


def _problem_sort_key(
    problems: tuple[ProblemRecord, ...],
    problem_id: int,
    source_key: str,
) -> int:
    for index, problem in enumerate(problems):
        if problem.problem_id == problem_id and problem.source_key == source_key:
            return index
    return len(problems)


def _summary_markdown(summary: dict[str, Any]) -> str:
    lines = [
        "# Problem Embeddings Summary",
        "",
        f"- Catalog path: `{summary['catalogPath']}`",
        f"- Embeddings path: `{summary['embeddingsPath']}`",
        f"- Model: `{summary['model']}`",
        f"- Output dimensionality: {summary['outputDimensionality']}",
        f"- Records: {summary['recordCount']}",
        f"- Reused: {summary['reusedCount']}",
        f"- Generated: {summary['generatedCount']}",
        f"- Failed: {summary['failedCount']}",
        f"- Issues: {summary['issueCount']}",
        "",
    ]
    if summary["issues"]:
        lines.extend(["## Issues", ""])
        for issue in summary["issues"]:
            label = issue.get("problemId", issue.get("sourceKey", "catalog"))
            message = issue.get("message", "")
            suffix = f" - {message}" if message else ""
            lines.append(f"- `{issue['reason']}` ({label}){suffix}")
        lines.append("")
    return "\n".join(lines)


if __name__ == "__main__":
    raise SystemExit(main())
