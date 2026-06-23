from __future__ import annotations

import json
import os
import uuid
from pathlib import Path
from typing import Any, Mapping, Protocol

from .policy import (
    SCORE_FIELDS,
    clamp_confidence,
    clamp_score_delta,
    sanitize_score_allocation,
    zero_score_vector,
)
from .schemas import QuizGradingResult


DEFAULT_GEMINI_QUIZ_GRADER_MODEL = "gemini-3.1-flash-lite"
FALLBACK_GEMINI_QUIZ_GRADER_MODEL = "gemini-2.5-flash-lite"
DEFAULT_UNGRADED_FEEDBACK = (
    "채점 AI가 잠깐 시무룩해져서 이번 답안은 점수를 매기지 못했어요. "
    "API 키와 모델 설정을 살짝 확인해 주세요."
)
EMPTY_ANSWER_FEEDBACK = "답안이 비어 있어서 점수는 살짝 아껴둘게요. 다음엔 한 줄이라도 꼭 적어줘요."
FASTAPI_ROOT = Path(__file__).resolve().parents[2]


class GradingModelClient(Protocol):
    def grade(self, prompt: str) -> Mapping[str, Any] | str:
        """Return a structured quiz grading payload or a JSON string."""


class GeminiQuizGraderSettings:
    def __init__(
        self,
        *,
        gemini_api_key: str | None,
        gemini_quiz_grader_model: str,
    ) -> None:
        self.gemini_api_key = gemini_api_key
        self.gemini_quiz_grader_model = gemini_quiz_grader_model

    @classmethod
    def from_env(cls) -> "GeminiQuizGraderSettings":
        dotenv = _read_dotenv(FASTAPI_ROOT / ".env")
        api_key = os.getenv("GEMINI_API_KEY") or dotenv.get("GEMINI_API_KEY")
        model = (
            os.getenv("GEMINI_QUIZ_GRADER_MODEL")
            or dotenv.get("GEMINI_QUIZ_GRADER_MODEL")
            or DEFAULT_GEMINI_QUIZ_GRADER_MODEL
        )
        return cls(
            gemini_api_key=api_key,
            gemini_quiz_grader_model=model,
        )


class GeminiGradingModelClient:
    def __init__(self, *, api_key: str, model: str) -> None:
        if not api_key:
            raise ValueError("GEMINI_API_KEY is required")
        self.api_key = api_key
        self.model = model or DEFAULT_GEMINI_QUIZ_GRADER_MODEL
        self._client: Any | None = None

    @classmethod
    def from_env(cls) -> "GeminiGradingModelClient":
        settings = GeminiQuizGraderSettings.from_env()
        api_key = settings.gemini_api_key
        model = settings.gemini_quiz_grader_model
        return cls(api_key=api_key or "", model=model)

    def grade(self, prompt: str) -> Mapping[str, Any] | str:
        try:
            from google import genai
            from google.genai import types
        except ImportError as exc:
            raise RuntimeError(
                "google-genai package is required for Gemini grading"
            ) from exc

        if self._client is None:
            self._client = genai.Client(api_key=self.api_key)

        response = self._client.models.generate_content(
            model=self.model,
            contents=prompt,
            config=types.GenerateContentConfig(
                response_mime_type="application/json",
                temperature=0.0,
            ),
        )
        return response.text or ""


def grade_quiz_answer(
    *,
    submission_id: str | None = None,
    submissionId: str | None = None,
    problem_id: int | str | None = None,
    problemId: int | str | None = None,
    question: str,
    model_answer: str | None = None,
    modelAnswer: str | None = None,
    user_answer: str | None = None,
    userAnswer: str | None = None,
    score_allocation: Mapping[str, Any] | None = None,
    scoreAllocation: Mapping[str, Any] | None = None,
    difficulty: str | None = None,
    source_path: str | None = None,
    sourcePath: str | None = None,
    rubric: Mapping[str, Any] | None = None,
    client: GradingModelClient | None = None,
) -> dict[str, Any]:
    """Grade a quiz answer with an injectable model client.

    The returned dict intentionally follows the Spring-facing camelCase
    contract while accepting both Pythonic and Spring-style keyword names.
    """

    submission = _normalize_submission_id(submission_id or submissionId)
    allocation = sanitize_score_allocation(score_allocation or scoreAllocation)
    answer = _clean_text(user_answer if user_answer is not None else userAnswer)

    if not answer:
        return _result(
            submission_id=submission,
            status="GRADED",
            score_allocation=allocation,
            score_delta=zero_score_vector(),
            feedback=EMPTY_ANSWER_FEEDBACK,
            confidence=0.0,
        )

    try:
        prompt = build_quiz_grading_prompt(
            submission_id=submission,
            problem_id=problem_id if problem_id is not None else problemId,
            question=question,
            model_answer=model_answer if model_answer is not None else modelAnswer,
            user_answer=answer,
            score_allocation=allocation,
            difficulty=difficulty,
            source_path=source_path if source_path is not None else sourcePath,
            rubric=rubric,
        )
        model_client = client if client is not None else GeminiGradingModelClient.from_env()
        payload = _parse_model_response(model_client.grade(prompt))
        raw_delta = _mapping_or_none(payload.get("scoreDelta")) or _mapping_or_none(
            payload.get("score_delta")
        )
        if raw_delta is None:
            raise ValueError("model response must include scoreDelta object")
        score_delta = clamp_score_delta(raw_delta, allocation)
        feedback = _normalize_feedback(payload.get("feedback"))
        confidence = clamp_confidence(payload.get("confidence"))
    except Exception:
        return _result(
            submission_id=submission,
            status="UNGRADED",
            score_allocation=allocation,
            score_delta=zero_score_vector(),
            feedback=DEFAULT_UNGRADED_FEEDBACK,
            confidence=0.0,
        )

    return _result(
        submission_id=submission,
        status="GRADED",
        score_allocation=allocation,
        score_delta=score_delta,
        feedback=feedback,
        confidence=confidence,
    )


