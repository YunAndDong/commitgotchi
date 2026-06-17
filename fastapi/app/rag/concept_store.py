from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable, Mapping

from .schemas import (
    SCORE_FIELDS,
    ConceptChunkRecord,
    ConceptNeighbors,
    validate_concept_chunk_record,
)


FASTAPI_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CATALOG_PATH = FASTAPI_ROOT / "data" / "rag" / "catalog" / "chunks.jsonl"


class ConceptChunkDecodeError(ValueError):
    """Raised when a JSON object cannot be converted into a ConceptChunkRecord."""


@dataclass(frozen=True)
class ConceptCatalogLoadIssue:
    line_number: int
    reason: str
    message: str


@dataclass(frozen=True)
class ConceptCatalogLoadResult:
    records: tuple[ConceptChunkRecord, ...] = field(default_factory=tuple)
    issues: tuple[ConceptCatalogLoadIssue, ...] = field(default_factory=tuple)


@dataclass(frozen=True)
class ConceptCatalogStore:
    records: tuple[ConceptChunkRecord, ...] = field(default_factory=tuple)
    _by_chunk_id: dict[str, ConceptChunkRecord] = field(init=False, repr=False)
    _by_source_path: dict[str, tuple[ConceptChunkRecord, ...]] = field(init=False, repr=False)

    def __post_init__(self) -> None:
        records = tuple(self.records)
        by_chunk_id: dict[str, ConceptChunkRecord] = {}
        by_source_lists: dict[str, list[ConceptChunkRecord]] = {}
        for record in records:
            by_chunk_id.setdefault(record.chunk_id, record)
            by_source_lists.setdefault(record.source_path, []).append(record)
        by_source_path = {
            source_path: tuple(sorted(items, key=lambda item: item.chunk_index))
            for source_path, items in by_source_lists.items()
        }
        object.__setattr__(self, "records", records)
        object.__setattr__(self, "_by_chunk_id", by_chunk_id)
        object.__setattr__(self, "_by_source_path", by_source_path)

    def __iter__(self):
        return iter(self.records)

    def __len__(self) -> int:
        return len(self.records)

    def get(self, chunk_id: str) -> ConceptChunkRecord | None:
        return self._by_chunk_id.get(chunk_id)

    def by_source_path(self, source_path: str) -> tuple[ConceptChunkRecord, ...]:
        return self._by_source_path.get(source_path, ())

    def filter_by_field_hint(self, field_hint: str) -> tuple[ConceptChunkRecord, ...]:
        if field_hint not in SCORE_FIELDS:
            return ()
        return tuple(record for record in self.records if field_hint in record.field_hints)


def load_concept_catalog_store(path: Path = DEFAULT_CATALOG_PATH) -> ConceptCatalogStore:
    return ConceptCatalogStore(read_concept_jsonl(path).records)


def read_concept_jsonl(
    path: Path = DEFAULT_CATALOG_PATH,
    *,
    strict: bool = False,
) -> ConceptCatalogLoadResult:
    catalog_path = Path(path).expanduser()
    if not catalog_path.exists():
        return ConceptCatalogLoadResult()
    if not catalog_path.is_file():
        issue = ConceptCatalogLoadIssue(
            line_number=0,
            reason="not_a_file",
            message=f"concept catalog path is not a file: {catalog_path}",
        )
        if strict:
            raise FileNotFoundError(issue.message)
        return ConceptCatalogLoadResult(issues=(issue,))

    records: list[ConceptChunkRecord] = []
    issues: list[ConceptCatalogLoadIssue] = []
    try:
        with catalog_path.open("r", encoding="utf-8") as file:
            for line_number, line in enumerate(file, start=1):
                stripped = line.strip()
                if not stripped:
                    continue
                try:
                    payload = json.loads(stripped)
                    record = concept_chunk_record_from_dict(payload)
                except json.JSONDecodeError as exc:
                    issue = ConceptCatalogLoadIssue(
                        line_number=line_number,
                        reason="json_decode_failed",
                        message=str(exc),
                    )
                except ConceptChunkDecodeError as exc:
                    issue = ConceptCatalogLoadIssue(
                        line_number=line_number,
                        reason="schema_validation_failed",
                        message=str(exc),
                    )
                else:
                    records.append(record)
                    continue

                if strict:
                    raise ConceptChunkDecodeError(
                        f"{catalog_path}:{line_number}: {issue.message}"
                    ) from None
                issues.append(issue)
    except (OSError, UnicodeDecodeError) as exc:
        issue = ConceptCatalogLoadIssue(
            line_number=0,
            reason="read_failed",
            message=str(exc),
        )
        if strict:
            raise
        return ConceptCatalogLoadResult(issues=(issue,))

    return ConceptCatalogLoadResult(records=tuple(records), issues=tuple(issues))


