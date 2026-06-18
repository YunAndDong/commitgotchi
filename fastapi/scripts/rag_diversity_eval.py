from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import math
import subprocess
import sys
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path, PurePosixPath
from typing import Any, Iterable, Mapping, Sequence


FASTAPI_ROOT = Path(__file__).resolve().parents[1]
if str(FASTAPI_ROOT) not in sys.path:
    sys.path.insert(0, str(FASTAPI_ROOT))

from app.rag.concept_search import build_report_evidence_bundle  # noqa: E402
from app.rag.concept_embeddings import GeminiEmbeddingClient  # noqa: E402
from app.rag.concept_store import (  # noqa: E402
    DEFAULT_CATALOG_PATH,
    ConceptCatalogStore,
    load_concept_catalog_store,
)
from app.rag.embedding_store import (  # noqa: E402
    ConceptEmbeddingStore,
    EmbeddedConceptChunk,
    load_concept_embedding_store,
)
from app.rag.problem_bank_search import ProblemBankSearcher  # noqa: E402
from app.rag.problem_embedding_store import (  # noqa: E402
    EmbeddedProblem,
    ProblemEmbeddingStore,
    load_problem_embedding_store,
)
from app.rag.problem_embeddings import (  # noqa: E402
    DEFAULT_PROBLEM_EMBEDDINGS_PATH,
    build_problem_embedding_input,
)
from app.rag.problem_bank_store import (  # noqa: E402
    DEFAULT_CATALOG_PATH as DEFAULT_PROBLEM_BANK_PATH,
    ProblemBankStore,
    load_problem_bank_store,
)
from app.rag.schemas import SCORE_FIELDS, ConceptChunkRecord, ProblemRecord  # noqa: E402
from app.rag.text import extract_terms  # noqa: E402
from app.scoring.schemas import ReportChunk  # noqa: E402


DEFAULT_QUERIES_PATH = FASTAPI_ROOT / "tests" / "fixtures" / "rag" / "eval" / "queries.jsonl"
DEFAULT_REPORT_MD_PATH = FASTAPI_ROOT / "data" / "rag" / "reports" / "rag-diversity-baseline.md"
DEFAULT_REPORT_JSON_PATH = FASTAPI_ROOT / "data" / "rag" / "reports" / "rag-diversity-baseline.json"
ALLOWED_SOURCE_TIERS = frozenset(("core", "low", "appendix"))
FAKE_EMBEDDING_MODEL = "fake-hash-embedding"
FAKE_EMBEDDING_DIMENSIONS = 32


@dataclass(frozen=True)
class EvalQuery:
    query_id: str
    report_text: str
    expected_topics: tuple[str, ...] = field(default_factory=tuple)
    expected_fields: tuple[str, ...] = field(default_factory=tuple)
    source_path: str | None = None
    source_tier: str | None = None
    relevant_source_paths: tuple[str, ...] = field(default_factory=tuple)
    relevance_grades: dict[str, int] = field(default_factory=dict)
    notes: str = ""

    def to_json_dict(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "queryId": self.query_id,
            "reportText": self.report_text,
            "expectedTopics": list(self.expected_topics),
            "expectedFields": list(self.expected_fields),
        }
        if self.source_path is not None:
            payload["sourcePath"] = self.source_path
        if self.source_tier is not None:
            payload["sourceTier"] = self.source_tier
        if self.relevant_source_paths:
            payload["relevantSourcePaths"] = list(self.relevant_source_paths)
        if self.relevance_grades:
            payload["relevanceGrades"] = dict(self.relevance_grades)
        if self.notes:
            payload["notes"] = self.notes
        return payload


@dataclass(frozen=True)
class TierAValidationSummary:
    record_count: int
    catalog_source_count: int
    duplicate_source_paths: tuple[str, ...]
    missing_source_paths: tuple[str, ...]
    extra_source_paths: tuple[str, ...]
    is_sorted: bool

    def to_json_dict(self) -> dict[str, Any]:
        return {
            "recordCount": self.record_count,
            "catalogSourceCount": self.catalog_source_count,
            "duplicateSourcePaths": list(self.duplicate_source_paths),
            "missingSourcePaths": list(self.missing_source_paths),
            "extraSourcePaths": list(self.extra_source_paths),
            "isSorted": self.is_sorted,
        }


@dataclass(frozen=True)
class RetrievedItem:
    query_id: str
    rank: int
    item_id: str
    source_path: str
    field_hints: tuple[str, ...] = field(default_factory=tuple)

    def to_json_dict(self) -> dict[str, Any]:
        return {
            "queryId": self.query_id,
            "rank": self.rank,
            "itemId": self.item_id,
            "sourcePath": self.source_path,
            "fieldHints": list(self.field_hints),
        }


@dataclass(frozen=True)
class ConceptEvaluationResult:
    top_k: dict[str, tuple[RetrievedItem, ...]]
    bundle: dict[str, tuple[RetrievedItem, ...]]
    neighborhoods: dict[str, tuple[RetrievedItem, ...]]


@dataclass(frozen=True)
class ProblemEvaluationResult:
    top_k: dict[str, tuple[RetrievedItem, ...]]


class FakeEmbeddingClient:
    """Deterministic local embedder for tests and no-API baseline runs."""

    def __init__(self, dimensions: int = FAKE_EMBEDDING_DIMENSIONS):
        self.dimensions = dimensions

    def embed_document(self, text: str) -> tuple[float, ...]:
        return fake_embedding(text, dimensions=self.dimensions)

    def embed_query(self, text: str) -> tuple[float, ...]:
        return fake_embedding(text, dimensions=self.dimensions)


def fake_embedding(text: str, *, dimensions: int = FAKE_EMBEDDING_DIMENSIONS) -> tuple[float, ...]:
    if dimensions <= 0:
        raise ValueError("dimensions must be positive")
    vector = [0.0] * dimensions
    terms = extract_terms(text)
    if not terms:
        vector[0] = 1.0
        return tuple(vector)
    for term in terms:
        digest = hashlib.sha256(term.encode("utf-8")).digest()
        bucket = int.from_bytes(digest[:4], "big") % dimensions
        vector[bucket] += 1.0
    norm = math.sqrt(sum(value * value for value in vector))
    if norm == 0.0:
        vector[0] = 1.0
        return tuple(vector)
    return tuple(value / norm for value in vector)


