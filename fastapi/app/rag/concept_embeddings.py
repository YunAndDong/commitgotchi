from __future__ import annotations

import json
import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable, Mapping, Protocol

from .concept_store import FASTAPI_ROOT
from .schemas import ConceptChunkRecord, ConceptEmbeddingRecord
from .text import clean_markdown, normalize_text


DEFAULT_EMBEDDING_MODEL = "gemini-embedding-2"
DEFAULT_EMBEDDING_DIMENSIONS = 768
DEFAULT_EMBEDDINGS_PATH = FASTAPI_ROOT / "data" / "rag" / "catalog" / "chunk-embeddings.jsonl"


class EmbeddingClient(Protocol):
    def embed_document(self, text: str) -> tuple[float, ...]:
        ...

    def embed_query(self, text: str) -> tuple[float, ...]:
        ...


class ConceptEmbeddingDecodeError(ValueError):
    """Raised when a JSON object cannot be converted into a ConceptEmbeddingRecord."""


@dataclass(frozen=True)
class GeminiEmbeddingSettings:
    api_key: str
    model: str = DEFAULT_EMBEDDING_MODEL
    output_dimensionality: int = DEFAULT_EMBEDDING_DIMENSIONS

    @classmethod
    def from_env(cls) -> "GeminiEmbeddingSettings":
        dotenv = _read_dotenv(FASTAPI_ROOT / ".env")
        raw_dimensions = (
            os.getenv("GEMINI_EMBEDDING_DIMENSIONS")
            or dotenv.get("GEMINI_EMBEDDING_DIMENSIONS")
            or str(DEFAULT_EMBEDDING_DIMENSIONS)
        )
        try:
            output_dimensionality = int(raw_dimensions)
        except ValueError as exc:
            raise ValueError("GEMINI_EMBEDDING_DIMENSIONS must be an integer") from exc
        if output_dimensionality <= 0:
            raise ValueError("GEMINI_EMBEDDING_DIMENSIONS must be positive")
        return cls(
            api_key=os.getenv("GEMINI_API_KEY") or dotenv.get("GEMINI_API_KEY") or "",
            model=os.getenv("GEMINI_EMBEDDING_MODEL")
            or dotenv.get("GEMINI_EMBEDDING_MODEL")
            or DEFAULT_EMBEDDING_MODEL,
            output_dimensionality=output_dimensionality,
        )


class GeminiEmbeddingClient:
    def __init__(self, settings: GeminiEmbeddingSettings | None = None):
        self.settings = settings or GeminiEmbeddingSettings.from_env()
        self._client: Any | None = None

    def embed_document(self, text: str) -> tuple[float, ...]:
        return self._embed(f"Document for semantic retrieval:\n{normalize_text(text)}")

    def embed_query(self, text: str) -> tuple[float, ...]:
        return self._embed(f"Query for semantic retrieval:\n{normalize_text(text)}")

    def _embed(self, text: str) -> tuple[float, ...]:
        if not self.settings.api_key:
            raise ValueError("GEMINI_API_KEY is required for Gemini embeddings")
        client = self._get_client()
        config = self._embed_config()
        response = client.models.embed_content(
            model=self.settings.model,
            contents=text,
            config=config,
        )
        return _embedding_values_from_response(response)

    def _get_client(self) -> Any:
        if self._client is None:
            try:
                from google import genai
            except ImportError as exc:
                raise RuntimeError("google-genai package is required for Gemini embeddings") from exc
            self._client = genai.Client(api_key=self.settings.api_key)
        return self._client

    def _embed_config(self) -> Any:
        try:
            from google.genai import types
        except ImportError:
            return {"output_dimensionality": self.settings.output_dimensionality}
        return types.EmbedContentConfig(
            output_dimensionality=self.settings.output_dimensionality,
        )


@dataclass(frozen=True)
class ConceptEmbeddingLoadIssue:
    line_number: int
    reason: str
    message: str
    chunk_id: str | None = None


