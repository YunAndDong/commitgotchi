from __future__ import annotations

import unittest
from typing import Any, Mapping
from unittest.mock import patch

from app.rag.schemas import SCORE_FIELDS
from app.scoring.report_analyzer import fallback_report_analysis
from app.scoring.schemas import (
    DailyReportAnalysis,
    NextRecommendation,
    ReportAnalysis,
)


class FakeAnalyzer:
    def __init__(
        self,
        result: Mapping[str, Any] | ReportAnalysis | None = None,
        *,
        error: Exception | None = None,
    ) -> None:
        self.result = result
        self.error = error
        self.calls: list[Mapping[str, Any]] = []

    def analyze(self, **kwargs: Any) -> Mapping[str, Any] | ReportAnalysis:
        self.calls.append(kwargs)
        if self.error is not None:
            raise self.error
        return self.result if self.result is not None else _success_analysis()


class FakeQuizRecommender:
    def __init__(
        self,
        result: list[dict[str, Any]] | None = None,
        *,
        error: Exception | None = None,
    ) -> None:
        self.result = result
        self.error = error
        self.calls: list[Any] = []

    def __call__(self, report: Any) -> list[dict[str, Any]]:
        self.calls.append(report)
        if self.error is not None:
            raise self.error
        return list(self.result or [])


class FalsyQuizRecommender(FakeQuizRecommender):
    def __bool__(self) -> bool:
        return False


class DailyReportServiceTest(unittest.TestCase):
    def test_success_payload_preserves_analysis_and_trims_quiz_snapshots(self) -> None:
        from app.scoring.daily_report_service import generate_daily_report_result

        analyzer = FakeAnalyzer(
            _success_analysis(
                score_delta={"db": 6, "algorithm": 0, "cs": 0, "network": 2, "framework": 4}
            )
        )
        recommender = FakeQuizRecommender([_quiz_snapshot_with_extra_fields()])

        with patch(
            "app.scoring.quiz_grader.grade_quiz_answer",
            side_effect=AssertionError("quiz grading must not be called"),
        ):
            result = generate_daily_report_result(
                report_title="오늘 학습",
                report_content="JPA N+1과 JWT 인증 흐름을 정리했다.",
                user_metadata={
                    "reportDirection": {
                        "scoreDeltaHint": {field: 10 for field in SCORE_FIELDS}
                    }
                },
                character_metadata={
                    "currentStats": {field: 999 for field in SCORE_FIELDS},
                },
                analyzer=analyzer,
                quiz_recommender=recommender,
            )

        self.assertEqual(result["status"], "SUCCESS")
        self.assertNotIn("emotion", result)
        self.assertEqual(
            result["scoreDelta"],
            {"db": 6, "algorithm": 0, "cs": 0, "network": 2, "framework": 4},
        )
        self.assertEqual(tuple(result["scoreDelta"]), SCORE_FIELDS)
        self.assertEqual(
            result["dailyReport"],
            {
                "text": "오늘은 JPA N+1과 JWT 흐름을 정리했습니다.",
                "feedback": "원인과 해결 흐름을 함께 설명한 점이 좋습니다.",
            },
        )
        self.assertEqual(
            result["nextRecommendation"],
            {
                "topics": ["EntityGraph", "JWT refresh token"],
                "rationale": "오늘 다룬 주제의 한계를 이어서 확인하면 좋습니다.",
            },
        )
        self.assertEqual(len(result["recommendedQuizzes"]), 1)
        quiz = result["recommendedQuizzes"][0]
        self.assertEqual(
            set(quiz),
            {"problemId", "question", "modelAnswer", "scoreAllocation"},
        )
        self.assertEqual(tuple(quiz["scoreAllocation"]), SCORE_FIELDS)
        self.assertEqual(
            quiz["scoreAllocation"],
            {"db": 10, "algorithm": 0, "cs": 0, "network": 7, "framework": 0},
        )
        self.assertTrue(_forbidden_fields().isdisjoint(result))
        self.assertTrue(_forbidden_fields().isdisjoint(quiz))
        self.assertEqual(len(analyzer.calls), 1)
        self.assertEqual(len(recommender.calls), 1)
        recommender_input = recommender.calls[0]
        self.assertEqual(
            set(recommender_input),
            {"reportText", "analysis", "feedback", "detectedKeywords", "detectedFields"},
        )
        self.assertIn("JPA N+1", recommender_input["detectedKeywords"])
        self.assertIn("EntityGraph", recommender_input["detectedKeywords"])
        self.assertEqual(recommender_input["detectedFields"], ["db", "network", "framework"])

    def test_dataclass_analysis_result_is_supported(self) -> None:
        from app.scoring.daily_report_service import generate_daily_report_result

        analyzer = FakeAnalyzer(
            ReportAnalysis(
                status="SUCCESS",
                topics=("Docker",),
                field_evidence={field: "" for field in SCORE_FIELDS},
                score_delta={"framework": 3},
                confidence=0.8,
                status_message="Docker 흐름을 잘 잡았어요.",
                daily_report=DailyReportAnalysis(
                    text="Docker 이미지와 컨테이너를 비교했습니다.",
                    feedback="차이를 명확히 설명했습니다.",
                ),
                next_recommendation=NextRecommendation(
                    topics=("컨테이너 네트워크",),
                    rationale="실행 환경 연결로 확장하면 좋습니다.",
                ),
            )
        )

        result = generate_daily_report_result(
            report_title=None,
            report_content="Docker 이미지와 컨테이너 차이를 공부했다.",
            analyzer=analyzer,
            quiz_recommender=FakeQuizRecommender([]),
        )

        self.assertEqual(result["status"], "SUCCESS")
        self.assertEqual(result["scoreDelta"]["framework"], 3)
        self.assertEqual(tuple(result["scoreDelta"]), SCORE_FIELDS)
        self.assertEqual(result["recommendedQuizzes"], [])

    def test_empty_recommender_result_keeps_empty_recommended_quizzes(self) -> None:
        from app.scoring.daily_report_service import generate_daily_report_result

        recommender = FakeQuizRecommender([])
        result = generate_daily_report_result(
            report_title=None,
            report_content="관련 문제를 찾기 어려운 리포트",
            analyzer=FakeAnalyzer(_success_analysis()),
            quiz_recommender=recommender,
        )

        self.assertEqual(result["status"], "SUCCESS")
        self.assertEqual(result["recommendedQuizzes"], [])
        self.assertEqual(len(recommender.calls), 1)

    def test_falsy_injected_recommender_is_still_used(self) -> None:
        from app.scoring.daily_report_service import generate_daily_report_result

        recommender = FalsyQuizRecommender([_quiz_snapshot_with_extra_fields()])
        result = generate_daily_report_result(
            report_title=None,
            report_content="JPA N+1을 정리했다.",
            analyzer=FakeAnalyzer(_success_analysis()),
            quiz_recommender=recommender,
        )

        self.assertEqual(result["status"], "SUCCESS")
        self.assertEqual(len(recommender.calls), 1)
        self.assertEqual(len(result["recommendedQuizzes"]), 1)

    def test_recommender_exception_only_falls_back_recommended_quizzes(self) -> None:
        from app.scoring.daily_report_service import generate_daily_report_result

        result = generate_daily_report_result(
            report_title=None,
            report_content="JPA N+1을 정리했다.",
            analyzer=FakeAnalyzer(_success_analysis(score_delta={"db": 7})),
            quiz_recommender=FakeQuizRecommender(error=RuntimeError("search down")),
        )

        self.assertEqual(result["status"], "SUCCESS")
        self.assertEqual(result["scoreDelta"]["db"], 7)
        self.assertEqual(result["dailyReport"]["text"], "오늘은 JPA N+1과 JWT 흐름을 정리했습니다.")
        self.assertEqual(result["recommendedQuizzes"], [])

    def test_analyzer_exception_returns_safe_fallback_and_skips_recommender(self) -> None:
        from app.scoring.daily_report_service import generate_daily_report_result

        recommender = FakeQuizRecommender([_quiz_snapshot_with_extra_fields()])
        result = generate_daily_report_result(
            report_title=None,
            report_content="JPA N+1을 정리했다.",
            analyzer=FakeAnalyzer(error=RuntimeError("model down")),
            quiz_recommender=recommender,
        )

        self.assertEqual(result["status"], "FALLBACK")
        self.assertEqual(result["scoreDelta"], {field: 0 for field in SCORE_FIELDS})
        self.assertEqual(result["recommendedQuizzes"], [])
        self.assertEqual(recommender.calls, [])
        self.assertIn("feedback", result["dailyReport"])
        self.assertIn("rationale", result["nextRecommendation"])

    def test_fallback_analysis_skips_recommender_even_if_it_can_return_quizzes(self) -> None:
        from app.scoring.daily_report_service import generate_daily_report_result

        recommender = FakeQuizRecommender([_quiz_snapshot_with_extra_fields()])
        result = generate_daily_report_result(
            report_title=None,
            report_content="JPA N+1을 정리했다.",
            analyzer=FakeAnalyzer(fallback_report_analysis()),
            quiz_recommender=recommender,
        )

        self.assertEqual(result["status"], "FALLBACK")
        self.assertEqual(result["scoreDelta"], {field: 0 for field in SCORE_FIELDS})
        self.assertEqual(result["recommendedQuizzes"], [])
        self.assertEqual(recommender.calls, [])


