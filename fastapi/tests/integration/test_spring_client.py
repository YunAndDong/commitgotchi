from __future__ import annotations

import os
import unittest
from typing import Any, Mapping
from unittest.mock import patch

from pydantic import SecretStr, ValidationError

from app.config import Settings
from app.integration.schemas import SpringCallbackResponse
from app.integration.spring_client import (
    SpringCallbackClient,
    build_internal_auth_headers,
    build_spring_callback_url,
)


class FakeTransport:
    def __init__(
        self,
        response: SpringCallbackResponse | None = None,
        *,
        error: Exception | None = None,
    ) -> None:
        self.response = response or SpringCallbackResponse(status_code=200, body={"ok": True})
        self.error = error
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
        if self.error is not None:
            raise self.error
        return self.response


class SpringClientConfigTest(unittest.TestCase):
    def test_settings_defaults(self) -> None:
        settings = Settings(_env_file=None)

        self.assertEqual(settings.spring_boot_internal_base_url, "http://localhost:8080")
        self.assertIsNone(settings.spring_internal_api_secret)
        self.assertEqual(settings.spring_report_callback_path, "/api/report")
        self.assertEqual(
            settings.spring_quiz_grade_result_path,
            "/api/internal/quizzes/grade-result",
        )
        self.assertEqual(settings.spring_callback_timeout_seconds, 10.0)

    def test_settings_env_override_and_normalization(self) -> None:
        with patch.dict(
            os.environ,
            {
                "SPRING_BOOT_INTERNAL_BASE_URL": "https://spring.internal:8443/",
                "SPRING_INTERNAL_API_SECRET": "internal-secret",
                "SPRING_REPORT_CALLBACK_PATH": "api/report",
                "SPRING_QUIZ_GRADE_RESULT_PATH": "/api//internal//quizzes//grade-result",
                "SPRING_CALLBACK_TIMEOUT_SECONDS": "2.5",
            },
            clear=True,
        ):
            settings = Settings(_env_file=None)

        self.assertEqual(settings.spring_boot_internal_base_url, "https://spring.internal:8443")
        self.assertEqual(settings.spring_internal_api_secret.get_secret_value(), "internal-secret")
        self.assertEqual(settings.spring_report_callback_path, "/api/report")
        self.assertEqual(
            settings.spring_quiz_grade_result_path,
            "/api/internal/quizzes/grade-result",
        )
        self.assertEqual(settings.spring_callback_timeout_seconds, 2.5)

    def test_invalid_base_url_rejects_path_query_and_fragment(self) -> None:
        invalid_values = [
            "http://localhost:8080/api",
            "http://localhost:8080?x=1",
            "http://localhost:8080#frag",
            "localhost:8080",
        ]
        for value in invalid_values:
            with self.subTest(value=value):
                with self.assertRaises(ValidationError):
                    Settings(_env_file=None, spring_boot_internal_base_url=value)

    def test_timeout_must_be_positive(self) -> None:
        for value in (0, -1):
            with self.subTest(value=value):
                with self.assertRaises(ValidationError):
                    Settings(_env_file=None, spring_callback_timeout_seconds=value)


class SpringClientHelperTest(unittest.TestCase):
    def test_url_builder_normalizes_slash_boundaries(self) -> None:
        self.assertEqual(
            build_spring_callback_url(
                base_url="http://spring.local:8080/",
                path="api/report",
            ),
            "http://spring.local:8080/api/report",
        )
        self.assertEqual(
            build_spring_callback_url(
                base_url="http://spring.local:8080",
                path="/api//internal//quizzes//grade-result",
            ),
            "http://spring.local:8080/api/internal/quizzes/grade-result",
        )

    def test_url_builder_rejects_absolute_callback_path(self) -> None:
        with self.assertRaises(ValueError):
            build_spring_callback_url(
                base_url="http://spring.local:8080",
                path="https://evil.example/api/report",
            )

    def test_internal_auth_headers_use_single_contract_and_omit_blank_secret(self) -> None:
        self.assertEqual(
            build_internal_auth_headers(SecretStr("test-secret")),
            {"Authorization": "Internal test-secret"},
        )
        self.assertEqual(build_internal_auth_headers(SecretStr("")), {})
        self.assertEqual(build_internal_auth_headers(None), {})
        self.assertNotIn("test-secret", repr(SecretStr("test-secret")))


