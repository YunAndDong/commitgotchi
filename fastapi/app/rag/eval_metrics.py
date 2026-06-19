from __future__ import annotations

import math
import random
from collections import Counter
from pathlib import PurePosixPath
from statistics import mean
from typing import Any, Iterable, Mapping, Sequence


DEFAULT_GATE_THRESHOLDS = {
    "minSignificantDiversityMetrics": 2,
    "relevanceEpsilon": 0.02,
    "maxWorsenedQueryRatio": 0.10,
    "confidence": 0.95,
}


def relevance_metrics_at_k(
    results: Sequence[Any],
    *,
    relevant_source_paths: Sequence[str],
    relevance_grades: Mapping[str, int] | None = None,
    expected_fields: Sequence[str] = (),
    k: int,
) -> dict[str, float]:
    top_results = tuple(results[: max(k, 0)])
    relevant_sources = set(relevant_source_paths)
    grades = dict(relevance_grades or {})
    expected_field_set = set(expected_fields)

    found_sources: set[str] = set()
    first_relevant_rank = 0
    relevant_result_count = 0
    binary_seen_sources: set[str] = set()
    gains: list[int] = []
    result_fields = {
        field
        for item in top_results
        for field in _field_hints(item)
    }

    for rank, item in enumerate(top_results, start=1):
        source_path = _source_path(item)
        matched_source = _matching_relevant_source(source_path, relevant_sources)
        if matched_source is not None:
            if first_relevant_rank == 0:
                first_relevant_rank = rank
            if matched_source not in found_sources:
                relevant_result_count += 1
            found_sources.add(matched_source)

        if grades:
            gains.append(max(0, int(grades.get(_item_id(item), grades.get(source_path, 0)))))
        elif matched_source is not None and matched_source not in binary_seen_sources:
            gains.append(1)
            binary_seen_sources.add(matched_source)
        else:
            gains.append(0)

    recall = len(found_sources) / len(relevant_sources) if relevant_sources else 0.0
    precision = relevant_result_count / k if k > 0 else 0.0
    mrr = 1.0 / first_relevant_rank if first_relevant_rank else 0.0
    ndcg = _ndcg(gains, grades=grades, relevant_source_count=len(relevant_sources), k=k)
    field_hit = 1.0 if expected_field_set and expected_field_set.intersection(result_fields) else 0.0

    return {
        "recallAtK": recall,
        "precisionAtK": precision,
        "mrr": mrr,
        "ndcgAtK": ndcg,
        "fieldHitAtK": field_hit,
        "firstRelevantRank": float(first_relevant_rank),
        "retrievedRelevantSourceCount": float(len(found_sources)),
        "relevantSourceCount": float(len(relevant_sources)),
    }


def summarize_relevance_by_query(
    queries: Sequence[Any],
    results_by_query: Mapping[str, Sequence[Any]],
    *,
    k: int,
) -> dict[str, Any]:
    per_query: list[dict[str, Any]] = []
    for query in queries:
        query_id = _query_id(query)
        relevant_sources = tuple(_query_value(query, "relevant_source_paths", "relevantSourcePaths") or ())
        if not relevant_sources:
            continue
        metrics = relevance_metrics_at_k(
            tuple(results_by_query.get(query_id, ())),
            relevant_source_paths=relevant_sources,
            relevance_grades=_query_value(query, "relevance_grades", "relevanceGrades") or {},
            expected_fields=_query_value(query, "expected_fields", "expectedFields") or (),
            k=k,
        )
        per_query.append({"queryId": query_id, **metrics})

    aggregate = {
        "queryCount": len(per_query),
        "recallAtK": aggregate_metric(per_query, "recallAtK"),
        "precisionAtK": aggregate_metric(per_query, "precisionAtK"),
        "mrr": aggregate_metric(per_query, "mrr"),
        "ndcgAtK": aggregate_metric(per_query, "ndcgAtK"),
        "fieldHitAtK": aggregate_metric(per_query, "fieldHitAtK"),
    }
    return {"aggregate": aggregate, "perQuery": per_query}