def _success_analysis(
    *,
    score_delta: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    return {
        "status": "SUCCESS",
        "topics": ["JPA N+1", "JWT"],
        "fieldEvidence": {
            "db": "N+1 원인을 설명했다.",
            "algorithm": "",
            "cs": "",
            "network": "JWT 인증 흐름을 정리했다.",
            "framework": "JPA fetch join을 비교했다.",
        },
        "scoreDelta": dict(
            score_delta
            if score_delta is not None
            else {"db": 8, "algorithm": 0, "cs": 0, "network": 3, "framework": 5}
        ),
        "confidence": 0.86,
        "statusMessage": "핵심 원인과 해결책을 잘 연결했어요.",
        "dailyReport": {
            "text": "오늘은 JPA N+1과 JWT 흐름을 정리했습니다.",
            "feedback": "원인과 해결 흐름을 함께 설명한 점이 좋습니다.",
        },
        "nextRecommendation": {
            "topics": ["EntityGraph", "JWT refresh token"],
            "rationale": "오늘 다룬 주제의 한계를 이어서 확인하면 좋습니다.",
        },
        "confidenceDebug": "trimmed",
        "recommendedQuizzes": [{"problemId": 999}],
    }


def _quiz_snapshot_with_extra_fields() -> dict[str, Any]:
    return {
        "problemId": 123,
        "question": "JPA N+1 문제와 JWT 인증 흐름을 설명하세요.",
        "modelAnswer": "N+1은 반복 지연 로딩 쿼리이며 JWT는 서명된 토큰입니다.",
        "scoreAllocation": {"db": 10, "network": 7, "framework": "invalid-high"},
        "difficulty": "advanced",
        "sourcePath": "fixtures/problem.md",
        "matchScore": 99.9,
        "submissionId": "must-be-trimmed",
        "gradings": [{"field": "db"}],
    }


def _forbidden_fields() -> set[str]:
    return {
        "gradings",
        "submissionId",
        "requestId",
        "userId",
        "characterId",
        "targetDate",
    }


if __name__ == "__main__":
    unittest.main()
