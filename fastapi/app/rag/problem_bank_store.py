from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable, Mapping

from .schemas import (
    DIFFICULTIES,
    SCORE_FIELDS,
    ProblemRecord,
    ProblemRubric,
    validate_problem_record,
)


FASTAPI_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CATALOG_PATH = FASTAPI_ROOT / "data" / "rag" / "catalog" / "problems.jsonl"


class ProblemRecordDecodeError(ValueError):
    """Raised when a JSON object cannot be converted into a ProblemRecord."""


@dataclass(frozen=True)
class ProblemBankLoadIssue:
    line_number: int
    reason: str
    message: str


@dataclass(frozen=True)
class ProblemBankLoadResult:
    records: tuple[ProblemRecord, ...] = field(default_factory=tuple)
    issues: tuple[ProblemBankLoadIssue, ...] = field(default_factory=tuple)


@dataclass(frozen=True)
class ProblemBankStore:
    records: tuple[ProblemRecord, ...] = field(default_factory=tuple)
    _by_problem_id: dict[int, ProblemRecord] = field(init=False, repr=False)

    def __post_init__(self) -> None:
        records = tuple(self.records)
        by_problem_id: dict[int, ProblemRecord] = {}
        for record in records:
            by_problem_id.setdefault(record.problem_id, record)
        object.__setattr__(self, "records", records)
        object.__setattr__(self, "_by_problem_id", by_problem_id)

    def __iter__(self):
        return iter(self.records)

    def __len__(self) -> int:
        return len(self.records)

    def get(self, problem_id: int) -> ProblemRecord | None:
        return self._by_problem_id.get(problem_id)

    def filter(
        self,
        *,
        field: str | None = None,
        difficulty: str | None = None,
    ) -> tuple[ProblemRecord, ...]:
        if field is not None and field not in SCORE_FIELDS:
            return ()
        if difficulty is not None and difficulty not in DIFFICULTIES:
            return ()
        return tuple(
            record
            for record in self.records
            if (field is None or field in record.fields)
            and (difficulty is None or difficulty == record.difficulty)
        )


def load_problem_bank_store(path: Path = DEFAULT_CATALOG_PATH) -> ProblemBankStore:
    return ProblemBankStore(read_problem_jsonl(path).records)


def read_problem_jsonl(
    path: Path = DEFAULT_CATALOG_PATH,
    *,
    strict: bool = False,
) -> ProblemBankLoadResult:
    catalog_path = Path(path).expanduser()
    if not catalog_path.exists():
        return ProblemBankLoadResult()
    if not catalog_path.is_file():
        issue = ProblemBankLoadIssue(
            line_number=0,
            reason="not_a_file",
            message=f"problem bank catalog path is not a file: {catalog_path}",
        )
        if strict:
            raise FileNotFoundError(issue.message)
        return ProblemBankLoadResult(issues=(issue,))

    records: list[ProblemRecord] = []
    issues: list[ProblemBankLoadIssue] = []
    try:
        with catalog_path.open("r", encoding="utf-8") as file:
            for line_number, line in enumerate(file, start=1):
                stripped = line.strip()
                if not stripped:
                    continue
                try:
                    payload = json.loads(stripped)
                    record = problem_record_from_dict(payload)
                except json.JSONDecodeError as exc:
                    issue = ProblemBankLoadIssue(
                        line_number=line_number,
                        reason="json_decode_failed",
                        message=str(exc),
                    )
                except ProblemRecordDecodeError as exc:
                    issue = ProblemBankLoadIssue(
                        line_number=line_number,
                        reason="schema_validation_failed",
                        message=str(exc),
                    )
                else:
                    records.append(record)
                    continue

                if strict:
                    raise ProblemRecordDecodeError(
                        f"{catalog_path}:{line_number}: {issue.message}"
                    ) from None
                issues.append(issue)
    except (OSError, UnicodeDecodeError) as exc:
        issue = ProblemBankLoadIssue(
            line_number=0,
            reason="read_failed",
            message=str(exc),
        )
        if strict:
            raise
        return ProblemBankLoadResult(issues=(issue,))

    return ProblemBankLoadResult(records=tuple(records), issues=tuple(issues))


