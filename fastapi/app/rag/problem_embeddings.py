from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable, Mapping

from .problem_bank_store import FASTAPI_ROOT
from .schemas import ProblemEmbeddingRecord, ProblemRecord
from .text import clean_markdown


DEFAULT_PROBLEM_EMBEDDINGS_PATH = (
    FASTAPI_ROOT / "data" / "rag" / "catalog" / "problem-embeddings.jsonl"
)


class ProblemEmbeddingDecodeError(ValueError):
    """Raised when a JSON object cannot be converted into a ProblemEmbeddingRecord."""


@dataclass(frozen=True)
class ProblemEmbeddingLoadIssue:
    line_number: int
    reason: str
    message: str
    problem_id: int | None = None
    source_key: str | None = None


@dataclass(frozen=True)
class ProblemEmbeddingLoadResult:
    records: tuple[ProblemEmbeddingRecord, ...] = field(default_factory=tuple)
    issues: tuple[ProblemEmbeddingLoadIssue, ...] = field(default_factory=tuple)


def build_problem_embedding_input(problem: ProblemRecord) -> str:
    nonzero_scores = [
        f"{field_name}={score}"
        for field_name, score in problem.score_allocation.items()
        if score
    ]
    return clean_markdown(
        "\n".join(
            (
                "Problem bank document",
                f"Source: {problem.source_path}",
                f"Heading: {' > '.join(problem.heading_path)}",
                f"Primary field: {problem.primary_field}",
                f"Fields: {', '.join(problem.fields)}",
                f"Score allocation: {', '.join(nonzero_scores)}",
                f"Rubric must mention: {', '.join(problem.rubric.must_mention)}",
                f"Rubric optional mention: {', '.join(problem.rubric.optional_mention)}",
                "Question:",
                problem.question,
                "Model answer:",
                problem.model_answer,
            )
        )
    )


def build_problem_query_embedding_input(
    query: str,
    *,
    field: str | None = None,
    difficulty: str | None = None,
) -> str:
    return clean_markdown(
        "\n".join(
            (
                "Problem bank retrieval query",
                f"Field filter: {field or ''}",
                f"Difficulty filter: {difficulty or ''}",
                "Report text:",
                query,
            )
        )
    )


def make_problem_embedding_record(
    problem: ProblemRecord,
    *,
    embedding: Iterable[float],
    model: str,
    output_dimensionality: int,
) -> ProblemEmbeddingRecord:
    values = tuple(float(value) for value in embedding)
    if len(values) != output_dimensionality:
        raise ValueError(
            f"embedding dimension mismatch for problem {problem.problem_id}: "
            f"expected {output_dimensionality}, got {len(values)}"
        )
    return ProblemEmbeddingRecord(
        problem_id=problem.problem_id,
        source_key=problem.source_key,
        content_hash=problem.content_hash,
        model=model,
        output_dimensionality=output_dimensionality,
        source_path=problem.source_path,
        heading_path=problem.heading_path,
        embedding=values,
    )


def read_problem_embedding_jsonl(
    path: Path = DEFAULT_PROBLEM_EMBEDDINGS_PATH,
    *,
    strict: bool = False,
) -> ProblemEmbeddingLoadResult:
    embeddings_path = Path(path).expanduser()
    if not embeddings_path.exists():
        return ProblemEmbeddingLoadResult()
    if not embeddings_path.is_file():
        issue = ProblemEmbeddingLoadIssue(
            line_number=0,
            reason="not_a_file",
            message=f"problem embedding path is not a file: {embeddings_path}",
        )
        if strict:
            raise FileNotFoundError(issue.message)
        return ProblemEmbeddingLoadResult(issues=(issue,))

    records: list[ProblemEmbeddingRecord] = []
    issues: list[ProblemEmbeddingLoadIssue] = []
    try:
        with embeddings_path.open("r", encoding="utf-8") as file:
            for line_number, line in enumerate(file, start=1):
                stripped = line.strip()
                if not stripped:
                    continue
                try:
                    payload = json.loads(stripped)
                    record = problem_embedding_record_from_dict(payload)
                except json.JSONDecodeError as exc:
                    issue = ProblemEmbeddingLoadIssue(
                        line_number=line_number,
                        reason="json_decode_failed",
                        message=str(exc),
                    )
                except ProblemEmbeddingDecodeError as exc:
                    issue = ProblemEmbeddingLoadIssue(
                        line_number=line_number,
                        reason="schema_validation_failed",
                        message=str(exc),
                    )
                else:
                    records.append(record)
                    continue

                if strict:
                    raise ProblemEmbeddingDecodeError(
                        f"{embeddings_path}:{line_number}: {issue.message}"
                    ) from None
                issues.append(issue)
    except (OSError, UnicodeDecodeError) as exc:
        issue = ProblemEmbeddingLoadIssue(
            line_number=0,
            reason="read_failed",
            message=str(exc),
        )
        if strict:
            raise
        return ProblemEmbeddingLoadResult(issues=(issue,))

    return ProblemEmbeddingLoadResult(records=tuple(records), issues=tuple(issues))