def build_quiz_grading_prompt(
    *,
    submission_id: str,
    problem_id: int | str | None,
    question: str,
    model_answer: str | None,
    user_answer: str,
    score_allocation: Mapping[str, Any],
    difficulty: str | None = None,
    source_path: str | None = None,
    rubric: Mapping[str, Any] | None = None,
) -> str:
    template = _load_prompt_template()
    prompt_vars = {
        "submission_id": submission_id,
        "problem_id": _clean_text(problem_id) or "unknown",
        "difficulty": _clean_text(difficulty) or "unknown",
        "source_path": _clean_text(source_path) or "request-payload",
        "question": _clean_text(question),
        "model_answer": _clean_text(model_answer),
        "user_answer": user_answer,
        "score_allocation": json.dumps(
            sanitize_score_allocation(score_allocation),
            ensure_ascii=False,
            sort_keys=False,
        ),
        "score_fields": ", ".join(SCORE_FIELDS),
        "rubric": _format_rubric(rubric),
    }
    return template.format(**prompt_vars)


def _result(
    *,
    submission_id: str,
    status: str,
    score_allocation: Mapping[str, int],
    score_delta: Mapping[str, int],
    feedback: str,
    confidence: float,
) -> dict[str, Any]:
    return QuizGradingResult(
        submission_id=submission_id,
        status=status,  # type: ignore[arg-type]
        score_allocation=dict(score_allocation),
        score_delta=dict(score_delta),
        feedback=feedback,
        confidence=confidence,
    ).to_dict()


def _parse_model_response(response: Mapping[str, Any] | str) -> Mapping[str, Any]:
    if isinstance(response, Mapping):
        return response
    if not isinstance(response, str):
        raise ValueError("model response must be a mapping or JSON string")

    payload_text = _extract_json_object(response)
    payload = json.loads(payload_text)
    if not isinstance(payload, Mapping):
        raise ValueError("model response JSON must be an object")
    return payload


def _extract_json_object(value: str) -> str:
    stripped = value.strip()
    if stripped.startswith("```"):
        lines = stripped.splitlines()
        if lines and lines[0].startswith("```"):
            lines = lines[1:]
        if lines and lines[-1].strip().startswith("```"):
            lines = lines[:-1]
        stripped = "\n".join(lines).strip()

    start = stripped.find("{")
    end = stripped.rfind("}")
    if start == -1 or end == -1 or end < start:
        raise ValueError("model response did not contain a JSON object")
    return stripped[start : end + 1]


def _mapping_or_none(value: Any) -> Mapping[str, Any] | None:
    return value if isinstance(value, Mapping) else None


def _normalize_submission_id(submission_id: str | None) -> str:
    if submission_id is None or not str(submission_id).strip():
        return str(uuid.uuid4())
    return str(submission_id).strip()


def _normalize_feedback(value: Any) -> str:
    feedback = _clean_text(value)
    if not feedback:
        return "채점은 끝났는데 상세 피드백이 쏙 비었어요. 그래도 점수 결과는 저장해둘게요."
    return feedback


def _clean_text(value: Any) -> str:
    if value is None:
        return ""
    return str(value).strip()


def _format_rubric(rubric: Mapping[str, Any] | None) -> str:
    if not rubric:
        return "- 요청에 별도 rubric이 없으므로 모범답안과 공통 채점 기준을 사용합니다."
    return json.dumps(dict(rubric), ensure_ascii=False, sort_keys=True, indent=2)


def _load_prompt_template() -> str:
    template_path = Path(__file__).resolve().parent / "prompts" / "quiz_grading.md"
    return template_path.read_text(encoding="utf-8")


def _read_dotenv(path: Path) -> dict[str, str]:
    if not path.exists() or not path.is_file():
        return {}

    values: dict[str, str] = {}
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError:
        return {}

    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        key = key.strip()
        if not key:
            continue
        values[key] = _strip_env_value(value.strip())
    return values


def _strip_env_value(value: str) -> str:
    if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
        return value[1:-1]
    return value
