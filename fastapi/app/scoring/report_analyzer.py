from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any, Mapping, Protocol

from app.rag.concept_search import build_report_evidence_bundles
from app.rag.concept_store import load_concept_catalog_store
from app.rag.embedding_store import load_concept_embedding_store
from app.rag.schemas import ReportEvidenceBundle

from .policy import (
    LOW_CONFIDENCE_FALLBACK_THRESHOLD,
    SCORE_FIELDS,
    apply_report_confidence_policy,
    clamp_confidence,
    zero_score_vector,
)
from .report_chunker import chunk_daily_report
from .schemas import (
    DailyReportAnalysis,
    EmotionStatus,
    NextRecommendation,
    ReportAnalysis,
    ReportChunk,
)


DEFAULT_GEMINI_REPORT_ANALYZER_MODEL = "gemini-3.1-flash-lite"
FASTAPI_ROOT = Path(__file__).resolve().parents[2]
FALLBACK_STATUS_MESSAGE = (
    "오늘은 리포트를 안정적으로 분석하지 못했어요. "
    "조금 속상하지만, 내일 다시 차근차근 봐줄게요."
)
FALLBACK_FEEDBACK = "분석할 만한 리포트 근거가 아직 부족해서 점수는 살짝 아껴둘게요."
FALLBACK_RECOMMENDATION = "리포트 근거가 더 또렷해지면 다음 학습 주제를 콕 집어 추천할게요."
BASE_TONE_GUIDANCE = (
    "기본 말투: 모든 감정에서 귀엽고 다정한 Commitgotchi 캐릭터 말투를 유지한다. "
    "사무적이거나 딱딱한 보고서체는 피하고, 짧고 생동감 있는 표현으로 말한다. "
    "다만 학습 근거, 부족한 점, 다음 행동은 정확하게 짚는다."
)
EMOTION_TONE_GUIDANCE: dict[str, str] = {
    "JOY": "기쁨: 뿌듯하고 밝은 말투를 쓰되, 귀여운 칭찬과 정확한 피드백을 함께 둔다.",
    "ANGRY": "화남: 무섭게 화내기보다 삐진 듯한 귀여운 투덜거림으로 엄격하게 짚는다. 모욕이나 과도한 비난은 피한다.",
    "SAD": "슬픔: 살짝 시무룩하고 서운한 말투를 쓰되, 다정하게 다시 이어갈 힘을 준다.",
}


class ReportAnalysisModelClient(Protocol):
    def analyze(self, prompt: str) -> Mapping[str, Any] | str:
        """Return a structured report analysis payload or a JSON string."""


class GeminiReportAnalyzerSettings:
    def __init__(
        self,
        *,
        gemini_api_key: str | None,
        gemini_report_analyzer_model: str,
    ) -> None:
        self.gemini_api_key = gemini_api_key
        self.gemini_report_analyzer_model = gemini_report_analyzer_model

    @classmethod
    def from_env(cls) -> "GeminiReportAnalyzerSettings":
        dotenv = _read_dotenv(FASTAPI_ROOT / ".env")
        api_key = os.getenv("GEMINI_API_KEY") or dotenv.get("GEMINI_API_KEY")
        model = (
            os.getenv("GEMINI_REPORT_ANALYZER_MODEL")
            or dotenv.get("GEMINI_REPORT_ANALYZER_MODEL")
            or DEFAULT_GEMINI_REPORT_ANALYZER_MODEL
        )
        return cls(
            gemini_api_key=api_key,
            gemini_report_analyzer_model=model,
        )