def summarize_diversity_by_query(
    query_ids: Sequence[str],
    results_by_query: Mapping[str, Sequence[Any]],
    *,
    total_source_paths: Iterable[str],
    embeddings_by_item_id: Mapping[str, Sequence[float]] | None = None,
    neighborhoods_by_query: Mapping[str, Sequence[Any]] | None = None,
    seed_results_by_query: Mapping[str, Sequence[Any]] | None = None,
) -> dict[str, Any]:
    total_sources = tuple(sorted(set(total_source_paths)))
    embedding_lookup = dict(embeddings_by_item_id or {})
    retrieved_sources: set[str] = set()
    per_query: list[dict[str, Any]] = []

    for query_id in query_ids:
        items = tuple(results_by_query.get(query_id, ()))
        sources = tuple(source for item in items if (source := _source_path(item)))
        retrieved_sources.update(sources)
        fields = {field for item in items for field in _field_hints(item)}
        folders = {_top_level_folder(source) for source in sources if source}
        embeddings = tuple(
            tuple(float(value) for value in embedding)
            for item in items
            if (embedding := embedding_lookup.get(_item_id(item))) is not None
        )

        neighbor_items = tuple(neighborhoods_by_query.get(query_id, ())) if neighborhoods_by_query else ()
        seed_items = tuple(
            seed_results_by_query.get(query_id, ())
            if seed_results_by_query is not None
            else results_by_query.get(query_id, ())
        )
        seed_sources = {_source_path(item) for item in seed_items if _source_path(item)}
        same_source_neighbor_ratio = (
            sum(1 for item in neighbor_items if _source_path(item) in seed_sources) / len(neighbor_items)
            if neighbor_items
            else 0.0
        )

        source_counts = Counter(sources)
        per_query.append(
            {
                "queryId": query_id,
                "resultCount": float(len(items)),
                "distinctSourceCount": float(len(source_counts)),
                "mostCommonSourceRatio": (
                    max(source_counts.values()) / len(sources) if sources else 0.0
                ),
                "sourceHHI": source_hhi(sources),
                "sourceGini": source_gini(sources, total_source_paths=total_sources),
                "ild": intra_list_diversity(embeddings),
                "fieldCoverage": float(len(fields)),
                "folderCoverage": float(len(folders)),
                "neighborCount": float(len(neighbor_items)),
                "sameSourceNeighborRatio": same_source_neighbor_ratio,
            }
        )

    aggregate = {
        "queryCount": len(query_ids),
        "avgResultCount": aggregate_metric(per_query, "resultCount"),
        "avgDistinctSourceCount": aggregate_metric(per_query, "distinctSourceCount"),
        "avgMostCommonSourceRatio": aggregate_metric(per_query, "mostCommonSourceRatio"),
        "avgSourceHHI": aggregate_metric(per_query, "sourceHHI"),
        "avgSourceGini": aggregate_metric(per_query, "sourceGini"),
        "avgILD": aggregate_metric(per_query, "ild"),
        "avgFieldCoverage": aggregate_metric(per_query, "fieldCoverage"),
        "avgFolderCoverage": aggregate_metric(per_query, "folderCoverage"),
        "avgSameSourceNeighborRatio": aggregate_metric(per_query, "sameSourceNeighborRatio"),
        "catalogCoverage": (
            len(retrieved_sources.intersection(total_sources)) / len(total_sources)
            if total_sources
            else 0.0
        ),
        "retrievedDistinctSourceCount": len(retrieved_sources),
        "totalSourceCount": len(total_sources),
    }
    return {"aggregate": aggregate, "perQuery": per_query}


def source_hhi(source_paths: Sequence[str]) -> float:
    counts = Counter(source for source in source_paths if source)
    total = sum(counts.values())
    if total <= 0:
        return 0.0
    return sum((count / total) ** 2 for count in counts.values())


def source_gini(
    source_paths: Sequence[str],
    *,
    total_source_paths: Iterable[str] | None = None,
) -> float:
    observed_counts = Counter(source for source in source_paths if source)
    if total_source_paths is None:
        values = list(observed_counts.values())
    else:
        values = [observed_counts.get(source, 0) for source in sorted(set(total_source_paths))]
    if not values:
        return 0.0
    avg = sum(values) / len(values)
    if avg == 0.0:
        return 0.0
    absolute_differences = sum(abs(left - right) for left in values for right in values)
    return absolute_differences / (2 * len(values) * len(values) * avg)