def generate_fake_embedding_store(
    store: ConceptCatalogStore,
    *,
    dimensions: int = FAKE_EMBEDDING_DIMENSIONS,
) -> ConceptEmbeddingStore:
    items = tuple(
        EmbeddedConceptChunk(
            chunk=chunk,
            embedding=fake_embedding(_fake_embedding_input(chunk), dimensions=dimensions),
        )
        for chunk in store.records
    )
    return ConceptEmbeddingStore(
        items=items,
        expected_model=FAKE_EMBEDDING_MODEL,
        expected_dimensionality=dimensions,
    )


def generate_fake_problem_embedding_store(
    store: ProblemBankStore,
    *,
    dimensions: int = FAKE_EMBEDDING_DIMENSIONS,
) -> ProblemEmbeddingStore:
    items = tuple(
        EmbeddedProblem(
            problem=problem,
            embedding=fake_embedding(_fake_problem_embedding_input(problem), dimensions=dimensions),
        )
        for problem in store.records
    )
    return ProblemEmbeddingStore(
        items=items,
        expected_model=FAKE_EMBEDDING_MODEL,
        expected_dimensionality=dimensions,
    )


def load_tier_a_queries(path: Path = DEFAULT_QUERIES_PATH) -> tuple[EvalQuery, ...]:
    return tuple(
        _eval_query_from_payload(payload, path=path, line_number=line_number, require_labels=True)
        for line_number, payload in _read_jsonl_objects(path)
    )


def load_unlabeled_queries(path: Path) -> tuple[EvalQuery, ...]:
    return tuple(
        _eval_query_from_payload(payload, path=path, line_number=line_number, require_labels=False)
        for line_number, payload in _read_jsonl_objects(path)
    )


def validate_tier_a_fixture(
    queries: Sequence[EvalQuery],
    catalog_source_paths: Iterable[str],
    *,
    expected_count: int | None = None,
) -> TierAValidationSummary:
    catalog_sources = tuple(sorted(set(catalog_source_paths)))
    expected = expected_count if expected_count is not None else len(catalog_sources)
    query_sources = tuple(query.source_path or "" for query in queries)
    source_counts = Counter(query_sources)
    duplicates = tuple(sorted(source for source, count in source_counts.items() if source and count > 1))
    missing = tuple(sorted(set(catalog_sources) - set(query_sources)))
    extra = tuple(sorted(set(query_sources) - set(catalog_sources)))
    is_sorted = query_sources == tuple(sorted(query_sources))
    summary = TierAValidationSummary(
        record_count=len(queries),
        catalog_source_count=len(catalog_sources),
        duplicate_source_paths=duplicates,
        missing_source_paths=missing,
        extra_source_paths=extra,
        is_sorted=is_sorted,
    )

    errors: list[str] = []
    if len(queries) != expected:
        errors.append(f"expected {expected} Tier A records, got {len(queries)}")
    if len(catalog_sources) != expected:
        errors.append(f"expected {expected} catalog sources, got {len(catalog_sources)}")
    if duplicates:
        errors.append(f"duplicate sourcePath values: {', '.join(duplicates[:5])}")
    if missing:
        errors.append(f"missing catalog sourcePath values: {', '.join(missing[:5])}")
    if extra:
        errors.append(f"extra fixture sourcePath values: {', '.join(extra[:5])}")
    if not is_sorted:
        errors.append("fixture records must be sorted by sourcePath")

    for query in queries:
        errors.extend(_validate_tier_a_record(query))

    if errors:
        raise ValueError("; ".join(errors))
    return summary


def generate_tier_c_queries(
    records: Iterable[ConceptChunkRecord],
    *,
    min_queries: int = 150,
    max_queries: int = 300,
) -> tuple[EvalQuery, ...]:
    if min_queries < 0 or max_queries < 0:
        raise ValueError("query limits must be non-negative")
    if min_queries > max_queries:
        raise ValueError("min_queries must be <= max_queries")

    sorted_records = sorted(
        records,
        key=lambda chunk: (chunk.source_path, chunk.heading_path, chunk.chunk_index, chunk.chunk_id),
    )
    if not sorted_records or max_queries == 0:
        return ()

    templates = (
        "{topic} 내용을 면접 답변 관점에서 다시 정리하고 싶다.",
        "{topic} 개념이 헷갈리는데 핵심 흐름과 주의할 점을 알고 싶다.",
        "{topic}을 공부했는데 실제 질문으로 나오면 어떤 식으로 설명해야 할지 막힌다.",
    )
    queries: list[EvalQuery] = []
    seen_text: set[str] = set()
    for template_index, template in enumerate(templates):
        for chunk in sorted_records:
            if len(queries) >= max_queries:
                break
            topic = _topic_from_chunk(chunk)
            report_text = template.format(topic=topic)
            dedupe_key = f"{report_text}\0{','.join(chunk.field_hints)}"
            if dedupe_key in seen_text:
                continue
            seen_text.add(dedupe_key)
            queries.append(
                EvalQuery(
                    query_id=f"tier-c-{len(queries) + 1:03d}",
                    report_text=report_text,
                    expected_topics=(topic,),
                    expected_fields=tuple(sorted(set(chunk.field_hints))),
                )
            )
        if len(queries) >= max_queries:
            break

    if len(queries) < min_queries:
        for chunk in sorted_records:
            if len(queries) >= min_queries or len(queries) >= max_queries:
                break
            topic = _topic_from_chunk(chunk)
            queries.append(
                EvalQuery(
                    query_id=f"tier-c-{len(queries) + 1:03d}",
                    report_text=f"{topic} 관련 개념을 다른 자료와 연결해서 복습하고 싶다.",
                    expected_topics=(topic,),
                    expected_fields=tuple(sorted(set(chunk.field_hints))),
                )
            )
    return tuple(queries)


