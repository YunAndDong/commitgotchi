from __future__ import annotations

import argparse
import datetime as dt
import json
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Mapping, Sequence


FASTAPI_ROOT = Path(__file__).resolve().parents[1]
if str(FASTAPI_ROOT) not in sys.path:
    sys.path.insert(0, str(FASTAPI_ROOT))

from app.rag.concept_search import build_report_evidence_bundle  # noqa: E402
from app.rag.concept_store import (  # noqa: E402
    DEFAULT_CATALOG_PATH,
    ConceptCatalogStore,
    load_concept_catalog_store,
)
from app.rag.embedding_store import ConceptEmbeddingStore  # noqa: E402
from app.rag.eval_metrics import (  # noqa: E402
    DEFAULT_GATE_THRESHOLDS,
    bootstrap_paired_delta,
    evaluate_quality_gate,
    summarize_diversity_by_query,
    summarize_relevance_by_query,
)
from app.rag.problem_bank_search import ProblemBankSearcher  # noqa: E402
from app.rag.problem_bank_store import (  # noqa: E402
    DEFAULT_CATALOG_PATH as DEFAULT_PROBLEM_BANK_PATH,
    ProblemBankStore,
    load_problem_bank_store,
)
from app.rag.problem_embedding_store import ProblemEmbeddingStore  # noqa: E402
from app.scoring.schemas import ReportChunk  # noqa: E402
from scripts.rag_diversity_eval import (  # noqa: E402
    DEFAULT_QUERIES_PATH,
    FAKE_EMBEDDING_DIMENSIONS,
    FAKE_EMBEDDING_MODEL,
    EvalQuery,
    FakeEmbeddingClient,
    generate_fake_embedding_store,
    generate_fake_problem_embedding_store,
    generate_tier_c_queries,
    load_tier_a_queries,
    load_unlabeled_queries,
    normalize_source_path,
    validate_tier_a_fixture,
)


DEFAULT_BENCHMARK_MD_PATH = FASTAPI_ROOT / "data" / "rag" / "reports" / "rag-enhancement-benchmark.md"
DEFAULT_BENCHMARK_JSON_PATH = FASTAPI_ROOT / "data" / "rag" / "reports" / "rag-enhancement-benchmark.json"
DEFAULT_STORY1_BASELINE_MD_PATH = FASTAPI_ROOT / "data" / "rag" / "reports" / "rag-diversity-baseline.md"
DEFAULT_STORY1_BASELINE_JSON_PATH = FASTAPI_ROOT / "data" / "rag" / "reports" / "rag-diversity-baseline.json"


@dataclass(frozen=True)
class AblationConfig:
    key: str
    label: str
    description: str
    mmr_lambda: float
    max_per_source: int | None
    candidate_pool: int | None = None
    max_same_source_neighbors: int | None = None
    min_cross_source: int = 0
    max_subqueries: int = 0
    use_problem_hybrid: bool = False
    limitations: tuple[str, ...] = field(default_factory=tuple)


@dataclass(frozen=True)
class BenchmarkItem:
    query_id: str
    rank: int
    item_id: str
    source_path: str
    heading_path: tuple[str, ...] = field(default_factory=tuple)
    field_hints: tuple[str, ...] = field(default_factory=tuple)

    def to_json_dict(self) -> dict[str, Any]:
        return {
            "queryId": self.query_id,
            "rank": self.rank,
            "itemId": self.item_id,
            "sourcePath": self.source_path,
            "headingPath": list(self.heading_path),
            "fieldHints": list(self.field_hints),
        }