def problem_record_from_dict(payload: Any) -> ProblemRecord:
    if not isinstance(payload, Mapping):
        raise ProblemRecordDecodeError("record must be a JSON object")

    problem = ProblemRecord(
        problem_id=_required_int(payload, "problemId"),
        source_key=_required_str(payload, "sourceKey"),
        question=_required_str(payload, "question"),
        model_answer=_required_str(payload, "modelAnswer"),
        difficulty=_required_str(payload, "difficulty"),
        primary_field=_required_str(payload, "primaryField"),
        fields=_required_str_tuple(payload, "fields"),
        score_allocation=_required_score_allocation(payload, "scoreAllocation"),
        source_path=_required_str(payload, "sourcePath"),
        heading_path=_required_str_tuple(payload, "headingPath"),
        rubric=_required_rubric(payload, "rubric"),
        content_hash=_required_str(payload, "contentHash"),
    )
    errors = validate_problem_record(problem)
    if errors:
        raise ProblemRecordDecodeError("; ".join(errors))
    return problem


def _required_value(payload: Mapping[str, Any], key: str) -> Any:
    if key not in payload:
        raise ProblemRecordDecodeError(f"{key} is required")
    return payload[key]


def _required_int(payload: Mapping[str, Any], key: str) -> int:
    value = _required_value(payload, key)
    if not isinstance(value, int) or isinstance(value, bool):
        raise ProblemRecordDecodeError(f"{key} must be an integer")
    return value


def _required_str(payload: Mapping[str, Any], key: str) -> str:
    value = _required_value(payload, key)
    if not isinstance(value, str):
        raise ProblemRecordDecodeError(f"{key} must be a string")
    return value


def _required_str_tuple(payload: Mapping[str, Any], key: str) -> tuple[str, ...]:
    value = _required_value(payload, key)
    if not isinstance(value, list):
        raise ProblemRecordDecodeError(f"{key} must be an array")
    return _str_tuple(value, key)


def _str_tuple(values: Iterable[Any], key: str) -> tuple[str, ...]:
    result: list[str] = []
    for index, value in enumerate(values):
        if not isinstance(value, str):
            raise ProblemRecordDecodeError(f"{key}[{index}] must be a string")
        result.append(value)
    return tuple(result)


def _required_score_allocation(payload: Mapping[str, Any], key: str) -> dict[str, int]:
    value = _required_value(payload, key)
    if not isinstance(value, Mapping):
        raise ProblemRecordDecodeError(f"{key} must be an object")
    allocation: dict[str, int] = {}
    for score_field, score_value in value.items():
        if not isinstance(score_field, str):
            raise ProblemRecordDecodeError(f"{key} keys must be strings")
        if not isinstance(score_value, int) or isinstance(score_value, bool):
            raise ProblemRecordDecodeError(f"{key}.{score_field} must be an integer")
        allocation[score_field] = score_value
    return allocation


def _required_rubric(payload: Mapping[str, Any], key: str) -> ProblemRubric:
    value = _required_value(payload, key)
    if not isinstance(value, Mapping):
        raise ProblemRecordDecodeError(f"{key} must be an object")
    return ProblemRubric(
        must_mention=_rubric_terms(value, "mustMention"),
        optional_mention=_rubric_terms(value, "optionalMention"),
        must_not_confuse=_rubric_terms(value, "mustNotConfuse"),
    )


def _rubric_terms(payload: Mapping[str, Any], key: str) -> tuple[str, ...]:
    value = payload.get(key, [])
    if not isinstance(value, list):
        raise ProblemRecordDecodeError(f"rubric.{key} must be an array")
    return _str_tuple(value, f"rubric.{key}")