def intra_list_diversity(embeddings: Sequence[Sequence[float]]) -> float:
    vectors = tuple(tuple(float(value) for value in embedding) for embedding in embeddings)
    if len(vectors) < 2:
        return 0.0
    distances: list[float] = []
    for index, left in enumerate(vectors):
        for right in vectors[index + 1 :]:
            similarity = _cosine_similarity(left, right)
            if similarity is None:
                continue
            distances.append(1.0 - similarity)
    return mean(distances) if distances else 0.0


def bootstrap_paired_delta(
    baseline_values: Sequence[float],
    candidate_values: Sequence[float],
    *,
    confidence: float = 0.95,
    resamples: int = 2000,
    seed: int = 0,
    epsilon: float = 0.0,
) -> dict[str, Any]:
    if len(baseline_values) != len(candidate_values):
        raise ValueError("baseline_values and candidate_values must have the same length")
    deltas = [float(candidate) - float(baseline) for baseline, candidate in zip(baseline_values, candidate_values)]
    if not deltas:
        return {
            "queryCount": 0,
            "meanBaseline": 0.0,
            "meanCandidate": 0.0,
            "meanDelta": 0.0,
            "ciLow": 0.0,
            "ciHigh": 0.0,
            "confidence": confidence,
            "improvedCount": 0,
            "worsenedCount": 0,
            "unchangedCount": 0,
            "worsenedRatio": 0.0,
            "bootstrapPValueApprox": 1.0,
        }

    rng = random.Random(seed)
    sample_count = max(1, int(resamples))
    boot_means: list[float] = []
    for _ in range(sample_count):
        sample = [deltas[rng.randrange(len(deltas))] for _index in deltas]
        boot_means.append(sum(sample) / len(sample))
    boot_means.sort()
    alpha = max(0.0, min(1.0, 1.0 - confidence))
    ci_low = _percentile(boot_means, alpha / 2)
    ci_high = _percentile(boot_means, 1.0 - (alpha / 2))
    less_or_equal_zero = sum(1 for value in boot_means if value <= 0.0) / len(boot_means)
    greater_or_equal_zero = sum(1 for value in boot_means if value >= 0.0) / len(boot_means)

    improved_count = sum(1 for delta in deltas if delta > epsilon)
    worsened_count = sum(1 for delta in deltas if delta < -epsilon)
    unchanged_count = len(deltas) - improved_count - worsened_count
    return {
        "queryCount": len(deltas),
        "meanBaseline": sum(float(value) for value in baseline_values) / len(baseline_values),
        "meanCandidate": sum(float(value) for value in candidate_values) / len(candidate_values),
        "meanDelta": sum(deltas) / len(deltas),
        "ciLow": ci_low,
        "ciHigh": ci_high,
        "confidence": confidence,
        "improvedCount": improved_count,
        "worsenedCount": worsened_count,
        "unchangedCount": unchanged_count,
        "worsenedRatio": worsened_count / len(deltas),
        "bootstrapPValueApprox": min(1.0, 2.0 * min(less_or_equal_zero, greater_or_equal_zero)),
    }


def evaluate_quality_gate(
    *,
    diversity_stats: Mapping[str, Mapping[str, Any]],
    relevance_stats: Mapping[str, Mapping[str, Any]],
    thresholds: Mapping[str, float] | None = None,
) -> dict[str, Any]:
    resolved = {**DEFAULT_GATE_THRESHOLDS, **dict(thresholds or {})}
    diversity_metric_names = ("avgDistinctSourceCount", "avgILD", "avgFieldCoverage")
    significant_improvements = [
        metric
        for metric in diversity_metric_names
        if _stat_value(diversity_stats.get(metric), "meanDelta") > 0.0
        and _stat_value(diversity_stats.get(metric), "ciLow") > 0.0
    ]

    relevance_metric_names = ("recallAtK", "ndcgAtK")
    relevance_epsilon = float(resolved["relevanceEpsilon"])
    relevance_maintained = [
        metric
        for metric in relevance_metric_names
        if _stat_value(relevance_stats.get(metric), "meanDelta") >= -relevance_epsilon
    ]
    worsened_ratios = [
        _stat_value(relevance_stats.get(metric), "worsenedRatio")
        for metric in relevance_metric_names
        if metric in relevance_stats
    ]
    max_worsened_ratio = max(worsened_ratios or [0.0])

    checks = [
        (
            "diversity",
            len(significant_improvements) >= int(resolved["minSignificantDiversityMetrics"]),
            (
                "significant diversity improvements "
                f"{len(significant_improvements)}/{int(resolved['minSignificantDiversityMetrics'])} required: "
                f"{', '.join(significant_improvements) or 'none'}"
            ),
        ),
        (
            "relevance",
            len(relevance_maintained) == len(relevance_metric_names),
            (
                f"relevance maintained within epsilon={relevance_epsilon} "
                f"{len(relevance_maintained)}/{len(relevance_metric_names)} required: "
                f"{', '.join(relevance_maintained) or 'none'}"
            ),
        ),
        (
            "worsened_ratio",
            max_worsened_ratio <= float(resolved["maxWorsenedQueryRatio"]),
            f"max worsened query ratio={max_worsened_ratio:.4f}",
        ),
    ]
    failures = [message for _name, passed, message in checks if not passed]
    return {
        "passed": not failures,
        "thresholds": resolved,
        "checks": [message for _name, _passed, message in checks],
        "failures": failures,
        "significantDiversityMetrics": significant_improvements,
        "relevanceMetricsMaintained": relevance_maintained,
        "maxWorsenedQueryRatio": max_worsened_ratio,
    }