def evaluate_concept_queries(
    queries: Sequence[EvalQuery],
    *,
    store: ConceptCatalogStore,
    embedding_store: ConceptEmbeddingStore | None,
    client: Any | None,
    top_k: int = 5,
    max_neighborhood_chunks: int = 8,
    known_source_paths: Iterable[str] = (),
) -> ConceptEvaluationResult:
    known_sources = tuple(known_source_paths) or _catalog_source_paths(store)
    top_k_results: dict[str, tuple[RetrievedItem, ...]] = {}
    bundle_results: dict[str, tuple[RetrievedItem, ...]] = {}
    neighborhood_results: dict[str, tuple[RetrievedItem, ...]] = {}

    for query in queries:
        report_chunk = ReportChunk(
            report_chunk_id=query.query_id,
            text=query.report_text,
            char_start=0,
            char_end=len(query.report_text),
            topic_hints=query.expected_topics,
            field_hints=query.expected_fields,
        )
        bundle = build_report_evidence_bundle(
            report_chunk,
            store=store,
            embedding_store=embedding_store,
            client=client,
            top_k=top_k,
            max_neighborhood_chunks=max_neighborhood_chunks,
        )
        top_items = tuple(
            RetrievedItem(
                query_id=query.query_id,
                rank=index,
                item_id=match.chunk_id,
                source_path=normalize_source_path(match.source_path, known_sources),
                field_hints=tuple(match.field_hints),
            )
            for index, match in enumerate(bundle.matches, start=1)
        )
        neighbor_items = tuple(
            RetrievedItem(
                query_id=query.query_id,
                rank=index,
                item_id=item.chunk_id,
                source_path=normalize_source_path(item.source_path, known_sources),
                field_hints=tuple(item.field_hints),
            )
            for index, item in enumerate(bundle.neighborhood, start=1)
        )
        top_k_results[query.query_id] = top_items
        neighborhood_results[query.query_id] = neighbor_items
        bundle_results[query.query_id] = top_items + tuple(
            RetrievedItem(
                query_id=item.query_id,
                rank=len(top_items) + index,
                item_id=item.item_id,
                source_path=item.source_path,
                field_hints=item.field_hints,
            )
            for index, item in enumerate(neighbor_items, start=1)
        )

    return ConceptEvaluationResult(
        top_k=top_k_results,
        bundle=bundle_results,
        neighborhoods=neighborhood_results,
    )


def evaluate_problem_queries(
    queries: Sequence[EvalQuery],
    *,
    store: ProblemBankStore,
    embedding_store: ProblemEmbeddingStore | None = None,
    client: Any | None = None,
    top_k: int = 3,
    known_source_paths: Iterable[str] = (),
) -> ProblemEvaluationResult:
    known_sources = tuple(known_source_paths)
    searcher = ProblemBankSearcher(
        store,
        embedding_store=embedding_store,
        client=client,
    )
    results: dict[str, tuple[RetrievedItem, ...]] = {}
    for query in queries:
        hits = searcher.search(query.report_text, limit=top_k)
        results[query.query_id] = tuple(
            RetrievedItem(
                query_id=query.query_id,
                rank=index,
                item_id=f"problem:{hit.problem.problem_id}",
                source_path=normalize_source_path(hit.problem.source_path, known_sources),
                field_hints=tuple(hit.problem.fields),
            )
            for index, hit in enumerate(hits, start=1)
        )
    return ProblemEvaluationResult(top_k=results)


def calculate_diversity_metrics(
    query_ids: Sequence[str],
    results_by_query: Mapping[str, Sequence[RetrievedItem]],
    *,
    total_source_paths: Iterable[str],
    neighborhoods_by_query: Mapping[str, Sequence[RetrievedItem]] | None = None,
    seed_results_by_query: Mapping[str, Sequence[RetrievedItem]] | None = None,
) -> dict[str, Any]:
    total_sources = set(total_source_paths)
    retrieved_sources: set[str] = set()
    per_query: list[dict[str, Any]] = []

    for query_id in query_ids:
        items = tuple(results_by_query.get(query_id, ()))
        sources = [item.source_path for item in items if item.source_path]
        retrieved_sources.update(sources)
        source_counts = Counter(sources)
        result_count = len(sources)
        distinct_source_count = len(source_counts)
        most_common_source_ratio = (
            max(source_counts.values()) / result_count if result_count else 0.0
        )
        source_hhi = (
            sum((count / result_count) ** 2 for count in source_counts.values())
            if result_count
            else 0.0
        )
        entropy = _normalized_entropy(source_counts)
        field_coverage = len({field for item in items for field in item.field_hints})
        folder_coverage = len({_top_level_folder(source) for source in sources if source})

        neighbor_items = tuple(neighborhoods_by_query.get(query_id, ())) if neighborhoods_by_query else ()
        seed_items = tuple(
            seed_results_by_query.get(query_id, ())
            if seed_results_by_query is not None
            else results_by_query.get(query_id, ())
        )
        seed_sources = {item.source_path for item in seed_items if item.source_path}
        same_source_neighbor_ratio = (
            sum(1 for item in neighbor_items if item.source_path in seed_sources) / len(neighbor_items)
            if neighbor_items
            else 0.0
        )

        per_query.append(
            {
                "queryId": query_id,
                "resultCount": result_count,
                "distinctSourceCount": distinct_source_count,
                "mostCommonSourceRatio": _round(most_common_source_ratio),
                "sourceHHI": _round(source_hhi),
                "normalizedSourceEntropy": _round(entropy),
                "fieldCoverage": field_coverage,
                "folderCoverage": folder_coverage,
                "neighborCount": len(neighbor_items),
                "sameSourceNeighborRatio": _round(same_source_neighbor_ratio),
            }
        )

    aggregate = {
        "queryCount": len(query_ids),
        "avgResultCount": _round(_avg(item["resultCount"] for item in per_query)),
        "avgDistinctSourceCount": _round(_avg(item["distinctSourceCount"] for item in per_query)),
        "avgMostCommonSourceRatio": _round(_avg(item["mostCommonSourceRatio"] for item in per_query)),
        "avgSourceHHI": _round(_avg(item["sourceHHI"] for item in per_query)),
        "avgNormalizedSourceEntropy": _round(_avg(item["normalizedSourceEntropy"] for item in per_query)),
        "avgFieldCoverage": _round(_avg(item["fieldCoverage"] for item in per_query)),
        "avgFolderCoverage": _round(_avg(item["folderCoverage"] for item in per_query)),
        "avgSameSourceNeighborRatio": _round(_avg(item["sameSourceNeighborRatio"] for item in per_query)),
        "catalogCoverage": _round(
            len(retrieved_sources.intersection(total_sources)) / len(total_sources)
            if total_sources
            else 0.0
        ),
        "retrievedDistinctSourceCount": len(retrieved_sources),
        "totalSourceCount": len(total_sources),
    }
    return {"aggregate": aggregate, "perQuery": per_query}


