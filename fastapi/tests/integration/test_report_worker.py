from __future__ import annotations

import io
import unittest
from contextlib import redirect_stderr
from typing import Any
from unittest.mock import patch

from app.config import Settings


class FakeSqsClient:
    pass


class FakeCallbackClient:
    pass


class FakeReportService:
    def __call__(self, **kwargs: Any) -> dict[str, Any]:
        return {"status": "SUCCESS"}


class ReportWorkerEntrypointTest(unittest.TestCase):
    def test_main_calls_report_worker_loop_with_injected_dependencies(self) -> None:
        from app.integration import report_worker

        settings = Settings(
            _env_file=None,
            report_request_queue_url="https://sqs.fake/report-requests",
        )
        sqs_client = FakeSqsClient()
        callback_client = FakeCallbackClient()
        report_service = FakeReportService()
        calls: list[dict[str, Any]] = []

        def fake_run_report_worker(**kwargs: Any) -> None:
            calls.append(dict(kwargs))

        with patch.object(
            report_worker,
            "run_report_worker",
            side_effect=fake_run_report_worker,
        ):
            exit_code = report_worker.main(
                settings=settings,
                sqs_client=sqs_client,
                callback_client=callback_client,
                report_service=report_service,
            )

        self.assertEqual(exit_code, 0)
        self.assertEqual(len(calls), 1)
        self.assertIs(calls[0]["settings"], settings)
        self.assertIs(calls[0]["sqs_client"], sqs_client)
        self.assertIs(calls[0]["callback_client"], callback_client)
        self.assertIs(calls[0]["report_service"], report_service)

    def test_main_returns_safe_config_error_when_queue_url_is_missing(self) -> None:
        from app.integration import report_worker

        stderr = io.StringIO()

        with redirect_stderr(stderr):
            exit_code = report_worker.main(
                settings=Settings(
                    _env_file=None,
                    spring_internal_api_secret="fake-secret-key",
                ),
                sqs_client=FakeSqsClient(),
                callback_client=FakeCallbackClient(),
            )

        error_output = stderr.getvalue()
        self.assertEqual(exit_code, 2)
        self.assertIn("report worker configuration error", error_output)
        self.assertIn("REPORT_REQUEST_QUEUE_URL is required", error_output)
        self.assertNotIn("fake-secret-key", error_output)


if __name__ == "__main__":
    unittest.main()
