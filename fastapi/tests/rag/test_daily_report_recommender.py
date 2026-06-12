from __future__ import annotations

import unittest

from app.rag.daily_report_recommender import (
    analyze_daily_report,
    build_daily_report_recommendation,
)
from app.rag.problem_bank_store import ProblemBankStore
from app.rag.schemas import ProblemRecord, ProblemRubric


class DailyReportRecommenderTest(unittest.TestCase):
    def setUp(self) -> None:
        self.store = ProblemBankStore(
            (
                _problem(
                    101,
                    "jpa-n-plus-one",
                    "JPA N+1 문제란 무엇이며 어떻게 해결하나요?",
                    (
                        "JPA N+1은 지연 로딩 연관 엔티티를 반복 접근할 때 "
                        "추가 쿼리가 발생하는 문제입니다. fetch join, "
                        "EntityGraph, Batch Size로 해결할 수 있습니다."
                    ),
                    "db",
                    ("db", "framework"),
                    ("N+1", "fetch join", "Lazy Loading"),
                ),
                _problem(
                    202,
                    "oauth-jwt-flow",
                    "OAuth와 JWT 인증 흐름의 차이를 설명해주세요.",
                    (
                        "OAuth 2.0은 인가 프로토콜이고 Authorization Code를 "
                        "Access Token으로 교환합니다. JWT는 Header, Payload, "
                        "Signature로 구성된 토큰 형식입니다."
                    ),
                    "network",
                    ("network", "framework"),
                    ("OAuth", "JWT", "Authorization Code", "Access Token"),
                ),
                _problem(
                    303,
                    "dijkstra",
                    "다익스트라 알고리즘을 설명해주세요.",
                    "가중치 그래프에서 최단 경로를 찾는 그리디 알고리즘입니다.",
                    "algorithm",
                    ("algorithm",),
                    ("graph", "shortest path", "priority queue"),
                ),
            )
        )

    def test_jpa_n_plus_one_report_builds_db_framework_recommendation(self) -> None:
        result = build_daily_report_recommendation(
            "오늘 JPA N+1 문제와 fetch join 해결 방법을 정리했다.",
            store=self.store,
        )

        self.assertIn("JPA N+1", result["topics"])
        self.assertIn("db", result["detectedFields"])
        self.assertIn("framework", result["detectedFields"])
        self.assertEqual(result["recommendedQuizzes"][0]["problemId"], 101)
        self.assertGreater(
            result["recommendedQuizzes"][0]["scoreAllocation"]["db"],
            0,
        )
        self.assertGreater(
            result["recommendedQuizzes"][0]["scoreAllocation"]["framework"],
            0,
        )

    def test_oauth_jwt_report_builds_network_framework_recommendation(self) -> None:
        result = build_daily_report_recommendation(
            "OAuth와 JWT 인증 흐름, access token 교환 과정을 복습했다.",
            store=self.store,
        )

        self.assertIn("OAuth", result["topics"])
        self.assertIn("JWT", result["topics"])
        self.assertIn("network", result["detectedFields"])
        self.assertIn("framework", result["detectedFields"])
        self.assertEqual(result["recommendedQuizzes"][0]["problemId"], 202)
        self.assertGreater(
            result["recommendedQuizzes"][0]["scoreAllocation"]["network"],
            0,
        )
        self.assertGreater(
            result["recommendedQuizzes"][0]["scoreAllocation"]["framework"],
            0,
        )

    def test_unrelated_report_returns_empty_recommendations(self) -> None:
        result = build_daily_report_recommendation(
            "오늘 점심으로 김치찌개를 먹고 산책을 했다.",
            store=self.store,
        )

        self.assertEqual(result["topics"], [])
        self.assertEqual(result["detectedFields"], [])
        self.assertEqual(result["scoreDeltaHint"], "insufficient_signal")
        self.assertEqual(result["recommendedQuizzes"], [])

    def test_recommended_quiz_contains_callback_snapshot_fields(self) -> None:
        result = build_daily_report_recommendation(
            "JPA N+1 fetch join",
            store=self.store,
        )

        snapshot = result["recommendedQuizzes"][0]
        for field_name in (
            "problemId",
            "question",
            "modelAnswer",
            "scoreAllocation",
        ):
            self.assertIn(field_name, snapshot)

    def test_analysis_exposes_score_delta_hint_without_recommending(self) -> None:
        analysis = analyze_daily_report("OAuth와 JWT 인증 흐름을 학습했다.")

        self.assertIn("network", analysis["detectedFields"])
        self.assertIn("framework", analysis["detectedFields"])
        self.assertEqual(analysis["scoreDeltaHint"], "field_focus:network,framework")


def _problem(
    problem_id: int,
    key: str,
    question: str,
    model_answer: str,
    primary_field: str,
    fields: tuple[str, ...],
    rubric_terms: tuple[str, ...],
) -> ProblemRecord:
    score_allocation = {
        "db": 0,
        "algorithm": 0,
        "cs": 0,
        "network": 0,
        "framework": 0,
    }
    for field in fields:
        score_allocation[field] = 7
    return ProblemRecord(
        problem_id=problem_id,
        source_key=f"sha256:fixture-{key}",
        question=question,
        model_answer=model_answer,
        difficulty="intermediate",
        primary_field=primary_field,
        fields=fields,
        score_allocation=score_allocation,
        source_path=f"fixtures/{key}.md",
        heading_path=("Fixture", question),
        rubric=ProblemRubric(must_mention=rubric_terms),
        content_hash=f"sha256:fixture-{key}-content",
    )


if __name__ == "__main__":
    unittest.main()
