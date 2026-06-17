from __future__ import annotations

import math
from collections import Counter
from typing import Iterable

from app.scoring.schemas import ReportChunk

from .concept_embeddings import (
    EmbeddingClient,
    GeminiEmbeddingClient,
    build_query_embedding_input,
)
from .concept_store import ConceptCatalogStore, load_concept_catalog_store
from .embedding_store import ConceptEmbeddingStore, load_concept_embedding_store
from .schemas import (
    ConceptChunkRecord,
    ConceptSearchHit,
    EvidenceMatch,
    ReportEvidenceBundle,
)
from .source_neighborhood import build_source_neighborhood
from .text import clean_markdown, extract_terms, term_counter


DEFAULT_TOP_K = 5
DEFAULT_MAX_NEIGHBORHOOD_CHUNKS = 8
DEFAULT_MAX_BUNDLE_CHARS = 6000
DEFAULT_MAX_TEXT_CHARS_PER_ITEM = 1200
STALE_EMBEDDING_REASONS = {
    "stale_content_hash",
    "stale_model",
    "stale_output_dimensionality",
}


class ConceptCatalogSearcher:
    def __init__(
        self,
        *,
        store: ConceptCatalogStore,
        embedding_store: ConceptEmbeddingStore | None = None,
        client: EmbeddingClient | None = None,
    ):
        self.store = store
        self.embedding_store = embedding_store
        self.client = client

    def search(
        self,
        query: str,
        *,
        topic_hints: tuple[str, ...] = (),
        field_hints: tuple[str, ...] = (),
        limit: int = DEFAULT_TOP_K,
        min_score: float = 1.0,
        allow_keyword_fallback: bool = True,
    ) -> list[ConceptSearchHit]:
        return search_concept_chunks(
            query,
            store=self.store,
            embedding_store=self.embedding_store,
            client=self.client,
            topic_hints=topic_hints,
            field_hints=field_hints,
            limit=limit,
            min_score=min_score,
            allow_keyword_fallback=allow_keyword_fallback,
        )


def search_concept_chunks(
    query: str,
    *,
    store: ConceptCatalogStore | None = None,
    embedding_store: ConceptEmbeddingStore | None = None,
    client: EmbeddingClient | None = None,
    topic_hints: tuple[str, ...] = (),
    field_hints: tuple[str, ...] = (),
    limit: int = DEFAULT_TOP_K,
    min_score: float = 1.0,
    allow_keyword_fallback: bool = True,
) -> list[ConceptSearchHit]:
    catalog_store = _resolve_store(store, embedding_store)
    if limit <= 0 or not clean_markdown(query) or len(catalog_store) == 0:
        return []

    index = embedding_store
    if index is None:
        index = load_concept_embedding_store(store=catalog_store)

    query_input = build_query_embedding_input(
        query,
        topic_hints=topic_hints,
        field_hints=field_hints,
    )
    if len(index) == 0:
        return _keyword_fallback(
            query_input,
            store=catalog_store,
            limit=limit,
            min_score=min_score,
            field_hints=field_hints,
            excluded_chunk_ids=_stale_chunk_ids(index),
        ) if allow_keyword_fallback else []

    embedder = client or GeminiEmbeddingClient()
    try:
        query_embedding = embedder.embed_query(query_input)
    except Exception:
        return _keyword_fallback(
            query_input,
            store=catalog_store,
            limit=limit,
            min_score=min_score,
            field_hints=field_hints,
            excluded_chunk_ids=_stale_chunk_ids(index),
        ) if allow_keyword_fallback else []

    hits: list[ConceptSearchHit] = []
    query_terms = extract_terms(query_input)
    for item in index.items:
        similarity = cosine_similarity(query_embedding, item.embedding)
        if similarity is None:
            continue
        matched_terms = _matched_terms(query_terms, item.chunk)
        field_overlap = len(set(field_hints).intersection(item.chunk.field_hints))
        score = (similarity * 10.0) + (field_overlap * 0.01) + (len(matched_terms) * 0.001)
        if score >= min_score:
            hits.append(
                ConceptSearchHit(
                    chunk=item.chunk,
                    score=score,
                    search_mode="embedding",
                    matched_terms=matched_terms,
                )
            )

    return _sorted_hits(hits)[:limit]


