from __future__ import annotations

import re
from dataclasses import dataclass

from app.rag.schemas import SCORE_FIELDS
from app.rag.text import (
    FIELD_KEYWORDS,
    extract_terms,
    infer_fields,
    keyword_matches,
    normalize_text,
)
from app.scoring.schemas import ReportChunk


MIN_MERGE_CHARS = 40
MIN_SPLIT_CHARS = 180
TARGET_CHUNK_CHARS = 520
MAX_SEGMENT_CHARS = 680
DEFAULT_TOPIC_LIMIT = 8

HEADING_RE = re.compile(r"^\s{0,3}#{1,6}\s+(.+?)\s*$")
BULLET_RE = re.compile(r"^\s{0,3}(?:[-*+]\s+|\d+[.)]\s+).+")
CODE_FENCE_RE = re.compile(r"^\s*```")
SLASH_TOPIC_RE = re.compile(
    r"[A-Za-z가-힣0-9][A-Za-z가-힣0-9+#._-]*(?:/[A-Za-z가-힣0-9][A-Za-z가-힣0-9+#._-]*)+"
)
PLUS_TOPIC_RE = re.compile(
    r"[A-Za-z][A-Za-z0-9._-]*(?:[ \t]+[A-Za-z][A-Za-z0-9._-]*)?[ \t]+N[ \t]*\+[ \t]*1",
    re.IGNORECASE,
)
BACKTICK_TOPIC_RE = re.compile(r"`([^`\n]{2,80})`")
CAPITAL_TOPIC_RE = re.compile(
    r"\b[A-Z][A-Za-z0-9]*(?:[A-Z][a-z0-9]+|[A-Z0-9]*)(?:\s+[A-Z][A-Za-z0-9]{1,}){0,2}\b"
)
SENTENCE_BOUNDARY_RE = re.compile(r"(?:다\.|요\.|[.!?。]|[,;；，])\s*")


TOPIC_RULES: tuple[tuple[str, tuple[str, ...], tuple[str, ...]], ...] = (
    ("JPA N+1", ("jpa n+1", "jpa n + 1", "n+1", "n + 1", "n플러스1"), ("db", "framework")),
    ("JPA", ("jpa", "hibernate", "영속성"), ("db", "framework")),
    ("Fetch Join", ("fetch join", "페치 조인"), ("db", "framework")),
    ("Lazy Loading", ("lazy loading", "지연 로딩"), ("db", "framework")),
    ("OAuth/JWT", ("oauth/jwt", "oauth jwt"), ("network", "framework")),
    ("OAuth", ("oauth", "oauth2"), ("network", "framework")),
    ("JWT", ("jwt", "json web token"), ("network", "framework")),
    ("Authentication", ("인증", "인가", "authorization"), ("network", "framework")),
    ("REST API", ("rest api", "restful", "rest"), ("network",)),
    ("HTTP", ("http", "https", "uri", "상태 코드"), ("network",)),
    ("프로세스/스레드", ("프로세스/스레드", "프로세스 스레드", "process/thread"), ("cs",)),
    ("OS", ("os", "운영체제"), ("cs",)),
    ("Docker", ("docker", "container", "컨테이너", "이미지"), ("framework", "cs")),
    ("Graph", ("그래프", "graph", "dijkstra", "다익스트라"), ("algorithm",)),
)


@dataclass(frozen=True)
class ReportSegment:
    text: str
    char_start: int
    char_end: int
    kind: str


@dataclass(frozen=True)
class HintCandidate:
    position: int
    priority: int
    value: str


def chunk_daily_report(title: str | None, content: str | None) -> list[ReportChunk]:
    combined_text = combine_report_text(title, content)
    if not combined_text.strip():
        return []

    segments = split_report_segments(combined_text)
    chunks: list[ReportChunk] = []
    for grouped_segments in _assemble_segments(combined_text, segments):
        char_start = grouped_segments[0].char_start
        char_end = grouped_segments[-1].char_end
        text = combined_text[char_start:char_end].strip()
        if not text:
            continue
        topic_hints = tuple(extract_topic_hints(text))
        field_hints = tuple(extract_field_hints(text, topic_hints))
        chunks.append(
            ReportChunk(
                report_chunk_id=f"report:{len(chunks)}",
                text=text,
                char_start=char_start,
                char_end=char_end,
                topic_hints=topic_hints,
                field_hints=field_hints,
            )
        )
    return chunks


def combine_report_text(title: str | None, content: str | None) -> str:
    normalized_title = normalize_text(title or "").strip()
    normalized_content = normalize_text(content or "")
    if normalized_title:
        if normalized_content:
            return f"{normalized_title}\n\n{normalized_content}"
        return normalized_title
    return normalized_content


