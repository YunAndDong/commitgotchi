from __future__ import annotations

import json
import inspect
import sys
import types
import unittest
from typing import Any, Mapping
from unittest.mock import patch

from app.config import Settings
from app.integration.schemas import SpringCallbackResponse
from app.integration.spring_client import SpringCallbackClient


class FakeTransport:
    def __init__(
        self,
        response: SpringCallbackResponse | None = None,
        *,
        error: Exception | None = None,
    ) -> None:
        self.response = response or SpringCallbackResponse(
            status_code=200,
            body={"ok": True},
        )
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


class RecordingReportService:
    def __init__(
        self,
        result: Mapping[str, Any] | None = None,
        *,
        error: Exception | None = None,
    ) -> None:
        self.result = dict(result or _report_result())
        self.error = error
        self.calls: list[dict[str, Any]] = []

    def __call__(self, **kwargs: Any) -> Mapping[str, Any]:
        self.calls.append(dict(kwargs))
        if self.error is not None:
            raise self.error
        return self.result


class FakeSqsClient:
    def __init__(self, messages: list[dict[str, Any]] | None = None) -> None:
        self.messages = list(messages or [])
        self.receive_calls: list[dict[str, Any]] = []
        self.delete_calls: list[dict[str, Any]] = []

    def receive_message(self, **kwargs: Any) -> dict[str, Any]:
        self.receive_calls.append(dict(kwargs))
        return {"Messages": list(self.messages)}

    def delete_message(self, **kwargs: Any) -> None:
        self.delete_calls.append(dict(kwargs))


class ReportConsumerSettingsTest(unittest.TestCase):
    def test_settings_load_sqs_defaults_without_required_queue_url(self) -> None:
        settings = Settings(_env_file=None)

        self.assertEqual(settings.aws_region, "ap-northeast-2")
        self.assertIsNone(settings.aws_sqs_endpoint)
        self.assertIsNone(settings.aws_access_key_id)
        self.assertIsNone(settings.aws_secret_access_key)
        self.assertIsNone(settings.report_request_queue_url)
        self.assertIsNone(settings.report_request_dlq_url)

    def test_settings_load_sqs_env_values_without_secret_repr_leak(self) -> None:
        with patch.dict(
            "os.environ",
            {
                "AWS_REGION": "us-east-1",
                "AWS_SQS_ENDPOINT": "http://localhost:4566",
                "AWS_ACCESS_KEY_ID": "fake-access-key",
                "AWS_SECRET_ACCESS_KEY": "fake-secret-key",
                "REPORT_REQUEST_QUEUE_URL": "https://sqs.fake/report-requests",
                "REPORT_REQUEST_DLQ_URL": "https://sqs.fake/report-requests-dlq",
            },
            clear=True,
        ):
            settings = Settings(_env_file=None)

        self.assertEqual(settings.aws_region, "us-east-1")
        self.assertEqual(settings.aws_sqs_endpoint, "http://localhost:4566")
        self.assertEqual(settings.aws_access_key_id, "fake-access-key")
        self.assertEqual(
            settings.aws_secret_access_key.get_secret_value(),
            "fake-secret-key",
        )
        self.assertNotIn("fake-secret-key", repr(settings.aws_secret_access_key))
        self.assertEqual(
            settings.report_request_queue_url,
            "https://sqs.fake/report-requests",
        )
        self.assertEqual(
            settings.report_request_dlq_url,
            "https://sqs.fake/report-requests-dlq",
        )

    def test_boto3_sqs_client_uses_region_endpoint_and_fake_credentials(self) -> None:
        from app.integration.report_consumer import create_report_sqs_client

        fake_boto3 = types.SimpleNamespace(calls=[])

        def client(service_name: str, **kwargs: Any) -> object:
            fake_boto3.calls.append((service_name, kwargs))
            return object()

        fake_boto3.client = client
        settings = Settings(
            _env_file=None,
            aws_region="us-west-2",
            aws_sqs_endpoint="http://localhost:4566",
            aws_access_key_id="fake-access-key",
            aws_secret_access_key="fake-secret-key",
        )

        with patch.dict(sys.modules, {"boto3": fake_boto3}):
            create_report_sqs_client(settings=settings)

        service_name, kwargs = fake_boto3.calls[0]
        self.assertEqual(service_name, "sqs")
        self.assertEqual(kwargs["region_name"], "us-west-2")
        self.assertEqual(kwargs["endpoint_url"], "http://localhost:4566")
        self.assertEqual(kwargs["aws_access_key_id"], "fake-access-key")
        self.assertEqual(kwargs["aws_secret_access_key"], "fake-secret-key")


