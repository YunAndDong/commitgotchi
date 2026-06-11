from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


SCORE_FIELDS = ("db", "algorithm", "cs", "network", "framework")


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
