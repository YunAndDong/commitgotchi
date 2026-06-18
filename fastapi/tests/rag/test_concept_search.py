from __future__ import annotations

import unittest

from app.rag.concept_search import (
    build_report_evidence_bundle,
    build_report_evidence_bundles,
    search_concept_chunks,
)
from app.rag.concept_store import ConceptCatalogStore
from app.rag.embedding_store import (
    ConceptEmbeddingStore,
    ConceptEmbeddingStoreIssue,
    EmbeddedConceptChunk,
)
from app.rag.schemas import ConceptChunkRecord, ConceptNeighbors
from app.scoring.schemas import ReportChunk


class FakeEmbeddingClient:
    def embed_document(self, text: str) -> tuple[float, ...]:
        return _fake_vector(text)

    def embed_query(self, text: str) -> tuple[float, ...]:
        return _fake_vector(text)


class FailingEmbeddingClient:
    def embed_document(self, text: str) -> tuple[float, ...]:
        raise AssertionError("document embedding is not used during search")

    def embed_query(self, text: str) -> tuple[float, ...]:
        raise RuntimeError("embedding service unavailable")


class StaticEmbeddingClient:
    def __init__(self, query_embedding: tuple[float, ...]):
        self.query_embedding = query_embedding

    def embed_document(self, text: str) -> tuple[float, ...]:
        raise AssertionError("document embedding is not used during search")

    def embed_query(self, text: str) -> tuple[float, ...]:
        return self.query_embedding


