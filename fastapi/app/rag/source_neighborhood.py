from __future__ import annotations

from pathlib import PurePosixPath

from .concept_store import ConceptCatalogStore
from .schemas import ConceptChunkRecord, ConceptSearchHit, NeighborhoodEvidence
from .text import extract_terms, term_counter


NEIGHBORHOOD_REASON_ORDER = (
    "previous_chunk",
    "next_chunk",
    "parent_heading",
    "sibling_heading",
    "same_source_nearby",
    "same_folder_related",
)


def build_source_neighborhood(
    hits: list[ConceptSearchHit],
    *,
    store: ConceptCatalogStore,
    query_terms: tuple[str, ...] = (),
    field_hints: tuple[str, ...] = (),
    max_chunks: int = 8,
    max_chars: int = 6000,
    max_text_chars_per_item: int = 1200,
) -> list[NeighborhoodEvidence]:
    if max_chunks <= 0 or max_chars <= 0 or not hits or len(store) == 0:
        return []

    seeds = [hit.chunk for hit in hits]
    seed_ids = {chunk.chunk_id for chunk in seeds}
    seen_ids = set(seed_ids)
    used_chars = sum(
        len(_trim_text(hit.chunk.text, max_text_chars_per_item))
        for hit in hits
    )
    query_term_set = set(query_terms)
    matched_term_set = {term for hit in hits for term in hit.matched_terms}
    signal_terms = query_term_set | matched_term_set
    signal_fields = set(field_hints) - {"framework", "cs"}

    results: list[NeighborhoodEvidence] = []

    def add_candidate(chunk: ConceptChunkRecord | None, reason: str) -> None:
        nonlocal used_chars
        if chunk is None:
            return
        if chunk.chunk_id in seen_ids:
            return
        text = _trim_text(chunk.text, max_text_chars_per_item)
        if used_chars + len(text) > max_chars:
            return
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

    for reason in NEIGHBORHOOD_REASON_ORDER:
        if len(results) >= max_chunks:
            break
        for seed in seeds:
            if len(results) >= max_chunks:
                break
            for candidate in _candidates_for_reason(
                seed,
                reason,
                store=store,
                signal_terms=signal_terms,
                signal_fields=signal_fields,
                seed_ids=seed_ids,
            ):
                if len(results) >= max_chunks:
                    break
                add_candidate(candidate, reason)

    return results


def _candidates_for_reason(
    seed: ConceptChunkRecord,
    reason: str,
    *,
    store: ConceptCatalogStore,
    signal_terms: set[str],
    signal_fields: set[str],
    seed_ids: set[str],
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
            and chunk.source_path != seed.source_path
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
    return ()


def _has_related_signal(
    chunk: ConceptChunkRecord,
    *,
    signal_terms: set[str],
    signal_fields: set[str],
) -> bool:
    if _term_overlap_count(chunk, signal_terms) > 0:
        return True
    candidate_fields = set(chunk.field_hints) - {"framework", "cs"}
    return bool(candidate_fields.intersection(signal_fields))


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