def calculate_relevance_metrics(
    queries: Sequence[EvalQuery],
    results_by_query: Mapping[str, Sequence[RetrievedItem]],
    *,
    k: int,
) -> dict[str, Any]:
    labeled_queries = tuple(query for query in queries if query.relevant_source_paths)
    per_query: list[dict[str, Any]] = []
    all_relevant_sources = {
        source
        for query in labeled_queries
        for source in query.relevant_source_paths
    }
    found_relevant_sources: set[str] = set()
    missed_query_ids: list[str] = []
    ndcg_values: list[float] = []

    for query in labeled_queries:
        results = tuple(results_by_query.get(query.query_id, ()))[:k]
        relevant_sources = set(query.relevant_source_paths)
        found_for_query: set[str] = set()
        first_relevant_rank = 0
        for rank, item in enumerate(results, start=1):
            matched_source = _matching_relevant_source(item.source_path, relevant_sources)
            if matched_source is None:
                continue
            found_for_query.add(matched_source)
            found_relevant_sources.add(matched_source)
            if first_relevant_rank == 0:
                first_relevant_rank = rank

        recall = len(found_for_query) / len(relevant_sources) if relevant_sources else 0.0
        hit = 1.0 if found_for_query else 0.0
        mrr = 1.0 / first_relevant_rank if first_relevant_rank else 0.0
        item_metrics: dict[str, Any] = {
            "queryId": query.query_id,
            "recallAtK": _round(recall),
            "hitAtK": _round(hit),
            "mrr": _round(mrr),
            "firstRelevantRank": first_relevant_rank,
            "relevantSourceCount": len(relevant_sources),
            "retrievedRelevantSourceCount": len(found_for_query),
        }
        if query.relevance_grades:
            ndcg = _ndcg_at_k(results, query.relevance_grades, k)
            ndcg_values.append(ndcg)
            item_metrics["ndcgAtK"] = _round(ndcg)
        if not found_for_query:
            missed_query_ids.append(query.query_id)
        per_query.append(item_metrics)

    aggregate = {
        "queryCount": len(labeled_queries),
        "recallAtK": _round(_avg(item["recallAtK"] for item in per_query)),
        "hitRateAtK": _round(_avg(item["hitAtK"] for item in per_query)),
        "mrr": _round(_avg(item["mrr"] for item in per_query)),
        "sourceCoverage": _round(
            len(found_relevant_sources) / len(all_relevant_sources)
            if all_relevant_sources
            else 0.0
        ),
        "foundRelevantSourceCount": len(found_relevant_sources),
        "totalRelevantSourceCount": len(all_relevant_sources),
    }
    if ndcg_values:
        aggregate["ndcgAtK"] = _round(_avg(ndcg_values))

    return {
        "aggregate": aggregate,
        "perQuery": per_query,
        "missedQueryIds": missed_query_ids,
    }


