from __future__ import annotations

import re
from pathlib import Path
from typing import Any, Mapping

from .problem_bank_search import (
    ProblemSearchHit,
    problem_to_recommendation_snapshot,
    search_problem_bank,
)
from .problem_bank_store import ProblemBankStore, load_problem_bank_store
from .text import clean_markdown, extract_terms, normalize_text


DEFAULT_RECOMMENDATION_LIMIT = 3
MAX_RECOMMENDATION_LIMIT = 3
DEFAULT_MIN_SCORE = 4.0
SEARCH_LIMIT_MULTIPLIER = 8

REPORT_TEXT_KEYS = (
    "reportText",
    "inputReport",
    "text",
    "content",
    "summary",
    "analysis",
    "feedback",
    "answer",
)
REPORT_LIST_KEYS = (
    "detectedKeywords",
    "keywords",
    "keyTerms",
    "weakConcepts",
    "missingConcepts",
    "detectedFields",
    "fields",
)

TOPIC_STOPWORDS = {
    "q",
    "기본",
    "심화",
    "문제",
    "문제란",
    "무엇",
    "무엇인가요",
    "무엇이며",
    "어떻게",
    "설명",
    "설명해주세요",
    "서술",
    "서술하시오",
    "답하시오",
    "차이",
    "차이점",
    "방법",
    "각각",
}


def recommend_quizzes(
    report: str | Mapping[str, Any],
    *,
    store: ProblemBankStore | None = None,
    catalog_path: Path | None = None,
    limit: int = DEFAULT_RECOMMENDATION_LIMIT,
    min_score: float = DEFAULT_MIN_SCORE,
) -> list[dict[str, Any]]:
    """Recommend existing problem snapshots for a user report.

    This is an internal helper only. It does not create new quiz content and does
    not depend on API, Spring Boot, pgvector, or embedding infrastructure.
    """

    capped_limit = _clamp_limit(limit)
    if capped_limit <= 0:
        return []

    query = build_recommendation_query(report)
    if not query:
        return []

    problem_store = store
    if problem_store is None and catalog_path is not None:
        problem_store = load_problem_bank_store(catalog_path)

    hits = search_problem_bank(
        query,
        store=problem_store,
        limit=max(capped_limit * SEARCH_LIMIT_MULTIPLIER, capped_limit),
        min_score=min_score,
    )
    return [
        problem_to_recommendation_snapshot(hit.problem, match_score=hit.score)
        for hit in _dedupe_hits(hits, capped_limit)
    ]


def build_recommended_quizzes(
    report: str | Mapping[str, Any],
    *,
    store: ProblemBankStore | None = None,
    catalog_path: Path | None = None,
    limit: int = DEFAULT_RECOMMENDATION_LIMIT,
    min_score: float = DEFAULT_MIN_SCORE,
) -> list[dict[str, Any]]:
    return recommend_quizzes(
        report,
        store=store,
        catalog_path=catalog_path,
        limit=limit,
        min_score=min_score,
    )


def build_recommendation_query(report: str | Mapping[str, Any]) -> str:
    if isinstance(report, str):
        base_query = clean_markdown(report)
    elif isinstance(report, Mapping):
        base_query = clean_markdown(" ".join(_extract_report_parts(report)))
    else:
        return ""

    if not base_query:
        return ""

    return _expand_query(base_query)


def _extract_report_parts(report: Mapping[str, Any]) -> list[str]:
    parts: list[str] = []
    for key in REPORT_TEXT_KEYS:
        value = report.get(key)
        if isinstance(value, str):
            parts.append(value)
    for key in REPORT_LIST_KEYS:
        parts.extend(_string_values(report.get(key)))
    if not parts:
        parts.extend(_string_values(report))
    return parts


def _string_values(value: Any) -> list[str]:
    if isinstance(value, str):
        return [value]
    if isinstance(value, Mapping):
        strings: list[str] = []
        for nested_value in value.values():
            strings.extend(_string_values(nested_value))
        return strings
    if isinstance(value, (list, tuple, set)):
        strings = []
        for nested_value in value:
            strings.extend(_string_values(nested_value))
        return strings
    return []


def _expand_query(query: str) -> str:
    return normalize_text(query)


def _dedupe_hits(hits: list[ProblemSearchHit], limit: int) -> list[ProblemSearchHit]:
    selected: list[ProblemSearchHit] = []
    seen_problem_ids: set[int] = set()
    seen_source_keys: set[str] = set()
    seen_content_hashes: set[str] = set()
    seen_questions: set[str] = set()
    seen_topics: set[str] = set()

    for hit in hits:
        problem = hit.problem
        question_key = _normalize_topic_text(problem.question)
        topic_key = _topic_key(hit)
        if problem.problem_id in seen_problem_ids:
            continue
        if problem.source_key in seen_source_keys:
            continue
        if problem.content_hash in seen_content_hashes:
            continue
        if question_key in seen_questions:
            continue
        if topic_key and topic_key in seen_topics:
            continue

        selected.append(hit)
        seen_problem_ids.add(problem.problem_id)
        seen_source_keys.add(problem.source_key)
        seen_content_hashes.add(problem.content_hash)
        seen_questions.add(question_key)
        if topic_key:
            seen_topics.add(topic_key)
        if len(selected) >= limit:
            break

    return selected


def _topic_key(hit: ProblemSearchHit) -> str:
    problem = hit.problem
    terms = set(_topic_terms(" ".join((problem.title, problem.question))))
    special_terms = {
        "n+1",
        "oauth",
        "jwt",
        "rest",
        "http",
        "https",
        "jpa",
        "fetch",
        "join",
    }
    matched_specials = sorted(
        special_terms.intersection(terms).intersection(hit.matched_terms)
    )
    if "n+1" in terms:
        return f"{problem.primary_field}:n+1"
    if "oauth" in matched_specials and "jwt" in matched_specials:
        return f"{problem.primary_field}:oauth-jwt"
    if matched_specials:
        return f"{problem.primary_field}:{'-'.join(matched_specials[:3])}"
    return f"{problem.primary_field}:{_normalize_topic_text(problem.title)}"


def _topic_terms(value: str) -> list[str]:
    terms: list[str] = []
    for term in extract_terms(value):
        if term in TOPIC_STOPWORDS:
            continue
        if term.isdigit():
            continue
        if re.fullmatch(r"q\d+", term):
            continue
        terms.append(term)
    return terms


def _normalize_topic_text(value: str) -> str:
    normalized = clean_markdown(value).lower()
    normalized = re.sub(r"^\[[^\]]+]\s*", "", normalized)
    normalized = re.sub(r"^(기본|심화|중급|고급)\s*q?\d*[\s.)-]*", "", normalized)
    normalized = re.sub(r"^q\d+[\s.)-]*", "", normalized)
    normalized = re.sub(r"^\d+[\s.)-]*", "", normalized)
    normalized = re.sub(r"\s+", " ", normalized)
    return normalized.strip()


def _clamp_limit(limit: int) -> int:
    if limit <= 0:
        return 0
    return min(limit, MAX_RECOMMENDATION_LIMIT)