class ConceptSearchTest(unittest.TestCase):
    def test_embedding_search_finds_jpa_concept_as_top_result(self) -> None:
        store, embedding_store = _search_stores()

        hits = search_concept_chunks(
            "JPA N+1 Lazy Loading fetch join을 정리했다.",
            store=store,
            embedding_store=embedding_store,
            client=FakeEmbeddingClient(),
            topic_hints=("JPA N+1", "Lazy Loading", "Fetch Join"),
            field_hints=("db", "framework"),
            limit=3,
        )

        self.assertTrue(hits)
        self.assertEqual(hits[0].chunk.chunk_id, "concept:sha256:jpa")
        self.assertEqual(hits[0].search_mode, "embedding")
        payload = hits[0].to_dict()
        for key in ("chunkId", "score", "searchMode", "matchedTerms", "sourcePath", "headingPath", "text", "fieldHints"):
            self.assertIn(key, payload)

    def test_keyword_fallback_is_used_when_query_embedding_fails(self) -> None:
        store, embedding_store = _search_stores()

        hits = search_concept_chunks(
            "JPA N+1 Lazy Loading",
            store=store,
            embedding_store=embedding_store,
            client=FailingEmbeddingClient(),
            field_hints=("db", "framework"),
            limit=2,
            allow_keyword_fallback=True,
        )

        self.assertTrue(hits)
        self.assertEqual(hits[0].chunk.chunk_id, "concept:sha256:jpa")
        self.assertEqual(hits[0].search_mode, "keyword_fallback")

    def test_keyword_fallback_excludes_stale_embedding_chunks(self) -> None:
        stale = _chunk(
            "stale-jpa",
            "03-framework/jpa.md",
            ("Spring Framework", "JPA"),
            0,
            "JPA N+1 Lazy Loading fetch join stale concept.",
            ("db", "framework"),
        )
        valid = _chunk(
            "http",
            "02-network/http.md",
            ("Network", "HTTP"),
            0,
            "HTTP status codes and REST API concepts.",
            ("network",),
        )
        store = ConceptCatalogStore((stale, valid))
        embedding_store = ConceptEmbeddingStore(
            items=(EmbeddedConceptChunk(chunk=valid, embedding=_fake_vector(valid.text)),),
            issues=(
                ConceptEmbeddingStoreIssue(
                    reason="stale_content_hash",
                    chunk_id=stale.chunk_id,
                    message="stale test record",
                ),
            ),
            expected_model="fake-embedding-model",
            expected_dimensionality=4,
        )

        hits = search_concept_chunks(
            "JPA N+1 Lazy Loading",
            store=store,
            embedding_store=embedding_store,
            client=FailingEmbeddingClient(),
            field_hints=("db", "framework"),
            allow_keyword_fallback=True,
        )

        self.assertNotIn(stale.chunk_id, {hit.chunk.chunk_id for hit in hits})

    def test_report_evidence_bundles_preserve_report_chunk_ids_and_omit_scoring_fields(self) -> None:
        store, embedding_store = _search_stores()
        report_chunks = [
            ReportChunk("report:0", "JPA N+1과 Lazy Loading을 공부했다.", 0, 28, ("JPA N+1",), ("db", "framework")),
            ReportChunk("report:1", "HTTP status code와 REST API를 복습했다.", 29, 61, ("HTTP", "REST API"), ("network",)),
        ]

        bundles = build_report_evidence_bundles(
            report_chunks,
            store=store,
            embedding_store=embedding_store,
            client=FakeEmbeddingClient(),
            top_k=1,
        )
        payloads = [bundle.to_dict() for bundle in bundles]

        self.assertEqual([bundle.report_chunk_id for bundle in bundles], ["report:0", "report:1"])
        self.assertEqual(payloads[0]["matches"][0]["chunkId"], "concept:sha256:jpa")
        self.assertEqual(payloads[1]["matches"][0]["chunkId"], "concept:sha256:http")
        self.assertLessEqual(len(payloads[0]["matches"]), 1)
        for payload in payloads:
            encoded = str(payload)
            for forbidden in ("scoreDelta", "scoreAllocation", "emotion", "dailyReport", "recommendedQuizzes"):
                self.assertNotIn(forbidden, encoded)

    def test_empty_store_returns_empty_evidence_bundle(self) -> None:
        chunk = ReportChunk("report:0", "JPA N+1", 0, 7, ("JPA N+1",), ("db",))

        bundle = build_report_evidence_bundle(
            chunk,
            store=ConceptCatalogStore(),
            embedding_store=ConceptEmbeddingStore(),
            client=FakeEmbeddingClient(),
        )

        self.assertEqual(bundle.matches, ())
        self.assertEqual(bundle.neighborhood, ())

    def test_embedding_search_caps_final_hits_per_source(self) -> None:
        store, embedding_store = _diversity_search_stores()

        hits = search_concept_chunks(
            "css layout browser",
            store=store,
            embedding_store=embedding_store,
            client=StaticEmbeddingClient(_unit_vector(1.0, 1.0, 0.0)),
            limit=5,
            candidate_pool=6,
            max_per_source=2,
        )

        source_counts = _source_counts(hits)
        self.assertLessEqual(source_counts["01-css/css.md"], 2)
        self.assertGreaterEqual(len(source_counts), 2)

    def test_mmr_adds_secondary_relevant_source_before_duplicate_chunk(self) -> None:
        store, embedding_store = _diversity_search_stores()

        hits = search_concept_chunks(
            "css layout browser",
            store=store,
            embedding_store=embedding_store,
            client=StaticEmbeddingClient(_unit_vector(1.0, 1.0, 0.0)),
            limit=3,
            candidate_pool=6,
            mmr_lambda=0.7,
            max_per_source=10,
        )

        self.assertEqual(hits[0].chunk.source_path, "01-css/css.md")
        self.assertEqual(hits[1].chunk.source_path, "02-browser/rendering.md")

    def test_mmr_lambda_one_preserves_relevance_order_when_cap_allows_it(self) -> None:
        store, embedding_store = _diversity_search_stores()

        hits = search_concept_chunks(
            "css layout browser",
            store=store,
            embedding_store=embedding_store,
            client=StaticEmbeddingClient(_unit_vector(1.0, 1.0, 0.0)),
            limit=4,
            candidate_pool=6,
            mmr_lambda=1.0,
            max_per_source=10,
        )

        self.assertEqual(
            [hit.chunk.chunk_id for hit in hits],
            [
                "concept:sha256:css-0",
                "concept:sha256:css-1",
                "concept:sha256:css-2",
                "concept:sha256:browser",
            ],
        )

    def test_embedding_search_is_deterministic_for_same_catalog(self) -> None:
        store, embedding_store = _diversity_search_stores()
        kwargs = {
            "store": store,
            "embedding_store": embedding_store,
            "client": StaticEmbeddingClient(_unit_vector(1.0, 1.0, 0.0)),
            "limit": 5,
            "candidate_pool": 6,
            "max_per_source": 2,
        }

        first = search_concept_chunks("css layout browser", **kwargs)
        second = search_concept_chunks("css layout browser", **kwargs)

        self.assertEqual(
            [hit.to_dict() for hit in first],
            [hit.to_dict() for hit in second],
        )

    def test_missing_chunk_embeddings_do_not_crash_mmr_selection(self) -> None:
        store, embedding_store = _diversity_search_stores()
        partial_embedding_store = ConceptEmbeddingStore(
            items=embedding_store.items[:2] + embedding_store.items[3:],
            issues=(
                ConceptEmbeddingStoreIssue(
                    reason="missing_embedding",
                    chunk_id="concept:sha256:css-2",
                    message="missing test embedding",
                ),
            ),
            expected_model="fake-embedding-model",
            expected_dimensionality=3,
        )

        hits = search_concept_chunks(
            "css layout browser",
            store=store,
            embedding_store=partial_embedding_store,
            client=StaticEmbeddingClient(_unit_vector(1.0, 1.0, 0.0)),
            limit=4,
            candidate_pool=6,
            max_per_source=2,
        )

        self.assertTrue(hits)
        self.assertLessEqual(_source_counts(hits)["01-css/css.md"], 2)

    def test_keyword_fallback_applies_per_source_cap(self) -> None:
        chunks = (
            _chunk("css-0", "01-css/css.md", ("CSS", "Layout"), 0, "CSS layout flexbox grid cascade.", ("framework",)),
            _chunk("css-1", "01-css/css.md", ("CSS", "Box"), 1, "CSS layout box model flexbox.", ("framework",)),
            _chunk("css-2", "01-css/css.md", ("CSS", "Cascade"), 2, "CSS layout cascade specificity.", ("framework",)),
            _chunk("browser", "02-browser/rendering.md", ("Browser", "Rendering"), 0, "Browser layout uses CSS render trees.", ("framework",)),
        )
        store = ConceptCatalogStore(chunks)

        hits = search_concept_chunks(
            "css layout",
            store=store,
            embedding_store=ConceptEmbeddingStore(),
            client=FailingEmbeddingClient(),
            limit=4,
            allow_keyword_fallback=True,
            max_per_source=1,
        )

        source_counts = _source_counts(hits)
        self.assertEqual(source_counts["01-css/css.md"], 1)
        self.assertEqual(source_counts["02-browser/rendering.md"], 1)

    def test_report_evidence_bundle_passes_diversity_options_without_shape_change(self) -> None:
        store, embedding_store = _diversity_search_stores()
        report_chunk = ReportChunk(
            "report:css",
            "css layout browser",
            0,
            18,
            ("CSS Layout",),
            ("framework",),
        )

        bundle = build_report_evidence_bundle(
            report_chunk,
            store=store,
            embedding_store=embedding_store,
            client=StaticEmbeddingClient(_unit_vector(1.0, 1.0, 0.0)),
            top_k=4,
            candidate_pool=6,
            max_per_source=1,
        )
        payload = bundle.to_dict()

        match_sources = [match["sourcePath"] for match in payload["matches"]]
        self.assertEqual(len(match_sources), len(set(match_sources)))
        self.assertEqual(
            set(payload["matches"][0]),
            {"chunkId", "score", "searchMode", "matchedTerms", "sourcePath", "headingPath", "fieldHints", "text"},
        )


