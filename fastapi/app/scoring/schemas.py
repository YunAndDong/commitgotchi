from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Literal

from app.rag.schemas import SCORE_FIELDS


QuizGradingStatus = Literal["GRADED", "UNGRADED"]
ReportAnalysisStatus = Literal["SUCCESS", "FALLBACK"]
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


@dataclass(frozen=True)
class DailyReportAnalysis:
    text: str
    feedback: str

    def to_dict(self) -> dict[str, Any]:
        return {
            "text": self.text,
            "feedback": self.feedback,
        }


@dataclass(frozen=True)
class NextRecommendation:
    topics: tuple[str, ...]
    rationale: str

    def to_dict(self) -> dict[str, Any]:
        return {
            "topics": list(self.topics),
            "rationale": self.rationale,
        }


@dataclass(frozen=True)
class ReportAnalysis:
    status: ReportAnalysisStatus
    topics: tuple[str, ...]
    field_evidence: dict[str, str]
    score_delta: ScoreVector
    confidence: float
    emotion: str
    status_message: str
    daily_report: DailyReportAnalysis | None
    next_recommendation: NextRecommendation | None

    def to_dict(self) -> dict[str, Any]:
        return {
            "status": self.status,
            "topics": list(self.topics),
            "fieldEvidence": {
                field_name: self.field_evidence.get(field_name, "")
                for field_name in SCORE_FIELDS
            },
            "scoreDelta": {
                field_name: int(self.score_delta.get(field_name, 0))
                for field_name in SCORE_FIELDS
            },
            "confidence": self.confidence,
            "emotion": self.emotion,
            "statusMessage": self.status_message,
            "dailyReport": (
                self.daily_report.to_dict()
                if self.daily_report is not None
                else None
            ),
            "nextRecommendation": (
                self.next_recommendation.to_dict()
                if self.next_recommendation is not None
                else None
            ),
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
