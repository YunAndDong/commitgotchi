from __future__ import annotations

# Story 4의 리포트 분석 결과와 기존 quiz recommender 결과를 조립한다.
# 이 모듈은 API/SQS/callback wrapper를 만들지 않는 내부 서비스 계층이다.

from typing import Any, Mapping

from app.rag.quiz_recommender import build_recommended_quizzes

from .policy import clamp_report_score_delta, sanitize_score_allocation
from .report_analyzer import analyze_daily_report, fallback_report_analysis
from .report_chunker import combine_report_text
from .schemas import (
    DailyReportAnalysis,
    DailyReportResult,
    NextRecommendation,
    RecommendedQuizSnapshot,
    ReportAnalysis,
)


FORBIDDEN_OUTPUT_FIELDS = frozenset(
    {
        "gradings",
        "submissionId",
        "requestId",
        "userId",
        "characterId",
        "targetDate",
    }
)


def generate_daily_report_result(
    *,
    report_title: str | None,
    report_content: str,
    user_metadata: Mapping[str, Any] | None = None,
    character_personality: str | None = None,
    character_metadata: Mapping[str, Any] | None = None,
    analyzer: Any | None = None,
    quiz_recommender: Any | None = None,
) -> dict[str, Any]:
    """분석 결과와 추천 퀴즈를 callback-ready 핵심 payload로 조립한다.

    `analyzer`는 Story 4 `ReportAnalysis` 또는 그 dict 결과를 만들고,
    `quiz_recommender`는 기존 문제 은행 추천 snapshot 목록을 반환한다.
    analyzer가 실패하거나 `FALLBACK`이면 추천은 실행하지 않고 안전 fallback을 반환한다.
    """

    try:
        analysis_payload = _analysis_to_mapping(
            _run_analyzer(
                analyzer=analyzer,
                report_title=report_title,
                report_content=report_content,
                user_metadata=user_metadata,
                character_personality=character_personality,
                character_metadata=character_metadata,
            )
        )
        analysis_result = _result_without_quizzes(analysis_payload)
    except Exception:
        return _fallback_result_payload()

    if analysis_result.status != "SUCCESS":
        return _fallback_result_payload()

    recommended_quizzes = _build_recommended_quiz_snapshots(
        quiz_recommender=quiz_recommender,
        report_title=report_title,
        report_content=report_content,
        analysis_payload=analysis_payload,
        analysis_result=analysis_result,
    )
    return _without_forbidden_fields(
        DailyReportResult(
            status=analysis_result.status,
            score_delta=analysis_result.score_delta,
            status_message=analysis_result.status_message,
            daily_report=analysis_result.daily_report,
            next_recommendation=analysis_result.next_recommendation,
            recommended_quizzes=tuple(recommended_quizzes),
        ).to_dict()
    )


def _run_analyzer(
    *,
    analyzer: Any | None,
    report_title: str | None,
    report_content: str,
    user_metadata: Mapping[str, Any] | None,
    character_personality: str | None,
    character_metadata: Mapping[str, Any] | None,
) -> Mapping[str, Any] | ReportAnalysis:
    kwargs = {
        "report_title": report_title,
        "report_content": report_content,
        "user_metadata": user_metadata,
        "character_personality": character_personality,
        "character_metadata": character_metadata,
    }
    if analyzer is None:
        return analyze_daily_report(**kwargs)
    if hasattr(analyzer, "analyze"):
        return analyzer.analyze(**kwargs)
    if callable(analyzer):
        return analyzer(**kwargs)
    raise TypeError("analyzer must be callable or expose analyze()")


def _analysis_to_mapping(
    value: Mapping[str, Any] | ReportAnalysis | Any,
) -> Mapping[str, Any]:
    if isinstance(value, Mapping):
        return value
    if hasattr(value, "to_dict"):
        mapped = value.to_dict()
        if isinstance(mapped, Mapping):
            return mapped
    raise TypeError("analysis result must be a mapping or to_dict-capable object")


def _result_without_quizzes(payload: Mapping[str, Any]) -> DailyReportResult:
    status = _clean_text(payload.get("status")).upper()
    if status != "SUCCESS":
        return _fallback_result_model()

    daily_report = _daily_report_from_payload(payload.get("dailyReport"))
    next_recommendation = _next_recommendation_from_payload(
        payload.get("nextRecommendation")
    )
    status_message = _clean_text(payload.get("statusMessage"))
    if (
        daily_report is None
        or next_recommendation is None
        or not status_message
    ):
        return _fallback_result_model()

    return DailyReportResult(
        status="SUCCESS",
        score_delta=clamp_report_score_delta(
            _mapping_or_none(payload.get("scoreDelta"))
        ),
        status_message=status_message,
        daily_report=daily_report,
        next_recommendation=next_recommendation,
        recommended_quizzes=(),
    )


def _daily_report_from_payload(value: Any) -> DailyReportAnalysis | None:
    if not isinstance(value, Mapping):
        return None
    text = _clean_text(value.get("text"))
    feedback = _clean_text(value.get("feedback"))
    if not text and not feedback:
        return None
    return DailyReportAnalysis(text=text, feedback=feedback)