def split_report_segments(combined_text: str) -> list[ReportSegment]:
    segments: list[ReportSegment] = []
    paragraph_lines: list[tuple[str, int, int]] = []
    in_code_fence = False

    def flush_paragraph() -> None:
        nonlocal paragraph_lines
        if not paragraph_lines:
            return
        start = paragraph_lines[0][1]
        end = paragraph_lines[-1][2]
        _append_trimmed_segment(segments, combined_text, start, end, "paragraph")
        paragraph_lines = []

    for line, line_start, line_end in _iter_lines(combined_text):
        line_without_newline = line.rstrip("\r\n")
        stripped = line_without_newline.strip()
        is_code_fence = bool(CODE_FENCE_RE.match(line_without_newline))

        if in_code_fence:
            paragraph_lines.append((line, line_start, line_end))
            if is_code_fence:
                in_code_fence = False
            continue

        if is_code_fence:
            paragraph_lines.append((line, line_start, line_end))
            in_code_fence = True
            continue

        if not stripped:
            flush_paragraph()
            continue

        if HEADING_RE.match(line_without_newline):
            flush_paragraph()
            _append_trimmed_segment(segments, combined_text, line_start, line_end, "heading")
            continue

        if BULLET_RE.match(line_without_newline):
            flush_paragraph()
            _append_trimmed_segment(segments, combined_text, line_start, line_end, "bullet")
            continue

        paragraph_lines.append((line, line_start, line_end))

    flush_paragraph()

    split_segments: list[ReportSegment] = []
    for segment in segments:
        split_segments.extend(_split_long_segment(segment))
    return split_segments


def extract_topic_hints(text: str, heading_context: str | None = None) -> list[str]:
    source = normalize_text(" ".join(part for part in (heading_context, text) if part))
    if not source.strip():
        return []

    lowered = source.lower()
    terms = set(extract_terms(lowered))
    candidates: list[HintCandidate] = []

    for match in HEADING_RE.finditer(source):
        _add_candidate(candidates, match.group(1), match.start(1), 0)
    for match in BACKTICK_TOPIC_RE.finditer(source):
        _add_candidate(candidates, match.group(1), match.start(1), 1)
    for match in SLASH_TOPIC_RE.finditer(source):
        _add_candidate(candidates, match.group(0), match.start(), 2)
    for match in PLUS_TOPIC_RE.finditer(source):
        _add_candidate(candidates, _normalize_plus_topic(match.group(0)), match.start(), 2)

    for topic, aliases, _fields in TOPIC_RULES:
        positions = [
            _alias_position(alias, lowered, terms)
            for alias in aliases
        ]
        matched_positions = [position for position in positions if position is not None]
        if matched_positions:
            _add_candidate(candidates, topic, min(matched_positions), 3)

    for match in CAPITAL_TOPIC_RE.finditer(source):
        _add_candidate(candidates, match.group(0), match.start(), 4)

    return _dedupe_candidates(candidates)[:DEFAULT_TOPIC_LIMIT]


def extract_field_hints(
    text: str,
    topic_hints: tuple[str, ...] | list[str] | None = None,
) -> list[str]:
    hints = tuple(topic_hints or ())
    haystack = normalize_text(" ".join((text, *hints))).lower()
    if not haystack.strip():
        return []

    terms = set(extract_terms(haystack))
    fields: list[str] = []
    for field_name in infer_fields(text, *hints):
        if field_name in SCORE_FIELDS and _field_has_keyword_evidence(field_name, haystack, terms):
            fields.append(field_name)

    for topic in hints:
        topic_key = normalize_text(topic).lower()
        for rule_topic, aliases, rule_fields in TOPIC_RULES:
            alias_keys = {normalize_text(alias).lower() for alias in aliases}
            if topic_key == rule_topic.lower() or topic_key in alias_keys:
                fields.extend(field_name for field_name in rule_fields if field_name in SCORE_FIELDS)

    return _dedupe_preserving_order(fields)


def _iter_lines(value: str) -> list[tuple[str, int, int]]:
    lines: list[tuple[str, int, int]] = []
    offset = 0
    for line in value.splitlines(keepends=True):
        line_start = offset
        offset += len(line)
        lines.append((line, line_start, offset))
    return lines


def _append_trimmed_segment(
    segments: list[ReportSegment],
    combined_text: str,
    start: int,
    end: int,
    kind: str,
) -> None:
    raw_text = combined_text[start:end]
    leading = len(raw_text) - len(raw_text.lstrip())
    trailing = len(raw_text.rstrip())
    char_start = start + leading
    char_end = start + trailing
    if char_start < char_end:
        segments.append(
            ReportSegment(
                text=combined_text[char_start:char_end],
                char_start=char_start,
                char_end=char_end,
                kind=kind,
            )
        )


def _split_long_segment(segment: ReportSegment) -> list[ReportSegment]:
    if len(segment.text) <= MAX_SEGMENT_CHARS:
        return [segment]

    pieces: list[ReportSegment] = []
    start = 0
    text_length = len(segment.text)
    while text_length - start > MAX_SEGMENT_CHARS:
        break_at = _choose_breakpoint(segment.text, start)
        pieces.append(_trim_piece(segment, start, break_at))
        start = break_at
        while start < text_length and segment.text[start].isspace():
            start += 1
    if start < text_length:
        pieces.append(_trim_piece(segment, start, text_length))
    return [piece for piece in pieces if piece.text]


