from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


SCORE_FIELDS = ("db", "algorithm", "cs", "network", "framework")
DIFFICULTIES = ("basic", "intermediate", "advanced")


@dataclass(frozen=True)
class KnowledgeChunk:
    chunk_id: str
    source_path: str
    heading_path: tuple[str, ...]
    text: str
    field: str
    source_type: str

    @property
    def title(self) -> str:
        return self.heading_path[-1] if self.heading_path else self.source_path


@dataclass(frozen=True)
class ProblemCandidate:
    problem_id: int
    source_key: str
    question: str
    model_answer: str | None
    score_allocation: dict[str, int]
    field: str
    difficulty: str
    source_path: str
    heading_path: tuple[str, ...] = field(default_factory=tuple)

    @property
    def title(self) -> str:
        return self.heading_path[-1] if self.heading_path else self.question


@dataclass(frozen=True)
class ProblemRubric:
    must_mention: tuple[str, ...] = field(default_factory=tuple)
    optional_mention: tuple[str, ...] = field(default_factory=tuple)
    must_not_confuse: tuple[str, ...] = field(default_factory=tuple)

    def to_dict(self) -> dict[str, list[str]]:
        return {
            "mustMention": list(self.must_mention),
            "optionalMention": list(self.optional_mention),
            "mustNotConfuse": list(self.must_not_confuse),
        }


@dataclass(frozen=True)
class ProblemRecord:
    # Internal FastAPI tracking ID. Spring Boot receives problem snapshots; this is not a lookup contract.
    problem_id: int
    source_key: str
    question: str
    model_answer: str
    difficulty: str
    primary_field: str
    fields: tuple[str, ...]
    score_allocation: dict[str, int]
    source_path: str
    heading_path: tuple[str, ...]
    rubric: ProblemRubric
    content_hash: str

    @property
    def title(self) -> str:
        return self.heading_path[-1] if self.heading_path else self.question

    def to_json_dict(self) -> dict[str, Any]:
        return {
            "problemId": self.problem_id,
            "sourceKey": self.source_key,
            "question": self.question,
            "modelAnswer": self.model_answer,
            "difficulty": self.difficulty,
            "primaryField": self.primary_field,
            "fields": list(self.fields),
            "scoreAllocation": self.score_allocation,
            "sourcePath": self.source_path,
            "headingPath": list(self.heading_path),
            "rubric": self.rubric.to_dict(),
            "contentHash": self.content_hash,
        }


@dataclass(frozen=True)
class SearchHit:
    item: KnowledgeChunk | ProblemCandidate
    score: float
    matched_terms: tuple[str, ...]


@dataclass(frozen=True)
class RagIndex:
    chunks: tuple[KnowledgeChunk, ...]
    problems: tuple[ProblemCandidate, ...]


def empty_score_allocation() -> dict[str, int]:
    return {field_name: 0 for field_name in SCORE_FIELDS}


def validate_score_allocation(score_allocation: dict[str, int]) -> list[str]:
    errors: list[str] = []
    expected_fields = set(SCORE_FIELDS)
    actual_fields = set(score_allocation)
    missing_fields = sorted(expected_fields - actual_fields)
    extra_fields = sorted(actual_fields - expected_fields)
    if missing_fields:
        errors.append(f"missing score fields: {', '.join(missing_fields)}")
    if extra_fields:
        errors.append(f"unknown score fields: {', '.join(extra_fields)}")
    for field_name in SCORE_FIELDS:
        value = score_allocation.get(field_name)
        if not isinstance(value, int):
            errors.append(f"{field_name} score must be an integer")
            continue
        if value < 0 or value > 10:
            errors.append(f"{field_name} score must be between 0 and 10")
    return errors


def validate_problem_record(problem: ProblemRecord) -> list[str]:
    errors: list[str] = []
    if problem.problem_id < 0:
        errors.append("problemId must be non-negative")
    if not problem.source_key.startswith("sha256:"):
        errors.append("sourceKey must start with sha256:")
    if not problem.content_hash.startswith("sha256:"):
        errors.append("contentHash must start with sha256:")
    if not problem.question.strip():
        errors.append("question is required")
    if not problem.model_answer.strip():
        errors.append("modelAnswer is required")
    if problem.difficulty not in DIFFICULTIES:
        errors.append("difficulty must be basic, intermediate, or advanced")
    if problem.primary_field not in SCORE_FIELDS:
        errors.append("primaryField must be one of the score fields")
    if not problem.fields:
        errors.append("fields must not be empty")
    unknown_fields = [field_name for field_name in problem.fields if field_name not in SCORE_FIELDS]
    if unknown_fields:
        errors.append(f"unknown fields: {', '.join(unknown_fields)}")
    if problem.primary_field not in problem.fields:
        errors.append("primaryField must be included in fields")
    errors.extend(validate_score_allocation(problem.score_allocation))
    if not problem.source_path.strip():
        errors.append("sourcePath is required")
    if not problem.heading_path:
        errors.append("headingPath is required")
    if not problem.rubric.must_mention and not problem.rubric.optional_mention:
        errors.append("rubric must include mustMention or optionalMention")
    return errors


def problem_to_contract_dict(problem: ProblemCandidate, score: float) -> dict[str, Any]:
    return {
        "problemId": problem.problem_id,
        "question": problem.question,
        "modelAnswer": problem.model_answer,
        "scoreAllocation": problem.score_allocation,
        "difficulty": problem.difficulty,
        "sourcePath": problem.source_path,
        "matchScore": round(score, 3),
    }
