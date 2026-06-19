from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any, Callable, Mapping

from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator

from app.config import Settings, settings as default_settings
from app.scoring.daily_report_service import generate_daily_report_result
from app.scoring.policy import zero_score_vector

from .schemas import SpringCallbackResult
from .spring_client import SpringCallbackClient


ReportService = Callable[..., Mapping[str, Any]]

DEFAULT_WAIT_TIME_SECONDS = 20
DEFAULT_MAX_NUMBER_OF_MESSAGES = 1
INVALID_SCHEMA_ERROR = "invalid report request schema"
CALLBACK_FAILED_ERROR = "spring callback failed"
MISSING_QUEUE_URL_ERROR = "REPORT_REQUEST_QUEUE_URL is required"
REPORT_EMOTIONS = frozenset(("JOY", "ANGRY", "SAD"))


@dataclass(frozen=True)
class ReportMessageProcessResult:
    callback_attempted: bool
    callback_ok: bool
    delete_message: bool
    retryable: bool
    poison: bool = False
    request_id: str | None = None
    error: str | None = None


class ReportWorkerConfigError(RuntimeError):
    pass


class _DailyReportMessage(BaseModel):
    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    title: str | None = None
    content: str

    @field_validator("title", mode="before")
    @classmethod
    def _optional_text(cls, value: Any) -> str | None:
        if value is None:
            return None
        text = str(value).strip()
        return text or None

    @field_validator("content", mode="before")
    @classmethod
    def _required_content(cls, value: Any) -> str:
        return _required_text(value)


class _CharacterMetadataMessage(BaseModel):
    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    character_id: int = Field(alias="characterId")
    name: str | None = None
    personality: str | None = None
    emotion: str | None = None
    current_stats: Mapping[str, Any] | None = Field(default=None, alias="currentStats")

    @field_validator("character_id", mode="before")
    @classmethod
    def _positive_character_id(cls, value: Any) -> int:
        return _positive_int(value)

    @field_validator("name", "personality", mode="before")
    @classmethod
    def _optional_text(cls, value: Any) -> str | None:
        if value is None:
            return None
        text = str(value).strip()
        return text or None

    @field_validator("emotion", mode="before")
    @classmethod
    def _optional_emotion(cls, value: Any) -> str | None:
        if value is None:
            return None
        emotion = str(value).strip()
        if emotion not in REPORT_EMOTIONS:
            raise ValueError("emotion must be one of JOY, ANGRY, SAD")
        return emotion


class _ReportRequestMessage(BaseModel):
    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    request_id: str = Field(alias="requestId")
    user_id: int = Field(alias="userId")
    target_date: str = Field(alias="targetDate")
    user_metadata: Mapping[str, Any] | None = Field(default=None, alias="userMetadata")
    character_metadata: _CharacterMetadataMessage = Field(alias="characterMetadata")
    daily_report: _DailyReportMessage = Field(alias="dailyReport")

    @field_validator("request_id", "target_date", mode="before")
    @classmethod
    def _required_text_field(cls, value: Any) -> str:
        return _required_text(value)

    @field_validator("user_id", mode="before")
    @classmethod
    def _positive_user_id(cls, value: Any) -> int:
        return _positive_int(value)