@dataclass(frozen=True)
class ConceptEmbeddingLoadResult:
    records: tuple[ConceptEmbeddingRecord, ...] = field(default_factory=tuple)
    issues: tuple[ConceptEmbeddingLoadIssue, ...] = field(default_factory=tuple)


def build_document_embedding_input(chunk: ConceptChunkRecord) -> str:
    return clean_markdown(
        "\n".join(
            (
                "Concept chunk document",
                f"Source: {chunk.source_path}",
                f"Heading: {' > '.join(chunk.heading_path)}",
                f"Field hints: {', '.join(chunk.field_hints)}",
                "Text:",
                chunk.text,
            )
        )
    )


def build_query_embedding_input(
    query: str,
    *,
    topic_hints: Iterable[str] = (),
    field_hints: Iterable[str] = (),
) -> str:
    return clean_markdown(
        "\n".join(
            (
                "Report chunk retrieval query",
                f"Topic hints: {', '.join(topic_hints)}",
                f"Field hints: {', '.join(field_hints)}",
                "Report text:",
                query,
            )
        )
    )


def make_concept_embedding_record(
    chunk: ConceptChunkRecord,
    *,
    embedding: Iterable[float],
    model: str,
    output_dimensionality: int,
) -> ConceptEmbeddingRecord:
    values = tuple(float(value) for value in embedding)
    if len(values) != output_dimensionality:
        raise ValueError(
            f"embedding dimension mismatch for {chunk.chunk_id}: "
            f"expected {output_dimensionality}, got {len(values)}"
        )
    return ConceptEmbeddingRecord(
        chunk_id=chunk.chunk_id,
        content_hash=chunk.content_hash,
        model=model,
        output_dimensionality=output_dimensionality,
        source_path=chunk.source_path,
        heading_path=chunk.heading_path,
        embedding=values,
    )


def read_concept_embedding_jsonl(
    path: Path = DEFAULT_EMBEDDINGS_PATH,
    *,
    strict: bool = False,
) -> ConceptEmbeddingLoadResult:
    embeddings_path = Path(path).expanduser()
    if not embeddings_path.exists():
        return ConceptEmbeddingLoadResult()
    if not embeddings_path.is_file():
        issue = ConceptEmbeddingLoadIssue(
            line_number=0,
            reason="not_a_file",
            message=f"embedding path is not a file: {embeddings_path}",
        )
        if strict:
            raise FileNotFoundError(issue.message)
        return ConceptEmbeddingLoadResult(issues=(issue,))

    records: list[ConceptEmbeddingRecord] = []
    issues: list[ConceptEmbeddingLoadIssue] = []
    try:
        with embeddings_path.open("r", encoding="utf-8") as file:
            for line_number, line in enumerate(file, start=1):
                stripped = line.strip()
                if not stripped:
                    continue
                try:
                    payload = json.loads(stripped)
                    record = concept_embedding_record_from_dict(payload)
                except json.JSONDecodeError as exc:
                    issue = ConceptEmbeddingLoadIssue(
                        line_number=line_number,
                        reason="json_decode_failed",
                        message=str(exc),
                    )
                except ConceptEmbeddingDecodeError as exc:
                    issue = ConceptEmbeddingLoadIssue(
                        line_number=line_number,
                        reason="schema_validation_failed",
                        message=str(exc),
                    )
                else:
                    records.append(record)
                    continue

                if strict:
                    raise ConceptEmbeddingDecodeError(
                        f"{embeddings_path}:{line_number}: {issue.message}"
                    ) from None
                issues.append(issue)
    except (OSError, UnicodeDecodeError) as exc:
        issue = ConceptEmbeddingLoadIssue(
            line_number=0,
            reason="read_failed",
            message=str(exc),
        )
        if strict:
            raise
        return ConceptEmbeddingLoadResult(issues=(issue,))

    return ConceptEmbeddingLoadResult(records=tuple(records), issues=tuple(issues))