class ReportConsumerProcessTest(unittest.TestCase):
    def test_valid_message_maps_service_input_and_posts_report_callback(self) -> None:
        from app.integration.report_consumer import process_report_request_message

        service = RecordingReportService()
        transport = FakeTransport()
        callback_client = _spring_callback_client(transport)
        result = process_report_request_message(
            _valid_message(
                callbackUrl="https://evil.example/callback",
                gradings=[{"submissionId": "sub-1"}],
            ),
            report_service=service,
            callback_client=callback_client,
        )

        self.assertTrue(result.callback_attempted)
        self.assertTrue(result.callback_ok)
        self.assertTrue(result.delete_message)
        self.assertFalse(result.retryable)
        self.assertFalse(result.poison)
        self.assertEqual(result.request_id, "report-request-1")

        self.assertEqual(len(service.calls), 1)
        self.assertEqual(service.calls[0]["report_title"], "오늘 학습 기록")
        self.assertEqual(service.calls[0]["report_content"], "짧은 합성 리포트 본문")
        self.assertEqual(
            service.calls[0]["user_metadata"]["reportDirection"]["scoreDeltaHint"][
                "algorithm"
            ],
            3,
        )
        self.assertEqual(
            service.calls[0]["character_personality"],
            "칭찬과 교정을 균형 있게 하는 성격",
        )
        self.assertEqual(service.calls[0]["character_metadata"]["characterId"], 10)
        self.assertEqual(
            service.calls[0]["character_metadata"]["currentStats"]["algorithm"],
            200,
        )
        self.assertNotIn("callbackUrl", service.calls[0]["character_metadata"])

        callback = transport.calls[0]
        self.assertEqual(callback["url"], "http://spring.local:8080/api/report")
        self.assertEqual(callback["payload"]["requestId"], "report-request-1")
        self.assertEqual(callback["payload"]["userId"], 1)
        self.assertEqual(callback["payload"]["characterId"], 10)
        self.assertEqual(callback["payload"]["targetDate"], "2026-06-14")
        self.assertEqual(callback["payload"]["status"], "SUCCESS")
        self.assertEqual(callback["payload"]["scoreDelta"]["algorithm"], 3)
        serialized_payload = json.dumps(callback["payload"], ensure_ascii=False)
        self.assertNotIn("gradings", serialized_payload)
        self.assertNotIn("submissionId", serialized_payload)
        self.assertNotIn("quizSubmission", serialized_payload)
        self.assertNotIn("https://evil.example/callback", serialized_payload)

    def test_callback_200_duplicate_body_still_deletes(self) -> None:
        from app.integration.report_consumer import process_report_request_message

        transport = FakeTransport(
            SpringCallbackResponse(status_code=200, body={"duplicate": True})
        )

        result = process_report_request_message(
            json.dumps(_valid_message()).encode("utf-8"),
            report_service=RecordingReportService(),
            callback_client=_spring_callback_client(transport),
        )

        self.assertTrue(result.callback_ok)
        self.assertTrue(result.delete_message)

    def test_callback_5xx_and_timeout_are_retryable_without_delete(self) -> None:
        from app.integration.report_consumer import process_report_request_message

        cases = [
            (
                FakeTransport(SpringCallbackResponse(status_code=503)),
                "spring callback server error",
            ),
            (
                FakeTransport(error=TimeoutError("fake-secret-key")),
                "spring callback timeout",
            ),
            (
                FakeTransport(error=ConnectionError("fake-secret-key")),
                "spring callback connection error",
            ),
        ]
        for transport, expected_error in cases:
            with self.subTest(expected_error=expected_error):
                result = process_report_request_message(
                    _valid_message(),
                    report_service=RecordingReportService(),
                    callback_client=_spring_callback_client(transport),
                )

                self.assertTrue(result.callback_attempted)
                self.assertFalse(result.callback_ok)
                self.assertFalse(result.delete_message)
                self.assertTrue(result.retryable)
                self.assertFalse(result.poison)
                self.assertEqual(result.error, expected_error)
                self.assertNotIn("fake-secret-key", result.error or "")

    def test_callback_4xx_is_poison_but_not_silent_delete(self) -> None:
        from app.integration.report_consumer import process_report_request_message

        result = process_report_request_message(
            _valid_message(),
            report_service=RecordingReportService(),
            callback_client=_spring_callback_client(
                FakeTransport(SpringCallbackResponse(status_code=400))
            ),
        )

        self.assertTrue(result.callback_attempted)
        self.assertFalse(result.callback_ok)
        self.assertFalse(result.delete_message)
        self.assertFalse(result.retryable)
        self.assertTrue(result.poison)
        self.assertEqual(result.error, "spring callback client error")

    def test_invalid_schema_skips_service_callback_and_delete_without_leaking_body(
        self,
    ) -> None:
        from app.integration.report_consumer import process_report_request_message

        service = RecordingReportService()
        transport = FakeTransport()
        raw_body = json.dumps(
            {
                "requestId": "report-request-1",
                "userId": 0,
                "targetDate": "2026-06-14",
                "characterMetadata": {"characterId": 10},
                "dailyReport": {"content": "DO_NOT_LEAK_REPORT_CONTENT"},
            }
        )

        result = process_report_request_message(
            raw_body,
            report_service=service,
            callback_client=_spring_callback_client(transport),
        )

        self.assertFalse(result.callback_attempted)
        self.assertFalse(result.callback_ok)
        self.assertFalse(result.delete_message)
        self.assertFalse(result.retryable)
        self.assertTrue(result.poison)
        self.assertEqual(result.error, "invalid report request schema")
        self.assertEqual(service.calls, [])
        self.assertEqual(transport.calls, [])
        self.assertNotIn(raw_body, result.error or "")
        self.assertNotIn("DO_NOT_LEAK_REPORT_CONTENT", result.error or "")

    def test_fallback_result_is_sent_and_delete_depends_on_callback_200(self) -> None:
        from app.integration.report_consumer import process_report_request_message

        fallback_service = RecordingReportService(_fallback_report_result())
        ok_result = process_report_request_message(
            _valid_message(),
            report_service=fallback_service,
            callback_client=_spring_callback_client(FakeTransport()),
        )
        failed_result = process_report_request_message(
            _valid_message(),
            report_service=fallback_service,
            callback_client=_spring_callback_client(
                FakeTransport(SpringCallbackResponse(status_code=503))
            ),
        )

        self.assertTrue(ok_result.callback_ok)
        self.assertTrue(ok_result.delete_message)
        self.assertTrue(failed_result.callback_attempted)
        self.assertFalse(failed_result.callback_ok)
        self.assertFalse(failed_result.delete_message)

    def test_service_exception_uses_safe_fallback_callback_without_leaking_exception(
        self,
    ) -> None:
        from app.integration.report_consumer import process_report_request_message

        service = RecordingReportService(error=RuntimeError("fake-secret-key"))
        transport = FakeTransport()

        result = process_report_request_message(
            _valid_message(),
            report_service=service,
            callback_client=_spring_callback_client(transport),
        )

        self.assertTrue(result.callback_attempted)
        self.assertTrue(result.callback_ok)
        self.assertTrue(result.delete_message)
        self.assertEqual(transport.calls[0]["payload"]["status"], "FALLBACK")
        serialized_payload = json.dumps(transport.calls[0]["payload"], ensure_ascii=False)
        self.assertNotIn("fake-secret-key", serialized_payload)
        self.assertNotIn("fake-secret-key", result.error or "")