def process_report_request_message(
    message_body: Mapping[str, Any] | str | bytes,
    *,
    report_service: ReportService = generate_daily_report_result,
    callback_client: SpringCallbackClient,
) -> ReportMessageProcessResult:
    request = _parse_report_request(message_body)
    if request is None:
        return ReportMessageProcessResult(
            callback_attempted=False,
            callback_ok=False,
            delete_message=False,
            retryable=False,
            poison=True,
            error=INVALID_SCHEMA_ERROR,
        )

    try:
        report_result = report_service(
            report_title=request.daily_report.title,
            report_content=request.daily_report.content,
            user_metadata=(
                dict(request.user_metadata) if request.user_metadata is not None else None
            ),
            character_personality=request.character_metadata.personality,
            character_metadata=_character_metadata_for_service(
                request.character_metadata
            ),
        )
    except Exception:
        report_result = _safe_fallback_report_result()

    try:
        callback_result = callback_client.send_report_callback(
            request_id=request.request_id,
            user_id=request.user_id,
            character_id=request.character_metadata.character_id,
            target_date=request.target_date,
            report_result=report_result,
        )
    except TimeoutError:
        callback_result = SpringCallbackResult(
            status_code=None,
            ok=False,
            retryable=True,
            error="spring callback timeout",
        )
    except (ConnectionError, OSError):
        callback_result = SpringCallbackResult(
            status_code=None,
            ok=False,
            retryable=True,
            error="spring callback connection error",
        )
    except Exception:
        callback_result = SpringCallbackResult(
            status_code=None,
            ok=False,
            retryable=True,
            error=CALLBACK_FAILED_ERROR,
        )

    return _process_result_from_callback(
        callback_result,
        request_id=request.request_id,
    )


def poll_report_request_queue(
    *,
    sqs_client: Any,
    queue_url: str,
    callback_client: SpringCallbackClient,
    report_service: ReportService = generate_daily_report_result,
    wait_time_seconds: int = DEFAULT_WAIT_TIME_SECONDS,
    max_number_of_messages: int = DEFAULT_MAX_NUMBER_OF_MESSAGES,
    max_batches: int = 1,
) -> list[ReportMessageProcessResult]:
    queue_url = _required_queue_url(queue_url)
    if max_batches <= 0:
        return []

    results: list[ReportMessageProcessResult] = []
    for _ in range(max_batches):
        response = sqs_client.receive_message(
            QueueUrl=queue_url,
            WaitTimeSeconds=wait_time_seconds,
            MaxNumberOfMessages=max_number_of_messages,
        )
        for message in response.get("Messages", []):
            body = message.get("Body", "")
            result = process_report_request_message(
                body,
                report_service=report_service,
                callback_client=callback_client,
            )
            results.append(result)
            receipt_handle = message.get("ReceiptHandle")
            if result.delete_message and receipt_handle:
                sqs_client.delete_message(
                    QueueUrl=queue_url,
                    ReceiptHandle=receipt_handle,
                )
    return results


def run_report_worker_once(
    *,
    settings: Settings | None = None,
    sqs_client: Any | None = None,
    callback_client: SpringCallbackClient | None = None,
    report_service: ReportService = generate_daily_report_result,
) -> list[ReportMessageProcessResult]:
    resolved_settings = settings or default_settings
    queue_url = _required_queue_url(resolved_settings.report_request_queue_url)
    resolved_sqs_client = sqs_client or create_report_sqs_client(
        settings=resolved_settings
    )
    resolved_callback_client = callback_client or SpringCallbackClient(
        settings=resolved_settings
    )
    return poll_report_request_queue(
        sqs_client=resolved_sqs_client,
        queue_url=queue_url,
        callback_client=resolved_callback_client,
        report_service=report_service,
        max_batches=1,
    )


def run_report_worker(
    *,
    settings: Settings | None = None,
    sqs_client: Any | None = None,
    callback_client: SpringCallbackClient | None = None,
    report_service: ReportService = generate_daily_report_result,
) -> None:
    resolved_settings = settings or default_settings
    queue_url = _required_queue_url(resolved_settings.report_request_queue_url)
    resolved_sqs_client = sqs_client or create_report_sqs_client(
        settings=resolved_settings
    )
    resolved_callback_client = callback_client or SpringCallbackClient(
        settings=resolved_settings
    )
    while True:
        poll_report_request_queue(
            sqs_client=resolved_sqs_client,
            queue_url=queue_url,
            callback_client=resolved_callback_client,
            report_service=report_service,
            max_batches=1,
        )