def ablation_configs() -> tuple[AblationConfig, ...]:
    return (
        AblationConfig(
            key="B0",
            label="B0",
            description="Baseline approximation: no MMR/source cap, no cross-source neighborhood quota, no multi-query, problem keyword-only.",
            mmr_lambda=1.0,
            max_per_source=None,
            max_same_source_neighbors=999,
            min_cross_source=0,
            max_subqueries=0,
        ),
        AblationConfig(
            key="S2",
            label="+S2",
            description="B0 + concept MMR reranking and per-source cap.",
            mmr_lambda=0.7,
            max_per_source=2,
            max_same_source_neighbors=999,
            min_cross_source=0,
            max_subqueries=0,
        ),
        AblationConfig(
            key="S3",
            label="+S3",
            description="+S2 + source-neighborhood rebalance using the current public quota arguments.",
            mmr_lambda=0.7,
            max_per_source=2,
            max_same_source_neighbors=None,
            min_cross_source=2,
            max_subqueries=0,
            limitations=(
                "S3 is measured through public max_same_source_neighbors/min_cross_source arguments; the exact pre-story implementation is not resurrected.",
            ),
        ),
        AblationConfig(
            key="S4",
            label="+S4",
            description="+S3 + field/topic multi-query merge through max_subqueries.",
            mmr_lambda=0.7,
            max_per_source=2,
            max_same_source_neighbors=None,
            min_cross_source=2,
            max_subqueries=3,
            limitations=(
                "S4 is separated by max_subqueries=0 vs 3; field quota merge internals are not split into finer-grained toggles.",
            ),
        ),
        AblationConfig(
            key="S5_6",
            label="+S5/6",
            description="+S4 + problem-bank fake embedding store/client for hybrid search.",
            mmr_lambda=0.7,
            max_per_source=2,
            max_same_source_neighbors=None,
            min_cross_source=2,
            max_subqueries=3,
            use_problem_hybrid=True,
            limitations=(
                "Story 5 and 6 are measured together because embeddings and hybrid ranking form one exposed problem-bank toggle.",
            ),
        ),
    )


