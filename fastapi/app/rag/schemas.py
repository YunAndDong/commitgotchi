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
class ProblemEmbeddingRecord:
    problem_id: int
    source_key: str
    content_hash: str
    model: str
    output_dimensionality: int
    source_path: str
    heading_path: tuple[str, ...]
    embedding: tuple[float, ...]

    def to_json_dict(self) -> dict[str, Any]:
        return {
            "problemId": self.problem_id,
            "sourceKey": self.source_key,
            "contentHash": self.content_hash,
            "model": self.model,
            "outputDimensionality": self.output_dimensionality,
            "sourcePath": self.source_path,
            "headingPath": list(self.heading_path),
            "embedding": list(self.embedding),
        }


@dataclass(frozen=True)
class ConceptNeighbors:
    previous_chunk_id: str | None = None
    next_chunk_id: str | None = None
    parent_heading_path: tuple[str, ...] = field(default_factory=tuple)
    sibling_heading_paths: tuple[tuple[str, ...], ...] = field(default_factory=tuple)

    def to_dict(self) -> dict[str, Any]:
        return {
            "previousChunkId": self.previous_chunk_id,
            "nextChunkId": self.next_chunk_id,
            "parentHeadingPath": list(self.parent_heading_path),
            "siblingHeadingPaths": [
                list(heading_path) for heading_path in self.sibling_heading_paths
            ],
        }


@dataclass(frozen=True)
class ConceptChunkRecord:
    chunk_id: str
    source_path: str
    source_root: str
    heading_path: tuple[str, ...]
    heading_level: int
    chunk_index: int
    text: str
    content_hash: str
    field_hints: tuple[str, ...]
    neighbors: ConceptNeighbors
    char_start: int | None = None
    char_end: int | None = None

    @property
    def title(self) -> str:
        return self.heading_path[-1] if self.heading_path else self.source_path

    def to_json_dict(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "chunkId": self.chunk_id,
            "sourcePath": self.source_path,
            "sourceRoot": self.source_root,
            "headingPath": list(self.heading_path),
            "headingLevel": self.heading_level,
            "chunkIndex": self.chunk_index,
            "text": self.text,
            "contentHash": self.content_hash,
            "fieldHints": list(self.field_hints),
            "neighbors": self.neighbors.to_dict(),
        }
        if self.char_start is not None:
            payload["charStart"] = self.char_start
        if self.char_end is not None:
            payload["charEnd"] = self.char_end
        return payload


@dataclass(frozen=True)
class ConceptEmbeddingRecord:
    chunk_id: str
    content_hash: str
    model: str
    output_dimensionality: int
    source_path: str
    heading_path: tuple[str, ...]
    embedding: tuple[float, ...]

    def to_json_dict(self) -> dict[str, Any]:
        return {
            "chunkId": self.chunk_id,
            "contentHash": self.content_hash,
            "model": self.model,
            "outputDimensionality": self.output_dimensionality,
            "sourcePath": self.source_path,
            "headingPath": list(self.heading_path),
            "embedding": list(self.embedding),
        }


@dataclass(frozen=True)
class ConceptSearchHit:
    chunk: ConceptChunkRecord
    score: float
    search_mode: str
    matched_terms: tuple[str, ...] = field(default_factory=tuple)

    def to_dict(self) -> dict[str, Any]:
        return {
            "chunkId": self.chunk.chunk_id,
            "score": round(self.score, 3),
            "searchMode": self.search_mode,
            "matchedTerms": list(self.matched_terms),
            "sourcePath": self.chunk.source_path,
            "headingPath": list(self.chunk.heading_path),
            "text": self.chunk.text,
            "fieldHints": list(self.chunk.field_hints),
        }


@dataclass(frozen=True)
class EvidenceMatch:
    chunk_id: str
    score: float
    search_mode: str
    matched_terms: tuple[str, ...]
    source_path: str
    heading_path: tuple[str, ...]
    text: str
    field_hints: tuple[str, ...]

    def to_dict(self) -> dict[str, Any]:
        return {
            "chunkId": self.chunk_id,
            "score": round(self.score, 3),
            "searchMode": self.search_mode,
            "matchedTerms": list(self.matched_terms),
            "sourcePath": self.source_path,
            "headingPath": list(self.heading_path),
            "fieldHints": list(self.field_hints),
            "text": self.text,
        }


@dataclass(frozen=True)
class NeighborhoodEvidence:
    reason: str
    chunk_id: str
    source_path: str
    heading_path: tuple[str, ...]
    text: str
    field_hints: tuple[str, ...]

    def to_dict(self) -> dict[str, Any]:
        return {
            "reason": self.reason,
            "chunkId": self.chunk_id,
            "sourcePath": self.source_path,
            "headingPath": list(self.heading_path),
            "fieldHints": list(self.field_hints),
            "text": self.text,
        }


@dataclass(frozen=True)
class ReportEvidenceBundle:
    report_chunk_id: str
    query_text: str
    topic_hints: tuple[str, ...]
    field_hints: tuple[str, ...]
    matches: tuple[EvidenceMatch, ...] = field(default_factory=tuple)
    neighborhood: tuple[NeighborhoodEvidence, ...] = field(default_factory=tuple)

    def to_dict(self) -> dict[str, Any]:
        return {
            "reportChunkId": self.report_chunk_id,
            "query": {
                "text": self.query_text,
                "topicHints": list(self.topic_hints),
                "fieldHints": list(self.field_hints),
            },
            "matches": [match.to_dict() for match in self.matches],
            "neighborhood": [item.to_dict() for item in self.neighborhood],
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


def validate_concept_chunk_record(chunk: ConceptChunkRecord) -> list[str]:
    errors: list[str] = []
    if not chunk.chunk_id.startswith("concept:sha256:"):
        errors.append("chunkId must start with concept:sha256:")
    if not chunk.content_hash.startswith("sha256:"):
        errors.append("contentHash must start with sha256:")
    if not chunk.source_path.strip():
        errors.append("sourcePath is required")
    if chunk.source_path.startswith("/"):
        errors.append("sourcePath must be relative")
    if not chunk.source_root.strip():
        errors.append("sourceRoot is required")
    if not chunk.heading_path:
        errors.append("headingPath is required")
    if chunk.heading_level < 0 or chunk.heading_level > 6:
        errors.append("headingLevel must be between 0 and 6")
    if chunk.chunk_index < 0:
        errors.append("chunkIndex must be non-negative")
    if not chunk.text.strip():
        errors.append("text is required")
    unknown_fields = [
        field_name for field_name in chunk.field_hints if field_name not in SCORE_FIELDS
    ]
    if unknown_fields:
        errors.append(f"unknown fieldHints: {', '.join(unknown_fields)}")
    if chunk.neighbors.previous_chunk_id is not None and not chunk.neighbors.previous_chunk_id.startswith("concept:sha256:"):
        errors.append("neighbors.previousChunkId must start with concept:sha256:")
    if chunk.neighbors.next_chunk_id is not None and not chunk.neighbors.next_chunk_id.startswith("concept:sha256:"):
        errors.append("neighbors.nextChunkId must start with concept:sha256:")
    if chunk.char_start is not None and chunk.char_start < 0:
        errors.append("charStart must be non-negative")
    if chunk.char_end is not None and chunk.char_end < 0:
        errors.append("charEnd must be non-negative")
    if (
        chunk.char_start is not None
        and chunk.char_end is not None
        and chunk.char_end < chunk.char_start
    ):
        errors.append("charEnd must be greater than or equal to charStart")
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