def write_concept_embedding_jsonl(
    records: Iterable[ConceptEmbeddingRecord],
    path: Path = DEFAULT_EMBEDDINGS_PATH,
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


def concept_embedding_record_from_dict(payload: Any) -> ConceptEmbeddingRecord:
    if not isinstance(payload, Mapping):
        raise ConceptEmbeddingDecodeError("record must be a JSON object")

    record = ConceptEmbeddingRecord(
        chunk_id=_required_str(payload, "chunkId"),
        content_hash=_required_str(payload, "contentHash"),
        model=_required_str(payload, "model"),
        output_dimensionality=_required_int(payload, "outputDimensionality"),
        source_path=_required_str(payload, "sourcePath"),
        heading_path=_required_str_tuple(payload, "headingPath"),
        embedding=_required_float_tuple(payload, "embedding"),
    )
    errors = validate_concept_embedding_record(record)
    if errors:
        raise ConceptEmbeddingDecodeError("; ".join(errors))
    return record


def validate_concept_embedding_record(record: ConceptEmbeddingRecord) -> list[str]:
    errors: list[str] = []
    if not record.chunk_id.startswith("concept:sha256:"):
        errors.append("chunkId must start with concept:sha256:")
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


def _embedding_values_from_response(response: Any) -> tuple[float, ...]:
    embeddings = _get_value(response, "embeddings")
    if embeddings:
        first_embedding = embeddings[0]
        values = _get_value(first_embedding, "values")
        if values is not None:
            return tuple(float(value) for value in values)

    embedding = _get_value(response, "embedding")
    if embedding is not None:
        values = _get_value(embedding, "values")
        if values is not None:
            return tuple(float(value) for value in values)

    values = _get_value(response, "values")
    if values is not None:
        return tuple(float(value) for value in values)

    raise RuntimeError("Gemini embedding response did not include vector values")


def _get_value(value: Any, key: str) -> Any:
    if isinstance(value, Mapping):
        return value.get(key)
    return getattr(value, key, None)


def _read_dotenv(path: Path) -> dict[str, str]:
    if not path.exists() or not path.is_file():
        return {}
    values: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def _required_value(payload: Mapping[str, Any], key: str) -> Any:
    if key not in payload:
        raise ConceptEmbeddingDecodeError(f"{key} is required")
    return payload[key]


def _required_int(payload: Mapping[str, Any], key: str) -> int:
    value = _required_value(payload, key)
    if not isinstance(value, int) or isinstance(value, bool):
        raise ConceptEmbeddingDecodeError(f"{key} must be an integer")
    return value


def _required_str(payload: Mapping[str, Any], key: str) -> str:
    value = _required_value(payload, key)
    if not isinstance(value, str):
        raise ConceptEmbeddingDecodeError(f"{key} must be a string")
    return value


def _required_str_tuple(payload: Mapping[str, Any], key: str) -> tuple[str, ...]:
    value = _required_value(payload, key)
    if not isinstance(value, list):
        raise ConceptEmbeddingDecodeError(f"{key} must be an array")
    result: list[str] = []
    for index, item in enumerate(value):
        if not isinstance(item, str):
            raise ConceptEmbeddingDecodeError(f"{key}[{index}] must be a string")
        result.append(item)
    return tuple(result)


def _required_float_tuple(payload: Mapping[str, Any], key: str) -> tuple[float, ...]:
    value = _required_value(payload, key)
    if not isinstance(value, list):
        raise ConceptEmbeddingDecodeError(f"{key} must be an array")
    result: list[float] = []
    for index, item in enumerate(value):
        if not isinstance(item, (int, float)) or isinstance(item, bool):
            raise ConceptEmbeddingDecodeError(f"{key}[{index}] must be a number")
        result.append(float(item))
    return tuple(result)