def aggregate_metric(rows: Sequence[Mapping[str, Any]], metric_key: str) -> float:
    values = [float(row[metric_key]) for row in rows if metric_key in row]
    return sum(values) / len(values) if values else 0.0


def _ndcg(
    gains: Sequence[int],
    *,
    grades: Mapping[str, int],
    relevant_source_count: int,
    k: int,
) -> float:
    if k <= 0:
        return 0.0
    dcg = _dcg(gains[:k])
    if grades:
        ideal_gains = sorted((max(0, int(value)) for value in grades.values()), reverse=True)[:k]
    else:
        ideal_gains = [1] * min(relevant_source_count, k)
    ideal = _dcg(ideal_gains)
    return dcg / ideal if ideal else 0.0


def _dcg(gains: Sequence[int]) -> float:
    return sum((2**gain - 1) / math.log2(index + 2) for index, gain in enumerate(gains))


def _cosine_similarity(left: Sequence[float], right: Sequence[float]) -> float | None:
    if not left or len(left) != len(right):
        return None
    dot = sum(a * b for a, b in zip(left, right))
    left_norm = math.sqrt(sum(value * value for value in left))
    right_norm = math.sqrt(sum(value * value for value in right))
    if left_norm == 0.0 or right_norm == 0.0:
        return None
    return dot / (left_norm * right_norm)


def _percentile(sorted_values: Sequence[float], probability: float) -> float:
    if not sorted_values:
        return 0.0
    if probability <= 0.0:
        return sorted_values[0]
    if probability >= 1.0:
        return sorted_values[-1]
    position = probability * (len(sorted_values) - 1)
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return sorted_values[int(position)]
    weight = position - lower
    return sorted_values[lower] * (1.0 - weight) + sorted_values[upper] * weight


def _item_id(item: Any) -> str:
    return str(_value(item, "item_id", "itemId") or "")


def _source_path(item: Any) -> str:
    return str(_value(item, "source_path", "sourcePath") or "")


def _field_hints(item: Any) -> tuple[str, ...]:
    value = _value(item, "field_hints", "fieldHints")
    if value is None:
        return ()
    return tuple(str(item) for item in value)


def _query_id(query: Any) -> str:
    return str(_query_value(query, "query_id", "queryId") or "")


def _query_value(query: Any, *keys: str) -> Any:
    return _value(query, *keys)


def _value(target: Any, *keys: str) -> Any:
    if isinstance(target, Mapping):
        for key in keys:
            if key in target:
                return target[key]
        return None
    for key in keys:
        if hasattr(target, key):
            return getattr(target, key)
    return None


def _matching_relevant_source(source_path: str, relevant_sources: set[str]) -> str | None:
    if source_path in relevant_sources:
        return source_path
    for relevant in relevant_sources:
        if source_path.endswith(f"/{relevant}") or relevant.endswith(f"/{source_path}"):
            return relevant
    return None


def _top_level_folder(source_path: str) -> str:
    parts = PurePosixPath(source_path).parts
    return parts[0] if parts else ""


def _stat_value(stats: Mapping[str, Any] | None, key: str) -> float:
    if stats is None:
        return 0.0
    return float(stats.get(key, 0.0))
