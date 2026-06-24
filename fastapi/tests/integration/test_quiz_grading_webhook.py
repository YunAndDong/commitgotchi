from __future__ import annotations

import json
import unittest
from typing import Any, Mapping

from app.config import Settings
from app.integration.schemas import SpringCallbackResponse, SpringCallbackResult
from app.integration.spring_client import SpringCallbackClient
from app.integration.spring_payloads import build_quiz_grade_result_callback_payload
from app.api.quiz_grading import QuizGradingRequest, process_quiz_grading_request


ZERO_VECTOR = {"db": 0, "algorithm": 0, "cs": 0, "network": 0, "framework": 0}


class RecordingGrader:
    def __init__(
        self,
        response: Mapping[str, Any] | None = None,
        *,
        error: Exception | None = None,
    ) -> None:
        self.response = response
        self.error = error
        self.calls: list[dict[str, Any]] = []

    def __call__(self, **kwargs: Any) -> Mapping[str, Any]:
        self.calls.append(dict(kwargs))
        if self.error is not None:
            raise self.error
        return self.response or {
            "submissionId": kwargs["submissionId"],
            "status": "GRADED",
            "scoreAllocation": kwargs["scoreAllocation"],
            "scoreDelta": {"db": 9, "algorithm": 2, "cs": 2, "network": 0, "framework": 0},
            "feedback": "핵심을 잘 잡았습니다.",
            "confidence": 0.9,
        }


class RecordingCallbackClient:
    def __init__(
        self,
        result: SpringCallbackResult | None = None,
        *,
        error: Exception | None = None,
    ) -> None:
        self.result = result or SpringCallbackResult(
            status_code=200,
            ok=True,
            retryable=False,
        )
        self.error = error
        self.payloads: list[dict[str, Any]] = []
        self.calls: list[dict[str, Any]] = []

    def send_quiz_grade_result(
        self,
        *,
        user_id: int,
        character_id: int,
        quiz_id: int,
        grading_result: Mapping[str, Any],
        emotion: str | None = None,
        status_message: str | None = None,
        failed_reason: str | None = None,
    ) -> SpringCallbackResult:
        self.calls.append(
            {
                "user_id": user_id,
                "character_id": character_id,
                "quiz_id": quiz_id,
                "grading_result": dict(grading_result),
                "failed_reason": failed_reason,
            }
        )
        if self.error is not None:
            raise self.error
        self.payloads.append(
            build_quiz_grade_result_callback_payload(
                user_id=user_id,
                character_id=character_id,
                quiz_id=quiz_id,
                grading_result=grading_result,
                emotion=emotion,
                status_message=status_message,
                failed_reason=failed_reason,
            )
        )
        return self.result


class FakeTransport:
    def __init__(self) -> None:
        self.calls: list[dict[str, Any]] = []

    def post_json(
        self,
        url: str,
        *,
        headers: Mapping[str, str],
        payload: Mapping[str, Any],
        timeout_seconds: float,
    ) -> SpringCallbackResponse:
        self.calls.append(
            {
                "url": url,
                "headers": dict(headers),
                "payload": dict(payload),
                "timeout_seconds": timeout_seconds,
            }
        )
        return SpringCallbackResponse(status_code=200, body={"ok": True})


