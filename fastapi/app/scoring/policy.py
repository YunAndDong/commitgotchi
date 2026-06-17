from __future__ import annotations

import math
from typing import Any, Mapping

from app.rag.schemas import SCORE_FIELDS


MIN_SCORE = 0
MAX_FIELD_SCORE = 10
LOW_CONFIDENCE_FALLBACK_THRESHOLD = 0.35
LOW_CONFIDENCE_SCORE_CAP_THRESHOLD = 0.55
LOW_CONFIDENCE_SCORE_CAP = 3


def zero_score_vector() -> dict[str, int]:
    return {field_name: 0 for field_name in SCORE_FIELDS}


def sanitize_score_allocation(
    allocation: Mapping[str, Any] | None,
) -> dict[str, int]:
    sanitized = zero_score_vector()
    if allocation is None:
        return sanitized

    for field_name in SCORE_FIELDS:
        sanitized[field_name] = _clamp_int(
            allocation.get(field_name),
            MIN_SCORE,
            MAX_FIELD_SCORE,
        )
    return sanitized


def clamp_score_delta(
    raw_delta: Mapping[str, Any] | None,
    allocation: Mapping[str, Any] | None,
) -> dict[str, int]:
    safe_allocation = sanitize_score_allocation(allocation)
    delta = zero_score_vector()
    if raw_delta is None:
        return delta

    for field_name in SCORE_FIELDS:
        delta[field_name] = _clamp_int(
            raw_delta.get(field_name),
            MIN_SCORE,
            safe_allocation[field_name],
        )
    return delta


def clamp_report_score_delta(
    raw_delta: Mapping[str, Any] | None,
    *,
    max_field_score: int = MAX_FIELD_SCORE,
) -> dict[str, int]:
    delta = zero_score_vector()
    if raw_delta is None:
        return delta

    upper_bound = _clamp_int(max_field_score, MIN_SCORE, MAX_FIELD_SCORE)
    for field_name in SCORE_FIELDS:
        delta[field_name] = _clamp_int(
            raw_delta.get(field_name),
            MIN_SCORE,
            upper_bound,
        )
    return delta


def apply_report_confidence_policy(
    raw_delta: Mapping[str, Any] | None,
    confidence: Any,
) -> dict[str, int]:
    safe_confidence = clamp_confidence(confidence)
    if safe_confidence < LOW_CONFIDENCE_FALLBACK_THRESHOLD:
        return zero_score_vector()
    if safe_confidence < LOW_CONFIDENCE_SCORE_CAP_THRESHOLD:
        return clamp_report_score_delta(
            raw_delta,
            max_field_score=LOW_CONFIDENCE_SCORE_CAP,
        )
    return clamp_report_score_delta(raw_delta)


def active_score_fields(allocation: Mapping[str, Any] | None) -> list[str]:
    safe_allocation = sanitize_score_allocation(allocation)
    return [
        field_name
        for field_name in SCORE_FIELDS
        if safe_allocation[field_name] > 0
    ]


def clamp_confidence(confidence: Any) -> float:
    value = _coerce_float(confidence)
    if value is None:
        return 0.0
    return max(0.0, min(1.0, value))


def _clamp_int(value: Any, minimum: int, maximum: int) -> int:
    coerced = _coerce_int(value)
    if coerced is None:
        coerced = 0
    return max(minimum, min(maximum, coerced))


def _coerce_int(value: Any) -> int | None:
    if isinstance(value, bool) or value is None:
        return None
    if isinstance(value, int):
        return value
    if isinstance(value, float):
        if not math.isfinite(value):
            return None
        return int(value)
    if isinstance(value, str):
        stripped = value.strip()
        if not stripped:
            return None
        try:
            numeric_value = float(stripped)
        except ValueError:
            return None
        if not math.isfinite(numeric_value):
            return None
        return int(numeric_value)
    return None


def _coerce_float(value: Any) -> float | None:
    if isinstance(value, bool) or value is None:
        return None
    if isinstance(value, (int, float)):
        numeric_value = float(value)
    elif isinstance(value, str):
        stripped = value.strip()
        if not stripped:
            return None
        try:
            numeric_value = float(stripped)
        except ValueError:
            return None
    else:
        return None
    if not math.isfinite(numeric_value):
        return None
    return numeric_value
