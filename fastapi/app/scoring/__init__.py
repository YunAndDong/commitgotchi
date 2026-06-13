from .policy import (
    SCORE_FIELDS,
    active_score_fields,
    clamp_confidence,
    clamp_report_score_delta,
    clamp_score_delta,
    sanitize_score_allocation,
    zero_score_vector,
)
from .quiz_grader import (
    DEFAULT_GEMINI_QUIZ_GRADER_MODEL,
    GeminiGradingModelClient,
    GradingModelClient,
    grade_quiz_answer,
)
from .schemas import (
    DailyReportAnalysis,
    NextRecommendation,
    QuizGradingResult,
    QuizGradingStatus,
    ReportAnalysis,
    ReportAnalysisStatus,
)

_REPORT_ANALYZER_EXPORTS = {
    "DEFAULT_GEMINI_REPORT_ANALYZER_MODEL",
    "GeminiReportAnalysisModelClient",
    "GeminiReportAnalyzer",
    "ReportAnalysisModelClient",
    "analyze_daily_report",
}

__all__ = [
    "DEFAULT_GEMINI_QUIZ_GRADER_MODEL",
    "DEFAULT_GEMINI_REPORT_ANALYZER_MODEL",
    "DailyReportAnalysis",
    "GeminiReportAnalysisModelClient",
    "GeminiReportAnalyzer",
    "GeminiGradingModelClient",
    "GradingModelClient",
    "NextRecommendation",
    "QuizGradingResult",
    "QuizGradingStatus",
    "ReportAnalysis",
    "ReportAnalysisModelClient",
    "ReportAnalysisStatus",
    "SCORE_FIELDS",
    "active_score_fields",
    "analyze_daily_report",
    "clamp_confidence",
    "clamp_report_score_delta",
    "clamp_score_delta",
    "grade_quiz_answer",
    "sanitize_score_allocation",
    "zero_score_vector",
]


def __getattr__(name: str):
    if name in _REPORT_ANALYZER_EXPORTS:
        from . import report_analyzer

        return getattr(report_analyzer, name)
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")
