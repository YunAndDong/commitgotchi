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