def concept_chunk_record_from_dict(payload: Any) -> ConceptChunkRecord:
    if not isinstance(payload, Mapping):
        raise ConceptChunkDecodeError("record must be a JSON object")

    record = ConceptChunkRecord(
        chunk_id=_required_str(payload, "chunkId"),
        source_path=_required_str(payload, "sourcePath"),
        source_root=_required_str(payload, "sourceRoot"),
        heading_path=_required_str_tuple(payload, "headingPath"),
        heading_level=_required_int(payload, "headingLevel"),
        chunk_index=_required_int(payload, "chunkIndex"),
        text=_required_str(payload, "text"),
        content_hash=_required_str(payload, "contentHash"),
        field_hints=_required_str_tuple(payload, "fieldHints"),
        neighbors=_required_neighbors(payload, "neighbors"),
        char_start=_optional_int(payload, "charStart"),
        char_end=_optional_int(payload, "charEnd"),
    )
    errors = validate_concept_chunk_record(record)
    if errors:
        raise ConceptChunkDecodeError("; ".join(errors))
    return record


def _required_value(payload: Mapping[str, Any], key: str) -> Any:
    if key not in payload:
        raise ConceptChunkDecodeError(f"{key} is required")
    return payload[key]


def _required_int(payload: Mapping[str, Any], key: str) -> int:
    value = _required_value(payload, key)
    if not isinstance(value, int) or isinstance(value, bool):
        raise ConceptChunkDecodeError(f"{key} must be an integer")
    return value


def _optional_int(payload: Mapping[str, Any], key: str) -> int | None:
    if key not in payload or payload[key] is None:
        return None
    value = payload[key]
    if not isinstance(value, int) or isinstance(value, bool):
        raise ConceptChunkDecodeError(f"{key} must be an integer")
    return value


def _required_str(payload: Mapping[str, Any], key: str) -> str:
    value = _required_value(payload, key)
    if not isinstance(value, str):
        raise ConceptChunkDecodeError(f"{key} must be a string")
    return value


def _required_str_tuple(payload: Mapping[str, Any], key: str) -> tuple[str, ...]:
    value = _required_value(payload, key)
    if not isinstance(value, list):
        raise ConceptChunkDecodeError(f"{key} must be an array")
    return _str_tuple(value, key)


def _str_tuple(values: Iterable[Any], key: str) -> tuple[str, ...]:
    result: list[str] = []
    for index, value in enumerate(values):
        if not isinstance(value, str):
            raise ConceptChunkDecodeError(f"{key}[{index}] must be a string")
        result.append(value)
    return tuple(result)


def _required_neighbors(payload: Mapping[str, Any], key: str) -> ConceptNeighbors:
    value = _required_value(payload, key)
    if not isinstance(value, Mapping):
        raise ConceptChunkDecodeError(f"{key} must be an object")
    return ConceptNeighbors(
        previous_chunk_id=_optional_str(value, "previousChunkId"),
        next_chunk_id=_optional_str(value, "nextChunkId"),
        parent_heading_path=_optional_str_tuple(value, "parentHeadingPath"),
        sibling_heading_paths=_optional_heading_path_tuple(value, "siblingHeadingPaths"),
    )


def _optional_str(payload: Mapping[str, Any], key: str) -> str | None:
    value = payload.get(key)
    if value is None:
        return None
    if not isinstance(value, str):
        raise ConceptChunkDecodeError(f"{key} must be a string or null")
    return value


def _optional_str_tuple(payload: Mapping[str, Any], key: str) -> tuple[str, ...]:
    value = payload.get(key, [])
    if not isinstance(value, list):
        raise ConceptChunkDecodeError(f"{key} must be an array")
    return _str_tuple(value, key)


def _optional_heading_path_tuple(
    payload: Mapping[str, Any],
    key: str,
) -> tuple[tuple[str, ...], ...]:
    value = payload.get(key, [])
    if not isinstance(value, list):
        raise ConceptChunkDecodeError(f"{key} must be an array")
    result: list[tuple[str, ...]] = []
    for index, heading_path in enumerate(value):
        if not isinstance(heading_path, list):
            raise ConceptChunkDecodeError(f"{key}[{index}] must be an array")
        result.append(_str_tuple(heading_path, f"{key}[{index}]"))
    return tuple(result)
