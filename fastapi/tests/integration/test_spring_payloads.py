from __future__ import annotations

import json
import unittest
from copy import deepcopy
from typing import Any

from app.integration.spring_payloads import (
    build_quiz_grade_result_callback_payload,
    build_report_callback_payload,
)


class ReportCallbackPayloadTest(unittest.TestCase):
    def test_report_payload_wraps_metadata_and_preserves_story5_fields(self) -> None:
        report_result = _report_result()

        payload = build_report_callback_payload(
            request_id="report-request-1",
            user_id=1,
            character_id=10,
            target_date="2026-06-14",
            report_result=report_result,
        )

        self.assertEqual(payload["requestId"], "report-request-1")
        self.assertEqual(payload["userId"], 1)
        self.assertEqual(payload["characterId"], 10)
        self.assertEqual(payload["targetDate"], "2026-06-14")
        for field_name in (
            "status",
            "scoreDelta",
            "emotion",
            "statusMessage",
            "dailyReport",
            "nextRecommendation",
            "recommendedQuizzes",
        ):
            self.assertEqual(payload[field_name], report_result[field_name])

    def test_report_payload_does_not_mutate_source_and_removes_quiz_grading_fields(self) -> None:
        report_result = _report_result()
        report_result["gradings"] = [{"submissionId": "sub-1"}]
        report_result["recommendedQuizzes"][0]["submissionId"] = "sub-1"
        report_result["recommendedQuizzes"][0]["gradingResult"] = {"status": "GRADED"}
        before = deepcopy(report_result)

        payload = build_report_callback_payload(
            request_id="report-request-1",
            user_id=1,
            character_id=10,
            target_date="2026-06-14",
            report_result=report_result,
        )

        self.assertEqual(report_result, before)
        serialized = json.dumps(payload, ensure_ascii=False)
        self.assertNotIn("gradings", payload)
        self.assertNotIn('"submissionId"', serialized)
        self.assertNotIn('"gradingResult"', serialized)
        self.assertEqual(payload["recommendedQuizzes"][0]["problemId"], 101)

    def test_report_payload_requires_story5_fields(self) -> None:
        report_result = _report_result()
        report_result.pop("dailyReport")

        with self.assertRaises(ValueError):
            build_report_callback_payload(
                request_id="report-request-1",
                user_id=1,
                character_id=10,
                target_date="2026-06-14",
                report_result=report_result,
            )


class QuizGradeResultPayloadTest(unittest.TestCase):
    def test_quiz_payload_combines_metadata_and_adapter_defaults(self) -> None:
        payload = build_quiz_grade_result_callback_payload(
            user_id=1,
            quiz_id=55,
            grading_result={
                "submissionId": "sub-1",
                "status": "GRADED",
                "scoreAllocation": {"db": 3, "framework": 2},
                "scoreDelta": {"db": 9, "framework": 1},
                "feedback": "원인은 맞췄으나 해결책이 조금 부족합니다.",
                "callbackUrl": "https://evil.example/callback",
            },
        )

        self.assertEqual(payload["submissionId"], "sub-1")
        self.assertEqual(payload["userId"], 1)
        self.assertEqual(payload["quizId"], 55)
        self.assertEqual(payload["status"], "GRADED")
        self.assertEqual(
            payload["scoreAllocation"],
            {"db": 3, "algorithm": 0, "cs": 0, "network": 0, "framework": 2},
        )
        self.assertEqual(
            payload["scoreDelta"],
            {"db": 3, "algorithm": 0, "cs": 0, "network": 0, "framework": 1},
        )
        self.assertEqual(payload["emotion"], "JOY")
        self.assertEqual(payload["statusMessage"], "좋아요, 핵심은 잡았어요!")
        self.assertNotIn("callbackUrl", payload)

    def test_quiz_payload_accepts_custom_emotion_and_status_message(self) -> None:
        payload = build_quiz_grade_result_callback_payload(
            user_id=1,
            quiz_id=55,
            emotion="HAPPY",
            status_message="연속 정답 흐름이 좋아요.",
            grading_result=_grading_result(),
        )

        self.assertEqual(payload["emotion"], "HAPPY")
        self.assertEqual(payload["statusMessage"], "연속 정답 흐름이 좋아요.")

    def test_ungraded_quiz_payload_forces_zero_score_delta(self) -> None:
        payload = build_quiz_grade_result_callback_payload(
            user_id=1,
            quiz_id=55,
            failed_reason="LLM_TIMEOUT",
            grading_result={
                "submissionId": "sub-2",
                "status": "UNGRADED",
                "scoreAllocation": {"db": 10, "network": 5},
                "scoreDelta": {"db": 8, "network": 5},
                "feedback": "",
            },
        )

        self.assertEqual(payload["status"], "UNGRADED")
        self.assertEqual(
            payload["scoreDelta"],
            {"db": 0, "algorithm": 0, "cs": 0, "network": 0, "framework": 0},
        )
        self.assertEqual(payload["emotion"], "SAD")
        self.assertEqual(payload["failedReason"], "LLM_TIMEOUT")


def _report_result() -> dict[str, Any]:
    return {
        "status": "SUCCESS",
        "scoreDelta": {"db": 1, "algorithm": 0, "cs": 0, "network": 0, "framework": 2},
        "emotion": "JOY",
        "statusMessage": "오늘 학습 기록이 알찼어요!",
        "dailyReport": {
            "text": "오늘은 JPA N+1을 정리했습니다.",
            "feedback": "원인과 해결 흐름을 함께 설명한 점이 좋습니다.",
        },
        "nextRecommendation": {
            "topics": ["EntityGraph"],
            "rationale": "N+1 원인은 이해했으니 해결 도구로 확장하세요.",
        },
        "recommendedQuizzes": [
            {
                "problemId": 101,
                "question": "JPA N+1 문제란?",
                "modelAnswer": "fetch join 등으로 해결합니다.",
                "scoreAllocation": {
                    "db": 10,
                    "algorithm": 0,
                    "cs": 0,
                    "network": 0,
                    "framework": 5,
                },
            }
        ],
    }


def _grading_result() -> dict[str, Any]:
    return {
        "submissionId": "sub-1",
        "status": "GRADED",
        "scoreAllocation": {"db": 5},
        "scoreDelta": {"db": 4},
        "feedback": "핵심을 잘 잡았습니다.",
    }


if __name__ == "__main__":
    unittest.main()