class ReportConsumerPollingTest(unittest.TestCase):
    def test_polling_deletes_only_when_handler_result_allows_it(self) -> None:
        from app.integration.report_consumer import poll_report_request_queue

        sqs_client = FakeSqsClient(
            [
                {
                    "MessageId": "message-1",
                    "ReceiptHandle": "receipt-1",
                    "Body": json.dumps(_valid_message()),
                    "Attributes": {"ApproximateReceiveCount": "1"},
                }
            ]
        )
        transport = FakeTransport()

        results = poll_report_request_queue(
            sqs_client=sqs_client,
            queue_url="https://sqs.fake/report-requests",
            callback_client=_spring_callback_client(transport),
            report_service=RecordingReportService(),
            max_batches=1,
        )

        self.assertEqual(len(results), 1)
        self.assertEqual(
            sqs_client.receive_calls[0]["QueueUrl"],
            "https://sqs.fake/report-requests",
        )
        self.assertEqual(sqs_client.receive_calls[0]["WaitTimeSeconds"], 20)
        self.assertEqual(sqs_client.receive_calls[0]["MaxNumberOfMessages"], 1)
        self.assertEqual(
            sqs_client.delete_calls,
            [
                {
                    "QueueUrl": "https://sqs.fake/report-requests",
                    "ReceiptHandle": "receipt-1",
                }
            ],
        )
        serialized_payload = json.dumps(transport.calls[0]["payload"], ensure_ascii=False)
        self.assertNotIn("receipt-1", serialized_payload)
        self.assertNotIn("MessageId", serialized_payload)

    def test_polling_does_not_delete_when_callback_fails(self) -> None:
        from app.integration.report_consumer import poll_report_request_queue

        sqs_client = FakeSqsClient(
            [
                {
                    "MessageId": "message-1",
                    "ReceiptHandle": "receipt-1",
                    "Body": json.dumps(_valid_message()),
                }
            ]
        )

        results = poll_report_request_queue(
            sqs_client=sqs_client,
            queue_url="https://sqs.fake/report-requests",
            callback_client=_spring_callback_client(
                FakeTransport(SpringCallbackResponse(status_code=503))
            ),
            report_service=RecordingReportService(),
            max_batches=1,
        )

        self.assertEqual(len(results), 1)
        self.assertFalse(results[0].delete_message)
        self.assertEqual(sqs_client.delete_calls, [])

    def test_empty_receive_response_is_noop(self) -> None:
        from app.integration.report_consumer import poll_report_request_queue

        sqs_client = FakeSqsClient([])

        results = poll_report_request_queue(
            sqs_client=sqs_client,
            queue_url="https://sqs.fake/report-requests",
            callback_client=_spring_callback_client(FakeTransport()),
            report_service=RecordingReportService(),
            max_batches=1,
        )

        self.assertEqual(results, [])
        self.assertEqual(sqs_client.delete_calls, [])

    def test_worker_uses_configured_report_queue_url_and_missing_url_is_safe(
        self,
    ) -> None:
        from app.integration.report_consumer import (
            ReportWorkerConfigError,
            run_report_worker_once,
        )

        sqs_client = FakeSqsClient(
            [
                {
                    "MessageId": "message-1",
                    "ReceiptHandle": "receipt-1",
                    "Body": json.dumps(_valid_message()),
                }
            ]
        )

        run_report_worker_once(
            settings=Settings(
                _env_file=None,
                report_request_queue_url="https://sqs.fake/report-requests",
            ),
            sqs_client=sqs_client,
            callback_client=_spring_callback_client(FakeTransport()),
            report_service=RecordingReportService(),
        )

        self.assertEqual(
            sqs_client.receive_calls[0]["QueueUrl"],
            "https://sqs.fake/report-requests",
        )
        with self.assertRaises(ReportWorkerConfigError) as context:
            run_report_worker_once(
                settings=Settings(
                    _env_file=None,
                    spring_internal_api_secret="fake-secret-key",
                ),
                sqs_client=sqs_client,
                callback_client=_spring_callback_client(FakeTransport()),
                report_service=RecordingReportService(),
            )
        self.assertEqual(str(context.exception), "REPORT_REQUEST_QUEUE_URL is required")
        self.assertNotIn("fake-secret-key", str(context.exception))


