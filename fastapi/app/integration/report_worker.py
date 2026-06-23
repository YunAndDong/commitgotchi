from __future__ import annotations

import sys
from typing import Any

from app.config import Settings

from .report_consumer import ReportService, ReportWorkerConfigError, run_report_worker
from .spring_client import SpringCallbackClient


def main(
    *,
    settings: Settings | None = None,
    sqs_client: Any | None = None,
    callback_client: SpringCallbackClient | None = None,
    report_service: ReportService | None = None,
) -> int:
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