def run_baseline_evaluation(
    *,
    catalog_path: Path = DEFAULT_CATALOG_PATH,
    problem_bank_path: Path = DEFAULT_PROBLEM_BANK_PATH,
    queries_path: Path = DEFAULT_QUERIES_PATH,
    embeddings_path: Path | None = None,
    problem_embeddings_path: Path | None = None,
    tier_c_queries_path: Path | None = None,
    tier_c_min_queries: int = 150,
    tier_c_max_queries: int = 300,
    embedding_mode: str = "fake",
    top_k: int = 5,
    problem_top_k: int = 3,
    max_neighborhood_chunks: int = 8,
) -> dict[str, Any]:
    catalog_store = load_concept_catalog_store(catalog_path)
    problem_store = load_problem_bank_store(problem_bank_path)
    catalog_sources = _catalog_source_paths(catalog_store)
    problem_sources = _problem_source_paths(problem_store, known_source_paths=catalog_sources)

    tier_a_queries = load_tier_a_queries(queries_path)
    validation = validate_tier_a_fixture(tier_a_queries, catalog_sources)
    tier_c_queries = (
        load_unlabeled_queries(tier_c_queries_path)
        if tier_c_queries_path is not None
        else generate_tier_c_queries(
            catalog_store.records,
            min_queries=tier_c_min_queries,
            max_queries=tier_c_max_queries,
        )
    )
    if not (0 <= len(tier_c_queries) <= tier_c_max_queries):
        raise ValueError("Tier C query count is outside the requested maximum")
    if len(catalog_store) and len(tier_c_queries) < tier_c_min_queries:
        raise ValueError(
            f"Tier C query count must be at least {tier_c_min_queries}, got {len(tier_c_queries)}"
        )

    embedding_store, client, embedding_metadata = _prepare_embedding_components(
        embedding_mode,
        catalog_store=catalog_store,
        embeddings_path=embeddings_path,
    )
    problem_embedding_store, problem_client, problem_embedding_metadata = _prepare_problem_embedding_components(
        embedding_mode,
        problem_store=problem_store,
        problem_embeddings_path=problem_embeddings_path,
    )

    concept_tier_a = evaluate_concept_queries(
        tier_a_queries,
        store=catalog_store,
        embedding_store=embedding_store,
        client=client,
        top_k=top_k,
        max_neighborhood_chunks=max_neighborhood_chunks,
        known_source_paths=catalog_sources,
    )
    problem_tier_a = evaluate_problem_queries(
        tier_a_queries,
        store=problem_store,
        embedding_store=problem_embedding_store,
        client=problem_client,
        top_k=problem_top_k,
        known_source_paths=catalog_sources,
    )
    concept_tier_c = evaluate_concept_queries(
        tier_c_queries,
        store=catalog_store,
        embedding_store=embedding_store,
        client=client,
        top_k=top_k,
        max_neighborhood_chunks=max_neighborhood_chunks,
        known_source_paths=catalog_sources,
    )
    problem_tier_c = evaluate_problem_queries(
        tier_c_queries,
        store=problem_store,
        embedding_store=problem_embedding_store,
        client=problem_client,
        top_k=problem_top_k,
        known_source_paths=catalog_sources,
    )

    tier_a_query_ids = [query.query_id for query in tier_a_queries]
    tier_c_query_ids = [query.query_id for query in tier_c_queries]
    report: dict[str, Any] = {
        "metadata": {
            "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
            "gitCommit": _git_commit(),
            "catalogPath": _relative_path(catalog_path),
            "queryFixturePath": _relative_path(queries_path),
            "problemBankPath": _relative_path(problem_bank_path),
            "embeddingMode": embedding_mode,
            "topK": top_k,
            "problemTopK": problem_top_k,
            "maxNeighborhoodChunks": max_neighborhood_chunks,
            "catalogChunkCount": len(catalog_store),
            "catalogDistinctSourceCount": len(catalog_sources),
            "problemCount": len(problem_store),
            "problemDistinctSourceCount": len(problem_sources),
            "tierAQueryCount": len(tier_a_queries),
            "tierCQueryCount": len(tier_c_queries),
            "embedding": embedding_metadata,
            "problemEmbedding": problem_embedding_metadata,
        },
        "validation": validation.to_json_dict(),
        "tierA": {
            "conceptSearch": {
                "topK": {
                    "diversity": calculate_diversity_metrics(
                        tier_a_query_ids,
                        concept_tier_a.top_k,
                        total_source_paths=catalog_sources,
                    ),
                    "relevance": calculate_relevance_metrics(
                        tier_a_queries,
                        concept_tier_a.top_k,
                        k=top_k,
                    ),
                },
                "evidenceBundle": {
                    "diversity": calculate_diversity_metrics(
                        tier_a_query_ids,
                        concept_tier_a.bundle,
                        total_source_paths=catalog_sources,
                        neighborhoods_by_query=concept_tier_a.neighborhoods,
                        seed_results_by_query=concept_tier_a.top_k,
                    ),
                    "relevance": calculate_relevance_metrics(
                        tier_a_queries,
                        concept_tier_a.bundle,
                        k=top_k + max_neighborhood_chunks,
                    ),
                },
            },
            "problemBankSearch": {
                "topK": {
                    "diversity": calculate_diversity_metrics(
                        tier_a_query_ids,
                        problem_tier_a.top_k,
                        total_source_paths=problem_sources,
                    ),
                    "relevance": calculate_relevance_metrics(
                        tier_a_queries,
                        problem_tier_a.top_k,
                        k=problem_top_k,
                    ),
                }
            },
        },
        "tierC": {
            "conceptSearch": {
                "topK": {
                    "diversity": calculate_diversity_metrics(
                        tier_c_query_ids,
                        concept_tier_c.top_k,
                        total_source_paths=catalog_sources,
                    )
                },
                "evidenceBundle": {
                    "diversity": calculate_diversity_metrics(
                        tier_c_query_ids,
                        concept_tier_c.bundle,
                        total_source_paths=catalog_sources,
                        neighborhoods_by_query=concept_tier_c.neighborhoods,
                        seed_results_by_query=concept_tier_c.top_k,
                    )
                },
            },
            "problemBankSearch": {
                "topK": {
                    "diversity": calculate_diversity_metrics(
                        tier_c_query_ids,
                        problem_tier_c.top_k,
                        total_source_paths=problem_sources,
                    )
                }
            },
        },
    }
    return report


