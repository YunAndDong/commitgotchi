from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .problem_bank_store import ProblemBankStore
from .quiz_recommender import recommend_quizzes
from .schemas import SCORE_FIELDS
from .text import (
    FIELD_KEYWORDS,
    clean_markdown,
    extract_terms,
    infer_fields,
    keyword_matches,
    normalize_text,
)


DEFAULT_TOPIC_LIMIT = 6


@dataclass(frozen=True)
class TopicRule:
    topic: str
    aliases: tuple[str, ...]
    fields: tuple[str, ...]


TOPIC_RULES: tuple[TopicRule, ...] = (
    TopicRule("JPA N+1", ("n+1", "n + 1", "n플러스1"), ("db", "framework")),
    TopicRule("JPA", ("jpa", "hibernate", "영속성"), ("db", "framework")),
    TopicRule("Fetch Join", ("fetch join", "페치 조인"), ("db", "framework")),
    TopicRule("Lazy Loading", ("lazy loading", "지연 로딩"), ("db", "framework")),
    TopicRule("OAuth", ("oauth", "oauth2"), ("network", "framework")),
    TopicRule("JWT", ("jwt", "json web token"), ("network", "framework")),
    TopicRule("Authentication", ("인증", "인가", "authorization"), ("network", "framework")),
    TopicRule("REST API", ("rest api", "restful", "rest"), ("network",)),
    TopicRule("HTTP", ("http", "https", "uri", "상태 코드"), ("network",)),
    TopicRule("Docker", ("docker", "container", "컨테이너", "이미지"), ("framework", "cs")),
    TopicRule("Graph", ("그래프", "graph", "dijkstra", "다익스트라"), ("algorithm",)),
)


def build_daily_report_recommendation(
    report_text: str,
    *,
    store: ProblemBankStore | None = None,
    catalog_path: Path | None = None,
    limit: int = 3,
    min_score: float = 4.0,
) -> dict[str, Any]:
    """Build a callback-friendly quiz recommendation payload from daily report text.

    This module is intentionally heuristic-only: no API endpoint, Spring callback,
    LLM call, embedding, pgvector, or new quiz generation happens here.
    """

    analysis = analyze_daily_report(report_text)
    recommendation_input = {
        "reportText": analysis["inputReport"],
        "detectedKeywords": analysis["topics"],
        "detectedFields": analysis["detectedFields"],
        "scoreDeltaHint": analysis["scoreDeltaHint"],
    }
    recommended_quizzes = recommend_quizzes(
        recommendation_input,
        store=store,
        catalog_path=catalog_path,
        limit=limit,
        min_score=min_score,
    )

    return {
        "topics": analysis["topics"],
        "detectedFields": analysis["detectedFields"],
        "scoreDeltaHint": analysis["scoreDeltaHint"],
        "nextRecommendation": _next_recommendation(
            analysis["topics"],
            analysis["detectedFields"],
            recommended_quizzes,
        ),
        "recommendedQuizzes": recommended_quizzes,
    }


def analyze_daily_report(report_text: str) -> dict[str, Any]:
    normalized_report = clean_markdown(report_text)
    topics = _detect_topics(normalized_report)
    detected_fields = _detect_fields(normalized_report, topics)
    return {
        "inputReport": normalized_report,
        "topics": topics,
        "detectedFields": detected_fields,
        "scoreDeltaHint": _score_delta_hint(detected_fields, topics),
    }


def _detect_topics(report_text: str) -> list[str]:
    if not report_text:
        return []

    haystack = normalize_text(report_text).lower()
    terms = set(extract_terms(haystack))
    topics: list[str] = []
    for rule in TOPIC_RULES:
        if _rule_matches(rule, haystack, terms):
            topics.append(rule.topic)

    if topics:
        return _dedupe_preserving_order(topics)[:DEFAULT_TOPIC_LIMIT]
    return []


def _detect_fields(report_text: str, topics: list[str]) -> list[str]:
    if not report_text:
        return []

    haystack = normalize_text(report_text).lower()
    terms = set(extract_terms(haystack))
    fields: list[str] = []

    inferred_fields = infer_fields(report_text)
    for field_name in inferred_fields:
        if field_name in SCORE_FIELDS and _field_has_keyword_evidence(field_name, haystack, terms):
            fields.append(field_name)

    topic_set = set(topics)
    for rule in TOPIC_RULES:
        if rule.topic not in topic_set:
            continue
        fields.extend(field_name for field_name in rule.fields if field_name in SCORE_FIELDS)

    return _dedupe_preserving_order(fields)


def _field_has_keyword_evidence(
    field_name: str,
    haystack: str,
    terms: set[str],
) -> bool:
    return any(
        keyword_matches(normalize_text(keyword).lower(), haystack, terms)
        for keyword in FIELD_KEYWORDS.get(field_name, ())
    )


def _rule_matches(rule: TopicRule, haystack: str, terms: set[str]) -> bool:
    for alias in rule.aliases:
        normalized_alias = normalize_text(alias).lower()
        if keyword_matches(normalized_alias, haystack, terms):
            return True
        if normalized_alias in haystack:
            return True
    return False


def _score_delta_hint(detected_fields: list[str], topics: list[str]) -> str:
    if detected_fields:
        return f"field_focus:{','.join(detected_fields)}"
    if topics:
        return f"topic_focus:{','.join(topics)}"
    return "insufficient_signal"


def _next_recommendation(
    topics: list[str],
    detected_fields: list[str],
    recommended_quizzes: list[dict[str, Any]],
) -> str:
    if recommended_quizzes:
        return "가까운 기존 문제를 다음 추천 퀴즈 후보로 사용합니다."
    if topics or detected_fields:
        return "감지된 주제는 있지만 가까운 기존 문제가 없어 추천 퀴즈를 비웁니다."
    return "리포트에서 추천할 만한 기술 주제를 찾지 못했습니다."


def _dedupe_preserving_order(values: list[str]) -> list[str]:
    deduped: list[str] = []
    seen: set[str] = set()
    for value in values:
        if value in seen:
            continue
        deduped.append(value)
        seen.add(value)
    return deduped