def evaluate_ablation_configs(
    queries: Sequence[EvalQuery],
    *,
    catalog_store: ConceptCatalogStore,
    problem_store: ProblemBankStore,
    concept_embedding_store: ConceptEmbeddingStore,
    problem_embedding_store: ProblemEmbeddingStore,
    client: FakeEmbeddingClient,
    top_k: int = 5,
    problem_top_k: int = 3,
    max_neighborhood_chunks: int = 8,
    tier_name: str = "tierA",
    bootstrap_resamples: int = 2000,
    bootstrap_seed: int = 7,
    metadata: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    query_ids = tuple(query.query_id for query in queries)
    catalog_sources = _catalog_source_paths(catalog_store)
    problem_sources = _problem_source_paths(problem_store, known_source_paths=catalog_sources)
    concept_embeddings = _concept_embeddings_by_item_id(concept_embedding_store)
    problem_embeddings = _problem_embeddings_by_item_id(problem_embedding_store)
    configs = ablation_configs()

    ablations: dict[str, Any] = {}
    raw_concept_top: dict[str, dict[str, tuple[BenchmarkItem, ...]]] = {}

    for config in configs:
        concept_result = _evaluate_concept_queries_for_config(
            queries,
            config=config,
            store=catalog_store,
            embedding_store=concept_embedding_store,
            client=client,
            top_k=top_k,
            max_neighborhood_chunks=max_neighborhood_chunks,
            known_source_paths=catalog_sources,
        )
        problem_result = _evaluate_problem_queries_for_config(
            queries,
            config=config,
            store=problem_store,
            embedding_store=problem_embedding_store,
            client=client,
            top_k=problem_top_k,
            known_source_paths=catalog_sources,
        )
        raw_concept_top[config.key] = concept_result["topK"]
        ablations[config.key] = {
            "key": config.key,
            "label": config.label,
            "description": config.description,
            "limitations": list(config.limitations),
            tier_name: {
                "conceptSearch": {
                    "topK": _metric_section(
                        query_ids,
                        queries,
                        concept_result["topK"],
                        total_source_paths=catalog_sources,
                        embeddings_by_item_id=concept_embeddings,
                        k=top_k,
                    ),
                    "evidenceBundle": _metric_section(
                        query_ids,
                        queries,
                        concept_result["bundle"],
                        total_source_paths=catalog_sources,
                        embeddings_by_item_id=concept_embeddings,
                        neighborhoods_by_query=concept_result["neighborhoods"],
                        seed_results_by_query=concept_result["topK"],
                        k=top_k + max_neighborhood_chunks,
                    ),
                },
                "problemBankSearch": {
                    "topK": _metric_section(
                        query_ids,
                        queries,
                        problem_result["topK"],
                        total_source_paths=problem_sources,
                        embeddings_by_item_id=problem_embeddings,
                        k=problem_top_k,
                    )
                },
            },
        }

    comparisons = {
        "finalVsBaseline": _compare_ablation_sections(
            ablations["B0"][tier_name],
            ablations["S5_6"][tier_name],
            bootstrap_resamples=bootstrap_resamples,
            bootstrap_seed=bootstrap_seed,
        )
    }
    gate = evaluate_quality_gate(
        diversity_stats={
            metric: comparisons["finalVsBaseline"]["conceptSearch.topK"][metric]
            for metric in ("avgDistinctSourceCount", "avgILD", "avgFieldCoverage")
        },
        relevance_stats={
            metric: comparisons["finalVsBaseline"]["conceptSearch.topK"][metric]
            for metric in ("recallAtK", "ndcgAtK")
        },
        thresholds=DEFAULT_GATE_THRESHOLDS,
    )

    report_metadata = {
        "generatedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "gitCommit": _git_commit(),
        "embeddingMode": "fake",
        "callsGemini": False,
        "embedding": {
            "model": FAKE_EMBEDDING_MODEL,
            "dimensions": FAKE_EMBEDDING_DIMENSIONS,
            "callsGemini": False,
        },
        "topK": top_k,
        "problemTopK": problem_top_k,
        "maxNeighborhoodChunks": max_neighborhood_chunks,
        "catalogChunkCount": len(catalog_store),
        "catalogDistinctSourceCount": len(catalog_sources),
        "problemCount": len(problem_store),
        "problemDistinctSourceCount": len(problem_sources),
        "thresholds": dict(DEFAULT_GATE_THRESHOLDS),
    }
    report_metadata.update(dict(metadata or {}))

    return {
        "metadata": report_metadata,
        "ablations": ablations,
        "comparisons": comparisons,
        "gate": gate,
        "caseStudies": _case_studies(
            queries,
            raw_concept_top["B0"],
            raw_concept_top["S5_6"],
            max_cases=3,
        ),
        "limitations": _limitations(configs),
    }


def run_benchmark(
    *,
    catalog_path: Path = DEFAULT_CATALOG_PATH,
    problem_bank_path: Path = DEFAULT_PROBLEM_BANK_PATH,
    queries_path: Path = DEFAULT_QUERIES_PATH,
    tier_c_queries_path: Path | None = None,
    tier_c_min_queries: int = 150,
    tier_c_max_queries: int = 300,
    top_k: int = 5,
    problem_top_k: int = 3,
    max_neighborhood_chunks: int = 8,
    bootstrap_resamples: int = 2000,
    output_md_path: Path = DEFAULT_BENCHMARK_MD_PATH,
    output_json_path: Path = DEFAULT_BENCHMARK_JSON_PATH,
) -> dict[str, Any]:
    catalog_store = load_concept_catalog_store(catalog_path)
    problem_store = load_problem_bank_store(problem_bank_path)
    catalog_sources = _catalog_source_paths(catalog_store)
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
    concept_embedding_store = generate_fake_embedding_store(catalog_store)
    problem_embedding_store = generate_fake_problem_embedding_store(problem_store)
    client = FakeEmbeddingClient()

    common_metadata = {
        "catalogPath": _relative_path(catalog_path),
        "problemBankPath": _relative_path(problem_bank_path),
        "queryFixturePath": _relative_path(queries_path),
        "tierAQueryCount": len(tier_a_queries),
        "tierCQueryCount": len(tier_c_queries),
        "tierCMaxNeighborhoodChunks": 0,
        "bootstrapResamples": bootstrap_resamples,
        "story1BaselineArtifacts": {
            "markdown": _relative_path(DEFAULT_STORY1_BASELINE_MD_PATH),
            "json": _relative_path(DEFAULT_STORY1_BASELINE_JSON_PATH),
        },
        "outputMarkdownPath": _relative_path(output_md_path),
        "outputJsonPath": _relative_path(output_json_path),
    }
    report = evaluate_ablation_configs(
        tier_a_queries,
        catalog_store=catalog_store,
        problem_store=problem_store,
        concept_embedding_store=concept_embedding_store,
        problem_embedding_store=problem_embedding_store,
        client=client,
        top_k=top_k,
        problem_top_k=problem_top_k,
        max_neighborhood_chunks=max_neighborhood_chunks,
        tier_name="tierA",
        bootstrap_resamples=bootstrap_resamples,
        metadata=common_metadata,
    )
    tier_c_report = evaluate_ablation_configs(
        tier_c_queries,
        catalog_store=catalog_store,
        problem_store=problem_store,
        concept_embedding_store=concept_embedding_store,
        problem_embedding_store=problem_embedding_store,
        client=client,
        top_k=top_k,
        problem_top_k=problem_top_k,
        max_neighborhood_chunks=0,
        tier_name="tierC",
        bootstrap_resamples=bootstrap_resamples,
        metadata=common_metadata,
    )
    for key in report["ablations"]:
        report["ablations"][key]["tierC"] = tier_c_report["ablations"][key]["tierC"]
    report["comparisons"]["tierCFinalVsBaseline"] = tier_c_report["comparisons"]["finalVsBaseline"]
    report["validation"] = validation.to_json_dict()
    report["story1BaselineSummary"] = _story1_baseline_summary(DEFAULT_STORY1_BASELINE_JSON_PATH)
    return report


def write_json_report(report: Mapping[str, Any], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(_json_safe(report), ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def write_markdown_report(report: Mapping[str, Any], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(render_markdown_report(report), encoding="utf-8")


def render_markdown_report(report: Mapping[str, Any]) -> str:
    metadata = report["metadata"]
    gate = report.get("gate", {})
    lines = [
        "# RAG Enhancement Benchmark",
        "",
        "## Scope",
        "",
        "이 benchmark는 검색 결과의 문서 커버리지/다양성/분포 개선 측정이며, 답변 생성 품질 평가는 아니다.",
        "LLM generation, answer quality, Gemini answer generation 비교는 포함하지 않는다.",
        "",
        "## Metadata",
        "",
        f"- Generated at: `{metadata['generatedAt']}`",
        f"- Git commit: `{metadata.get('gitCommit') or 'unknown'}`",
        f"- Embedding mode: `{metadata['embeddingMode']}` (`callsGemini={metadata['callsGemini']}`)",
        f"- Catalog: {metadata['catalogChunkCount']} chunks / {metadata['catalogDistinctSourceCount']} sources",
        f"- Problem bank: {metadata['problemCount']} problems / {metadata['problemDistinctSourceCount']} sources",
        f"- topK: {metadata['topK']}, problemTopK: {metadata['problemTopK']}",
        f"- Output JSON: `{metadata.get('outputJsonPath', '')}`",
        f"- Output Markdown: `{metadata.get('outputMarkdownPath', '')}`",
        "",
        "## Pass/Fail Gate",
        "",
        f"- Result: **{'PASS' if gate.get('passed') else 'FAIL'}**",
        f"- Thresholds: relevance epsilon `{metadata.get('thresholds', {}).get('relevanceEpsilon', 0.02)}`, max worsened query ratio `{metadata.get('thresholds', {}).get('maxWorsenedQueryRatio', 0.1)}`",
    ]
    for check in gate.get("checks", ()):
        lines.append(f"- {check}")
    if gate.get("failures"):
        lines.append(f"- Failures: {'; '.join(gate['failures'])}")

    lines.extend(
        [
            "",
            "## Headline Metrics",
            "",
            _headline_table(report, "tierA", "conceptSearch", "topK"),
            "",
            "## Ablation",
            "",
            "### Tier A Concept Top-K",
            "",
            _ablation_table(report, "tierA", "conceptSearch", "topK"),
            "",
            "### Tier A Evidence Bundle",
            "",
            _ablation_table(report, "tierA", "conceptSearch", "evidenceBundle"),
            "",
            "### Tier A Problem Bank Top-K",
            "",
            _ablation_table(report, "tierA", "problemBankSearch", "topK"),
        ]
    )
    if "tierC" in next(iter(report["ablations"].values())):
        lines.extend(
            [
                "",
                "### Tier C Diversity Sweep",
                "",
                _ablation_table(report, "tierC", "conceptSearch", "topK", include_relevance=False),
            ]
        )

    lines.extend(["", "## Case Studies", ""])
    case_studies = report.get("caseStudies", ())
    if not case_studies:
        lines.append("- No positive distinct-source deltas were found; see JSON per-query metrics.")
    for case in case_studies:
        lines.extend(
            [
                f"### {case['queryId']}",
                "",
                f"- Query: {case['reportText']}",
                f"- Distinct sources: B0 `{case['baselineDistinctSourceCount']}` → final `{case['finalDistinctSourceCount']}`",
                "- B0:",
                _result_list(case["baselineResults"]),
                "- Final:",
                _result_list(case["finalResults"]),
                "",
            ]
        )

    lines.extend(["", "## Limitations", ""])
    for limitation in report.get("limitations", ()):
        lines.append(f"- {limitation}")
    lines.extend(
        [
            "- 현재 source-coverage golden set의 `relevanceGrades`는 비어 있어 relevance 지표는 `relevantSourcePaths` 앵커 기반 binary relevance로 계산한다.",
            "- nDCG는 binary relevance 기준이며 graded nDCG와 실제 의미 정확도 평가는 후속 real embedding + graded relevance 작업이 필요하다.",
            "- fake-hash embedding은 결정적 분포 비교에는 유용하지만 실제 의미 검색 정확도나 답변 생성 품질 개선을 강하게 주장하는 근거는 아니다.",
            "",
        ]
    )
    return "\n".join(lines)


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run fake-embedding RAG enhancement benchmark and ablation report.")
    parser.add_argument("--catalog-path", type=Path, default=DEFAULT_CATALOG_PATH)
    parser.add_argument("--problem-bank-path", type=Path, default=DEFAULT_PROBLEM_BANK_PATH)
    parser.add_argument("--queries-path", type=Path, default=DEFAULT_QUERIES_PATH)
    parser.add_argument("--tier-c-queries-path", type=Path, default=None)
    parser.add_argument("--tier-c-min-queries", type=int, default=150)
    parser.add_argument("--tier-c-max-queries", type=int, default=150)
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--problem-top-k", type=int, default=3)
    parser.add_argument("--max-neighborhood-chunks", type=int, default=8)
    parser.add_argument("--bootstrap-resamples", type=int, default=500)
    parser.add_argument("--output-md", type=Path, default=DEFAULT_BENCHMARK_MD_PATH)
    parser.add_argument("--output-json", type=Path, default=DEFAULT_BENCHMARK_JSON_PATH)
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    report = run_benchmark(
        catalog_path=args.catalog_path,
        problem_bank_path=args.problem_bank_path,
        queries_path=args.queries_path,
        tier_c_queries_path=args.tier_c_queries_path,
        tier_c_min_queries=args.tier_c_min_queries,
        tier_c_max_queries=args.tier_c_max_queries,
        top_k=args.top_k,
        problem_top_k=args.problem_top_k,
        max_neighborhood_chunks=args.max_neighborhood_chunks,
        bootstrap_resamples=args.bootstrap_resamples,
        output_md_path=args.output_md,
        output_json_path=args.output_json,
    )
    write_json_report(report, args.output_json)
    write_markdown_report(report, args.output_md)
    print(f"Wrote {args.output_json}")
    print(f"Wrote {args.output_md}")
    return 0


def _evaluate_concept_queries_for_config(
    queries: Sequence[EvalQuery],
    *,
    config: AblationConfig,
    store: ConceptCatalogStore,
    embedding_store: ConceptEmbeddingStore,
    client: FakeEmbeddingClient,
    top_k: int,
    max_neighborhood_chunks: int,
    known_source_paths: Sequence[str],
) -> dict[str, dict[str, tuple[BenchmarkItem, ...]]]:
    top_k_results: dict[str, tuple[BenchmarkItem, ...]] = {}
    bundle_results: dict[str, tuple[BenchmarkItem, ...]] = {}
    neighborhood_results: dict[str, tuple[BenchmarkItem, ...]] = {}

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
            max_same_source_neighbors=config.max_same_source_neighbors,
            min_cross_source=config.min_cross_source,
            candidate_pool=config.candidate_pool,
            mmr_lambda=config.mmr_lambda,
            max_per_source=config.max_per_source,
            max_subqueries=config.max_subqueries,
        )
        top_items = tuple(
            BenchmarkItem(
                query_id=query.query_id,
                rank=index,
                item_id=match.chunk_id,
                source_path=normalize_source_path(match.source_path, known_source_paths),
                heading_path=tuple(match.heading_path),
                field_hints=tuple(match.field_hints),
            )
            for index, match in enumerate(bundle.matches, start=1)
        )
        neighbor_items = tuple(
            BenchmarkItem(
                query_id=query.query_id,
                rank=index,
                item_id=item.chunk_id,
                source_path=normalize_source_path(item.source_path, known_source_paths),
                heading_path=tuple(item.heading_path),
                field_hints=tuple(item.field_hints),
            )
            for index, item in enumerate(bundle.neighborhood, start=1)
        )
        top_k_results[query.query_id] = top_items
        neighborhood_results[query.query_id] = neighbor_items
        bundle_results[query.query_id] = top_items + tuple(
            BenchmarkItem(
                query_id=item.query_id,
                rank=len(top_items) + index,
                item_id=item.item_id,
                source_path=item.source_path,
                heading_path=item.heading_path,
                field_hints=item.field_hints,
            )
            for index, item in enumerate(neighbor_items, start=1)
        )

    return {
        "topK": top_k_results,
        "bundle": bundle_results,
        "neighborhoods": neighborhood_results,
    }


def _evaluate_problem_queries_for_config(
    queries: Sequence[EvalQuery],
    *,
    config: AblationConfig,
    store: ProblemBankStore,
    embedding_store: ProblemEmbeddingStore,
    client: FakeEmbeddingClient,
    top_k: int,
    known_source_paths: Sequence[str],
) -> dict[str, dict[str, tuple[BenchmarkItem, ...]]]:
    searcher = ProblemBankSearcher(
        store,
        embedding_store=embedding_store if config.use_problem_hybrid else None,
        client=client if config.use_problem_hybrid else None,
    )
    results: dict[str, tuple[BenchmarkItem, ...]] = {}
    for query in queries:
        hits = searcher.search(query.report_text, limit=top_k)
        results[query.query_id] = tuple(
            BenchmarkItem(
                query_id=query.query_id,
                rank=index,
                item_id=f"problem:{hit.problem.problem_id}",
                source_path=normalize_source_path(hit.problem.source_path, known_source_paths),
                heading_path=tuple(hit.problem.heading_path),
                field_hints=tuple(hit.problem.fields),
            )
            for index, hit in enumerate(hits, start=1)
        )
    return {"topK": results}


def _metric_section(
    query_ids: Sequence[str],
    queries: Sequence[EvalQuery],
    results_by_query: Mapping[str, Sequence[BenchmarkItem]],
    *,
    total_source_paths: Sequence[str],
    embeddings_by_item_id: Mapping[str, Sequence[float]],
    k: int,
    neighborhoods_by_query: Mapping[str, Sequence[BenchmarkItem]] | None = None,
    seed_results_by_query: Mapping[str, Sequence[BenchmarkItem]] | None = None,
) -> dict[str, Any]:
    return {
        "diversity": summarize_diversity_by_query(
            query_ids,
            results_by_query,
            total_source_paths=total_source_paths,
            embeddings_by_item_id=embeddings_by_item_id,
            neighborhoods_by_query=neighborhoods_by_query,
            seed_results_by_query=seed_results_by_query,
        ),
        "relevance": summarize_relevance_by_query(
            queries,
            results_by_query,
            k=k,
        ),
    }


def _compare_ablation_sections(
    baseline: Mapping[str, Any],
    final: Mapping[str, Any],
    *,
    bootstrap_resamples: int,
    bootstrap_seed: int,
) -> dict[str, dict[str, Any]]:
    comparisons: dict[str, dict[str, Any]] = {}
    scopes = (
        ("conceptSearch.topK", baseline["conceptSearch"]["topK"], final["conceptSearch"]["topK"]),
        ("conceptSearch.evidenceBundle", baseline["conceptSearch"]["evidenceBundle"], final["conceptSearch"]["evidenceBundle"]),
        ("problemBankSearch.topK", baseline["problemBankSearch"]["topK"], final["problemBankSearch"]["topK"]),
    )
    diversity_metrics = {
        "avgDistinctSourceCount": "distinctSourceCount",
        "avgILD": "ild",
        "avgSourceHHI": "sourceHHI",
        "avgSourceGini": "sourceGini",
        "avgFieldCoverage": "fieldCoverage",
        "avgFolderCoverage": "folderCoverage",
        "avgSameSourceNeighborRatio": "sameSourceNeighborRatio",
    }
    relevance_metrics = ("recallAtK", "precisionAtK", "mrr", "ndcgAtK", "fieldHitAtK")
    for scope_name, baseline_section, final_section in scopes:
        scope_stats: dict[str, Any] = {}
        for aggregate_name, per_query_name in diversity_metrics.items():
            scope_stats[aggregate_name] = _paired_stats(
                baseline_section["diversity"]["perQuery"],
                final_section["diversity"]["perQuery"],
                per_query_name,
                bootstrap_resamples=bootstrap_resamples,
                bootstrap_seed=bootstrap_seed,
            )
        for metric in relevance_metrics:
            scope_stats[metric] = _paired_stats(
                baseline_section["relevance"]["perQuery"],
                final_section["relevance"]["perQuery"],
                metric,
                bootstrap_resamples=bootstrap_resamples,
                bootstrap_seed=bootstrap_seed,
            )
        comparisons[scope_name] = scope_stats
    return comparisons


def _paired_stats(
    baseline_rows: Sequence[Mapping[str, Any]],
    final_rows: Sequence[Mapping[str, Any]],
    metric: str,
    *,
    bootstrap_resamples: int,
    bootstrap_seed: int,
) -> dict[str, Any]:
    baseline_by_query = {row["queryId"]: float(row.get(metric, 0.0)) for row in baseline_rows}
    final_by_query = {row["queryId"]: float(row.get(metric, 0.0)) for row in final_rows}
    query_ids = tuple(query_id for query_id in baseline_by_query if query_id in final_by_query)
    return bootstrap_paired_delta(
        [baseline_by_query[query_id] for query_id in query_ids],
        [final_by_query[query_id] for query_id in query_ids],
        resamples=bootstrap_resamples,
        seed=bootstrap_seed,
    )


def _case_studies(
    queries: Sequence[EvalQuery],
    baseline_results: Mapping[str, Sequence[BenchmarkItem]],
    final_results: Mapping[str, Sequence[BenchmarkItem]],
    *,
    max_cases: int,
) -> list[dict[str, Any]]:
    query_by_id = {query.query_id: query for query in queries}
    candidates: list[tuple[int, str]] = []
    for query_id, baseline_items in baseline_results.items():
        final_items = tuple(final_results.get(query_id, ()))
        delta = _distinct_source_count(final_items) - _distinct_source_count(baseline_items)
        if delta > 0:
            candidates.append((delta, query_id))
    candidates.sort(key=lambda item: (-item[0], item[1]))

    studies: list[dict[str, Any]] = []
    for _delta, query_id in candidates[:max_cases]:
        query = query_by_id[query_id]
        baseline_items = tuple(baseline_results.get(query_id, ()))
        final_items = tuple(final_results.get(query_id, ()))
        studies.append(
            {
                "queryId": query_id,
                "reportText": query.report_text,
                "expectedFields": list(query.expected_fields),
                "expectedTopics": list(query.expected_topics),
                "baselineDistinctSourceCount": _distinct_source_count(baseline_items),
                "finalDistinctSourceCount": _distinct_source_count(final_items),
                "baselineResults": [item.to_json_dict() for item in baseline_items],
                "finalResults": [item.to_json_dict() for item in final_items],
            }
        )
    return studies


def _headline_table(report: Mapping[str, Any], tier: str, path: str, section: str) -> str:
    baseline = report["ablations"]["B0"][tier][path][section]
    final = report["ablations"]["S5_6"][tier][path][section]
    comparisons = report.get("comparisons", {}).get("finalVsBaseline", {}).get(f"{path}.{section}", {})
    rows = (
        ("Distinct source", "avgDistinctSourceCount", baseline["diversity"]["aggregate"], final["diversity"]["aggregate"]),
        ("ILD", "avgILD", baseline["diversity"]["aggregate"], final["diversity"]["aggregate"]),
        ("Source HHI", "avgSourceHHI", baseline["diversity"]["aggregate"], final["diversity"]["aggregate"]),
        ("Field coverage", "avgFieldCoverage", baseline["diversity"]["aggregate"], final["diversity"]["aggregate"]),
        ("Recall@k", "recallAtK", baseline["relevance"]["aggregate"], final["relevance"]["aggregate"]),
        ("nDCG@k", "ndcgAtK", baseline["relevance"]["aggregate"], final["relevance"]["aggregate"]),
    )
    lines = [
        "| Metric | B0 | Final | Delta | 95% CI |",
        "| --- | ---: | ---: | ---: | --- |",
    ]
    for label, key, baseline_aggregate, final_aggregate in rows:
        stats = comparisons.get(key, {})
        lines.append(
            "| "
            + " | ".join(
                (
                    label,
                    _fmt(baseline_aggregate.get(key, 0.0)),
                    _fmt(final_aggregate.get(key, 0.0)),
                    _fmt(stats.get("meanDelta", final_aggregate.get(key, 0.0) - baseline_aggregate.get(key, 0.0))),
                    f"[{_fmt(stats.get('ciLow', 0.0))}, {_fmt(stats.get('ciHigh', 0.0))}]",
                )
            )
            + " |"
        )
    return "\n".join(lines)


def _ablation_table(
    report: Mapping[str, Any],
    tier: str,
    path: str,
    section: str,
    *,
    include_relevance: bool = True,
) -> str:
    headers = [
        "Rung",
        "Distinct",
        "ILD",
        "HHI",
        "Gini",
        "Field",
        "Folder",
        "Catalog",
    ]
    if include_relevance:
        headers.extend(("Recall", "Precision", "MRR", "nDCG", "FieldHit"))
    lines = [
        "| " + " | ".join(headers) + " |",
        "| " + " | ".join("---" if index == 0 else "---:" for index, _header in enumerate(headers)) + " |",
    ]
    for key in (key for key in ("B0", "S2", "S3", "S4", "S5_6") if key in report["ablations"]):
        ablation = report["ablations"][key]
        metrics = ablation[tier][path][section]
        diversity = metrics["diversity"]["aggregate"]
        values = [
            ablation.get("label", key),
            _fmt(diversity["avgDistinctSourceCount"]),
            _fmt(diversity["avgILD"]),
            _fmt(diversity["avgSourceHHI"]),
            _fmt(diversity["avgSourceGini"]),
            _fmt(diversity["avgFieldCoverage"]),
            _fmt(diversity["avgFolderCoverage"]),
            _fmt(diversity["catalogCoverage"]),
        ]
        if include_relevance:
            relevance = metrics["relevance"]["aggregate"]
            values.extend(
                (
                    _fmt(relevance.get("recallAtK", 0.0)),
                    _fmt(relevance.get("precisionAtK", 0.0)),
                    _fmt(relevance.get("mrr", 0.0)),
                    _fmt(relevance.get("ndcgAtK", 0.0)),
                    _fmt(relevance.get("fieldHitAtK", 0.0)),
                )
            )
        lines.append("| " + " | ".join(values) + " |")
    return "\n".join(lines)


def _result_list(items: Sequence[Mapping[str, Any]]) -> str:
    if not items:
        return "  - No results"
    lines = []
    for item in items:
        heading = " > ".join(item.get("headingPath", ()))
        lines.append(
            f"  - {item['rank']}. `{item['sourcePath']}`"
            + (f" — {heading}" if heading else "")
        )
    return "\n".join(lines)


def _limitations(configs: Sequence[AblationConfig]) -> list[str]:
    result = [
        "Actual Gemini API calls are intentionally disabled; all benchmark runs use deterministic fake-hash embeddings.",
        "This report measures retrieval/source distribution, not final answer sentence generation quality.",
    ]
    for config in configs:
        result.extend(config.limitations)
    return result


def _distinct_source_count(items: Sequence[BenchmarkItem]) -> int:
    return len({item.source_path for item in items if item.source_path})


def _concept_embeddings_by_item_id(store: ConceptEmbeddingStore) -> dict[str, tuple[float, ...]]:
    return {item.chunk.chunk_id: item.embedding for item in store.items}


def _problem_embeddings_by_item_id(store: ProblemEmbeddingStore) -> dict[str, tuple[float, ...]]:
    return {f"problem:{item.problem.problem_id}": item.embedding for item in store.items}


def _catalog_source_paths(store: ConceptCatalogStore) -> tuple[str, ...]:
    return tuple(sorted({chunk.source_path for chunk in store.records}))


def _problem_source_paths(
    store: ProblemBankStore,
    *,
    known_source_paths: Sequence[str],
) -> tuple[str, ...]:
    return tuple(sorted({normalize_source_path(problem.source_path, known_source_paths) for problem in store.records}))


def _story1_baseline_summary(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}
    report = json.loads(path.read_text(encoding="utf-8"))
    result: dict[str, Any] = {"metadata": report.get("metadata", {})}
    try:
        result["tierAConceptTopK"] = report["tierA"]["conceptSearch"]["topK"]
        result["tierAConceptEvidenceBundle"] = report["tierA"]["conceptSearch"]["evidenceBundle"]
        result["tierAProblemTopK"] = report["tierA"]["problemBankSearch"]["topK"]
    except KeyError:
        pass
    return result


def _fmt(value: Any) -> str:
    try:
        return f"{float(value):.4f}"
    except (TypeError, ValueError):
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


def _json_safe(value: Any) -> Any:
    if isinstance(value, Mapping):
        return {str(key): _json_safe(item) for key, item in value.items()}
    if isinstance(value, tuple):
        return [_json_safe(item) for item in value]
    if isinstance(value, list):
        return [_json_safe(item) for item in value]
    return value


if __name__ == "__main__":
    raise SystemExit(main())