def write_json_report(report: Mapping[str, Any], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def write_markdown_report(report: Mapping[str, Any], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(render_markdown_report(report), encoding="utf-8")


def render_markdown_report(report: Mapping[str, Any]) -> str:
    metadata = report["metadata"]
    validation = report["validation"]
    lines = [
        "# RAG Diversity Baseline",
        "",
        "## Metadata",
        "",
        f"- Generated at: `{metadata['generatedAt']}`",
        f"- Git commit: `{metadata.get('gitCommit') or 'unknown'}`",
        f"- Embedding mode: `{metadata['embeddingMode']}`",
        f"- Catalog: `{metadata['catalogPath']}` ({metadata['catalogChunkCount']} chunks, {metadata['catalogDistinctSourceCount']} sources)",
        f"- Problem bank: `{metadata['problemBankPath']}` ({metadata['problemCount']} problems, {metadata['problemDistinctSourceCount']} sources)",
        f"- Tier A fixture: `{metadata['queryFixturePath']}` ({metadata['tierAQueryCount']} queries)",
        f"- Tier C sweep: {metadata['tierCQueryCount']} generated/input queries",
        f"- topK: {metadata['topK']}, problemTopK: {metadata['problemTopK']}, maxNeighborhoodChunks: {metadata['maxNeighborhoodChunks']}",
        "",
        "## Tier A Fixture Validation",
        "",
        f"- Records: {validation['recordCount']}",
        f"- Catalog sources: {validation['catalogSourceCount']}",
        f"- Missing sources: {len(validation['missingSourcePaths'])}",
        f"- Extra sources: {len(validation['extraSourcePaths'])}",
        f"- Duplicate sources: {len(validation['duplicateSourcePaths'])}",
        f"- Sorted by sourcePath: {validation['isSorted']}",
        "",
        "## Tier A Source Coverage Guard",
        "",
        _summary_table(
            (
                ("Concept top-k", report["tierA"]["conceptSearch"]["topK"]),
                ("Concept evidence bundle", report["tierA"]["conceptSearch"]["evidenceBundle"]),
                ("Problem bank top-k", report["tierA"]["problemBankSearch"]["topK"]),
            ),
            include_relevance=True,
        ),
        "",
        "## Tier C Unlabeled Diversity Sweep",
        "",
        _summary_table(
            (
                ("Concept top-k", report["tierC"]["conceptSearch"]["topK"]),
                ("Concept evidence bundle", report["tierC"]["conceptSearch"]["evidenceBundle"]),
                ("Problem bank top-k", report["tierC"]["problemBankSearch"]["topK"]),
            ),
            include_relevance=False,
        ),
        "",
        "## Notes",
        "",
        "- This Story 1 baseline only measures the current retrieval behavior; search, recommendation, and catalog logic were not modified.",
        "- `fake` embedding mode is deterministic and does not call Gemini. Use `--embedding-mode sidecar` to evaluate with the checked-in sidecar embeddings and real query embeddings.",
        "",
    ]
    return "\n".join(lines)


def write_tier_c_queries(queries: Sequence[EvalQuery], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "".join(json.dumps(query.to_json_dict(), ensure_ascii=False, sort_keys=True) + "\n" for query in queries),
        encoding="utf-8",
    )


def normalize_source_path(source_path: str, known_source_paths: Iterable[str] = ()) -> str:
    if not source_path:
        return source_path
    known = tuple(sorted(set(known_source_paths), key=len, reverse=True))
    if source_path in known:
        return source_path
    for candidate in known:
        if source_path.endswith(f"/{candidate}"):
            return candidate
    return source_path


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Evaluate RAG retrieval diversity and relevance baseline.",
    )
    parser.add_argument("--catalog-path", type=Path, default=DEFAULT_CATALOG_PATH)
    parser.add_argument("--problem-bank-path", type=Path, default=DEFAULT_PROBLEM_BANK_PATH)
    parser.add_argument("--queries-path", type=Path, default=DEFAULT_QUERIES_PATH)
    parser.add_argument("--embeddings-path", type=Path, default=None)
    parser.add_argument("--problem-embeddings-path", type=Path, default=None)
    parser.add_argument("--tier-c-queries-path", type=Path, default=None)
    parser.add_argument("--write-tier-c-queries", type=Path, default=None)
    parser.add_argument("--tier-c-min-queries", type=int, default=150)
    parser.add_argument("--tier-c-max-queries", type=int, default=300)
    parser.add_argument("--embedding-mode", choices=("fake", "keyword", "sidecar"), default="fake")
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--problem-top-k", type=int, default=3)
    parser.add_argument("--max-neighborhood-chunks", type=int, default=8)
    parser.add_argument("--output-md", type=Path, default=DEFAULT_REPORT_MD_PATH)
    parser.add_argument("--output-json", type=Path, default=DEFAULT_REPORT_JSON_PATH)
    parser.add_argument("--validate-only", action="store_true")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    catalog_store = load_concept_catalog_store(args.catalog_path)
    tier_a_queries = load_tier_a_queries(args.queries_path)
    validation = validate_tier_a_fixture(tier_a_queries, _catalog_source_paths(catalog_store))
    if args.validate_only:
        print(
            "OK final Tier A fixture: "
            f"{validation.record_count} records, source full match, "
            f"{len(validation.duplicate_source_paths)} duplicates, sorted={validation.is_sorted}"
        )
        return 0

    if args.write_tier_c_queries is not None and args.tier_c_queries_path is None:
        tier_c_queries = generate_tier_c_queries(
            catalog_store.records,
            min_queries=args.tier_c_min_queries,
            max_queries=args.tier_c_max_queries,
        )
        write_tier_c_queries(tier_c_queries, args.write_tier_c_queries)

    report = run_baseline_evaluation(
        catalog_path=args.catalog_path,
        problem_bank_path=args.problem_bank_path,
        queries_path=args.queries_path,
        embeddings_path=args.embeddings_path,
        problem_embeddings_path=args.problem_embeddings_path,
        tier_c_queries_path=args.tier_c_queries_path,
        tier_c_min_queries=args.tier_c_min_queries,
        tier_c_max_queries=args.tier_c_max_queries,
        embedding_mode=args.embedding_mode,
        top_k=args.top_k,
        problem_top_k=args.problem_top_k,
        max_neighborhood_chunks=args.max_neighborhood_chunks,
    )
    write_json_report(report, args.output_json)
    write_markdown_report(report, args.output_md)
    print(f"Wrote {args.output_json}")
    print(f"Wrote {args.output_md}")
    return 0


def _eval_query_from_payload(
    payload: Any,
    *,
    path: Path,
    line_number: int,
    require_labels: bool,
) -> EvalQuery:
    if not isinstance(payload, Mapping):
        raise ValueError(f"{path}:{line_number}: record must be a JSON object")
    required_keys = ("queryId", "reportText", "expectedTopics", "expectedFields")
    if require_labels:
        required_keys += (
            "sourcePath",
            "sourceTier",
            "relevantSourcePaths",
            "relevanceGrades",
            "notes",
        )
    for key in required_keys:
        if key not in payload:
            raise ValueError(f"{path}:{line_number}: {key} is required")

    query_id = _required_str(payload, "queryId", path, line_number)
    report_text = _required_str(payload, "reportText", path, line_number)
    expected_topics = _str_tuple(payload.get("expectedTopics", ()), "expectedTopics", path, line_number)
    expected_fields = _str_tuple(payload.get("expectedFields", ()), "expectedFields", path, line_number)
    source_path = _optional_str(payload, "sourcePath", path, line_number)
    source_tier = _optional_str(payload, "sourceTier", path, line_number)
    relevant_source_paths = _str_tuple(
        payload.get("relevantSourcePaths", ()),
        "relevantSourcePaths",
        path,
        line_number,
    )
    relevance_grades = _relevance_grades(payload.get("relevanceGrades", {}), path, line_number)
    notes = _optional_str(payload, "notes", path, line_number) or ""
    return EvalQuery(
        query_id=query_id,
        report_text=report_text,
        expected_topics=expected_topics,
        expected_fields=expected_fields,
        source_path=source_path,
        source_tier=source_tier,
        relevant_source_paths=relevant_source_paths,
        relevance_grades=relevance_grades,
        notes=notes,
    )


def _validate_tier_a_record(query: EvalQuery) -> list[str]:
    errors: list[str] = []
    if query.source_path is None:
        errors.append(f"{query.query_id}: sourcePath is required")
        return errors
    if query.query_id != f"q-{slug_source_path(query.source_path)}":
        errors.append(f"{query.query_id}: queryId does not match sourcePath slug")
    if query.source_tier not in ALLOWED_SOURCE_TIERS:
        errors.append(f"{query.query_id}: sourceTier must be one of {sorted(ALLOWED_SOURCE_TIERS)}")
    if not query.report_text.strip():
        errors.append(f"{query.query_id}: reportText must not be empty")
    if not query.expected_topics:
        errors.append(f"{query.query_id}: expectedTopics must not be empty")
    unknown_fields = sorted(set(query.expected_fields) - set(SCORE_FIELDS))
    if unknown_fields:
        errors.append(f"{query.query_id}: unknown expectedFields {unknown_fields}")
    if not query.relevant_source_paths:
        errors.append(f"{query.query_id}: relevantSourcePaths must not be empty")
    if query.source_path not in query.relevant_source_paths:
        errors.append(f"{query.query_id}: relevantSourcePaths must include sourcePath")
    if len(query.relevant_source_paths) > 3:
        errors.append(f"{query.query_id}: relevantSourcePaths must contain at most 3 sources")
    for item_id, grade in query.relevance_grades.items():
        if not isinstance(item_id, str) or not item_id:
            errors.append(f"{query.query_id}: relevanceGrades keys must be non-empty strings")
        if not isinstance(grade, int) or grade < 0 or grade > 3:
            errors.append(f"{query.query_id}: relevanceGrades values must be integers from 0 to 3")
    return errors


def slug_source_path(source_path: str) -> str:
    chars: list[str] = []
    last_was_dash = False
    for char in source_path.lower():
        if char.isalnum():
            chars.append(char)
            last_was_dash = False
        elif not last_was_dash:
            chars.append("-")
            last_was_dash = True
    return "".join(chars).strip("-")


def _read_jsonl_objects(path: Path) -> tuple[tuple[int, Any], ...]:
    records: list[tuple[int, Any]] = []
    with Path(path).open("r", encoding="utf-8") as file:
        for line_number, line in enumerate(file, start=1):
            stripped = line.strip()
            if not stripped:
                continue
            try:
                records.append((line_number, json.loads(stripped)))
            except json.JSONDecodeError as exc:
                raise ValueError(f"{path}:{line_number}: invalid JSON: {exc}") from exc
    return tuple(records)


def _required_str(payload: Mapping[str, Any], key: str, path: Path, line_number: int) -> str:
    value = payload.get(key)
    if not isinstance(value, str):
        raise ValueError(f"{path}:{line_number}: {key} must be a string")
    return value


def _optional_str(payload: Mapping[str, Any], key: str, path: Path, line_number: int) -> str | None:
    if key not in payload or payload[key] is None:
        return None
    return _required_str(payload, key, path, line_number)


def _str_tuple(values: Any, key: str, path: Path, line_number: int) -> tuple[str, ...]:
    if not isinstance(values, list) and not isinstance(values, tuple):
        raise ValueError(f"{path}:{line_number}: {key} must be an array")
    result: list[str] = []
    for index, value in enumerate(values):
        if not isinstance(value, str):
            raise ValueError(f"{path}:{line_number}: {key}[{index}] must be a string")
        result.append(value)
    return tuple(result)


def _relevance_grades(value: Any, path: Path, line_number: int) -> dict[str, int]:
    if not isinstance(value, Mapping):
        raise ValueError(f"{path}:{line_number}: relevanceGrades must be an object")
    result: dict[str, int] = {}
    for key, grade in value.items():
        if not isinstance(key, str):
            raise ValueError(f"{path}:{line_number}: relevanceGrades keys must be strings")
        if not isinstance(grade, int):
            raise ValueError(f"{path}:{line_number}: relevanceGrades values must be integers")
        result[key] = grade
    return result


def _prepare_embedding_components(
    embedding_mode: str,
    *,
    catalog_store: ConceptCatalogStore,
    embeddings_path: Path | None,
) -> tuple[ConceptEmbeddingStore | None, Any | None, dict[str, Any]]:
    if embedding_mode == "fake":
        store = generate_fake_embedding_store(catalog_store)
        return store, FakeEmbeddingClient(), {
            "model": store.expected_model,
            "dimensions": store.expected_dimensionality,
            "storeItemCount": len(store),
            "issueCount": len(store.issues),
            "callsGemini": False,
        }
    if embedding_mode == "keyword":
        store = ConceptEmbeddingStore(expected_model="keyword-fallback", expected_dimensionality=0)
        return store, None, {
            "model": "keyword-fallback",
            "dimensions": 0,
            "storeItemCount": 0,
            "issueCount": 0,
            "callsGemini": False,
        }
    if embedding_mode == "sidecar":
        store = load_concept_embedding_store(
            store=catalog_store,
            embeddings_path=embeddings_path or FASTAPI_ROOT / "data" / "rag" / "catalog" / "chunk-embeddings.jsonl",
        )
        return store, None, {
            "model": store.expected_model,
            "dimensions": store.expected_dimensionality,
            "storeItemCount": len(store),
            "issueCount": len(store.issues),
            "callsGemini": True,
        }
    raise ValueError(f"unknown embedding mode: {embedding_mode}")


def _prepare_problem_embedding_components(
    embedding_mode: str,
    *,
    problem_store: ProblemBankStore,
    problem_embeddings_path: Path | None,
) -> tuple[ProblemEmbeddingStore | None, Any | None, dict[str, Any]]:
    if embedding_mode == "fake":
        store = generate_fake_problem_embedding_store(problem_store)
        return store, FakeEmbeddingClient(), {
            "model": store.expected_model,
            "dimensions": store.expected_dimensionality,
            "storeItemCount": len(store),
            "issueCount": len(store.issues),
            "callsGemini": False,
        }
    if embedding_mode == "keyword":
        return None, None, {
            "model": "keyword-fallback",
            "dimensions": 0,
            "storeItemCount": 0,
            "issueCount": 0,
            "callsGemini": False,
        }
    if embedding_mode == "sidecar":
        store = load_problem_embedding_store(
            store=problem_store,
            embeddings_path=problem_embeddings_path or DEFAULT_PROBLEM_EMBEDDINGS_PATH,
        )
        return store, GeminiEmbeddingClient(), {
            "model": store.expected_model,
            "dimensions": store.expected_dimensionality,
            "storeItemCount": len(store),
            "issueCount": len(store.issues),
            "callsGemini": True,
        }
    raise ValueError(f"unknown embedding mode: {embedding_mode}")


def _catalog_source_paths(store: ConceptCatalogStore) -> tuple[str, ...]:
    return tuple(sorted({chunk.source_path for chunk in store.records}))


def _problem_source_paths(
    store: ProblemBankStore,
    *,
    known_source_paths: Iterable[str] = (),
) -> tuple[str, ...]:
    return tuple(
        sorted(
            {
                normalize_source_path(problem.source_path, known_source_paths)
                for problem in store.records
            }
        )
    )


def _fake_embedding_input(chunk: ConceptChunkRecord) -> str:
    return " ".join(
        (
            chunk.source_path,
            " ".join(chunk.heading_path),
            " ".join(chunk.field_hints),
            chunk.text,
        )
    )


def _fake_problem_embedding_input(problem: ProblemRecord) -> str:
    return build_problem_embedding_input(problem)


def _topic_from_chunk(chunk: ConceptChunkRecord) -> str:
    if chunk.heading_path:
        return " > ".join(chunk.heading_path[-2:])
    return PurePosixPath(chunk.source_path).stem


def _normalized_entropy(source_counts: Counter[str]) -> float:
    total = sum(source_counts.values())
    if total <= 0 or len(source_counts) <= 1:
        return 0.0
    entropy = 0.0
    for count in source_counts.values():
        probability = count / total
        entropy -= probability * math.log(probability)
    return entropy / math.log(len(source_counts))


def _top_level_folder(source_path: str) -> str:
    parts = PurePosixPath(source_path).parts
    return parts[0] if parts else ""


def _matching_relevant_source(source_path: str, relevant_sources: set[str]) -> str | None:
    if source_path in relevant_sources:
        return source_path
    for relevant in relevant_sources:
        if source_path.endswith(f"/{relevant}") or relevant.endswith(f"/{source_path}"):
            return relevant
    return None


def _ndcg_at_k(
    results: Sequence[RetrievedItem],
    relevance_grades: Mapping[str, int],
    k: int,
) -> float:
    if not relevance_grades:
        return 0.0
    gains = [
        relevance_grades.get(item.item_id, relevance_grades.get(item.source_path, 0))
        for item in results[:k]
    ]
    dcg = _dcg(gains)
    ideal = _dcg(sorted(relevance_grades.values(), reverse=True)[:k])
    return dcg / ideal if ideal else 0.0


def _dcg(grades: Sequence[int]) -> float:
    return sum((2 ** grade - 1) / math.log2(index + 2) for index, grade in enumerate(grades))


def _avg(values: Iterable[float]) -> float:
    items = tuple(values)
    return sum(items) / len(items) if items else 0.0


def _round(value: float) -> float:
    return round(float(value), 6)


def _summary_table(rows: Iterable[tuple[str, Mapping[str, Any]]], *, include_relevance: bool) -> str:
    headers = [
        "Path",
        "Avg distinct source",
        "HHI",
        "Field cov.",
        "Folder cov.",
        "Catalog cov.",
        "Same-source neighbor",
    ]
    if include_relevance:
        headers.extend(("Recall@k", "Hit@k", "MRR", "Source cov."))
    lines = [
        "| " + " | ".join(headers) + " |",
        "| " + " | ".join("---" for _ in headers) + " |",
    ]
    for label, section in rows:
        diversity = section["diversity"]["aggregate"]
        values = [
            label,
            _fmt(diversity["avgDistinctSourceCount"]),
            _fmt(diversity["avgSourceHHI"]),
            _fmt(diversity["avgFieldCoverage"]),
            _fmt(diversity["avgFolderCoverage"]),
            _fmt(diversity["catalogCoverage"]),
            _fmt(diversity["avgSameSourceNeighborRatio"]),
        ]
        if include_relevance:
            relevance = section["relevance"]["aggregate"]
            values.extend(
                (
                    _fmt(relevance["recallAtK"]),
                    _fmt(relevance["hitRateAtK"]),
                    _fmt(relevance["mrr"]),
                    _fmt(relevance["sourceCoverage"]),
                )
            )
        lines.append("| " + " | ".join(values) + " |")
    return "\n".join(lines)


def _fmt(value: Any) -> str:
    if isinstance(value, float):
        return f"{value:.4f}"
    return str(value)


def _relative_path(path: Path) -> str:
    try:
        return Path(path).resolve().relative_to(FASTAPI_ROOT).as_posix()
    except ValueError:
        return Path(path).as_posix()


def _git_commit() -> str | None:
    try:
        result = subprocess.run(
            ("git", "rev-parse", "--short", "HEAD"),
            cwd=FASTAPI_ROOT.parent,
            check=True,
            capture_output=True,
            text=True,
        )
    except (OSError, subprocess.CalledProcessError):
        return None
    return result.stdout.strip() or None


if __name__ == "__main__":
    raise SystemExit(main())
