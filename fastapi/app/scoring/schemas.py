from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Literal

from app.rag.schemas import SCORE_FIELDS


QuizGradingStatus = Literal["GRADED", "UNGRADED"]
ScoreVector = dict[str, int]


@dataclass(frozen=True)
class ReportChunk:
    report_chunk_id: str
    text: str
    char_start: int
    char_end: int
    topic_hints: tuple[str, ...]
    field_hints: tuple[str, ...]

    def to_dict(self) -> dict[str, Any]:
        return {
            "reportChunkId": self.report_chunk_id,
            "text": self.text,
            "charStart": self.char_start,
            "charEnd": self.char_end,
            "topicHints": list(self.topic_hints),
            "fieldHints": list(self.field_hints),
        }


@dataclass(frozen=True)
class QuizGradingResult:
    submission_id: str
    status: QuizGradingStatus
    score_allocation: ScoreVector
    score_delta: ScoreVector
    feedback: str
    confidence: float

    def to_dict(self) -> dict[str, Any]:
        return {
            "submissionId": self.submission_id,
            "status": self.status,
            "scoreAllocation": dict(self.score_allocation),
            "scoreDelta": dict(self.score_delta),
            "feedback": self.feedback,
            "confidence": self.confidence,
        }


def score_field_names() -> tuple[str, ...]:
    return tuple(SCORE_FIELDS)


def validate_report_chunk(
    chunk: ReportChunk,
    combined_text: str | None = None,
) -> list[str]:
    errors: list[str] = []
    if not chunk.report_chunk_id.strip():
        errors.append("reportChunkId is required")
    if not chunk.text.strip():
        errors.append("text is required")
    if chunk.char_start < 0:
        errors.append("charStart must be non-negative")
    if chunk.char_end <= chunk.char_start:
        errors.append("charEnd must be greater than charStart")
    unknown_fields = [field_name for field_name in chunk.field_hints if field_name not in SCORE_FIELDS]
    if unknown_fields:
        errors.append(f"unknown fieldHints: {', '.join(unknown_fields)}")
    if combined_text is not None:
        if chunk.char_end > len(combined_text):
            errors.append("charEnd is outside combined text")
        elif combined_text[chunk.char_start : chunk.char_end].strip() != chunk.text:
            errors.append("text must match the combined text substring")
    return errors
