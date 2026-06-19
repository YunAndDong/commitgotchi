from __future__ import annotations

from pathlib import PurePosixPath
from typing import Iterable

from .concept_store import ConceptCatalogStore
from .schemas import ConceptChunkRecord, ConceptSearchHit, NeighborhoodEvidence
from .text import extract_terms, term_counter


SAME_SOURCE_REASON_ORDER = (
    "previous_chunk",
    "next_chunk",
    "parent_heading",
    "sibling_heading",
    "same_source_nearby",
)
CROSS_SOURCE_REASON_ORDER = (
    "same_folder_related",
    "cross_source_related",
)
NEIGHBORHOOD_REASON_ORDER = SAME_SOURCE_REASON_ORDER + CROSS_SOURCE_REASON_ORDER
COMMON_SIGNAL_FIELDS = {"framework", "cs"}
METADATA_SIGNAL_TERMS = {
    "chunk",
    "field",
    "fields",
    "hint",
    "hints",
    "query",
    "report",
    "retrieval",
    "text",
    "topic",
    "topics",
} | COMMON_SIGNAL_FIELDS


def build_source_neighborhood(
    hits: list[ConceptSearchHit],
    *,
    store: ConceptCatalogStore,
    query_terms: tuple[str, ...] = (),
    field_hints: tuple[str, ...] = (),
    max_chunks: int = 8,
    max_same_source_neighbors: int | None = None,
    min_cross_source: int = 2,
    max_chars: int = 6000,
    max_text_chars_per_item: int = 1200,
) -> list[NeighborhoodEvidence]:
    if max_chunks <= 0 or max_chars <= 0 or not hits or len(store) == 0:
        return []

    same_source_cap = _resolve_same_source_cap(
        max_chunks,
        max_same_source_neighbors=max_same_source_neighbors,
    )
    cross_source_quota = max(0, min(min_cross_source, max_chunks))
    seeds = [hit.chunk for hit in hits]
    seed_ids = {chunk.chunk_id for chunk in seeds}
    seed_source_paths = {chunk.source_path for chunk in seeds}
    seen_ids = set(seed_ids)
    used_chars = sum(
        len(_trim_text(hit.chunk.text, max_text_chars_per_item))
        for hit in hits
    )
    query_term_set = _signal_term_set(query_terms)
    matched_term_set = _signal_term_set(
        term for hit in hits for term in hit.matched_terms
    )
    signal_terms = query_term_set | matched_term_set
    signal_fields = set(field_hints) - COMMON_SIGNAL_FIELDS

    results: list[NeighborhoodEvidence] = []

    def add_candidate(chunk: ConceptChunkRecord | None, reason: str) -> bool:
        nonlocal used_chars
        if chunk is None:
            return False
        if chunk.chunk_id in seen_ids:
            return False
        text = _trim_text(chunk.text, max_text_chars_per_item)
        if used_chars + len(text) > max_chars:
            return False
        seen_ids.add(chunk.chunk_id)
        used_chars += len(text)
        results.append(
            NeighborhoodEvidence(
                reason=reason,
                chunk_id=chunk.chunk_id,
                source_path=chunk.source_path,
                heading_path=chunk.heading_path,
                text=text,
                field_hints=chunk.field_hints,
            )
        )
        return True

    same_source_candidates: list[tuple[str, ConceptChunkRecord]] = []
    cross_source_candidates: list[tuple[str, ConceptChunkRecord]] = []
    for reason in NEIGHBORHOOD_REASON_ORDER:
        for seed in seeds:
            for candidate in _candidates_for_reason(
                seed,
                reason,
                store=store,
                signal_terms=signal_terms,
                signal_fields=signal_fields,
                seed_ids=seed_ids,
                seed_source_paths=seed_source_paths,
            ):
                if reason in SAME_SOURCE_REASON_ORDER:
                    same_source_candidates.append((reason, candidate))
                else:
                    cross_source_candidates.append((reason, candidate))

    def add_candidates(
        candidates: list[tuple[str, ConceptChunkRecord]],
        *,
        max_to_add: int,
    ) -> int:
        added = 0
        for reason, candidate in candidates:
            if len(results) >= max_chunks or added >= max_to_add:
                break
            if add_candidate(candidate, reason):
                added += 1
        return added

    add_candidates(cross_source_candidates, max_to_add=cross_source_quota)
    add_candidates(same_source_candidates, max_to_add=same_source_cap)
    add_candidates(
        cross_source_candidates,
        max_to_add=max_chunks - len(results),
    )

    return results


