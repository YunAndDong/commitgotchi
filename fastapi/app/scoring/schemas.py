from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Literal

from app.rag.schemas import SCORE_FIELDS


QuizGradingStatus = Literal["GRADED", "UNGRADED"]
ScoreVector = dict[str, int]


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