def _next_recommendation_from_payload(value: Any) -> NextRecommendation | None:
    if not isinstance(value, Mapping):
        return None
    rationale = _clean_text(value.get("rationale"))
    topics = tuple(_string_list(value.get("topics")))
    if not rationale and not topics:
        return None
    return NextRecommendation(topics=topics, rationale=rationale)


def _build_recommended_quiz_snapshots(
    *,
    quiz_recommender: Any | None,
    report_title: str | None,
    report_content: str,
    analysis_payload: Mapping[str, Any],
    analysis_result: DailyReportResult,
) -> list[RecommendedQuizSnapshot]:
    recommendation_input = _recommendation_input(
        report_title=report_title,
        report_content=report_content,
        analysis_payload=analysis_payload,
        analysis_result=analysis_result,
    )
    try:
        raw_quizzes = _run_quiz_recommender(quiz_recommender, recommendation_input)
    except Exception:
        return []
    return _recommended_quiz_snapshots(raw_quizzes)


def _recommendation_input(
    *,
    report_title: str | None,
    report_content: str,
    analysis_payload: Mapping[str, Any],
    analysis_result: DailyReportResult,
) -> dict[str, Any]:
    detected_keywords = _dedupe_strings(
        [
            *_string_list(analysis_payload.get("topics")),
            *analysis_result.next_recommendation.topics,
        ]
    )
    detected_fields = [
        field_name
        for field_name, value in analysis_result.score_delta.items()
        if value > 0
    ]
    return {
        "reportText": combine_report_text(report_title, report_content),
        "analysis": analysis_result.daily_report.text,
        "feedback": analysis_result.daily_report.feedback,
        "detectedKeywords": detected_keywords,
        "detectedFields": detected_fields,
    }


def _run_quiz_recommender(
    quiz_recommender: Any | None,
    recommendation_input: Mapping[str, Any],
) -> Any:
    recommender = (
        build_recommended_quizzes
        if quiz_recommender is None
        else quiz_recommender
    )
    if callable(recommender):
        return recommender(recommendation_input)
    for method_name in ("build_recommended_quizzes", "recommend_quizzes", "recommend"):
        method = getattr(recommender, method_name, None)
        if callable(method):
            return method(recommendation_input)
    raise TypeError("quiz_recommender must be callable or expose a recommend method")


def _recommended_quiz_snapshots(raw_quizzes: Any) -> list[RecommendedQuizSnapshot]:
    if not isinstance(raw_quizzes, (list, tuple)):
        return []

    snapshots: list[RecommendedQuizSnapshot] = []
    for item in raw_quizzes:
        snapshot = _recommended_quiz_snapshot(item)
        if snapshot is not None:
            snapshots.append(snapshot)
    return snapshots


def _recommended_quiz_snapshot(item: Any) -> RecommendedQuizSnapshot | None:
    if not isinstance(item, Mapping):
        return None
    problem_id = _coerce_problem_id(item.get("problemId"))
    question = _clean_text(item.get("question"))
    model_answer = _clean_text(item.get("modelAnswer"))
    if problem_id is None or not question or not model_answer:
        return None
    return RecommendedQuizSnapshot(
        problem_id=problem_id,
        question=question,
        model_answer=model_answer,
        score_allocation=sanitize_score_allocation(
            _mapping_or_none(item.get("scoreAllocation"))
        ),
    )


def _fallback_result_payload() -> dict[str, Any]:
    return _without_forbidden_fields(_fallback_result_model().to_dict())


def _fallback_result_model() -> DailyReportResult:
    fallback = fallback_report_analysis().to_dict()
    daily_report = _daily_report_from_payload(fallback.get("dailyReport"))
    next_recommendation = _next_recommendation_from_payload(
        fallback.get("nextRecommendation")
    )
    return DailyReportResult(
        status="FALLBACK",
        score_delta=clamp_report_score_delta(fallback.get("scoreDelta")),
        status_message=_clean_text(fallback.get("statusMessage")),
        daily_report=daily_report or DailyReportAnalysis(text="", feedback=""),
        next_recommendation=next_recommendation
        or NextRecommendation(topics=(), rationale=""),
        recommended_quizzes=(),
    )


def _mapping_or_none(value: Any) -> Mapping[str, Any] | None:
    return value if isinstance(value, Mapping) else None


def _without_forbidden_fields(payload: dict[str, Any]) -> dict[str, Any]:
    return {
        key: value
        for key, value in payload.items()
        if key not in FORBIDDEN_OUTPUT_FIELDS
    }


def _string_list(value: Any) -> list[str]:
    if not isinstance(value, (list, tuple, set)):
        return []
    return [_clean_text(item) for item in value if _clean_text(item)]


def _dedupe_strings(values: list[str]) -> list[str]:
    result: list[str] = []
    seen: set[str] = set()
    for value in values:
        if value in seen:
            continue
        result.append(value)
        seen.add(value)
    return result


def _coerce_problem_id(value: Any) -> int | None:
    if isinstance(value, bool) or value is None:
        return None
    if isinstance(value, int):
        return value
    if isinstance(value, str):
        stripped = value.strip()
        if stripped.isdecimal():
            return int(stripped)
    return None


def _clean_text(value: Any) -> str:
    if value is None:
        return ""
    return str(value).strip()
