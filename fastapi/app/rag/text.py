from __future__ import annotations

import hashlib
import html
import re
import unicodedata
from collections import Counter
from pathlib import Path

from .schemas import SCORE_FIELDS, empty_score_allocation


TOKEN_RE = re.compile(r"[A-Za-z가-힣0-9][A-Za-z가-힣0-9+#./_-]*")
HEADING_RE = re.compile(r"^(#{1,6})\s+(.+?)\s*$", re.MULTILINE)

STOPWORDS = {
    "그리고",
    "그러나",
    "대한",
    "때문",
    "설명",
    "해주세요",
    "합니다",
    "입니다",
    "있는",
    "없는",
    "사용",
    "학습",
    "오늘",
    "공부",
    "공부했다",
    "방식",
    "흐름",
    "문제",
    "해결",
    "방법",
    "기본",
    "심화",
    "차이",
    "the",
    "and",
    "for",
    "with",
    "from",
    "this",
    "that",
    "what",
    "how",
    "why",
    "api",
}

FIELD_KEYWORDS: dict[str, tuple[str, ...]] = {
    "db": (
        "db",
        "database",
        "sql",
        "rdbms",
        "nosql",
        "jpa",
        "orm",
        "transaction",
        "트랜잭션",
        "데이터베이스",
        "정규화",
        "인덱스",
        "격리",
        "영속성",
        "n+1",
    ),
    "algorithm": (
        "algorithm",
        "알고리즘",
        "자료구조",
        "data-structure",
        "complexity",
        "복잡도",
        "정렬",
        "탐색",
        "그래프",
        "트리",
        "스택",
        "큐",
    ),
    "cs": (
        "os",
        "운영체제",
        "computer-architecture",
        "컴퓨터구조",
        "process",
        "thread",
        "memory",
        "프로세스",
        "스레드",
        "메모리",
        "디자인패턴",
        "software",
        "version-control",
    ),
    "network": (
        "network",
        "네트워크",
        "http",
        "https",
        "tcp",
        "udp",
        "rest",
        "restful",
        "graphql",
        "security",
        "보안",
        "인증",
        "인가",
        "cors",
    ),
    "framework": (
        "spring",
        "java",
        "jpa",
        "orm",
        "hibernate",
        "entitygraph",
        "fetch join",
        "lazy loading",
        "react",
        "next",
        "frontend",
        "docker",
        "kubernetes",
        "cloud",
        "aws",
        "devops",
        "fastapi",
        "프레임워크",
        "스프링",
        "자바",
        "프론트엔드",
    ),
}

DB_DOMINANT_HINTS = (
    "n+1",
    "database",
    "데이터베이스",
    "sql",
    "transaction",
    "트랜잭션",
    "정규화",
    "인덱스",
    "격리",
)

DIFFICULTY_MAP = {
    "기본": "basic",
    "중급": "intermediate",
    "심화": "advanced",
    "고급": "advanced",
}

DIFFICULTY_MAX_SCORE = {
    "basic": 5,
    "intermediate": 7,
    "advanced": 10,
}


def normalize_text(value: str) -> str:
    value = unicodedata.normalize("NFC", value)
    value = value.replace("\ufeff", "")
    return html.unescape(value)


def clean_markdown(value: str) -> str:
    value = normalize_text(value)
    value = re.sub(r"```.*?```", " ", value, flags=re.DOTALL)
    value = re.sub(r"</?(details|summary)>", " ", value, flags=re.IGNORECASE)
    value = re.sub(r"<[^>]+>", " ", value)
    value = re.sub(r"!\[[^\]]*]\([^)]+\)", " ", value)
    value = re.sub(r"\[([^\]]+)]\([^)]+\)", r"\1", value)
    value = re.sub(r"[`*_>|#\-]+", " ", value)
    value = re.sub(r"\s+", " ", value)
    return value.strip()


def extract_terms(value: str) -> list[str]:
    terms: list[str] = []
    for raw in TOKEN_RE.findall(normalize_text(value)):
        term = raw.lower().strip("._-/")
        term = strip_korean_suffix(term)
        if len(term) < 2:
            continue
        if term in STOPWORDS:
            continue
        terms.append(term)
    return terms