class ReportConsumerGuardrailTest(unittest.TestCase):
    def test_fastapi_does_not_define_report_or_character_generation_post_route(
        self,
    ) -> None:
        from app.main import app

        post_routes = {
            route.path
            for route in app.routes
            if "POST" in getattr(route, "methods", set())
        }

        self.assertNotIn("/api/report", post_routes)
        self.assertNotIn("/api/ai/commitgotchi", post_routes)

    def test_report_consumer_does_not_use_quiz_callback_path_or_db_access(self) -> None:
        from app.integration import report_consumer

        source = inspect.getsource(report_consumer)

        self.assertNotIn("spring_quiz_grade_result_path", source)
        self.assertNotIn("post_quiz_grade_result", source)
        self.assertNotIn("send_quiz_grade_result", source)
        self.assertNotIn("from app.db", source)
        self.assertNotIn("sqlalchemy", source)


def _spring_callback_client(transport: FakeTransport) -> SpringCallbackClient:
    return SpringCallbackClient(
        settings=Settings(
            _env_file=None,
            spring_boot_internal_base_url="http://spring.local:8080",
            spring_report_callback_path="/api/report",
            spring_quiz_grade_result_path="/api/internal/quizzes/grade-result",
            spring_internal_api_secret="fake-secret",
        ),
        transport=transport,
    )