class SpringCallbackClientTest(unittest.TestCase):
    def test_report_sender_posts_json_to_configured_path_with_headers_and_timeout(self) -> None:
        settings = Settings(
            _env_file=None,
            spring_boot_internal_base_url="http://spring.local:8080/",
            spring_internal_api_secret="test-secret",
            spring_callback_timeout_seconds=3.5,
        )
        transport = FakeTransport()
        client = SpringCallbackClient(settings=settings, transport=transport)

        result = client.post_report_result({"requestId": "req-1"})

        self.assertTrue(result.ok)
        self.assertEqual(transport.calls[0]["url"], "http://spring.local:8080/api/report")
        self.assertEqual(transport.calls[0]["headers"]["Content-Type"], "application/json")
        self.assertEqual(
            transport.calls[0]["headers"]["Authorization"],
            "Internal test-secret",
        )
        self.assertEqual(transport.calls[0]["payload"], {"requestId": "req-1"})
        self.assertEqual(transport.calls[0]["timeout_seconds"], 3.5)

    def test_quiz_sender_uses_configured_destination_not_payload_callback_url(self) -> None:
        settings = Settings(
            _env_file=None,
            spring_boot_internal_base_url="http://spring.local:8080",
            spring_quiz_grade_result_path="/api/internal/quizzes/grade-result",
        )
        transport = FakeTransport()
        client = SpringCallbackClient(settings=settings, transport=transport)

        client.post_quiz_grade_result(
            {
                "submissionId": "sub-1",
                "callbackUrl": "https://evil.example/steal",
            }
        )

        self.assertEqual(
            transport.calls[0]["url"],
            "http://spring.local:8080/api/internal/quizzes/grade-result",
        )

    def test_send_helpers_build_payloads_before_posting(self) -> None:
        settings = Settings(_env_file=None, spring_boot_internal_base_url="http://spring.local:8080")
        transport = FakeTransport()
        client = SpringCallbackClient(settings=settings, transport=transport)

        client.send_report_callback(
            request_id="req-1",
            user_id=1,
            character_id=10,
            target_date="2026-06-14",
            report_result=_report_result(),
        )
        client.send_quiz_grade_result(
            user_id=1,
            quiz_id=55,
            grading_result=_grading_result(),
        )

        self.assertEqual(transport.calls[0]["payload"]["requestId"], "req-1")
        self.assertEqual(transport.calls[1]["payload"]["quizId"], 55)

    def test_response_classification(self) -> None:
        cases = [
            (200, True, False, None),
            (201, False, False, "spring callback contract mismatch"),
            (400, False, False, "spring callback client error"),
            (503, False, True, "spring callback server error"),
        ]
        for status_code, ok, retryable, error in cases:
            with self.subTest(status_code=status_code):
                transport = FakeTransport(SpringCallbackResponse(status_code=status_code))
                client = SpringCallbackClient(settings=Settings(_env_file=None), transport=transport)

                result = client.post_report_result({"requestId": "req-1"})

                self.assertEqual(result.status_code, status_code)
                self.assertEqual(result.ok, ok)
                self.assertEqual(result.retryable, retryable)
                self.assertEqual(result.error, error)

    def test_timeout_and_connection_error_are_retryable_and_do_not_leak_exception_text(self) -> None:
        cases = [
            (TimeoutError("test-secret"), "spring callback timeout"),
            (ConnectionError("test-secret"), "spring callback connection error"),
        ]
        for error, expected_message in cases:
            with self.subTest(error=type(error).__name__):
                transport = FakeTransport(error=error)
                client = SpringCallbackClient(settings=Settings(_env_file=None), transport=transport)

                result = client.post_report_result({"requestId": "req-1"})

                self.assertFalse(result.ok)
                self.assertTrue(result.retryable)
                self.assertEqual(result.error, expected_message)
                self.assertNotIn("test-secret", result.error or "")


def _report_result() -> dict[str, Any]:
    return {
        "status": "SUCCESS",
        "scoreDelta": {"db": 1, "algorithm": 0, "cs": 0, "network": 0, "framework": 2},
        "statusMessage": "좋은 흐름이에요.",
        "dailyReport": {"text": "JPA를 공부했습니다.", "feedback": "근거가 좋습니다."},
        "nextRecommendation": {"topics": ["JPA"], "rationale": "fetch join으로 확장하세요."},
        "recommendedQuizzes": [],
    }


def _grading_result() -> dict[str, Any]:
    return {
        "submissionId": "sub-1",
        "status": "GRADED",
        "scoreAllocation": {"db": 5, "algorithm": 0, "cs": 0, "network": 0, "framework": 0},
        "scoreDelta": {"db": 4, "algorithm": 0, "cs": 0, "network": 0, "framework": 0},
        "feedback": "핵심을 잘 잡았습니다.",
    }


if __name__ == "__main__":
    unittest.main()