def _candidates_for_reason(
    seed: ConceptChunkRecord,
    reason: str,
    *,
    store: ConceptCatalogStore,
    signal_terms: set[str],
    signal_fields: set[str],
    seed_ids: set[str],
    seed_source_paths: set[str],
) -> tuple[ConceptChunkRecord, ...]:
    if reason == "previous_chunk":
        chunk = store.get(seed.neighbors.previous_chunk_id or "")
        return (chunk,) if chunk is not None else ()
    if reason == "next_chunk":
        chunk = store.get(seed.neighbors.next_chunk_id or "")
        return (chunk,) if chunk is not None else ()
    if reason == "parent_heading":
        parent = seed.neighbors.parent_heading_path
        if not parent:
            return ()
        return tuple(
            chunk
            for chunk in store.by_source_path(seed.source_path)
            if chunk.heading_path == parent and chunk.chunk_id not in seed_ids
        )
    if reason == "sibling_heading":
        sibling_paths = set(seed.neighbors.sibling_heading_paths)
        parent = seed.neighbors.parent_heading_path
        return tuple(
            chunk
            for chunk in store.by_source_path(seed.source_path)
            if chunk.chunk_id not in seed_ids
            and (
                chunk.heading_path in sibling_paths
                or (parent and chunk.heading_path[:-1] == parent and chunk.heading_path != seed.heading_path)
            )
        )
    if reason == "same_source_nearby":
        return tuple(
            sorted(
                (
                    chunk
                    for chunk in store.by_source_path(seed.source_path)
                    if chunk.chunk_id not in seed_ids
                ),
                key=lambda chunk: (abs(chunk.chunk_index - seed.chunk_index), chunk.chunk_index, chunk.chunk_id),
            )
        )
    if reason == "same_folder_related":
        seed_folder = PurePosixPath(seed.source_path).parent.as_posix()
        candidates = [
            chunk
            for chunk in store.records
            if chunk.chunk_id not in seed_ids
            and chunk.source_path not in seed_source_paths
            and PurePosixPath(chunk.source_path).parent.as_posix() == seed_folder
            and _has_related_signal(chunk, signal_terms=signal_terms, signal_fields=signal_fields)
        ]
        return tuple(
            sorted(
                candidates,
                key=lambda chunk: (
                    -_term_overlap_count(chunk, signal_terms),
                    -len(set(chunk.field_hints).intersection(signal_fields)),
                    chunk.source_path,
                    chunk.chunk_index,
                    chunk.chunk_id,
                ),
            )
        )
    if reason == "cross_source_related":
        seed_folder = PurePosixPath(seed.source_path).parent.as_posix()
        candidates = [
            chunk
            for chunk in store.records
            if chunk.chunk_id not in seed_ids
            and chunk.source_path not in seed_source_paths
            and PurePosixPath(chunk.source_path).parent.as_posix() != seed_folder
            and _has_related_signal(
                chunk,
                signal_terms=signal_terms,
                signal_fields=signal_fields,
                require_strong=True,
            )
        ]
        return tuple(
            sorted(
                candidates,
                key=lambda chunk: (
                    -_term_overlap_count(chunk, signal_terms),
                    -len(set(chunk.field_hints).intersection(signal_fields)),
                    chunk.source_path,
                    chunk.chunk_index,
                    chunk.chunk_id,
                ),
            )
        )
    return ()


def _has_related_signal(
    chunk: ConceptChunkRecord,
    *,
    signal_terms: set[str],
    signal_fields: set[str],
    require_strong: bool = False,
) -> bool:
    term_overlap = _term_overlap_count(chunk, signal_terms)
    if term_overlap > 0:
        return True
    candidate_fields = set(chunk.field_hints) - COMMON_SIGNAL_FIELDS
    field_overlap = len(candidate_fields.intersection(signal_fields))
    if require_strong:
        return field_overlap >= 2
    if signal_terms:
        return False
    return field_overlap > 0


def _resolve_same_source_cap(
    max_chunks: int,
    *,
    max_same_source_neighbors: int | None,
) -> int:
    if max_same_source_neighbors is not None:
        return max(0, min(max_same_source_neighbors, max_chunks))
    return max(1, max_chunks // 2)


def _signal_term_set(terms: Iterable[str]) -> set[str]:
    return {
        term
        for term in extract_terms(" ".join(terms))
        if term not in METADATA_SIGNAL_TERMS
    }


def _term_overlap_count(chunk: ConceptChunkRecord, terms: set[str]) -> int:
    if not terms:
        return 0
    chunk_terms = set(
        term_counter(
            " ".join(
                (
                    chunk.source_path,
                    " ".join(chunk.heading_path),
                    chunk.text,
                )
            )
        )
    )
    normalized_terms = set(extract_terms(" ".join(terms)))
    return len(chunk_terms.intersection(normalized_terms))


def _trim_text(text: str, max_chars: int) -> str:
    if max_chars <= 0:
        return ""
    if len(text) <= max_chars:
        return text
    return text[:max_chars].rstrip()
