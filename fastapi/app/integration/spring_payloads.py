from __future__ import annotations

from copy import deepcopy
from typing import Any, Mapping

from app.scoring.policy import clamp_score_delta, sanitize_score_allocation, zero_score_vector


REPORT_RESULT_FIELDS = (
    "status",
    "scoreDelta",
    "statusMessage",
    "dailyReport",
    "nextRecommendation",
    "recommendedQuizzes",
)
REPORT_FORBIDDEN_FIELDS = frozenset(
    {
        "gradings",
        "submissionId",
        "submission",
        "submissionPayload",
        "quizSubmission",
        "quizSubmissionPayload",
        "gradingResult",
        "quizGradingResult",
        "quizGradeResult",
    }
)
DEFAULT_GRADED_EMOTION = "JOY"
DEFAULT_GRADED_STATUS_MESSAGE = "좋아요, 핵심은 잡았어요!"
DEFAULT_UNGRADED_EMOTION = "SAD"
DEFAULT_UNGRADED_STATUS_MESSAGE = "AI가 잠깐 쉬는 중이에요. 답안은 저장됐어요."
DEFAULT_GRADED_FEEDBACK = "채점은 끝났는데 상세 피드백이 쏙 비었어요. 점수 결과는 저장해둘게요."
DEFAULT_UNGRADED_FEEDBACK = "채점 AI가 잠깐 시무룩해져서 이번 답안은 점수를 매기지 못했어요."


def build_report_callback_payload(
    *,
    request_id: str,
    user_id: int,
    character_id: int,
    target_date: str,
    report_result: Mapping[str, Any],
) -> dict[str, Any]:
    """Wrap the Story 5 report result with Spring callback metadata."""

    missing_fields = [
        field_name for field_name in REPORT_RESULT_FIELDS if field_name not in report_result
    ]
    if missing_fields:
        raise ValueError(
            "report_result is missing required fields: " + ", ".join(missing_fields)
        )

    payload: dict[str, Any] = {
        "requestId": _required_text(request_id, "requestId"),
        "userId": user_id,
        "characterId": character_id,
        "targetDate": _required_text(target_date, "targetDate"),
    }
    for field_name in REPORT_RESULT_FIELDS:
        payload[field_name] = _without_report_forbidden_fields(
            deepcopy(report_result[field_name])
        )
    return payload


def build_quiz_grade_result_callback_payload(
    *,
    user_id: int,
    character_id: int,
    quiz_id: int,
    grading_result: Mapping[str, Any],
    emotion: str | None = None,
    status_message: str | None = None,
    failed_reason: str | None = None,
) -> dict[str, Any]:
    """Adapt grade_quiz_answer output to the Spring grade-result callback shape."""

    submission_id = _required_text(grading_result.get("submissionId"), "submissionId")
    raw_status = _clean_text(grading_result.get("status")).upper()
    status = "GRADED" if raw_status == "GRADED" and not failed_reason else "UNGRADED"
    score_allocation = sanitize_score_allocation(
        _mapping_or_none(grading_result.get("scoreAllocation"))
    )
    if status == "GRADED":
        score_delta = clamp_score_delta(
            _mapping_or_none(grading_result.get("scoreDelta")),
            score_allocation,
        )
        fallback_feedback = DEFAULT_GRADED_FEEDBACK
        fallback_emotion = DEFAULT_GRADED_EMOTION
        fallback_status_message = DEFAULT_GRADED_STATUS_MESSAGE
    else:
        score_delta = zero_score_vector()
        fallback_feedback = DEFAULT_UNGRADED_FEEDBACK
        fallback_emotion = DEFAULT_UNGRADED_EMOTION
        fallback_status_message = DEFAULT_UNGRADED_STATUS_MESSAGE

    payload = {
        "submissionId": submission_id,
        "userId": user_id,
        "characterId": character_id,
        "quizId": quiz_id,
        "status": status,
        "scoreAllocation": score_allocation,
        "scoreDelta": score_delta,
        "feedback": _clean_text(grading_result.get("feedback")) or fallback_feedback,
        "emotion": _clean_text(emotion) or fallback_emotion,
        "statusMessage": _clean_text(status_message) or fallback_status_message,
    }
    safe_failed_reason = _clean_text(failed_reason)
    if safe_failed_reason:
        payload["failedReason"] = safe_failed_reason
    return payload


def _without_report_forbidden_fields(value: Any) -> Any:
    if isinstance(value, Mapping):
        return {
            key: _without_report_forbidden_fields(item)
            for key, item in value.items()
            if key not in REPORT_FORBIDDEN_FIELDS
        }
    if isinstance(value, (list, tuple)):
        return [_without_report_forbidden_fields(item) for item in value]
    return value


def _mapping_or_none(value: Any) -> Mapping[str, Any] | None:
    return value if isinstance(value, Mapping) else None


def _required_text(value: Any, field_name: str) -> str:
    text = _clean_text(value)
    if not text:
        raise ValueError(f"{field_name} is required")
    return text


def _clean_text(value: Any) -> str:
    if value is None:
        return ""
    return str(value).strip()
