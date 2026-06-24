from __future__ import annotations

import unittest
from typing import Any, Callable

from fastapi.testclient import TestClient

from app.config import Settings
from app.integration.schemas import SpringCallbackResult
from app.main import app
from app.api.quiz_grading import (
    QuizGradingRequest,
    accept_quiz_grading_request,
    get_callback_client,
    get_quiz_grader,
    get_settings,
)


class FakeCallbackClient:
    def __init__(self) -> None:
        self.calls: list[dict[str, Any]] = []

    def send_quiz_grade_result(
        self,
        *,
        user_id: int,
        character_id: int,
        quiz_id: int,
        grading_result: dict[str, Any],
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
        return SpringCallbackResult(status_code=200, ok=True, retryable=False)


class RecordingBackgroundTasks:
    def __init__(self) -> None:
        self.tasks: list[tuple[Callable[..., Any], tuple[Any, ...], dict[str, Any]]] = []

    def add_task(
        self,
        func: Callable[..., Any],
        *args: Any,
        **kwargs: Any,
    ) -> None:
        self.tasks.append((func, args, kwargs))


class QuizGradingEndpointTest(unittest.TestCase):
    def setUp(self) -> None:
        app.dependency_overrides.clear()
        self.client = TestClient(app)

    def tearDown(self) -> None:
        app.dependency_overrides.clear()

    def test_valid_request_returns_accepted_response(self) -> None:
        app.dependency_overrides[get_quiz_grader] = lambda: _graded_grader
        app.dependency_overrides[get_callback_client] = FakeCallbackClient
        app.dependency_overrides[get_settings] = lambda: Settings(_env_file=None)

        response = self.client.post(
            "/api/internal/quizzes/grade",
            json=_request_payload(),
        )

        self.assertEqual(response.status_code, 202)
        self.assertEqual(
            response.json(),
            {"accepted": True, "submissionId": "sub-1"},
        )

    def test_invalid_request_returns_validation_error(self) -> None:
        payload = _request_payload()
        payload.pop("quizId")

        response = self.client.post(
            "/api/internal/quizzes/grade",
            json=payload,
        )

        self.assertEqual(response.status_code, 422)

    def test_internal_auth_rejects_missing_and_wrong_token_without_leakage(self) -> None:
        expected_secret = "expected-internal-secret"
        received_secret = "wrong-internal-secret"
        app.dependency_overrides[get_settings] = lambda: Settings(
            _env_file=None,
            spring_internal_api_secret=expected_secret,
        )

        missing = self.client.post(
            "/api/internal/quizzes/grade",
            json=_request_payload(),
        )
        wrong = self.client.post(
            "/api/internal/quizzes/grade",
            json=_request_payload(),
            headers={"Authorization": f"Internal {received_secret}"},
        )

        self.assertEqual(missing.status_code, 401)
        self.assertEqual(wrong.status_code, 401)
        response_text = missing.text + wrong.text
        self.assertNotIn(expected_secret, response_text)
        self.assertNotIn(received_secret, response_text)
        self.assertNotIn("Authorization", response_text)

    def test_blank_internal_secret_allows_local_dev_request_without_auth(self) -> None:
        app.dependency_overrides[get_quiz_grader] = lambda: _graded_grader
        app.dependency_overrides[get_callback_client] = FakeCallbackClient
        app.dependency_overrides[get_settings] = lambda: Settings(
            _env_file=None,
            spring_internal_api_secret=None,
        )

        response = self.client.post(
            "/api/internal/quizzes/grade",
            json=_request_payload(),
        )

        self.assertEqual(response.status_code, 202)

    def test_health_endpoint_is_preserved(self) -> None:
        response = self.client.get("/api/health")

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["service"], "fastapi")

    def test_accept_helper_schedules_background_work_without_calling_grader_inline(self) -> None:
        request = QuizGradingRequest(**_request_payload())
        background_tasks = RecordingBackgroundTasks()
        callback_client = FakeCallbackClient()
        called = False

        def exploding_grader(**kwargs: Any) -> dict[str, Any]:
            nonlocal called
            called = True
            raise AssertionError("grader must not run inline")

        accepted = accept_quiz_grading_request(
            request,
            background_tasks=background_tasks,
            grader=exploding_grader,
            callback_client=callback_client,
            settings=Settings(_env_file=None),
        )

        self.assertFalse(called)
        self.assertTrue(accepted.accepted)
        self.assertEqual(accepted.submissionId, "sub-1")
        self.assertEqual(len(background_tasks.tasks), 1)

    def test_callback_url_mismatch_logs_safe_warning_but_still_accepts(self) -> None:
        payload = _request_payload()
        payload["callbackUrl"] = "https://evil.example/steal?token=do-not-log"
        request = QuizGradingRequest(**payload)
        background_tasks = RecordingBackgroundTasks()

        with self.assertLogs("app.api.quiz_grading", level="WARNING") as logs:
            accepted = accept_quiz_grading_request(
                request,
                background_tasks=background_tasks,
                grader=_graded_grader,
                callback_client=FakeCallbackClient(),
                settings=Settings(
                    _env_file=None,
                    spring_boot_internal_base_url="http://spring.local:8080",
                ),
            )

        joined_logs = "\n".join(logs.output)
        self.assertTrue(accepted.accepted)
        self.assertIn("sub-1", joined_logs)
        self.assertIn("callback_url_mismatch", joined_logs)
        self.assertNotIn("evil.example", joined_logs)
        self.assertNotIn("do-not-log", joined_logs)
        self.assertNotIn("쿼리가 N번 더 나가는 문제", joined_logs)


def _graded_grader(**kwargs: Any) -> dict[str, Any]:
    return {
        "submissionId": kwargs["submissionId"],
        "status": "GRADED",
        "scoreAllocation": kwargs["scoreAllocation"],
        "scoreDelta": {"db": 0, "algorithm": 2, "cs": 0, "network": 0, "framework": 0},
        "feedback": "원인은 맞췄으나 해결책이 조금 부족합니다.",
        "confidence": 0.8,
    }


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
            "db": 0,
            "algorithm": 3,
            "cs": 0,
            "network": 0,
            "framework": 0,
            "unknown": 7,
        },
        "characterMetadata": {
            "personality": "칭찬을 많이 하지만 틀린 부분은 명확하게 지적하는 성격"
        },
        "callbackUrl": "http://localhost:8080/api/internal/quizzes/grade-result",
    }


if __name__ == "__main__":
    unittest.main()
