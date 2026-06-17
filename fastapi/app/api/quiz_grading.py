from __future__ import annotations

import hmac
import logging
from typing import Any, Callable, Mapping, Protocol
from urllib.parse import urlsplit

from fastapi import APIRouter, BackgroundTasks, Depends, Header, HTTPException, status
from pydantic import BaseModel, ConfigDict, Field, field_validator
from pydantic import SecretStr

from app.config import Settings, settings as default_settings
from app.integration.schemas import SpringCallbackResult
from app.integration.spring_client import (
    INTERNAL_AUTH_SCHEME,
    SpringCallbackClient,
    build_spring_callback_url,
)
from app.scoring.policy import sanitize_score_allocation, zero_score_vector
from app.scoring.quiz_grader import grade_quiz_answer


logger = logging.getLogger(__name__)

SAFE_GRADER_ERROR = "GRADER_ERROR"
SAFE_GRADER_UNGRADED = "GRADER_UNGRADED"
CALLBACK_RETRYABLE_FAILURE = "CALLBACK_RETRYABLE_FAILURE"
CALLBACK_NON_RETRYABLE_FAILURE = "CALLBACK_NON_RETRYABLE_FAILURE"
CALLBACK_DISPATCH_ERROR = "CALLBACK_DISPATCH_ERROR"


QuizGrader = Callable[..., Mapping[str, Any]]


class QuizGradeResultSender(Protocol):
    def send_quiz_grade_result(
        self,
        *,
        user_id: int,
        quiz_id: int,
        grading_result: Mapping[str, Any],
        emotion: str | None = None,
        status_message: str | None = None,
        failed_reason: str | None = None,
    ) -> SpringCallbackResult:
        ...


class QuizGradingRequest(BaseModel):
    submissionId: str = Field(min_length=1)
    userId: int = Field(gt=0)
    characterId: int = Field(gt=0)
    quizId: int = Field(gt=0)
    problemId: int | str | None = None
    question: str = Field(min_length=1)
    modelAnswer: str = Field(min_length=1)
    userAnswer: str
    scoreAllocation: dict[str, int]
    characterMetadata: Mapping[str, Any] | None = None
    callbackUrl: str | None = None
    difficulty: str | None = None
    sourcePath: str | None = None
    rubric: Mapping[str, Any] | None = None

    model_config = ConfigDict(extra="ignore")

    @field_validator("submissionId", "question", "modelAnswer")
    @classmethod
    def _required_text(cls, value: str) -> str:
        stripped = str(value).strip()
        if not stripped:
            raise ValueError("field must not be blank")
        return stripped

    @field_validator("scoreAllocation", mode="before")
    @classmethod
    def _sanitize_score_allocation(cls, value: Any) -> dict[str, int]:
        if not isinstance(value, Mapping):
            raise ValueError("scoreAllocation must be an object")
        return sanitize_score_allocation(value)

    def to_grader_kwargs(self) -> dict[str, Any]:
        return {
            "submissionId": self.submissionId,
            "problemId": self.problemId,
            "question": self.question,
            "modelAnswer": self.modelAnswer,
            "userAnswer": self.userAnswer,
            "scoreAllocation": dict(self.scoreAllocation),
            "difficulty": self.difficulty,
            "sourcePath": self.sourcePath,
            "rubric": dict(self.rubric) if self.rubric is not None else None,
        }


class QuizGradingAccepted(BaseModel):
    accepted: bool = True
    submissionId: str


router = APIRouter(prefix="/api/internal/quizzes", tags=["quiz-grading"])


def get_settings() -> Settings:
    return default_settings


def get_quiz_grader() -> QuizGrader:
    return grade_quiz_answer


def get_callback_client(
    settings: Settings = Depends(get_settings),
) -> SpringCallbackClient:
    return SpringCallbackClient(settings=settings)


def require_internal_auth(
    authorization: str | None = Header(default=None, alias="Authorization"),
    settings: Settings = Depends(get_settings),
) -> None:
    expected_token = _secret_value(settings.spring_internal_api_secret)
    if not expected_token:
        return

    received_token = _extract_internal_token(authorization)
    if received_token is None or not hmac.compare_digest(
        received_token,
        expected_token,
    ):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid internal authorization",
        )


@router.post(
    "/grade",
    response_model=QuizGradingAccepted,
    status_code=status.HTTP_202_ACCEPTED,
)
def grade_quiz(
    request: QuizGradingRequest,
    background_tasks: BackgroundTasks,
    _: None = Depends(require_internal_auth),
    grader: QuizGrader = Depends(get_quiz_grader),
    callback_client: QuizGradeResultSender = Depends(get_callback_client),
    settings: Settings = Depends(get_settings),
) -> QuizGradingAccepted:
    return accept_quiz_grading_request(
        request,
        background_tasks=background_tasks,
        grader=grader,
        callback_client=callback_client,
        settings=settings,
    )