def _search_stores() -> tuple[ConceptCatalogStore, ConceptEmbeddingStore]:
    chunks = (
        _chunk(
            "jpa",
            "03-framework/jpa.md",
            ("Spring Framework", "JPA"),
            0,
            "JPA maps Java objects. Lazy Loading, fetch join, Hibernate, ORM, and N+1 problems are important.",
            ("db", "framework"),
        ),
        _chunk(
            "http",
            "02-network/http.md",
            ("Network", "HTTP"),
            0,
            "HTTP uses methods, status codes, headers, authentication, REST constraints, and cache control.",
            ("network",),
        ),
        _chunk(
            "process",
            "01-cs/os.md",
            ("OS", "Process and Thread"),
            0,
            "Process, thread, memory sharing, scheduling, context switching, and concurrency concepts.",
            ("cs",),
        ),
    )
    store = ConceptCatalogStore(chunks)
    items = tuple(EmbeddedConceptChunk(chunk=chunk, embedding=_fake_vector(chunk.text)) for chunk in chunks)
    return store, ConceptEmbeddingStore(items=items, expected_model="fake-embedding-model", expected_dimensionality=4)


def _diversity_search_stores() -> tuple[ConceptCatalogStore, ConceptEmbeddingStore]:
    chunks = (
        _chunk(
            "css-0",
            "01-css/css.md",
            ("CSS", "Layout"),
            0,
            "CSS layout flexbox grid cascade browser rendering.",
            ("framework",),
        ),
        _chunk(
            "css-1",
            "01-css/css.md",
            ("CSS", "Layout"),
            1,
            "CSS layout flexbox grid browser rendering repeated.",
            ("framework",),
        ),
        _chunk(
            "css-2",
            "01-css/css.md",
            ("CSS", "Layout"),
            2,
            "CSS layout cascade specificity browser rendering repeated.",
            ("framework",),
        ),
        _chunk(
            "browser",
            "02-browser/rendering.md",
            ("Browser", "Rendering"),
            0,
            "Browser rendering layout uses style and paint.",
            ("framework",),
        ),
        _chunk(
            "dom",
            "03-browser/dom.md",
            ("Browser", "DOM"),
            0,
            "DOM layout updates can trigger rendering work.",
            ("framework",),
        ),
    )
    embeddings = {
        "concept:sha256:css-0": (1.0, 0.0, 0.0),
        "concept:sha256:css-1": (1.0, 0.0, 0.0),
        "concept:sha256:css-2": (1.0, 0.0, 0.0),
        "concept:sha256:browser": (0.0, 1.0, 0.0),
        "concept:sha256:dom": _unit_vector(0.5, 0.5, 1.0),
    }
    store = ConceptCatalogStore(chunks)
    items = tuple(
        EmbeddedConceptChunk(chunk=chunk, embedding=embeddings[chunk.chunk_id])
        for chunk in chunks
    )
    return store, ConceptEmbeddingStore(items=items, expected_model="fake-embedding-model", expected_dimensionality=3)