def build_report_evidence_bundle(
    report_chunk: ReportChunk,
    *,
    store: ConceptCatalogStore | None = None,
    embedding_store: ConceptEmbeddingStore | None = None,
    client: EmbeddingClient | None = None,
    top_k: int = DEFAULT_TOP_K,
    max_neighborhood_chunks: int = DEFAULT_MAX_NEIGHBORHOOD_CHUNKS,
    max_bundle_chars: int = DEFAULT_MAX_BUNDLE_CHARS,
    max_text_chars_per_item: int = DEFAULT_MAX_TEXT_CHARS_PER_ITEM,
    allow_keyword_fallback: bool = True,
) -> ReportEvidenceBundle:
    catalog_store = _resolve_store(store, embedding_store)
    if top_k <= 0 or len(catalog_store) == 0 or not report_chunk.text.strip():
        return ReportEvidenceBundle(
            report_chunk_id=report_chunk.report_chunk_id,
            query_text=report_chunk.text,
            topic_hints=report_chunk.topic_hints,
            field_hints=report_chunk.field_hints,
        )

    hits = search_concept_chunks(
        report_chunk.text,
        store=catalog_store,
        embedding_store=embedding_store,
        client=client,
        topic_hints=report_chunk.topic_hints,
        field_hints=report_chunk.field_hints,
        limit=top_k,
        allow_keyword_fallback=allow_keyword_fallback,
    )
    query_input = build_query_embedding_input(
        report_chunk.text,
        topic_hints=report_chunk.topic_hints,
        field_hints=report_chunk.field_hints,
    )
    matches = _evidence_matches(
        hits,
        max_bundle_chars=max_bundle_chars,
        max_text_chars_per_item=max_text_chars_per_item,
    )
    neighborhood = build_source_neighborhood(
        hits,
        store=catalog_store,
        query_terms=tuple(extract_terms(query_input)),
        field_hints=report_chunk.field_hints,
        max_chunks=max_neighborhood_chunks,
        max_chars=max_bundle_chars,
        max_text_chars_per_item=max_text_chars_per_item,
    )
    match_ids = {match.chunk_id for match in matches}
    neighborhood = [
        item
        for item in neighborhood
        if item.chunk_id not in match_ids
    ]

    return ReportEvidenceBundle(
        report_chunk_id=report_chunk.report_chunk_id,
        query_text=report_chunk.text,
        topic_hints=report_chunk.topic_hints,
        field_hints=report_chunk.field_hints,
        matches=tuple(matches),
        neighborhood=tuple(neighborhood),
    )


def build_report_evidence_bundles(
    report_chunks: list[ReportChunk],
    *,
    store: ConceptCatalogStore | None = None,
    embedding_store: ConceptEmbeddingStore | None = None,
    client: EmbeddingClient | None = None,
    top_k: int = DEFAULT_TOP_K,
    max_neighborhood_chunks: int = DEFAULT_MAX_NEIGHBORHOOD_CHUNKS,
    max_bundle_chars: int = DEFAULT_MAX_BUNDLE_CHARS,
    max_text_chars_per_item: int = DEFAULT_MAX_TEXT_CHARS_PER_ITEM,
    allow_keyword_fallback: bool = True,
) -> list[ReportEvidenceBundle]:
    catalog_store = _resolve_store(store, embedding_store)
    return [
        build_report_evidence_bundle(
            report_chunk,
            store=catalog_store,
            embedding_store=embedding_store,
            client=client,
            top_k=top_k,
            max_neighborhood_chunks=max_neighborhood_chunks,
            max_bundle_chars=max_bundle_chars,
            max_text_chars_per_item=max_text_chars_per_item,
            allow_keyword_fallback=allow_keyword_fallback,
        )
        for report_chunk in report_chunks
    ]


def cosine_similarity(
    left: Iterable[float],
    right: Iterable[float],
) -> float | None:
    left_values = tuple(float(value) for value in left)
    right_values = tuple(float(value) for value in right)
    if not left_values or len(left_values) != len(right_values):
        return None
    dot = sum(a * b for a, b in zip(left_values, right_values))
    left_norm = math.sqrt(sum(value * value for value in left_values))
    right_norm = math.sqrt(sum(value * value for value in right_values))
    if left_norm == 0.0 or right_norm == 0.0:
        return None
    return dot / (left_norm * right_norm)