class GeminiReportAnalysisModelClient:
    def __init__(self, *, api_key: str, model: str) -> None:
        if not api_key:
            raise ValueError("GEMINI_API_KEY is required")
        self.api_key = api_key
        self.model = model or DEFAULT_GEMINI_REPORT_ANALYZER_MODEL
        self._client: Any | None = None

    @classmethod
    def from_env(cls) -> "GeminiReportAnalysisModelClient":
        settings = GeminiReportAnalyzerSettings.from_env()
        return cls(
            api_key=settings.gemini_api_key or "",
            model=settings.gemini_report_analyzer_model,
        )

    def analyze(self, prompt: str) -> Mapping[str, Any] | str:
        try:
            from google import genai
            from google.genai import types
        except ImportError as exc:
            raise RuntimeError(
                "google-genai package is required for Gemini report analysis"
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


class GeminiReportAnalyzer:
    def __init__(self, client: ReportAnalysisModelClient | None = None) -> None:
        self.client = client

    def analyze(
        self,
        *,
        report_title: str | None,
        report_content: str | None,
        user_metadata: Mapping[str, Any] | None = None,
        character_personality: str | None = None,
        character_metadata: Mapping[str, Any] | None = None,
        report_chunks: list[ReportChunk] | None = None,
        evidence_bundles: list[ReportEvidenceBundle] | list[Mapping[str, Any]] | None = None,
    ) -> ReportAnalysis:
        try:
            chunks = (
                list(report_chunks)
                if report_chunks is not None
                else chunk_daily_report(report_title, report_content)
            )
            if not chunks:
                return fallback_report_analysis()

            bundle_dicts = _resolve_evidence_bundle_dicts(chunks, evidence_bundles)
            personality = _resolve_personality(
                character_personality,
                character_metadata,
            )
            prompt = build_report_analysis_prompt(
                report_title=report_title,
                report_content=report_content,
                report_chunks=chunks,
                evidence_bundles=bundle_dicts,
                user_metadata=user_metadata,
                character_personality=personality,
                character_metadata=character_metadata,
            )
            model_client = self.client or GeminiReportAnalysisModelClient.from_env()
            payload = _parse_model_response(model_client.analyze(prompt))
            return _report_analysis_from_payload(payload)
        except Exception:
            return fallback_report_analysis()


def analyze_daily_report(
    *,
    report_title: str | None,
    report_content: str | None,
    user_metadata: Mapping[str, Any] | None = None,
    character_personality: str | None = None,
    character_metadata: Mapping[str, Any] | None = None,
    report_chunks: list[ReportChunk] | None = None,
    evidence_bundles: list[ReportEvidenceBundle] | list[Mapping[str, Any]] | None = None,
    client: ReportAnalysisModelClient | None = None,
) -> dict[str, Any]:
    analyzer = GeminiReportAnalyzer(client=client)
    return analyzer.analyze(
        report_title=report_title,
        report_content=report_content,
        user_metadata=user_metadata,
        character_personality=character_personality,
        character_metadata=character_metadata,
        report_chunks=report_chunks,
        evidence_bundles=evidence_bundles,
    ).to_dict()


def build_report_analysis_prompt(
    *,
    report_title: str | None,
    report_content: str | None,
    report_chunks: list[ReportChunk],
    evidence_bundles: list[Mapping[str, Any]],
    user_metadata: Mapping[str, Any] | None,
    character_personality: str | None,
    character_metadata: Mapping[str, Any] | None,
) -> str:
    template = _load_prompt_template()
    prompt_vars = {
        "report_title": _clean_text(report_title),
        "report_content": _clean_text(report_content),
        "report_chunks": _json_dumps([chunk.to_dict() for chunk in report_chunks]),
        "evidence_bundles": _json_dumps(evidence_bundles),
        "user_context": _json_dumps(_user_context(user_metadata)),
        "character_context": _json_dumps(
            _character_context(character_personality, character_metadata)
        ),
        "score_fields": ", ".join(SCORE_FIELDS),
        "zero_score_delta": _json_dumps(zero_score_vector()),
    }
    return template.format(**prompt_vars)


def fallback_report_analysis() -> ReportAnalysis:
    return ReportAnalysis(
        status="FALLBACK",
        topics=(),
        field_evidence={field_name: "" for field_name in SCORE_FIELDS},
        score_delta=zero_score_vector(),
        confidence=0.0,
        status_message=FALLBACK_STATUS_MESSAGE,
        daily_report=DailyReportAnalysis(text="", feedback=FALLBACK_FEEDBACK),
        next_recommendation=NextRecommendation(
            topics=(),
            rationale=FALLBACK_RECOMMENDATION,
        ),
    )


def _resolve_evidence_bundle_dicts(
    chunks: list[ReportChunk],
    evidence_bundles: list[ReportEvidenceBundle] | list[Mapping[str, Any]] | None,
) -> list[Mapping[str, Any]]:
    if evidence_bundles is None:
        store = load_concept_catalog_store()
        embedding_store = load_concept_embedding_store(store=store)
        evidence_bundles = build_report_evidence_bundles(
            chunks,
            store=store,
            embedding_store=embedding_store,
        )
    return [_evidence_bundle_to_dict(bundle) for bundle in evidence_bundles]


def _evidence_bundle_to_dict(
    bundle: ReportEvidenceBundle | Mapping[str, Any],
) -> Mapping[str, Any]:
    if isinstance(bundle, Mapping):
        return _json_safe_mapping(bundle)
    if hasattr(bundle, "to_dict"):
        return _json_safe_mapping(bundle.to_dict())
    raise ValueError("evidence bundle must be a mapping or to_dict-capable object")


def _parse_model_response(response: Mapping[str, Any] | str) -> Mapping[str, Any]:
    if isinstance(response, Mapping):
        return response
    if not isinstance(response, str):
        raise ValueError("model response must be a mapping or JSON string")

    payload_text = _extract_json_payload(response)
    payload = json.loads(payload_text)
    if not isinstance(payload, Mapping):
        raise ValueError("model response JSON must be an object")
    return payload


def _extract_json_payload(value: str) -> str:
    stripped = value.strip()
    if stripped.startswith("```"):
        lines = stripped.splitlines()
        if lines and lines[0].startswith("```"):
            lines = lines[1:]
        if lines and lines[-1].strip().startswith("```"):
            lines = lines[:-1]
        else:
            raise ValueError("model response code fence was not closed")
    return "\n".join(lines).strip() if value.strip().startswith("```") else stripped


def _report_analysis_from_payload(payload: Mapping[str, Any]) -> ReportAnalysis:
    if _required_text(payload.get("status"), "status") != "SUCCESS":
        return fallback_report_analysis()
    _require_mapping(payload.get("fieldEvidence"), "fieldEvidence")
    raw_score_delta = _require_mapping(payload.get("scoreDelta"), "scoreDelta")
    confidence = clamp_confidence(payload.get("confidence"))
    if confidence < LOW_CONFIDENCE_FALLBACK_THRESHOLD:
        return fallback_report_analysis()

    topics = _required_string_tuple(payload.get("topics"), "topics")
    status_message = _required_text(payload.get("statusMessage"), "statusMessage")
    daily_report = _daily_report_from_payload(
        _require_mapping(payload.get("dailyReport"), "dailyReport")
    )
    next_recommendation = _next_recommendation_from_payload(
        _require_mapping(payload.get("nextRecommendation"), "nextRecommendation")
    )

    return ReportAnalysis(
        status="SUCCESS",
        topics=topics,
        field_evidence=_field_evidence_from_payload(payload["fieldEvidence"]),
        score_delta=apply_report_confidence_policy(raw_score_delta, confidence),
        confidence=confidence,
        status_message=status_message,
        daily_report=daily_report,
        next_recommendation=next_recommendation,
    )


def _field_evidence_from_payload(value: Any) -> dict[str, str]:
    payload = _require_mapping(value, "fieldEvidence")
    return {
        field_name: _clean_text(payload.get(field_name))
        for field_name in SCORE_FIELDS
    }


def _daily_report_from_payload(payload: Mapping[str, Any]) -> DailyReportAnalysis:
    return DailyReportAnalysis(
        text=_required_text(payload.get("text"), "dailyReport.text"),
        feedback=_required_text(payload.get("feedback"), "dailyReport.feedback"),
    )


def _next_recommendation_from_payload(
    payload: Mapping[str, Any],
) -> NextRecommendation:
    return NextRecommendation(
        topics=_required_string_tuple(
            payload.get("topics"),
            "nextRecommendation.topics",
        ),
        rationale=_required_text(
            payload.get("rationale"),
            "nextRecommendation.rationale",
        ),
    )


def _require_mapping(value: Any, name: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise ValueError(f"{name} must be an object")
    return value


def _required_string_tuple(value: Any, name: str) -> tuple[str, ...]:
    if not isinstance(value, (list, tuple)):
        raise ValueError(f"{name} must be an array")
    result: list[str] = []
    for item in value:
        text = _clean_text(item)
        if text:
            result.append(text)
    return tuple(result)


def _required_text(value: Any, name: str) -> str:
    text = _clean_text(value)
    if not text:
        raise ValueError(f"{name} is required")
    return text


def _resolve_personality(
    character_personality: str | None,
    character_metadata: Mapping[str, Any] | None,
) -> str:
    explicit = _clean_text(character_personality)
    if explicit:
        return explicit
    if character_metadata is None:
        return ""
    return _clean_text(character_metadata.get("personality"))


def _user_context(user_metadata: Mapping[str, Any] | None) -> Mapping[str, Any]:
    metadata = _json_safe_mapping(user_metadata or {})
    weekly_streak = metadata.get("weeklyStudyStreak")
    return {
        "userMetadata": metadata,
        "studyContinuity": _study_continuity_context(weekly_streak),
        "scoreUsagePolicy": (
            "weeklyStudyStreak, reportDirection.scoreDeltaHint, reportDirection.focus, "
            "recent study context, recent score changes, and currentStats are tone, "
            "feedback, and recommendation context only. They must never increase "
            "scoreDelta without evidence in today's report body."
        ),
    }


def _character_context(
    character_personality: str | None,
    character_metadata: Mapping[str, Any] | None,
) -> Mapping[str, Any]:
    metadata = _json_safe_mapping(character_metadata or {})
    emotion = _resolve_character_emotion(metadata.get("emotion"))
    return {
        "baseToneGuidance": BASE_TONE_GUIDANCE,
        "personality": _clean_text(character_personality),
        "emotion": emotion,
        "emotionToneGuidance": EMOTION_TONE_GUIDANCE.get(
            emotion,
            "명시 감정 없음: 귀엽고 다정한 기본 말투를 유지하며 personality와 학습 근거를 자연스럽게 반영한다.",
        ),
        "currentStats": _json_safe(metadata.get("currentStats", {})),
        "characterMetadata": metadata,
    }


def _resolve_character_emotion(value: Any) -> EmotionStatus | str:
    emotion = _clean_text(value).upper()
    if emotion in EMOTION_TONE_GUIDANCE:
        return emotion
    return ""


def _study_continuity_context(weekly_streak: Any) -> Mapping[str, Any]:
    streak = _clean_text(weekly_streak)
    if not streak:
        return {
            "weeklyStudyStreak": "",
            "pattern": "unknown",
            "guidance": "스트릭 근거가 약하면 억지로 언급하지 않는다.",
        }

    normalized = "".join(character for character in streak if character in {"0", "1"})
    trailing_ones = len(normalized) - len(normalized.rstrip("1"))
    recent_zeros = normalized[-7:].count("0")
    if trailing_ones >= 3:
        pattern = "steady"
        guidance = "연속 리포트 작성 흐름이 보이면 꾸준함을 구체적으로 칭찬한다."
    elif normalized.endswith("1") and recent_zeros >= 3:
        pattern = "returning"
        guidance = "오랜만의 복귀라면 캐릭터 성격에 맞춰 부드럽지만 분명하게 리듬 회복을 돕는다."
    elif recent_zeros >= 4:
        pattern = "sparse"
        guidance = "뜸한 작성 패턴이면 비난 없이 다시 루틴을 잡도록 말한다."
    else:
        pattern = "mixed"
        guidance = "스트릭이 유의미하게 연결될 때만 짧게 언급한다."
    return {
        "weeklyStudyStreak": streak,
        "normalized": normalized,
        "trailingStudyDays": trailing_ones,
        "recentMissingDays": recent_zeros,
        "pattern": pattern,
        "guidance": guidance,
    }


def _json_safe_mapping(value: Mapping[str, Any]) -> Mapping[str, Any]:
    return {
        str(key): _json_safe(nested_value)
        for key, nested_value in value.items()
    }


def _json_safe(value: Any) -> Any:
    if isinstance(value, Mapping):
        return _json_safe_mapping(value)
    if isinstance(value, (list, tuple)):
        return [_json_safe(item) for item in value]
    if isinstance(value, set):
        return [_json_safe(item) for item in sorted(value, key=str)]
    if isinstance(value, (str, int, float, bool)) or value is None:
        return value
    return str(value)


def _json_dumps(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=False, indent=2)


def _clean_text(value: Any) -> str:
    if value is None:
        return ""
    return str(value).strip()


def _load_prompt_template() -> str:
    template_path = Path(__file__).resolve().parent / "prompts" / "report_analysis.md"
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