def create_report_sqs_client(*, settings: Settings | None = None) -> Any:
    resolved_settings = settings or default_settings

    import boto3

    kwargs: dict[str, Any] = {
        "region_name": resolved_settings.aws_region,
    }
    if resolved_settings.aws_sqs_endpoint:
        kwargs["endpoint_url"] = resolved_settings.aws_sqs_endpoint
    if resolved_settings.aws_access_key_id:
        kwargs["aws_access_key_id"] = resolved_settings.aws_access_key_id
    if resolved_settings.aws_secret_access_key is not None:
        kwargs["aws_secret_access_key"] = (
            resolved_settings.aws_secret_access_key.get_secret_value()
        )
    return boto3.client("sqs", **kwargs)


def _parse_report_request(
    message_body: Mapping[str, Any] | str | bytes,
) -> _ReportRequestMessage | None:
    try:
        payload = _message_body_to_mapping(message_body)
        return _ReportRequestMessage.model_validate(payload)
    except (TypeError, ValueError, UnicodeDecodeError, ValidationError):
        return None


def _message_body_to_mapping(
    message_body: Mapping[str, Any] | str | bytes,
) -> Mapping[str, Any]:
    if isinstance(message_body, Mapping):
        return dict(message_body)
    if isinstance(message_body, bytes):
        message_body = message_body.decode("utf-8")
    if isinstance(message_body, str):
        payload = json.loads(message_body)
        if not isinstance(payload, Mapping):
            raise TypeError("SQS Body must decode to a JSON object")
        return payload
    raise TypeError("SQS Body must be a mapping, string, or bytes")


def _character_metadata_for_service(
    character_metadata: _CharacterMetadataMessage,
) -> dict[str, Any]:
    return character_metadata.model_dump(by_alias=True, exclude_none=True)


def _process_result_from_callback(
    callback_result: SpringCallbackResult,
    *,
    request_id: str,
) -> ReportMessageProcessResult:
    callback_ok = callback_result.ok
    retryable = False if callback_ok else callback_result.retryable
    return ReportMessageProcessResult(
        callback_attempted=True,
        callback_ok=callback_ok,
        delete_message=callback_ok,
        retryable=retryable,
        poison=(not callback_ok and not retryable),
        request_id=request_id,
        error=_safe_callback_error(callback_result),
    )


def _safe_callback_error(callback_result: SpringCallbackResult) -> str | None:
    if callback_result.ok:
        return None
    status_code = callback_result.status_code
    if status_code is None:
        if callback_result.error == "spring callback timeout":
            return "spring callback timeout"
        if callback_result.error == "spring callback connection error":
            return "spring callback connection error"
        return CALLBACK_FAILED_ERROR
    if status_code >= 500:
        return "spring callback server error"
    if 400 <= status_code < 500:
        return "spring callback client error"
    return "spring callback contract mismatch"


def _safe_fallback_report_result() -> dict[str, Any]:
    return {
        "status": "FALLBACK",
        "scoreDelta": zero_score_vector(),
        "statusMessage": "리포트 분석을 완료하지 못했지만 안전한 fallback 결과를 전송합니다.",
        "dailyReport": {
            "text": "",
            "feedback": "리포트 분석을 완료하지 못했습니다.",
        },
        "nextRecommendation": {
            "topics": [],
            "rationale": "분석 fallback으로 추천을 생성하지 않았습니다.",
        },
        "recommendedQuizzes": [],
    }


def _required_text(value: Any) -> str:
    if value is None:
        raise ValueError("required text is missing")
    text = str(value).strip()
    if not text:
        raise ValueError("required text is missing")
    return text


def _positive_int(value: Any) -> int:
    if isinstance(value, bool):
        raise ValueError("positive integer is required")
    try:
        number = int(value)
    except (TypeError, ValueError) as exc:
        raise ValueError("positive integer is required") from exc
    if number <= 0:
        raise ValueError("positive integer is required")
    return number


def _required_queue_url(value: str | None) -> str:
    queue_url = str(value).strip() if value is not None else ""
    if not queue_url:
        raise ReportWorkerConfigError(MISSING_QUEUE_URL_ERROR)
    return queue_url