class QuizGradingWebhookTest(unittest.TestCase):
    def test_process_calls_grader_with_expected_sanitized_fields(self) -> None:
        request = QuizGradingRequest(**_request_payload())
        grader = RecordingGrader()
        callback_client = RecordingCallbackClient()

        result = process_quiz_grading_request(
            request,
            grader=grader,
            callback_client=callback_client,
        )

        self.assertTrue(result.ok)
        self.assertEqual(len(grader.calls), 1)
        self.assertEqual(
            set(grader.calls[0]),
            {
                "submissionId",
                "problemId",
                "question",
                "modelAnswer",
                "userAnswer",
                "scoreAllocation",
                "difficulty",
                "sourcePath",
                "rubric",
            },
        )
        self.assertEqual(
            grader.calls[0]["scoreAllocation"],
            {"db": 4, "algorithm": 3, "cs": 0, "network": 0, "framework": 10},
        )
        self.assertNotIn("userId", grader.calls[0])
        self.assertNotIn("quizId", grader.calls[0])
        self.assertNotIn("callbackUrl", grader.calls[0])

    def test_graded_result_sends_clamped_spring_callback_payload(self) -> None:
        request = QuizGradingRequest(**_request_payload())
        callback_client = RecordingCallbackClient()

        process_quiz_grading_request(
            request,
            grader=RecordingGrader(),
            callback_client=callback_client,
        )

        payload = callback_client.payloads[0]
        self.assertEqual(payload["submissionId"], "sub-1")
        self.assertEqual(payload["userId"], 1)
        self.assertEqual(payload["quizId"], 55)
        self.assertEqual(payload["status"], "GRADED")
        self.assertEqual(payload["scoreDelta"]["db"], 4)
        self.assertEqual(payload["scoreDelta"]["algorithm"], 2)
        self.assertEqual(payload["feedback"], "핵심을 잘 잡았습니다.")
        self.assertEqual(payload["emotion"], "JOY")
        self.assertEqual(payload["statusMessage"], "좋아요, 핵심은 잡았어요!")

    def test_ungraded_result_sends_zero_vector_with_safe_failed_reason(self) -> None:
        request = QuizGradingRequest(**_request_payload())
        callback_client = RecordingCallbackClient()

        process_quiz_grading_request(
            request,
            grader=RecordingGrader(
                {
                    "submissionId": "sub-1",
                    "status": "UNGRADED",
                    "scoreAllocation": request.scoreAllocation,
                    "scoreDelta": {
                        "db": 4,
                        "algorithm": 3,
                        "cs": 0,
                        "network": 0,
                        "framework": 10,
                    },
                    "feedback": "",
                }
            ),
            callback_client=callback_client,
        )

        payload = callback_client.payloads[0]
        self.assertEqual(payload["status"], "UNGRADED")
        self.assertEqual(payload["scoreDelta"], ZERO_VECTOR)
        self.assertEqual(payload["failedReason"], "GRADER_UNGRADED")

    def test_grader_exception_sends_ungraded_zero_vector_without_raw_exception_text(self) -> None:
        request = QuizGradingRequest(**_request_payload())
        callback_client = RecordingCallbackClient()

        process_quiz_grading_request(
            request,
            grader=RecordingGrader(error=RuntimeError("raw-secret-token")),
            callback_client=callback_client,
        )

        payload = callback_client.payloads[0]
        serialized = json.dumps(payload, ensure_ascii=False)
        self.assertEqual(payload["status"], "UNGRADED")
        self.assertEqual(payload["scoreDelta"], ZERO_VECTOR)
        self.assertEqual(payload["failedReason"], "GRADER_ERROR")
        self.assertNotIn("raw-secret-token", serialized)

    def test_request_callback_url_is_not_used_as_sender_destination(self) -> None:
        request = QuizGradingRequest(**_request_payload())
        transport = FakeTransport()
        client = SpringCallbackClient(
            settings=Settings(
                _env_file=None,
                spring_boot_internal_base_url="http://spring.local:8080",
                spring_quiz_grade_result_path="/api/internal/quizzes/grade-result",
            ),
            transport=transport,
        )

        process_quiz_grading_request(
            request,
            grader=RecordingGrader(),
            callback_client=client,
        )

        self.assertEqual(
            transport.calls[0]["url"],
            "http://spring.local:8080/api/internal/quizzes/grade-result",
        )
        self.assertNotIn("evil.example", transport.calls[0]["url"])
        self.assertNotIn("callbackUrl", transport.calls[0]["payload"])

    def test_callback_failure_logs_safe_signal_and_returns_failure_result(self) -> None:
        request = QuizGradingRequest(**_request_payload())
        callback_client = RecordingCallbackClient(
            SpringCallbackResult(
                status_code=503,
                ok=False,
                retryable=True,
                error="spring callback server error",
            )
        )

        with self.assertLogs("app.api.quiz_grading", level="WARNING") as logs:
            result = process_quiz_grading_request(
                request,
                grader=RecordingGrader(),
                callback_client=callback_client,
            )

        joined_logs = "\n".join(logs.output)
        self.assertFalse(result.ok)
        self.assertTrue(result.retryable)
        self.assertIn("sub-1", joined_logs)
        self.assertIn("CALLBACK_RETRYABLE_FAILURE", joined_logs)
        self.assertNotIn("Internal", joined_logs)
        self.assertNotIn("쿼리가 N번 더 나가는 문제", joined_logs)


def _request_payload() -> dict[str, Any]:
    return {
        "submissionId": "sub-1",
        "userId": 1,
        "characterId": 10,
        "quizId": 55,
        "problemId": 101,
        "question": "JPA N+1 문제란 무엇인가?",
        "modelAnswer": "연관 엔티티를 지연 로딩할 때 추가 쿼리가 발생하는 문제입니다.",
        "userAnswer": "쿼리가 N번 더 나가는 문제",
        "scoreAllocation": {
            "db": 4,
            "algorithm": 3,
            "cs": -1,
            "network": "bad",
            "framework": 99,
            "unknown": 5,
        },
        "difficulty": "advanced",
        "sourcePath": "quiz-bank/jpa.md",
        "rubric": {"mustMention": ["N+1"]},
        "callbackUrl": "https://evil.example/steal",
    }


if __name__ == "__main__":
    unittest.main()
