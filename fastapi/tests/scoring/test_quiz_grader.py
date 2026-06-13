from __future__ import annotations

import unittest
from typing import Any, Mapping

from app.scoring.quiz_grader import grade_quiz_answer


class FakeGradingClient:
    def __init__(self, response: Mapping[str, Any] | str | None = None) -> None:
        self.response = response or {
            "scoreDelta": {
                "db": 5,
                "algorithm": 0,
                "cs": 0,
                "network": 0,
                "framework": 4,
            },
            "feedback": "핵심 원인과 해결책을 일부 설명했습니다.",
            "confidence": 0.8,
        }
        self.prompts: list[str] = []

    def grade(self, prompt: str) -> Mapping[str, Any] | str:
        self.prompts.append(prompt)
        return self.response


class ExplodingClient:
    def grade(self, prompt: str) -> Mapping[str, Any] | str:
        raise RuntimeError("model unavailable")


class QuizGraderTest(unittest.TestCase):
    def test_grade_quiz_answer_returns_contract_shape_and_prompt_context(self) -> None:
        client = FakeGradingClient()

        result = grade_quiz_answer(
            submissionId="sub-1",
            problemId=101,
            question="N+1 문제란 무엇이며 어떻게 해결하나요?",
            modelAnswer="지연 로딩 반복 접근으로 추가 쿼리가 발생하며 fetch join 등으로 해결합니다.",
            userAnswer="N+1은 지연 로딩 때문에 쿼리가 많이 나가고 fetch join으로 줄일 수 있습니다.",
            scoreAllocation={"db": 10, "framework": 10},
            difficulty="advanced",
            sourcePath="qna/database.md",
            rubric={"mustMention": ["N+1", "fetch join"]},
            client=client,
        )

        self.assertEqual(result["submissionId"], "sub-1")
        self.assertEqual(result["status"], "GRADED")
        self.assertEqual(tuple(result["scoreAllocation"]), ("db", "algorithm", "cs", "network", "framework"))
        self.assertEqual(tuple(result["scoreDelta"]), ("db", "algorithm", "cs", "network", "framework"))
        self.assertEqual(result["scoreDelta"]["db"], 5)
        self.assertEqual(result["scoreDelta"]["framework"], 4)
        self.assertEqual(result["confidence"], 0.8)
        self.assertEqual(len(client.prompts), 1)
        self.assertIn("scoreAllocation", client.prompts[0])
        self.assertIn("mustMention", client.prompts[0])
        self.assertIn("총합 10점으로 재분배하지 않습니다", client.prompts[0])

    def test_model_weird_fields_missing_fields_and_overages_are_corrected(self) -> None:
        client = FakeGradingClient(
            {
                "scoreDelta": {
                    "db": 99,
                    "framework": 6,
                    "unknown": 10,
                },
                "feedback": "범위를 벗어난 모델 출력입니다.",
                "confidence": 2,
            }
        )

        result = grade_quiz_answer(
            submissionId="sub-2",
            problemId=102,
            question="OAuth와 JWT 차이는?",
            modelAnswer="OAuth는 인가 프로토콜이고 JWT는 토큰 형식입니다.",
            userAnswer="OAuth는 권한 위임 흐름이고 JWT는 서명된 토큰입니다.",
            scoreAllocation={
                "db": 7,
                "algorithm": 3,
                "cs": 0,
                "network": 12,
                "framework": 10,
                "unknown": 10,
            },
            client=client,
        )

        self.assertEqual(
            result["scoreAllocation"],
            {
                "db": 7,
                "algorithm": 3,
                "cs": 0,
                "network": 10,
                "framework": 10,
            },
        )
        self.assertEqual(
            result["scoreDelta"],
            {
                "db": 7,
                "algorithm": 0,
                "cs": 0,
                "network": 0,
                "framework": 6,
            },
        )
        self.assertEqual(result["confidence"], 1.0)
        self.assertNotIn("unknown", result["scoreDelta"])

    def test_total_fifty_point_delta_is_allowed(self) -> None:
        client = FakeGradingClient(
            {
                "scoreDelta": {
                    "db": 99,
                    "algorithm": 99,
                    "cs": 99,
                    "network": 99,
                    "framework": 99,
                },
                "feedback": "모든 필드를 충분히 설명했습니다.",
                "confidence": 0.95,
            }
        )

        result = grade_quiz_answer(
            submissionId="sub-50",
            problemId=50,
            question="전 범위 문제",
            modelAnswer="전 범위 모범답안",
            userAnswer="전 범위를 구체적으로 설명했습니다.",
            scoreAllocation={field: 10 for field in ("db", "algorithm", "cs", "network", "framework")},
            client=client,
        )

        self.assertEqual(sum(result["scoreAllocation"].values()), 50)
        self.assertEqual(sum(result["scoreDelta"].values()), 50)

    def test_json_string_response_is_parsed(self) -> None:
        client = FakeGradingClient(
            """
            ```json
            {
              "scoreDelta": {"db": 4, "algorithm": 0, "cs": 0, "network": 0, "framework": 3},
              "feedback": "JSON 문자열도 파싱됩니다.",
              "confidence": "0.7"
            }
            ```
            """
        )

        result = grade_quiz_answer(
            submissionId="sub-json",
            problemId=103,
            question="N+1?",
            modelAnswer="N+1 설명",
            userAnswer="N+1을 설명했습니다.",
            scoreAllocation={"db": 10, "framework": 10},
            client=client,
        )

        self.assertEqual(result["status"], "GRADED")
        self.assertEqual(result["scoreDelta"]["db"], 4)
        self.assertEqual(result["scoreDelta"]["framework"], 3)
        self.assertEqual(result["confidence"], 0.7)

    def test_empty_answer_is_graded_zero_without_model_call(self) -> None:
        result = grade_quiz_answer(
            submissionId="sub-empty",
            problemId=104,
            question="N+1?",
            modelAnswer="N+1 설명",
            userAnswer="   ",
            scoreAllocation={"db": 10, "framework": 10},
            client=ExplodingClient(),
        )

        self.assertEqual(result["status"], "GRADED")
        self.assertEqual(
            result["scoreDelta"],
            {
                "db": 0,
                "algorithm": 0,
                "cs": 0,
                "network": 0,
                "framework": 0,
            },
        )
        self.assertEqual(result["confidence"], 0.0)

    def test_model_exception_returns_ungraded_zero_delta(self) -> None:
        result = grade_quiz_answer(
            submissionId="sub-error",
            problemId=105,
            question="N+1?",
            modelAnswer="N+1 설명",
            userAnswer="N+1을 설명했습니다.",
            scoreAllocation={"db": 10, "framework": 10},
            client=ExplodingClient(),
        )

        self.assertEqual(result["status"], "UNGRADED")
        self.assertEqual(
            result["scoreDelta"],
            {
                "db": 0,
                "algorithm": 0,
                "cs": 0,
                "network": 0,
                "framework": 0,
            },
        )
        self.assertEqual(result["confidence"], 0.0)

    def test_malformed_model_output_returns_ungraded_zero_delta(self) -> None:
        result = grade_quiz_answer(
            submissionId="sub-malformed",
            problemId=106,
            question="N+1?",
            modelAnswer="N+1 설명",
            userAnswer="N+1을 설명했습니다.",
            scoreAllocation={"db": 10, "framework": 10},
            client=FakeGradingClient("not json"),
        )

        self.assertEqual(result["status"], "UNGRADED")
        self.assertEqual(result["scoreDelta"]["db"], 0)
        self.assertEqual(result["scoreDelta"]["framework"], 0)

    def test_missing_score_delta_object_returns_ungraded_zero_delta(self) -> None:
        result = grade_quiz_answer(
            submissionId="sub-missing-delta",
            problemId=107,
            question="N+1?",
            modelAnswer="N+1 설명",
            userAnswer="N+1을 설명했습니다.",
            scoreAllocation={"db": 10, "framework": 10},
            client=FakeGradingClient({"feedback": "scoreDelta가 없습니다.", "confidence": 0.9}),
        )

        self.assertEqual(result["status"], "UNGRADED")
        self.assertEqual(result["scoreDelta"]["db"], 0)
        self.assertEqual(result["confidence"], 0.0)


if __name__ == "__main__":
    unittest.main()
