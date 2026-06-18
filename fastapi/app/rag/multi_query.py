from __future__ import annotations

from collections import Counter
from dataclasses import dataclass
from typing import Sequence

from app.scoring.schemas import ReportChunk

from .schemas import ConceptSearchHit, SCORE_FIELDS
from .text import clean_markdown, normalize_text


DEFAULT_MAX_SUBQUERIES = 3
WEAK_FIELD_SIGNALS = frozenset(("framework", "cs"))


@dataclass(frozen=True)
class ReportSubquery:
    key: str
    query: str
    topic_hints: tuple[str, ...]
    field_hints: tuple[str, ...]


def build_report_subqueries(
    report_chunk: ReportChunk,
    *,
    max_subqueries: int = DEFAULT_MAX_SUBQUERIES,
) -> tuple[ReportSubquery, ...]:
    if max_subqueries <= 0 or not report_chunk.text.strip():
        return ()

    fields = _valid_fields(report_chunk.field_hints)
    topics = _normalized_hints(report_chunk.topic_hints)
    strong_fields = tuple(field for field in fields if field not in WEAK_FIELD_SIGNALS)
    field_candidates = _field_candidates(fields, strong_fields)

    subqueries: list[ReportSubquery] = []
    for field in field_candidates:
        subqueries.append(
            ReportSubquery(
                key=f"field:{field}",
                query=_focused_query(report_chunk.text, field=field),
                topic_hints=topics,
                field_hints=(field,),
            )
        )
        if len(subqueries) >= max_subqueries:
            return tuple(subqueries)

    used_topic_keys: set[str] = set()
    for topic in topics:
        topic_key = normalize_text(topic).lower()
        if not topic_key or topic_key in used_topic_keys:
            continue
        used_topic_keys.add(topic_key)
        subqueries.append(
            ReportSubquery(
                key=f"topic:{topic_key}",
                query=_focused_query(report_chunk.text, topic=topic),
                topic_hints=(topic,),
                field_hints=strong_fields,
            )
        )
        if len(subqueries) >= max_subqueries:
            break

    return tuple(subqueries)


def merge_subquery_hits(
    lanes: Sequence[Sequence[ConceptSearchHit]],
    *,
    limit: int,
    max_per_source: int | None,
) -> list[ConceptSearchHit]:
    if limit <= 0:
        return []

    active_lanes = [tuple(_dedupe_lane(lane)) for lane in lanes if lane]
    if not active_lanes:
        return []

    selected: list[ConceptSearchHit] = []
    selected_ids: set[str] = set()
    source_counts: Counter[str] = Counter()
    cursors = [0 for _lane in active_lanes]

    def next_valid(lane_index: int) -> ConceptSearchHit | None:
        lane = active_lanes[lane_index]
        while cursors[lane_index] < len(lane):
            hit = lane[cursors[lane_index]]
            cursors[lane_index] += 1
            if hit.chunk.chunk_id in selected_ids:
                continue
            if _source_limit_reached(source_counts, hit, max_per_source):
                continue
            return hit
        return None

    def append_hit(hit: ConceptSearchHit | None) -> bool:
        if hit is None:
            return False
        selected.append(hit)
        selected_ids.add(hit.chunk.chunk_id)
        source_counts[hit.chunk.source_path] += 1
        return True

    for lane_index in range(len(active_lanes)):
        if len(selected) >= limit:
            break
        append_hit(next_valid(lane_index))

    while len(selected) < limit:
        added = False
        for lane_index in range(len(active_lanes)):
            if len(selected) >= limit:
                break
            added = append_hit(next_valid(lane_index)) or added
        if not added:
            break

    return selected


def _field_candidates(
    fields: tuple[str, ...],
    strong_fields: tuple[str, ...],
) -> tuple[str, ...]:
    if not strong_fields:
        return ()
    strong_set = set(strong_fields)
    return strong_fields + tuple(
        field
        for field in fields
        if field not in strong_set and field in WEAK_FIELD_SIGNALS
    )


def _valid_fields(values: tuple[str, ...]) -> tuple[str, ...]:
    seen: set[str] = set()
    result: list[str] = []
    for value in values:
        field = normalize_text(value).lower()
        if field in SCORE_FIELDS and field not in seen:
            seen.add(field)
            result.append(field)
    return tuple(result)


def _normalized_hints(values: tuple[str, ...]) -> tuple[str, ...]:
    seen: set[str] = set()
    result: list[str] = []
    for value in values:
        hint = normalize_text(value)
        key = hint.lower()
        if hint and key not in seen:
            seen.add(key)
            result.append(hint)
    return tuple(result)


def _focused_query(
    text: str,
    *,
    field: str | None = None,
    topic: str | None = None,
) -> str:
    focus_lines = []
    if field:
        focus_lines.append(f"Retrieval focus field: {field}")
    if topic:
        focus_lines.append(f"Retrieval focus topic: {topic}")
    return clean_markdown("\n".join((*focus_lines, "Report text:", text)))


def _dedupe_lane(lane: Sequence[ConceptSearchHit]) -> list[ConceptSearchHit]:
    seen: set[str] = set()
    result: list[ConceptSearchHit] = []
    for hit in lane:
        if hit.chunk.chunk_id in seen:
            continue
        seen.add(hit.chunk.chunk_id)
        result.append(hit)
    return result


def _source_limit_reached(
    source_counts: Counter[str],
    hit: ConceptSearchHit,
    max_per_source: int | None,
) -> bool:
    return max_per_source is not None and source_counts[hit.chunk.source_path] >= max_per_source