def write_problem_embedding_jsonl(
    records: Iterable[ProblemEmbeddingRecord],
    path: Path = DEFAULT_PROBLEM_EMBEDDINGS_PATH,
) -> None:
    embeddings_path = Path(path).expanduser()
    embeddings_path.parent.mkdir(parents=True, exist_ok=True)
    embeddings_path.write_text(
        "".join(
            json.dumps(record.to_json_dict(), ensure_ascii=False) + "\n"
            for record in records
        ),
        encoding="utf-8",
    )


def problem_embedding_record_from_dict(payload: Any) -> ProblemEmbeddingRecord:
    if not isinstance(payload, Mapping):
        raise ProblemEmbeddingDecodeError("record must be a JSON object")

    record = ProblemEmbeddingRecord(
        problem_id=_required_int(payload, "problemId"),
        source_key=_required_str(payload, "sourceKey"),
        content_hash=_required_str(payload, "contentHash"),
        model=_required_str(payload, "model"),
        output_dimensionality=_required_int(payload, "outputDimensionality"),
        source_path=_required_str(payload, "sourcePath"),
        heading_path=_required_str_tuple(payload, "headingPath"),
        embedding=_required_float_tuple(payload, "embedding"),
    )
    errors = validate_problem_embedding_record(record)
    if errors:
        raise ProblemEmbeddingDecodeError("; ".join(errors))
    return record


def validate_problem_embedding_record(record: ProblemEmbeddingRecord) -> list[str]:
    errors: list[str] = []
    if record.problem_id < 0:
        errors.append("problemId must be non-negative")
    if not record.source_key.startswith("sha256:"):
        errors.append("sourceKey must start with sha256:")
    if not record.content_hash.startswith("sha256:"):
        errors.append("contentHash must start with sha256:")
    if not record.model.strip():
        errors.append("model is required")
    if record.output_dimensionality <= 0:
        errors.append("outputDimensionality must be positive")
    if len(record.embedding) != record.output_dimensionality:
        errors.append("embedding length must match outputDimensionality")
    if not record.source_path.strip():
        errors.append("sourcePath is required")
    if record.source_path.startswith("/"):
        errors.append("sourcePath must be relative")
    if not record.heading_path:
        errors.append("headingPath is required")
    return errors


def _required_value(payload: Mapping[str, Any], key: str) -> Any:
    if key not in payload:
        raise ProblemEmbeddingDecodeError(f"{key} is required")
    return payload[key]


def _required_int(payload: Mapping[str, Any], key: str) -> int:
    value = _required_value(payload, key)
    if not isinstance(value, int) or isinstance(value, bool):
        raise ProblemEmbeddingDecodeError(f"{key} must be an integer")
    return value


def _required_str(payload: Mapping[str, Any], key: str) -> str:
    value = _required_value(payload, key)
    if not isinstance(value, str):
        raise ProblemEmbeddingDecodeError(f"{key} must be a string")
    return value


def _required_str_tuple(payload: Mapping[str, Any], key: str) -> tuple[str, ...]:
    value = _required_value(payload, key)
    if not isinstance(value, list):
        raise ProblemEmbeddingDecodeError(f"{key} must be an array")
    result: list[str] = []
    for index, item in enumerate(value):
        if not isinstance(item, str):
            raise ProblemEmbeddingDecodeError(f"{key}[{index}] must be a string")
        result.append(item)
    return tuple(result)


def _required_float_tuple(payload: Mapping[str, Any], key: str) -> tuple[float, ...]:
    value = _required_value(payload, key)
    if not isinstance(value, list):
        raise ProblemEmbeddingDecodeError(f"{key} must be an array")
    result: list[float] = []
    for index, item in enumerate(value):
        if not isinstance(item, (int, float)) or isinstance(item, bool):
            raise ProblemEmbeddingDecodeError(f"{key}[{index}] must be a number")
        result.append(float(item))
    return tuple(result)