def strip_korean_suffix(term: str) -> str:
    for suffix in ("으로부터", "에서", "으로", "에게", "까지", "부터", "했다", "한다", "이며", "이며", "와", "과", "을", "를", "은", "는", "이", "가"):
        if term.endswith(suffix) and len(term) > len(suffix) + 1:
            return term[: -len(suffix)]
    return term


def term_counter(value: str) -> Counter[str]:
    return Counter(extract_terms(value))


def stable_hex(value: str) -> str:
    return hashlib.sha256(normalize_text(value).encode("utf-8")).hexdigest()


def stable_int_id(value: str) -> int:
    return int(stable_hex(value)[:12], 16) % 1_000_000_000


def relative_source_path(path: Path, source_root: Path) -> str:
    try:
        return path.relative_to(source_root).as_posix()
    except ValueError:
        return path.as_posix()


def is_excluded_path(path: Path) -> bool:
    normalized = normalize_text(path.as_posix())
    if "(대외비)" in normalized:
        return True
    return any(part.startswith(".") for part in path.parts)


def infer_fields(*values: str) -> list[str]:
    haystack = normalize_text(" ".join(values)).lower()
    haystack_terms = set(extract_terms(haystack))
    scores: dict[str, int] = {}
    for field_name, keywords in FIELD_KEYWORDS.items():
        score = 0
        for keyword in keywords:
            normalized_keyword = normalize_text(keyword).lower()
            if keyword_matches(normalized_keyword, haystack, haystack_terms):
                score += 2 if len(normalized_keyword) > 3 else 1
        if score:
            scores[field_name] = score
    if "db" in scores and "framework" in scores:
        if any(hint in haystack for hint in DB_DOMINANT_HINTS):
            scores["db"] += 3
    if not scores:
        return ["cs"]
    return [
        field_name
        for field_name, _ in sorted(scores.items(), key=lambda item: item[1], reverse=True)
    ]


def keyword_matches(keyword: str, haystack: str, haystack_terms: set[str]) -> bool:
    if " " in keyword:
        return keyword in haystack
    if re.search(r"[^A-Za-z가-힣0-9+#./_-]", keyword):
        return keyword in haystack
    return keyword in haystack_terms


def score_allocation_for_fields(
    fields: list[str],
    difficulty: str = "basic",
) -> dict[str, int]:
    allocation = empty_score_allocation()
    usable_fields = [field_name for field_name in fields if field_name in SCORE_FIELDS]
    max_score = DIFFICULTY_MAX_SCORE.get(difficulty, DIFFICULTY_MAX_SCORE["basic"])
    if not usable_fields:
        allocation["cs"] = max_score
        return allocation
    for field_name in usable_fields:
        allocation[field_name] = max_score
    return allocation


def difficulty_from_text(value: str) -> str:
    if "⭐⭐⭐" in value:
        return "advanced"
    if "⭐⭐" in value:
        return "intermediate"
    for korean, english in DIFFICULTY_MAP.items():
        if korean in value:
            return english
    return "basic"


def split_sentences(value: str, limit: int = 3) -> list[str]:
    cleaned = clean_markdown(value)
    parts = re.split(r"(?<=[.!?。])\s+|\n+", cleaned)
    sentences = [part.strip() for part in parts if len(part.strip()) >= 20]
    return sentences[:limit]


def make_excerpt(value: str, query_terms: list[str], max_chars: int = 260) -> str:
    cleaned = clean_markdown(value)
    if not cleaned:
        return ""
    lowered = cleaned.lower()
    positions = [lowered.find(term.lower()) for term in query_terms if term.lower() in lowered]
    start = min([position for position in positions if position >= 0], default=0)
    start = max(start - 60, 0)
    excerpt = cleaned[start : start + max_chars].strip()
    if start > 0:
        excerpt = "... " + excerpt
    if start + max_chars < len(cleaned):
        excerpt += " ..."
    return excerpt
