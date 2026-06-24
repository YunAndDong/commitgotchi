from __future__ import annotations

import logging
import os
import sys
from typing import Any

from app.config import Settings

from .report_consumer import ReportService, ReportWorkerConfigError, run_report_worker
from .spring_client import SpringCallbackClient


def _configure_logging() -> None:
    """Send worker INFO logs to stdout so `docker compose logs` shows SQS
    receive / report generation / Spring callback activity. Without this the
    long-polling worker is silent and hard to operate."""
    level = os.getenv("REPORT_WORKER_LOG_LEVEL", "INFO").upper()
    logging.basicConfig(
        level=level,
        stream=sys.stdout,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )


def main(
    *,
    settings: Settings | None = None,
    sqs_client: Any | None = None,
    callback_client: SpringCallbackClient | None = None,
    report_service: ReportService | None = None,
) -> int:
    _configure_logging()
    logging.getLogger(__name__).info("report worker starting (SQS consumer loop)")

    worker_kwargs: dict[str, Any] = {
        "settings": settings,
        "sqs_client": sqs_client,
        "callback_client": callback_client,
    }
    if report_service is not None:
        worker_kwargs["report_service"] = report_service

    try:
        run_report_worker(**worker_kwargs)
    except ReportWorkerConfigError as exc:
        print(f"report worker configuration error: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