def accept_quiz_grading_request(
    request: QuizGradingRequest,
    *,
    background_tasks: BackgroundTasks,
    grader: QuizGrader = grade_quiz_answer,
    callback_client: QuizGradeResultSender | None = None,
    settings: Settings | None = None,
) -> QuizGradingAccepted:
    safe_settings = settings or default_settings
    sender = callback_client or SpringCallbackClient(settings=safe_settings)
    _record_callback_url_policy_signal(request, settings=safe_settings)
    background_tasks.add_task(
        process_quiz_grading_request,
        request,
        grader=grader,
        callback_client=sender,
    )
    return QuizGradingAccepted(submissionId=request.submissionId)


def process_quiz_grading_request(
    request: QuizGradingRequest,
    *,
    grader: QuizGrader,
    callback_client: QuizGradeResultSender,
) -> SpringCallbackResult:
    failed_reason: str | None = None
    try:
        grading_result = _validated_grading_result(
            request,
            grader(**request.to_grader_kwargs()),
        )
        if _status_value(grading_result.get("status")) != "GRADED":
            failed_reason = SAFE_GRADER_UNGRADED
    except Exception:
        grading_result = _ungraded_grading_result(request)
        failed_reason = SAFE_GRADER_ERROR

    try:
        callback_result = callback_client.send_quiz_grade_result(
            user_id=request.userId,
            quiz_id=request.quizId,
            grading_result=grading_result,
            failed_reason=failed_reason,
        )
    except Exception:
        callback_result = SpringCallbackResult(
            status_code=None,
            ok=False,
            retryable=True,
            error="spring callback dispatch error",
        )
        _log_callback_failure(
            submission_id=request.submissionId,
            result=callback_result,
            category=CALLBACK_DISPATCH_ERROR,
        )
        return callback_result

    if not callback_result.ok:
        _log_callback_failure(
            submission_id=request.submissionId,
            result=callback_result,
            category=(
                CALLBACK_RETRYABLE_FAILURE
                if callback_result.retryable
                else CALLBACK_NON_RETRYABLE_FAILURE
            ),
        )
    return callback_result


def _validated_grading_result(
    request: QuizGradingRequest,
    grading_result: Mapping[str, Any],
) -> dict[str, Any]:
    if not isinstance(grading_result, Mapping):
        raise ValueError("grader result must be a mapping")
    result = dict(grading_result)
    if not str(result.get("submissionId") or "").strip():
        raise ValueError("grader result must include submissionId")
    result["scoreAllocation"] = sanitize_score_allocation(
        result.get("scoreAllocation")
        if isinstance(result.get("scoreAllocation"), Mapping)
        else request.scoreAllocation
    )
    return result


def _ungraded_grading_result(request: QuizGradingRequest) -> dict[str, Any]:
    return {
        "submissionId": request.submissionId,
        "status": "UNGRADED",
        "scoreAllocation": dict(request.scoreAllocation),
        "scoreDelta": zero_score_vector(),
        "feedback": "",
        "confidence": 0.0,
    }


def _record_callback_url_policy_signal(
    request: QuizGradingRequest,
    *,
    settings: Settings,
) -> None:
    category = _callback_url_mismatch_category(request.callbackUrl, settings=settings)
    if category is None:
        return
    logger.warning(
        "quiz_grading_callback_url_mismatch submissionId=%s category=%s",
        request.submissionId,
        category,
    )


def _callback_url_mismatch_category(
    callback_url: str | None,
    *,
    settings: Settings,
) -> str | None:
    if not callback_url:
        return None

    requested = urlsplit(callback_url.strip())
    if requested.scheme not in {"http", "https"} or not requested.netloc:
        return "invalid_callback_url"

    configured_url = build_spring_callback_url(
        base_url=settings.spring_boot_internal_base_url,
        path=settings.spring_quiz_grade_result_path,
    )
    configured = urlsplit(configured_url)
    if requested.scheme != configured.scheme:
        return "scheme_mismatch"
    if requested.netloc != configured.netloc:
        return "host_mismatch"
    requested_path = "/" + "/".join(
        segment for segment in requested.path.split("/") if segment
    )
    configured_path = "/" + "/".join(
        segment for segment in configured.path.split("/") if segment
    )
    if requested_path != configured_path:
        return "path_mismatch"
    return None


def _log_callback_failure(
    *,
    submission_id: str,
    result: SpringCallbackResult,
    category: str,
) -> None:
    logger.warning(
        "quiz_grading_callback_failed submissionId=%s status_code=%s retryable=%s category=%s",
        submission_id,
        result.status_code,
        result.retryable,
        category,
    )


def _extract_internal_token(authorization: str | None) -> str | None:
    if not authorization:
        return None
    scheme, separator, token = authorization.partition(" ")
    if not separator or scheme != INTERNAL_AUTH_SCHEME:
        return None
    stripped = token.strip()
    return stripped or None


def _secret_value(secret: SecretStr | str | None) -> str:
    if secret is None:
        return ""
    if isinstance(secret, SecretStr):
        return secret.get_secret_value().strip()
    return str(secret).strip()


def _status_value(value: Any) -> str:
    return str(value or "").strip().upper()