def _resolve_store(
    store: ConceptCatalogStore | None,
    embedding_store: ConceptEmbeddingStore | None,
) -> ConceptCatalogStore:
    if store is not None:
        return store
    if embedding_store is not None:
        return ConceptCatalogStore(tuple(item.chunk for item in embedding_store.items))
    return load_concept_catalog_store()


def _keyword_fallback(
    query_input: str,
    *,
    store: ConceptCatalogStore,
    limit: int,
    min_score: float,
    field_hints: tuple[str, ...],
    excluded_chunk_ids: set[str] | None = None,
) -> list[ConceptSearchHit]:
    if limit <= 0:
        return []
    query_terms = extract_terms(query_input)
    if not query_terms:
        return []
    excluded = excluded_chunk_ids or set()

    hits: list[ConceptSearchHit] = []
    for chunk in store.records:
        if chunk.chunk_id in excluded:
            continue
        score, matched_terms = _score_keywords(query_terms, chunk)
        field_overlap = len(set(field_hints).intersection(chunk.field_hints))
        if field_overlap:
            score += 0.25 * field_overlap
        if score >= min_score:
            hits.append(
                ConceptSearchHit(
                    chunk=chunk,
                    score=score,
                    search_mode="keyword_fallback",
                    matched_terms=matched_terms,
                )
            )
    return _sorted_hits(hits)[:limit]


def _stale_chunk_ids(index: ConceptEmbeddingStore) -> set[str]:
    return {
        issue.chunk_id
        for issue in index.issues
        if issue.chunk_id is not None and issue.reason in STALE_EMBEDDING_REASONS
    }


def _score_keywords(
    query_terms: list[str],
    chunk: ConceptChunkRecord,
) -> tuple[float, tuple[str, ...]]:
    target_terms = term_counter(
        " ".join(
            (
                chunk.source_path,
                " ".join(chunk.heading_path),
                " ".join(chunk.field_hints),
                chunk.text,
            )
        )
    )
    target_text = clean_markdown(
        " ".join((chunk.source_path, " ".join(chunk.heading_path), chunk.text))
    ).lower()
    score = 0.0
    matched: list[str] = []
    for term in query_terms:
        count = target_terms.get(term, 0)
        if count:
            score += min(count, 4)
            matched.append(term)
        elif term in target_text:
            score += 0.5
            matched.append(term)
    unique_matches = len(set(matched))
    if unique_matches >= 2:
        score += unique_matches * 0.75
    return score, tuple(sorted(set(matched)))


def _matched_terms(
    query_terms: list[str],
    chunk: ConceptChunkRecord,
) -> tuple[str, ...]:
    if not query_terms:
        return ()
    target_terms = set(
        term_counter(
            " ".join(
                (
                    chunk.source_path,
                    " ".join(chunk.heading_path),
                    " ".join(chunk.field_hints),
                    chunk.text,
                )
            )
        )
    )
    target_text = clean_markdown(chunk.text).lower()
    return tuple(
        sorted(
            {
                term
                for term in query_terms
                if term in target_terms or term in target_text
            }
        )
    )


def _sorted_hits(hits: list[ConceptSearchHit]) -> list[ConceptSearchHit]:
    return sorted(
        hits,
        key=lambda hit: (
            -hit.score,
            hit.chunk.source_path,
            hit.chunk.chunk_index,
            hit.chunk.chunk_id,
        ),
    )


def _evidence_matches(
    hits: list[ConceptSearchHit],
    *,
    max_bundle_chars: int,
    max_text_chars_per_item: int,
) -> list[EvidenceMatch]:
    matches: list[EvidenceMatch] = []
    used_chars = 0
    for hit in hits:
        if used_chars >= max_bundle_chars:
            break
        available = max_bundle_chars - used_chars
        text_limit = min(max_text_chars_per_item, available)
        text = _trim_text(hit.chunk.text, text_limit)
        used_chars += len(text)
        matches.append(
            EvidenceMatch(
                chunk_id=hit.chunk.chunk_id,
                score=hit.score,
                search_mode=hit.search_mode,
                matched_terms=hit.matched_terms,
                source_path=hit.chunk.source_path,
                heading_path=hit.chunk.heading_path,
                text=text,
                field_hints=hit.chunk.field_hints,
            )
        )
    return matches


def _trim_text(text: str, max_chars: int) -> str:
    if max_chars <= 0:
        return ""
    if len(text) <= max_chars:
        return text
    return text[:max_chars].rstrip()