def _chunk(
    suffix: str,
    source_path: str,
    heading_path: tuple[str, ...],
    index: int,
    text: str,
    field_hints: tuple[str, ...],
) -> ConceptChunkRecord:
    return ConceptChunkRecord(
        chunk_id=f"concept:sha256:{suffix}",
        source_path=source_path,
        source_root="/tmp/source",
        heading_path=heading_path,
        heading_level=len(heading_path),
        chunk_index=index,
        text=text,
        content_hash=f"sha256:{suffix}",
        field_hints=field_hints,
        neighbors=ConceptNeighbors(),
    )


def _source_counts(hits) -> dict[str, int]:
    counts: dict[str, int] = {}
    for hit in hits:
        counts[hit.chunk.source_path] = counts.get(hit.chunk.source_path, 0) + 1
    return counts


def _unit_vector(*values: float) -> tuple[float, ...]:
    norm = sum(value * value for value in values) ** 0.5
    if norm == 0.0:
        raise ValueError("zero vector")
    return tuple(value / norm for value in values)


def _fake_vector(text: str) -> tuple[float, ...]:
    lowered = text.lower()
    if any(term in lowered for term in ("jpa", "lazy", "fetch", "n+1", "hibernate")):
        return (1.0, 0.0, 0.0, 0.0)
    if any(term in lowered for term in ("http", "rest", "status", "network")):
        return (0.0, 1.0, 0.0, 0.0)
    if any(term in lowered for term in ("process", "thread", "context")):
        return (0.0, 0.0, 1.0, 0.0)
    return (0.0, 0.0, 0.0, 1.0)


if __name__ == "__main__":
    unittest.main()