def _valid_message(**overrides: Any) -> dict[str, Any]:
    message: dict[str, Any] = {
        "requestId": "report-request-1",
        "userId": 1,
        "targetDate": "2026-06-14",
        "userMetadata": {
            "weeklyStudyStreak": "0100011",
            "reportDirection": {
                "scoreDeltaHint": {
                    "db": 0,
                    "algorithm": 3,
                    "cs": 0,
                    "network": 1,
                    "framework": 0,
                }
            },
        },
        "characterMetadata": {
            "characterId": 10,
            "name": "커밋 몬스터",
            "personality": "칭찬과 교정을 균형 있게 하는 성격",
            "currentStats": {
                "db": 120,
                "algorithm": 200,
                "cs": 80,
                "network": 60,
                "framework": 140,
            },
        },
        "dailyReport": {
            "title": "오늘 학습 기록",
            "content": "짧은 합성 리포트 본문",
        },
    }
    message.update(overrides)
    return message


def _report_result() -> dict[str, Any]:
    return {
        "status": "SUCCESS",
        "scoreDelta": {"db": 0, "algorithm": 3, "cs": 0, "network": 1, "framework": 0},
        "emotion": "JOY",
        "statusMessage": "좋은 학습 흐름이에요.",
        "dailyReport": {
            "text": "합성 분석 결과",
            "feedback": "원인과 해결책을 함께 정리했습니다.",
        },
        "nextRecommendation": {
            "topics": ["fetch join"],
            "rationale": "N+1 해결책으로 확장하세요.",
        },
        "recommendedQuizzes": [
            {
                "problemId": 101,
                "question": "N+1 문제란?",
                "modelAnswer": "연관 데이터를 반복 조회하는 문제입니다.",
                "scoreAllocation": {"db": 5},
                "submissionId": "sub-should-be-filtered",
            }
        ],
    }


def _fallback_report_result() -> dict[str, Any]:
    result = _report_result()
    result["status"] = "FALLBACK"
    result["scoreDelta"] = {
        "db": 0,
        "algorithm": 0,
        "cs": 0,
        "network": 0,
        "framework": 0,
    }
    result["recommendedQuizzes"] = []
    return result


if __name__ == "__main__":
    unittest.main()