def _choose_breakpoint(text: str, start: int) -> int:
    minimum = start + MIN_SPLIT_CHARS
    target = min(start + TARGET_CHUNK_CHARS, len(text))
    maximum = min(start + MAX_SEGMENT_CHARS, len(text))
    candidates = [
        match.end()
        for match in SENTENCE_BOUNDARY_RE.finditer(text, start, maximum)
        if minimum <= match.end() <= maximum
    ]
    if candidates:
        near_target = [candidate for candidate in candidates if candidate <= target]
        return max(near_target or candidates)

    whitespace_candidates = [
        match.end()
        for match in re.finditer(r"\s+", text[start:maximum])
        if minimum <= start + match.end() <= maximum
    ]
    if whitespace_candidates:
        near_target = [start + candidate for candidate in whitespace_candidates if start + candidate <= target]
        return max(near_target or [start + whitespace_candidates[-1]])
    return maximum


def _trim_piece(segment: ReportSegment, start: int, end: int) -> ReportSegment:
    raw_piece = segment.text[start:end]
    leading = len(raw_piece) - len(raw_piece.lstrip())
    trailing = len(raw_piece.rstrip())
    char_start = segment.char_start + start + leading
    char_end = segment.char_start + start + trailing
    return ReportSegment(
        text=segment.text[start + leading : start + trailing],
        char_start=char_start,
        char_end=char_end,
        kind=segment.kind,
    )


def _assemble_segments(
    combined_text: str,
    segments: list[ReportSegment],
) -> list[list[ReportSegment]]:
    grouped: list[list[ReportSegment]] = []
    current: list[ReportSegment] = []

    for segment in segments:
        if not current:
            current = [segment]
            continue

        if _should_merge(combined_text, current, segment):
            current.append(segment)
            continue

        grouped.append(current)
        current = [segment]

    if current:
        grouped.append(current)
    return grouped


def _should_merge(
    combined_text: str,
    current: list[ReportSegment],
    next_segment: ReportSegment,
) -> bool:
    current_text = _group_text(combined_text, current)
    current_has_signal = bool(extract_topic_hints(current_text))
    next_has_signal = bool(extract_topic_hints(next_segment.text))
    current_len = len(current_text.strip())
    next_len = len(next_segment.text.strip())
    projected_len = next_segment.char_end - current[0].char_start

    if projected_len > TARGET_CHUNK_CHARS:
        return False
    if next_segment.kind == "heading":
        return (
            current_len < MIN_MERGE_CHARS
            and not current_has_signal
            and all(segment.kind == "paragraph" for segment in current)
        )
    if current[-1].kind == "heading":
        return True
    if current_len < MIN_MERGE_CHARS and not current_has_signal:
        return True
    if next_len < MIN_MERGE_CHARS and not next_has_signal:
        return True
    if next_segment.kind == "bullet" or current[-1].kind == "bullet":
        return False
    return not (current_has_signal and next_has_signal)


def _group_text(combined_text: str, segments: list[ReportSegment]) -> str:
    return combined_text[segments[0].char_start : segments[-1].char_end].strip()


def _field_has_keyword_evidence(
    field_name: str,
    haystack: str,
    terms: set[str],
) -> bool:
    return any(
        keyword_matches(normalize_text(keyword).lower(), haystack, terms)
        for keyword in FIELD_KEYWORDS.get(field_name, ())
    )


def _alias_position(alias: str, haystack: str, terms: set[str]) -> int | None:
    normalized_alias = normalize_text(alias).lower()
    if keyword_matches(normalized_alias, haystack, terms) or normalized_alias in haystack:
        return haystack.find(normalized_alias)
    return None


def _add_candidate(
    candidates: list[HintCandidate],
    value: str,
    position: int,
    priority: int,
) -> None:
    normalized = re.sub(r"\s+", " ", normalize_text(value)).strip(" `*_#>-")
    if len(normalized) < 2:
        return
    candidates.append(HintCandidate(position=max(0, position), priority=priority, value=normalized))


def _normalize_plus_topic(value: str) -> str:
    normalized = re.sub(r"\s+", " ", normalize_text(value)).strip()
    return re.sub(r"\s*N\s*\+\s*1", " N+1", normalized, flags=re.IGNORECASE)


def _dedupe_candidates(candidates: list[HintCandidate]) -> list[str]:
    ordered = sorted(candidates, key=lambda candidate: (candidate.position, candidate.priority))
    values: list[str] = []
    seen: set[str] = set()
    for candidate in ordered:
        key = candidate.value.lower()
        if key in seen:
            continue
        seen.add(key)
        values.append(candidate.value)
    return values


def _dedupe_preserving_order(values: list[str]) -> list[str]:
    deduped: list[str] = []
    seen: set[str] = set()
    for value in values:
        if value in seen:
            continue
        seen.add(value)
        deduped.append(value)
    return deduped
